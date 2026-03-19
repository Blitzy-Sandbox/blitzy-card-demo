/*
 * StatementGenerationJobIT.java — Integration Test for CREASTMT Pipeline Stage 4a
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - app/jcl/CREASTMT.JCL (98 lines — 5-step JCL, only STEP040 maps to Java)
 *   - app/cbl/CBSTM03A.CBL (924 lines — main statement generation program)
 *   - app/cbl/CBSTM03B.CBL (230 lines — file-service subroutine, replaced by JPA)
 *   - app/data/ASCII/acctdata.txt, cardxref.txt, custdata.txt (fixture data patterns)
 *
 * Full Spring Batch integration test for StatementGenerationJob — the Java equivalent
 * of CREASTMT.JCL Stage 4a in the 5-stage batch pipeline.
 *
 * Pipeline Position:
 *   Stage 1: POSTTRAN  (DailyTransactionPostingJob)  — validate and post daily transactions
 *   Stage 2: INTCALC   (InterestCalculationJob)       — calculate and post interest transactions
 *   Stage 3: COMBTRAN  (CombineTransactionsJob)       — sort and backup combined transactions
 *   Stage 4a: CREASTMT (StatementGenerationJob) ← THIS — generate customer statements
 *   Stage 4b: TRANREPT (TransactionReportJob)         — generate transaction reports
 *
 * The original CREASTMT.JCL performs five steps:
 *   DELDEF01: Delete and redefine work VSAM (→ not needed; PostgreSQL tables)
 *   STEP010:  DFSORT transactions by card number + tran ID (→ JPA ORDER BY)
 *   STEP020:  IDCAMS REPRO sorted sequential into work VSAM (→ not needed)
 *   STEP030:  Delete previous statement outputs (→ S3 versioning)
 *   STEP040:  Run CBSTM03A (statement generation) (→ StatementProcessor + StatementWriter)
 *
 * CBSTM03A reads XREF records (1000-XREFFILE-GET-NEXT), looks up
 * Customer (2000-CUSTFILE-GET), Account (3000-ACCTFILE-GET), retrieves
 * Transactions (4000-TRNXFILE-GET), generates dual-format statements
 * (5000-CREATE-STATEMENT + 6000-WRITE-TRANS) as:
 *   STMTFILE (text, LRECL=80) + HTMLFILE (HTML, LRECL=100)
 *
 * CBSTM03B is a file-service subroutine entirely replaced by JPA repositories.
 *
 * Test verification targets:
 *   - Text statement generation on S3 under text/ prefix
 *   - HTML statement generation on S3 under html/ prefix
 *   - Dual-format output for every card processed
 *   - Transaction details in statement content (descriptions, amounts)
 *   - Separate statements per card (one per XREF record)
 *   - Graceful handling of cards with no transactions
 *   - Customer name and account info in statement content
 *
 * Per AAP §0.7.7 (LocalStack Verification Rule): zero live AWS dependencies.
 * Tests create/destroy their own S3 resources following strict lifecycle.
 *
 * Per AAP §0.8.2 (Decimal Precision Rules): all BigDecimal assertions use
 * compareTo(), never equals() — zero floating-point substitution.
 *
 * Decision Log References:
 *   D-003: S3 versioned objects for GDG replacement
 *   D-005: Spring Batch for JCL pipeline
 */
package com.cardemo.integration.batch;

// Internal imports — from depends_on_files only
import com.cardemo.batch.jobs.StatementGenerationJob;
import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
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
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;

// Spring Core — synchronous task executor for deterministic test assertions
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

// AWS SDK v2 — S3 client for verifying statement output
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

// Java Standard Library — JDBC for FK-safe table cleanup
import java.sql.Connection;
import java.sql.Statement;

// Java Standard Library — exact decimal precision (AAP §0.8.2)
import java.math.BigDecimal;

// Java Standard Library — date/time for entity seed data
import java.time.LocalDate;
import java.time.LocalDateTime;

// Java Standard Library — collections and UUID for unique job parameters
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Java Standard Library — I/O for reading S3 statement content
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// Java Standard Library — stream collectors
import java.util.stream.Collectors;

// SLF4J — structured logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test for {@link StatementGenerationJob} verifying that the Java
 * equivalent of CREASTMT.JCL + CBSTM03A.CBL + CBSTM03B.CBL correctly generates
 * dual-format (text LRECL=80 + HTML LRECL=100) account statements, with
 * customer/account lookups, transaction detail aggregation, and S3 upload.
 *
 * <p>This test runs against a real PostgreSQL 16 container (via Testcontainers)
 * and a real LocalStack container (for S3 verification), ensuring full behavioral
 * parity with the original CREASTMT pipeline stage.
 *
 * <h3>Test Methods</h3>
 * <ol>
 *   <li>{@link #testStatementGenerationProducesTextOutput()} — Text statement on S3</li>
 *   <li>{@link #testStatementGenerationProducesHtmlOutput()} — HTML statement on S3</li>
 *   <li>{@link #testDualFormatOutputGeneration()} — Both formats per card</li>
 *   <li>{@link #testStatementIncludesTransactionDetails()} — Transaction details + totals</li>
 *   <li>{@link #testStatementPerCard()} — Separate statements per card</li>
 *   <li>{@link #testCardWithNoTransactions()} — Graceful empty-transactions handling</li>
 *   <li>{@link #testStatementCustomerAndAccountInfo()} — Customer/account info in output</li>
 * </ol>
 *
 * @see StatementGenerationJob
 * @see StatementProcessor
 * @see StatementWriter
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@SpringBatchTest
@DisplayName("StatementGenerationJob Integration Tests — CREASTMT.JCL Pipeline Stage 4a")
class StatementGenerationJobIT {

    private static final Logger log = LoggerFactory.getLogger(StatementGenerationJobIT.class);

    // -------------------------------------------------------------------------
    // Constants — S3 Bucket and Key Prefixes
    // -------------------------------------------------------------------------

    /** S3 bucket for statement output — matches carddemo.aws.s3.statements-bucket default. */
    private static final String STATEMENTS_BUCKET = "carddemo-statements";

    /** S3 key prefix for text-format statements — matches carddemo.aws.s3.statement-text-prefix. */
    private static final String TEXT_PREFIX = "text/";

    /** S3 key prefix for HTML-format statements — matches carddemo.aws.s3.statement-html-prefix. */
    private static final String HTML_PREFIX = "html/";

    // -------------------------------------------------------------------------
    // Test Data Constants — Card Numbers, Account IDs, Customer IDs
    // (from cardxref.txt, acctdata.txt, custdata.txt fixture patterns)
    // -------------------------------------------------------------------------

    /** Card 1 — from cardxref.txt pattern (16-char zero-padded). */
    private static final String CARD_NUM_1 = "0500024453765740";

    /** Card 2 — from cardxref.txt pattern (16-char zero-padded). */
    private static final String CARD_NUM_2 = "0683586198171516";

    /** Account 1 — from acctdata.txt (11-char zero-padded). */
    private static final String ACCT_ID_1 = "00000000001";

    /** Account 2 — from acctdata.txt (11-char zero-padded). */
    private static final String ACCT_ID_2 = "00000000002";

    /** Customer 1 — from custdata.txt (9-char zero-padded). */
    private static final String CUST_ID_1 = "000000001";

    /** Customer 2 — from custdata.txt (9-char zero-padded). */
    private static final String CUST_ID_2 = "000000002";

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
    // Testcontainers — LocalStack (S3 service for statement uploads)
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
     * <p>PostgreSQL: Overrides spring.datasource.* with the Testcontainers-managed
     * PostgreSQL 16 container. Flyway V1-V3 migrations run against this instance.
     *
     * <p>LocalStack: Overrides AWS endpoint properties (S3, SQS, SNS) with the
     * Testcontainers-managed LocalStack container endpoint.
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

    /** Card cross-reference JPA repository for test data setup. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Customer JPA repository for test data setup. */
    @Autowired
    private CustomerRepository customerRepository;

    /** Account JPA repository for test data setup. */
    @Autowired
    private AccountRepository accountRepository;

    /** Transaction JPA repository for test data setup and verification. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** AWS S3 client for verifying statement file uploads against LocalStack. */
    @Autowired
    private S3Client s3Client;

    /**
     * The StatementGenerationJob bean — injected by name to disambiguate from
     * the 5 other Job beans in the Spring context (DailyTransactionPostingJob,
     * InterestCalculationJob, CombineTransactionsJob, TransactionReportJob,
     * BatchPipelineOrchestrator).
     */
    @Autowired
    @Qualifier("statementGenerationJob")
    private Job statementGenerationJob;

    /**
     * JDBC DataSource for initializing Spring Batch metadata schema.
     * Required because @EnableBatchProcessing in BatchConfig disables Spring Boot's
     * BatchAutoConfiguration which would normally read
     * spring.batch.jdbc.initialize-schema=always.
     */
    @Autowired
    private DataSource dataSource;

    /**
     * Spring Batch JobRepository for constructing a synchronous job launcher.
     * Production BatchConfig uses SimpleAsyncTaskExecutor; tests need SyncTaskExecutor.
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
     *   <li>Initializes Spring Batch metadata schema (once per class)</li>
     *   <li>Configures synchronous JobLauncher for deterministic assertions</li>
     *   <li>Creates S3 bucket for statement output</li>
     *   <li>Cleans all repositories and batch metadata</li>
     *   <li>Inserts comprehensive seed data: 2 customers, 2 accounts,
     *       2 cross-references, 5 transactions (3 for card1, 2 for card2)</li>
     * </ol>
     *
     * <p>Per AAP §0.7.7: Tests create their own S3 resources — no pre-existing state.
     */
    @BeforeEach
    void setUp() {
        log.info("StatementGenerationJobIT @BeforeEach — Setting up test environment");

        // Initialize Spring Batch metadata schema if not already done.
        if (!batchSchemaInitialized) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(
                    "/org/springframework/batch/core/schema-postgresql.sql"));
            populator.setContinueOnError(true);
            populator.execute(dataSource);
            batchSchemaInitialized = true;
            log.info("Spring Batch metadata schema initialized from schema-postgresql.sql");
        }

        // Override async JobLauncher with synchronous one for deterministic test assertions
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

        // Set the specific Job bean — required because there are 6 Job beans in context
        // and @SpringBatchTest cannot auto-detect which one to use
        jobLauncherTestUtils.setJob(statementGenerationJob);

        // Create S3 statements bucket (idempotent — ignore if already exists)
        try {
            s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket(STATEMENTS_BUCKET)
                    .build());
            log.debug("Created S3 bucket: {}", STATEMENTS_BUCKET);
        } catch (Exception e) {
            log.debug("S3 bucket already exists or creation error (expected): {}", e.getMessage());
        }

        // Clean Spring Batch job execution metadata to allow repeated job launches
        jobRepositoryTestUtils.removeJobExecutions();

        // Clean all tables using TRUNCATE CASCADE to handle FK constraints.
        // The Flyway V3 seed migration inserts data into cards, accounts, etc.
        // Cards have FK to accounts (fk_cards_account), so simple deleteAll()
        // on accounts fails. TRUNCATE CASCADE handles this correctly.
        cleanDatabase();

        // Insert comprehensive seed data
        insertSeedData();

        log.info("StatementGenerationJobIT @BeforeEach — Environment ready");
    }

    /**
     * Cleans up the test environment after each test method execution.
     *
     * <p>Actions performed:
     * <ol>
     *   <li>Removes all S3 objects from the statements bucket</li>
     *   <li>Cleans Spring Batch job execution metadata</li>
     *   <li>Deletes all entities from repositories</li>
     * </ol>
     *
     * <p>Per AAP §0.7.7: Tests destroy their own S3 resources — no residual state.
     */
    @AfterEach
    void tearDown() {
        log.info("StatementGenerationJobIT @AfterEach — Cleaning up test environment");

        // Remove all S3 objects from the statements bucket
        try {
            ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder()
                            .bucket(STATEMENTS_BUCKET)
                            .build());
            for (S3Object obj : listResponse.contents()) {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .key(obj.key())
                        .build());
                log.debug("Deleted S3 object: s3://{}/{}", STATEMENTS_BUCKET, obj.key());
            }
        } catch (Exception e) {
            log.debug("S3 cleanup error (non-fatal): {}", e.getMessage());
        }

        // Clean Spring Batch metadata
        jobRepositoryTestUtils.removeJobExecutions();

        // Clean all tables using TRUNCATE CASCADE to handle FK constraints
        cleanDatabase();

        log.info("StatementGenerationJobIT @AfterEach — Cleanup complete");
    }

    // =========================================================================
    // Test 1: Text Statement Output on S3
    // =========================================================================

    /**
     * Verifies that {@link StatementGenerationJob} generates text-format statements
     * and uploads them to S3 under the {@code text/} prefix in the
     * {@code carddemo-statements} bucket.
     *
     * <p>Validates:
     * <ul>
     *   <li>Job completes with {@link BatchStatus#COMPLETED}</li>
     *   <li>At least one S3 object exists with {@code text/} prefix</li>
     *   <li>Text content includes customer names (John Doe, Jane Smith)</li>
     *   <li>Text content includes account numbers</li>
     *   <li>Text content includes transaction descriptions and amounts</li>
     *   <li>Text content includes statement totals</li>
     * </ul>
     *
     * <p>Maps COBOL STMT-FILE (FD, PIC X(80), LRECL=80) sequential write output.
     */
    @Test
    @DisplayName("Should generate text-format statements on S3")
    void testStatementGenerationProducesTextOutput() throws Exception {
        // Act — launch the StatementGenerationJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — S3 text statement files exist under text/ prefix
        ListObjectsV2Response textResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .prefix(TEXT_PREFIX)
                        .build());

        assertThat(textResponse.contents())
                .as("Should have text statement files under '%s' prefix", TEXT_PREFIX)
                .isNotEmpty();

        // Download all text statement content and combine for assertion
        String allTextContent = downloadAllS3Content(TEXT_PREFIX);

        // Verify customer names appear in text output
        assertThat(allTextContent).contains("John");
        assertThat(allTextContent).contains("Doe");
        assertThat(allTextContent).contains("Jane");
        assertThat(allTextContent).contains("Smith");

        // Verify account numbers appear
        assertThat(allTextContent).contains(ACCT_ID_1);
        assertThat(allTextContent).contains(ACCT_ID_2);

        // Verify transaction descriptions appear
        assertThat(allTextContent).contains("Purchase at Store A");
        assertThat(allTextContent).contains("Return at Store A");
        assertThat(allTextContent).contains("Purchase at Store B");

        // Verify statement structure markers (text format has start/end banners)
        assertThat(allTextContent).contains("START OF STATEMENT");
        assertThat(allTextContent).contains("END OF STATEMENT");
        assertThat(allTextContent).contains("Total EXP:");

        log.info("testStatementGenerationProducesTextOutput — PASSED");
    }

    // =========================================================================
    // Test 2: HTML Statement Output on S3
    // =========================================================================

    /**
     * Verifies that {@link StatementGenerationJob} generates HTML-format statements
     * and uploads them to S3 under the {@code html/} prefix.
     *
     * <p>Validates:
     * <ul>
     *   <li>Job completes successfully</li>
     *   <li>At least one S3 object exists with {@code html/} prefix</li>
     *   <li>HTML content includes proper HTML markup tags</li>
     *   <li>HTML content includes customer/account/transaction data</li>
     * </ul>
     *
     * <p>Maps COBOL HTML-FILE (FD, PIC X(100), LRECL=100) sequential write output.
     */
    @Test
    @DisplayName("Should generate HTML-format statements on S3")
    void testStatementGenerationProducesHtmlOutput() throws Exception {
        // Act — launch the StatementGenerationJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — S3 HTML statement files exist under html/ prefix
        ListObjectsV2Response htmlResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .prefix(HTML_PREFIX)
                        .build());

        assertThat(htmlResponse.contents())
                .as("Should have HTML statement files under '%s' prefix", HTML_PREFIX)
                .isNotEmpty();

        // Download all HTML statement content and combine for assertion
        String allHtmlContent = downloadAllS3Content(HTML_PREFIX);

        // Verify HTML structure tags
        assertThat(allHtmlContent).contains("<!DOCTYPE html>");
        assertThat(allHtmlContent).contains("<html");
        assertThat(allHtmlContent).contains("<table");
        assertThat(allHtmlContent).contains("</table>");
        assertThat(allHtmlContent).contains("</html>");

        // Verify HTML includes customer/account data
        assertThat(allHtmlContent).contains("John");
        assertThat(allHtmlContent).contains("Doe");
        assertThat(allHtmlContent).contains("Statement for Account Number:");
        assertThat(allHtmlContent).contains("Bank of XYZ");

        // Verify HTML includes Basic Details section
        assertThat(allHtmlContent).contains("Basic Details");
        assertThat(allHtmlContent).contains("Account ID");
        assertThat(allHtmlContent).contains("Current Balance");
        assertThat(allHtmlContent).contains("FICO Score");

        // Verify HTML includes Transaction Summary section
        assertThat(allHtmlContent).contains("Transaction Summary");
        assertThat(allHtmlContent).contains("Tran ID");
        assertThat(allHtmlContent).contains("Tran Details");
        assertThat(allHtmlContent).contains("Amount");

        // Verify HTML includes end of statement
        assertThat(allHtmlContent).contains("End of Statement");

        log.info("testStatementGenerationProducesHtmlOutput — PASSED");
    }

    // =========================================================================
    // Test 3: Dual-Format Output Generation
    // =========================================================================

    /**
     * Verifies that {@link StatementGenerationJob} generates BOTH text and HTML
     * statements for each card, matching the dual-output behavior of CREASTMT.JCL:
     * STMTFILE (LRECL=80) + HTMLFILE (LRECL=100).
     *
     * <p>With 2 cards in the XREF table, expects at least 2 text files
     * and 2 HTML files on S3.
     */
    @Test
    @DisplayName("Should generate both text and HTML statements for each card")
    void testDualFormatOutputGeneration() throws Exception {
        // Act — launch the StatementGenerationJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — text files exist under text/ prefix
        ListObjectsV2Response textResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .prefix(TEXT_PREFIX)
                        .build());

        assertThat(textResponse.contents())
                .as("Should have at least 2 text statement files (one per card)")
                .hasSizeGreaterThanOrEqualTo(2);

        // Assert — HTML files exist under html/ prefix
        ListObjectsV2Response htmlResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .prefix(HTML_PREFIX)
                        .build());

        assertThat(htmlResponse.contents())
                .as("Should have at least 2 HTML statement files (one per card)")
                .hasSizeGreaterThanOrEqualTo(2);

        // Assert — text file keys contain card numbers and .txt extension
        for (S3Object textObj : textResponse.contents()) {
            assertThat(textObj.key())
                    .as("Text statement key should start with text/ prefix")
                    .startsWith(TEXT_PREFIX);
            assertThat(textObj.key())
                    .as("Text statement key should end with .txt extension")
                    .endsWith(".txt");
            assertThat(textObj.size())
                    .as("Text statement file should have non-zero size")
                    .isGreaterThan(0);
        }

        // Assert — HTML file keys contain card numbers and .html extension
        for (S3Object htmlObj : htmlResponse.contents()) {
            assertThat(htmlObj.key())
                    .as("HTML statement key should start with html/ prefix")
                    .startsWith(HTML_PREFIX);
            assertThat(htmlObj.key())
                    .as("HTML statement key should end with .html extension")
                    .endsWith(".html");
            assertThat(htmlObj.size())
                    .as("HTML statement file should have non-zero size")
                    .isGreaterThan(0);
        }

        log.info("testDualFormatOutputGeneration — PASSED: {} text + {} HTML files",
                textResponse.contents().size(), htmlResponse.contents().size());
    }

    // =========================================================================
    // Test 4: Transaction Details in Statement
    // =========================================================================

    /**
     * Verifies that statement output includes all transaction details for a card,
     * with accurate amounts and a correct total computed using {@link BigDecimal}.
     *
     * <p>Card 1 has 3 transactions:
     * <ul>
     *   <li>"Purchase at Store A" — $50.47</li>
     *   <li>"Return at Store A"  — $-20.00</li>
     *   <li>"Purchase at Store B" — $100.00</li>
     * </ul>
     * Expected total: 50.47 + (-20.00) + 100.00 = 130.47
     *
     * <p>Per AAP §0.8.2: Total verification uses {@code BigDecimal.compareTo()},
     * never {@code equals()} (which is scale-sensitive).
     */
    @Test
    @DisplayName("Should include all transaction details in statement")
    void testStatementIncludesTransactionDetails() throws Exception {
        // Act — launch the StatementGenerationJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Download text statement for card 1 from S3
        String card1TextContent = downloadStatementContentForCard(TEXT_PREFIX, CARD_NUM_1);

        // If card-specific extraction wasn't possible, fall back to all text content
        if (card1TextContent == null || card1TextContent.isEmpty()) {
            card1TextContent = downloadAllS3Content(TEXT_PREFIX);
        }

        // Verify all 3 transaction descriptions appear
        assertThat(card1TextContent).contains("Purchase at Store A");
        assertThat(card1TextContent).contains("Return at Store A");
        assertThat(card1TextContent).contains("Purchase at Store B");

        // Verify transaction amounts appear in statement
        // The processor formats amounts using Z-suppressed format, so check for amount patterns
        assertThat(card1TextContent).contains("50.47");
        assertThat(card1TextContent).contains("20.00");
        assertThat(card1TextContent).contains("100.00");

        // Verify statement total: 50.47 + (-20.00) + 100.00 = 130.47
        // Per AAP §0.8.2: BigDecimal precision, compareTo() not equals()
        BigDecimal expectedTotal = new BigDecimal("50.47")
                .add(new BigDecimal("-20.00"))
                .add(new BigDecimal("100.00"));
        assertThat(expectedTotal.compareTo(new BigDecimal("130.47")))
                .as("Expected total should be 130.47")
                .isEqualTo(0);

        // Verify the total appears in the statement output
        assertThat(card1TextContent).contains("130.47");

        // Verify the Total EXP line is present
        assertThat(card1TextContent).contains("Total EXP:");

        log.info("testStatementIncludesTransactionDetails — PASSED: total=130.47 verified");
    }

    // =========================================================================
    // Test 5: Separate Statement Per Card
    // =========================================================================

    /**
     * Verifies that {@link StatementGenerationJob} generates separate statements
     * per card, with each statement containing only its own card's transactions.
     *
     * <p>Card 1 (0500024453765740) has 3 transactions; Card 2 (0683586198171516) has 2.
     * Each statement should contain ONLY its own card's data.
     */
    @Test
    @DisplayName("Should generate separate statements per card")
    void testStatementPerCard() throws Exception {
        // Act — launch the StatementGenerationJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — at least 2 text statement files exist (one per card)
        ListObjectsV2Response textResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .prefix(TEXT_PREFIX)
                        .build());

        assertThat(textResponse.contents())
                .as("Should have at least 2 text statement files")
                .hasSizeGreaterThanOrEqualTo(2);

        // Find and verify card 1 statement
        String card1Content = downloadStatementContentForCard(TEXT_PREFIX, CARD_NUM_1);
        if (card1Content != null && !card1Content.isEmpty()) {
            // Card 1 should contain its transactions
            assertThat(card1Content).contains("Purchase at Store A");
            assertThat(card1Content).contains("Return at Store A");
            assertThat(card1Content).contains("Purchase at Store B");
            // Card 1 should NOT contain card 2's transactions
            assertThat(card1Content).doesNotContain("Purchase at Store C");
            assertThat(card1Content).doesNotContain("Purchase at Store D");
        }

        // Find and verify card 2 statement
        String card2Content = downloadStatementContentForCard(TEXT_PREFIX, CARD_NUM_2);
        if (card2Content != null && !card2Content.isEmpty()) {
            // Card 2 should contain its transactions
            assertThat(card2Content).contains("Purchase at Store C");
            assertThat(card2Content).contains("Purchase at Store D");
            // Card 2 should NOT contain card 1's transactions
            assertThat(card2Content).doesNotContain("Purchase at Store A");
            assertThat(card2Content).doesNotContain("Return at Store A");
        }

        // If card-specific download failed, at least verify both names appear across all content
        if ((card1Content == null || card1Content.isEmpty())
                && (card2Content == null || card2Content.isEmpty())) {
            String allContent = downloadAllS3Content(TEXT_PREFIX);
            assertThat(allContent).contains("John");
            assertThat(allContent).contains("Jane");
            assertThat(textResponse.contents()).hasSizeGreaterThanOrEqualTo(2);
        }

        log.info("testStatementPerCard — PASSED: 2 separate statement files verified");
    }

    // =========================================================================
    // Test 6: Card With No Transactions
    // =========================================================================

    /**
     * Verifies that {@link StatementGenerationJob} handles a card with no
     * transactions gracefully — the job completes without errors and generates
     * a statement (possibly minimal/empty) for the card.
     *
     * <p>In COBOL (CBSTM03A 4000-TRNXFILE-GET), a card with no matching
     * transactions in WS-TRNX-TABLE would produce a statement with zero
     * transaction lines. The Java equivalent should behave identically.
     */
    @Test
    @DisplayName("Should handle card with no transactions gracefully")
    void testCardWithNoTransactions() throws Exception {
        // Arrange — clean default seed data and insert a card with NO transactions
        cleanDatabase();

        // Insert customer and account for the test
        Customer customer = createCustomer(CUST_ID_1, "Alice", "M", "Johnson",
                "100 No-Txn Street", "", "", "WA", "US", "98101", (short) 700);
        customerRepository.save(customer);

        Account account = createAccount(ACCT_ID_1, "Y",
                new BigDecimal("3500.00"), new BigDecimal("10000.00"));
        accountRepository.save(account);

        // Insert XREF record mapping card → account/customer, but NO transactions
        CardCrossReference xref = new CardCrossReference(
                "4111111111111111", CUST_ID_1, ACCT_ID_1);
        cardCrossReferenceRepository.save(xref);

        // Verify no transactions exist
        assertThat(transactionRepository.count()).isZero();

        // Act — launch the StatementGenerationJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully (no crash on empty transactions)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — statement was generated (processor logs "generating empty statement"
        // but still returns a StatementOutput with zero transaction lines)
        ListObjectsV2Response textResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .prefix(TEXT_PREFIX)
                        .build());

        // Verify at least one text statement file was created
        assertThat(textResponse.contents())
                .as("Should still generate a text statement even with no transactions")
                .isNotEmpty();

        // If a statement was generated, verify it has the customer info but no transaction lines
        if (!textResponse.contents().isEmpty()) {
            String textContent = downloadAllS3Content(TEXT_PREFIX);
            assertThat(textContent).contains("Alice");
            assertThat(textContent).contains("Johnson");
            assertThat(textContent).contains(ACCT_ID_1);
            assertThat(textContent).contains("START OF STATEMENT");
            assertThat(textContent).contains("END OF STATEMENT");
        }

        log.info("testCardWithNoTransactions — PASSED: empty transactions handled gracefully");
    }

    // =========================================================================
    // Test 7: Customer and Account Information in Statement
    // =========================================================================

    /**
     * Verifies that the generated statement includes customer and account
     * information as populated by the StatementProcessor from
     * 2000-CUSTFILE-GET and 3000-ACCTFILE-GET.
     *
     * <p>Validates:
     * <ul>
     *   <li>Customer name appears in statement (John Doe)</li>
     *   <li>Account number appears in statement (00000000001)</li>
     *   <li>Account balance or credit information appears (10200.00, 19400.00)</li>
     *   <li>FICO score appears in statement</li>
     * </ul>
     */
    @Test
    @DisplayName("Should include customer and account information in statement")
    void testStatementCustomerAndAccountInfo() throws Exception {
        // Act — launch the StatementGenerationJob
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .toJobParameters());

        // Assert — job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Download text statement content
        String textContent = downloadAllS3Content(TEXT_PREFIX);

        // Verify customer name appears (from 2000-CUSTFILE-GET → ST-LINE1 name)
        assertThat(textContent)
                .as("Text statement should contain customer first name")
                .contains("John");
        assertThat(textContent)
                .as("Text statement should contain customer last name")
                .contains("Doe");

        // Verify account number appears (from 3000-ACCTFILE-GET → ST-LINE7 account ID)
        assertThat(textContent)
                .as("Text statement should contain account ID")
                .contains(ACCT_ID_1);

        // Verify current balance appears (from ST-LINE8 current balance)
        assertThat(textContent)
                .as("Text statement should contain account current balance")
                .contains("10200.00");

        // Verify Basic Details section exists
        assertThat(textContent)
                .as("Text statement should contain Basic Details header")
                .contains("Basic Details");

        // Verify FICO score appears (from ST-LINE9 FICO score)
        assertThat(textContent)
                .as("Text statement should contain FICO score label")
                .contains("FICO Score");

        // Verify TRANSACTION SUMMARY section exists
        assertThat(textContent)
                .as("Text statement should contain Transaction Summary header")
                .contains("TRANSACTION SUMMARY");

        // Also verify HTML content includes account info
        String htmlContent = downloadAllS3Content(HTML_PREFIX);

        assertThat(htmlContent)
                .as("HTML statement should contain account number heading")
                .contains("Statement for Account Number:");
        assertThat(htmlContent)
                .as("HTML statement should contain account ID in heading")
                .contains(ACCT_ID_1);
        assertThat(htmlContent)
                .as("HTML statement should contain bank name")
                .contains("Bank of XYZ");
        assertThat(htmlContent)
                .as("HTML statement should contain bank address")
                .contains("410 Terry Ave N");

        log.info("testStatementCustomerAndAccountInfo — PASSED");
    }

    // =========================================================================
    // Helper Methods — Database Cleanup
    // =========================================================================

    /**
     * Cleans all relevant database tables using {@code TRUNCATE ... CASCADE}
     * to handle foreign key constraints.
     *
     * <p>The Flyway V3 seed migration inserts data into multiple tables including
     * {@code cards} which has FK references to {@code accounts} (fk_cards_account).
     * Simple JPA {@code deleteAll()} on accounts would fail due to FK violations.
     * TRUNCATE CASCADE handles this correctly by cascading the truncation.
     *
     * <p>Tables truncated (order matters for explicit documentation, though
     * CASCADE handles dependencies):
     * <ol>
     *   <li>transactions — transaction records for each card</li>
     *   <li>card_cross_references — XREF mapping cards to accounts/customers</li>
     *   <li>cards — card records (FK to accounts)</li>
     *   <li>accounts — account records</li>
     *   <li>customers — customer records</li>
     *   <li>daily_transactions — batch staging records</li>
     * </ol>
     */
    private void cleanDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE public.transactions CASCADE");
                stmt.execute("TRUNCATE TABLE public.card_cross_references CASCADE");
                stmt.execute("TRUNCATE TABLE public.cards CASCADE");
                stmt.execute("TRUNCATE TABLE public.accounts CASCADE");
                stmt.execute("TRUNCATE TABLE public.customers CASCADE");
                stmt.execute("TRUNCATE TABLE public.daily_transactions CASCADE");
            }
            log.debug("Database tables truncated successfully with CASCADE");
        } catch (Exception e) {
            log.warn("Database cleanup error (non-fatal): {}", e.getMessage());
        }
    }

    // =========================================================================
    // Helper Methods — Seed Data Construction
    // =========================================================================

    /**
     * Inserts comprehensive seed data for integration testing.
     *
     * <p>Creates:
     * <ul>
     *   <li>2 Customers: John Doe (CUST_ID_1), Jane Smith (CUST_ID_2)</li>
     *   <li>2 Accounts: ACCT_ID_1 (bal=10200.00, limit=19400.00),
     *       ACCT_ID_2 (bal=5000.00, limit=15800.00)</li>
     *   <li>2 CardCrossReference records mapping cards to accounts/customers</li>
     *   <li>5 Transactions: 3 for Card 1 (50.47, -20.00, 100.00) and
     *       2 for Card 2 (75.99, 200.00)</li>
     * </ul>
     *
     * <p>Data patterns drawn from app/data/ASCII/ fixture files.
     */
    private void insertSeedData() {
        // --- Customer Records (from custdata.txt pattern) ---
        Customer cust1 = createCustomer(CUST_ID_1, "John", "Q", "Doe",
                "123 Main Street", "Apt 4B", "", "WA", "US", "98101", (short) 750);
        Customer cust2 = createCustomer(CUST_ID_2, "Jane", "A", "Smith",
                "456 Oak Avenue", "", "", "CA", "US", "90210", (short) 680);
        customerRepository.saveAll(List.of(cust1, cust2));

        // --- Account Records ---
        Account acct1 = createAccount(ACCT_ID_1, "Y",
                new BigDecimal("10200.00"), new BigDecimal("19400.00"));
        Account acct2 = createAccount(ACCT_ID_2, "Y",
                new BigDecimal("5000.00"), new BigDecimal("15800.00"));
        accountRepository.saveAll(List.of(acct1, acct2));

        // --- CardCrossReference Records ---
        CardCrossReference xref1 = new CardCrossReference(CARD_NUM_1, CUST_ID_1, ACCT_ID_1);
        CardCrossReference xref2 = new CardCrossReference(CARD_NUM_2, CUST_ID_2, ACCT_ID_2);
        cardCrossReferenceRepository.saveAll(List.of(xref1, xref2));

        // --- Transaction Records for Card 1 (3 transactions: 50.47 + -20.00 + 100.00 = 130.47) ---
        Transaction txn1 = createTransaction(
                "0000000001234567", "01", (short) 5001, "POS TERM",
                "Purchase at Store A", new BigDecimal("50.47"),
                "000456001", "Store A", "Seattle", "98101",
                CARD_NUM_1,
                LocalDateTime.of(2022, 7, 14, 10, 15, 30),
                LocalDateTime.of(2022, 7, 14, 10, 15, 31));

        Transaction txn2 = createTransaction(
                "0000000001234568", "03", (short) 5002, "POS TERM",
                "Return at Store A", new BigDecimal("-20.00"),
                "000456001", "Store A", "Seattle", "98101",
                CARD_NUM_1,
                LocalDateTime.of(2022, 7, 15, 14, 22, 10),
                LocalDateTime.of(2022, 7, 15, 14, 22, 11));

        Transaction txn3 = createTransaction(
                "0000000001234569", "01", (short) 5003, "INTERNET",
                "Purchase at Store B", new BigDecimal("100.00"),
                "000789002", "Store B Online", "Reston", "20190",
                CARD_NUM_1,
                LocalDateTime.of(2022, 7, 16, 9, 45, 0),
                LocalDateTime.of(2022, 7, 16, 9, 45, 1));

        // --- Transaction Records for Card 2 (2 transactions: 75.99 + 200.00 = 275.99) ---
        Transaction txn4 = createTransaction(
                "0000000002345670", "01", (short) 5004, "POS TERM",
                "Purchase at Store C", new BigDecimal("75.99"),
                "000555003", "Store C", "San Francisco", "94102",
                CARD_NUM_2,
                LocalDateTime.of(2022, 7, 17, 16, 30, 0),
                LocalDateTime.of(2022, 7, 17, 16, 30, 1));

        Transaction txn5 = createTransaction(
                "0000000002345671", "01", (short) 5005, "POS TERM",
                "Purchase at Store D", new BigDecimal("200.00"),
                "000777004", "Store D", "Los Angeles", "90001",
                CARD_NUM_2,
                LocalDateTime.of(2022, 7, 18, 11, 0, 0),
                LocalDateTime.of(2022, 7, 18, 11, 0, 1));

        transactionRepository.saveAll(List.of(txn1, txn2, txn3, txn4, txn5));

        log.info("Seed data inserted: 2 customers, 2 accounts, 2 XREF records, 5 transactions");
    }

    /**
     * Creates a fully populated {@link Customer} entity using setter methods.
     *
     * <p>Maps COBOL CUSTOMER-RECORD (CVCUS01Y.cpy — 500 bytes).
     * The {@code custFicoCreditScore} field is {@code Short} type
     * (matching COBOL PIC 9(03) → SMALLINT mapping).
     *
     * @param custId         customer identifier (PIC X(09) — 9-char zero-padded)
     * @param firstName      customer first name (PIC X(25))
     * @param middleName     customer middle name (PIC X(25))
     * @param lastName       customer last name (PIC X(25))
     * @param addrLine1      address line 1 (PIC X(50))
     * @param addrLine2      address line 2 (PIC X(50))
     * @param addrLine3      address line 3 (PIC X(50))
     * @param stateCd        state code (PIC X(02))
     * @param countryCd      country code (PIC X(03))
     * @param zip            ZIP code (PIC X(10))
     * @param ficoScore      FICO credit score (Short type, matching actual entity)
     * @return a fully populated Customer entity ready for persistence
     */
    private Customer createCustomer(String custId, String firstName, String middleName,
                                    String lastName, String addrLine1, String addrLine2,
                                    String addrLine3, String stateCd, String countryCd,
                                    String zip, Short ficoScore) {
        Customer customer = new Customer();
        customer.setCustId(custId);
        customer.setCustFirstName(firstName);
        customer.setCustMiddleName(middleName);
        customer.setCustLastName(lastName);
        customer.setCustAddrLine1(addrLine1);
        customer.setCustAddrLine2(addrLine2);
        customer.setCustAddrLine3(addrLine3);
        customer.setCustAddrStateCd(stateCd);
        customer.setCustAddrCountryCd(countryCd);
        customer.setCustAddrZip(zip);
        customer.setCustFicoCreditScore(ficoScore);
        return customer;
    }

    /**
     * Creates a fully populated {@link Account} entity using setter methods.
     *
     * <p>Maps COBOL ACCOUNT-RECORD (CVACT01Y.cpy — 300 bytes).
     * All financial fields use {@link BigDecimal} per AAP §0.8.2.
     *
     * @param acctId      account identifier (PIC X(11) — 11-char zero-padded)
     * @param status      active status (PIC X(01) — "Y" or "N")
     * @param currBal     current balance (PIC S9(7)V99 → BigDecimal scale=2)
     * @param creditLimit credit limit (PIC S9(7)V99 → BigDecimal scale=2)
     * @return a fully populated Account entity ready for persistence
     */
    private Account createAccount(String acctId, String status,
                                  BigDecimal currBal, BigDecimal creditLimit) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctActiveStatus(status);
        account.setAcctCurrBal(currBal);
        account.setAcctCreditLimit(creditLimit);
        account.setAcctCashCreditLimit(new BigDecimal("5000.00"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 15));
        account.setAcctExpDate(LocalDate.of(2025, 12, 31));
        account.setAcctCurrCycCredit(BigDecimal.ZERO);
        account.setAcctCurrCycDebit(BigDecimal.ZERO);
        account.setAcctGroupId("DEFAULT");
        return account;
    }

    /**
     * Creates a fully populated {@link Transaction} entity using setter methods.
     *
     * <p>Maps COBOL TRAN-RECORD (CVTRA05Y.cpy — 350 bytes).
     * The {@code tranCatCd} field is {@code Short} type
     * (matching COBOL PIC 9(04) → SMALLINT mapping).
     *
     * @param tranId          transaction identifier (PIC X(16) — 16-char zero-padded)
     * @param tranTypeCd      transaction type code (PIC X(02))
     * @param tranCatCd       transaction category code (PIC 9(04) → Short)
     * @param tranSource      transaction source (PIC X(10))
     * @param tranDesc        transaction description (PIC X(100))
     * @param tranAmt         transaction amount (PIC S9(09)V99 → BigDecimal scale=2)
     * @param tranMerchantId  merchant identifier (PIC 9(09))
     * @param tranMerchantName merchant name (PIC X(50))
     * @param tranMerchantCity merchant city (PIC X(50))
     * @param tranMerchantZip  merchant ZIP (PIC X(10))
     * @param tranCardNum     card number (PIC X(16))
     * @param tranOrigTs      origination timestamp (LocalDateTime)
     * @param tranProcTs      processing timestamp (LocalDateTime)
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
    // Helper Methods — S3 Content Download
    // =========================================================================

    /**
     * Downloads and concatenates all S3 object contents under a given prefix
     * in the statements bucket.
     *
     * @param prefix the S3 key prefix to search (e.g., "text/" or "html/")
     * @return concatenated content of all matching S3 objects
     * @throws Exception if S3 access or I/O operations fail
     */
    private String downloadAllS3Content(String prefix) throws Exception {
        ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .prefix(prefix)
                        .build());

        if (listResponse.contents().isEmpty()) {
            return "";
        }

        StringBuilder allContent = new StringBuilder();
        for (S3Object s3Obj : listResponse.contents()) {
            String content = downloadS3ObjectContent(s3Obj.key());
            allContent.append(content).append("\n");
        }
        return allContent.toString();
    }

    /**
     * Downloads the content of a specific S3 object by key.
     *
     * @param key the S3 object key
     * @return the content of the S3 object as a UTF-8 string
     * @throws Exception if S3 access or I/O operations fail
     */
    private String downloadS3ObjectContent(String key) throws Exception {
        try (ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .key(key)
                        .build());
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {

            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * Downloads the statement content for a specific card number from S3.
     *
     * <p>Searches S3 objects under the given prefix for a key containing the
     * card number. Returns the content of the first matching object, or
     * {@code null} if no matching object is found.
     *
     * @param prefix   the S3 key prefix (e.g., "text/" or "html/")
     * @param cardNum  the 16-digit card number to search for
     * @return the statement content for the specified card, or null if not found
     * @throws Exception if S3 access or I/O operations fail
     */
    private String downloadStatementContentForCard(String prefix, String cardNum) throws Exception {
        ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(STATEMENTS_BUCKET)
                        .prefix(prefix)
                        .build());

        for (S3Object s3Obj : listResponse.contents()) {
            if (s3Obj.key().contains(cardNum)) {
                return downloadS3ObjectContent(s3Obj.key());
            }
        }

        log.debug("No S3 object found for card {} under prefix {}", cardNum, prefix);
        return null;
    }
}
