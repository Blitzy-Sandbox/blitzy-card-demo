package com.cardemo.integration.repository;

import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.TransactionCategoryRepository;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TransactionCategoryRepository} verifying composite key
 * operations and read-only reference data access against a real PostgreSQL 16 instance
 * via Testcontainers.
 *
 * <p>This tests the simplest composite-key entity in the CardDemo application —
 * a 2-field key ({@code typeCode} + {@code catCode}) from the TRANCATG VSAM KSDS dataset.
 * The entity maps the COBOL {@code TRAN-CAT-RECORD} (60 bytes) defined in
 * {@code app/cpy/CVTRA04Y.cpy}.</p>
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS)
 *     KEYS(6 0)
 *     RECORDSIZE(60 60)
 *     INDEXED)
 * </pre>
 *
 * <h3>COBOL Record Layout (CVTRA04Y.cpy)</h3>
 * <pre>
 * 01  TRAN-CAT-RECORD.
 *     05  TRAN-CAT-KEY.
 *         10  TRAN-TYPE-CD       PIC X(02).   — Composite PK part 1 (type_cd CHAR(2))
 *         10  TRAN-CAT-CD        PIC 9(04).   — Composite PK part 2 (cat_cd SMALLINT)
 *     05  TRAN-CAT-TYPE-DESC    PIC X(50).   — Description (cat_desc VARCHAR(50))
 *     05  FILLER                PIC X(04).   — Not mapped (padding)
 * </pre>
 *
 * <h3>Seed Data (trancatg.txt — 18 records across 7 type groups)</h3>
 * <ul>
 *   <li>Type 01 (Purchase): 5 categories</li>
 *   <li>Type 02 (Payment): 3 categories</li>
 *   <li>Type 03 (Credit): 3 categories</li>
 *   <li>Type 04 (Authorization): 3 categories</li>
 *   <li>Type 05 (Refund): 1 category</li>
 *   <li>Type 06 (Reversal): 2 categories</li>
 *   <li>Type 07 (Adjustment): 1 category</li>
 * </ul>
 *
 * <p>All tests run against a real PostgreSQL 16 instance via Testcontainers with Flyway
 * migrations provisioning the schema (V1__create_schema.sql) and seed data
 * (V3__seed_data.sql — 18 transaction category records parsed from
 * {@code app/data/ASCII/trancatg.txt}).</p>
 *
 * <p>COBOL source reference: {@code app/jcl/TRANCATG.jcl}, {@code app/cpy/CVTRA04Y.cpy},
 * and {@code app/data/ASCII/trancatg.txt} from commit {@code 27d6c6f}.</p>
 *
 * @see TransactionCategory
 * @see TransactionCategoryId
 * @see TransactionCategoryRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("TransactionCategoryRepository Integration Tests — TRANCATG VSAM KSDS (Composite Key)")
public class TransactionCategoryRepositoryIT {

    // -----------------------------------------------------------------------
    // Testcontainers PostgreSQL 16 — managed lifecycle via @Container
    // Replaces VSAM DEFINE CLUSTER for TRANCATG.VSAM.KSDS
    // KEYS(6 0): 2-byte type_cd + 4-byte cat_cd composite primary key
    // RECORDSIZE(60 60): fixed-length 60-byte records
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
     * and Hibernate validates entity mappings against the Flyway-created schema
     * (composite PK on {@code type_cd} + {@code cat_cd}).</p>
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
    private TransactionCategoryRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    // -----------------------------------------------------------------------
    // Test 1: findById with composite key — TRANCATG KSDS KEYS(6 0)
    // Verifies keyed read: COBOL READ TRANCATG KEY IS TRAN-CAT-KEY
    // where TRAN-CAT-KEY = TRAN-TYPE-CD(2) + TRAN-CAT-CD(4) = "010001"
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById(new TransactionCategoryId("01", 1))} returns
     * the "Regular Sales Draft" category seeded from {@code trancatg.txt} via
     * Flyway V3 migration.
     *
     * <p>Maps COBOL keyed read: {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD
     * KEY IS TRAN-CAT-KEY} where TRAN-TYPE-CD='01' and TRAN-CAT-CD=0001.</p>
     *
     * <p>Source: First record in trancatg.txt — bytes 1-2: "01", bytes 3-6: "0001",
     * bytes 7-56: "Regular Sales Draft" (right-padded with spaces to 50 chars).</p>
     */
    @Test
    @DisplayName("findById with composite key (01, 1) returns 'Regular Sales Draft'")
    void testFindById_WithCompositeKey() {
        // Arrange — build composite key matching TRAN-CAT-KEY = '010001'
        TransactionCategoryId compositeKey = new TransactionCategoryId("01", (short) 1);

        // Act — equivalent to COBOL READ TRANCATG KEY IS TRAN-CAT-KEY
        Optional<TransactionCategory> result = repository.findById(compositeKey);

        // Assert — verify presence and field values
        assertThat(result)
                .as("Transaction category (type='01', cat=1) should be present in seed data")
                .isPresent();

        TransactionCategory category = result.get();

        // Verify the composite key is correctly populated
        assertThat(category.getId())
                .as("Composite key should not be null")
                .isNotNull();

        assertThat(category.getId().getTypeCode())
                .as("Type code should be '01' (Purchase type)")
                .isEqualTo("01");

        assertThat(category.getId().getCatCode())
                .as("Category code should be 1 (Regular Sales Draft)")
                .isEqualTo((short) 1);

        // Verify the description — from trancatg.txt first record
        assertThat(category.getTranCatTypeDesc())
                .as("Description should contain 'Regular Sales Draft'")
                .contains("Regular Sales Draft");

        // Verify description is trimmed (no trailing spaces from COBOL PIC X(50))
        assertThat(category.getTranCatTypeDesc())
                .as("Description should be trimmed — no trailing whitespace")
                .isEqualTo(category.getTranCatTypeDesc().trim());
    }

    // -----------------------------------------------------------------------
    // Test 2: findById for non-existent composite key
    // Verifies COBOL FILE STATUS '23' (INVALID KEY) scenario
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById(new TransactionCategoryId("99", 9999))} returns
     * an empty Optional, equivalent to COBOL FILE STATUS '23' (record not found).
     *
     * <p>Type code "99" and category code 9999 do not exist in the trancatg.txt
     * seed data. In COBOL, this would set FILE STATUS to '23' (INVALID KEY) after
     * a READ attempt. In Java, the repository returns {@code Optional.empty()}.</p>
     */
    @Test
    @DisplayName("findById with non-existent composite key (99, 9999) returns empty")
    void testFindById_NonExistent() {
        // Arrange — composite key that does not exist in seed data
        TransactionCategoryId nonExistentKey = new TransactionCategoryId("99", (short) 9999);

        // Act — equivalent to COBOL READ TRANCATG KEY IS '999999' → FILE STATUS '23'
        Optional<TransactionCategory> result = repository.findById(nonExistentKey);

        // Assert — should be empty (INVALID KEY)
        assertThat(result)
                .as("Non-existent composite key (99, 9999) should return empty Optional")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 3: findAll — read-only reference data browse
    // Verifies sequential read of all 18 TRANCATG records
    // Equivalent to COBOL STARTBR / READNEXT loop through all records
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findAll()} returns exactly 18 transaction category records
     * from the seed data loaded via Flyway V3 migration from {@code trancatg.txt}.
     *
     * <p>The 18 records span 7 transaction type groups (01-07) with varying numbers
     * of categories per type: 5+3+3+3+1+2+1 = 18 total.</p>
     */
    @Test
    @DisplayName("findAll returns exactly 18 transaction categories from seed data")
    void testFindAll() {
        // Act — equivalent to COBOL STARTBR / READNEXT loop
        List<TransactionCategory> allCategories = repository.findAll();

        // Assert — verify total record count matches trancatg.txt (18 records)
        assertThat(allCategories)
                .as("Should return all 18 transaction categories from seed data")
                .hasSize(18);

        // Verify each record has valid composite key and non-empty description
        for (TransactionCategory category : allCategories) {
            assertThat(category.getId())
                    .as("Each category must have a non-null composite key")
                    .isNotNull();

            assertThat(category.getId().getTypeCode())
                    .as("Type code must not be null or blank")
                    .isNotBlank();

            assertThat(category.getId().getCatCode())
                    .as("Category code must not be null")
                    .isNotNull();

            assertThat(category.getTranCatTypeDesc())
                    .as("Category description must not be null or blank")
                    .isNotBlank();
        }
    }

    // -----------------------------------------------------------------------
    // Test 4: findAll — comprehensive verification of all 18 categories
    // Verifies every record from trancatg.txt by collecting into a map
    // keyed by composite TransactionCategoryId
    // -----------------------------------------------------------------------

    /**
     * Verifies all 18 categories from {@code trancatg.txt} by collecting
     * {@code findAll()} results into a map keyed by composite
     * {@link TransactionCategoryId} and asserting each entry's description.
     *
     * <p>Complete transaction category reference data from trancatg.txt:</p>
     * <pre>
     * Type 01 (Purchase):      0001=Regular Sales Draft, 0002=Regular Cash Advance,
     *                          0003=Convenience Check Debit, 0004=ATM Cash Advance,
     *                          0005=Interest Amount
     * Type 02 (Payment):       0001=Cash payment, 0002=Electronic payment,
     *                          0003=Check payment
     * Type 03 (Credit):        0001=Credit to Account, 0002=Credit to Purchase balance,
     *                          0003=Credit to Cash balance
     * Type 04 (Authorization): 0001=Zero dollar authorization,
     *                          0002=Online purchase authorization,
     *                          0003=Travel booking authorization
     * Type 05 (Refund):        0001=Refund credit
     * Type 06 (Reversal):      0001=Fraud reversal, 0002=Non-fraud reversal
     * Type 07 (Adjustment):    0001=Sales draft credit adjustment
     * </pre>
     */
    @Test
    @DisplayName("findAll verifies all 18 categories from trancatg.txt with correct descriptions")
    void testFindAll_VerifyAllCategories() {
        // Act — load all categories
        List<TransactionCategory> allCategories = repository.findAll();

        // Build a lookup map: TransactionCategoryId → description
        Map<TransactionCategoryId, String> categoryMap = new HashMap<>();
        for (TransactionCategory category : allCategories) {
            categoryMap.put(category.getId(), category.getTranCatTypeDesc());
        }

        // Assert — verify all 18 entries are present with correct descriptions

        // Type 01: Purchase — 5 categories
        assertCategoryPresent(categoryMap, "01", (short) 1, "Regular Sales Draft");
        assertCategoryPresent(categoryMap, "01", (short) 2, "Regular Cash Advance");
        assertCategoryPresent(categoryMap, "01", (short) 3, "Convenience Check Debit");
        assertCategoryPresent(categoryMap, "01", (short) 4, "ATM Cash Advance");
        assertCategoryPresent(categoryMap, "01", (short) 5, "Interest Amount");

        // Type 02: Payment — 3 categories
        assertCategoryPresent(categoryMap, "02", (short) 1, "Cash payment");
        assertCategoryPresent(categoryMap, "02", (short) 2, "Electronic payment");
        assertCategoryPresent(categoryMap, "02", (short) 3, "Check payment");

        // Type 03: Credit — 3 categories
        assertCategoryPresent(categoryMap, "03", (short) 1, "Credit to Account");
        assertCategoryPresent(categoryMap, "03", (short) 2, "Credit to Purchase balance");
        assertCategoryPresent(categoryMap, "03", (short) 3, "Credit to Cash balance");

        // Type 04: Authorization — 3 categories
        assertCategoryPresent(categoryMap, "04", (short) 1, "Zero dollar authorization");
        assertCategoryPresent(categoryMap, "04", (short) 2, "Online purchase authorization");
        assertCategoryPresent(categoryMap, "04", (short) 3, "Travel booking authorization");

        // Type 05: Refund — 1 category
        assertCategoryPresent(categoryMap, "05", (short) 1, "Refund credit");

        // Type 06: Reversal — 2 categories
        assertCategoryPresent(categoryMap, "06", (short) 1, "Fraud reversal");
        assertCategoryPresent(categoryMap, "06", (short) 2, "Non-fraud reversal");

        // Type 07: Adjustment — 1 category
        assertCategoryPresent(categoryMap, "07", (short) 1, "Sales draft credit adjustment");
    }

    // -----------------------------------------------------------------------
    // Test 5: Composite key equality — @EmbeddedId equals/hashCode
    // Verifies TransactionCategoryId contract for JPA identity semantics
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link TransactionCategoryId} correctly implements
     * {@code equals()} and {@code hashCode()} for JPA entity identity.
     *
     * <p>Correct composite key equality is critical for JPA persistence context
     * behavior, first-level cache operations, and collection membership checks.
     * Two keys with identical {@code typeCode} and {@code catCode} must be equal;
     * keys with different values must not be equal.</p>
     */
    @Test
    @DisplayName("TransactionCategoryId equals/hashCode correctness for composite key identity")
    void testCompositeKeyEquality() {
        // Setup 1: Two keys with identical values
        TransactionCategoryId key1 = new TransactionCategoryId("01", (short) 1);
        TransactionCategoryId key2 = new TransactionCategoryId("01", (short) 1);

        // Assert: equals() returns true for identical composite key values
        assertThat(key1.equals(key2))
                .as("Two TransactionCategoryId with same typeCode and catCode should be equal")
                .isTrue();

        // Assert: hashCode() matches for equal objects
        assertThat(key1.hashCode())
                .as("Equal TransactionCategoryId instances must produce same hashCode")
                .isEqualTo(key2.hashCode());

        // Setup 2: Two keys with different typeCode
        TransactionCategoryId differentTypeKey = new TransactionCategoryId("02", (short) 1);

        // Assert: equals() returns false for different type codes
        assertThat(key1.equals(differentTypeKey))
                .as("TransactionCategoryId with different typeCode should not be equal")
                .isFalse();

        // Setup 3: Two keys with different catCode
        TransactionCategoryId differentCatKey = new TransactionCategoryId("01", (short) 2);

        // Assert: equals() returns false for different category codes
        assertThat(key1.equals(differentCatKey))
                .as("TransactionCategoryId with different catCode should not be equal")
                .isFalse();

        // Setup 4: Two keys with both fields different
        TransactionCategoryId fullyDifferentKey = new TransactionCategoryId("05", (short) 9);

        // Assert: equals() returns false for completely different keys
        assertThat(key1.equals(fullyDifferentKey))
                .as("TransactionCategoryId with both fields different should not be equal")
                .isFalse();

        // Verify null and other type handling
        assertThat(key1.equals(null))
                .as("TransactionCategoryId should not equal null")
                .isFalse();

        assertThat(key1.equals("not-a-key"))
                .as("TransactionCategoryId should not equal a String")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Test 6: Save and retrieve round-trip
    // Verifies JPA entity persistence with composite @EmbeddedId
    // Uses TestEntityManager.flush() + clear() to ensure database round-trip
    // -----------------------------------------------------------------------

    /**
     * Verifies that a new {@link TransactionCategory} entity with a composite
     * {@code @EmbeddedId} can be saved and retrieved with all fields intact.
     *
     * <p>Uses {@link TestEntityManager#flush()} and {@link TestEntityManager#clear()}
     * to force a real database round-trip, bypassing Hibernate's first-level cache.
     * This ensures the composite primary key, column mappings, and VARCHAR constraints
     * are correctly handled by PostgreSQL.</p>
     *
     * <p>The test creates a new category with type code "07" (Adjustment, which exists
     * in transaction_types) and category code 99 (which does not conflict with the
     * single existing "07"/1 seed record). Type "07" is used to satisfy the FK
     * constraint {@code fk_tran_cat_type} referencing {@code transaction_types(type_cd)}.
     * </p>
     */
    @Test
    @DisplayName("Save and retrieve round-trip with composite key (07, 99)")
    void testSaveAndRetrieve() {
        // Arrange — use existing type "07" (Adjustment) with new cat_cd 99
        // Type "07" exists in transaction_types seed data, satisfying FK constraint
        // Cat code 99 does not conflict with existing seed data (only 07/1 exists)
        TransactionCategoryId newKey = new TransactionCategoryId("07", (short) 99);
        TransactionCategory newCategory = new TransactionCategory(newKey, "Test Category");

        // Act — save, flush to database, clear first-level cache
        repository.save(newCategory);
        entityManager.flush();
        entityManager.clear();

        // Re-read from database (not from cache) to verify round-trip
        Optional<TransactionCategory> retrieved = repository.findById(newKey);

        // Assert — verify all fields survived the round-trip
        assertThat(retrieved)
                .as("Saved category with key (07, 99) should be retrievable from database")
                .isPresent();

        TransactionCategory retrievedCategory = retrieved.get();

        // Verify composite key fields
        assertThat(retrievedCategory.getId())
                .as("Retrieved entity should have the correct composite key")
                .isNotNull();

        assertThat(retrievedCategory.getId().getTypeCode())
                .as("Retrieved type code should be '07'")
                .isEqualTo("07");

        assertThat(retrievedCategory.getId().getCatCode())
                .as("Retrieved category code should be 99")
                .isEqualTo((short) 99);

        // Verify description field
        assertThat(retrievedCategory.getTranCatTypeDesc())
                .as("Retrieved description should be 'Test Category'")
                .isEqualTo("Test Category");
    }

    // -----------------------------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Helper method that asserts a specific transaction category entry exists in
     * the provided map with the expected description.
     *
     * @param categoryMap   map of TransactionCategoryId → description from findAll()
     * @param typeCode      the 2-character transaction type code
     * @param catCode       the transaction category code (SMALLINT)
     * @param expectedDesc  the expected category description (or substring)
     */
    private void assertCategoryPresent(Map<TransactionCategoryId, String> categoryMap,
                                       String typeCode, Short catCode, String expectedDesc) {
        TransactionCategoryId key = new TransactionCategoryId(typeCode, catCode);

        assertThat(categoryMap.containsKey(key))
                .as("Category (%s, %d) should exist in seed data", typeCode, catCode)
                .isTrue();

        String actualDesc = categoryMap.get(key);
        assertThat(actualDesc)
                .as("Category (%s, %d) description should contain '%s'",
                        typeCode, catCode, expectedDesc)
                .contains(expectedDesc);
    }
}
