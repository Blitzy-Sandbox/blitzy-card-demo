package com.cardemo.integration.repository;

import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.TransactionCategoryBalanceRepository;

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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TransactionCategoryBalanceRepository} verifying
 * composite key operations, BigDecimal balance precision, and the custom
 * {@code findByIdAcctId()} query against a real PostgreSQL 16 instance via
 * Testcontainers.
 *
 * <p>This entity tracks per-category running balances for accounts and is a
 * critical component of the batch processing pipeline:</p>
 * <ul>
 *   <li><strong>Interest Calculation ({@code CBACT04C.cbl})</strong> — reads
 *       all category balances for an account to compute interest using the
 *       formula {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200}.</li>
 *   <li><strong>Daily Transaction Posting ({@code CBTRN02C.cbl})</strong> —
 *       updates category balance after posting each validated transaction.</li>
 * </ul>
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS)
 *     KEYS(17 0)
 *     RECORDSIZE(50 50)
 *     INDEXED)
 * </pre>
 *
 * <h3>COBOL Record Layout (CVTRA01Y.cpy)</h3>
 * <pre>
 * 01  TRAN-CAT-BAL-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10 TRANCAT-ACCT-ID     PIC 9(11).    — acctId  VARCHAR(11)
 *        10 TRANCAT-TYPE-CD     PIC X(02).    — typeCode CHAR(2)
 *        10 TRANCAT-CD          PIC 9(04).    — catCode  SMALLINT
 *     05  TRAN-CAT-BAL          PIC S9(09)V99. — balance NUMERIC(11,2)
 *     05  FILLER                PIC X(22).     — Not mapped
 * </pre>
 *
 * <h3>Composite Key Structure (17 bytes from VSAM KEYS(17,0))</h3>
 * <p>The composite key consists of three parts:</p>
 * <ul>
 *   <li>{@code acctId} — 11-character account identifier (PIC 9(11))</li>
 *   <li>{@code typeCode} — 2-character transaction type code (PIC X(02))</li>
 *   <li>{@code catCode} — transaction category code (PIC 9(04) → SMALLINT)</li>
 * </ul>
 *
 * <h3>Seed Data (tcatbal.txt — 50 records)</h3>
 * <p>All 50 records have balance 0.00 with composite keys from account IDs
 * 00000000001 through 00000000050, type code '01', and category code 1.
 * Seeded via Flyway V3__seed_data.sql.</p>
 *
 * <h3>BigDecimal Precision Rules (AAP §0.8.2)</h3>
 * <p>All balance comparisons use {@code compareTo()} — never {@code equals()},
 * which is scale-sensitive in {@link BigDecimal}. COBOL PIC S9(09)V99 maps to
 * {@code NUMERIC(11,2)} with 9 integer digits and 2 decimal places.</p>
 *
 * <p>COBOL source reference: {@code app/jcl/TCATBALF.jcl}, {@code app/cpy/CVTRA01Y.cpy},
 * and {@code app/data/ASCII/tcatbal.txt} from commit {@code 27d6c6f}.</p>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 * @see TransactionCategoryBalanceRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("TransactionCategoryBalanceRepository Integration Tests — TCATBALF VSAM KSDS")
public class TransactionCategoryBalanceRepositoryIT {

    // -----------------------------------------------------------------------
    // Testcontainers PostgreSQL 16 — managed lifecycle via @Container
    // Replaces VSAM DEFINE CLUSTER for TCATBALF.VSAM.KSDS
    // KEYS(17 0), RECORDSIZE(50 50), SHAREOPTIONS(2 3)
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
     * <p>This ensures Flyway migrations (V1 schema creation, V2 indexes, V3 seed
     * data) run against the real PostgreSQL container and Hibernate validates
     * entity mappings against the Flyway-created schema.</p>
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
    private TransactionCategoryBalanceRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    // -----------------------------------------------------------------------
    // Test 1: findById with composite key (acctId + typeCode + catCode)
    // Verifies keyed read — equivalent to COBOL READ TCATBALF KEY(17 bytes)
    // KSDS KEYS(17 0): acctId(11) + typeCode(2) + catCode(4) = 17 bytes
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById()} with a valid composite key returns the
     * expected transaction category balance record seeded from
     * {@code tcatbal.txt} via Flyway V3 migration.
     *
     * <p>Maps COBOL keyed read: {@code READ TCATBALF-FILE INTO TRAN-CAT-BAL-RECORD
     * KEY IS TRAN-CAT-KEY}. The composite key consists of
     * TRANCAT-ACCT-ID(11) + TRANCAT-TYPE-CD(2) + TRANCAT-CD(4).</p>
     *
     * <p>BigDecimal balance assertion uses {@code compareTo()} per AAP §0.8.2
     * — never {@code equals()} which is scale-sensitive.</p>
     */
    @Test
    @DisplayName("findById with composite key returns seeded balance record")
    void testFindById_WithCompositeKey() {
        // Arrange — construct the 17-byte composite key for account 00000000001
        // typeCode "01" (Purchase), catCode 1 (Regular Sales Draft)
        TransactionCategoryBalanceId compositeKey =
                new TransactionCategoryBalanceId("00000000001", "01", (short) 1);

        // Act — equivalent to COBOL READ TCATBALF KEY(00000000001|01|0001)
        Optional<TransactionCategoryBalance> result = repository.findById(compositeKey);

        // Assert — verify presence and field values
        assertThat(result)
                .as("Category balance for acctId='00000000001', typeCode='01', catCode=1 "
                        + "should be present in seed data (tcatbal.txt)")
                .isPresent();

        TransactionCategoryBalance balance = result.get();

        // Verify composite key fields round-trip correctly
        assertThat(balance.getId()).isNotNull();
        assertThat(balance.getId().getAcctId())
                .as("Account ID should be '00000000001' (11-char PIC 9(11))")
                .isEqualTo("00000000001");
        assertThat(balance.getId().getTypeCode())
                .as("Type code should be '01' (2-char PIC X(02))")
                .startsWith("01");
        assertThat(balance.getId().getCatCode())
                .as("Category code should be 1 (PIC 9(04) → SMALLINT)")
                .isEqualTo((short) 1);

        // Verify balance is a valid BigDecimal — use compareTo() per AAP §0.8.2
        assertThat(balance.getTranCatBal())
                .as("Balance should be non-null BigDecimal")
                .isNotNull();
        assertThat(balance.getTranCatBal().compareTo(BigDecimal.ZERO))
                .as("Seed data balance should be 0.00 (decoded from COBOL 0000000000{)")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Test 2: findById with non-existent composite key
    // Verifies INVALID KEY / FILE STATUS '23' equivalent (RecordNotFound)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById()} with a non-existent composite key returns
     * an empty {@code Optional}, equivalent to COBOL FILE STATUS '23'
     * (record not found) on TCATBALF READ.
     *
     * <p>In the COBOL source, this scenario triggers a
     * {@code FILE STATUS = '23'} condition, which maps to
     * {@code RecordNotFoundException} in the Java exception hierarchy.</p>
     */
    @Test
    @DisplayName("findById with non-existent composite key returns empty")
    void testFindById_NonExistent() {
        // Arrange — key that does not exist in seed data
        TransactionCategoryBalanceId nonExistentKey =
                new TransactionCategoryBalanceId("99999999999", "99", (short) 9999);

        // Act — equivalent to COBOL READ TCATBALF with invalid key → STATUS '23'
        Optional<TransactionCategoryBalance> result = repository.findById(nonExistentKey);

        // Assert — should be empty (no RecordNotFoundException in JPA, just empty Optional)
        assertThat(result)
                .as("Non-existent composite key (99999999999/99/9999) should return empty")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 3: findByIdAcctId custom query
    // Verifies account-based category balance lookup used by CBACT04C.cbl
    // interest calculation (iterates all category balances for an account)
    // -----------------------------------------------------------------------

    /**
     * Verifies that the custom {@code findByIdAcctId()} method returns all
     * category balance records for a given account ID.
     *
     * <p>This query is critical for the interest calculation batch program
     * ({@code CBACT04C.cbl}), which reads all TCATBALF records matching a
     * given TRANCAT-ACCT-ID. In COBOL, this was accomplished by sequential
     * reads with a partial key match; in JPA, the derived query filters on
     * the {@code acct_id} column of the composite key.</p>
     *
     * <p>Seed data from tcatbal.txt has exactly one record per account
     * (typeCode='01', catCode=1), so account 00000000001 should have exactly
     * 1 category balance record.</p>
     */
    @Test
    @DisplayName("findByIdAcctId returns all category balances for an account")
    void testFindByIdAcctId() {
        // Act — retrieve all category balances for account 00000000001
        List<TransactionCategoryBalance> balances =
                repository.findByIdAcctId("00000000001");

        // Assert — seed data has exactly 1 record for account 00000000001
        // (typeCode='01', catCode=1, balance=0.00)
        assertThat(balances)
                .as("Account 00000000001 should have at least one category balance")
                .isNotEmpty();

        // Verify all returned records belong to the requested account
        assertThat(balances)
                .allSatisfy(balance -> {
                    assertThat(balance.getId().getAcctId())
                            .as("Every returned balance should belong to account 00000000001")
                            .isEqualTo("00000000001");
                });

        // Verify the expected count matches tcatbal.txt seed data
        // tcatbal.txt has exactly 1 entry per account (type '01', cat 1)
        assertThat(balances)
                .as("Seed data contains exactly 1 category balance for account 00000000001")
                .hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Test 4: findByIdAcctId with no matching balances
    // Verifies empty result for non-existent account
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findByIdAcctId()} returns an empty list when no
     * category balance records exist for the specified account.
     *
     * <p>In COBOL, this corresponds to a partial key read on TCATBALF that
     * returns no records (browse/next returns EOF immediately).</p>
     */
    @Test
    @DisplayName("findByIdAcctId with non-existent account returns empty list")
    void testFindByIdAcctId_NoBalances() {
        // Act — query for an account that does not exist in seed data
        List<TransactionCategoryBalance> balances =
                repository.findByIdAcctId("99999999999");

        // Assert — should be empty, not null
        assertThat(balances)
                .as("Non-existent account 99999999999 should return empty list")
                .isNotNull()
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 5: save and retrieve round-trip
    // Verifies INSERT + SELECT preserves composite key and BigDecimal balance
    // Equivalent to COBOL WRITE TCATBALF FROM TRAN-CAT-BAL-RECORD
    // -----------------------------------------------------------------------

    /**
     * Verifies that a new {@code TransactionCategoryBalance} entity can be saved
     * and retrieved with composite key and balance preserved through a full
     * database round-trip (flush + clear L1 cache + re-read from PostgreSQL).
     *
     * <p>Maps COBOL WRITE: {@code WRITE TCATBALF-REC FROM TRAN-CAT-BAL-RECORD}.
     * The composite key is TRANCAT-ACCT-ID(11) + TRANCAT-TYPE-CD(2) +
     * TRANCAT-CD(4). Balance uses BigDecimal with scale=2 (PIC S9(09)V99).</p>
     *
     * <p>BigDecimal comparison uses {@code compareTo()} per AAP §0.8.2.</p>
     */
    @Test
    @DisplayName("save and retrieve preserves composite key and BigDecimal balance")
    void testSaveAndRetrieve() {
        // Arrange — create a new category balance record
        TransactionCategoryBalanceId newKey =
                new TransactionCategoryBalanceId("00000000099", "01", (short) 1);
        BigDecimal expectedBalance = new BigDecimal("1500.50");
        TransactionCategoryBalance newBalance =
                new TransactionCategoryBalance(newKey, expectedBalance);

        // Act — save, flush to DB, clear L1 cache, then re-read from PostgreSQL
        repository.save(newBalance);
        entityManager.flush();
        entityManager.clear();

        Optional<TransactionCategoryBalance> retrieved = repository.findById(newKey);

        // Assert — round-trip preserves composite key and balance
        assertThat(retrieved)
                .as("Saved category balance should be retrievable by composite key")
                .isPresent();

        TransactionCategoryBalance entity = retrieved.get();

        // Verify composite key fields
        assertThat(entity.getId().getAcctId())
                .as("Account ID should be preserved as '00000000099'")
                .isEqualTo("00000000099");
        assertThat(entity.getId().getTypeCode())
                .as("Type code should be preserved as '01'")
                .startsWith("01");
        assertThat(entity.getId().getCatCode())
                .as("Category code should be preserved as 1 (SMALLINT)")
                .isEqualTo((short) 1);

        // Verify balance with compareTo() — never equals() (AAP §0.8.2)
        assertThat(entity.getTranCatBal().compareTo(expectedBalance))
                .as("Balance should be exactly 1500.50 (PIC S9(09)V99 → NUMERIC(11,2))")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Test 6: BigDecimal balance precision at maximum value
    // CRITICAL — AAP §0.8.2: PIC S9(09)V99 → NUMERIC(11,2)
    // Maximum representable value: 999999999.99 (9 integer + 2 decimal digits)
    // -----------------------------------------------------------------------

    /**
     * Verifies that the maximum representable balance value
     * ({@code 999999999.99}) for COBOL {@code PIC S9(09)V99} survives a full
     * PostgreSQL round-trip without precision loss.
     *
     * <p><strong>CRITICAL — AAP §0.8.2 Decimal Precision:</strong>
     * COBOL PIC S9(09)V99 defines a signed packed decimal field with 9 integer
     * digits and 2 decimal places. In PostgreSQL, this maps to
     * {@code NUMERIC(11,2)} (precision=11, scale=2). The maximum positive value
     * is 999,999,999.99.</p>
     *
     * <p>This test ensures that {@link BigDecimal} storage and retrieval
     * preserve the exact value without rounding, truncation, or
     * floating-point approximation.</p>
     */
    @Test
    @DisplayName("BigDecimal maximum balance precision preserved (PIC S9(09)V99)")
    void testBigDecimalBalancePrecision() {
        // Arrange — maximum value for PIC S9(09)V99: 999999999.99
        TransactionCategoryBalanceId precisionKey =
                new TransactionCategoryBalanceId("00000000098", "01", (short) 1);
        BigDecimal maxBalance = new BigDecimal("999999999.99");
        TransactionCategoryBalance entity =
                new TransactionCategoryBalance(precisionKey, maxBalance);

        // Act — save, flush, clear cache, re-read
        repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        Optional<TransactionCategoryBalance> retrieved = repository.findById(precisionKey);

        // Assert — maximum precision value must survive round-trip exactly
        assertThat(retrieved)
                .as("Max-precision balance record should be retrievable")
                .isPresent();

        BigDecimal retrievedBalance = retrieved.get().getTranCatBal();

        // CRITICAL: use compareTo(), not equals() — equals() is scale-sensitive
        // BigDecimal("999999999.99").equals(BigDecimal("999999999.990")) → false
        // BigDecimal("999999999.99").compareTo(BigDecimal("999999999.990")) → 0
        assertThat(retrievedBalance.compareTo(maxBalance))
                .as("Max balance 999999999.99 must be preserved exactly "
                        + "(NUMERIC(11,2), no floating-point approximation)")
                .isEqualTo(0);

        // Verify scale is 2 (matching PIC S9(09)V99)
        assertThat(retrievedBalance.scale())
                .as("Balance scale should be 2 (matching COBOL V99 = 2 decimal places)")
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Test 7: @EmbeddedId composite key equals/hashCode correctness
    // Verifies JPA composite key identity contract
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link TransactionCategoryBalanceId} correctly implements
     * {@code equals()} and {@code hashCode()} for identical composite key values.
     *
     * <p>JPA requires that {@code @EmbeddedId} classes implement these methods
     * correctly for entity identity, L1 cache lookups, and {@code findById()}
     * operations. Two composite keys with identical field values must be
     * considered equal.</p>
     */
    @Test
    @DisplayName("@EmbeddedId equals/hashCode correctness for composite key")
    void testCompositeKeyEquality() {
        // Arrange — create two keys with identical field values
        TransactionCategoryBalanceId key1 =
                new TransactionCategoryBalanceId("00000000001", "01", (short) 1);
        TransactionCategoryBalanceId key2 =
                new TransactionCategoryBalanceId("00000000001", "01", (short) 1);

        // Assert equals() — same values must be equal
        assertThat(key1.equals(key2))
                .as("Two composite keys with identical (acctId, typeCode, catCode) should be equal")
                .isTrue();

        // Assert hashCode() — equal objects must have same hash code
        assertThat(key1.hashCode())
                .as("Equal composite keys must produce identical hashCode values")
                .isEqualTo(key2.hashCode());

        // Assert symmetry — equals must be symmetric
        assertThat(key2.equals(key1))
                .as("equals() must be symmetric: key2.equals(key1) should also be true")
                .isTrue();

        // Assert inequality with different values
        TransactionCategoryBalanceId differentKey =
                new TransactionCategoryBalanceId("00000000002", "01", (short) 1);
        assertThat(key1.equals(differentKey))
                .as("Keys with different acctId should not be equal")
                .isFalse();

        // Assert inequality with different catCode
        TransactionCategoryBalanceId differentCatKey =
                new TransactionCategoryBalanceId("00000000001", "01", (short) 2);
        assertThat(key1.equals(differentCatKey))
                .as("Keys with different catCode should not be equal")
                .isFalse();

        // Assert inequality with different typeCode
        TransactionCategoryBalanceId differentTypeKey =
                new TransactionCategoryBalanceId("00000000001", "02", (short) 1);
        assertThat(key1.equals(differentTypeKey))
                .as("Keys with different typeCode should not be equal")
                .isFalse();

        // Assert getter values are correct
        assertThat(key1.getAcctId()).isEqualTo("00000000001");
        assertThat(key1.getTypeCode()).isEqualTo("01");
        assertThat(key1.getCatCode()).isEqualTo((short) 1);
    }

    // -----------------------------------------------------------------------
    // Test 8: update existing balance
    // Batch pipeline updates category balances after transaction posting
    // Equivalent to COBOL REWRITE TCATBALF FROM TRAN-CAT-BAL-RECORD
    // -----------------------------------------------------------------------

    /**
     * Verifies that an existing category balance record can be updated (REWRITE)
     * with the new balance value preserved through a full database round-trip.
     *
     * <p>Maps COBOL REWRITE: {@code REWRITE TCATBALF-REC FROM TRAN-CAT-BAL-RECORD}.
     * In the batch pipeline ({@code CBTRN02C.cbl}), after posting a validated
     * daily transaction, the corresponding category balance is updated:
     * {@code TRAN-CAT-BAL = TRAN-CAT-BAL + TRAN-AMT}.</p>
     *
     * <p>BigDecimal comparison uses {@code compareTo()} per AAP §0.8.2.</p>
     */
    @Test
    @DisplayName("update existing balance preserves new value (REWRITE equivalent)")
    void testUpdateBalance() {
        // Arrange — read existing seed record for account 00000000002
        TransactionCategoryBalanceId existingKey =
                new TransactionCategoryBalanceId("00000000002", "01", (short) 1);
        Optional<TransactionCategoryBalance> existing = repository.findById(existingKey);

        assertThat(existing)
                .as("Seed record for account 00000000002 should exist")
                .isPresent();

        // Verify initial balance is 0.00 (from tcatbal.txt seed data)
        TransactionCategoryBalance entity = existing.get();
        assertThat(entity.getTranCatBal().compareTo(BigDecimal.ZERO))
                .as("Initial seed balance should be 0.00")
                .isEqualTo(0);

        // Act — update balance (simulating batch TRAN-CAT-BAL = TRAN-CAT-BAL + TRAN-AMT)
        BigDecimal updatedAmount = new BigDecimal("2575.33");
        entity.setTranCatBal(updatedAmount);
        repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        // Re-read from PostgreSQL after L1 cache clear
        Optional<TransactionCategoryBalance> retrieved = repository.findById(existingKey);

        // Assert — updated balance must be preserved exactly
        assertThat(retrieved)
                .as("Updated balance record should be retrievable")
                .isPresent();

        BigDecimal retrievedBalance = retrieved.get().getTranCatBal();

        // Use compareTo() for BigDecimal — never equals() (AAP §0.8.2)
        assertThat(retrievedBalance.compareTo(updatedAmount))
                .as("Updated balance 2575.33 must be preserved exactly after REWRITE")
                .isEqualTo(0);
    }
}
