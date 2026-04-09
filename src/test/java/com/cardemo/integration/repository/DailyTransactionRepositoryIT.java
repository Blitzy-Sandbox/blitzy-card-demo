package com.cardemo.integration.repository;

import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.repository.DailyTransactionRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link DailyTransactionRepository} verifying the staging table
 * lifecycle operations: bulk insert (saveAll), batch read (findAll), and cleanup (deleteAll).
 *
 * <p>This entity represents the DALYTRAN.PS sequential staging file used by the POSTTRAN.jcl
 * batch job. The staging table lifecycle is: load → process → clean.</p>
 *
 * <p>All tests run against a real PostgreSQL 16 instance via Testcontainers with Flyway
 * migrations provisioning the schema (V1) and seed data (V3 — 300 daily transaction records
 * parsed from app/data/ASCII/dailytran.txt).</p>
 *
 * <p>CRITICAL per AAP §0.8.2: All BigDecimal assertions use {@code compareTo()} — NEVER
 * {@code equals()} due to scale sensitivity.</p>
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class DailyTransactionRepositoryIT {

    @Container
    static PostgreSQLContainer postgresContainer =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Disable autoCommit so @DataJpaTest @Transactional rollback works correctly
        // with PostgreSQL — HikariCP defaults to autoCommit=true which prevents rollback
        registry.add("spring.datasource.hikari.auto-commit", () -> "false");
    }

    @Autowired
    private DailyTransactionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    // -----------------------------------------------------------------------
    // Helper: create a fully-populated DailyTransaction with a unique ID suffix
    // -----------------------------------------------------------------------
    private DailyTransaction createDailyTransaction(String idSuffix,
                                                     String typeCd,
                                                     short catCd,
                                                     String source,
                                                     String description,
                                                     BigDecimal amount,
                                                     String merchantId,
                                                     String merchantName,
                                                     String merchantCity,
                                                     String merchantZip,
                                                     String cardNum,
                                                     LocalDateTime origTs,
                                                     LocalDateTime procTs) {
        DailyTransaction txn = new DailyTransaction();
        txn.setDalytranId(idSuffix);
        txn.setDalytranTypeCd(typeCd);
        txn.setDalytranCatCd(catCd);
        txn.setDalytranSource(source);
        txn.setDalytranDesc(description);
        txn.setDalytranAmt(amount);
        txn.setDalytranMerchantId(merchantId);
        txn.setDalytranMerchantName(merchantName);
        txn.setDalytranMerchantCity(merchantCity);
        txn.setDalytranMerchantZip(merchantZip);
        txn.setDalytranCardNum(cardNum);
        txn.setDalytranOrigTs(origTs);
        txn.setDalytranProcTs(procTs);
        return txn;
    }

    // -----------------------------------------------------------------------
    // Test 1: Bulk insert — simulates POSTTRAN.jcl loading daily transactions
    // from S3/file into the staging table
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("saveAll — bulk insert simulating POSTTRAN.jcl S3 file load into staging table")
    void testSave_BulkInsert() {
        // Capture the current count (V3 seed data may already be present)
        long countBefore = repository.count();

        // Create 5 daily transaction records mimicking daily transaction file records
        List<DailyTransaction> transactions = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 1; i <= 5; i++) {
            String id = String.format("BULK%012d", i);
            transactions.add(createDailyTransaction(
                    id,
                    "01",                                       // Purchase type
                    (short) 1,                                  // Category code
                    "POS TERM",                                 // Source
                    "Bulk insert test record " + i,             // Description
                    new BigDecimal("100.00").add(new BigDecimal(String.valueOf(i))), // Amount varies
                    "900000001",                                // Merchant ID
                    "Bulk Merchant " + i,                       // Merchant name
                    "TestCity",                                 // Merchant city
                    "10001",                                    // Merchant ZIP
                    String.format("4111111111%06d", i),         // Card number
                    now,                                        // Origination timestamp
                    null                                        // proc_ts null = unprocessed
            ));
        }

        // Execute bulk save — mirrors saveAll() usage for S3 file load
        List<DailyTransaction> saved = repository.saveAll(transactions);
        entityManager.flush();

        // Verify all 5 records saved successfully
        assertThat(saved).hasSize(5);
        for (DailyTransaction txn : saved) {
            assertThat(txn.getDalytranId()).isNotNull();
            assertThat(txn.getDalytranTypeCd()).isEqualTo("01");
            assertThat(txn.getDalytranCatCd()).isEqualTo((short) 1);
            assertThat(txn.getDalytranSource()).isEqualTo("POS TERM");
            assertThat(txn.getDalytranCardNum()).startsWith("4111111111");
        }

        // Verify count includes the new records
        long countAfter = repository.count();
        assertThat(countAfter).isEqualTo(countBefore + 5);
    }

    // -----------------------------------------------------------------------
    // Test 2: Batch processing read — reads all staged daily transactions
    // Relies on V3 seed data (300 records from dailytran.txt)
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("findAll — batch processing reads all 300+ staged daily transactions from V3 seed data")
    void testFindAll_BatchProcessing() {
        // V3 migration seeds 300 daily_transactions records from dailytran.txt
        List<DailyTransaction> allRecords = repository.findAll();

        // Assert at least 300 records from seed data
        assertThat(allRecords).hasSizeGreaterThanOrEqualTo(300);

        // Validate field structure and constraints on each record
        allRecords.forEach(record -> {
            // dalytranId — PIC X(16), VARCHAR(16) NOT NULL PK
            assertThat(record.getDalytranId())
                    .isNotNull()
                    .hasSizeLessThanOrEqualTo(16);

            // dalytranTypeCd — PIC X(02), CHAR(2) NOT NULL
            assertThat(record.getDalytranTypeCd())
                    .isNotNull()
                    .hasSizeLessThanOrEqualTo(2);

            // dalytranAmt — PIC S9(09)V99, NUMERIC(11,2) NOT NULL
            // CRITICAL AAP §0.8.2: BigDecimal assertions use compareTo(), never equals()
            assertThat(record.getDalytranAmt()).isNotNull();

            // dalytranCardNum — PIC X(16), VARCHAR(16) NOT NULL
            assertThat(record.getDalytranCardNum())
                    .isNotNull()
                    .hasSizeLessThanOrEqualTo(16);

            // dalytranCatCd — PIC 9(04), SMALLINT NOT NULL
            assertThat(record.getDalytranCatCd()).isNotNull();
        });

        // Spot-check a known seed data record if present
        // dailytran.txt first record: tran_id = '0000000000683580', amount = 504.77
        Optional<DailyTransaction> knownRecord = repository.findById("0000000000683580");
        if (knownRecord.isPresent()) {
            DailyTransaction seedRecord = knownRecord.get();
            assertThat(seedRecord.getDalytranTypeCd()).isEqualTo("01");
            assertThat(seedRecord.getDalytranSource()).isEqualTo("POS TERM");
            // compareTo() per AAP §0.8.2 — NEVER equals()
            assertThat(seedRecord.getDalytranAmt().compareTo(new BigDecimal("504.77"))).isZero();
            assertThat(seedRecord.getDalytranCardNum()).isEqualTo("4859452612877065");
            // All V3 seed data has proc_ts = NULL (unprocessed staging records)
            assertThat(seedRecord.getDalytranProcTs()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: Staging table cleanup after batch processing
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("deleteAll — staging table cleanup after batch processing completes")
    void testDeleteAll_Cleanup() {
        // Save several test records to ensure records exist
        List<DailyTransaction> testRecords = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 1; i <= 3; i++) {
            String id = String.format("DEL_%011d", i);
            testRecords.add(createDailyTransaction(
                    id,
                    "02",                                // Return type
                    (short) 2,                           // Category code
                    "OPERATOR",                          // Source
                    "Cleanup test record " + i,          // Description
                    new BigDecimal("50.00"),              // Amount
                    "800000002",                          // Merchant ID
                    "Cleanup Merchant",                  // Merchant name
                    "CleanCity",                          // Merchant city
                    "20002",                             // Merchant ZIP
                    String.format("5500000000%06d", i),  // Card number
                    now,                                 // Origination timestamp
                    null                                 // Unprocessed
            ));
        }
        repository.saveAll(testRecords);
        entityManager.flush();

        // Confirm records are present before cleanup
        assertThat(repository.count()).isGreaterThan(0);

        // Execute staging table cleanup — deleteAll()
        repository.deleteAll();
        entityManager.flush();

        // Verify count is 0 after deleteAll — staging table fully cleaned
        assertThat(repository.count()).isZero();
    }

    // -----------------------------------------------------------------------
    // Test 4: Single record save and retrieve with all 13 fields
    // Verifies true database round-trip with flush/clear
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("save/findById — single record round-trip with all 13 DALYTRAN-RECORD fields")
    void testSaveAndRetrieve_SingleRecord() {
        // Create a DailyTransaction with all 13 fields populated per CVTRA06Y.cpy layout
        DailyTransaction txn = new DailyTransaction();
        txn.setDalytranId("0000000100000001");                          // PIC X(16)
        txn.setDalytranTypeCd("01");                                    // Purchase
        txn.setDalytranCatCd((short) 1);                                // Category
        txn.setDalytranSource("POS TERM");                              // PIC X(10)
        txn.setDalytranDesc("Test daily transaction");                  // PIC X(100)
        txn.setDalytranAmt(new BigDecimal("504.77"));                   // PIC S9(09)V99
        txn.setDalytranMerchantId("000000001");                         // PIC 9(09)
        txn.setDalytranMerchantName("Test Merchant");                   // Merchant name
        txn.setDalytranMerchantCity("Test City");                       // Merchant city
        txn.setDalytranMerchantZip("10001");                            // Merchant ZIP
        txn.setDalytranCardNum("4111111111111111");                     // PIC X(16)
        LocalDateTime origTimestamp = LocalDateTime.of(2024, 3, 15, 10, 30, 0);
        txn.setDalytranOrigTs(origTimestamp);                           // PIC X(26) timestamp
        txn.setDalytranProcTs(null);                                    // NULL = unprocessed

        // Save, flush persistence context, then clear L1 cache for true DB round-trip
        repository.save(txn);
        entityManager.flush();
        entityManager.clear();

        // Retrieve by primary key
        Optional<DailyTransaction> found = repository.findById("0000000100000001");
        assertThat(found).isPresent();

        DailyTransaction retrieved = found.get();

        // Assert all 13 fields are preserved after round-trip
        assertThat(retrieved.getDalytranId()).isEqualTo("0000000100000001");
        assertThat(retrieved.getDalytranTypeCd()).isEqualTo("01");
        assertThat(retrieved.getDalytranCatCd()).isEqualTo((short) 1);
        assertThat(retrieved.getDalytranSource()).isEqualTo("POS TERM");
        assertThat(retrieved.getDalytranDesc()).isEqualTo("Test daily transaction");

        // CRITICAL AAP §0.8.2: BigDecimal amount uses compareTo() — NEVER equals()
        assertThat(retrieved.getDalytranAmt().compareTo(new BigDecimal("504.77"))).isZero();

        assertThat(retrieved.getDalytranMerchantId()).isEqualTo("000000001");
        assertThat(retrieved.getDalytranMerchantName()).isEqualTo("Test Merchant");
        assertThat(retrieved.getDalytranMerchantCity()).isEqualTo("Test City");
        assertThat(retrieved.getDalytranMerchantZip()).isEqualTo("10001");
        assertThat(retrieved.getDalytranCardNum()).isEqualTo("4111111111111111");
        assertThat(retrieved.getDalytranOrigTs()).isEqualTo(origTimestamp);

        // Null procTs preserved — this is an unprocessed staging record
        assertThat(retrieved.getDalytranProcTs()).isNull();
    }

    // -----------------------------------------------------------------------
    // Test 5: BigDecimal precision — CRITICAL per AAP §0.8.2
    // COBOL PIC S9(09)V99 → NUMERIC(11,2) → BigDecimal
    // Zero floating-point substitution. All assertions use compareTo().
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("BigDecimal amount precision — max value 99999999.99, compareTo() verification (AAP §0.8.2)")
    void testBigDecimalAmountPrecision() {
        // Create daily transaction with maximum COBOL amount value
        // PIC S9(09)V99 max unsigned = 999999999.99 but NUMERIC(11,2) max = 999999999.99
        // Test with large representative value within NUMERIC(11,2) precision
        DailyTransaction txn = new DailyTransaction();
        txn.setDalytranId("PRECTEST00000001");
        txn.setDalytranTypeCd("01");
        txn.setDalytranCatCd((short) 1);
        txn.setDalytranSource("POS TERM");
        txn.setDalytranDesc("BigDecimal precision test");
        txn.setDalytranAmt(new BigDecimal("99999999.99"));  // Near-max precision value
        txn.setDalytranMerchantId("999999999");
        txn.setDalytranMerchantName("Precision Merchant");
        txn.setDalytranMerchantCity("Precision City");
        txn.setDalytranMerchantZip("99999");
        txn.setDalytranCardNum("4999999999999999");
        txn.setDalytranOrigTs(LocalDateTime.now());
        txn.setDalytranProcTs(null);

        // Save, flush, clear for true database round-trip
        repository.save(txn);
        entityManager.flush();
        entityManager.clear();

        // Retrieve and verify BigDecimal precision is preserved
        Optional<DailyTransaction> found = repository.findById("PRECTEST00000001");
        assertThat(found).isPresent();

        DailyTransaction retrieved = found.get();

        // CRITICAL AAP §0.8.2: compareTo() == 0, NEVER equals()
        // BigDecimal("99999999.99") must survive PostgreSQL NUMERIC(11,2) round-trip
        assertThat(retrieved.getDalytranAmt().compareTo(new BigDecimal("99999999.99"))).isZero();

        // Verify scale is preserved (2 decimal places per COBOL PIC S9(09)V99)
        assertThat(retrieved.getDalytranAmt().scale()).isLessThanOrEqualTo(2);

        // Additional precision check: verify no floating-point corruption
        // If float/double were used, 99999999.99 could become 99999999.98999... or 100000000.0
        assertThat(retrieved.getDalytranAmt().compareTo(new BigDecimal("99999999.98"))).isGreaterThan(0);
        assertThat(retrieved.getDalytranAmt().compareTo(new BigDecimal("100000000.00"))).isLessThan(0);
    }

    // -----------------------------------------------------------------------
    // Test 6: Complete staging table lifecycle — Load → Process → Clean
    // Mirrors the POSTTRAN.jcl batch pipeline: S3 file load → processing → cleanup
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("staging lifecycle — load 10 records → findAll verify → deleteAll cleanup → count == 0")
    void testStagingTableLifecycle_LoadProcessClean() {
        // Phase 0: Clean slate — remove any existing records for predictable test
        repository.deleteAll();
        entityManager.flush();
        assertThat(repository.count()).isZero();

        // Phase 1: LOAD — simulate S3 file load inserting 10 records into staging table
        List<DailyTransaction> staged = new ArrayList<>();
        LocalDateTime loadTimestamp = LocalDateTime.of(2024, 6, 15, 8, 0, 0);
        for (int i = 1; i <= 10; i++) {
            String id = String.format("STAGE%011d", i);
            staged.add(createDailyTransaction(
                    id,
                    i <= 7 ? "01" : "02",                           // Mix of purchases and returns
                    (short) (i % 5 + 1),                             // Vary categories 1-5
                    i <= 7 ? "POS TERM" : "OPERATOR",                // Vary sources
                    "Lifecycle test transaction " + i,               // Description
                    new BigDecimal(String.valueOf(100 + i * 10))     // Amounts: 110, 120, ..., 200
                            .add(new BigDecimal("0.50")),            // e.g., 110.50, 120.50, etc.
                    String.format("%09d", 100000000 + i),            // Merchant IDs
                    "Lifecycle Merchant " + i,                       // Merchant names
                    "LifecycleCity",                                 // Merchant city
                    String.format("%05d", 10000 + i),                // Merchant ZIPs
                    String.format("4000000000%06d", i),              // Card numbers
                    loadTimestamp.plusMinutes(i),                     // Stagger origination times
                    null                                             // proc_ts NULL = unprocessed
            ));
        }
        repository.saveAll(staged);
        entityManager.flush();
        entityManager.clear();

        // Verify: 10 records loaded into staging table
        List<DailyTransaction> loaded = repository.findAll();
        assertThat(loaded).hasSize(10);

        // Verify each loaded record has expected unprocessed state
        loaded.forEach(record -> {
            assertThat(record.getDalytranId()).startsWith("STAGE");
            assertThat(record.getDalytranProcTs()).isNull();   // Unprocessed
            assertThat(record.getDalytranAmt()).isNotNull();
            // Verify amounts are within expected range using compareTo() per AAP §0.8.2
            assertThat(record.getDalytranAmt().compareTo(new BigDecimal("100.00"))).isGreaterThan(0);
            assertThat(record.getDalytranAmt().compareTo(new BigDecimal("300.00"))).isLessThan(0);
        });

        // Phase 2: PROCESS — simulate batch processing (read all records)
        // In real batch processing, records would be validated and posted to transactions table.
        // Here we verify the read pathway works correctly.
        List<DailyTransaction> forProcessing = repository.findAll();
        assertThat(forProcessing).hasSize(10);
        forProcessing.forEach(record -> {
            // Verify fields are readable and non-null for mandatory columns
            assertThat(record.getDalytranId()).isNotBlank();
            assertThat(record.getDalytranTypeCd()).isNotBlank();
            assertThat(record.getDalytranCardNum()).isNotBlank();
            assertThat(record.getDalytranCatCd()).isNotNull();
        });

        // Phase 3: CLEAN — staging table cleanup after batch processing completes
        repository.deleteAll();
        entityManager.flush();

        // Verify staging table is empty after cleanup
        assertThat(repository.count()).isZero();
    }
}
