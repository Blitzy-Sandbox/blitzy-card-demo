package com.cardemo.integration.repository;

import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.repository.DisclosureGroupRepository;

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
 * Integration test for {@link DisclosureGroupRepository} verifying composite key
 * operations, BigDecimal interest rate precision, and the <strong>CRITICAL DEFAULT
 * fallback pattern</strong> for interest rate lookups against a real PostgreSQL 16
 * instance via Testcontainers.
 *
 * <p>The disclosure group table is central to the batch interest calculation process
 * ({@code CBACT04C.cbl}). The CRITICAL DEFAULT fallback pattern works as follows:</p>
 * <ol>
 *   <li>For each account's category balance, look up the interest rate using the
 *       account's specific group ID + transaction type code + category code.</li>
 *   <li>If no specific rate exists (FILE STATUS '23' / empty Optional), fall back
 *       to the {@code "DEFAULT"} group ID with the same type and category codes.</li>
 *   <li>The resolved rate is used in the formula:
 *       {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200} with
 *       {@code RoundingMode.HALF_EVEN} (banker's rounding).</li>
 * </ol>
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS)
 *     KEYS(16 0)
 *     RECORDSIZE(50 50)
 *     SHAREOPTIONS(2 3)
 *     INDEXED)
 * </pre>
 *
 * <h3>COBOL Record Layout (CVTRA02Y.cpy)</h3>
 * <pre>
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *        10 DIS-ACCT-GROUP-ID   PIC X(10).   — groupId  VARCHAR(10)
 *        10 DIS-TRAN-TYPE-CD    PIC X(02).   — typeCode CHAR(2)
 *        10 DIS-TRAN-CAT-CD     PIC 9(04).   — catCode  SMALLINT
 *     05  DIS-INT-RATE          PIC S9(04)V99. — disIntRate NUMERIC(6,2)
 *     05  FILLER                PIC X(28).     — Not mapped
 * </pre>
 *
 * <h3>Composite Key Structure (16 bytes from VSAM KEYS(16,0))</h3>
 * <ul>
 *   <li>{@code groupId} — 10-char account group ID (PIC X(10)):
 *       account-specific ({@code "A000000000"}), fallback ({@code "DEFAULT"}),
 *       zero-rate ({@code "ZEROAPR"})</li>
 *   <li>{@code typeCode} — 2-char transaction type code (PIC X(02))</li>
 *   <li>{@code catCode} — transaction category code (PIC 9(04) → SMALLINT)</li>
 * </ul>
 *
 * <h3>Seed Data (discgrp.txt — 51 records in 3 blocks)</h3>
 * <ul>
 *   <li>Rows 1–17: Account-specific group ({@code "A000000000"}) with rates
 *       15.00, 25.00, 0.00 across type codes 01–07</li>
 *   <li>Rows 18–34: DEFAULT fallback group with standard rates 15.00, 25.00,
 *       0.00 — note type 07/cat 1 rate is 0.00 (differs from specific 15.00)</li>
 *   <li>Rows 35–51: ZEROAPR promotional group — all rates 0.00</li>
 * </ul>
 *
 * <h3>BigDecimal Precision Rules (AAP §0.8.2)</h3>
 * <p>All interest rate comparisons use {@code compareTo()} — never {@code equals()},
 * which is scale-sensitive in {@link BigDecimal}. COBOL {@code PIC S9(04)V99} maps to
 * {@code NUMERIC(6,2)} with 4 integer digits and 2 decimal places.</p>
 *
 * <p>COBOL source reference: {@code app/jcl/DISCGRP.jcl}, {@code app/cpy/CVTRA02Y.cpy},
 * {@code app/data/ASCII/discgrp.txt}, and {@code app/cbl/CBACT04C.cbl}
 * from commit {@code 27d6c6f}.</p>
 *
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @see DisclosureGroupRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("DisclosureGroupRepository Integration Tests — DISCGRP VSAM KSDS")
public class DisclosureGroupRepositoryIT {

    // -----------------------------------------------------------------------
    // Testcontainers PostgreSQL 16 — managed lifecycle via @Container
    // Replaces VSAM DEFINE CLUSTER for DISCGRP.VSAM.KSDS
    // KEYS(16 0), RECORDSIZE(50 50), SHAREOPTIONS(2 3)
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
     * data with 51 disclosure group records) run against the real PostgreSQL
     * container and Hibernate validates entity mappings against the Flyway-created
     * schema.</p>
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
    private DisclosureGroupRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    // -----------------------------------------------------------------------
    // Test 1: findById with composite key (DEFAULT group)
    // Verifies keyed read — equivalent to COBOL READ DISCGRP KEY(16 bytes)
    // KSDS KEYS(16 0): groupId(10) + typeCode(2) + catCode(4) = 16 bytes
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById()} with a valid composite key returns the
     * expected DEFAULT disclosure group record seeded from {@code discgrp.txt}
     * via Flyway V3 migration.
     *
     * <p>Maps COBOL keyed read: {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD
     * KEY IS DIS-GROUP-KEY}. The composite key consists of
     * DIS-ACCT-GROUP-ID(10) + DIS-TRAN-TYPE-CD(2) + DIS-TRAN-CAT-CD(4).</p>
     *
     * <p>Seed data in V3__seed_data.sql uses trimmed group IDs (VARCHAR(10)),
     * so {@code "DEFAULT"} is used without trailing space padding.</p>
     *
     * <p>BigDecimal interest rate assertion uses {@code compareTo()} per AAP §0.8.2
     * — never {@code equals()} which is scale-sensitive.</p>
     */
    @Test
    @DisplayName("findById with composite key returns DEFAULT group record with correct interest rate")
    void testFindById_WithCompositeKey() {
        // Arrange — construct the 16-byte composite key for DEFAULT group,
        // type code '01' (Purchase), category code 1 (Regular Sales Draft)
        // Seed data: ('DEFAULT', '01', 1, 15.00) in V3__seed_data.sql
        DisclosureGroupId compositeKey = new DisclosureGroupId("DEFAULT", "01", (short) 1);

        // Act — equivalent to COBOL READ DISCGRP KEY(DEFAULT   |01|0001)
        Optional<DisclosureGroup> result = repository.findById(compositeKey);

        // Assert — verify presence and field values
        assertThat(result)
                .as("DEFAULT disclosure group for type '01', cat 1 "
                        + "should be present in seed data (discgrp.txt row 18)")
                .isPresent();

        DisclosureGroup group = result.get();

        // Verify composite key fields round-trip correctly
        assertThat(group.getId()).isNotNull();
        assertThat(group.getId().getGroupId())
                .as("Group ID should be 'DEFAULT' (trimmed VARCHAR(10))")
                .isEqualTo("DEFAULT");
        assertThat(group.getId().getTypeCode())
                .as("Type code should be '01' (CHAR(2))")
                .startsWith("01");
        assertThat(group.getId().getCatCode())
                .as("Category code should be 1 (PIC 9(04) → SMALLINT)")
                .isEqualTo((short) 1);

        // Verify interest rate — use compareTo() per AAP §0.8.2
        // DEFAULT/01/0001 rate = 15.00 in discgrp.txt (decoded from COBOL 00150{)
        assertThat(group.getDisIntRate())
                .as("Interest rate should be non-null BigDecimal")
                .isNotNull();
        assertThat(group.getDisIntRate().compareTo(new BigDecimal("15.00")))
                .as("DEFAULT group interest rate for type 01/cat 1 should be 15.00 "
                        + "(decoded from COBOL signed zoned decimal 00150{)")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Test 2: findById with account-specific group
    // Verifies per-account interest rate lookup (A000000000 group)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById()} returns the correct record for an
     * account-specific disclosure group ({@code "A000000000"}).
     *
     * <p>In the COBOL interest calculation (CBACT04C.cbl), each account has a
     * {@code group_id} field in the accounts table. The first lookup attempt
     * uses this specific group ID. Account-specific rates may differ from
     * DEFAULT group rates for the same type/category combination.</p>
     *
     * <p>Seed data: {@code ('A000000000', '01', 2, 25.00)} — cash advance rate
     * for account group A000000000 is 25.00%.</p>
     */
    @Test
    @DisplayName("findById returns account-specific group record with valid interest rate")
    void testFindById_SpecificAccountGroup() {
        // Arrange — composite key for account-specific group A000000000,
        // type '01' (Purchase), cat 2 (Regular Cash Advance)
        // Seed data: ('A000000000', '01', 2, 25.00)
        DisclosureGroupId specificKey =
                new DisclosureGroupId("A000000000", "01", (short) 2);

        // Act — equivalent to COBOL READ DISCGRP KEY(A000000000|01|0002)
        Optional<DisclosureGroup> result = repository.findById(specificKey);

        // Assert — verify presence
        assertThat(result)
                .as("Account-specific group A000000000 for type '01', cat 2 "
                        + "should be present in seed data (discgrp.txt row 2)")
                .isPresent();

        DisclosureGroup group = result.get();

        // Verify interest rate — use compareTo() per AAP §0.8.2
        assertThat(group.getDisIntRate())
                .as("Interest rate should be non-null BigDecimal")
                .isNotNull();
        assertThat(group.getDisIntRate().compareTo(new BigDecimal("25.00")))
                .as("Account group A000000000 cash advance rate should be 25.00%")
                .isEqualTo(0);

        // Verify the group ID from the composite key
        assertThat(group.getId().getGroupId())
                .as("Group ID should be 'A000000000' (account-specific)")
                .isEqualTo("A000000000");
    }

    // -----------------------------------------------------------------------
    // Test 3: findById with non-existent composite key
    // Verifies INVALID KEY / FILE STATUS '23' equivalent (RecordNotFound)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code findById()} with a non-existent composite key returns
     * an empty {@code Optional}, equivalent to COBOL FILE STATUS '23'
     * (record not found) on DISCGRP READ.
     *
     * <p>In the COBOL source, this triggers a {@code FILE STATUS = '23'} condition,
     * which signals the interest calculation processor to attempt the DEFAULT
     * group fallback lookup.</p>
     */
    @Test
    @DisplayName("findById with non-existent composite key returns empty Optional")
    void testFindById_NonExistent() {
        // Arrange — key that does not exist in seed data
        DisclosureGroupId nonExistentKey =
                new DisclosureGroupId("NOTEXIST", "99", (short) 9999);

        // Act — equivalent to COBOL READ DISCGRP with invalid key → STATUS '23'
        Optional<DisclosureGroup> result = repository.findById(nonExistentKey);

        // Assert — should be empty (no RecordNotFoundException in JPA, just empty Optional)
        assertThat(result)
                .as("Non-existent composite key (NOTEXIST/99/9999) should return empty")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 4: CRITICAL — DEFAULT fallback pattern via custom @Query method
    // This is the most important test in this class.
    // Source: CBACT04C.cbl interest calculation logic paragraphs
    //   1200-GET-INTEREST-RATE, 1200-A-GET-DEFAULT-INT-RATE
    // Pattern: specific group → not found → DEFAULT group fallback
    // -----------------------------------------------------------------------

    /**
     * <strong>CRITICAL TEST</strong> — Verifies the DEFAULT fallback pattern used by
     * the interest calculation batch processor ({@code CBACT04C.cbl}).
     *
     * <p>This test exercises the custom {@code findByGroupIdAndTypeCodeAndCatCode()}
     * {@code @Query} method that enables the DEFAULT group fallback pattern:</p>
     * <ol>
     *   <li>Look up a specific account group + type + category → should find a rate</li>
     *   <li>Look up a non-existent group + same type + category → should NOT find</li>
     *   <li>Then look up {@code "DEFAULT"} + same type + category → MUST find the
     *       fallback rate</li>
     * </ol>
     *
     * <p>The DEFAULT group provides universal fallback rates for any account that
     * does not have account-specific disclosure group entries. This pattern is the
     * single most important business logic for interest rate resolution.</p>
     */
    @Test
    @DisplayName("CRITICAL: findByGroupIdAndTypeCodeAndCatCode validates DEFAULT fallback pattern")
    void testFindByGroupIdAndTypeCodeAndCatCode_CRITICAL_DEFAULT_FALLBACK() {
        String typeCode = "01";
        short catCode = (short) 1;

        // Step 1: Look up specific account group → should find rate 15.00
        // Seed: ('A000000000', '01', 1, 15.00)
        Optional<DisclosureGroup> specificResult =
                repository.findByGroupIdAndTypeCodeAndCatCode("A000000000", typeCode, catCode);

        assertThat(specificResult)
                .as("Specific group A000000000 for type '01'/cat 1 should exist in seed data")
                .isPresent();
        assertThat(specificResult.get().getDisIntRate().compareTo(new BigDecimal("15.00")))
                .as("A000000000 type 01/cat 1 rate should be 15.00")
                .isEqualTo(0);

        // Step 2: Look up non-existent specific group → should NOT find
        // This simulates an account whose group ID has no specific disclosure entries
        Optional<DisclosureGroup> missingResult =
                repository.findByGroupIdAndTypeCodeAndCatCode("NOACCOUNT", typeCode, catCode);

        assertThat(missingResult)
                .as("Non-existent group 'NOACCOUNT' for type '01'/cat 1 should return empty "
                        + "(FILE STATUS '23' equivalent triggering DEFAULT fallback)")
                .isEmpty();

        // Step 3: CRITICAL — DEFAULT fallback lookup MUST succeed
        // Seed: ('DEFAULT', '01', 1, 15.00)
        Optional<DisclosureGroup> defaultResult =
                repository.findByGroupIdAndTypeCodeAndCatCode("DEFAULT", typeCode, catCode);

        assertThat(defaultResult)
                .as("DEFAULT fallback group for type '01'/cat 1 MUST exist — "
                        + "this is the universal fallback for interest rate resolution")
                .isPresent();
        assertThat(defaultResult.get().getDisIntRate().compareTo(new BigDecimal("15.00")))
                .as("DEFAULT fallback rate for type 01/cat 1 should be 15.00")
                .isEqualTo(0);

        // Verify that DEFAULT and specific rates can differ for some type/cat combos
        // Seed: A000000000/07/1 = 15.00 vs DEFAULT/07/1 = 0.00
        Optional<DisclosureGroup> specificType07 =
                repository.findByGroupIdAndTypeCodeAndCatCode("A000000000", "07", (short) 1);
        Optional<DisclosureGroup> defaultType07 =
                repository.findByGroupIdAndTypeCodeAndCatCode("DEFAULT", "07", (short) 1);

        assertThat(specificType07).isPresent();
        assertThat(defaultType07).isPresent();
        assertThat(specificType07.get().getDisIntRate().compareTo(new BigDecimal("15.00")))
                .as("A000000000 type 07/cat 1 rate should be 15.00")
                .isEqualTo(0);
        assertThat(defaultType07.get().getDisIntRate().compareTo(new BigDecimal("0.00")))
                .as("DEFAULT type 07/cat 1 rate should be 0.00 — differs from specific!")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Test 5: End-to-end DEFAULT fallback simulation
    // Simulates the exact CBACT04C.cbl interest rate resolution pattern:
    //   1. Try specific group → may or may not exist
    //   2. If not found → try DEFAULT group → MUST exist
    //   3. If DEFAULT also not found → rate = 0.00
    // -----------------------------------------------------------------------

    /**
     * Simulates the exact CBACT04C.cbl interest rate resolution algorithm end-to-end.
     *
     * <p>Given an account with a group ID that does NOT exist in the disclosure
     * group table (e.g., {@code "A000000001"}), the resolution logic must:</p>
     * <ol>
     *   <li>Attempt lookup with the specific group ID → empty Optional</li>
     *   <li>Fall back to {@code "DEFAULT"} group → MUST find a rate</li>
     *   <li>If {@code "DEFAULT"} also not found → use rate 0.00 as final fallback</li>
     * </ol>
     *
     * <p>This test also verifies that DEFAULT entries exist for all standard
     * type/category combinations present in the seed data.</p>
     */
    @Test
    @DisplayName("End-to-end DEFAULT fallback: specific miss → DEFAULT hit → rate resolved")
    void testDefaultFallbackPattern_EndToEnd() {
        // Simulate an account whose groupId is "A000000001" — no specific entries exist
        String specificGroupId = "A000000001";
        String typeCode = "01";
        short catCode = (short) 1;

        // Step 1: Try specific account group → should NOT exist in seed data
        Optional<DisclosureGroup> specificResult =
                repository.findByGroupIdAndTypeCodeAndCatCode(specificGroupId, typeCode, catCode);

        // Step 2: Fall back to DEFAULT group
        BigDecimal resolvedRate;
        if (specificResult.isPresent()) {
            resolvedRate = specificResult.get().getDisIntRate();
        } else {
            Optional<DisclosureGroup> defaultResult =
                    repository.findByGroupIdAndTypeCodeAndCatCode("DEFAULT", typeCode, catCode);
            if (defaultResult.isPresent()) {
                resolvedRate = defaultResult.get().getDisIntRate();
            } else {
                // Final fallback: rate = 0.00 if neither specific nor DEFAULT found
                resolvedRate = BigDecimal.ZERO;
            }
        }

        // Assert — at least one lookup must succeed and resolve a valid rate
        assertThat(resolvedRate)
                .as("Resolved interest rate must be non-null after fallback chain")
                .isNotNull();
        assertThat(resolvedRate.compareTo(new BigDecimal("15.00")))
                .as("DEFAULT fallback rate for type 01/cat 1 should resolve to 15.00")
                .isEqualTo(0);

        // Verify DEFAULT entries exist for all standard type/category combos in seed
        // DEFAULT group has entries for types 01-07 with various category codes
        String[] defaultTypeCodes = {"01", "02", "03", "04", "05", "06", "07"};
        for (String tc : defaultTypeCodes) {
            Optional<DisclosureGroup> defaultEntry =
                    repository.findByGroupIdAndTypeCodeAndCatCode("DEFAULT", tc, (short) 1);
            assertThat(defaultEntry)
                    .as("DEFAULT group should have entry for type '%s'/cat 1", tc)
                    .isPresent();
        }

        // Verify total seed data count — 51 records (3 blocks × 17 entries)
        // Using findAll() and count() to exercise repository members_accessed
        long totalCount = repository.count();
        assertThat(totalCount)
                .as("V3 seed data should contain 51 disclosure group records "
                        + "(17 A000000000 + 17 DEFAULT + 17 ZEROAPR)")
                .isGreaterThanOrEqualTo(51L);

        // Verify via findAll() with List.size() and List.stream()
        List<DisclosureGroup> allGroups = repository.findAll();
        assertThat(allGroups.size())
                .as("findAll() should return at least 51 seeded records")
                .isGreaterThanOrEqualTo(51);

        // Verify all three group blocks exist in the full result set
        long defaultCount = allGroups.stream()
                .filter(g -> "DEFAULT".equals(g.getId().getGroupId()))
                .count();
        assertThat(defaultCount)
                .as("Should have 17 DEFAULT group entries in seed data")
                .isEqualTo(17L);

        long zeroAprCount = allGroups.stream()
                .filter(g -> "ZEROAPR".equals(g.getId().getGroupId()))
                .count();
        assertThat(zeroAprCount)
                .as("Should have 17 ZEROAPR group entries in seed data")
                .isEqualTo(17L);
    }

    // -----------------------------------------------------------------------
    // Test 6: ZEROAPR promotional group — all rates 0.00
    // Verifies zero-interest promotional rate group from discgrp.txt rows 35-51
    // -----------------------------------------------------------------------

    /**
     * Verifies that the ZEROAPR promotional group returns a zero interest rate.
     *
     * <p>The ZEROAPR group exists in the seed data (discgrp.txt rows 35–51) with
     * all interest rates set to 0.00%. This represents a promotional zero-APR
     * period for qualifying accounts.</p>
     *
     * <p>Uses {@code compareTo(BigDecimal.ZERO)} per AAP §0.8.2 — never
     * {@code equals()} which is scale-sensitive.</p>
     */
    @Test
    @DisplayName("ZEROAPR group returns zero interest rate (0.00)")
    void testZeroAprGroup() {
        // Arrange — ZEROAPR group, type '01', cat 1
        // Seed: ('ZEROAPR', '01', 1, 0.00)
        DisclosureGroupId zeroAprKey = new DisclosureGroupId("ZEROAPR", "01", (short) 1);

        // Act — look up the ZEROAPR promotional group entry
        Optional<DisclosureGroup> result = repository.findById(zeroAprKey);

        // Assert — verify presence and zero interest rate
        assertThat(result)
                .as("ZEROAPR group for type '01'/cat 1 should be present "
                        + "in seed data (discgrp.txt row 35)")
                .isPresent();

        DisclosureGroup group = result.get();

        // CRITICAL: Use compareTo() for BigDecimal zero comparison per AAP §0.8.2
        assertThat(group.getDisIntRate().compareTo(BigDecimal.ZERO))
                .as("ZEROAPR group interest rate must be exactly 0.00 "
                        + "(zero-APR promotional rate)")
                .isEqualTo(0);

        // Also verify via the custom @Query method
        Optional<DisclosureGroup> queryResult =
                repository.findByGroupIdAndTypeCodeAndCatCode("ZEROAPR", "01", (short) 1);
        assertThat(queryResult).isPresent();
        assertThat(queryResult.get().getDisIntRate().compareTo(BigDecimal.ZERO))
                .as("ZEROAPR rate via @Query method should also be 0.00")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Test 7: BigDecimal interest rate precision round-trip
    // CRITICAL — AAP §0.8.2: PIC S9(04)V99 → NUMERIC(6,2)
    // Verifies save → flush → clear → findById preserves exact precision
    // -----------------------------------------------------------------------

    /**
     * <strong>CRITICAL</strong> — Verifies that BigDecimal interest rate precision is
     * preserved through the full JPA persistence lifecycle: save → flush → clear
     * (evict from L1 cache) → findById (fresh database read).
     *
     * <p>COBOL {@code PIC S9(04)V99} maps to PostgreSQL {@code NUMERIC(6,2)} via
     * JPA {@code @Column(precision = 6, scale = 2)}. This test creates a disclosure
     * group with rate {@code 15.75}, persists it, evicts it from Hibernate's
     * first-level cache, and verifies the rate round-trips without precision loss.</p>
     *
     * <p>All BigDecimal comparisons use {@code compareTo()} per AAP §0.8.2 — never
     * {@code equals()} which is scale-sensitive (e.g., {@code new BigDecimal("15.75")}
     * vs {@code new BigDecimal("15.750")} would return false with equals()).</p>
     */
    @Test
    @DisplayName("BigDecimal interest rate precision preserved through save/flush/clear/findById")
    void testBigDecimalInterestRatePrecision() {
        // Arrange — create a disclosure group using no-arg constructor + setters
        // to exercise DisclosureGroup() no-arg, setId(), setDisIntRate()
        // Use a unique key not in seed data to avoid conflicts
        DisclosureGroupId testKey = new DisclosureGroupId("TESTPREC", "99", (short) 9999);
        BigDecimal expectedRate = new BigDecimal("15.75");

        // Exercise no-arg constructor + setters (members_accessed compliance)
        DisclosureGroup newGroup = new DisclosureGroup();
        newGroup.setId(testKey);
        newGroup.setDisIntRate(expectedRate);

        // Act — save via repository.save(), flush to DB, clear L1 cache, then re-read
        // This exercises repository.save() as required by members_accessed
        repository.save(newGroup);
        entityManager.flush();
        entityManager.clear(); // Evict from Hibernate first-level cache

        // Re-read from database (not from cache)
        Optional<DisclosureGroup> result = repository.findById(testKey);

        // Assert — verify the rate survived the full persistence round-trip
        assertThat(result)
                .as("Persisted disclosure group should be retrievable by composite key")
                .isPresent();

        DisclosureGroup retrieved = result.get();

        // CRITICAL: Use compareTo() for BigDecimal comparison per AAP §0.8.2
        assertThat(retrieved.getDisIntRate().compareTo(expectedRate))
                .as("Interest rate 15.75 must survive save/flush/clear/findById "
                        + "round-trip with zero precision loss (NUMERIC(6,2))")
                .isEqualTo(0);

        // Also verify the composite key fields survived the round-trip
        assertThat(retrieved.getId().getGroupId()).isEqualTo("TESTPREC");
        assertThat(retrieved.getId().getTypeCode()).startsWith("99");
        assertThat(retrieved.getId().getCatCode()).isEqualTo((short) 9999);

        // Additionally test with the all-args constructor for coverage
        DisclosureGroupId testKey2 = new DisclosureGroupId("TESTPRC2", "98", (short) 9998);
        DisclosureGroup constructorGroup = new DisclosureGroup(testKey2, new BigDecimal("99.99"));
        repository.save(constructorGroup);
        entityManager.flush();
        entityManager.clear();

        Optional<DisclosureGroup> result2 = repository.findById(testKey2);
        assertThat(result2).isPresent();
        assertThat(result2.get().getDisIntRate().compareTo(new BigDecimal("99.99")))
                .as("Max precision rate 99.99 must survive round-trip (NUMERIC(6,2))")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Test 8: @EmbeddedId composite key equals/hashCode correctness
    // Verifies that DisclosureGroupId implements equals() and hashCode()
    // correctly — critical for JPA entity identity and the DEFAULT fallback
    // lookup logic in InterestCalculationProcessor
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link DisclosureGroupId} correctly implements
     * {@code equals()} and {@code hashCode()} for composite key comparison.
     *
     * <p>Correct equals/hashCode is essential for:</p>
     * <ul>
     *   <li>JPA entity identity resolution in Hibernate's first-level cache</li>
     *   <li>Correct behavior in {@code Set} and {@code Map} collections</li>
     *   <li>The DEFAULT fallback lookup pattern where composite key equality
     *       determines whether a specific rate was found or the DEFAULT
     *       fallback is needed</li>
     * </ul>
     */
    @Test
    @DisplayName("Composite key equals/hashCode: identical values → equal, different values → not equal")
    void testCompositeKeyEquality() {
        // Arrange — create two DisclosureGroupId instances with identical values
        DisclosureGroupId key1 = new DisclosureGroupId("DEFAULT", "01", (short) 1);
        DisclosureGroupId key2 = new DisclosureGroupId("DEFAULT", "01", (short) 1);

        // Assert — equals() should return true for identical values
        assertThat(key1.equals(key2))
                .as("Two DisclosureGroupId with same groupId/typeCode/catCode must be equal")
                .isTrue();

        // Assert — hashCode() should match for equal objects
        assertThat(key1.hashCode())
                .as("Equal DisclosureGroupId instances must have the same hashCode")
                .isEqualTo(key2.hashCode());

        // Verify getters return expected values
        assertThat(key1.getGroupId()).isEqualTo("DEFAULT");
        assertThat(key1.getTypeCode()).isEqualTo("01");
        assertThat(key1.getCatCode()).isEqualTo((short) 1);

        // Verify inequality — different groupId
        DisclosureGroupId differentGroupKey = new DisclosureGroupId("ZEROAPR", "01", (short) 1);
        assertThat(key1.equals(differentGroupKey))
                .as("DisclosureGroupId with different groupId should NOT be equal")
                .isFalse();

        // Verify inequality — different typeCode
        DisclosureGroupId differentTypeKey = new DisclosureGroupId("DEFAULT", "02", (short) 1);
        assertThat(key1.equals(differentTypeKey))
                .as("DisclosureGroupId with different typeCode should NOT be equal")
                .isFalse();

        // Verify inequality — different catCode
        DisclosureGroupId differentCatKey = new DisclosureGroupId("DEFAULT", "01", (short) 2);
        assertThat(key1.equals(differentCatKey))
                .as("DisclosureGroupId with different catCode should NOT be equal")
                .isFalse();

        // Verify reflexive, symmetric, and null-safe behavior
        assertThat(key1.equals(key1))
                .as("equals() must be reflexive")
                .isTrue();
        assertThat(key1.equals(null))
                .as("equals() with null must return false")
                .isFalse();

        // Verify no-arg constructor creates usable instance
        DisclosureGroupId emptyKey = new DisclosureGroupId();
        assertThat(emptyKey.getGroupId())
                .as("No-arg constructor should initialize groupId to null")
                .isNull();
        emptyKey.setGroupId("TEST");
        emptyKey.setTypeCode("01");
        emptyKey.setCatCode((short) 1);
        assertThat(emptyKey.getGroupId()).isEqualTo("TEST");
    }
}
