/*
 * CombineTransactionsJobIT.java — Integration Test for COMBTRAN Pipeline Stage 3
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - app/jcl/COMBTRAN.jcl (53 lines — pure JCL utility, NO COBOL program)
 *   - app/data/ASCII/dailytran.txt (301 lines — transaction fixture data patterns)
 *
 * Full Spring Batch integration test for CombineTransactionsJob — the Java equivalent
 * of COMBTRAN.jcl, Stage 3 of the 5-stage batch pipeline.
 *
 * Pipeline Position:
 *   Stage 1: POSTTRAN  (DailyTransactionPostingJob)  — validate and post daily transactions
 *   Stage 2: INTCALC   (InterestCalculationJob)       — calculate and post interest transactions
 *   Stage 3: COMBTRAN  (CombineTransactionsJob) ← THIS — sort and backup combined transactions
 *   Stage 4a: CREASTMT (StatementGenerationJob)       — generate customer statements
 *   Stage 4b: TRANREPT (TransactionReportJob)         — generate transaction reports
 *
 * The original COMBTRAN.jcl performs two steps:
 *   STEP05R (DFSORT): Concatenates TRANSACT.BKUP(0) + SYSTRAN(0), sorts by TRAN-ID ascending
 *                      SORT FIELDS=(TRAN-ID,A) with SYMNAMES TRAN-ID,1,16,CH
 *   STEP10  (IDCAMS REPRO): Loads sorted TRANSACT.COMBINED(+1) into TRANSACT.VSAM.KSDS
 *
 * In the Java/JPA world, both Stage 1 (posting) and Stage 2 (interest) write to the
 * SAME PostgreSQL transactions table. COMBTRAN's Java purpose is:
 *   1. Read all transactions via TransactionRepository.findAll()
 *   2. Sort by transaction ID ascending using TransactionCombineProcessor.TRAN_ID_COMPARATOR
 *   3. Create a sorted backup to S3 (replacing TRANSACT.COMBINED(+1) GDG generation)
 *   4. Verify combined dataset consistency for downstream stages
 *
 * This is a TASKLET-based job (not chunk-based) — the ONLY batch job with NO
 * corresponding COBOL program.
 *
 * Test verification targets:
 *   - Ascending sort order by TRAN-ID (16-char zero-padded lexicographic)
 *   - Record count preservation (no records lost or duplicated)
 *   - Graceful handling of empty transaction tables
 *   - Single-transaction edge case
 *   - Already-sorted input stability
 *   - S3 backup file creation at expected key prefix
 *
 * Per AAP §0.7.7 (LocalStack Verification Rule): zero live AWS dependencies.
 * Tests create/destroy their own S3 resources following the strict lifecycle pattern.
 *
 * Per AAP §0.8.2 (Decimal Precision Rules): all BigDecimal assertions use compareTo(),
 * never equals() — zero floating-point substitution.
 *
 * Decision Log References:
 *   D-003: S3 versioned objects for GDG replacement
 *   D-005: Spring Batch for JCL pipeline
 */
package com.cardemo.integration.batch;

// Internal imports — from depends_on_files only
import com.cardemo.batch.jobs.CombineTransactionsJob;
import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

// JUnit 5 — test framework annotations and lifecycle hooks
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Spring Boot Test — full application context loading
import org.springframework.boot.test.context.SpringBootTest;

// Spring Test — profile activation and dynamic property wiring
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Spring Batch Test — job launching and metadata utilities
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;

// Spring Batch Core — job execution and parameter types
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;

// Spring Core — synchronous task executor for deterministic test assertions
// (BatchConfig uses SimpleAsyncTaskExecutor which returns STARTING immediately)
import org.springframework.core.task.SyncTaskExecutor;

// Spring Beans — dependency injection annotations
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

// Spring JDBC — batch schema initialization (required because @EnableBatchProcessing
// in BatchConfig disables Spring Boot's BatchAutoConfiguration which would normally
// read spring.batch.jdbc.initialize-schema and create the BATCH_ metadata tables)
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

// Testcontainers — container lifecycle management
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Testcontainers — PostgreSQL 16 container
import org.testcontainers.postgresql.PostgreSQLContainer;

// Testcontainers — LocalStack container for S3 emulation
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

// AssertJ — fluent assertion library
import static org.assertj.core.api.Assertions.assertThat;

// AWS SDK v2 — S3 client for verifying backup output
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

// Java Standard Library — exact decimal precision (AAP §0.8.2)
import java.math.BigDecimal;

// Java Standard Library — collections and UUID for unique job parameters
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Java Standard Library — timestamps for test data
import java.time.LocalDateTime;

// Java Standard Library — I/O for reading S3 backup content
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// SLF4J — structured logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test for {@link CombineTransactionsJob} verifying that the Java
 * DFSORT replacement correctly sorts all transactions by transaction ID ascending
 * (SORT FIELDS=(TRAN-ID,A) with 16-char zero-padded lexicographic ordering) and
 * creates a sorted backup to S3 (replacing the TRANSACT.COMBINED GDG generation).
 *
 * <p>This test runs against a real PostgreSQL 16 container (via Testcontainers)
 * and a real LocalStack container (for S3 verification), ensuring full behavioral
 * parity with the original COMBTRAN.jcl pipeline stage.
 *
 * <h3>Test Methods</h3>
 * <ol>
 *   <li>{@link #testCombineTransactionsSortOrder()} — Verifies ascending sort by TRAN-ID</li>
 *   <li>{@link #testRecordCountPreservation()} — Verifies no records lost or duplicated</li>
 *   <li>{@link #testEmptyTransactionTable()} — Graceful handling of empty dataset</li>
 *   <li>{@link #testSingleTransaction()} — Single-record edge case</li>
 *   <li>{@link #testAlreadySortedTransactions()} — Stable sort for pre-sorted input</li>
 *   <li>{@link #testS3BackupFileCreated()} — S3 object creation verification</li>
 * </ol>
 *
 * @see CombineTransactionsJob
 * @see TransactionCombineProcessor#TRAN_ID_COMPARATOR
 * @see TransactionRepository
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@SpringBatchTest
@DisplayName("CombineTransactionsJob Integration Tests — COMBTRAN.jcl Pipeline Stage 3")
class CombineTransactionsJobIT {

    private static final Logger log = LoggerFactory.getLogger(CombineTransactionsJobIT.class);

    // -------------------------------------------------------------------------
    // Constants — S3 Bucket and Key Pattern
    // -------------------------------------------------------------------------

    /** S3 bucket for batch output — matches carddemo.s3.output-bucket property default. */
    private static final String BATCH_OUTPUT_BUCKET = "carddemo-batch-output";

    /**
     * S3 key prefix for combined transaction backups — matches
     * CombineTransactionsJob.S3_KEY_PREFIX constant. The full key pattern is:
     * {@code combined-transactions/TRANSACT-COMBINED-{yyyyMMddHHmmss}.txt}
     */
    private static final String S3_COMBINED_KEY_PREFIX = "combined-transactions/";

    // -------------------------------------------------------------------------
    // Testcontainers — PostgreSQL 16 (replaces VSAM KSDS access)
    // -------------------------------------------------------------------------

    @Container
    static PostgreSQLContainer postgresContainer =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    // -------------------------------------------------------------------------
    // Testcontainers — LocalStack (S3 service for GDG replacement backup)
    // -------------------------------------------------------------------------

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices("s3");

    // -------------------------------------------------------------------------
    // Dynamic Property Registration — Wire Testcontainers Endpoints
    // -------------------------------------------------------------------------

    /**
     * Injects dynamically allocated Testcontainers endpoints into the Spring
     * Environment, overriding the static values from application-test.yml.
     *
     * <p>PostgreSQL: Overrides {@code spring.datasource.*} properties with
     * the Testcontainers-managed PostgreSQL 16 container connection details.
     * Flyway V1-V3 migrations run against this real PostgreSQL instance.</p>
     *
     * <p>LocalStack: Overrides all AWS endpoint properties (S3, SQS, SNS)
     * with the Testcontainers-managed LocalStack container endpoint. Even
     * though this test only uses S3, all three are wired because AwsConfig
     * creates @Bean instances for all AWS clients during context startup.</p>
     *
     * @param registry the dynamic property registry for runtime injection
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL connection properties — MUST override driver-class-name because
        // application-test.yml sets it to org.testcontainers.jdbc.ContainerDatabaseDriver
        // which only accepts jdbc:tc: URLs, not the raw jdbc:postgresql: URLs from
        // PostgreSQLContainer.getJdbcUrl(). Without this override Flyway init fails.
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.auto-commit", () -> "false");

        // LocalStack S3/SQS/SNS endpoints — all required by AwsConfig @Bean initialization
        registry.add("spring.cloud.aws.s3.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.sqs.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.sns.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.credentials.access-key",
                localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key",
                localstack::getSecretKey);
        registry.add("spring.cloud.aws.region.static",
                localstack::getRegion);
    }

    // -------------------------------------------------------------------------
    // Autowired Dependencies — Spring Application Context Beans
    // -------------------------------------------------------------------------

    /** Spring Batch test utility for launching jobs and verifying execution. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Spring Batch test utility for cleaning job metadata between test runs. */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /** Transaction JPA repository for test data setup and verification. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** AWS S3 client for verifying backup file creation against LocalStack. */
    @Autowired
    private S3Client s3Client;

    /**
     * JDBC DataSource for initializing Spring Batch metadata schema.
     * Required because {@code @EnableBatchProcessing} in BatchConfig disables
     * Spring Boot's {@code BatchAutoConfiguration} which would normally read
     * {@code spring.batch.jdbc.initialize-schema=always} and create the
     * BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION, etc. tables.
     * We must run the PostgreSQL DDL script explicitly before any batch operations.
     */
    @Autowired
    private DataSource dataSource;

    /**
     * Spring Batch JobRepository for constructing a synchronous job launcher.
     * The production BatchConfig uses {@link org.springframework.core.task.SimpleAsyncTaskExecutor}
     * which returns immediately with STARTING status. For deterministic test assertions
     * we need a {@link SyncTaskExecutor} that blocks until job completion.
     */
    @Autowired
    private JobRepository jobRepository;

    /** Flag to ensure batch schema initialization runs only once per test class. */
    private static boolean batchSchemaInitialized = false;

    // -------------------------------------------------------------------------
    // Lifecycle Methods — Test Data Setup and Cleanup
    // -------------------------------------------------------------------------

    /**
     * Sets up the test environment before each test method execution.
     *
     * <p>Actions performed:
     * <ol>
     *   <li>Creates the S3 output bucket ({@code carddemo-batch-output}) in LocalStack,
     *       silently handling the case where the bucket already exists from a prior test</li>
     *   <li>Cleans Spring Batch job execution metadata to allow repeated job launches
     *       without "already completed" conflicts</li>
     *   <li>Deletes all existing transactions from the database to ensure test isolation</li>
     * </ol>
     *
     * <p>Per AAP §0.7.7: Tests create their own S3 resources — no pre-existing state.</p>
     */
    @BeforeEach
    void setUp() {
        log.info("CombineTransactionsJobIT @BeforeEach — Setting up test environment");

        // Initialize Spring Batch metadata schema if not already done.
        // @EnableBatchProcessing in BatchConfig disables Spring Boot's
        // BatchAutoConfiguration, so the spring.batch.jdbc.initialize-schema=always
        // property from application-test.yml is NOT honored. We must run the DDL
        // explicitly using ResourceDatabasePopulator with continueOnError=true
        // to handle "already exists" errors on subsequent tests.
        if (!batchSchemaInitialized) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(
                    "/org/springframework/batch/core/schema-postgresql.sql"));
            populator.setContinueOnError(true);
            populator.execute(dataSource);
            batchSchemaInitialized = true;
            log.info("Spring Batch metadata schema initialized from schema-postgresql.sql");
        }

        // Override async JobLauncher with synchronous one for deterministic test assertions.
        // The production BatchConfig creates a SimpleAsyncTaskExecutor-backed launcher
        // which returns JobExecution in STARTING status immediately. For test verification
        // we need to block until the job completes — SyncTaskExecutor runs in the calling thread.
        try {
            TaskExecutorJobLauncher syncLauncher = new TaskExecutorJobLauncher();
            syncLauncher.setJobRepository(jobRepository);
            syncLauncher.setTaskExecutor(new SyncTaskExecutor());
            syncLauncher.afterPropertiesSet();
            jobLauncherTestUtils.setJobLauncher(syncLauncher);
            log.debug("Configured synchronous JobLauncher for test execution");
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure synchronous JobLauncher", e);
        }

        // Create S3 bucket (idempotent — ignore if already exists)
        try {
            s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket(BATCH_OUTPUT_BUCKET)
                    .build());
            log.debug("Created S3 bucket: {}", BATCH_OUTPUT_BUCKET);
        } catch (Exception e) {
            // Bucket may already exist from a previous test — that's fine
            log.debug("S3 bucket already exists or creation error (expected): {}", e.getMessage());
        }

        // Clean Spring Batch job execution metadata to allow repeated runs
        jobRepositoryTestUtils.removeJobExecutions();

        // Delete all transactions for test isolation
        transactionRepository.deleteAll();
        log.info("CombineTransactionsJobIT @BeforeEach — Environment ready");
    }

    /**
     * Cleans up the test environment after each test method execution.
     *
     * <p>Actions performed:
     * <ol>
     *   <li>Removes all S3 objects from the output bucket to prevent cross-test contamination</li>
     *   <li>Cleans Spring Batch job execution metadata</li>
     *   <li>Deletes all transactions from the database</li>
     * </ol>
     *
     * <p>Per AAP §0.7.7: Tests destroy their own S3 resources — no residual state.</p>
     */
    @AfterEach
    void tearDown() {
        log.info("CombineTransactionsJobIT @AfterEach — Cleaning up test environment");

        // Remove all S3 objects from the output bucket
        try {
            ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder()
                            .bucket(BATCH_OUTPUT_BUCKET)
                            .build());
            for (S3Object obj : listResponse.contents()) {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(BATCH_OUTPUT_BUCKET)
                        .key(obj.key())
                        .build());
                log.debug("Deleted S3 object: s3://{}/{}", BATCH_OUTPUT_BUCKET, obj.key());
            }
        } catch (Exception e) {
            log.debug("S3 cleanup error (non-fatal): {}", e.getMessage());
        }

        // Clean Spring Batch metadata
        jobRepositoryTestUtils.removeJobExecutions();

        // Clean transaction data
        transactionRepository.deleteAll();

        log.info("CombineTransactionsJobIT @AfterEach — Cleanup complete");
    }

    // =========================================================================
    // Test 1: Sort Order Verification (DFSORT Replacement)
    // =========================================================================

    /**
     * Verifies that {@link CombineTransactionsJob} sorts all transactions by transaction ID
     * in ascending lexicographic order, matching COMBTRAN.jcl STEP05R DFSORT behavior:
     * {@code SORT FIELDS=(TRAN-ID,A)} with {@code SYMNAMES TRAN-ID,1,16,CH}.
     *
     * <p>Test data uses 7 deliberately unsorted Transaction records with IDs drawn from
     * {@code dailytran.txt} patterns (16-character zero-padded) plus system-generated
     * interest transaction IDs (prefix {@code 20220718}). After job execution, the S3
     * backup file is downloaded and parsed to verify ascending order.</p>
     *
     * <p>Expected ascending sort order:
     * <pre>
     * "0000000001774260" &lt; "0000000006292564" &lt; "0000000009101861" &lt;
     * "0000000010142252" &lt; "0000000021711604" &lt; "2022071800000001" &lt;
     * "2022071800000002"
     * </pre>
     *
     * <p>This validates {@link TransactionCombineProcessor#TRAN_ID_COMPARATOR}
     * ({@code Comparator.comparing(Transaction::getTranId)}) preserves COBOL CH
     * (character) sort semantics.
     */
    @Test
    @DisplayName("Should sort all transactions by transaction ID ascending (DFSORT replacement)")
    void testCombineTransactionsSortOrder() throws Exception {
        // Arrange — insert 7 transactions with deliberately UNSORTED IDs
        List<Transaction> testData = createUnsortedTestTransactions();
        transactionRepository.saveAll(testData);
        assertThat(transactionRepository.count()).isEqualTo(7);

        // Act — launch the CombineTransactionsJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — verify sort order in S3 backup file
        List<String> transactionIdsFromS3 = extractTransactionIdsFromS3Backup();
        assertThat(transactionIdsFromS3).hasSize(7);

        // Verify ascending lexicographic order (SORT FIELDS=(TRAN-ID,A))
        assertThat(transactionIdsFromS3).containsExactly(
                "0000000001774260",
                "0000000006292564",
                "0000000009101861",
                "0000000010142252",
                "0000000021711604",
                "2022071800000001",
                "2022071800000002"
        );

        // Additional verification: each ID is lexicographically >= previous
        for (int i = 1; i < transactionIdsFromS3.size(); i++) {
            assertThat(transactionIdsFromS3.get(i).compareTo(transactionIdsFromS3.get(i - 1)))
                    .as("Transaction ID at index %d ('%s') should be >= ID at index %d ('%s')",
                            i, transactionIdsFromS3.get(i), i - 1, transactionIdsFromS3.get(i - 1))
                    .isGreaterThanOrEqualTo(0);
        }

        log.info("testCombineTransactionsSortOrder — PASSED: 7 transactions sorted correctly");
    }

    // =========================================================================
    // Test 2: Record Count Preservation
    // =========================================================================

    /**
     * Verifies that {@link CombineTransactionsJob} preserves all records in the combined
     * output — no records are lost or duplicated during the sort and backup operation.
     *
     * <p>This validates the DFSORT/REPRO pipeline integrity: the number of records in the
     * S3 backup must exactly match the number of records in the transactions table.</p>
     */
    @Test
    @DisplayName("Should preserve all records in combined output (no loss or duplication)")
    void testRecordCountPreservation() throws Exception {
        // Arrange — insert 7 transactions
        List<Transaction> testData = createUnsortedTestTransactions();
        transactionRepository.saveAll(testData);
        long inputCount = transactionRepository.count();
        assertThat(inputCount).isEqualTo(7);

        // Act — launch the CombineTransactionsJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — S3 backup contains exactly 7 records (no loss, no duplication)
        List<String> transactionIdsFromS3 = extractTransactionIdsFromS3Backup();
        assertThat(transactionIdsFromS3).hasSize(7);

        // Assert — database still has exactly 7 records (no side effects)
        assertThat(transactionRepository.count()).isEqualTo(7);

        // Assert — every original transaction ID appears exactly once in S3 output
        List<Transaction> dbTransactions = transactionRepository.findAll();
        for (Transaction txn : dbTransactions) {
            assertThat(transactionIdsFromS3)
                    .as("S3 backup should contain transaction ID: %s", txn.getTranId())
                    .contains(txn.getTranId());
        }

        log.info("testRecordCountPreservation — PASSED: {} records preserved", inputCount);
    }

    // =========================================================================
    // Test 3: Empty Transaction Table
    // =========================================================================

    /**
     * Verifies that {@link CombineTransactionsJob} handles an empty transaction table
     * gracefully — the job completes successfully without errors.
     *
     * <p>In the COBOL world, an empty VSAM dataset would result in an EOF condition
     * on the first READ. The Java implementation should handle this as a normal case
     * (zero records to sort and backup).
     */
    @Test
    @DisplayName("Should handle empty transaction table gracefully")
    void testEmptyTransactionTable() throws Exception {
        // Arrange — no transactions inserted; table is empty
        assertThat(transactionRepository.count()).isZero();

        // Act — launch the CombineTransactionsJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully (graceful empty handling)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — S3 backup is either empty or contains no records
        ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BATCH_OUTPUT_BUCKET)
                        .prefix(S3_COMBINED_KEY_PREFIX)
                        .build());

        if (!listResponse.contents().isEmpty()) {
            // If a file was created, it should contain zero records
            List<String> transactionIds = extractTransactionIdsFromS3Backup();
            assertThat(transactionIds).isEmpty();
        }
        // If no file was created at all, that's also acceptable for empty input

        // Assert — database still empty
        assertThat(transactionRepository.count()).isZero();

        log.info("testEmptyTransactionTable — PASSED: Empty dataset handled gracefully");
    }

    // =========================================================================
    // Test 4: Single Transaction
    // =========================================================================

    /**
     * Verifies that {@link CombineTransactionsJob} handles a single transaction correctly.
     *
     * <p>Edge case: DFSORT with a single input record should produce a single output record
     * with no sorting needed. The S3 backup should contain exactly 1 record.</p>
     */
    @Test
    @DisplayName("Should handle single transaction correctly")
    void testSingleTransaction() throws Exception {
        // Arrange — insert exactly 1 transaction
        Transaction singleTxn = createTransaction(
                "0000000021711604",
                "01",
                (short) 5001,
                "POS TERM",
                "Single transaction test purchase",
                new BigDecimal("41.61"),
                "000456001",
                "Coffee Bean LLC",
                "Arlington",
                "22201",
                "4111222233334444",
                LocalDateTime.of(2022, 7, 14, 10, 15, 30),
                LocalDateTime.of(2022, 7, 14, 10, 15, 31)
        );
        transactionRepository.saveAll(List.of(singleTxn));
        assertThat(transactionRepository.count()).isEqualTo(1);

        // Act — launch the CombineTransactionsJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — S3 backup contains exactly 1 record
        List<String> transactionIdsFromS3 = extractTransactionIdsFromS3Backup();
        assertThat(transactionIdsFromS3).hasSize(1);
        assertThat(transactionIdsFromS3.get(0)).isEqualTo("0000000021711604");

        // Assert — database still has exactly 1 record
        assertThat(transactionRepository.count()).isEqualTo(1);

        log.info("testSingleTransaction — PASSED: Single record processed correctly");
    }

    // =========================================================================
    // Test 5: Already Sorted Transactions
    // =========================================================================

    /**
     * Verifies that {@link CombineTransactionsJob} handles already-sorted transactions
     * correctly — the output order should match the input order exactly (stable sort).
     *
     * <p>This ensures the sort is stable and doesn't shuffle records that are already
     * in the correct order. COBOL DFSORT preserves input order for equal keys.</p>
     */
    @Test
    @DisplayName("Should handle already-sorted transactions correctly (stable sort)")
    void testAlreadySortedTransactions() throws Exception {
        // Arrange — insert transactions already in ascending ID order
        List<Transaction> sortedData = new ArrayList<>();
        sortedData.add(createTransaction(
                "0000000001000001", "01", (short) 5001, "POS TERM",
                "Already sorted txn 1", new BigDecimal("10.00"),
                "000000001", "Store Alpha", "City A", "10001",
                "4111111111111111",
                LocalDateTime.of(2022, 7, 14, 8, 0, 0),
                LocalDateTime.of(2022, 7, 14, 8, 0, 1)));
        sortedData.add(createTransaction(
                "0000000002000002", "01", (short) 5002, "POS TERM",
                "Already sorted txn 2", new BigDecimal("20.00"),
                "000000002", "Store Beta", "City B", "20002",
                "4222222222222222",
                LocalDateTime.of(2022, 7, 14, 9, 0, 0),
                LocalDateTime.of(2022, 7, 14, 9, 0, 1)));
        sortedData.add(createTransaction(
                "0000000003000003", "01", (short) 5003, "POS TERM",
                "Already sorted txn 3", new BigDecimal("30.00"),
                "000000003", "Store Gamma", "City C", "30003",
                "4333333333333333",
                LocalDateTime.of(2022, 7, 14, 10, 0, 0),
                LocalDateTime.of(2022, 7, 14, 10, 0, 1)));
        transactionRepository.saveAll(sortedData);
        assertThat(transactionRepository.count()).isEqualTo(3);

        // Act — launch the CombineTransactionsJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — S3 backup preserves the already-sorted order exactly
        List<String> transactionIdsFromS3 = extractTransactionIdsFromS3Backup();
        assertThat(transactionIdsFromS3).containsExactly(
                "0000000001000001",
                "0000000002000002",
                "0000000003000003"
        );

        log.info("testAlreadySortedTransactions — PASSED: Pre-sorted order preserved");
    }

    // =========================================================================
    // Test 6: S3 Backup File Created
    // =========================================================================

    /**
     * Verifies that {@link CombineTransactionsJob} creates a sorted backup file on S3
     * at the expected key prefix ({@code combined-transactions/TRANSACT-COMBINED-*.txt}).
     *
     * <p>This validates the GDG replacement pattern (Decision D-003): the COBOL
     * {@code TRANSACT.COMBINED(+1)} GDG generation is replaced by an S3 object with
     * a timestamp-based key suffix.</p>
     *
     * <p>Verifications:
     * <ol>
     *   <li>S3 object exists with the correct key prefix</li>
     *   <li>Object content is non-empty</li>
     *   <li>Content is valid UTF-8 with line-delimited fixed-width records</li>
     *   <li>Transaction IDs are sorted in ascending order</li>
     * </ol>
     */
    @Test
    @DisplayName("Should create sorted backup file on S3 (GDG replacement)")
    void testS3BackupFileCreated() throws Exception {
        // Arrange — insert test transactions
        List<Transaction> testData = createUnsortedTestTransactions();
        transactionRepository.saveAll(testData);

        // Act — launch the CombineTransactionsJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — S3 object exists at expected key prefix
        ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BATCH_OUTPUT_BUCKET)
                        .prefix(S3_COMBINED_KEY_PREFIX)
                        .build());

        assertThat(listResponse.contents())
                .as("Should have at least one S3 object with prefix '%s'", S3_COMBINED_KEY_PREFIX)
                .isNotEmpty();

        // Assert — the key follows the expected naming pattern
        S3Object s3Object = listResponse.contents().get(0);
        assertThat(s3Object.key())
                .startsWith("combined-transactions/TRANSACT-COMBINED-")
                .endsWith(".txt");

        // Assert — object size is positive (non-empty content)
        assertThat(s3Object.size())
                .as("S3 backup file should have non-zero size")
                .isGreaterThan(0);

        // Assert — download and verify content structure
        try (ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(BATCH_OUTPUT_BUCKET)
                        .key(s3Object.key())
                        .build());
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {

            List<String> lines = reader.lines().toList();

            // Content should have 7 lines (one per transaction)
            assertThat(lines).hasSize(7);

            // Each line should be a fixed-width record of 350 characters
            for (String line : lines) {
                assertThat(line.length())
                        .as("Each record should be exactly 350 characters (LRECL=350)")
                        .isEqualTo(350);
            }

            // Extract transaction IDs (first 16 characters of each line) and verify sort
            List<String> ids = lines.stream()
                    .map(line -> line.substring(0, 16))
                    .toList();
            for (int i = 1; i < ids.size(); i++) {
                assertThat(ids.get(i).compareTo(ids.get(i - 1)))
                        .as("Record %d ID '%s' should be >= record %d ID '%s'",
                                i, ids.get(i), i - 1, ids.get(i - 1))
                        .isGreaterThanOrEqualTo(0);
            }
        }

        log.info("testS3BackupFileCreated — PASSED: S3 backup file verified");
    }

    // =========================================================================
    // Helper Methods — Test Data Construction
    // =========================================================================

    /**
     * Creates 7 Transaction records with deliberately UNSORTED transaction IDs for testing
     * the DFSORT replacement sort logic.
     *
     * <p>Transaction IDs are drawn from {@code dailytran.txt} patterns (16-character
     * zero-padded) plus system-generated interest transaction IDs with prefix
     * {@code 20220718} from Stage 2 (InterestCalculationJob).
     *
     * <p>Deliberate unsorted order:
     * <ol>
     *   <li>0000000021711604 (high real ID)</li>
     *   <li>0000000001774260 (low real ID — return item)</li>
     *   <li>0000000009101861 (mid real ID)</li>
     *   <li>0000000006292564 (mid-low real ID)</li>
     *   <li>0000000010142252 (mid-high real ID)</li>
     *   <li>2022071800000001 (system interest txn 1)</li>
     *   <li>2022071800000002 (system interest txn 2)</li>
     * </ol>
     *
     * @return a list of 7 deliberately unsorted Transaction entities
     */
    private List<Transaction> createUnsortedTestTransactions() {
        List<Transaction> transactions = new ArrayList<>();

        // Transaction 1 — high real ID (purchase, from dailytran.txt patterns)
        transactions.add(createTransaction(
                "0000000021711604",
                "01",
                (short) 5001,
                "POS TERM",
                "Purchase at Target 1 Arlington VA",
                new BigDecimal("41.61"),
                "000456001",
                "Target 1",
                "Arlington",
                "22201",
                "4111222233334444",
                LocalDateTime.of(2022, 7, 14, 10, 15, 30),
                LocalDateTime.of(2022, 7, 14, 10, 15, 31)));

        // Transaction 2 — low real ID (return item, type code 03)
        transactions.add(createTransaction(
                "0000000001774260",
                "03",
                (short) 5002,
                "POS TERM",
                "Return at BestBuy Springfield IL",
                new BigDecimal("91.90"),
                "000789002",
                "BestBuy",
                "Springfield",
                "62704",
                "4222333344445555",
                LocalDateTime.of(2022, 7, 13, 14, 22, 10),
                LocalDateTime.of(2022, 7, 13, 14, 22, 11)));

        // Transaction 3 — mid real ID (purchase)
        transactions.add(createTransaction(
                "0000000009101861",
                "01",
                (short) 5003,
                "INTERNET",
                "Online purchase at Amazon.com",
                new BigDecimal("28.17"),
                "000123003",
                "Amazon.com",
                "Seattle",
                "98101",
                "4333444455556666",
                LocalDateTime.of(2022, 7, 12, 9, 45, 0),
                LocalDateTime.of(2022, 7, 12, 9, 45, 1)));

        // Transaction 4 — mid-low real ID (purchase)
        transactions.add(createTransaction(
                "0000000006292564",
                "01",
                (short) 5004,
                "POS TERM",
                "Purchase at Walgreens Chicago IL",
                new BigDecimal("6.78"),
                "000555004",
                "Walgreens",
                "Chicago",
                "60601",
                "4444555566667777",
                LocalDateTime.of(2022, 7, 11, 16, 30, 0),
                LocalDateTime.of(2022, 7, 11, 16, 30, 1)));

        // Transaction 5 — mid-high real ID (purchase)
        transactions.add(createTransaction(
                "0000000010142252",
                "01",
                (short) 5005,
                "POS TERM",
                "Purchase at Costco Reston VA",
                new BigDecimal("45.46"),
                "000777005",
                "Costco",
                "Reston",
                "20190",
                "4555666677778888",
                LocalDateTime.of(2022, 7, 15, 11, 0, 0),
                LocalDateTime.of(2022, 7, 15, 11, 0, 1)));

        // Transaction 6 — system interest txn 1 (from Stage 2 interest calculation)
        transactions.add(createTransaction(
                "2022071800000001",
                "01",
                (short) 5001,
                "SYSTEM",
                "Interest charge for account 0000000001",
                new BigDecimal("6.25"),
                "000000000",
                "SYSTEM",
                "SYSTEM",
                "00000",
                "4111222233334444",
                LocalDateTime.of(2022, 7, 18, 0, 0, 0),
                LocalDateTime.of(2022, 7, 18, 0, 0, 1)));

        // Transaction 7 — system interest txn 2 (from Stage 2 interest calculation)
        transactions.add(createTransaction(
                "2022071800000002",
                "01",
                (short) 5002,
                "SYSTEM",
                "Interest charge for account 0000000002",
                new BigDecimal("25.00"),
                "000000000",
                "SYSTEM",
                "SYSTEM",
                "00000",
                "4222333344445555",
                LocalDateTime.of(2022, 7, 18, 0, 0, 0),
                LocalDateTime.of(2022, 7, 18, 0, 0, 1)));

        return transactions;
    }

    /**
     * Creates a fully populated {@link Transaction} entity with all required fields
     * matching the COBOL TRAN-RECORD layout (CVTRA05Y.cpy — 350 bytes).
     *
     * <p>Uses setter methods to populate all fields. The {@code tranCatCd} field is
     * {@code Short} type (matching COBOL PIC 9(04) → SMALLINT mapping).
     *
     * @param tranId          transaction identifier (PIC X(16) — 16-char zero-padded)
     * @param tranTypeCd      transaction type code (PIC X(02) — "01"=purchase, "03"=return)
     * @param tranCatCd       transaction category code (PIC 9(04) → Short)
     * @param tranSource      transaction source (PIC X(10) — "POS TERM", "INTERNET", "SYSTEM")
     * @param tranDesc        transaction description (PIC X(100))
     * @param tranAmt         transaction amount (PIC S9(09)V99 → BigDecimal scale=2)
     * @param tranMerchantId  merchant identifier (PIC 9(09))
     * @param tranMerchantName merchant name (PIC X(50))
     * @param tranMerchantCity merchant city (PIC X(50))
     * @param tranMerchantZip  merchant ZIP (PIC X(10))
     * @param tranCardNum     card number (PIC X(16) — indexed)
     * @param tranOrigTs      origination timestamp (PIC X(26) → LocalDateTime)
     * @param tranProcTs      processing timestamp (PIC X(26) → LocalDateTime)
     * @return a fully populated Transaction entity ready for persistence
     */
    private Transaction createTransaction(String tranId, String tranTypeCd, short tranCatCd,
                                           String tranSource, String tranDesc, BigDecimal tranAmt,
                                           String tranMerchantId, String tranMerchantName,
                                           String tranMerchantCity, String tranMerchantZip,
                                           String tranCardNum, LocalDateTime tranOrigTs,
                                           LocalDateTime tranProcTs) {
        Transaction txn = new Transaction();
        txn.setTranId(tranId);
        txn.setTranTypeCd(tranTypeCd);
        txn.setTranCatCd(tranCatCd);
        txn.setTranSource(tranSource);
        txn.setTranDesc(tranDesc);
        txn.setTranAmt(tranAmt);
        txn.setTranMerchantId(tranMerchantId);
        txn.setTranMerchantName(tranMerchantName);
        txn.setTranMerchantCity(tranMerchantCity);
        txn.setTranMerchantZip(tranMerchantZip);
        txn.setTranCardNum(tranCardNum);
        txn.setTranOrigTs(tranOrigTs);
        txn.setTranProcTs(tranProcTs);
        return txn;
    }

    // =========================================================================
    // Helper Methods — S3 Backup Verification
    // =========================================================================

    /**
     * Extracts transaction IDs from the S3 combined transactions backup file.
     *
     * <p>Locates the S3 object in the {@code carddemo-batch-output} bucket with
     * the {@code combined-transactions/} key prefix, downloads it, and parses
     * each fixed-width 350-byte record line to extract the first 16 characters
     * (the TRAN-ID field at offset 0, length 16).
     *
     * <p>The file format matches COBOL RECFM=FB, LRECL=350, with UTF-8 encoding
     * and newline-separated records.
     *
     * @return ordered list of transaction ID strings from the S3 backup file
     * @throws Exception if S3 access or I/O operations fail
     */
    private List<String> extractTransactionIdsFromS3Backup() throws Exception {
        // Find the S3 object with the combined-transactions prefix
        ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BATCH_OUTPUT_BUCKET)
                        .prefix(S3_COMBINED_KEY_PREFIX)
                        .build());

        if (listResponse.contents().isEmpty()) {
            return List.of();
        }

        // Use the most recent object (should be only one per test run)
        S3Object s3Object = listResponse.contents().get(listResponse.contents().size() - 1);
        log.debug("Reading S3 backup file: s3://{}/{}", BATCH_OUTPUT_BUCKET, s3Object.key());

        // Download and parse the fixed-width record file
        List<String> transactionIds = new ArrayList<>();
        try (ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(BATCH_OUTPUT_BUCKET)
                        .key(s3Object.key())
                        .build());
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    // Extract TRAN-ID: first 16 characters (offset 0, length 16)
                    String tranId = line.substring(0, Math.min(16, line.length())).trim();
                    if (!tranId.isEmpty()) {
                        transactionIds.add(tranId);
                    }
                }
            }
        }

        log.debug("Extracted {} transaction IDs from S3 backup", transactionIds.size());
        return transactionIds;
    }
}
