package com.cardemo.integration.repository;

import com.cardemo.model.entity.TransactionType;
import com.cardemo.repository.TransactionTypeRepository;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TransactionTypeRepository} verifying read-only
 * reference data access against a real PostgreSQL 16 instance via Testcontainers.
 *
 * <p>This is the simplest repository test in the CardDemo application — a simple
 * {@code String} primary key (2-character type code) with no custom query methods.
 * All required data access operations are inherited from {@code JpaRepository}.</p>
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS)
 *     KEYS(2 0)
 *     RECORDSIZE(60 60)
 *     INDEXED)
 * </pre>
 *
 * <h3>COBOL Record Layout (CVTRA03Y.cpy)</h3>
 * <pre>
 * 01  TRAN-TYPE-RECORD.
 *     05  TRAN-TYPE       PIC X(02).   — Primary key (type_cd CHAR(2))
 *     05  TRAN-TYPE-DESC  PIC X(50).   — Description (type_desc VARCHAR(50))
 *     05  FILLER          PIC X(08).   — Not mapped
 * </pre>
 *
 * <h3>Seed Data (trantype.txt — 7 records)</h3>
 * <ul>
 *   <li>01 → Purchase</li>
 *   <li>02 → Payment</li>
 *   <li>03 → Credit</li>
 *   <li>04 → Authorization</li>
 *   <li>05 → Refund</li>
 *   <li>06 → Reversal</li>
 *   <li>07 → Adjustment</li>
 * </ul>
 *
 * <p>All tests run against a real PostgreSQL 16 instance via Testcontainers with Flyway
 * migrations provisioning the schema (V1__create_schema.sql) and seed data
 * (V3__seed_data.sql — 7 transaction type records parsed from
 * {@code app/data/ASCII/trantype.txt}).</p>
 *
 * <p>COBOL source reference: {@code app/jcl/TRANTYPE.jcl}, {@code app/cpy/CVTRA03Y.cpy},
 * and {@code app/data/ASCII/trantype.txt} from commit {@code 27d6c6f}.</p>
 *
 * @see TransactionType
 * @see TransactionTypeRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("TransactionTypeRepository Integration Tests — TRANTYPE VSAM KSDS")
public class TransactionTypeRepositoryIT {

    // -----------------------------------------------------------------------
    // Testcontainers PostgreSQL 16 — managed lifecycle via @Container
    // Replaces VSAM DEFINE CLUSTER for TRANTYPE.VSAM.KSDS
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
    private TransactionTypeRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    // -----------------------------------------------------------------------
    // Test 1: findById for Purchase type (code "01")
    // Verifies single keyed read — equivalent to COBOL READ TRANTYPE KEY("01")
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById("01")} returns the Purchase transaction type
     * seeded from {@code trantype.txt} via Flyway V3 migration.
     *
     * <p>Maps COBOL keyed read: {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD
     * KEY IS '01'}. The PK is TRAN-TYPE PIC X(02) at KEYS(2,0).</p>
     */
    @Test
    @DisplayName("findById('01') returns Purchase transaction type")
    void testFindById_Purchase() {
        // Act — equivalent to COBOL READ TRANTYPE with KEY='01'
        Optional<TransactionType> result = repository.findById("01");

        // Assert — verify presence and field values
        assertThat(result)
                .as("Transaction type '01' (Purchase) should be present in seed data")
                .isPresent();

        TransactionType purchaseType = result.get();

        assertThat(purchaseType.getTranType())
                .as("Type code should be '01'")
                .isEqualTo("01");

        assertThat(purchaseType.getTranTypeDesc())
                .as("Type description should contain 'Purchase' (trimmed from PIC X(50))")
                .contains("Purchase");
    }

    // -----------------------------------------------------------------------
    // Test 2: findById for all 7 transaction types
    // Verifies complete seed data from trantype.txt (7 records, 60 bytes each)
    // -----------------------------------------------------------------------

    /**
     * Verifies that all 7 transaction types from {@code trantype.txt} are
     * accessible via {@code findById()} with correct type codes and descriptions.
     *
     * <p>Transaction type codes and descriptions from the ASCII fixture file:</p>
     * <pre>
     * 01 Purchase          (pos 1-2: type_cd, pos 3-52: type_desc)
     * 02 Payment
     * 03 Credit
     * 04 Authorization
     * 05 Refund
     * 06 Reversal
     * 07 Adjustment
     * </pre>
     */
    @Test
    @DisplayName("findById returns all 7 transaction types with correct descriptions")
    void testFindById_AllSevenTypes() {
        // Define the complete expected dataset from trantype.txt
        Map<String, String> expectedTypes = Map.of(
                "01", "Purchase",
                "02", "Payment",
                "03", "Credit",
                "04", "Authorization",
                "05", "Refund",
                "06", "Reversal",
                "07", "Adjustment"
        );

        // Verify each transaction type individually
        for (Map.Entry<String, String> entry : expectedTypes.entrySet()) {
            String typeCode = entry.getKey();
            String expectedDesc = entry.getValue();

            Optional<TransactionType> result = repository.findById(typeCode);

            assertThat(result)
                    .as("Transaction type '%s' (%s) should be present", typeCode, expectedDesc)
                    .isPresent();

            TransactionType transactionType = result.get();

            assertThat(transactionType.getTranType())
                    .as("Type code for %s should be '%s'", expectedDesc, typeCode)
                    .isEqualTo(typeCode);

            assertThat(transactionType.getTranTypeDesc())
                    .as("Description for type '%s' should contain '%s'", typeCode, expectedDesc)
                    .contains(expectedDesc);
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: findById for non-existent type code
    // Verifies empty result — equivalent to COBOL READ with FILE STATUS '23'
    // (INVALID KEY / record not found)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById("99")} returns an empty {@code Optional}
     * for a non-existent transaction type code.
     *
     * <p>Maps COBOL FILE STATUS '23' (INVALID KEY — record not found) for the
     * TRANTYPE VSAM KSDS dataset when the requested key does not exist.</p>
     */
    @Test
    @DisplayName("findById('99') returns empty Optional for non-existent type code")
    void testFindById_NonExistent() {
        // Act — attempt to read a type code that does not exist in seed data
        Optional<TransactionType> result = repository.findById("99");

        // Assert — result should be empty (FILE STATUS '23' equivalent)
        assertThat(result)
                .as("Type code '99' does not exist — findById should return empty")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 4: findAll returns exactly 7 records
    // Verifies full table scan — equivalent to COBOL BROWSE TRANTYPE
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findAll()} returns exactly 7 transaction type records
     * matching the seed data from {@code trantype.txt}.
     *
     * <p>Each record must have a 2-character type code and a non-blank description,
     * matching the COBOL record layout: {@code TRAN-TYPE PIC X(02)} and
     * {@code TRAN-TYPE-DESC PIC X(50)}.</p>
     */
    @Test
    @DisplayName("findAll() returns exactly 7 transaction type records")
    void testFindAll() {
        // Act — equivalent to COBOL sequential browse of TRANTYPE file
        List<TransactionType> allTypes = repository.findAll();

        // Assert — verify count matches seed data
        assertThat(allTypes)
                .as("Should return exactly 7 transaction types from V3 seed data")
                .hasSize(7);

        // Verify each record has valid field values per CVTRA03Y.cpy layout
        assertThat(allTypes).allSatisfy(type -> {
            assertThat(type.getTranType())
                    .as("Type code must be exactly 2 characters (PIC X(02))")
                    .isNotNull()
                    .hasSize(2);

            assertThat(type.getTranTypeDesc())
                    .as("Type description must not be blank (PIC X(50))")
                    .isNotBlank();
        });
    }

    // -----------------------------------------------------------------------
    // Test 5: save and retrieve round-trip
    // Verifies WRITE + READ cycle — COBOL WRITE TRANTYPE-FILE FROM record
    // followed by READ TRANTYPE-FILE INTO record KEY IS '08'
    // -----------------------------------------------------------------------

    /**
     * Verifies that a new {@code TransactionType} can be saved and retrieved
     * with all field values preserved through the JPA persistence cycle.
     *
     * <p>Uses {@code TestEntityManager.flush()} to force the INSERT SQL, then
     * {@code TestEntityManager.clear()} to evict the first-level cache, ensuring
     * the subsequent {@code findById()} performs an actual SELECT against PostgreSQL
     * rather than returning the cached entity instance.</p>
     *
     * <p>Maps COBOL WRITE: {@code WRITE TRANTYPE-RECORD} with
     * {@code TRAN-TYPE = '08'}, {@code TRAN-TYPE-DESC = 'Custom Type'}.</p>
     */
    @Test
    @DisplayName("save() and findById() round-trip preserves all TransactionType fields")
    void testSaveAndRetrieve() {
        // Arrange — create a new TransactionType not in the seed data
        TransactionType customType = new TransactionType("08", "Custom Type");

        // Act — save, flush to DB, clear L1 cache, then retrieve
        repository.save(customType);
        entityManager.flush();
        entityManager.clear();

        Optional<TransactionType> retrieved = repository.findById("08");

        // Assert — round-trip must preserve all fields exactly
        assertThat(retrieved)
                .as("Saved transaction type '08' should be retrievable after flush+clear")
                .isPresent();

        TransactionType roundTripped = retrieved.get();

        assertThat(roundTripped.getTranType())
                .as("Type code should be preserved as '08'")
                .isEqualTo("08");

        assertThat(roundTripped.getTranTypeDesc())
                .as("Type description should be preserved as 'Custom Type'")
                .isEqualTo("Custom Type");
    }
}
