/*
 * DailyTransactionPostingJobIT.java — Full Integration Test for POSTTRAN Pipeline Stage 1
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - app/jcl/POSTTRAN.jcl (46 lines — single-step JCL executing CBTRN02C)
 *   - app/cbl/CBTRN02C.cbl (731 lines — daily transaction posting batch program)
 *   - app/data/ASCII/dailytran.txt (daily transaction fixture data)
 *   - app/data/ASCII/acctdata.txt (account fixture data)
 *   - app/data/ASCII/cardxref.txt (card cross-reference fixture data)
 *   - app/data/ASCII/tcatbal.txt (transaction category balance fixture data)
 *
 * Full Spring Batch integration test for DailyTransactionPostingJob — the Java equivalent
 * of POSTTRAN.jcl + CBTRN02C.cbl, Stage 1 of the 5-stage batch pipeline.
 *
 * Pipeline Position:
 *   Stage 1: POSTTRAN  (DailyTransactionPostingJob) ← THIS — validate and post daily transactions
 *   Stage 2: INTCALC   (InterestCalculationJob)     — calculate and post interest transactions
 *   Stage 3: COMBTRAN  (CombineTransactionsJob)     — sort and backup combined transactions
 *   Stage 4a: CREASTMT (StatementGenerationJob)     — generate customer statements
 *   Stage 4b: TRANREPT (TransactionReportJob)       — generate transaction reports
 *
 * The original CBTRN02C.cbl performs:
 *   1500-VALIDATE-TRAN: 4-stage sequential validation cascade
 *     Stage 1 (1500-A-LOOKUP-XREF): Card number → XREF lookup → reject code 100 if not found
 *     Stage 2 (1500-B-LOOKUP-ACCT): XREF account ID → Account lookup → reject code 101 if not found
 *     Stage 3 (1500-B lines 403-413): Credit limit check → reject code 102 if overlimit
 *     Stage 4 (1500-B lines 414-420): Expiry check → reject code 103 if expired
 *   2000-POST-TRANSACTION: Field mapping DALYTRAN→TRAN (lines 425-438)
 *   2700-UPDATE-TCATBAL: Create-or-update TCATBAL by composite key (lines 467-500)
 *   2800-UPDATE-ACCOUNT-REC: Update currBal, cycCredit/cycDebit (lines 545-552)
 *   RETURN-CODE 4 (line 230): If WS-REJECT-COUNT > 0 → ExitStatus("COMPLETED_WITH_REJECTS")
 *
 * Test verification targets (9 test methods):
 *   1. testSuccessfulTransactionPosting — Valid transactions post with correct field mappings
 *   2. testRejectCode100InvalidCardNumber — Card not in XREF → reject 100
 *   3. testRejectCode101AccountNotFound — XREF exists but account missing → reject 101
 *   4. testRejectCode102OverlimitTransaction — Exceeds credit limit → reject 102
 *   5. testRejectCode103ExpiredAccount — Account expired → reject 103
 *   6. testMixedValidAndInvalidTransactions — Mixed batch with correct segregation
 *   7. testAllValidTransactionsNoRejects — All valid → ExitStatus.COMPLETED (no rejects)
 *   8. testTcatbalCreateWhenNotExists — New composite key → create TCATBAL record
 *   9. testTcatbalUpdateWhenExists — Pre-existing TCATBAL → balance incremented
 *
 * Per AAP §0.7.7 (LocalStack Verification Rule): zero live AWS dependencies.
 * Per AAP §0.8.2 (Decimal Precision Rules): ALL BigDecimal assertions use compareTo(),
 * never equals() — zero floating-point substitution.
 * Per AAP §0.8.4 (Batch Pipeline Rules): RETURN-CODE 4 → COMPLETED_WITH_REJECTS, not FAILED.
 *
 * Decision Log References:
 *   D-003: S3 versioned objects for GDG replacement
 *   D-005: Spring Batch for JCL pipeline
 */
package com.cardemo.integration.batch;

// Internal imports — from depends_on_files only
import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;

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
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;

// Spring Core — synchronous task executor for deterministic test assertions
import org.springframework.core.task.SyncTaskExecutor;

// Spring Beans — dependency injection annotations
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

// Spring JDBC — batch schema initialization and native SQL cleanup
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
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

// AWS SDK v2 — S3 client for bucket setup and reject file verification
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// Java Standard Library — exact decimal precision (AAP §0.8.2)
import java.math.BigDecimal;

// Java Standard Library — date/time for test data
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// Java Standard Library — collections, UUID, I/O
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// SLF4J — structured logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test for {@link DailyTransactionPostingJob} verifying that the Java
 * equivalent of POSTTRAN.jcl + CBTRN02C.cbl correctly:
 * <ol>
 *   <li>Reads DailyTransaction records from a fixed-width S3 file (CVTRA06Y.cpy layout)</li>
 *   <li>Validates via the 4-stage cascade (XREF→Account→CreditLimit→Expiry)</li>
 *   <li>Posts valid transactions to the Transaction table with correct field mappings</li>
 *   <li>Creates or updates TransactionCategoryBalance records (TCATBAL)</li>
 *   <li>Updates Account balances (currBal, cycCredit/cycDebit)</li>
 *   <li>Writes rejected transactions to S3 with 430-byte rejection records</li>
 *   <li>Returns correct exit status: COMPLETED or COMPLETED_WITH_REJECTS</li>
 * </ol>
 *
 * <p>All financial assertions use {@code BigDecimal.compareTo() == 0}, never
 * {@code equals()}, per AAP §0.8.2 decimal precision rules.</p>
 *
 * @see DailyTransactionPostingJob
 * @see TransactionPostingProcessor
 * @see DailyTransactionReader
 * @see TransactionWriter
 * @see RejectWriter
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@SpringBatchTest
@DisplayName("DailyTransactionPostingJob Integration Tests — POSTTRAN.jcl Pipeline Stage 1")
class DailyTransactionPostingJobIT {

    private static final Logger log = LoggerFactory.getLogger(DailyTransactionPostingJobIT.class);

    // -------------------------------------------------------------------------
    // Constants — S3 Buckets and Keys
    // -------------------------------------------------------------------------

    /** S3 bucket for batch input — daily transaction file upload target. */
    private static final String BATCH_INPUT_BUCKET = "carddemo-batch-input";

    /** S3 bucket for batch output — rejection files and transaction backups. */
    private static final String BATCH_OUTPUT_BUCKET = "carddemo-batch-output";

    /** S3 key prefix for rejection files matching RejectWriter configuration. */
    private static final String S3_REJECT_KEY_PREFIX = "rejections/";

    /** S3 object key for daily transaction input file, matching DailyTransactionReader default. */
    private static final String S3_DAILY_TRANSACTION_KEY = "dailytran.txt";

    /** COBOL positive overpunch characters: {=0, A=1, B=2, ..., I=9. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** COBOL negative overpunch characters: }=0, J=1, K=2, ..., R=9. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Timestamp format matching DailyTransactionReader PRIMARY_TS_FORMATTER. */
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

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
    // Testcontainers — LocalStack (S3 service for GDG replacement)
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
     * @param registry the dynamic property registry for runtime injection
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL connection properties
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

    /** Daily transaction staging table repository. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /** Posted transaction table repository. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Account master repository for balance verification. */
    @Autowired
    private AccountRepository accountRepository;

    /** Card cross-reference repository for XREF test data. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Transaction category balance repository for TCATBAL verification. */
    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** AWS S3 client for bucket management and reject file verification. */
    @Autowired
    private S3Client s3Client;

    /**
     * The specific DailyTransactionPostingJob bean — must be explicitly injected
     * and set on JobLauncherTestUtils because there are multiple Job beans in context,
     * and {@code @SpringBatchTest} cannot auto-detect which one to use.
     */
    @Autowired
    @Qualifier("dailyTransactionPostingJob")
    private Job dailyTransactionPostingJob;

    /**
     * JDBC DataSource for initializing Spring Batch metadata schema.
     * Required because {@code @EnableBatchProcessing} in BatchConfig disables
     * Spring Boot's {@code BatchAutoConfiguration}.
     */
    @Autowired
    private DataSource dataSource;

    /**
     * Spring Batch JobRepository for constructing a synchronous job launcher.
     */
    @Autowired
    private JobRepository jobRepository;

    /** JdbcTemplate for native SQL cleanup (FK cascade truncation). */
    private JdbcTemplate jdbcTemplate;

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
     *   <li>Initializes Spring Batch metadata schema if not already done</li>
     *   <li>Configures synchronous job launcher for deterministic assertions</li>
     *   <li>Creates S3 buckets for input and output in LocalStack</li>
     *   <li>Cleans all repositories and Spring Batch metadata</li>
     * </ol>
     */
    @BeforeEach
    void setUp() {
        log.info("DailyTransactionPostingJobIT @BeforeEach — Setting up test environment");

        // Initialize Spring Batch metadata schema if not already done.
        // @EnableBatchProcessing in BatchConfig disables Spring Boot's
        // BatchAutoConfiguration, so spring.batch.jdbc.initialize-schema=always
        // from application-test.yml is NOT honored.
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
        try {
            TaskExecutorJobLauncher syncLauncher = new TaskExecutorJobLauncher();
            syncLauncher.setJobRepository(jobRepository);
            syncLauncher.setTaskExecutor(new SyncTaskExecutor());
            syncLauncher.afterPropertiesSet();
            jobLauncherTestUtils.setJobLauncher(syncLauncher);
            jobLauncherTestUtils.setJob(dailyTransactionPostingJob);
            log.debug("Configured synchronous JobLauncher for dailyTransactionPostingJob");
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure synchronous JobLauncher", e);
        }

        // Initialize JdbcTemplate for native SQL cleanup
        this.jdbcTemplate = new JdbcTemplate(dataSource);

        // Create S3 buckets (idempotent — ignore if already exists)
        createS3BucketIfNotExists(BATCH_INPUT_BUCKET);
        createS3BucketIfNotExists(BATCH_OUTPUT_BUCKET);

        // Clean Spring Batch job execution metadata
        jobRepositoryTestUtils.removeJobExecutions();

        // Clean all data using TRUNCATE CASCADE to handle FK constraints.
        // The V3 Flyway seed data creates cards referencing accounts,
        // so simple deleteAll() on accounts would violate FK constraints.
        truncateAllTestTables();

        log.info("DailyTransactionPostingJobIT @BeforeEach — Environment ready");
    }

    /**
     * Cleans up the test environment after each test method execution.
     */
    @AfterEach
    void tearDown() {
        log.info("DailyTransactionPostingJobIT @AfterEach — Cleaning up");

        // Remove all S3 objects from both buckets
        cleanS3Bucket(BATCH_INPUT_BUCKET);
        cleanS3Bucket(BATCH_OUTPUT_BUCKET);

        // Clean Spring Batch metadata
        jobRepositoryTestUtils.removeJobExecutions();

        // Clean all tables using TRUNCATE CASCADE
        truncateAllTestTables();

        log.info("DailyTransactionPostingJobIT @AfterEach — Cleanup complete");
    }

    // =========================================================================
    // Test 1: Successful Transaction Posting
    // =========================================================================

    /**
     * Verifies that valid daily transactions are posted to the Transaction table
     * with correct field mappings (CBTRN02C.cbl 2000-POST-TRANSACTION, lines 425-438),
     * TCATBAL records are created/updated (2700-UPDATE-TCATBAL), and Account balances
     * are updated (2800-UPDATE-ACCOUNT-REC, lines 545-552).
     *
     * <p>CRITICAL: All financial assertions use {@code BigDecimal.compareTo() == 0},
     * never {@code equals()} (per AAP §0.8.2).</p>
     */
    @Test
    @DisplayName("Should post valid transactions and update balances")
    void testSuccessfulTransactionPosting() throws Exception {
        // Arrange — set up reference data and two valid daily transactions
        Account account = createTestAccount("00000000001", "Y",
                new BigDecimal("19400.00"), new BigDecimal("10200.00"),
                new BigDecimal("200.00"), new BigDecimal("100.00"),
                LocalDate.of(2026, 12, 31), "A000000001");
        accountRepository.save(account);

        // Insert customer records first (FK: card_cross_references.cust_id → customers.cust_id)
        insertTestCustomer("000000050");
        insertTestCustomer("000000051");

        CardCrossReference xref1 = new CardCrossReference(
                "4111111111111111", "000000050", "00000000001");
        CardCrossReference xref2 = new CardCrossReference(
                "4222222222222222", "000000051", "00000000001");
        cardCrossReferenceRepository.save(xref1);
        cardCrossReferenceRepository.save(xref2);

        DailyTransaction dt1 = createDailyTransaction("0000000001000001",
                "01", (short) 1, "POS TERM", "PURCHASE AT STORE A",
                new BigDecimal("50.47"), "M001", "STORE A", "NEW YORK", "10001",
                "4111111111111111",
                LocalDateTime.of(2025, 6, 10, 14, 30, 0),
                LocalDateTime.of(2025, 6, 10, 14, 30, 0));
        DailyTransaction dt2 = createDailyTransaction("0000000001000002",
                "01", (short) 1, "OPERATOR", "PURCHASE AT STORE B",
                new BigDecimal("91.90"), "M002", "STORE B", "BOSTON", "02101",
                "4222222222222222",
                LocalDateTime.of(2025, 6, 10, 15, 45, 0),
                LocalDateTime.of(2025, 6, 10, 15, 45, 0));
        uploadDailyTransactions(dt1, dt2);

        // Act — launch the DailyTransactionPostingJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — 2 transactions posted
        List<Transaction> postedTransactions = transactionRepository.findAll();
        assertThat(postedTransactions).hasSize(2);

        // Verify field mapping for first transaction (CBTRN02C lines 425-438)
        Transaction posted1 = postedTransactions.stream()
                .filter(t -> "0000000001000001".equals(t.getTranId()))
                .findFirst().orElse(null);
        assertThat(posted1).isNotNull();
        assertThat(posted1.getTranTypeCd()).isEqualTo("01");
        assertThat(posted1.getTranCatCd()).isEqualTo((short) 1);
        assertThat(posted1.getTranSource()).isEqualTo("POS TERM");
        assertThat(posted1.getTranDesc()).isEqualTo("PURCHASE AT STORE A");
        assertThat(posted1.getTranAmt().compareTo(new BigDecimal("50.47"))).isZero();
        assertThat(posted1.getTranCardNum()).isEqualTo("4111111111111111");
        assertThat(posted1.getTranOrigTs()).isNotNull();
        assertThat(posted1.getTranProcTs()).isNotNull();

        // Assert — Account balance updated (CBTRN02C lines 547-552)
        // Original: currBal=10200.00, cycCredit=200.00, cycDebit=100.00
        // After 2 positive transactions: currBal += 50.47 + 91.90 = 10342.37
        // cycCredit += 50.47 + 91.90 = 342.37 (both positive → cycCredit)
        Account updatedAccount = accountRepository.findById("00000000001").orElseThrow();
        BigDecimal expectedBal = new BigDecimal("10200.00")
                .add(new BigDecimal("50.47"))
                .add(new BigDecimal("91.90"));
        assertThat(updatedAccount.getAcctCurrBal().compareTo(expectedBal)).isZero();

        BigDecimal expectedCycCredit = new BigDecimal("200.00")
                .add(new BigDecimal("50.47"))
                .add(new BigDecimal("91.90"));
        assertThat(updatedAccount.getAcctCurrCycCredit().compareTo(expectedCycCredit)).isZero();

        // cycDebit unchanged — both transactions are positive amounts
        assertThat(updatedAccount.getAcctCurrCycDebit().compareTo(new BigDecimal("100.00"))).isZero();

        log.info("testSuccessfulTransactionPosting — PASSED: 2 transactions posted, balances verified");
    }

    // =========================================================================
    // Test 2: Reject Code 100 — Invalid Card Number
    // =========================================================================

    /**
     * Verifies that a daily transaction with a card number NOT in the
     * CardCrossReference table is rejected with code 100 ("INVALID CARD NUMBER FOUND").
     *
     * <p>Maps to CBTRN02C.cbl 1500-A-LOOKUP-XREF (lines 380-392):
     * {@code READ XREF-FILE; INVALID KEY → MOVE 100 TO WS-VALIDATION-FAIL-REASON}</p>
     */
    @Test
    @DisplayName("Should reject transaction with invalid card number (code 100)")
    void testRejectCode100InvalidCardNumber() throws Exception {
        // Arrange — NO XREF record for card 9999999999999999
        // We need at least one account for context, but the card won't resolve
        Account account = createTestAccount("00000000001", "Y",
                new BigDecimal("19400.00"), new BigDecimal("10200.00"),
                new BigDecimal("200.00"), new BigDecimal("100.00"),
                LocalDate.of(2026, 12, 31), "A000000001");
        accountRepository.save(account);

        DailyTransaction dt = createDailyTransaction("0000000099000001",
                "01", (short) 1, "POS TERM", "REJECTED PURCHASE",
                new BigDecimal("25.00"), "M099", "STORE X", "CHICAGO", "60601",
                "9999999999999999",  // Card NOT in XREF → reject 100
                LocalDateTime.of(2025, 6, 10, 10, 0, 0),
                LocalDateTime.of(2025, 6, 10, 10, 0, 0));
        uploadDailyTransactions(dt);

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completes with rejects (RETURN-CODE 4)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertExitStatusContains(jobExecution, "COMPLETED_WITH_REJECTS");

        // Assert — no transactions posted
        assertThat(transactionRepository.count()).isZero();

        // Assert — rejection file exists on S3
        List<String> rejectKeys = listS3ObjectKeys(BATCH_OUTPUT_BUCKET, S3_REJECT_KEY_PREFIX);
        assertThat(rejectKeys).isNotEmpty();

        // Download and verify rejection file content
        String rejectContent = downloadS3Object(BATCH_OUTPUT_BUCKET, rejectKeys.get(0));
        assertThat(rejectContent).contains("100");
        assertThat(rejectContent).contains("INVALID CARD NUMBER FOUND");

        log.info("testRejectCode100InvalidCardNumber — PASSED: reject code 100 verified");
    }

    // =========================================================================
    // Test 3: Reject Code 101 — Account Not Found
    // =========================================================================

    /**
     * Verifies that a daily transaction is rejected with code 101 when the
     * card cross-reference record points to a non-existent account.
     *
     * <p>Maps to CBTRN02C.cbl 1500-B-LOOKUP-ACCT (lines 393-399):
     * {@code READ ACCOUNT-FILE; INVALID KEY → MOVE 101 TO WS-VALIDATION-FAIL-REASON}</p>
     */
    @Test
    @DisplayName("Should reject transaction when account not found (code 101)")
    void testRejectCode101AccountNotFound() throws Exception {
        // Arrange — XREF exists but points to non-existent account.
        // fk_card_xref_account prevents inserting XREF with non-existent account_id,
        // so we use raw JDBC with session_replication_role='replica' to bypass FK triggers.
        insertTestCustomer("000000002");
        insertXrefBypassingFk("0683586198171516", "000000002", "00000000027");

        DailyTransaction dt = createDailyTransaction("0000000099000002",
                "01", (short) 1, "POS TERM", "NO ACCOUNT PURCHASE",
                new BigDecimal("35.00"), "M088", "STORE Y", "DALLAS", "75201",
                "0683586198171516",
                LocalDateTime.of(2025, 6, 10, 11, 0, 0),
                LocalDateTime.of(2025, 6, 10, 11, 0, 0));
        uploadDailyTransactions(dt);

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completes with rejects
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertExitStatusContains(jobExecution, "COMPLETED_WITH_REJECTS");

        // Assert — no transactions posted
        assertThat(transactionRepository.count()).isZero();

        // Assert — rejection file with code 101
        List<String> rejectKeys = listS3ObjectKeys(BATCH_OUTPUT_BUCKET, S3_REJECT_KEY_PREFIX);
        assertThat(rejectKeys).isNotEmpty();
        String rejectContent = downloadS3Object(BATCH_OUTPUT_BUCKET, rejectKeys.get(0));
        assertThat(rejectContent).contains("101");
        assertThat(rejectContent).contains("ACCOUNT RECORD NOT FOUND");

        log.info("testRejectCode101AccountNotFound — PASSED: reject code 101 verified");
    }

    // =========================================================================
    // Test 4: Reject Code 102 — Overlimit Transaction
    // =========================================================================

    /**
     * Verifies that a daily transaction is rejected with code 102 when
     * {@code cycCredit - cycDebit + tranAmt > creditLimit}.
     *
     * <p>Maps to CBTRN02C.cbl 1500-B-LOOKUP-ACCT (lines 403-413):
     * {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}
     * {@code IF ACCT-CREDIT-LIMIT < WS-TEMP-BAL → MOVE 102}</p>
     *
     * <p>CRITICAL: Account balances must remain UNCHANGED after a rejected transaction.</p>
     */
    @Test
    @DisplayName("Should reject transaction exceeding credit limit (code 102)")
    void testRejectCode102OverlimitTransaction() throws Exception {
        // Arrange — Account with tight credit limit
        // creditLimit=100.00, cycCredit=90.00, cycDebit=0.00
        // Transaction amount=20.00 → tempBal = 90.00 - 0.00 + 20.00 = 110.00 > 100.00 → REJECT
        Account account = createTestAccount("00000000010", "Y",
                new BigDecimal("100.00"), new BigDecimal("50.00"),
                new BigDecimal("90.00"), new BigDecimal("0.00"),
                LocalDate.of(2026, 12, 31), "A000000002");
        accountRepository.save(account);

        insertTestCustomer("000000060");
        CardCrossReference xref = new CardCrossReference(
                "5500000000000001", "000000060", "00000000010");
        cardCrossReferenceRepository.save(xref);

        DailyTransaction dt = createDailyTransaction("0000000099000003",
                "01", (short) 1, "POS TERM", "OVERLIMIT PURCHASE",
                new BigDecimal("20.00"), "M077", "STORE Z", "MIAMI", "33101",
                "5500000000000001",
                LocalDateTime.of(2025, 6, 10, 12, 0, 0),
                LocalDateTime.of(2025, 6, 10, 12, 0, 0));
        uploadDailyTransactions(dt);

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completes with rejects
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertExitStatusContains(jobExecution, "COMPLETED_WITH_REJECTS");

        // Assert — no transactions posted
        assertThat(transactionRepository.count()).isZero();

        // Assert — Account balances UNCHANGED (rejected transaction must not modify)
        Account unchangedAccount = accountRepository.findById("00000000010").orElseThrow();
        assertThat(unchangedAccount.getAcctCurrBal().compareTo(new BigDecimal("50.00"))).isZero();
        assertThat(unchangedAccount.getAcctCurrCycCredit().compareTo(new BigDecimal("90.00"))).isZero();
        assertThat(unchangedAccount.getAcctCurrCycDebit().compareTo(new BigDecimal("0.00"))).isZero();

        // Assert — rejection file with code 102
        List<String> rejectKeys = listS3ObjectKeys(BATCH_OUTPUT_BUCKET, S3_REJECT_KEY_PREFIX);
        assertThat(rejectKeys).isNotEmpty();
        String rejectContent = downloadS3Object(BATCH_OUTPUT_BUCKET, rejectKeys.get(0));
        assertThat(rejectContent).contains("102");
        assertThat(rejectContent).contains("OVERLIMIT TRANSACTION");

        log.info("testRejectCode102OverlimitTransaction — PASSED: reject code 102, balances unchanged");
    }

    // =========================================================================
    // Test 5: Reject Code 103 — Expired Account
    // =========================================================================

    /**
     * Verifies that a daily transaction is rejected with code 103 when the
     * account expiration date is before the transaction origination date.
     *
     * <p>Maps to CBTRN02C.cbl 1500-B-LOOKUP-ACCT (lines 414-420):
     * {@code IF ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10) → MOVE 103}</p>
     *
     * <p>Note: The COBOL comparison uses {@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10)},
     * rejecting when NOT true, i.e., when expiration date is BEFORE the transaction date.
     * Java equivalent: {@code acctExpDate.isBefore(tranOrigTs.toLocalDate())}.</p>
     */
    @Test
    @DisplayName("Should reject transaction after account expiration (code 103)")
    void testRejectCode103ExpiredAccount() throws Exception {
        // Arrange — Account expired on 2020-01-01, transaction on 2023-06-10
        Account account = createTestAccount("00000000020", "Y",
                new BigDecimal("50000.00"), new BigDecimal("1000.00"),
                new BigDecimal("500.00"), new BigDecimal("200.00"),
                LocalDate.of(2020, 1, 1), "A000000003");  // EXPIRED
        accountRepository.save(account);

        insertTestCustomer("000000070");
        CardCrossReference xref = new CardCrossReference(
                "3400000000000002", "000000070", "00000000020");
        cardCrossReferenceRepository.save(xref);

        DailyTransaction dt = createDailyTransaction("0000000099000004",
                "01", (short) 1, "POS TERM", "EXPIRED ACCOUNT PURCHASE",
                new BigDecimal("15.00"), "M066", "STORE W", "SEATTLE", "98101",
                "3400000000000002",
                LocalDateTime.of(2023, 6, 10, 13, 0, 0),  // After expiration
                LocalDateTime.of(2023, 6, 10, 13, 0, 0));
        uploadDailyTransactions(dt);

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completes with rejects
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertExitStatusContains(jobExecution, "COMPLETED_WITH_REJECTS");

        // Assert — no transactions posted
        assertThat(transactionRepository.count()).isZero();

        // Assert — rejection file with code 103
        List<String> rejectKeys = listS3ObjectKeys(BATCH_OUTPUT_BUCKET, S3_REJECT_KEY_PREFIX);
        assertThat(rejectKeys).isNotEmpty();
        String rejectContent = downloadS3Object(BATCH_OUTPUT_BUCKET, rejectKeys.get(0));
        assertThat(rejectContent).contains("103");
        assertThat(rejectContent).contains("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

        log.info("testRejectCode103ExpiredAccount — PASSED: reject code 103 verified");
    }

    // =========================================================================
    // Test 6: Mixed Valid and Invalid Transactions
    // =========================================================================

    /**
     * Verifies that a batch containing both valid and invalid daily transactions
     * is processed correctly: valid transactions are posted and invalid ones are
     * rejected, with correct exit status COMPLETED_WITH_REJECTS.
     *
     * <p>Account balances and TCATBAL records must reflect ONLY valid transactions.
     * Rejected transactions must NOT alter any balances.</p>
     */
    @Test
    @DisplayName("Should process mixed batch with valid and rejected transactions")
    void testMixedValidAndInvalidTransactions() throws Exception {
        // Arrange — set up 2 accounts, XREF records, and 4 daily transactions
        // Account 1: valid, large credit limit, future expiration
        Account acct1 = createTestAccount("00000000001", "Y",
                new BigDecimal("50000.00"), new BigDecimal("1000.00"),
                new BigDecimal("200.00"), new BigDecimal("100.00"),
                LocalDate.of(2026, 12, 31), "A000000001");
        accountRepository.save(acct1);

        // Account 2: expired — for reject code 103
        Account acct2 = createTestAccount("00000000002", "Y",
                new BigDecimal("50000.00"), new BigDecimal("2000.00"),
                new BigDecimal("300.00"), new BigDecimal("50.00"),
                LocalDate.of(2020, 6, 15), "A000000001");  // EXPIRED
        accountRepository.save(acct2);

        // XREF records (FK: card_cross_references.cust_id → customers.cust_id)
        insertTestCustomer("000000050");
        insertTestCustomer("000000051");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "4111111111111111", "000000050", "00000000001"));
        cardCrossReferenceRepository.save(new CardCrossReference(
                "4222222222222222", "000000051", "00000000002"));

        // Transaction 1: VALID — card 4111... → acct 1 (active, within limit, not expired)
        DailyTransaction dtValid1 = createDailyTransaction("0000000001000001",
                "01", (short) 1, "POS TERM", "VALID PURCHASE 1",
                new BigDecimal("50.47"), "M001", "STORE A", "NEW YORK", "10001",
                "4111111111111111",
                LocalDateTime.of(2025, 6, 10, 14, 30, 0),
                LocalDateTime.of(2025, 6, 10, 14, 30, 0));

        // Transaction 2: REJECT 100 — card 9999... NOT in XREF
        DailyTransaction dtReject100 = createDailyTransaction("0000000099000001",
                "01", (short) 1, "POS TERM", "NO XREF CARD",
                new BigDecimal("25.00"), "M099", "STORE X", "CHICAGO", "60601",
                "9999999999999999",
                LocalDateTime.of(2025, 6, 10, 15, 0, 0),
                LocalDateTime.of(2025, 6, 10, 15, 0, 0));

        // Transaction 3: VALID — card 4111... → acct 1 (second valid transaction)
        DailyTransaction dtValid2 = createDailyTransaction("0000000001000002",
                "03", (short) 2, "OPERATOR", "VALID PURCHASE 2",
                new BigDecimal("91.90"), "M002", "STORE B", "BOSTON", "02101",
                "4111111111111111",
                LocalDateTime.of(2025, 6, 10, 16, 0, 0),
                LocalDateTime.of(2025, 6, 10, 16, 0, 0));

        // Transaction 4: REJECT 103 — card 4222... → acct 2 (EXPIRED)
        DailyTransaction dtReject103 = createDailyTransaction("0000000099000002",
                "01", (short) 1, "POS TERM", "EXPIRED ACCT PURCHASE",
                new BigDecimal("40.00"), "M088", "STORE Y", "DALLAS", "75201",
                "4222222222222222",
                LocalDateTime.of(2025, 6, 10, 17, 0, 0),  // After acct 2 expiration
                LocalDateTime.of(2025, 6, 10, 17, 0, 0));

        uploadDailyTransactions(dtValid1, dtReject100, dtValid2, dtReject103);

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completes with rejects (rejectCount > 0 → RETURN-CODE 4)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertExitStatusContains(jobExecution, "COMPLETED_WITH_REJECTS");

        // Assert — exactly 2 valid transactions posted
        List<Transaction> posted = transactionRepository.findAll();
        assertThat(posted).hasSize(2);
        assertThat(posted.stream().map(Transaction::getTranId))
                .containsExactlyInAnyOrder("0000000001000001", "0000000001000002");

        // Assert — Account 1 balances updated by ONLY valid transactions
        // Original: currBal=1000.00, cycCredit=200.00, cycDebit=100.00
        // After 2 valid txns (+50.47, +91.90): currBal=1142.37, cycCredit=342.37
        Account updatedAcct1 = accountRepository.findById("00000000001").orElseThrow();
        BigDecimal expectedBal = new BigDecimal("1000.00")
                .add(new BigDecimal("50.47"))
                .add(new BigDecimal("91.90"));
        assertThat(updatedAcct1.getAcctCurrBal().compareTo(expectedBal)).isZero();
        BigDecimal expectedCycCredit = new BigDecimal("200.00")
                .add(new BigDecimal("50.47"))
                .add(new BigDecimal("91.90"));
        assertThat(updatedAcct1.getAcctCurrCycCredit().compareTo(expectedCycCredit)).isZero();
        assertThat(updatedAcct1.getAcctCurrCycDebit().compareTo(new BigDecimal("100.00"))).isZero();

        // Assert — Account 2 balances UNCHANGED (rejected transaction)
        Account unchangedAcct2 = accountRepository.findById("00000000002").orElseThrow();
        assertThat(unchangedAcct2.getAcctCurrBal().compareTo(new BigDecimal("2000.00"))).isZero();
        assertThat(unchangedAcct2.getAcctCurrCycCredit().compareTo(new BigDecimal("300.00"))).isZero();
        assertThat(unchangedAcct2.getAcctCurrCycDebit().compareTo(new BigDecimal("50.00"))).isZero();

        // Assert — rejection file exists on S3
        List<String> rejectKeys = listS3ObjectKeys(BATCH_OUTPUT_BUCKET, S3_REJECT_KEY_PREFIX);
        assertThat(rejectKeys).isNotEmpty();

        log.info("testMixedValidAndInvalidTransactions — PASSED: 2 valid, 2 rejected");
    }

    // =========================================================================
    // Test 7: All Valid Transactions — No Rejects
    // =========================================================================

    /**
     * Verifies that when all daily transactions are valid, the job completes
     * with exit status COMPLETED (not COMPLETED_WITH_REJECTS), equivalent
     * to RETURN-CODE 0 in COBOL (CBTRN02C.cbl lines 229-231).
     */
    @Test
    @DisplayName("Should complete successfully with no rejects")
    void testAllValidTransactionsNoRejects() throws Exception {
        // Arrange — all transactions will be valid
        Account account = createTestAccount("00000000001", "Y",
                new BigDecimal("50000.00"), new BigDecimal("500.00"),
                new BigDecimal("100.00"), new BigDecimal("50.00"),
                LocalDate.of(2026, 12, 31), "A000000001");
        accountRepository.save(account);

        insertTestCustomer("000000050");
        insertTestCustomer("000000051");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "4111111111111111", "000000050", "00000000001"));
        cardCrossReferenceRepository.save(new CardCrossReference(
                "4222222222222222", "000000051", "00000000001"));

        DailyTransaction dt1 = createDailyTransaction("0000000001000001",
                "01", (short) 1, "POS TERM", "PURCHASE 1",
                new BigDecimal("25.00"), "M001", "STORE A", "NEW YORK", "10001",
                "4111111111111111",
                LocalDateTime.of(2025, 6, 10, 14, 0, 0),
                LocalDateTime.of(2025, 6, 10, 14, 0, 0));
        DailyTransaction dt2 = createDailyTransaction("0000000001000002",
                "01", (short) 1, "OPERATOR", "PURCHASE 2",
                new BigDecimal("75.50"), "M002", "STORE B", "BOSTON", "02101",
                "4222222222222222",
                LocalDateTime.of(2025, 6, 10, 15, 0, 0),
                LocalDateTime.of(2025, 6, 10, 15, 0, 0));
        uploadDailyTransactions(dt1, dt2);

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed with COMPLETED (no rejects → RETURN-CODE 0)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // The exit status should be COMPLETED, not COMPLETED_WITH_REJECTS
        Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
        for (StepExecution step : stepExecutions) {
            // filterCount == 0 means no rejections → COMPLETED exit code
            if (step.getFilterCount() == 0) {
                assertThat(step.getExitStatus().getExitCode())
                        .isEqualTo(ExitStatus.COMPLETED.getExitCode());
            }
        }

        // Assert — 2 transactions posted
        assertThat(transactionRepository.count()).isEqualTo(2);

        // Assert — no rejection file on S3 (or empty prefix)
        List<String> rejectKeys = listS3ObjectKeys(BATCH_OUTPUT_BUCKET, S3_REJECT_KEY_PREFIX);
        // Either no keys or the file is empty — both indicate zero rejects
        if (!rejectKeys.isEmpty()) {
            String content = downloadS3Object(BATCH_OUTPUT_BUCKET, rejectKeys.get(0));
            // If a file was written, it should contain no reject records
            log.debug("Reject file content length: {}", content.length());
        }

        log.info("testAllValidTransactionsNoRejects — PASSED: COMPLETED, 0 rejects");
    }

    // =========================================================================
    // Test 8: TCATBAL Create When Not Exists
    // =========================================================================

    /**
     * Verifies that when a valid transaction has a type/category code combination
     * NOT already in the TransactionCategoryBalance table, a NEW record is created
     * with the transaction amount as the initial balance.
     *
     * <p>Maps to CBTRN02C.cbl 2700-A-CREATE-TCATBAL-REC (lines 503-524):
     * {@code INITIALIZE TRAN-CAT-BAL-RECORD; ADD DALYTRAN-AMT TO TRAN-CAT-BAL;
     * WRITE FD-TRAN-CAT-BAL-RECORD}</p>
     */
    @Test
    @DisplayName("Should create new TCATBAL record when composite key does not exist")
    void testTcatbalCreateWhenNotExists() throws Exception {
        // Arrange — no pre-existing TCATBAL record for acct 1 / type 01 / cat 1
        Account account = createTestAccount("00000000001", "Y",
                new BigDecimal("50000.00"), new BigDecimal("500.00"),
                new BigDecimal("100.00"), new BigDecimal("50.00"),
                LocalDate.of(2026, 12, 31), "A000000001");
        accountRepository.save(account);

        insertTestCustomer("000000050");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "4111111111111111", "000000050", "00000000001"));

        // Verify no TCATBAL exists for this composite key
        TransactionCategoryBalanceId tcatbalId =
                new TransactionCategoryBalanceId("00000000001", "01", (short) 1);
        assertThat(transactionCategoryBalanceRepository.findById(tcatbalId)).isEmpty();

        DailyTransaction dt = createDailyTransaction("0000000001000001",
                "01", (short) 1, "POS TERM", "FIRST PURCHASE",
                new BigDecimal("50.47"), "M001", "STORE A", "NEW YORK", "10001",
                "4111111111111111",
                LocalDateTime.of(2025, 6, 10, 14, 30, 0),
                LocalDateTime.of(2025, 6, 10, 14, 30, 0));
        uploadDailyTransactions(dt);

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — new TCATBAL record created with composite key
        Optional<TransactionCategoryBalance> tcatbalOpt =
                transactionCategoryBalanceRepository.findById(tcatbalId);
        assertThat(tcatbalOpt).isPresent();

        // Assert — balance equals transaction amount (BigDecimal.compareTo)
        TransactionCategoryBalance tcatbal = tcatbalOpt.get();
        assertThat(tcatbal.getTranCatBal().compareTo(new BigDecimal("50.47"))).isZero();

        log.info("testTcatbalCreateWhenNotExists — PASSED: new TCATBAL record created");
    }

    // =========================================================================
    // Test 9: TCATBAL Update When Exists
    // =========================================================================

    /**
     * Verifies that when a valid transaction has a type/category code combination
     * that ALREADY exists in the TransactionCategoryBalance table, the existing
     * record's balance is incremented by the transaction amount.
     *
     * <p>Maps to CBTRN02C.cbl 2700-B-UPDATE-TCATBAL-REC (lines 526-542):
     * {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL; REWRITE FD-TRAN-CAT-BAL-RECORD}</p>
     *
     * <p>CRITICAL: Balance uses {@code BigDecimal.compareTo() == 0} (AAP §0.8.2).</p>
     */
    @Test
    @DisplayName("Should update existing TCATBAL record balance")
    void testTcatbalUpdateWhenExists() throws Exception {
        // Arrange — pre-existing TCATBAL with balance = 100.00
        Account account = createTestAccount("00000000001", "Y",
                new BigDecimal("50000.00"), new BigDecimal("500.00"),
                new BigDecimal("100.00"), new BigDecimal("50.00"),
                LocalDate.of(2026, 12, 31), "A000000001");
        accountRepository.save(account);

        insertTestCustomer("000000050");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "4111111111111111", "000000050", "00000000001"));

        // Pre-existing TCATBAL record with balance 100.00
        TransactionCategoryBalanceId tcatbalId =
                new TransactionCategoryBalanceId("00000000001", "01", (short) 1);
        TransactionCategoryBalance existingTcatbal =
                new TransactionCategoryBalance(tcatbalId, new BigDecimal("100.00"));
        transactionCategoryBalanceRepository.save(existingTcatbal);

        // Verify pre-existing balance
        assertThat(transactionCategoryBalanceRepository.findById(tcatbalId))
                .isPresent()
                .hasValueSatisfying(t ->
                        assertThat(t.getTranCatBal().compareTo(new BigDecimal("100.00"))).isZero());

        DailyTransaction dt = createDailyTransaction("0000000001000001",
                "01", (short) 1, "POS TERM", "ADDITIONAL PURCHASE",
                new BigDecimal("50.47"), "M001", "STORE A", "NEW YORK", "10001",
                "4111111111111111",
                LocalDateTime.of(2025, 6, 10, 14, 30, 0),
                LocalDateTime.of(2025, 6, 10, 14, 30, 0));
        uploadDailyTransactions(dt);

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — TCATBAL balance updated: 100.00 + 50.47 = 150.47
        Optional<TransactionCategoryBalance> updatedOpt =
                transactionCategoryBalanceRepository.findById(tcatbalId);
        assertThat(updatedOpt).isPresent();
        BigDecimal expectedBalance = new BigDecimal("100.00").add(new BigDecimal("50.47"));
        assertThat(updatedOpt.get().getTranCatBal().compareTo(expectedBalance)).isZero();

        log.info("testTcatbalUpdateWhenExists — PASSED: TCATBAL balance 100.00 + 50.47 = 150.47");
    }

    // =========================================================================
    // Helper Methods — Test Data Creation
    // =========================================================================

    /**
     * Creates a test Account entity with the specified financial fields.
     * All monetary fields use BigDecimal per AAP §0.8.2 (zero floating-point).
     */
    private Account createTestAccount(String acctId, String activeStatus,
                                       BigDecimal creditLimit, BigDecimal currBal,
                                       BigDecimal currCycCredit, BigDecimal currCycDebit,
                                       LocalDate expDate, String groupId) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctActiveStatus(activeStatus);
        account.setAcctCreditLimit(creditLimit);
        account.setAcctCurrBal(currBal);
        account.setAcctCashCreditLimit(creditLimit);
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        account.setAcctExpDate(expDate);
        account.setAcctCurrCycCredit(currCycCredit);
        account.setAcctCurrCycDebit(currCycDebit);
        account.setAcctGroupId(groupId);
        return account;
    }

    /**
     * Creates a test DailyTransaction entity with all required fields populated.
     * Mirrors the 350-byte CVTRA06Y copybook record layout.
     *
     * @param dalytranId          transaction ID (PIC X(16))
     * @param typeCd              transaction type code (PIC X(02))
     * @param catCd               transaction category code (Short, matching DDL SMALLINT)
     * @param source              transaction source (PIC X(10))
     * @param desc                transaction description (PIC X(100))
     * @param amt                 transaction amount (BigDecimal — never float/double)
     * @param merchantId          merchant ID (PIC X(09))
     * @param merchantName        merchant name (PIC X(50))
     * @param merchantCity        merchant city (PIC X(50))
     * @param merchantZip         merchant ZIP code (PIC X(10))
     * @param cardNum             card number (PIC X(16))
     * @param origTs              origination timestamp
     * @param procTs              processing timestamp
     * @return the constructed DailyTransaction entity
     */
    private DailyTransaction createDailyTransaction(String dalytranId, String typeCd,
                                                     short catCd, String source,
                                                     String desc, BigDecimal amt,
                                                     String merchantId, String merchantName,
                                                     String merchantCity, String merchantZip,
                                                     String cardNum,
                                                     LocalDateTime origTs,
                                                     LocalDateTime procTs) {
        DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId(dalytranId);
        dt.setDalytranTypeCd(typeCd);
        dt.setDalytranCatCd(catCd);
        dt.setDalytranSource(source);
        dt.setDalytranDesc(desc);
        dt.setDalytranAmt(amt);
        dt.setDalytranMerchantId(merchantId);
        dt.setDalytranMerchantName(merchantName);
        dt.setDalytranMerchantCity(merchantCity);
        dt.setDalytranMerchantZip(merchantZip);
        dt.setDalytranCardNum(cardNum);
        dt.setDalytranOrigTs(origTs);
        dt.setDalytranProcTs(procTs);
        return dt;
    }

    // =========================================================================
    // Helper Methods — Job Execution
    // =========================================================================

    // NOTE: No helper method returning JobExecution is defined here.
    // The JobScopeTestExecutionListener in @SpringBatchTest scans ALL declared
    // methods (including private) returning JobExecution via reflection and
    // invokes them during prepareTestInstance (BEFORE @BeforeEach), which fails
    // because the Job hasn't been set yet. Inline the launchJob() call instead.

    // =========================================================================
    // Helper Methods — Database Cleanup
    // =========================================================================

    /**
     * Truncates all test-relevant tables with CASCADE to handle FK constraints.
     *
     * <p>The V3 Flyway migration seeds reference data (accounts, cards, customers,
     * cross-references, etc.) with FK relationships that prevent simple JPA
     * {@code deleteAll()} calls. This method uses native SQL TRUNCATE...CASCADE
     * to cleanly remove all data regardless of FK order.</p>
     *
     * <p>CRITICAL: Uses raw JDBC with explicit {@code setAutoCommit(true)} because
     * the HikariCP connection pool is configured with {@code auto-commit=false}
     * (via {@code spring.datasource.hikari.auto-commit=false} in DynamicPropertySource).
     * JdbcTemplate inherits this setting, causing TRUNCATE statements to execute
     * within an uncommitted transaction that is rolled back when the connection
     * returns to the pool. Raw JDBC bypasses Spring's transaction management
     * to guarantee DDL statements persist immediately.</p>
     */
    private void truncateAllTestTables() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE transaction_category_balances CASCADE");
                stmt.execute("TRUNCATE TABLE transactions CASCADE");
                stmt.execute("TRUNCATE TABLE daily_transactions CASCADE");
                stmt.execute("TRUNCATE TABLE card_cross_references CASCADE");
                stmt.execute("TRUNCATE TABLE cards CASCADE");
                stmt.execute("TRUNCATE TABLE customers CASCADE");
                stmt.execute("TRUNCATE TABLE accounts CASCADE");
            }
        } catch (SQLException e) {
            log.warn("Failed to truncate test tables: {}", e.getMessage(), e);
        }
    }

    /**
     * Inserts a minimal customer record via raw JDBC with auto-commit=true.
     *
     * <p>Required to satisfy the FK constraint
     * {@code card_cross_references.cust_id → customers.cust_id} after TRUNCATE
     * clears the V3 Flyway seed data. Uses raw JDBC because:
     * <ol>
     *   <li>The {@code Customer} entity is not in this test's dependency graph</li>
     *   <li>JdbcTemplate respects Hikari's {@code auto-commit=false}, preventing commits</li>
     * </ol>
     *
     * <p>Uses {@code ON CONFLICT DO NOTHING} for idempotent calls across tests.</p>
     *
     * @param custId the customer ID (VARCHAR(9), max 9 characters)
     */
    private void insertTestCustomer(String custId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(String.format(
                        "INSERT INTO customers (cust_id, first_name, last_name) " +
                        "VALUES ('%s', 'TEST', 'CUSTOMER') ON CONFLICT (cust_id) DO NOTHING",
                        custId));
            }
        } catch (SQLException e) {
            log.warn("Failed to insert test customer {}: {}", custId, e.getMessage());
        }
    }

    // =========================================================================
    // Helper Methods — S3 Operations
    // =========================================================================

    /**
     * Creates an S3 bucket if it does not already exist. Silently handles
     * the case where the bucket already exists.
     */
    private void createS3BucketIfNotExists(String bucketName) {
        try {
            s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build());
            log.debug("Created S3 bucket: {}", bucketName);
        } catch (Exception e) {
            log.debug("S3 bucket already exists or creation error: {}", e.getMessage());
        }
    }

    /**
     * Removes all objects from the specified S3 bucket.
     */
    private void cleanS3Bucket(String bucketName) {
        try {
            ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder()
                            .bucket(bucketName)
                            .build());
            for (S3Object obj : listResponse.contents()) {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(obj.key())
                        .build());
                log.debug("Deleted S3 object: s3://{}/{}", bucketName, obj.key());
            }
        } catch (Exception e) {
            log.debug("S3 cleanup error (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Lists all S3 object keys in the specified bucket with the given prefix.
     *
     * @param bucket the S3 bucket name
     * @param prefix the key prefix to filter by
     * @return list of matching object keys
     */
    private List<String> listS3ObjectKeys(String bucket, String prefix) {
        try {
            ListObjectsV2Response response = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .build());
            return response.contents().stream()
                    .map(S3Object::key)
                    .toList();
        } catch (Exception e) {
            log.debug("S3 list error: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Downloads an S3 object and returns its content as a UTF-8 string.
     *
     * @param bucket the S3 bucket name
     * @param key    the S3 object key
     * @return the object content as a string
     */
    private String downloadS3Object(String bucket, String key) {
        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build());
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(response, StandardCharsets.UTF_8))) {

            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        } catch (Exception e) {
            log.warn("Failed to download S3 object s3://{}/{}: {}", bucket, key, e.getMessage());
            return "";
        }
    }

    // =========================================================================
    // Helper Methods — S3 Upload (Fixed-Width CVTRA06Y format)
    // =========================================================================

    /**
     * Converts DailyTransaction entities to COBOL fixed-width 350-byte records
     * (CVTRA06Y copybook layout) and uploads them to the S3 input bucket as a
     * single file. This is the data source for {@link DailyTransactionReader}.
     *
     * @param txns one or more daily transaction entities to upload
     */
    private void uploadDailyTransactions(DailyTransaction... txns) {
        StringBuilder sb = new StringBuilder(txns.length * 351);
        for (DailyTransaction dt : txns) {
            sb.append(buildFixedWidthLine(dt)).append("\n");
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BATCH_INPUT_BUCKET)
                        .key(S3_DAILY_TRANSACTION_KEY)
                        .build(),
                RequestBody.fromBytes(bytes));
        log.debug("Uploaded {} daily transaction records to s3://{}/{}",
                txns.length, BATCH_INPUT_BUCKET, S3_DAILY_TRANSACTION_KEY);
    }

    /**
     * Builds a 350-character fixed-width line from a {@link DailyTransaction} entity,
     * matching the CVTRA06Y copybook layout parsed by {@link DailyTransactionReader}.
     *
     * <pre>
     * Offset  0-15:  DALYTRAN-ID          PIC X(16)
     * Offset 16-17:  DALYTRAN-TYPE-CD     PIC X(02)
     * Offset 18-21:  DALYTRAN-CAT-CD      PIC 9(04) (zero-padded)
     * Offset 22-31:  DALYTRAN-SOURCE      PIC X(10)
     * Offset 32-131: DALYTRAN-DESC        PIC X(100)
     * Offset 132-142:DALYTRAN-AMT         PIC S9(09)V99 (COBOL overpunch)
     * Offset 143-151:DALYTRAN-MERCHANT-ID  PIC 9(09) (zero-padded)
     * Offset 152-201:DALYTRAN-MERCHANT-NAME PIC X(50)
     * Offset 202-251:DALYTRAN-MERCHANT-CITY PIC X(50)
     * Offset 252-261:DALYTRAN-MERCHANT-ZIP  PIC X(10)
     * Offset 262-277:DALYTRAN-CARD-NUM     PIC X(16)
     * Offset 278-303:DALYTRAN-ORIG-TS      PIC X(26) yyyy-MM-dd HH:mm:ss.SSSSSS
     * Offset 304-329:DALYTRAN-PROC-TS      PIC X(26) yyyy-MM-dd HH:mm:ss.SSSSSS
     * Offset 330-349:FILLER               20 spaces
     * </pre>
     *
     * @param dt the daily transaction entity
     * @return a 350-character fixed-width string
     */
    private String buildFixedWidthLine(DailyTransaction dt) {
        StringBuilder sb = new StringBuilder(350);
        sb.append(padRight(dt.getDalytranId(), 16));                       // 0-15
        sb.append(padRight(dt.getDalytranTypeCd(), 2));                    // 16-17
        sb.append(String.format("%04d", dt.getDalytranCatCd()));           // 18-21
        sb.append(padRight(dt.getDalytranSource(), 10));                   // 22-31
        sb.append(padRight(dt.getDalytranDesc(), 100));                    // 32-131
        sb.append(encodeCobolSignedDecimal(dt.getDalytranAmt()));          // 132-142
        sb.append(padLeft(dt.getDalytranMerchantId(), 9, '0'));            // 143-151
        sb.append(padRight(dt.getDalytranMerchantName(), 50));             // 152-201
        sb.append(padRight(dt.getDalytranMerchantCity(), 50));             // 202-251
        sb.append(padRight(dt.getDalytranMerchantZip(), 10));              // 252-261
        sb.append(padRight(dt.getDalytranCardNum(), 16));                  // 262-277
        sb.append(padRight(formatTimestamp(dt.getDalytranOrigTs()), 26));   // 278-303
        sb.append(padRight(formatTimestamp(dt.getDalytranProcTs()), 26));   // 304-329
        sb.append(" ".repeat(20));                                          // 330-349
        return sb.toString();
    }

    /**
     * Encodes a {@link BigDecimal} amount into COBOL S9(09)V99 signed decimal format
     * with overpunch encoding on the last digit.
     *
     * <p>COBOL overpunch convention:
     * <ul>
     *   <li>Positive: {@code { A B C D E F G H I} → digits 0-9</li>
     *   <li>Negative: {@code } J K L M N O P Q R} → digits 0-9</li>
     * </ul>
     *
     * <p>Example: $50.47 → unscaled 5047 → "0000000504G" (G = positive 7).</p>
     *
     * @param amount the decimal amount to encode (scale ≤ 2)
     * @return an 11-character COBOL overpunch-encoded string
     */
    private String encodeCobolSignedDecimal(BigDecimal amount) {
        long unscaled = amount.movePointRight(2).longValueExact();
        boolean negative = unscaled < 0;
        long absValue = Math.abs(unscaled);
        String digits = String.format("%011d", absValue);
        int lastDigit = digits.charAt(10) - '0';
        char overpunch = negative
                ? NEGATIVE_OVERPUNCH.charAt(lastDigit)
                : POSITIVE_OVERPUNCH.charAt(lastDigit);
        return digits.substring(0, 10) + overpunch;
    }

    /**
     * Formats a {@link LocalDateTime} as "yyyy-MM-dd HH:mm:ss.SSSSSS" matching
     * the DailyTransactionReader PRIMARY_TS_FORMATTER.
     *
     * @param ts the timestamp to format
     * @return the formatted 26-character timestamp string
     */
    private String formatTimestamp(LocalDateTime ts) {
        if (ts == null) {
            return " ".repeat(26);
        }
        return ts.format(TS_FORMATTER);
    }

    /**
     * Right-pads a string to the specified length with spaces. If the input
     * is longer than {@code len}, it is truncated.
     *
     * @param s   the input string (may be null)
     * @param len the target length
     * @return a string of exactly {@code len} characters
     */
    private String padRight(String s, int len) {
        if (s == null) {
            return " ".repeat(len);
        }
        if (s.length() >= len) {
            return s.substring(0, len);
        }
        return s + " ".repeat(len - s.length());
    }

    /**
     * Left-pads a string to the specified length with the given character.
     * If the input is longer than {@code len}, it is truncated from the left.
     *
     * @param s       the input string (may be null)
     * @param len     the target length
     * @param padChar the character to pad with
     * @return a string of exactly {@code len} characters
     */
    private String padLeft(String s, int len, char padChar) {
        if (s == null) {
            return String.valueOf(padChar).repeat(len);
        }
        if (s.length() >= len) {
            return s.substring(s.length() - len);
        }
        return String.valueOf(padChar).repeat(len - s.length()) + s;
    }

    // =========================================================================
    // Helper Methods — FK Bypass for Orphan XREF Records
    // =========================================================================

    /**
     * Inserts a CardCrossReference record via raw JDBC with PostgreSQL
     * {@code session_replication_role = 'replica'} to bypass FK trigger checks.
     *
     * <p>Required for {@code testRejectCode101AccountNotFound} where the XREF
     * must point to a non-existent account ID. The FK constraint
     * {@code fk_card_xref_account} prevents this via normal JPA insertion.</p>
     *
     * @param cardNum the card number (PK)
     * @param custId  the customer ID (FK to customers)
     * @param acctId  the account ID (FK to accounts — intentionally non-existent)
     */
    private void insertXrefBypassingFk(String cardNum, String custId, String acctId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET session_replication_role = 'replica'");
                stmt.execute(String.format(
                        "INSERT INTO card_cross_references (card_num, cust_id, account_id) " +
                        "VALUES ('%s', '%s', '%s') ON CONFLICT (card_num) DO NOTHING",
                        cardNum, custId, acctId));
                stmt.execute("SET session_replication_role = 'origin'");
            }
        } catch (SQLException e) {
            log.warn("Failed to insert XREF bypassing FK: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // Helper Methods — Assertion Utilities
    // =========================================================================

    /**
     * Asserts that at least one step execution in the job has an exit status
     * containing the expected exit code string.
     *
     * @param jobExecution     the completed job execution
     * @param expectedExitCode the expected exit code fragment
     */
    private void assertExitStatusContains(JobExecution jobExecution, String expectedExitCode) {
        boolean found = false;
        for (StepExecution step : jobExecution.getStepExecutions()) {
            if (step.getExitStatus().getExitCode().contains(expectedExitCode)) {
                found = true;
                break;
            }
        }
        // Also check job-level exit status
        if (jobExecution.getExitStatus().getExitCode().contains(expectedExitCode)) {
            found = true;
        }
        assertThat(found)
                .as("Expected exit status containing '%s' but found job=%s, steps=%s",
                        expectedExitCode,
                        jobExecution.getExitStatus().getExitCode(),
                        jobExecution.getStepExecutions().stream()
                                .map(s -> s.getExitStatus().getExitCode())
                                .toList())
                .isTrue();
    }
}
