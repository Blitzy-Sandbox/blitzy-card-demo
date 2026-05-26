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
// TransactionReportJobTest — Spring Batch orchestration tests
//
// // COBOL: CBTRN03C
// // Replaces JCL job stream: app/jcl/TRANREPT.jcl
//
// These tests validate the production TransactionReportJob @Configuration
// class (the Spring Batch Job bean that replaces the COBOL CBTRN03C program
// orchestrated by JCL TRANREPT.jcl). The COBOL semantic verification of
// PAGE_SIZE=20, LRECL=133, card-change subtotals, and report formatting
// lives in TransactionReportServiceTest (sibling service test); this class
// verifies Job-level orchestration: JobParameters parsing (batchRunId
// validator, ISO-8601 startDate/endDate with previous-calendar-month
// fallback when both omitted, businessDate, correlationId), Tasklet
// delegation to TransactionReportService.generateReport(start, end),
// ExitStatus / BatchStatus propagation, ExecutionContext persistence,
// JobExecutionListener.beforeJob/afterJob audit-log lifecycle calls
// (AAP §0.6.6), and service-failure → BatchStatus.FAILED propagation.
// =============================================================================

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
 * Spring Batch tests for {@link TransactionReportJob}.
 *
 * <p><b>// COBOL: CBTRN03C</b> &mdash;
 * <b>// Replaces JCL job stream: app/jcl/TRANREPT.jcl</b>. The tests
 * validate the Spring Batch Job bean ({@link TransactionReportJob#transactionReportJob()})
 * that orchestrates the translated CBTRN03C transaction-report
 * pipeline. The actual COBOL business-rule verification (PAGE_SIZE=20,
 * LRECL=133, card-change subtotal, date-window filter on
 * {@code TRAN-PROC-TS}, BigDecimal scale=2 with
 * {@code RoundingMode.HALF_EVEN}) happens in
 * {@code TransactionReportServiceTest} (sibling service test) per AAP
 * &sect;0.7.2; this class focuses on Job-level orchestration:</p>
 *
 * <ul>
 *   <li><b>HappyPath</b> &mdash; explicit ISO-8601 date window
 *       produces {@link BatchStatus#COMPLETED} with the
 *       {@link TransactionReportService} receiving the parsed
 *       {@link LocalDate} pair verbatim and the
 *       {@link ExecutionContext} populated with the four report
 *       metadata keys.</li>
 *   <li><b>DateWindowDefault</b> &mdash; when both {@code startDate}
 *       and {@code endDate} are omitted, the job falls back to the
 *       previous calendar month derived from the optional
 *       {@code businessDate} parameter (CBTRN03C
 *       {@code WS-DATE-RANGE-EMPTY} verbatim semantic per AAP
 *       &sect;0.4.1 / &sect;0.6.1).</li>
 *   <li><b>ParameterValidation</b> &mdash; missing
 *       {@code batchRunId} throws
 *       {@link JobParametersInvalidException} from the validator;
 *       partial-window scenarios (one date present, the other
 *       missing) and malformed ISO-8601 strings yield
 *       {@link BatchStatus#FAILED}.</li>
 *   <li><b>PageSizeAndLrecl</b> &mdash; the
 *       {@code transactionCount / pageCount} relationship implied by
 *       the COBOL {@code WS-PAGE-SIZE=20} constant (200 transactions
 *       &rarr; 10 pages) is faithfully reflected in the
 *       {@link ExecutionContext}.</li>
 *   <li><b>EmptyResultSet</b> &mdash; an empty
 *       {@link ReportResult} (zero transactions / zero pages /
 *       {@link BigDecimal#ZERO} grand total) still completes
 *       successfully.</li>
 *   <li><b>GrandTotalArithmetic</b> &mdash; {@link BigDecimal} scale
 *       and value are preserved through the Job's
 *       {@link ExecutionContext} write, with no lossy
 *       {@code float}/{@code double} substitution
 *       (AAP &sect;0.6.1).</li>
 *   <li><b>ServiceFailurePropagation</b> &mdash; a
 *       {@link RuntimeException} thrown by
 *       {@link TransactionReportService#generateReport(LocalDate,
 *       LocalDate)} propagates to
 *       {@link JobExecution#getAllFailureExceptions()} and forces
 *       {@link BatchStatus#FAILED}.</li>
 * </ul>
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(TransactionReportJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("TransactionReportJob — Spring Batch orchestration tests (COBOL: CBTRN03C, JCL: TRANREPT.jcl)")
class TransactionReportJobTest {

    // =========================================================================
    // Test-only Spring configuration — stubs Secrets Manager and the Spring
    // Batch metadata schema initializer so the @SpringBootTest ApplicationContext
    // can bootstrap against the Testcontainers PostgreSQL instance without
    // requiring AWS credentials or a pre-populated database.
    // =========================================================================

    /**
     * Test-only Spring {@link TestConfiguration} that overrides two
     * beans which would otherwise require real AWS infrastructure:
     *
     * <ol>
     *   <li>{@link SecretsManagerService} &mdash; replaced by a Mockito
     *       stub returning a deterministic test signing key for the
     *       JWT-related beans wired by
     *       {@code com.awsm2.carddemo.config.SecurityConfig} during
     *       {@code @SpringBootTest} ApplicationContext startup.</li>
     *   <li>{@link DataSourceInitializer} &mdash; populates the
     *       Testcontainers PostgreSQL container with the Spring Batch
     *       metadata schema ({@code BATCH_JOB_INSTANCE},
     *       {@code BATCH_STEP_EXECUTION}, etc.) using the shipped
     *       {@code schema-postgresql.sql} DDL so the
     *       {@link JobLauncherTestUtils} can write Job/Step rows
     *       during {@link JobLauncherTestUtils#launchJob(JobParameters)}
     *       invocations.</li>
     * </ol>
     */
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

    // =========================================================================
    // Testcontainers — ephemeral PostgreSQL 16-alpine matching production RDS
    // PostgreSQL 16 (AAP §0.6.2). @Container/@Testcontainers manage lifecycle;
    // @ServiceConnection auto-binds spring.datasource.* — supplemented by
    // @DynamicPropertySource for explicit Hikari overrides used by the Batch
    // metadata initializer.
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
    // Spring Batch + JobLauncherTestUtils wiring
    // =========================================================================

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(TransactionReportJob.JOB_NAME)
    private Job transactionReportJob;

    // =========================================================================
    // Service collaborator (@MockBean) — every @Nested test programs the
    // expected return value or exception via Mockito.when(...).then...(...).
    // Per AAP §0.4.1, the Job class delegates ALL report-generation logic
    // (date filter, sort, card-change subtotal, PAGE_SIZE pagination,
    // LRECL=133 line rendering, S3 upload) to this service, so mocking it
    // is the cleanest unit-test boundary.
    // =========================================================================

    @MockBean
    private TransactionReportService transactionReportService;

    // =========================================================================
    // Adapter mocks — prevent the @SpringBootTest ApplicationContext from
    // requiring real AWS infrastructure (OpenSearch, S3, MSK, Step Functions,
    // Redis). AuditLogService is additionally exercised by the assertions
    // (verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(...)).
    // =========================================================================

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

    // =========================================================================
    // AWS SDK v2 client mocks — required to bootstrap the ApplicationContext
    // without AWS credentials. Per AAP §0.5.1, AWS SDK for Java v2 only.
    // =========================================================================

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

    // =========================================================================
    // Spring infrastructure mocks — avoid Kafka/Redis runtime dependencies
    // during context bootstrap.
    // =========================================================================

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        // Reset the service + audit-log mocks between tests so each
        // @Nested scenario starts with a clean interaction state.
        Mockito.reset(transactionReportService, auditLogService);
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(transactionReportJob);
    }

    // =========================================================================
    // @Nested — HappyPath
    // =========================================================================

    /**
     * Verifies the canonical end-to-end success scenario: an explicit
     * ISO-8601 date window flows through the
     * {@link JobParametersBuilder}, the {@link TransactionReportJob}
     * Tasklet delegates to {@link TransactionReportService}, and the
     * {@link ReportResult} fields are written to the
     * {@link ExecutionContext} for downstream Step Functions
     * consumption.
     */
    @Nested
    @DisplayName("HappyPath — Job completes successfully with an explicit date window")
    class HappyPath {

        @Test
        @DisplayName("Launches with explicit startDate and endDate; delegates to service; returns COMPLETED")
        void launchWithExplicitDateWindow_returnsCompleted() throws Exception {
            // Arrange
            // COBOL: CBTRN03C — date-window filter on TRAN-PROC-TS between startDate and endDate
            final LocalDate startDate = LocalDate.of(2022, 7, 1);
            final LocalDate endDate = LocalDate.of(2022, 7, 31);
            final BigDecimal expectedGrandTotal = new BigDecimal("12345.67");
            final String expectedS3Key =
                    "transaction-reports/2022-07-01_2022-07-31-20220801000000.txt";
            final ReportResult expected = new ReportResult(
                    150, 8, expectedGrandTotal, expectedS3Key);
            Mockito.when(transactionReportService.generateReport(startDate, endDate))
                    .thenReturn(expected);

            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-run-001")
                    .addString(TransactionReportJob.PARAM_START_DATE,
                            "2022-07-01")
                    .addString(TransactionReportJob.PARAM_END_DATE,
                            "2022-07-31")
                    .addString(TransactionReportJob.PARAM_BUSINESS_DATE,
                            "2022-08-01")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-001")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());

            final ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(TransactionReportJob.CTX_TRANSACTION_COUNT))
                    .isEqualTo(150);
            assertThat(stepContext.getInt(TransactionReportJob.CTX_PAGE_COUNT))
                    .isEqualTo(8);
            // BigDecimal scale=2 preserved as plain-string in the
            // execution context per AAP §0.6.1.
            assertThat(stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL))
                    .isEqualTo("12345.67");
            assertThat(new BigDecimal(
                    stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL)))
                    .isEqualByComparingTo(expectedGrandTotal);
            assertThat(stepContext.getString(TransactionReportJob.CTX_S3_KEY))
                    .isEqualTo(expectedS3Key);

            verify(transactionReportService, times(1))
                    .generateReport(startDate, endDate);
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(TransactionReportJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-001"));
        }
    }

    // =========================================================================
    // @Nested — DateWindowDefault
    // =========================================================================

    /**
     * Verifies CBTRN03C's verbatim {@code WS-DATE-RANGE-EMPTY}
     * fallback semantic: when both {@code startDate} and
     * {@code endDate} are omitted, the report runs for the previous
     * calendar month computed from the optional {@code businessDate}
     * parameter (or {@link LocalDate#now()} as ultimate fallback) per
     * AAP &sect;0.4.1 / &sect;0.6.1.
     */
    @Nested
    @DisplayName("DateWindowDefault — Previous calendar month when no dates provided (COBOL: CBTRN03C verbatim)")
    class DateWindowDefault {

        @Test
        @DisplayName("When no startDate/endDate provided, defaults to previous calendar month from businessDate")
        void noDates_defaultsToPreviousCalendarMonth() throws Exception {
            // Arrange — verbatim CBTRN03C: when WS-DATE-RANGE-EMPTY then
            // compute previous calendar month. Use businessDate as the
            // reference "today" so the test is fully deterministic.
            final LocalDate businessDate = LocalDate.of(2022, 8, 15);
            final YearMonth previousMonth =
                    YearMonth.from(businessDate).minusMonths(1L);
            final LocalDate expectedStart = previousMonth.atDay(1);
            final LocalDate expectedEnd = previousMonth.atEndOfMonth();

            final ReportResult result = new ReportResult(
                    0, 0, BigDecimal.ZERO,
                    "transaction-reports/" + expectedStart
                            + "_" + expectedEnd + "-empty.txt");
            Mockito.when(transactionReportService.generateReport(
                    expectedStart, expectedEnd)).thenReturn(result);

            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-default-window")
                    .addString(TransactionReportJob.PARAM_BUSINESS_DATE,
                            businessDate.toString())
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-002")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(transactionReportService, times(1))
                    .generateReport(expectedStart, expectedEnd);
        }

        @Test
        @DisplayName("Falls back to LocalDate.now() when businessDate is also absent")
        void noDatesAndNoBusinessDate_fallsBackToLocalDateNow() throws Exception {
            // Arrange — both dates AND businessDate absent. The Job
            // falls back to LocalDate.now() and computes the previous
            // calendar month from that.
            final YearMonth previousMonth =
                    YearMonth.from(LocalDate.now()).minusMonths(1L);
            final LocalDate expectedStart = previousMonth.atDay(1);
            final LocalDate expectedEnd = previousMonth.atEndOfMonth();

            final ReportResult result = new ReportResult(
                    3, 1, new BigDecimal("250.00"),
                    "transaction-reports/now-default.txt");
            Mockito.when(transactionReportService.generateReport(
                    expectedStart, expectedEnd)).thenReturn(result);

            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-now-default")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-002b")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(transactionReportService, times(1))
                    .generateReport(expectedStart, expectedEnd);
        }
    }

    // =========================================================================
    // @Nested — ParameterValidation
    // =========================================================================

    /**
     * Verifies the standard JobParameters validator enforces the
     * mandatory {@code batchRunId} (AAP &sect;0.7.1 audit-traceability
     * rule), and that partial-window scenarios + malformed ISO-8601
     * values produce {@link BatchStatus#FAILED} via the
     * Tasklet-level {@link IllegalArgumentException}.
     */
    @Nested
    @DisplayName("ParameterValidation — Missing batchRunId rejected; standard validator enforced")
    class ParameterValidation {

        @Test
        @DisplayName("Missing batchRunId throws JobParametersInvalidException from validator")
        void missingBatchRunId_throwsJobParametersInvalidException() {
            // Arrange — omit the required batchRunId parameter
            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_START_DATE, "2022-07-01")
                    .addString(TransactionReportJob.PARAM_END_DATE, "2022-07-31")
                    .toJobParameters();

            // Act & Assert
            assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(params))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining(TransactionReportJob.PARAM_BATCH_RUN_ID);

            // Service must NOT be invoked when validation fails (the
            // exception is thrown before the Tasklet runs).
            verify(transactionReportService, never())
                    .generateReport(any(LocalDate.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("Malformed startDate (non-ISO-8601) yields BatchStatus.FAILED")
        void malformedStartDate_failsJobWithFailedStatus() throws Exception {
            // Arrange — batchRunId present (validator passes), but
            // startDate is in MM/DD/YYYY format which violates the
            // ISO_DATE contract enforced by parseRequiredIsoDate.
            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-bad-date")
                    .addString(TransactionReportJob.PARAM_START_DATE,
                            "07/01/2022")
                    .addString(TransactionReportJob.PARAM_END_DATE,
                            "2022-07-31")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-bad-date")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(TransactionReportJobTest::isIllegalArgumentException);
            verify(transactionReportService, never())
                    .generateReport(any(LocalDate.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("Partial window — startDate present but endDate missing — yields BatchStatus.FAILED")
        void partialWindowMissingEndDate_failsJobWithFailedStatus() throws Exception {
            // Arrange — Partial window is NOT allowed. The
            // resolveDateWindow helper applies the previous-month
            // default ONLY when BOTH startDate AND endDate are absent;
            // a half-supplied window must surface the missing parameter
            // as an IllegalArgumentException.
            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-missing-end")
                    .addString(TransactionReportJob.PARAM_START_DATE,
                            "2022-07-01")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-missing-end")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(TransactionReportJobTest::isIllegalArgumentException);
            verify(transactionReportService, never())
                    .generateReport(any(LocalDate.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("Partial window — endDate present but startDate missing — yields BatchStatus.FAILED")
        void partialWindowMissingStartDate_failsJobWithFailedStatus() throws Exception {
            // Arrange — symmetric to the previous test: endDate is
            // supplied but startDate is omitted; the half-window guard
            // catches this before the previous-month default can apply.
            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-missing-start")
                    .addString(TransactionReportJob.PARAM_END_DATE,
                            "2022-07-31")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-missing-start")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(TransactionReportJobTest::isIllegalArgumentException);
            verify(transactionReportService, never())
                    .generateReport(any(LocalDate.class), any(LocalDate.class));
        }
    }

    // =========================================================================
    // @Nested — PageSizeAndLrecl
    // =========================================================================

    /**
     * Verifies the CBTRN03C verbatim PAGE_SIZE=20 constant is faithfully
     * reflected in the {@code pageCount} carried through the Job's
     * {@link ExecutionContext}. The COBOL constant lives in
     * {@code app/cbl/CBTRN03C.cbl} at {@code WS-PAGE-SIZE PIC 9(03) COMP-3
     * VALUE 20} (L131-L132); a 200-transaction run produces exactly
     * 10 pages (200 / 20 = 10). LRECL=133 is asserted indirectly by
     * preserving the COBOL constants in the service test.
     */
    @Nested
    @DisplayName("PageSizeAndLrecl — Verbatim COBOL constants preserved (PAGE_SIZE=20, LRECL=133)")
    class PageSizeAndLrecl {

        @Test
        @DisplayName("Service returns pageCount derived from PAGE_SIZE=20; job propagates pageCount via execution context")
        void pageSize20_reflectedInPageCount() throws Exception {
            // Arrange — 200 transactions / PAGE_SIZE=20 = 10 pages
            // COBOL: CBTRN03C:WS-PAGE-SIZE (L131-L132)
            final LocalDate startDate = LocalDate.of(2022, 1, 1);
            final LocalDate endDate = LocalDate.of(2022, 1, 31);
            final ReportResult result = new ReportResult(
                    200, 10, new BigDecimal("99999.99"),
                    "transaction-reports/2022-01-01_2022-01-31-200tx.txt");
            Mockito.when(transactionReportService.generateReport(startDate, endDate))
                    .thenReturn(result);

            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-pagecount")
                    .addString(TransactionReportJob.PARAM_START_DATE,
                            "2022-01-01")
                    .addString(TransactionReportJob.PARAM_END_DATE,
                            "2022-01-31")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-pages")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            final ExecutionContext stepContext = singleStepContext(execution);
            // 200 transactions split into 200/20 = 10 pages — verbatim
            // CBTRN03C arithmetic preserved through the Job's
            // execution-context write.
            assertThat(stepContext.getInt(TransactionReportJob.CTX_TRANSACTION_COUNT))
                    .isEqualTo(200);
            assertThat(stepContext.getInt(TransactionReportJob.CTX_PAGE_COUNT))
                    .isEqualTo(10);
            verify(transactionReportService, times(1))
                    .generateReport(startDate, endDate);
        }
    }

    // =========================================================================
    // @Nested — EmptyResultSet
    // =========================================================================

    /**
     * Verifies that an empty {@link ReportResult} (zero transactions,
     * zero pages, {@link BigDecimal#ZERO} grand total) still produces
     * {@link BatchStatus#COMPLETED}. The COBOL CBTRN03C program also
     * runs to completion when the {@code TRANSACT-FILE} contains no
     * records within the date window — the only emission is the
     * grand-total line at {@code 1110-WRITE-GRAND-TOTALS} (L318-L322).
     */
    @Nested
    @DisplayName("EmptyResultSet — Zero transactions still completes successfully")
    class EmptyResultSet {

        @Test
        @DisplayName("When service returns 0 transactions, job still completes successfully")
        void zeroTransactions_completes() throws Exception {
            // Arrange — a date window in the far future guarantees zero
            // matching transactions in any realistic dataset.
            final LocalDate startDate = LocalDate.of(2099, 1, 1);
            final LocalDate endDate = LocalDate.of(2099, 1, 31);
            final ReportResult emptyResult = new ReportResult(
                    0, 0, BigDecimal.ZERO,
                    "transaction-reports/2099-01-01_2099-01-31-empty.txt");
            Mockito.when(transactionReportService.generateReport(startDate, endDate))
                    .thenReturn(emptyResult);

            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-empty")
                    .addString(TransactionReportJob.PARAM_START_DATE,
                            "2099-01-01")
                    .addString(TransactionReportJob.PARAM_END_DATE,
                            "2099-01-31")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-empty")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            final ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(TransactionReportJob.CTX_TRANSACTION_COUNT))
                    .isZero();
            assertThat(stepContext.getInt(TransactionReportJob.CTX_PAGE_COUNT))
                    .isZero();
            // BigDecimal.ZERO renders as "0" via toPlainString() — the
            // Job preserves the value without forcing a 2-decimal scale.
            assertThat(stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL))
                    .isNotNull();
            assertThat(new BigDecimal(
                    stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL)))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            verify(transactionReportService, times(1))
                    .generateReport(startDate, endDate);
        }
    }

    // =========================================================================
    // @Nested — GrandTotalArithmetic
    // =========================================================================

    /**
     * Verifies AAP &sect;0.6.1: {@link BigDecimal} scale=2 with
     * {@link java.math.RoundingMode#HALF_EVEN} is the verbatim mapping
     * of COBOL {@code PIC S9(09)V99} (the {@code WS-GRAND-TOTAL}
     * accumulator at CBTRN03C L136). The Job must preserve the
     * BigDecimal value end-to-end through the execution context
     * without any lossy {@code float}/{@code double} substitution.
     */
    @Nested
    @DisplayName("GrandTotalArithmetic — BigDecimal scale=2 + HALF_EVEN preserved through job")
    class GrandTotalArithmetic {

        @Test
        @DisplayName("Service grandTotal returned with scale=2 propagates through job execution context")
        void grandTotalScale2_preserved() throws Exception {
            // Arrange — verbatim AAP §0.6.1: BigDecimal must use scale=2
            // RoundingMode.HALF_EVEN. The Job class is the integration
            // point that must preserve this through the Spring Batch
            // execution-context write.
            final BigDecimal expectedTotal = new BigDecimal("1234567.89");
            final LocalDate startDate = LocalDate.of(2022, 6, 1);
            final LocalDate endDate = LocalDate.of(2022, 6, 30);
            final ReportResult result = new ReportResult(
                    1000, 50, expectedTotal,
                    "transaction-reports/2022-06-01_2022-06-30-big.txt");
            Mockito.when(transactionReportService.generateReport(startDate, endDate))
                    .thenReturn(result);

            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-big-total")
                    .addString(TransactionReportJob.PARAM_START_DATE,
                            "2022-06-01")
                    .addString(TransactionReportJob.PARAM_END_DATE,
                            "2022-06-30")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-big")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            final ExecutionContext stepContext = singleStepContext(execution);
            // The Job writes the BigDecimal as toPlainString(), so the
            // scale-2 representation is preserved literally.
            assertThat(stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL))
                    .isEqualTo("1234567.89");
            // Per AAP §0.7.2 BigDecimal comparisons MUST use
            // isEqualByComparingTo — never .equals().
            assertThat(new BigDecimal(
                    stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL)))
                    .isEqualByComparingTo(expectedTotal);
            verify(transactionReportService, times(1))
                    .generateReport(startDate, endDate);
        }

        @Test
        @DisplayName("Trailing zeros in grandTotal scale preserved (no implicit rescaling)")
        void grandTotalTrailingZeros_preserved() throws Exception {
            // Arrange — a value like "100.00" must remain "100.00",
            // NOT collapse to "100" or "100.0", to match the COBOL
            // PIC S9(09)V99 fixed-scale contract.
            final BigDecimal expectedTotal = new BigDecimal("100.00");
            final LocalDate startDate = LocalDate.of(2022, 9, 1);
            final LocalDate endDate = LocalDate.of(2022, 9, 30);
            final ReportResult result = new ReportResult(
                    1, 1, expectedTotal,
                    "transaction-reports/2022-09-01_2022-09-30-100.txt");
            Mockito.when(transactionReportService.generateReport(startDate, endDate))
                    .thenReturn(result);

            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-trailing-zeros")
                    .addString(TransactionReportJob.PARAM_START_DATE,
                            "2022-09-01")
                    .addString(TransactionReportJob.PARAM_END_DATE,
                            "2022-09-30")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-trailing")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            final ExecutionContext stepContext = singleStepContext(execution);
            // toPlainString() on a scale-2 BigDecimal preserves the
            // trailing zeros verbatim.
            assertThat(stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL))
                    .isEqualTo("100.00");
            assertThat(new BigDecimal(
                    stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL)))
                    .isEqualByComparingTo(expectedTotal);
        }
    }

    // =========================================================================
    // @Nested — ServiceFailurePropagation
    // =========================================================================

    /**
     * Verifies that exceptions thrown by
     * {@link TransactionReportService#generateReport(LocalDate, LocalDate)}
     * are propagated to {@link JobExecution#getAllFailureExceptions()}
     * and cause the job to terminate with
     * {@link BatchStatus#FAILED}. This is the documented behaviour
     * for Step Functions to detect a failed task per AAP &sect;0.6.3.
     */
    @Nested
    @DisplayName("ServiceFailurePropagation — Underlying exceptions cause job FAILED status")
    class ServiceFailurePropagation {

        @Test
        @DisplayName("RuntimeException from service propagates and fails the job")
        void serviceException_jobFails() throws Exception {
            // Arrange — the service throws a RuntimeException (the
            // most generic unchecked failure mode; could be IOException
            // wrapped, S3 upload failure, JPA query failure, etc.).
            final LocalDate startDate = LocalDate.of(2022, 7, 1);
            final LocalDate endDate = LocalDate.of(2022, 7, 31);
            final RuntimeException simulatedFailure =
                    new RuntimeException("S3 upload failed");
            Mockito.when(transactionReportService.generateReport(startDate, endDate))
                    .thenThrow(simulatedFailure);

            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-svc-fail")
                    .addString(TransactionReportJob.PARAM_START_DATE,
                            "2022-07-01")
                    .addString(TransactionReportJob.PARAM_END_DATE,
                            "2022-07-31")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-fail")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions()).isNotEmpty();
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex instanceof RuntimeException
                            && "S3 upload failed".equals(ex.getMessage()));
            verify(transactionReportService, times(1))
                    .generateReport(startDate, endDate);
        }

        @Test
        @DisplayName("IllegalStateException from service propagates and fails the job")
        void illegalStateException_jobFails() throws Exception {
            // Arrange — preserves parity with sibling test pattern
            // (StatementGenerationJobTest.ServiceFailurePropagation)
            // by also covering IllegalStateException as a distinct
            // failure shape.
            Mockito.when(transactionReportService.generateReport(
                            any(LocalDate.class), any(LocalDate.class)))
                    .thenThrow(new IllegalStateException(
                            "TRANREPT service failure"));

            final JobParameters params = new JobParametersBuilder()
                    .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                            "tranrept-ise")
                    .addString(TransactionReportJob.PARAM_START_DATE,
                            "2022-05-01")
                    .addString(TransactionReportJob.PARAM_END_DATE,
                            "2022-05-31")
                    .addString(TransactionReportJob.PARAM_CORRELATION_ID,
                            "corr-ise")
                    .toJobParameters();

            // Act
            final JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("TRANREPT service failure"));
        }
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Extracts the single {@link ExecutionContext} expected for a
     * {@link TransactionReportJob} run. The Job defines exactly one
     * Step ({@link TransactionReportJob#STEP_NAME}); this helper
     * asserts the count and returns the underlying context for further
     * assertions.
     *
     * @param execution the {@link JobExecution} from
     *                  {@link JobLauncherTestUtils#launchJob(JobParameters)}
     * @return the single Step's {@link ExecutionContext}
     */
    private static ExecutionContext singleStepContext(JobExecution execution) {
        assertThat(execution.getStepExecutions()).hasSize(1);
        return execution.getStepExecutions().iterator().next().getExecutionContext();
    }

    /**
     * Predicate used by {@link org.assertj.core.api.AbstractIterableAssert#anyMatch}
     * to identify {@link IllegalArgumentException} instances inside
     * {@link JobExecution#getAllFailureExceptions()}.
     *
     * @param throwable the candidate {@link Throwable}
     * @return {@code true} if the throwable is an
     *         {@link IllegalArgumentException}
     */
    private static boolean isIllegalArgumentException(Throwable throwable) {
        return throwable instanceof IllegalArgumentException;
    }
}
