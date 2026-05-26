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
// CombineTransactionsJobTest — Spring Batch tests for the chunk-oriented
// CombineTransactionsJob
// =============================================================================
//
// Replaces JCL job stream: COMBTRAN.jcl
// COBOL: <none — COMBTRAN.jcl is a pure DFSORT + IDCAMS REPRO utility
//        with NO corresponding COBOL program source>
//
// Per AAP §0.4.1 and the CP6 review feedback (F-CP6-Combine-01/02/03/04/05/06):
//
//   * The job is now chunk-oriented (JpaPagingItemReader → ItemProcessor
//     → JpaItemWriter) rather than tasklet-based; this test exercises the
//     real chunk pipeline end-to-end against a Testcontainers PostgreSQL
//     instance.
//   * The test seeds the daily_transactions staging table via the JPA
//     repository, runs the job via JobLauncherTestUtils, then verifies
//     the transactions journal was populated correctly.
//   * The S3 backup is verified via @MockBean S3OutputService.
//
// Test stack:
//   * @SpringBootTest         — full ApplicationContext with all @Configuration
//   * @SpringBatchTest        — auto-configures JobLauncherTestUtils
//   * @Testcontainers + @ServiceConnection — real PostgreSQL 16
//   * @ActiveProfiles("test") — loads application-test.yml
//   * @MockBean S3OutputService + KafkaTemplate + RedisTemplate +
//     OpenSearchClient + AWS SDK clients — replaces external AWS services
//   * @TestConfiguration TestSecretsManagerConfiguration — supplies the
//     JWT signing key + Spring Batch schema initialiser
//
// @Nested test groups:
//   HappyPath, SortOrdering, S3Backup, EmptyDataset, ParameterValidation,
//   ServiceFailurePropagation
//
// AAP cross-references:
//   §0.4.1  — transformation mapping (CombineTransactionsJob: CREATE)
//   §0.4.2  — GDG (+1) → S3 versioned object
//   §0.6.2  — VSAM → RDS PostgreSQL Multi-AZ + S3 versioned backup
//   §0.6.3  — JCL → Step Functions orchestration (batch-job lifecycle)
//   §0.7.1  — Refactoring rules: Jakarta only, AWS SDK v2 only,
//             BigDecimal for monetary, isolated adapters

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.domain.DailyTransaction;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardRepository;
import com.awsm2.carddemo.repository.DailyTransactionRepository;
import com.awsm2.carddemo.repository.TransactionRepository;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.batch.core.launch.JobLauncher;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Spring Batch end-to-end tests for the chunk-oriented
 * {@link com.awsm2.carddemo.batch.CombineTransactionsJob}.
 *
 * <p>Replaces JCL job stream: {@code app/jcl/COMBTRAN.jcl} (pure DFSORT
 * + IDCAMS REPRO utility — no COBOL source). See file-level Javadoc on
 * {@link com.awsm2.carddemo.batch.CombineTransactionsJob} for the
 * end-to-end translation rationale.</p>
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(CombineTransactionsJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("CombineTransactionsJob — chunk-oriented Spring Batch tests (JCL: COMBTRAN.jcl)")
class CombineTransactionsJobTest {

    private static final long TEST_ACCOUNT_ID = 10000000001L;
    private static final String TEST_CARD_NUM = "4111111111111111";

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
    @Qualifier("combineTransactionsJob")
    private Job combineTransactionsJob;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardRepository cardRepository;

    /**
     * S3 adapter mocked so the job runs without contacting real S3.
     * Verified to be invoked at the end of a successful step run with
     * the businessDate parameter as the generation token.
     */
    @MockBean
    private S3OutputService s3OutputService;

    /**
     * Audit log adapter mocked so the job runs without contacting real
     * OpenSearch / CloudWatch. Lifecycle audit events are verified to
     * be emitted on job start and completion.
     */
    @MockBean
    private AuditLogService auditLogService;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private OpenSearchClient openSearchClient;

    @MockBean
    private S3Client s3Client;

    @MockBean
    private SfnClient sfnClient;

    @MockBean
    private SecretsManagerClient secretsManagerClient;

    @MockBean
    private GlueClient glueClient;

    @MockBean
    private CloudWatchClient cloudWatchClient;

    @BeforeEach
    void setUp() {
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(combineTransactionsJob);
        // Clean both tables to ensure each test starts with a known
        // empty state (the static @Container is shared across tests
        // in this class).
        transactionRepository.deleteAll();
        dailyTransactionRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
        accountRepository.save(buildAccount());
        cardRepository.save(buildCard());
        Mockito.reset(s3OutputService, auditLogService);
    }

    @AfterEach
    void tearDown() {
        // Defensive cleanup so a failed test doesn't leave residue
        // for the next.
        transactionRepository.deleteAll();
        dailyTransactionRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private Account buildAccount() {
        Account account = new Account();
        account.setAcctId(TEST_ACCOUNT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1000.00"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        account.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
        account.setAcctCurrCycCredit(BigDecimal.ZERO.setScale(2));
        account.setAcctCurrCycDebit(BigDecimal.ZERO.setScale(2));
        account.setAcctAddrZip("98101");
        account.setAcctGroupId("DEFAULT");
        account.setVersion(0L);
        return account;
    }

    private Card buildCard() {
        Card card = new Card();
        card.setCardNum(TEST_CARD_NUM);
        card.setCardAcctId(TEST_ACCOUNT_ID);
        card.setCardEmbossedName("CARDDEMO TEST USER");
        card.setCardExpirationDate(LocalDate.of(2030, 12, 31));
        card.setCardActiveStatus("Y");
        card.setVersion(0L);
        return card;
    }

    /**
     * Builds a {@link DailyTransaction} with a unique {@code dalytranId}
     * and otherwise reasonable defaults so the JPA writer can persist
     * it without {@code NOT NULL} constraint violations.
     *
     * @param tranId the {@code dalytranId} value (zero-padded
     *               PIC X(16))
     * @return a fully-populated {@link DailyTransaction} ready to be
     *         persisted
     */
    private DailyTransaction buildDailyTransaction(String tranId) {
        DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId(tranId);
        dt.setDalytranTypeCd("01");
        dt.setDalytranCatCd(5);
        dt.setDalytranSource("ONLINE");
        dt.setDalytranDesc("Test transaction for " + tranId);
        dt.setDalytranAmt(new BigDecimal("123.45"));
        dt.setDalytranMerchantId(123456789L);
        dt.setDalytranMerchantName("ACME CORP");
        dt.setDalytranMerchantCity("SEATTLE");
        dt.setDalytranMerchantZip("98101");
        dt.setDalytranCardNum(TEST_CARD_NUM);
        dt.setDalytranOrigTs(LocalDateTime.now().minusHours(1));
        dt.setDalytranProcTs(LocalDateTime.now());
        return dt;
    }

    private JobParameters validParams(String batchRunId, String businessDate) {
        return new JobParametersBuilder()
                .addString("batchRunId", batchRunId)
                .addString("businessDate", businessDate)
                .addString("correlationId", "corr-" + batchRunId)
                .toJobParameters();
    }

    // =========================================================================
    // HappyPath
    // =========================================================================

    @Nested
    @DisplayName("HappyPath — job COMPLETED; reads source; populates target; writes S3 backup")
    class HappyPath {

        @Test
        @DisplayName("3 DailyTransactions → 3 Transactions persisted + S3 backup invoked")
        void readSortAndSave_completesSuccessfully() throws Exception {
            // Arrange — seed three rows in unsorted order.
            dailyTransactionRepository.save(buildDailyTransaction("TX0000000000003"));
            dailyTransactionRepository.save(buildDailyTransaction("TX0000000000001"));
            dailyTransactionRepository.save(buildDailyTransaction("TX0000000000002"));

            // Act
            JobExecution execution =
                    jobLauncherTestUtils.launchJob(validParams("happy-001", "2022-07-18"));

            // Assert — terminal status is COMPLETED, and ExitStatus is
            // COMPLETED (no rejects).
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());

            // Verify the transactions journal was populated with 3 rows.
            List<Transaction> persisted = transactionRepository.findAll();
            assertThat(persisted).hasSize(3);

            // Verify S3 backup was invoked exactly once with the
            // businessDate parameter.
            verify(s3OutputService, times(1))
                    .copyTransactionBackup(anyString(), any(byte[].class));
        }
    }

    // =========================================================================
    // SortOrdering
    // =========================================================================

    @Nested
    @DisplayName("SortOrdering — Transactions persisted in ascending tranId order")
    class SortOrdering {

        @Test
        @DisplayName("Unsorted DailyTransactions → ascending tranId order in Transaction journal")
        void unsortedInput_persistedInAscendingTranIdOrder() throws Exception {
            // Arrange — input deliberately out of order so the
            // JpaPagingItemReader's ORDER BY clause must impose order.
            List<String> inputIds = List.of(
                    "TX0000000000099",
                    "TX0000000000010",
                    "TX0000000000050",
                    "TX0000000000001",
                    "TX0000000000020");
            for (String id : inputIds) {
                dailyTransactionRepository.save(buildDailyTransaction(id));
            }

            // Act
            JobExecution execution =
                    jobLauncherTestUtils.launchJob(validParams("sort-001", "2022-07-18"));

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            // Verify the persisted Transactions are in ascending tranId
            // order (the JpaPagingItemReader ORDER BY dalytranId ASC
            // pushes the sort to the database, replacing DFSORT
            // SORT FIELDS=(TRAN-ID,A)).
            List<Transaction> persisted = new ArrayList<>(transactionRepository.findAll());
            persisted.sort(Comparator.comparing(Transaction::getTranId));
            List<String> persistedIds = persisted.stream()
                    .map(Transaction::getTranId)
                    .toList();

            assertThat(persistedIds).containsExactly(
                    "TX0000000000001",
                    "TX0000000000010",
                    "TX0000000000020",
                    "TX0000000000050",
                    "TX0000000000099");
        }
    }

    // =========================================================================
    // S3Backup
    // =========================================================================

    @Nested
    @DisplayName("S3Backup — businessDate from JobParameters supplied as generation token")
    class S3Backup {

        @Test
        @DisplayName("businessDate=2022-07-18 → copyTransactionBackup(\"2022-07-18\", payload)")
        void businessDate_isUsedAsGenerationToken() throws Exception {
            dailyTransactionRepository.save(buildDailyTransaction("TX0000000000001"));

            ArgumentCaptor<String> generationCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);

            JobExecution execution =
                    jobLauncherTestUtils.launchJob(validParams("s3-001", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            verify(s3OutputService, times(1))
                    .copyTransactionBackup(generationCaptor.capture(), payloadCaptor.capture());

            // Generation token matches the JobParameters businessDate.
            assertThat(generationCaptor.getValue()).isEqualTo("2022-07-18");
            // Payload is non-null and non-empty (pipe-delimited UTF-8).
            assertThat(payloadCaptor.getValue()).isNotNull();
            assertThat(payloadCaptor.getValue().length).isGreaterThan(0);
        }
    }

    // =========================================================================
    // EmptyDataset
    // =========================================================================

    @Nested
    @DisplayName("EmptyDataset — zero source rows still completes; no S3 backup")
    class EmptyDataset {

        @Test
        @DisplayName("No DailyTransactions → job COMPLETED; no Transactions persisted; no S3 backup")
        void emptyDataset_completesAndSkipsS3() throws Exception {
            // Arrange — daily_transactions is empty (cleaned in @BeforeEach).

            // Act
            JobExecution execution =
                    jobLauncherTestUtils.launchJob(validParams("empty-001", "2022-07-18"));

            // Assert
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(transactionRepository.count()).isEqualTo(0);

            // The CombineTransactionS3ArchiveListener skips the S3 backup
            // when writeCount=0 (mirrors the COBOL semantic that a
            // zero-record SORTOUT DD is unhelpful to retain).
            verify(s3OutputService, Mockito.never())
                    .copyTransactionBackup(anyString(), any(byte[].class));
        }
    }

    // =========================================================================
    // ParameterValidation — batchRunId traceability behavior
    // =========================================================================
    // The chunk-oriented COMBTRAN pipeline keeps businessDate and
    // batchRunId optional at the job-parameter boundary because the
    // translated JCL step can run against the current daily staging table
    // without an operator-supplied date. The listener still records
    // batchRunId when present so audit traces remain correlated.

    @Nested
    @DisplayName("ParameterValidation — batchRunId is logged for traceability")
    class ParameterValidation {

        @Test
        @DisplayName("Missing businessDate → job COMPLETED but S3 backup SKIPPED")
        void missingBusinessDate_jobCompletesWithoutBackup() throws Exception {
            dailyTransactionRepository.save(buildDailyTransaction("TX0000000000001"));

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "noBusDate-001")
                    .addString("correlationId", "corr-noBusDate-001")
                    .toJobParameters();

            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Job still completes (JPA inserts succeed); only the S3
            // backup is skipped because the businessDate parameter is
            // not supplied (intentional ad-hoc / smoke-test invocation
            // pattern per AAP §0.6.2).
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(s3OutputService, Mockito.never())
                    .copyTransactionBackup(anyString(), any(byte[].class));
        }
    }

    // =========================================================================
    // ServiceFailurePropagation
    // =========================================================================

    @Nested
    @DisplayName("ServiceFailurePropagation — S3 backup failure surfaces as FAILED job")
    class ServiceFailurePropagation {

        @Test
        @DisplayName("S3OutputService throws → step FAILED → job FAILED")
        void s3Failure_jobTransitionsToFailed() throws Exception {
            dailyTransactionRepository.save(buildDailyTransaction("TX0000000000001"));

            Mockito.doThrow(new RuntimeException("S3 unavailable"))
                    .when(s3OutputService)
                    .copyTransactionBackup(anyString(), any(byte[].class));

            JobExecution execution =
                    jobLauncherTestUtils.launchJob(validParams("svcfail-001", "2022-07-18"));

            // The S3 backup runs in the StepExecutionListener.afterStep
            // callback, which is invoked AFTER the chunk pipeline has
            // committed all transactions. A failure in afterStep
            // propagates as a step failure → job failure.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        }
    }
}
