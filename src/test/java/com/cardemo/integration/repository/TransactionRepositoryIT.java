package com.cardemo.integration.repository;

import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TransactionRepository} verifying CRUD operations,
 * paginated queries, date-range filtering, and the max-ID query for auto-ID
 * generation against a real PostgreSQL 16 instance via Testcontainers.
 *
 * <p>This is the <strong>most complex repository test</strong> in the CardDemo
 * application, exercising all custom query methods defined on the repository
 * interface that support the online transaction browse (COTRN00C.cbl), transaction
 * add with auto-ID (COTRN02C.cbl), and batch date-filtered reporting
 * (CBTRN03C.cbl / TRANREPT.jcl).</p>
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)
 *     KEYS(16 0)           — TRAN-ID PIC X(16) at offset 0
 *     RECORDSIZE(350 350)
 *     INDEXED)
 * DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)
 *     KEYS(26 304)         — TRAN-PROC-TS at offset 304
 *     NONUNIQUEKEY
 *     RELATE(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS))
 * </pre>
 *
 * <h3>COBOL Record Layout (CVTRA05Y.cpy — 350 bytes)</h3>
 * <pre>
 * 01  TRAN-RECORD.
 *     05  TRAN-ID             PIC X(16).   — PK (tran_id VARCHAR(16))
 *     05  TRAN-TYPE-CD        PIC X(02).   — type_cd CHAR(2)
 *     05  TRAN-CAT-CD         PIC 9(04).   — cat_cd SMALLINT
 *     05  TRAN-SOURCE         PIC X(10).   — source VARCHAR(10)
 *     05  TRAN-DESC           PIC X(100).  — description VARCHAR(100)
 *     05  TRAN-AMT            PIC S9(09)V99. — amount NUMERIC(11,2)
 *     05  TRAN-MERCHANT-ID    PIC 9(09).   — merchant_id VARCHAR(9)
 *     05  TRAN-MERCHANT-NAME  PIC X(50).   — merchant_name VARCHAR(50)
 *     05  TRAN-MERCHANT-CITY  PIC X(50).   — merchant_city VARCHAR(50)
 *     05  TRAN-MERCHANT-ZIP   PIC X(10).   — merchant_zip VARCHAR(10)
 *     05  TRAN-CARD-NUM       PIC X(16).   — card_num VARCHAR(16) (indexed)
 *     05  TRAN-ORIG-TS        PIC X(26).   — orig_ts TIMESTAMP
 *     05  TRAN-PROC-TS        PIC X(26).   — proc_ts TIMESTAMP
 *     05  FILLER              PIC X(20).   — Not mapped
 * </pre>
 *
 * <h3>CRITICAL: No V3 Seed Data for Transactions</h3>
 * <p>The transactions table has <strong>no seed data</strong> from V3 migration
 * (transactions are populated by the batch pipeline only). All test data is
 * created programmatically within each test method via helper methods.</p>
 *
 * <h3>Custom Repository Methods Tested</h3>
 * <ol>
 *   <li>{@code findByTranCardNum(String, Pageable)} — paginated card-based browse
 *       (COTRN00C.cbl 10 rows/page)</li>
 *   <li>{@code findByTranCardNum(String)} — non-paginated list</li>
 *   <li>{@code findByTranOrigTsBetween(LocalDateTime, LocalDateTime)} — date range
 *       filter (TRANREPT.jcl)</li>
 *   <li>{@code findMaxTransactionId()} — {@code @Query MAX(tranId)} for auto-ID
 *       generation (COTRN02C.cbl browse-to-end + increment)</li>
 * </ol>
 *
 * <p>COBOL source references: {@code app/jcl/TRANFILE.jcl}, {@code app/cpy/CVTRA05Y.cpy},
 * {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN02C.cbl} from commit {@code 27d6c6f}.</p>
 *
 * @see Transaction
 * @see TransactionRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("TransactionRepository Integration Tests — TRANSACT VSAM KSDS")
public class TransactionRepositoryIT {

    // -----------------------------------------------------------------------
    // Testcontainers PostgreSQL 16 — managed lifecycle via @Container
    // Replaces VSAM DEFINE CLUSTER for TRANSACT.VSAM.KSDS
    // -----------------------------------------------------------------------

    @Container
    static PostgreSQLContainer postgresContainer =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    /**
     * Injects Testcontainers PostgreSQL connection properties into the Spring
     * Environment, overriding the static {@code jdbc:tc:} URL from
     * {@code application-test.yml} with the dynamically allocated container URL.
     *
     * <p>This ensures Flyway migrations run against the real PostgreSQL container
     * and Hibernate validates entity mappings against the Flyway-created schema.</p>
     *
     * @param registry the dynamic property registry for runtime property injection
     */
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
    private TransactionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    // -----------------------------------------------------------------------
    // Helper: Create a test Transaction entity with all required fields
    // -----------------------------------------------------------------------

    /**
     * Creates a fully populated {@link Transaction} entity for testing purposes.
     * All fields are set to valid values matching the COBOL record layout
     * constraints from CVTRA05Y.cpy (350-byte TRAN-RECORD).
     *
     * @param tranId   transaction identifier (PIC X(16) — primary key)
     * @param cardNum  card number (PIC X(16) — indexed for browse queries)
     * @param amount   transaction amount (PIC S9(09)V99 — BigDecimal precision=11, scale=2)
     * @param origTs   origination timestamp (PIC X(26) → LocalDateTime)
     * @param procTs   processing timestamp (PIC X(26) → LocalDateTime)
     * @return a fully populated Transaction entity ready for persistence
     */
    private Transaction createTestTransaction(String tranId, String cardNum,
                                               BigDecimal amount, LocalDateTime origTs,
                                               LocalDateTime procTs) {
        Transaction txn = new Transaction();
        txn.setTranId(tranId);
        txn.setTranTypeCd("01");                        // Purchase type
        txn.setTranCatCd((short) 1);                    // Category code (SMALLINT)
        txn.setTranSource("POS TERM");                  // Point of sale terminal
        txn.setTranDesc("Test transaction");             // Description
        txn.setTranAmt(amount);                          // BigDecimal amount
        txn.setTranMerchantId("000000001");             // Merchant ID (PIC 9(09))
        txn.setTranMerchantName("Test Store");          // Merchant name (PIC X(50))
        txn.setTranMerchantCity("New York");            // Merchant city (PIC X(50))
        txn.setTranMerchantZip("10001");                // Merchant ZIP (PIC X(10))
        txn.setTranCardNum(cardNum);                    // Card number (indexed)
        txn.setTranOrigTs(origTs);                       // Origination timestamp
        txn.setTranProcTs(procTs);                       // Processing timestamp
        return txn;
    }

    /**
     * Persists a test transaction via the repository and flushes the persistence
     * context so the entity is written to PostgreSQL before query assertions.
     *
     * @param txn the Transaction entity to persist
     * @return the persisted Transaction entity
     */
    private Transaction persistAndFlush(Transaction txn) {
        Transaction saved = repository.save(txn);
        entityManager.flush();
        return saved;
    }

    // -----------------------------------------------------------------------
    // Test 1: Save and find by ID — TRANFILE KSDS KEYS(16 0)
    // Verifies CRUD round-trip for Transaction entity
    // -----------------------------------------------------------------------

    /**
     * Verifies that a {@link Transaction} entity can be saved and retrieved
     * by its primary key ({@code tranId} — 16-character string PK mapped
     * from TRAN-ID PIC X(16) at VSAM KEYS(16,0)).
     *
     * <p>All 13 data fields are verified for exact round-trip fidelity,
     * including BigDecimal amount comparison via {@code compareTo()} per
     * AAP §0.8.2 (zero floating-point substitution — NEVER equals()).</p>
     */
    @Test
    @DisplayName("save and findById round-trips all 13 fields correctly")
    void testSaveAndFindById() {
        // Arrange — create a fully populated Transaction entity
        Transaction txn = new Transaction();
        txn.setTranId("0000000000000001");
        txn.setTranTypeCd("01");
        txn.setTranCatCd((short) 1);
        txn.setTranSource("POS TERM");
        txn.setTranDesc("Test purchase at Store A");
        txn.setTranAmt(new BigDecimal("150.75"));
        txn.setTranMerchantId("000000001");
        txn.setTranMerchantName("Test Store");
        txn.setTranMerchantCity("New York");
        txn.setTranMerchantZip("10001");
        txn.setTranCardNum("4111111111111111");
        txn.setTranOrigTs(LocalDateTime.of(2024, 1, 15, 10, 30, 0));
        txn.setTranProcTs(LocalDateTime.of(2024, 1, 15, 10, 30, 1));

        // Act — save, flush to DB, clear L1 cache, re-read from PostgreSQL
        repository.save(txn);
        entityManager.flush();
        entityManager.clear();

        Optional<Transaction> result = repository.findById("0000000000000001");

        // Assert — verify all fields round-trip correctly
        assertThat(result)
                .as("Transaction '0000000000000001' should be present after save")
                .isPresent();

        Transaction found = result.get();

        assertThat(found.getTranId())
                .as("TRAN-ID PIC X(16)")
                .isEqualTo("0000000000000001");

        assertThat(found.getTranTypeCd())
                .as("TRAN-TYPE-CD PIC X(02) — Purchase")
                .isEqualTo("01");

        assertThat(found.getTranCatCd())
                .as("TRAN-CAT-CD PIC 9(04) — Category 1")
                .isEqualTo((short) 1);

        assertThat(found.getTranSource())
                .as("TRAN-SOURCE PIC X(10)")
                .isEqualTo("POS TERM");

        assertThat(found.getTranDesc())
                .as("TRAN-DESC PIC X(100)")
                .isEqualTo("Test purchase at Store A");

        // CRITICAL: BigDecimal comparison via compareTo() — NEVER equals() (AAP §0.8.2)
        assertThat(found.getTranAmt().compareTo(new BigDecimal("150.75")))
                .as("TRAN-AMT PIC S9(09)V99 — BigDecimal precision preserved via compareTo()")
                .isZero();

        assertThat(found.getTranMerchantId())
                .as("TRAN-MERCHANT-ID PIC 9(09)")
                .isEqualTo("000000001");

        assertThat(found.getTranMerchantName())
                .as("TRAN-MERCHANT-NAME PIC X(50)")
                .isEqualTo("Test Store");

        assertThat(found.getTranMerchantCity())
                .as("TRAN-MERCHANT-CITY PIC X(50)")
                .isEqualTo("New York");

        assertThat(found.getTranMerchantZip())
                .as("TRAN-MERCHANT-ZIP PIC X(10)")
                .isEqualTo("10001");

        assertThat(found.getTranCardNum())
                .as("TRAN-CARD-NUM PIC X(16) — indexed via AIX")
                .isEqualTo("4111111111111111");

        assertThat(found.getTranOrigTs())
                .as("TRAN-ORIG-TS PIC X(26) — origination timestamp")
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30, 0));

        assertThat(found.getTranProcTs())
                .as("TRAN-PROC-TS PIC X(26) — processing timestamp")
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30, 1));
    }

    // -----------------------------------------------------------------------
    // Test 2: findById for non-existent ID
    // Equivalent to COBOL READ TRANSACT with FILE STATUS '23' (INVALID KEY)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById()} returns an empty {@code Optional} when
     * the requested transaction ID does not exist — equivalent to COBOL
     * FILE STATUS '23' (INVALID KEY / record not found) on the TRANSACT VSAM KSDS.
     */
    @Test
    @DisplayName("findById returns empty Optional for non-existent transaction ID")
    void testFindById_NonExistent() {
        // Act — attempt to read a transaction ID that was never inserted
        Optional<Transaction> result = repository.findById("9999999999999999");

        // Assert — result should be empty (FILE STATUS '23' equivalent)
        assertThat(result)
                .as("Transaction '9999999999999999' does not exist — findById should return empty")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 3: findByTranCardNum paginated — COTRN00C.cbl 10 rows/page browse
    // Tests TRANSACT.VSAM.AIX alternate index on card number
    // -----------------------------------------------------------------------

    /**
     * Verifies paginated card-based transaction browse — equivalent to
     * COTRN00C.cbl STARTBR/READNEXT with 10 rows per page using the VSAM
     * alternate index on TRAN-CARD-NUM.
     *
     * <p>Creates 15 transactions for a single card number, then retrieves
     * the first page (10 records) and verifies page metadata (total elements,
     * total pages, page content size, and card number on all entries).</p>
     */
    @Test
    @DisplayName("findByTranCardNum paginated returns correct first page of 10")
    void testFindByTranCardNum_Paginated() {
        // Arrange — create 15 transactions for the same card number
        String cardNum = "4111111111111111";
        LocalDateTime baseTime = LocalDateTime.of(2024, 1, 15, 10, 0, 0);

        for (int i = 1; i <= 15; i++) {
            String id = String.format("%016d", i);
            Transaction txn = createTestTransaction(id, cardNum,
                    new BigDecimal("100.00"),
                    baseTime.plusMinutes(i),
                    baseTime.plusMinutes(i).plusSeconds(1));
            repository.save(txn);
        }
        entityManager.flush();
        entityManager.clear();

        // Act — fetch first page of 10 (COTRN00C.cbl 10-row browse page)
        Page<Transaction> page = repository.findByTranCardNum(cardNum, PageRequest.of(0, 10));

        // Assert — page metadata and content verification
        assertThat(page.hasContent())
                .as("First page should have content")
                .isTrue();

        assertThat(page.getContent()).hasSize(10);

        assertThat(page.getTotalElements())
                .as("Total elements across all pages")
                .isEqualTo(15L);

        assertThat(page.getTotalPages())
                .as("15 records / 10 per page = 2 pages")
                .isEqualTo(2);

        // Verify every transaction on the page belongs to the queried card
        for (Transaction txn : page.getContent()) {
            assertThat(txn.getTranCardNum())
                    .as("All transactions on page must belong to card %s", cardNum)
                    .isEqualTo(cardNum);
        }
    }

    // -----------------------------------------------------------------------
    // Test 4: findByTranCardNum non-paginated — returns full list
    // -----------------------------------------------------------------------

    /**
     * Verifies non-paginated card-based transaction list retrieval —
     * returns all transactions for a given card number without pagination
     * constraints.
     */
    @Test
    @DisplayName("findByTranCardNum non-paginated returns all 15 transactions")
    void testFindByTranCardNum_NonPaginated() {
        // Arrange — create 15 transactions for the same card number
        String cardNum = "4111111111111111";
        LocalDateTime baseTime = LocalDateTime.of(2024, 1, 15, 10, 0, 0);

        for (int i = 1; i <= 15; i++) {
            String id = String.format("%016d", i);
            Transaction txn = createTestTransaction(id, cardNum,
                    new BigDecimal("100.00"),
                    baseTime.plusMinutes(i),
                    baseTime.plusMinutes(i).plusSeconds(1));
            repository.save(txn);
        }
        entityManager.flush();
        entityManager.clear();

        // Act — fetch all transactions for the card (no pagination)
        List<Transaction> transactions = repository.findByTranCardNum(cardNum);

        // Assert — all 15 transactions returned
        assertThat(transactions)
                .as("Non-paginated query should return all 15 transactions")
                .hasSize(15);

        // Verify all transactions belong to the correct card
        for (Transaction txn : transactions) {
            assertThat(txn.getTranCardNum())
                    .as("All transactions must belong to card %s", cardNum)
                    .isEqualTo(cardNum);
        }
    }

    // -----------------------------------------------------------------------
    // Test 5: findByTranCardNum with no matching records
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findByTranCardNum()} returns an empty page when
     * no transactions exist for the given card number — equivalent to COBOL
     * STARTBR with FILE STATUS '23' (key not found) on the TRANSACT VSAM AIX.
     */
    @Test
    @DisplayName("findByTranCardNum returns empty page when no transactions match")
    void testFindByTranCardNum_NoResults() {
        // Act — query for a card number with no transactions
        Page<Transaction> page = repository.findByTranCardNum(
                "9999999999999999", PageRequest.of(0, 10));

        // Assert — empty page
        assertThat(page.hasContent())
                .as("No transactions for card '9999999999999999' — page should be empty")
                .isFalse();

        assertThat(page.getTotalElements())
                .as("Total elements should be zero")
                .isZero();
    }

    // -----------------------------------------------------------------------
    // Test 6: findByTranOrigTsBetween — TRANREPT.jcl date-filtered reporting
    // -----------------------------------------------------------------------

    /**
     * Verifies date-range transaction filtering — equivalent to the TRANREPT.jcl
     * date-filtered reporting job (CBTRN03C.cbl) that selects transactions within
     * a specified date window.
     *
     * <p>Creates transactions across three days (Jan 15, 16, 17) and queries
     * for Jan 15-16 to verify boundary-inclusive filtering.</p>
     */
    @Test
    @DisplayName("findByTranOrigTsBetween returns transactions within date range only")
    void testFindByTranOrigTsBetween() {
        // Arrange — create transactions across 3 different dates
        String cardNum = "4111111111111111";

        // 5 transactions on Jan 15, 2024
        for (int i = 1; i <= 5; i++) {
            String id = String.format("%016d", i);
            LocalDateTime origTs = LocalDateTime.of(2024, 1, 15, 10, i, 0);
            Transaction txn = createTestTransaction(id, cardNum,
                    new BigDecimal("50.00"), origTs, origTs.plusSeconds(1));
            repository.save(txn);
        }

        // 3 transactions on Jan 16, 2024
        for (int i = 6; i <= 8; i++) {
            String id = String.format("%016d", i);
            LocalDateTime origTs = LocalDateTime.of(2024, 1, 16, 14, i, 0);
            Transaction txn = createTestTransaction(id, cardNum,
                    new BigDecimal("75.00"), origTs, origTs.plusSeconds(1));
            repository.save(txn);
        }

        // 2 transactions on Jan 17, 2024 (should NOT be in results)
        for (int i = 9; i <= 10; i++) {
            String id = String.format("%016d", i);
            LocalDateTime origTs = LocalDateTime.of(2024, 1, 17, 9, i, 0);
            Transaction txn = createTestTransaction(id, cardNum,
                    new BigDecimal("25.00"), origTs, origTs.plusSeconds(1));
            repository.save(txn);
        }
        entityManager.flush();
        entityManager.clear();

        // Act — query for Jan 15-16 inclusive
        LocalDateTime startDate = LocalDateTime.of(2024, 1, 15, 0, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(2024, 1, 16, 23, 59, 59);
        List<Transaction> results = repository.findByTranOrigTsBetween(startDate, endDate);

        // Assert — should return 8 transactions (5 from Jan 15 + 3 from Jan 16)
        assertThat(results)
                .as("Date range Jan 15-16 should return 5 + 3 = 8 transactions")
                .hasSize(8);

        // Verify all returned transactions have origTs within the date range
        for (Transaction txn : results) {
            assertThat(txn.getTranOrigTs())
                    .as("Transaction %s origTs must be within [%s, %s]",
                            txn.getTranId(), startDate, endDate)
                    .isAfterOrEqualTo(startDate)
                    .isBeforeOrEqualTo(endDate);
        }
    }

    // -----------------------------------------------------------------------
    // Test 7: findMaxTransactionId — COTRN02C.cbl auto-ID generation
    // Browse-to-end + increment pattern for new transaction ID assignment
    // -----------------------------------------------------------------------

    /**
     * Verifies the {@code findMaxTransactionId()} custom query that returns the
     * highest existing transaction ID — equivalent to the COTRN02C.cbl
     * STARTBR/READPREV browse-to-end pattern used for auto-ID generation.
     *
     * <p>When inserting transactions with IDs "...0001", "...0005", "...0003",
     * the MAX function should return "...0005" (lexicographic maximum of
     * zero-padded 16-character strings matches numeric maximum).</p>
     */
    @Test
    @DisplayName("findMaxTransactionId returns the highest transaction ID")
    void testFindMaxTransactionId() {
        // Arrange — insert transactions with non-sequential IDs
        LocalDateTime ts = LocalDateTime.of(2024, 1, 15, 10, 0, 0);

        persistAndFlush(createTestTransaction("0000000000000001", "4111111111111111",
                new BigDecimal("100.00"), ts, ts.plusSeconds(1)));
        persistAndFlush(createTestTransaction("0000000000000005", "4111111111111111",
                new BigDecimal("200.00"), ts.plusMinutes(1), ts.plusMinutes(1).plusSeconds(1)));
        persistAndFlush(createTestTransaction("0000000000000003", "4111111111111111",
                new BigDecimal("150.00"), ts.plusMinutes(2), ts.plusMinutes(2).plusSeconds(1)));
        entityManager.clear();

        // Act — find the maximum transaction ID
        Optional<String> maxId = repository.findMaxTransactionId();

        // Assert — should return the highest ID for auto-increment
        assertThat(maxId)
                .as("findMaxTransactionId should return the highest existing ID")
                .isPresent();

        assertThat(maxId.get())
                .as("Max ID should be '0000000000000005' (highest of 1, 5, 3)")
                .isEqualTo("0000000000000005");
    }

    // -----------------------------------------------------------------------
    // Test 8: findMaxTransactionId on empty table
    // Edge case: no transactions exist yet — return empty Optional
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findMaxTransactionId()} returns an empty
     * {@code Optional} when the transactions table is empty — this is the
     * initial state before any batch pipeline has populated the table.
     *
     * <p>The auto-ID generation logic in the Java equivalent of COTRN02C.cbl
     * must handle this case by starting from "0000000000000001" when no
     * existing transactions are found.</p>
     */
    @Test
    @DisplayName("findMaxTransactionId returns empty Optional on empty table")
    void testFindMaxTransactionId_EmptyTable() {
        // Act — query max ID on empty transactions table (no seed data, no inserts)
        Optional<String> maxId = repository.findMaxTransactionId();

        // Assert — should be empty (no transactions in table)
        assertThat(maxId)
                .as("Empty transactions table should return empty Optional from MAX query")
                .isNotPresent();
    }

    // -----------------------------------------------------------------------
    // Test 9: BigDecimal amount precision — AAP §0.8.2 CRITICAL
    // PIC S9(09)V99 → NUMERIC(11,2) → BigDecimal with exact precision
    // -----------------------------------------------------------------------

    /**
     * Verifies that the maximum BigDecimal amount ({@code 99999999.99}) from the
     * COBOL field {@code TRAN-AMT PIC S9(09)V99} round-trips through PostgreSQL
     * {@code NUMERIC(11,2)} without precision loss.
     *
     * <p><strong>CRITICAL (AAP §0.8.2):</strong> All assertions use
     * {@code compareTo()} — NEVER {@code equals()} — because BigDecimal
     * {@code equals()} is scale-sensitive and may fail when the DB returns
     * a different scale representation of the same numeric value.</p>
     */
    @Test
    @DisplayName("BigDecimal amount 99999999.99 round-trips with exact precision")
    void testBigDecimalAmountPrecision() {
        // Arrange — create transaction with maximum TRAN-AMT value
        BigDecimal maxAmount = new BigDecimal("99999999.99");
        LocalDateTime ts = LocalDateTime.of(2024, 1, 15, 10, 0, 0);
        Transaction txn = createTestTransaction("0000000000000001", "4111111111111111",
                maxAmount, ts, ts.plusSeconds(1));

        // Act — save, flush to PostgreSQL NUMERIC(11,2), clear cache, re-read
        repository.save(txn);
        entityManager.flush();
        entityManager.clear();

        Optional<Transaction> result = repository.findById("0000000000000001");

        // Assert — BigDecimal comparison via compareTo(), NEVER equals()
        assertThat(result)
                .as("Transaction should be present after save")
                .isPresent();

        assertThat(result.get().getTranAmt().compareTo(maxAmount))
                .as("TRAN-AMT PIC S9(09)V99 max value 99999999.99 must round-trip exactly")
                .isZero();
    }

    // -----------------------------------------------------------------------
    // Test 10: Pagination second page — COTRN00C.cbl page navigation
    // Tests page 3 of 25 records (10+10+5) → isLast() == true
    // -----------------------------------------------------------------------

    /**
     * Verifies pagination for the third (last) page when browsing 25
     * transactions at 10 per page — maps to COTRN00C.cbl forward browse
     * with PF8 (page down) navigation.
     *
     * <p>25 records / 10 per page = 3 pages: page 0 (10), page 1 (10),
     * page 2 (5 — last page). Verifies the third page has 5 records
     * and {@code isLast()} returns true.</p>
     */
    @Test
    @DisplayName("Pagination third page of 25 records returns 5 elements and isLast=true")
    void testPaginationSecondPage() {
        // Arrange — create 25 transactions for the same card number
        String cardNum = "4111111111111111";
        LocalDateTime baseTime = LocalDateTime.of(2024, 1, 15, 10, 0, 0);

        for (int i = 1; i <= 25; i++) {
            String id = String.format("%016d", i);
            Transaction txn = createTestTransaction(id, cardNum,
                    new BigDecimal("100.00"),
                    baseTime.plusMinutes(i),
                    baseTime.plusMinutes(i).plusSeconds(1));
            repository.save(txn);
        }
        entityManager.flush();
        entityManager.clear();

        // Act — fetch page index 2 (third page, 0-based) with size 10
        Page<Transaction> page = repository.findByTranCardNum(cardNum, PageRequest.of(2, 10));

        // Assert — third page should have 5 elements and be the last page
        assertThat(page.getContent())
                .as("Third page of 25 records (10 per page) should have 5 elements")
                .hasSize(5);

        assertThat(page.isLast())
                .as("Page index 2 should be the last page")
                .isTrue();

        assertThat(page.getTotalElements())
                .as("Total elements across all pages")
                .isEqualTo(25L);

        assertThat(page.getTotalPages())
                .as("25 records / 10 per page = 3 pages")
                .isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // Test 11: Timestamp precision — TRAN-ORIG-TS PIC X(26) microseconds
    // Verifies PostgreSQL TIMESTAMP preserves at least microsecond precision
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code LocalDateTime} timestamps with microsecond precision
     * round-trip correctly through PostgreSQL {@code TIMESTAMP} columns —
     * maps to TRAN-ORIG-TS PIC X(26) and TRAN-PROC-TS PIC X(26) fields.
     *
     * <p>PostgreSQL {@code TIMESTAMP} has microsecond resolution (6 decimal
     * fractional seconds). The test uses a nanosecond value of {@code 123456000}
     * (which corresponds to 123.456 microseconds) and verifies it is preserved
     * through the save/flush/clear/find round-trip.</p>
     */
    @Test
    @DisplayName("Timestamp with microsecond precision round-trips through PostgreSQL")
    void testTimestampPrecision() {
        // Arrange — create transaction with microsecond-precise timestamps
        // 123456000 nanoseconds = 123456 microseconds = 0.123456 seconds
        LocalDateTime preciseOrigTs = LocalDateTime.of(2024, 1, 15, 10, 30, 15, 123456000);
        LocalDateTime preciseProcTs = LocalDateTime.of(2024, 1, 15, 10, 30, 16, 654321000);

        Transaction txn = createTestTransaction("0000000000000001", "4111111111111111",
                new BigDecimal("100.00"), preciseOrigTs, preciseProcTs);

        // Act — save, flush to PostgreSQL TIMESTAMP, clear cache, re-read
        repository.save(txn);
        entityManager.flush();
        entityManager.clear();

        Optional<Transaction> result = repository.findById("0000000000000001");

        // Assert — verify microsecond precision is preserved
        assertThat(result)
                .as("Transaction should be present after save")
                .isPresent();

        Transaction found = result.get();

        assertThat(found.getTranOrigTs())
                .as("TRAN-ORIG-TS microsecond precision should be preserved")
                .isEqualTo(preciseOrigTs);

        assertThat(found.getTranProcTs())
                .as("TRAN-PROC-TS microsecond precision should be preserved")
                .isEqualTo(preciseProcTs);
    }
}
