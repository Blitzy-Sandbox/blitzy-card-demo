/*
 * InterestCalculationJobIT.java
 *
 * Full integration test for InterestCalculationJob — Stage 2 of the CardDemo
 * 5-stage batch pipeline, migrated from INTCALC.jcl + CBACT04C.cbl.
 *
 * Tests verify: specific group rate calculation, DEFAULT fallback, zero-rate
 * skipping, account break detection with multiple accounts, PARM date
 * propagation, and BigDecimal precision with RoundingMode.HALF_EVEN.
 *
 * COBOL Traceability:
 * - INTCALC.jcl  — JCL job definition (EXEC PGM=CBACT04C,PARM='2022071800')
 * - CBACT04C.cbl — Interest calculation batch program (652 lines)
 * - CVTRA01Y.cpy — Transaction category balance record layout (input)
 * - CVTRA02Y.cpy — Disclosure group record layout (interest rates)
 * - CVTRA05Y.cpy — Transaction record layout (output)
 * - CVACT01Y.cpy — Account record layout
 * - CVACT03Y.cpy — Card cross-reference record layout
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.integration.batch;

// Internal imports — strictly from depends_on_files
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;

// JUnit 5 — test framework annotations
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// SLF4J — structured logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Spring Batch Core — job execution types
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;

// Spring Batch Test — test utilities
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;

// Spring Framework — DI, config, and test annotations
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Testcontainers — PostgreSQL + LocalStack
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// AWS SDK v2 — S3 client for bucket operations
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

// Java Standard Library
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// AssertJ — fluent assertions
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Interest Calculation Job (Stage 2 of 5-stage batch pipeline).
 *
 * <p>Verifies the complete interest calculation workflow migrated from INTCALC.jcl +
 * CBACT04C.cbl: reads {@link TransactionCategoryBalance} records, looks up disclosure
 * group interest rates with DEFAULT fallback, computes monthly interest using formula
 * {@code (balance × rate) / 1200} with {@link BigDecimal} precision and
 * {@link RoundingMode#HALF_EVEN} (banker's rounding, COBOL default), generates system
 * interest {@link Transaction} records, and updates {@link Account} balances.</p>
 *
 * <h3>Test Environment</h3>
 * <ul>
 *   <li>PostgreSQL 16 via Testcontainers (replaces VSAM KSDS datasets)</li>
 *   <li>LocalStack S3 via Testcontainers (replaces GDG generation output)</li>
 *   <li>Flyway V1-V3 migrations auto-run on container startup</li>
 *   <li>Spring Batch metadata schema manually initialized (batch schema workaround)</li>
 * </ul>
 *
 * @see com.cardemo.batch.jobs.InterestCalculationJob
 * @see com.cardemo.batch.processors.InterestCalculationProcessor
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@SpringBatchTest
@DisplayName("Interest Calculation Job Integration Test — INTCALC.jcl + CBACT04C.cbl")
class InterestCalculationJobIT {

    private static final Logger log = LoggerFactory.getLogger(InterestCalculationJobIT.class);

    /** S3 output bucket name matching default in InterestCalculationJob. */
    private static final String BATCH_OUTPUT_BUCKET = "carddemo-batch-output";

    // =========================================================================
    // Testcontainers — PostgreSQL 16 + LocalStack (S3)
    // =========================================================================

    @Container
    static PostgreSQLContainer postgresContainer =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices("s3");

    // =========================================================================
    // Dynamic Property Source — wires Testcontainers to Spring context
    // =========================================================================

    /**
     * Dynamically binds Testcontainer-assigned ports and URLs to Spring properties,
     * overriding static values from application-test.yml with actual container endpoints.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL datasource (replaces VSAM file I/O)
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.auto-commit", () -> "false");

        // AWS S3/SQS/SNS endpoints (LocalStack — replaces GDG output)
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

    // =========================================================================
    // Autowired Dependencies
    // =========================================================================

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobRepository jobRepository;

    /** Flag to ensure batch schema is initialized only once per test class. */
    private static boolean batchSchemaInitialized = false;

    // =========================================================================
    // Test Lifecycle — @BeforeEach / @AfterEach
    // =========================================================================

    /**
     * Initializes a clean test environment before each test method.
     *
     * <ol>
     *   <li>Initializes Spring Batch metadata schema (once per class)</li>
     *   <li>Creates a synchronous job launcher for deterministic testing</li>
     *   <li>Sets the specific interest calculation job on the test utils</li>
     *   <li>Creates the S3 output bucket (idempotent)</li>
     *   <li>Cleans Spring Batch job metadata from previous runs</li>
     *   <li>Truncates all test-relevant tables for a clean slate</li>
     * </ol>
     */
    @BeforeEach
    void setUp() throws Exception {
        // Step 1: Initialize Spring Batch metadata tables if not already done.
        // @EnableBatchProcessing disables BatchAutoConfiguration, so
        // spring.batch.jdbc.initialize-schema=always is NOT honored.
        if (!batchSchemaInitialized) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(
                    "/org/springframework/batch/core/schema-postgresql.sql"));
            populator.setContinueOnError(true);
            populator.execute(dataSource);
            batchSchemaInitialized = true;
            log.info("Spring Batch metadata schema initialized");
        }

        // Step 2: Create synchronous TaskExecutorJobLauncher for deterministic testing.
        // Ensures job completes before assertions run.
        TaskExecutorJobLauncher syncLauncher = new TaskExecutorJobLauncher();
        syncLauncher.setJobRepository(jobRepository);
        syncLauncher.setTaskExecutor(new SyncTaskExecutor());
        syncLauncher.afterPropertiesSet();
        jobLauncherTestUtils.setJobLauncher(syncLauncher);

        // Step 3: Set the specific interest calculation job (multiple Job beans exist)
        jobLauncherTestUtils.setJob(interestCalculationJob);

        // Step 4: Create S3 bucket (idempotent — silently handles already-exists)
        createS3BucketIfNotExists(BATCH_OUTPUT_BUCKET);

        // Step 5: Clean Spring Batch job metadata from previous runs
        jobRepositoryTestUtils.removeJobExecutions();

        // Step 6: Truncate all test-relevant tables (clean slate for each test)
        truncateAllTestTables();

        log.info("Test setup complete — clean database and S3 bucket ready");
    }

    /**
     * Cleans up test state after each test method to prevent leakage.
     */
    @AfterEach
    void tearDown() {
        // Clean up S3 objects created during test execution
        cleanS3Bucket(BATCH_OUTPUT_BUCKET);

        // Remove Spring Batch job metadata
        jobRepositoryTestUtils.removeJobExecutions();

        // Truncate all test tables to prevent state leakage
        truncateAllTestTables();

        log.info("Test teardown complete");
    }

    // =========================================================================
    // Test 1: Interest Calculation with Specific Group Rate
    // =========================================================================

    /**
     * Verifies that interest is calculated using the specific disclosure group rate
     * when a matching record exists for the account's group ID.
     *
     * <p>Maps to CBACT04C.cbl 1200-GET-INTEREST-RATE (lines 415-440):
     * first attempt with account-specific group ID succeeds.</p>
     *
     * <p>Formula: {@code 500.00 x 15.00 / 1200 = 6.25}</p>
     * <p>Verification: {@code BigDecimal.compareTo() == 0} per AAP 0.8.2</p>
     */
    @Test
    @DisplayName("Should calculate interest using specific disclosure group rate")
    void testInterestCalculationWithSpecificGroupRate() throws Exception {
        // Arrange - Account with specific group rate A000000000
        Account account = createTestAccount("00000000001", "Y",
                new BigDecimal("50000.00"), new BigDecimal("10200.00"),
                new BigDecimal("20200.00"), new BigDecimal("10200.00"),
                LocalDate.of(2025, 5, 20), "A000000000");
        accountRepository.save(account);

        // Insert customer to satisfy FK constraint on card_cross_references
        insertTestCustomer("000000050");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "0500024453765740", "000000050", "00000000001"));

        // Specific disclosure group: A000000000 / 01 / 0001 -> rate 15.00%
        disclosureGroupRepository.save(new DisclosureGroup(
                new DisclosureGroupId("A000000000", "01", (short) 1),
                new BigDecimal("15.00")));

        // TCATBAL record: account 1, typeCode 01, catCode 0001, balance 500.00
        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalanceId("00000000001", "01", (short) 1),
                new BigDecimal("500.00")));

        // Act - Launch interest calculation job with PARM date from INTCALC.jcl
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .addString("parmDate", "2022071800")
                        .toJobParameters());

        // Assert - Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert - Generated interest transaction
        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).hasSize(1);

        Transaction txn = transactions.get(0);
        // Transaction ID format: {parmDate}-{5-digit-suffix} per processor code
        assertThat(txn.getTranId()).startsWith("2022071800-");
        assertThat(txn.getTranTypeCd()).isEqualTo("01");
        // tranCatCd is Short type, value 5 (INTEREST_TRAN_CAT_CD in processor)
        assertThat(txn.getTranCatCd()).isEqualTo((short) 5);
        assertThat(txn.getTranSource()).isEqualTo("System");
        assertThat(txn.getTranDesc()).contains("Int. for a/c 00000000001");
        // Card number from XREF lookup (1110-GET-XREF-DATA in CBACT04C)
        assertThat(txn.getTranCardNum()).isEqualTo("0500024453765740");

        // Assert - Interest amount: 500.00 x 15.00 / 1200 = 6.25
        // Uses BigDecimal.compareTo() per AAP 0.8.2 (never equals() for BigDecimal)
        BigDecimal expectedInterest = new BigDecimal("500.00")
                .multiply(new BigDecimal("15.00"))
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
        assertThat(txn.getTranAmt().compareTo(expectedInterest)).isZero();
        assertThat(txn.getTranAmt().compareTo(new BigDecimal("6.25"))).isZero();

        // Assert - Account balance updated per CBACT04C 1050-UPDATE-ACCOUNT
        // Lines 350-354: ADD WS-TOTAL-INT TO ACCT-CURR-BAL,
        //                MOVE 0 TO ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT
        Account updatedAccount = accountRepository.findById("00000000001").orElseThrow();
        BigDecimal expectedBalance = new BigDecimal("10200.00").add(expectedInterest);
        assertThat(updatedAccount.getAcctCurrBal().compareTo(expectedBalance)).isZero();
        assertThat(updatedAccount.getAcctCurrCycCredit().compareTo(BigDecimal.ZERO)).isZero();
        assertThat(updatedAccount.getAcctCurrCycDebit().compareTo(BigDecimal.ZERO)).isZero();

        log.info("testInterestCalculationWithSpecificGroupRate PASSED: "
                + "interest=6.25, balance updated to {}", expectedBalance);
    }

    // =========================================================================
    // Test 2: Interest Calculation with DEFAULT Fallback
    // =========================================================================

    /**
     * Verifies that when no disclosure group exists for the account's specific group
     * ID, the processor falls back to the DEFAULT group for interest rate lookup.
     *
     * <p>Maps to CBACT04C.cbl 1200-A-GET-DEFAULT-INT-RATE (lines 440-460):
     * first attempt with account group fails (FILE STATUS '23'), retry with 'DEFAULT'.</p>
     *
     * <p>Formula: {@code 300.00 x 18.00 / 1200 = 4.50}</p>
     */
    @Test
    @DisplayName("Should fallback to DEFAULT group when specific group not found")
    void testInterestCalculationWithDefaultFallback() throws Exception {
        // Arrange - Account with group B000000000 (no disclosure group for this ID)
        Account account = createTestAccount("00000000002", "Y",
                new BigDecimal("25000.00"), new BigDecimal("5000.00"),
                new BigDecimal("8000.00"), new BigDecimal("5000.00"),
                LocalDate.of(2025, 12, 31), "B000000000");
        accountRepository.save(account);

        insertTestCustomer("000000002");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "0683586198171516", "000000002", "00000000002"));

        // Only DEFAULT disclosure group exists - no B000000000 group
        // This forces the DEFAULT fallback path in lookupInterestRate()
        disclosureGroupRepository.save(new DisclosureGroup(
                new DisclosureGroupId("DEFAULT", "01", (short) 1),
                new BigDecimal("18.00")));

        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalanceId("00000000002", "01", (short) 1),
                new BigDecimal("300.00")));

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .addString("parmDate", "2022071800")
                        .toJobParameters());

        // Assert - Job completed
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert - Generated interest transaction with DEFAULT rate
        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).hasSize(1);

        // Expected interest: 300.00 x 18.00 / 1200 = 4.50 (DEFAULT rate)
        BigDecimal expectedInterest = new BigDecimal("300.00")
                .multiply(new BigDecimal("18.00"))
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
        assertThat(transactions.get(0).getTranAmt().compareTo(expectedInterest)).isZero();
        assertThat(transactions.get(0).getTranAmt().compareTo(new BigDecimal("4.50"))).isZero();
        assertThat(transactions.get(0).getTranDesc()).contains("Int. for a/c 00000000002");
        assertThat(transactions.get(0).getTranCardNum()).isEqualTo("0683586198171516");

        // Assert - Account balance updated with DEFAULT fallback rate
        Account updatedAccount = accountRepository.findById("00000000002").orElseThrow();
        BigDecimal expectedBalance = new BigDecimal("5000.00").add(expectedInterest);
        assertThat(updatedAccount.getAcctCurrBal().compareTo(expectedBalance)).isZero();
        assertThat(updatedAccount.getAcctCurrCycCredit().compareTo(BigDecimal.ZERO)).isZero();
        assertThat(updatedAccount.getAcctCurrCycDebit().compareTo(BigDecimal.ZERO)).isZero();

        log.info("testInterestCalculationWithDefaultFallback PASSED: "
                + "DEFAULT rate used, interest=4.50");
    }

    // =========================================================================
    // Test 3: Zero Interest Rate Skips Calculation
    // =========================================================================

    /**
     * Verifies that when the disclosure group interest rate is zero, no interest
     * transaction is generated and the account balance is not modified.
     *
     * <p>Maps to CBACT04C.cbl line 214:
     * {@code IF DIS-INT-RATE NOT = 0} - only computes when rate is non-zero.
     * The processor returns null for zero-rate items (Spring Batch filters them),
     * and the account is NOT updated because totalInterest remains zero.</p>
     */
    @Test
    @DisplayName("Should skip interest computation when rate is zero")
    void testZeroInterestRateSkipsCalculation() throws Exception {
        // Arrange - Account with group that has zero interest rate
        Account account = createTestAccount("00000000003", "Y",
                new BigDecimal("25000.00"), new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), new BigDecimal("500.00"),
                LocalDate.of(2025, 12, 31), "Z000000000");
        accountRepository.save(account);

        insertTestCustomer("000000003");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "4000000000000003", "000000003", "00000000003"));

        // Zero interest rate disclosure group - processor returns null for this
        disclosureGroupRepository.save(new DisclosureGroup(
                new DisclosureGroupId("Z000000000", "01", (short) 1),
                new BigDecimal("0.00")));

        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalanceId("00000000003", "01", (short) 1),
                new BigDecimal("500.00")));

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .addString("parmDate", "2022071800")
                        .toJobParameters());

        // Assert - Job completed (zero-rate is not an error condition)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert - NO transaction generated (zero rate -> null from processor -> filtered)
        assertThat(transactionRepository.count()).isZero();

        // Assert - Account balance NOT modified
        // (totalInterest=0 -> updateAccount() skips per condition:
        //  totalInterest.compareTo(BigDecimal.ZERO) != 0)
        // cycCredit and cycDebit also remain UNCHANGED (not zeroed)
        Account unchangedAccount = accountRepository.findById("00000000003").orElseThrow();
        assertThat(unchangedAccount.getAcctCurrBal()
                .compareTo(new BigDecimal("5000.00"))).isZero();
        assertThat(unchangedAccount.getAcctCurrCycCredit()
                .compareTo(new BigDecimal("1000.00"))).isZero();
        assertThat(unchangedAccount.getAcctCurrCycDebit()
                .compareTo(new BigDecimal("500.00"))).isZero();

        log.info("testZeroInterestRateSkipsCalculation PASSED: "
                + "no transaction, balance unchanged");
    }

    // =========================================================================
    // Test 4: Account Break Detection with Multiple Accounts
    // =========================================================================

    /**
     * Verifies that the processor correctly detects account breaks when processing
     * TCATBAL records for multiple accounts, accumulating interest per account and
     * updating each account's balance independently.
     *
     * <p>Maps to CBACT04C.cbl lines 194-206: account break detection logic.
     * {@code IF TRANCAT-ACCT-ID NOT = WS-LAST-ACCT-NUM} - update previous account
     * (ADD WS-TOTAL-INT TO ACCT-CURR-BAL, MOVE 0 TO ACCT-CURR-CYC-CREDIT/DEBIT),
     * reset WS-TOTAL-INT to zero, load new account data.</p>
     *
     * <p>Account 1 (2 TCATBAL entries): interest = 6.25 + 25.00 = 31.25</p>
     * <p>Account 2 (1 TCATBAL entry, DEFAULT fallback): interest = 4.50</p>
     * <p>Total generated transactions: 3</p>
     */
    @Test
    @DisplayName("Should detect account break and accumulate interest per account")
    void testAccountBreakDetectionWithMultipleAccounts() throws Exception {
        // Arrange - Two accounts with different disclosure groups

        // Account 1: groupId=A000000000 (specific rates available)
        Account account1 = createTestAccount("00000000001", "Y",
                new BigDecimal("50000.00"), new BigDecimal("10200.00"),
                new BigDecimal("20200.00"), new BigDecimal("10200.00"),
                LocalDate.of(2025, 5, 20), "A000000000");
        accountRepository.save(account1);

        // Account 2: groupId=B000000000 (no specific group -> DEFAULT fallback)
        Account account2 = createTestAccount("00000000002", "Y",
                new BigDecimal("25000.00"), new BigDecimal("5000.00"),
                new BigDecimal("8000.00"), new BigDecimal("5000.00"),
                LocalDate.of(2025, 12, 31), "B000000000");
        accountRepository.save(account2);

        // Customers and XREFs for both accounts
        insertTestCustomer("000000050");
        insertTestCustomer("000000002");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "0500024453765740", "000000050", "00000000001"));
        cardCrossReferenceRepository.save(new CardCrossReference(
                "0683586198171516", "000000002", "00000000002"));

        // Disclosure groups: specific A000000000 rates + DEFAULT fallback
        disclosureGroupRepository.save(new DisclosureGroup(
                new DisclosureGroupId("A000000000", "01", (short) 1),
                new BigDecimal("15.00")));
        disclosureGroupRepository.save(new DisclosureGroup(
                new DisclosureGroupId("A000000000", "01", (short) 2),
                new BigDecimal("25.00")));
        disclosureGroupRepository.save(new DisclosureGroup(
                new DisclosureGroupId("DEFAULT", "01", (short) 1),
                new BigDecimal("18.00")));

        // TCATBAL records - reader sorts by id.acctId ASC, so account 1 before account 2
        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalanceId("00000000001", "01", (short) 1),
                new BigDecimal("500.00")));
        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalanceId("00000000001", "01", (short) 2),
                new BigDecimal("1200.00")));
        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalanceId("00000000002", "01", (short) 1),
                new BigDecimal("300.00")));

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .addString("parmDate", "2022071800")
                        .toJobParameters());

        // Assert - Job completed
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert - 3 transactions generated (2 for account 1, 1 for account 2)
        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).hasSize(3);

        // Verify all transactions follow the expected format
        for (Transaction txn : transactions) {
            assertThat(txn.getTranId()).startsWith("2022071800-");
            assertThat(txn.getTranTypeCd()).isEqualTo("01");
            assertThat(txn.getTranCatCd()).isEqualTo((short) 5);
            assertThat(txn.getTranSource()).isEqualTo("System");
        }

        // Assert - Account 1 balance updated with accumulated interest from both entries
        // Entry 1: 500.00 x 15.00 / 1200 = 6.25
        BigDecimal interest1a = new BigDecimal("500.00")
                .multiply(new BigDecimal("15.00"))
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
        // Entry 2: 1200.00 x 25.00 / 1200 = 25.00
        BigDecimal interest1b = new BigDecimal("1200.00")
                .multiply(new BigDecimal("25.00"))
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
        // Total interest for Account 1: 6.25 + 25.00 = 31.25
        BigDecimal totalInterest1 = interest1a.add(interest1b);
        assertThat(totalInterest1.compareTo(new BigDecimal("31.25"))).isZero();

        Account updatedAccount1 = accountRepository.findById("00000000001").orElseThrow();
        BigDecimal expectedBalance1 = new BigDecimal("10200.00").add(totalInterest1);
        assertThat(updatedAccount1.getAcctCurrBal().compareTo(expectedBalance1)).isZero();
        assertThat(updatedAccount1.getAcctCurrCycCredit().compareTo(BigDecimal.ZERO)).isZero();
        assertThat(updatedAccount1.getAcctCurrCycDebit().compareTo(BigDecimal.ZERO)).isZero();

        // Assert - Account 2 balance updated with DEFAULT fallback rate
        // 300.00 x 18.00 / 1200 = 4.50
        BigDecimal interest2 = new BigDecimal("300.00")
                .multiply(new BigDecimal("18.00"))
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
        assertThat(interest2.compareTo(new BigDecimal("4.50"))).isZero();

        Account updatedAccount2 = accountRepository.findById("00000000002").orElseThrow();
        BigDecimal expectedBalance2 = new BigDecimal("5000.00").add(interest2);
        assertThat(updatedAccount2.getAcctCurrBal().compareTo(expectedBalance2)).isZero();
        assertThat(updatedAccount2.getAcctCurrCycCredit().compareTo(BigDecimal.ZERO)).isZero();
        assertThat(updatedAccount2.getAcctCurrCycDebit().compareTo(BigDecimal.ZERO)).isZero();

        log.info("testAccountBreakDetectionWithMultipleAccounts PASSED: "
                + "account1 interest=31.25, account2 interest=4.50");
    }

    // =========================================================================
    // Test 5: PARM Date Parameter Propagation
    // =========================================================================

    /**
     * Verifies that the PARM date from job parameters is correctly propagated to
     * generated transaction IDs.
     *
     * <p>Maps to CBACT04C.cbl lines 474-480: PARM-DATE concatenated with
     * WS-TRANID-SUFFIX to form transaction IDs. In Java implementation:
     * {@code parmDate + "-" + String.format("%05d", suffix)}.</p>
     *
     * <p>This test launches the job with a different PARM date than the standard
     * test date to verify the date propagation independently.</p>
     */
    @Test
    @DisplayName("Should use PARM date for transaction ID generation")
    void testParmDateParameterPropagation() throws Exception {
        // Arrange - minimal setup for one interest calculation
        Account account = createTestAccount("00000000001", "Y",
                new BigDecimal("50000.00"), new BigDecimal("10000.00"),
                new BigDecimal("5000.00"), new BigDecimal("3000.00"),
                LocalDate.of(2025, 6, 30), "A000000000");
        accountRepository.save(account);

        insertTestCustomer("000000050");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "0500024453765740", "000000050", "00000000001"));

        disclosureGroupRepository.save(new DisclosureGroup(
                new DisclosureGroupId("A000000000", "01", (short) 1),
                new BigDecimal("15.00")));

        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalanceId("00000000001", "01", (short) 1),
                new BigDecimal("500.00")));

        // Act - Launch with DIFFERENT parm date to verify propagation
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .addString("parmDate", "2023010100")
                        .toJobParameters());

        // Assert - Job completed
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert - All transaction IDs start with the new PARM date
        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).isNotEmpty();
        for (Transaction txn : transactions) {
            assertThat(txn.getTranId()).startsWith("2023010100-");
        }

        log.info("testParmDateParameterPropagation PASSED: "
                + "all {} transaction IDs start with 2023010100-", transactions.size());
    }

    // =========================================================================
    // Test 6: Interest Formula Decimal Precision
    // =========================================================================

    /**
     * Verifies that BigDecimal precision is maintained in the interest calculation
     * formula, using values that would produce rounding differences with float/double.
     *
     * <p>Maps to AAP 0.8.2 - zero floating-point substitution. The formula
     * {@code (balance x rate) / 1200} must use {@link BigDecimal} with
     * {@link RoundingMode#HALF_EVEN} (banker's rounding, COBOL default).</p>
     *
     * <p>Test case: {@code 333.33 x 7.99 / 1200 = 2663.3067 / 1200 = 2.22}
     * (rounded to scale 2 with HALF_EVEN).</p>
     *
     * <p>If float/double were used: {@code 333.33 * 7.99 = 2663.3066999...}
     * which could produce a different rounding result.</p>
     */
    @Test
    @DisplayName("Should maintain BigDecimal precision in interest calculation")
    void testInterestFormulaDecimalPrecision() throws Exception {
        // Arrange - values that expose floating-point precision issues
        Account account = createTestAccount("00000000004", "Y",
                new BigDecimal("50000.00"), new BigDecimal("8000.00"),
                new BigDecimal("3000.00"), new BigDecimal("1500.00"),
                LocalDate.of(2025, 12, 31), "C000000000");
        accountRepository.save(account);

        insertTestCustomer("000000004");
        cardCrossReferenceRepository.save(new CardCrossReference(
                "4000000000000004", "000000004", "00000000004"));

        // Rate that creates non-terminating decimal division
        disclosureGroupRepository.save(new DisclosureGroup(
                new DisclosureGroupId("C000000000", "01", (short) 1),
                new BigDecimal("7.99")));

        // Balance that creates precision-sensitive multiplication
        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalanceId("00000000004", "01", (short) 1),
                new BigDecimal("333.33")));

        // Act
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addString("run.id", UUID.randomUUID().toString())
                        .addString("parmDate", "2022071800")
                        .toJobParameters());

        // Assert - Job completed
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert - Interest amount with BigDecimal precision
        // 333.33 x 7.99 = 2663.3067
        // 2663.3067 / 1200 = 2.219422... -> 2.22 (HALF_EVEN rounding at scale 2)
        BigDecimal expectedInterest = new BigDecimal("333.33")
                .multiply(new BigDecimal("7.99"))
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
        // Verify expected = 2.22
        assertThat(expectedInterest.compareTo(new BigDecimal("2.22"))).isZero();

        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).getTranAmt().compareTo(expectedInterest)).isZero();
        assertThat(transactions.get(0).getTranAmt().compareTo(new BigDecimal("2.22"))).isZero();

        // Assert - Account balance updated with precise interest
        Account updatedAccount = accountRepository.findById("00000000004").orElseThrow();
        BigDecimal expectedBalance = new BigDecimal("8000.00").add(expectedInterest);
        assertThat(updatedAccount.getAcctCurrBal().compareTo(expectedBalance)).isZero();
        assertThat(updatedAccount.getAcctCurrCycCredit().compareTo(BigDecimal.ZERO)).isZero();
        assertThat(updatedAccount.getAcctCurrCycDebit().compareTo(BigDecimal.ZERO)).isZero();

        log.info("testInterestFormulaDecimalPrecision PASSED: "
                + "333.33 x 7.99 / 1200 = {} (scale 2, HALF_EVEN)", expectedInterest);
    }

    // =========================================================================
    // Helper Methods - Test Data Creation
    // =========================================================================

    /**
     * Creates a test {@link Account} entity with the specified financial fields.
     * All monetary fields use {@link BigDecimal} per AAP 0.8.2 (zero floating-point).
     *
     * <p>Follows the same setter-based pattern as DailyTransactionPostingJobIT
     * ({@code createTestAccount} helper) - no all-args constructor used.</p>
     *
     * @param acctId       account ID (11 chars, zero-padded)
     * @param activeStatus active status flag ('Y' or 'N')
     * @param creditLimit  credit limit
     * @param currBal      current balance
     * @param cycCredit    current cycle credit total
     * @param cycDebit     current cycle debit total
     * @param expDate      account expiration date
     * @param groupId      disclosure group ID (10 chars)
     * @return the constructed Account entity (not yet persisted)
     */
    private Account createTestAccount(String acctId, String activeStatus,
                                       BigDecimal creditLimit, BigDecimal currBal,
                                       BigDecimal cycCredit, BigDecimal cycDebit,
                                       LocalDate expDate, String groupId) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctActiveStatus(activeStatus);
        account.setAcctCreditLimit(creditLimit);
        account.setAcctCurrBal(currBal);
        account.setAcctCashCreditLimit(creditLimit);
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        account.setAcctExpDate(expDate);
        account.setAcctCurrCycCredit(cycCredit);
        account.setAcctCurrCycDebit(cycDebit);
        account.setAcctGroupId(groupId);
        account.setAcctReissueDate(LocalDate.of(2022, 1, 1));
        return account;
    }

    // =========================================================================
    // Helper Methods - Database Operations
    // =========================================================================

    /**
     * Truncates all test-relevant tables with CASCADE to handle FK constraints.
     *
     * <p>Uses raw JDBC with explicit {@code setAutoCommit(true)} because the HikariCP
     * connection pool is configured with {@code auto-commit=false} via DynamicPropertySource.
     * Without this, TRUNCATE statements execute within an uncommitted transaction that is
     * rolled back when the connection returns to the pool.</p>
     */
    private void truncateAllTestTables() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE disclosure_groups CASCADE");
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
     * {@code card_cross_references.cust_id -> customers.cust_id} after TRUNCATE
     * clears the Flyway V3 seed data. Uses {@code ON CONFLICT DO NOTHING} for
     * idempotent re-execution within a test method.</p>
     *
     * @param custId the customer ID (VARCHAR primary key)
     */
    private void insertTestCustomer(String custId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(String.format(
                        "INSERT INTO customers (cust_id, first_name, last_name) "
                                + "VALUES ('%s', 'TEST', 'CUSTOMER') "
                                + "ON CONFLICT (cust_id) DO NOTHING",
                        custId));
            }
        } catch (SQLException e) {
            log.warn("Failed to insert test customer {}: {}", custId, e.getMessage());
        }
    }

    // =========================================================================
    // Helper Methods - S3 Operations
    // =========================================================================

    /**
     * Creates an S3 bucket in LocalStack if it does not already exist.
     * Silently handles the {@code BucketAlreadyOwnedByYou} error from S3.
     *
     * @param bucketName the name of the bucket to create
     */
    private void createS3BucketIfNotExists(String bucketName) {
        try {
            s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build());
            log.debug("Created S3 bucket: {}", bucketName);
        } catch (Exception e) {
            // BucketAlreadyOwnedByYou or BucketAlreadyExists - safe to ignore
            log.debug("S3 bucket already exists or creation skipped: {}", e.getMessage());
        }
    }

    /**
     * Removes all objects from the specified S3 bucket for cleanup.
     * Non-fatal - logs warnings on failure without propagating exceptions.
     *
     * @param bucketName the name of the bucket to clean
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
}
