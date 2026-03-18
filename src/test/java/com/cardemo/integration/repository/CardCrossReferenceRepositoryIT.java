package com.cardemo.integration.repository;

import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.repository.CardCrossReferenceRepository;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link CardCrossReferenceRepository} verifying CRUD and
 * custom query operations against a real PostgreSQL 16 database via Testcontainers.
 *
 * <p>Validates the JPA entity mapping for the {@code card_cross_references} table,
 * which migrates the COBOL CARDXREF VSAM KSDS dataset defined in
 * {@code XREFFILE.jcl} with the record layout from {@code CVACT03Y.cpy} (50 bytes).</p>
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)
 *     KEYS(16 0)
 *     RECORDSIZE(50 50)
 *     INDEXED)
 * </pre>
 *
 * <h3>COBOL Record Layout (CVACT03Y.cpy)</h3>
 * <pre>
 * 01  CARD-XREF-RECORD.
 *     05  XREF-CARD-NUM   PIC X(16).   — Primary key (card_num VARCHAR(16))
 *     05  XREF-CUST-ID    PIC 9(09).   — Customer FK (cust_id VARCHAR(9))
 *     05  XREF-ACCT-ID    PIC 9(11).   — Account FK (account_id VARCHAR(11))
 *     05  FILLER           PIC X(14).   — Not mapped
 * </pre>
 *
 * <h3>VSAM Alternate Index (CXACAIX)</h3>
 * <pre>
 * DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.CARDXREF.CXACAIX)
 *     RELATE(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)
 *     KEYS(11 25) NONUNIQUEKEY)
 * </pre>
 * <p>The CXACAIX alternate index enables lookup of multiple cards by account ID.
 * This is CRITICAL for programs COACTVWC, COCRDLIC, CBTRN02C, and CBSTM03A
 * which all perform account-based card lookups. The {@code NONUNIQUEKEY}
 * attribute means multiple cross-reference records can share the same account ID.
 * The Java equivalent is the {@code findByXrefAcctId(String)} repository method
 * backed by {@code idx_card_xref_account_id} in V2 migration.</p>
 *
 * <h3>Seed Data (cardxref.txt — 50 records)</h3>
 * <ul>
 *   <li>First record: card 0500024453765740 → cust 000000050 → acct 00000000050</li>
 *   <li>Records map 1-to-1 in seed data (each account has exactly one card)</li>
 *   <li>Account IDs range from 00000000001 to 00000000050</li>
 * </ul>
 *
 * <h3>Test Strategy</h3>
 * <p>Six test methods validate:</p>
 * <ol>
 *   <li>Primary key lookup (VSAM READ by XREF-CARD-NUM)</li>
 *   <li>Non-existent primary key (FILE STATUS '23')</li>
 *   <li>CXACAIX alternate index lookup (findByXrefAcctId — CRITICAL)</li>
 *   <li>Non-existent alternate index value</li>
 *   <li>Save and retrieve round-trip (WRITE + READ)</li>
 *   <li>NONUNIQUEKEY semantics (multiple cards per account)</li>
 * </ol>
 *
 * @see CardCrossReference
 * @see CardCrossReferenceRepository
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("CardCrossReferenceRepository Integration Tests — CARDXREF VSAM KSDS + CXACAIX AIX")
public class CardCrossReferenceRepositoryIT {

    // -----------------------------------------------------------------------
    // Testcontainers PostgreSQL 16 — managed lifecycle via @Container
    // Replaces VSAM DEFINE CLUSTER for CARDXREF.VSAM.KSDS
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
     * <p>This ensures Flyway migrations (V1 schema, V2 indexes, V3 seed data)
     * run against the real PostgreSQL container and Hibernate validates entity
     * mappings against the Flyway-created schema.</p>
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
    private CardCrossReferenceRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    // -----------------------------------------------------------------------
    // Test 1: findById for existing cross-reference
    // Verifies primary key lookup — COBOL READ CARDXREF KEY(XREF-CARD-NUM)
    // Source: first record from cardxref.txt via V3 seed migration
    // VSAM KEYS(16,0) — 16-byte card number at offset 0
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById("0500024453765740")} returns the first
     * card cross-reference record seeded from {@code cardxref.txt} via Flyway V3.
     *
     * <p>Validates all three CVACT03Y.cpy fields are correctly mapped:</p>
     * <ul>
     *   <li>{@code XREF-CARD-NUM PIC X(16)} → xrefCardNum = "0500024453765740"</li>
     *   <li>{@code XREF-CUST-ID PIC 9(09)} → xrefCustId = "000000050" (leading zeros preserved)</li>
     *   <li>{@code XREF-ACCT-ID PIC 9(11)} → xrefAcctId = "00000000050" (leading zeros preserved)</li>
     * </ul>
     */
    @Test
    @DisplayName("findById('0500024453765740') returns first cross-reference with all fields mapped")
    void testFindById_ExistingCrossReference() {
        // Act — equivalent to COBOL READ CARDXREF KEY IS '0500024453765740'
        Optional<CardCrossReference> result = repository.findById("0500024453765740");

        // Assert — verify presence
        assertThat(result)
                .as("Card cross-reference '0500024453765740' should be present in V3 seed data")
                .isPresent();

        CardCrossReference xref = result.get();

        // Assert — verify XREF-CARD-NUM PIC X(16) primary key
        assertThat(xref.getXrefCardNum())
                .as("Card number should be '0500024453765740' (PIC X(16))")
                .isEqualTo("0500024453765740");

        // Assert — verify XREF-CUST-ID PIC 9(09) with leading zeros preserved
        assertThat(xref.getXrefCustId())
                .as("Customer ID should be '000000050' — PIC 9(09) leading zeros preserved as String")
                .isEqualTo("000000050");

        // Assert — verify XREF-ACCT-ID PIC 9(11) with leading zeros preserved
        assertThat(xref.getXrefAcctId())
                .as("Account ID should be '00000000050' — PIC 9(11) leading zeros preserved as String")
                .isEqualTo("00000000050");
    }

    // -----------------------------------------------------------------------
    // Test 2: findById for non-existent card number
    // Verifies empty result — equivalent to COBOL FILE STATUS '23'
    // (INVALID KEY / record not found on CARDXREF VSAM KSDS)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById("9999999999999999")} returns an empty
     * {@code Optional} for a card number that does not exist in the dataset.
     *
     * <p>Maps COBOL FILE STATUS '23' (INVALID KEY — record not found) for the
     * CARDXREF VSAM KSDS dataset when the requested primary key does not match
     * any XREF-CARD-NUM value.</p>
     */
    @Test
    @DisplayName("findById('9999999999999999') returns empty Optional for non-existent card")
    void testFindById_NonExistent() {
        // Act — attempt to read a card number that does not exist in seed data
        Optional<CardCrossReference> result = repository.findById("9999999999999999");

        // Assert — result should be empty (FILE STATUS '23' equivalent)
        assertThat(result)
                .as("Card number '9999999999999999' does not exist — findById should return empty")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 3: CRITICAL — CXACAIX alternate index lookup
    // Maps: DEFINE ALTERNATEINDEX(CXACAIX) KEYS(11,25) NONUNIQUEKEY
    // The VSAM AIX allows multiple cards to reference the same account.
    // Java equivalent: findByXrefAcctId(String) → List<CardCrossReference>
    // Backed by idx_card_xref_account_id from V2 migration
    // Used by: COACTVWC, COCRDLIC, CBTRN02C, CBSTM03A
    // -----------------------------------------------------------------------

    /**
     * <b>CRITICAL</b> — Validates the CXACAIX alternate index equivalent:
     * {@code findByXrefAcctId("00000000001")} must return a list of cross-reference
     * records for account ID "00000000001".
     *
     * <p>The VSAM ALTERNATEINDEX CXACAIX definition is:</p>
     * <pre>
     * DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.CARDXREF.CXACAIX)
     *     RELATE(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)
     *     KEYS(11 25) NONUNIQUEKEY)
     * </pre>
     *
     * <p>KEYS(11,25) means 11-byte key at offset 25 in the 50-byte record,
     * which corresponds to XREF-ACCT-ID PIC 9(11). NONUNIQUEKEY means
     * multiple cross-reference records can share the same account ID.</p>
     *
     * <p>This access path is used by COACTVWC (account view), COCRDLIC (card list),
     * CBTRN02C (batch transaction posting), and CBSTM03A (statement generation)
     * for account-based card lookups.</p>
     */
    @Test
    @DisplayName("findByXrefAcctId('00000000001') returns cross-references via CXACAIX alternate index")
    void testFindByXrefAcctId_CRITICAL_CXACAIX() {
        // Act — equivalent to COBOL READ CARDXREF through CXACAIX PATH
        // with ALTERNATE KEY = '00000000001'
        List<CardCrossReference> results = repository.findByXrefAcctId("00000000001");

        // Assert — list must not be empty (at least one card for this account in seed data)
        assertThat(results)
                .as("Account '00000000001' should have at least one cross-reference in V3 seed data")
                .isNotEmpty();

        // Assert — every returned record must have the queried account ID
        assertThat(results)
                .as("All returned cross-references must have xrefAcctId = '00000000001'")
                .allSatisfy(xref ->
                        assertThat(xref.getXrefAcctId()).isEqualTo("00000000001")
                );

        // Assert — each record has valid field lengths per CVACT03Y.cpy layout
        assertThat(results).allSatisfy(xref -> {
            // XREF-CARD-NUM PIC X(16) — exactly 16 characters
            assertThat(xref.getXrefCardNum())
                    .as("Card number must be exactly 16 characters (PIC X(16))")
                    .isNotNull()
                    .hasSize(16);

            // XREF-CUST-ID PIC 9(09) — exactly 9 characters with leading zeros
            assertThat(xref.getXrefCustId())
                    .as("Customer ID must be exactly 9 characters (PIC 9(09))")
                    .isNotNull()
                    .hasSize(9);

            // XREF-ACCT-ID PIC 9(11) — exactly 11 characters with leading zeros
            assertThat(xref.getXrefAcctId())
                    .as("Account ID must be exactly 11 characters (PIC 9(11))")
                    .hasSize(11);
        });
    }

    // -----------------------------------------------------------------------
    // Test 4: CXACAIX alternate index with non-existent account
    // Verifies empty list return — COBOL FILE STATUS '23' on CXACAIX PATH
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findByXrefAcctId("99999999999")} returns an empty
     * list when no cross-reference records exist for the given account ID.
     *
     * <p>Maps COBOL FILE STATUS '23' (record not found) on the CXACAIX
     * alternate index PATH when the requested account ID does not match
     * any XREF-ACCT-ID value in the dataset.</p>
     */
    @Test
    @DisplayName("findByXrefAcctId('99999999999') returns empty list for non-existent account")
    void testFindByXrefAcctId_NonExistentAccount() {
        // Act — attempt to read through CXACAIX with a non-existent account
        List<CardCrossReference> results = repository.findByXrefAcctId("99999999999");

        // Assert — result should be an empty list (not null)
        assertThat(results)
                .as("Account '99999999999' does not exist — findByXrefAcctId should return empty list")
                .isNotNull()
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 5: save and retrieve round-trip
    // Verifies WRITE + READ cycle — COBOL WRITE CARDXREF FROM record
    // followed by READ CARDXREF KEY IS new-card-num
    // Uses flush/clear to ensure true database round-trip (not L1 cache)
    // -----------------------------------------------------------------------

    /**
     * Verifies that a new {@code CardCrossReference} can be saved and retrieved
     * with all field values preserved through the JPA persistence cycle.
     *
     * <p>Uses {@code TestEntityManager.flush()} to force the INSERT SQL, then
     * {@code TestEntityManager.clear()} to evict the first-level cache, ensuring
     * the subsequent {@code findById()} performs an actual SELECT against PostgreSQL
     * rather than returning the cached entity instance.</p>
     *
     * <p>References existing account '00000000001' and customer '000000001'
     * from V3 seed data to satisfy the foreign key constraints on
     * {@code card_cross_references}.</p>
     *
     * <p>Maps COBOL WRITE: {@code WRITE CARD-XREF-RECORD} with
     * {@code XREF-CARD-NUM = '1234567890123456'}, {@code XREF-CUST-ID = '000000001'},
     * {@code XREF-ACCT-ID = '00000000001'}.</p>
     */
    @Test
    @DisplayName("save() and findById() round-trip preserves all CardCrossReference fields")
    void testSaveAndRetrieve() {
        // Arrange — create a new cross-reference not in seed data
        // Uses existing account 00000000001 and customer 000000001 from V3 seed
        // to satisfy FK constraints (fk_card_xref_account, fk_card_xref_customer)
        CardCrossReference newXref = new CardCrossReference();
        newXref.setXrefCardNum("1234567890123456");
        newXref.setXrefCustId("000000001");
        newXref.setXrefAcctId("00000000001");

        // Act — save, flush to DB, clear L1 cache, then retrieve
        repository.save(newXref);
        entityManager.flush();
        entityManager.clear();

        Optional<CardCrossReference> retrieved = repository.findById("1234567890123456");

        // Assert — round-trip must preserve all fields exactly
        assertThat(retrieved)
                .as("Saved cross-reference '1234567890123456' should be retrievable after flush+clear")
                .isPresent();

        CardCrossReference roundTripped = retrieved.get();

        assertThat(roundTripped.getXrefCardNum())
                .as("Card number should be preserved as '1234567890123456'")
                .isEqualTo("1234567890123456");

        assertThat(roundTripped.getXrefCustId())
                .as("Customer ID should be preserved as '000000001' (leading zeros intact)")
                .isEqualTo("000000001");

        assertThat(roundTripped.getXrefAcctId())
                .as("Account ID should be preserved as '00000000001' (leading zeros intact)")
                .isEqualTo("00000000001");
    }

    // -----------------------------------------------------------------------
    // Test 6: NONUNIQUEKEY semantics — multiple cards per account
    // CRITICAL — validates the CXACAIX NONUNIQUEKEY attribute meaning
    // multiple CARD-XREF-RECORD entries can share the same XREF-ACCT-ID
    // This is the core many-to-one (cards-to-account) relationship pattern
    // -----------------------------------------------------------------------

    /**
     * <b>CRITICAL</b> — Validates VSAM AIX {@code NONUNIQUEKEY} semantics where
     * multiple cross-reference records can share the same account ID.
     *
     * <p>Creates parent account and customer records via native SQL (to avoid
     * importing additional entity classes outside the dependency whitelist), then
     * saves 3 distinct cross-reference records all pointing to the same account.
     * Verifies that {@code findByXrefAcctId()} returns exactly 3 records, each
     * with a different card number but the same account ID.</p>
     *
     * <p>This pattern is fundamental to the CardDemo data model: one account
     * can have multiple credit cards, and the CXACAIX alternate index enables
     * programs like COCRDLIC (card list) and CBSTM03A (statement generation)
     * to enumerate all cards for a given account.</p>
     */
    @Test
    @DisplayName("NONUNIQUEKEY: findByXrefAcctId returns multiple cards for same account")
    void testNonUniqueKeySemantics() {
        // Arrange — create parent account and customer via native SQL to satisfy FK constraints
        // Account 00000000099 does not exist in V3 seed data (seed covers 00000000001–00000000050)
        // Customer 000000099 does not exist in V3 seed data (seed covers 000000001–000000050)
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO accounts (acct_id) VALUES ('00000000099')")
                .executeUpdate();
        entityManager.getEntityManager()
                .createNativeQuery(
                        "INSERT INTO customers (cust_id, first_name, last_name) "
                        + "VALUES ('000000099', 'Test', 'NonUniqueKey')")
                .executeUpdate();

        // Arrange — save 3 cross-references all pointing to the same account
        // Each has a unique card number (PK) but shares the same account ID
        CardCrossReference xref1 = new CardCrossReference();
        xref1.setXrefCardNum("1111111111111111");
        xref1.setXrefCustId("000000099");
        xref1.setXrefAcctId("00000000099");

        CardCrossReference xref2 = new CardCrossReference();
        xref2.setXrefCardNum("2222222222222222");
        xref2.setXrefCustId("000000099");
        xref2.setXrefAcctId("00000000099");

        CardCrossReference xref3 = new CardCrossReference();
        xref3.setXrefCardNum("3333333333333333");
        xref3.setXrefCustId("000000099");
        xref3.setXrefAcctId("00000000099");

        repository.save(xref1);
        repository.save(xref2);
        repository.save(xref3);

        // Flush to force INSERTs and clear L1 cache for true database round-trip
        entityManager.flush();
        entityManager.clear();

        // Act — query via CXACAIX alternate index equivalent
        List<CardCrossReference> results = repository.findByXrefAcctId("00000000099");

        // Assert — exactly 3 records returned (NONUNIQUEKEY semantics)
        assertThat(results)
                .as("NONUNIQUEKEY: findByXrefAcctId should return exactly 3 cross-references")
                .hasSize(3);

        // Assert — all records share the same account ID
        assertThat(results)
                .as("All returned cross-references must have xrefAcctId = '00000000099'")
                .allSatisfy(xref ->
                        assertThat(xref.getXrefAcctId()).isEqualTo("00000000099")
                );

        // Assert — all records have the same customer ID
        assertThat(results)
                .as("All returned cross-references must have xrefCustId = '000000099'")
                .allSatisfy(xref ->
                        assertThat(xref.getXrefCustId()).isEqualTo("000000099")
                );

        // Assert — each record has a distinct card number (PK uniqueness)
        assertThat(results)
                .as("Each cross-reference must have a distinct card number")
                .extracting(CardCrossReference::getXrefCardNum)
                .containsExactlyInAnyOrder(
                        "1111111111111111",
                        "2222222222222222",
                        "3333333333333333"
                );
    }
}
