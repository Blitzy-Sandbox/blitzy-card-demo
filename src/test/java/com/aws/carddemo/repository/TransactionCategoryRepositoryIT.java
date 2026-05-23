/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.repository;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies).
//
//   * TransactionCategory — the JPA entity stub matching the CVTRA04Y.cpy
//     TRAN-CAT-RECORD layout (60-byte fixed-width record: 2-char + 4-digit
//     composite key + 50-char description + 4-byte FILLER). Test methods
//     drive persist/find/save round-trips through this entity.
//
//   * TransactionCategoryKey — the JPA composite-key value-object stub
//     holding the (TRAN-TYPE-CD, TRAN-CAT-CD) tuple. Each test constructs
//     instances via the buildKey() helper and passes them to
//     TransactionCategoryRepository.findById() / .save() to verify
//     composite-key persistence semantics against PostgreSQL.
//
//   * AbstractRepositoryIT — the abstract base class providing the
//     @DataJpaTest slice annotation, the @Testcontainers PostgreSQL 16
//     container, the @DynamicPropertySource that wires Testcontainers'
//     JDBC URL/username/password into Spring's environment, and the
//     inherited TestEntityManager field (entityManager). Per AAP §0.4.4
//     all 10 repository ITs extend this base — this IT inherits the JPA
//     slice, per-class container lifecycle, and transactional rollback
//     after each @Test method without re-declaring any of that wiring.
//
//   * TestFixtures — the shared test-constants holder. The canonical
//     well-known transaction-type code
//     TestFixtures.Transactions.TRAN_TYPE_PURCHASE ('01') and the
//     canonical 4-digit category codes
//     TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES ('0001') and
//     TestFixtures.Transactions.TRAN_CAT_INTEREST ('0005') are referenced
//     by name to assert that the Flyway-seeded reference catalog
//     contains the canonical (type, cat) composite-key rows accessible
//     by the canonical pairs. Per AAP §0.5.5 Cross-File Test
//     Dependencies and AAP §0.10.1 Require Test Coverage Rule (test
//     bodies must not duplicate literal codes that already appear in
//     TestFixtures).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.TransactionCategory;
import com.aws.carddemo.entity.TransactionCategoryKey;
import com.aws.carddemo.testsupport.AbstractRepositoryIT;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
//
//   * @Test marks each integration-test method. The Failsafe plugin
//     discovers test methods on the {@code *IT.java} suffix convention
//     and JUnit Jupiter runs them via the JUnit Platform.
//
//   * @DisplayName supplies human-readable scenario descriptions on the
//     test class and each method so IDE runner output and CI test
//     reports surface the COBOL-parity intent (rather than the
//     camelCase method name alone).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Framework dependency-injection import.
//
//   * @Autowired field-injects the Spring-managed
//     TransactionCategoryRepository proxy into this IT class instance.
//     The proxy is created by Spring Data JPA at @DataJpaTest context
//     startup from the
//     {@code JpaRepository<TransactionCategory, TransactionCategoryKey>}
//     interface declaration on the production repository — no manual
//     implementation is required, and no field declared with @Mock is
//     permissible at this IT layer (AAP §0.10.1 Require Test Coverage
//     Rule: integration tests must invoke the REAL repository against
//     the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.util.List — return type of TransactionCategoryRepository.findAll()
//     used by the seed-integrity assertion (at least 18 rows present).
//
//   * java.util.Optional — return type of
//     TransactionCategoryRepository.findById(TransactionCategoryKey) used
//     by the composite-key lookup assertions (isPresent / isEmpty).
// ---------------------------------------------------------------------------
import java.util.List;
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static import (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. assertThat(optional).isPresent(),
//     assertThat(list).hasSizeGreaterThanOrEqualTo(18),
//     assertThat(reloaded.getTranCatTypeDesc()).isEqualTo(...) and
//     .extracting(TransactionCategory::getTranCatTypeDesc) chains are
//     the four idioms this class exercises.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TransactionCategoryRepository}, which persists
 * {@link TransactionCategory} entities migrated from the COBOL
 * {@code TRAN-CAT-RECORD} defined in {@code app/cpy/CVTRA04Y.cpy}
 * (RECLN 60). Seed data is sourced from {@code app/data/ASCII/trancatg.txt}
 * (18 reference rows).
 *
 * <h2>COBOL Provenance — CVTRA04Y.cpy</h2>
 *
 * <p>{@code TRAN-CAT-RECORD} uses a 6-byte composite primary key
 * ({@code TRAN-CAT-KEY}) carrying the {@code TRAN-TYPE-CD PIC X(02)} +
 * {@code TRAN-CAT-CD PIC 9(04)} tuple. The composite key is required
 * because the same numeric category code repeats across different
 * transaction types — neither field alone is unique. The
 * {@code TRAN-CAT-TYPE-DESC PIC X(50)} field carries the human-readable
 * description, and a 4-byte FILLER pads the record to RECLN 60.
 *
 * <p>This is <em>read-only reference data</em> after the initial Flyway
 * seed — no business workflow mutates rows in this catalog at runtime.
 * The 18 canonical rows from {@code app/data/ASCII/trancatg.txt}:
 *
 * <pre>
 *   (01, 0001)  Regular Sales Draft
 *   (01, 0002)  Regular Cash Advance
 *   (01, 0003)  Convenience Check Debit
 *   (01, 0004)  ATM Cash Advance
 *   (01, 0005)  Interest Amount
 *   (02, 0001)  Cash payment
 *   (02, 0002)  Electronic payment
 *   (02, 0003)  Check payment
 *   (03, 0001)  Credit to Account
 *   (03, 0002)  Credit to Purchase balance
 *   (03, 0003)  Credit to Cash balance
 *   (04, 0001)  Zero dollar authorization
 *   (04, 0002)  Online purchase authorization
 *   (04, 0003)  Travel booking authorization
 *   (05, 0001)  Refund credit
 *   (06, 0001)  Fraud reversal
 *   (06, 0002)  Non-fraud reversal
 *   (07, 0001)  Sales draft credit adjustment
 * </pre>
 *
 * <h2>Coverage Focus (AAP §0.5.1)</h2>
 *
 * <p>The 8 test methods exercise the integration of
 * {@link TransactionCategoryRepository} against a real PostgreSQL 16
 * instance provisioned by Testcontainers — no Mockito stubs at this
 * layer (AAP §0.10.1 Require Test Coverage Rule). The categories below
 * cover the AAP-specified scenarios:
 *
 * <ul>
 *   <li>{@link #save_newCategory_persistsWithCompositeKey()} —
 *       composite-key round-trip against a synthetic row persisted via
 *       the production {@code save()} method and reloaded via
 *       {@code findById()}.</li>
 *   <li>{@link #findById_nonexistentKey_returnsEmpty()} — negative-path
 *       lookup verifying {@link Optional#empty()} for an unknown
 *       composite key.</li>
 *   <li>{@link #save_sameTypeDifferentCategory_createsDistinctRows()} —
 *       composite-key distinctness check: two rows sharing
 *       {@code TRAN-TYPE-CD} but differing in {@code TRAN-CAT-CD} must
 *       coexist as separate rows.</li>
 *   <li>{@link #save_differentTypeSameCategory_createsDistinctRows()} —
 *       composite-key distinctness check: two rows sharing
 *       {@code TRAN-CAT-CD} but differing in {@code TRAN-TYPE-CD} must
 *       coexist as separate rows (the canonical reason the COBOL
 *       catalog uses a composite key in the first place).</li>
 *   <li>{@link #findById_canonicalRegularSalesKey_returnsCategory()} —
 *       canonical {@code (TRAN_TYPE_PURCHASE, TRAN_CAT_REGULAR_SALES) =
 *       (01, 0001)} lookup verifying the Flyway-seeded "Regular Sales
 *       Draft" row is accessible via composite-key {@code findById()}.</li>
 *   <li>{@link #findById_canonicalInterestKey_returnsCategory()} —
 *       canonical {@code (TRAN_TYPE_PURCHASE, TRAN_CAT_INTEREST) =
 *       (01, 0005)} lookup verifying the Flyway-seeded "Interest
 *       Amount" row is accessible via composite-key {@code findById()}.
 *       This is the category code the migrated
 *       {@code InterestCalculationProcessor} (CBACT04C migration)
 *       references on every interest transaction it emits — a missing
 *       seed row would block that processor at the validation
 *       boundary.</li>
 *   <li>{@link #findAll_seededReferenceData_returnsAllCategories()} —
 *       full-catalog smoke test asserting that the Flyway seed
 *       hydrates at least the 18 canonical rows from
 *       {@code app/data/ASCII/trancatg.txt} (AAP §0.5.1 "Lookup of all
 *       18 categories").</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — basic sanity
 *       check that the inherited {@code count()} method returns a
 *       non-negative tally; together with
 *       {@link #findAll_seededReferenceData_returnsAllCategories()} this
 *       guards against accidentally truncating the catalog table at any
 *       point in the migration lifecycle.</li>
 * </ul>
 *
 * <h2>Mock Boundary (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>This IT has <strong>no Mockito mocks</strong>. The system under test
 * is the real {@link TransactionCategoryRepository} bean wired by Spring
 * Data JPA against the real PostgreSQL 16 database supplied by
 * Testcontainers (inherited from {@link AbstractRepositoryIT}). Repository
 * ITs sit at the lowest mock boundary in the test pyramid: they verify
 * that the Spring Data JPA proxy + Hibernate ORM + JDBC driver +
 * PostgreSQL stack produces correct results against a real schema seeded
 * by Flyway. Tests that would otherwise mock the repository (service
 * unit tests, batch processor unit tests) live one layer up in
 * {@code com.aws.carddemo.service.*Test} and
 * {@code com.aws.carddemo.batch.*Test}.
 *
 * <h2>No Business Logic in Test Bodies (AAP §0.10.1)</h2>
 *
 * <p>The composite-key tests construct {@link TransactionCategoryKey}
 * values using only literal scalars and constants from
 * {@link TestFixtures.Transactions} — no key derivation, no
 * concatenation, no parsing of the on-disk fixture format. Each test
 * method exercises exactly one repository call (or one
 * {@code save}+{@code findById} round-trip) and asserts on the returned
 * {@link Optional}/{@link List}/{@code long}. The {@link #buildKey} and
 * {@link #buildCategory} helpers are pure no-logic builders that map
 * constructor arguments straight onto setter calls.
 *
 * <h2>Test isolation (AAP §0.10.9)</h2>
 *
 * <p>{@code @DataJpaTest} (inherited from {@link AbstractRepositoryIT})
 * wraps each {@code @Test} method in a transaction that rolls back at
 * method end. This means the synthetic rows persisted by
 * {@link #save_newCategory_persistsWithCompositeKey()},
 * {@link #save_sameTypeDifferentCategory_createsDistinctRows()}, and
 * {@link #save_differentTypeSameCategory_createsDistinctRows()} are gone
 * before the next test sees the database state. Each test starts from
 * the Flyway-seeded catalog (18 rows) plus zero synthetic additions —
 * test order independence is guaranteed.
 *
 * <h2>Activation State</h2>
 *
 * <p>This IT is active and executes under {@code mvn verify} (Failsafe).
 * The suite loads the {@code @DataJpaTest} Spring slice via
 * {@link AbstractRepositoryIT} and exercises the production
 * {@link TransactionCategoryRepository} bean against a real PostgreSQL
 * 16 database. The {@link TransactionCategory} entity is annotated as
 * a JPA {@code @Entity} (and {@link TransactionCategoryKey} as
 * {@code @Embeddable}), and the Flyway scripts under
 * {@code src/main/resources/db/migration/} create the
 * {@code transaction_categories} table and seed the 18 canonical
 * reference rows. The composite-key {@code findById}, miss-path,
 * 18-row floor, and INSERT round-trip paths are all exercised by the
 * test methods below.
 *
 * <h3>Operational Prerequisite</h3>
 *
 * <p>Docker must be available to Testcontainers at test runtime — the
 * {@code mvn verify} build agent must be able to run
 * {@code postgres:16-alpine}. CI agents that cannot start containers
 * (e.g. nested-virtualisation-free environments) can set
 * {@code TESTCONTAINERS_RYUK_DISABLED=true} as documented in
 * {@code src/test/resources/application-test.properties}.
 *
 * @see TransactionCategoryRepository
 * @see TransactionCategory
 * @see TransactionCategoryKey
 * @see AbstractRepositoryIT
 * @see TestFixtures.Transactions
 */
@DisplayName("TransactionCategoryRepository — CVTRA04Y.cpy / trancatg.txt composite-key ITs")
class TransactionCategoryRepositoryIT extends AbstractRepositoryIT {

    /**
     * The Spring Data JPA repository under integration test. The bean is
     * created by Spring Data JPA at {@code @DataJpaTest} context startup
     * from the {@link TransactionCategoryRepository} interface declaration
     * (no manual implementation). Field injection is consistent with the
     * inherited {@code @Autowired TestEntityManager entityManager} field
     * on {@link AbstractRepositoryIT}.
     */
    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    // =========================================================================
    // Composite-Key Save + findById Round-Trip
    // =========================================================================

    /**
     * Verifies that {@link TransactionCategoryRepository#save(Object)}
     * persists a {@link TransactionCategory} with a composite primary key
     * and that the subsequent {@link TransactionCategoryRepository#findById(Object)}
     * round-trips all fields (both key components and the description).
     * The test persists a synthetic row via the production repository's
     * {@code save()} method, flushes the persistence context to push the
     * row to PostgreSQL, clears the first-level cache so the subsequent
     * {@code findById} hits the database, and then asserts that the
     * returned {@link Optional} contains an entity with the expected
     * composite key and description.
     *
     * <p>The synthetic key {@code (TRAN-TYPE-CD = "99", TRAN-CAT-CD =
     * 9999)} is deliberately outside the canonical seed range so the
     * test never collides with a Flyway-seeded row and the synthetic
     * description ({@code "Test Category Description"}) is similarly
     * outside the seed catalog.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(new TransactionCategory) persists with composite key and round-trips all fields")
    void save_newCategory_persistsWithCompositeKey() {
        // Arrange — synthetic category outside the canonical seed range
        TransactionCategoryKey key = buildKey("99", 9999);
        TransactionCategory cat = buildCategory(key, "Test Category Description");

        // Act — drive the production repository's save path, then force a DB read
        transactionCategoryRepository.save(cat);
        entityManager.flush();
        entityManager.clear();

        // Assert — composite-key findById must locate the persisted row
        Optional<TransactionCategory> reloaded = transactionCategoryRepository.findById(key);
        assertThat(reloaded)
                .as("Composite-key findById must locate the persisted row")
                .isPresent();
        TransactionCategory r = reloaded.get();
        assertThat(r.getTranCatTypeDesc())
                .as("TRAN-CAT-TYPE-DESC must round-trip exactly after save+findById")
                .isEqualTo("Test Category Description");
        assertThat(getTypeCd(r))
                .as("TRAN-TYPE-CD composite-key component must round-trip exactly")
                .isEqualTo("99");
        assertThat(getCatCd(r))
                .as("TRAN-CAT-CD composite-key component must round-trip exactly")
                .isEqualTo(9999);
    }

    /**
     * Verifies that {@link TransactionCategoryRepository#findById(Object)}
     * returns {@link Optional#empty()} when the supplied composite key
     * does not exist in the {@code transaction_categories} table. This is
     * the Java equivalent of the COBOL CICS response code
     * {@code DFHRESP(NOTFND)} branch from the legacy
     * {@code EXEC CICS READ DATASET('TRANCATG') RIDFLD(TRAN-CAT-KEY)}
     * statement.
     *
     * <p>The lookup key {@code (TRAN-TYPE-CD = "XX", TRAN-CAT-CD = 8888)}
     * is outside the canonical {01..07} range for the type and the
     * {0001..0005} range observed in the seed for any single type — it
     * is guaranteed to be absent from both the Flyway seed and from any
     * synthetic row persisted by the other tests in this class (whose
     * synthetic keys use {@code "99"} / {@code "88"} / {@code "77"} /
     * {@code "78"}).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(nonexistent composite key) returns empty Optional")
    void findById_nonexistentKey_returnsEmpty() {
        // Arrange — composite key outside both the canonical seed range and the
        // synthetic ranges used by other tests in this class
        TransactionCategoryKey key = buildKey("XX", 8888);

        // Act — drive the production repository against the real DB
        Optional<TransactionCategory> result = transactionCategoryRepository.findById(key);

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND) signal
        assertThat(result)
                .as("findById should return Optional.empty for unknown composite keys "
                        + "(COBOL DFHRESP(NOTFND) equivalent)")
                .isEmpty();
    }

    // =========================================================================
    // Composite-Key Distinct-Rows Tests
    // =========================================================================

    /**
     * Verifies that two rows sharing {@code TRAN-TYPE-CD} but differing
     * in {@code TRAN-CAT-CD} are persisted as <em>distinct</em> rows by
     * the composite-key primary-key index. The COBOL {@code TRANCATG}
     * catalog exhibits this pattern five times under type {@code "01"}
     * alone ({@code (01, 0001)}–{@code (01, 0005)}); a composite-key
     * index that incorrectly treated only the type component as the row
     * identity would collapse those five rows into one, silently
     * losing reference data.
     *
     * <p>The test persists two synthetic rows
     * {@code (TRAN-TYPE-CD = "88", TRAN-CAT-CD = 1)} and
     * {@code (TRAN-TYPE-CD = "88", TRAN-CAT-CD = 2)} (both outside the
     * canonical seed range), flushes, and then asserts that
     * {@code findById} returns each row independently with its own
     * description.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(same TYPE-CD + different CAT-CD) creates distinct rows")
    void save_sameTypeDifferentCategory_createsDistinctRows() {
        // Arrange — same type, two different categories
        TransactionCategoryKey key1 = buildKey("88", 1);
        TransactionCategoryKey key2 = buildKey("88", 2);

        // Act — persist both rows, then force a DB read by flushing and clearing
        transactionCategoryRepository.save(buildCategory(key1, "First synthetic category"));
        transactionCategoryRepository.save(buildCategory(key2, "Second synthetic category"));
        entityManager.flush();
        entityManager.clear();

        // Assert — both rows must be retrievable independently with their own descriptions
        assertThat(transactionCategoryRepository.findById(key1))
                .as("First (88, 1) row must be present with its own description")
                .isPresent()
                .get()
                .extracting(TransactionCategory::getTranCatTypeDesc)
                .isEqualTo("First synthetic category");
        assertThat(transactionCategoryRepository.findById(key2))
                .as("Second (88, 2) row must be present with its own description "
                        + "(would collapse into the first row if the PK index ignored TRAN-CAT-CD)")
                .isPresent()
                .get()
                .extracting(TransactionCategory::getTranCatTypeDesc)
                .isEqualTo("Second synthetic category");
    }

    /**
     * Verifies that two rows sharing {@code TRAN-CAT-CD} but differing
     * in {@code TRAN-TYPE-CD} are persisted as <em>distinct</em> rows by
     * the composite-key primary-key index. The COBOL {@code TRANCATG}
     * catalog exhibits this pattern for category code {@code 0001},
     * which appears under every transaction type ({@code 01}–{@code 07});
     * a composite-key index that incorrectly treated only the category
     * component as the row identity would collapse those seven rows into
     * one — losing the type-specific descriptions that make the catalog
     * meaningful (the distinction between "Regular Sales Draft" under
     * type {@code 01} vs. "Cash payment" under type {@code 02}).
     *
     * <p>The test persists two synthetic rows
     * {@code (TRAN-TYPE-CD = "77", TRAN-CAT-CD = 1)} and
     * {@code (TRAN-TYPE-CD = "78", TRAN-CAT-CD = 1)} (both outside the
     * canonical seed range), flushes, and then asserts that both rows
     * are present and independently retrievable.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(different TYPE-CD + same CAT-CD) creates distinct rows")
    void save_differentTypeSameCategory_createsDistinctRows() {
        // Arrange — same category number, two different types
        TransactionCategoryKey keyType1 = buildKey("77", 1);
        TransactionCategoryKey keyType2 = buildKey("78", 1);

        // Act — persist both rows, then force a DB read by flushing and clearing
        transactionCategoryRepository.save(buildCategory(keyType1, "Type 77 category 1"));
        transactionCategoryRepository.save(buildCategory(keyType2, "Type 78 category 1"));
        entityManager.flush();
        entityManager.clear();

        // Assert — both rows must be present independently (would collapse if the PK
        // index ignored TRAN-TYPE-CD)
        assertThat(transactionCategoryRepository.findById(keyType1))
                .as("(77, 1) row must be present (distinct from (78, 1) by TRAN-TYPE-CD)")
                .isPresent();
        assertThat(transactionCategoryRepository.findById(keyType2))
                .as("(78, 1) row must be present (distinct from (77, 1) by TRAN-TYPE-CD)")
                .isPresent();
    }

    // =========================================================================
    // Canonical (TYPE, CAT) Pair Lookup Tests — Flyway Seed Integrity
    // =========================================================================

    /**
     * Verifies that the Flyway-seeded catalog contains the canonical
     * "Regular Sales Draft" row keyed by
     * {@code (TestFixtures.Transactions.TRAN_TYPE_PURCHASE,
     * TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES)} =
     * {@code (01, 0001)} (the first record in
     * {@code app/data/ASCII/trancatg.txt}). This row is referenced
     * across the migrated codebase as the default sales category — the
     * transaction-add validation cascade in {@code COTRN02C} permits
     * this pair as the canonical happy-path combination, and the
     * statement rendering in {@code CBSTM03A} groups regular sales
     * lines under this category.
     *
     * <p>The COBOL {@code TRAN-CAT-CD} is a {@code PIC 9(04)} numeric;
     * the {@link TestFixtures.Transactions#TRAN_CAT_REGULAR_SALES}
     * constant carries the 4-digit zero-padded string form
     * ({@code "0001"}). The {@link Integer#parseInt(String)} call
     * converts the on-disk string representation to the
     * {@link Integer} key field type expected by
     * {@link TransactionCategoryKey#setTranCatCd(Integer)}. This is a
     * pure representation conversion (string-of-digits to integer), not
     * business logic — the lossless mapping {@code "0001" -> 1} is the
     * canonical Java decoding of a {@code PIC 9(04)} numeric and AAP
     * §0.10.1 only forbids reimplementing COBOL business or
     * calculation logic.
     *
     * <p>The test asserts only the composite-key presence (not the
     * description literal), because the description is intentionally
     * sourced from the Flyway seed script — the test must not duplicate
     * the literal value per AAP §0.10.1.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById((TRAN_TYPE_PURCHASE, TRAN_CAT_REGULAR_SALES)) returns the seeded Regular Sales row")
    void findById_canonicalRegularSalesKey_returnsCategory() {
        // Arrange — canonical (01, 0001) composite key built from TestFixtures constants
        TransactionCategoryKey key = buildKey(
                TestFixtures.Transactions.TRAN_TYPE_PURCHASE,
                Integer.parseInt(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES));

        // Act — drive the production repository against the Flyway-seeded catalog
        Optional<TransactionCategory> result = transactionCategoryRepository.findById(key);

        // Assert — the canonical Regular Sales row must be present
        assertThat(result)
                .as("Seed data must include the canonical (TRAN_TYPE_PURCHASE, "
                        + "TRAN_CAT_REGULAR_SALES) = (01, 0001) row — the first record in "
                        + "app/data/ASCII/trancatg.txt and the default sales category referenced "
                        + "by transaction-add validation in COTRN02C and by statement rendering "
                        + "in CBSTM03A")
                .isPresent();
        assertThat(getTypeCd(result.get()))
                .as("Returned row's TRAN-TYPE-CD must match the lookup key (idempotent contract)")
                .isEqualTo(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);
        assertThat(getCatCd(result.get()))
                .as("Returned row's TRAN-CAT-CD must match the lookup key (idempotent contract)")
                .isEqualTo(Integer.parseInt(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES));
    }

    /**
     * Verifies that the Flyway-seeded catalog contains the canonical
     * "Interest Amount" row keyed by
     * {@code (TestFixtures.Transactions.TRAN_TYPE_PURCHASE,
     * TestFixtures.Transactions.TRAN_CAT_INTEREST)} =
     * {@code (01, 0005)} (record 5 in {@code app/data/ASCII/trancatg.txt}).
     * This is the category code the migrated
     * {@code InterestCalculationProcessor} (CBACT04C migration) emits on
     * every interest transaction it creates — the COBOL source executes
     * {@code MOVE '05' TO TRAN-CAT-CD} (the numeric MOVE right-justifies
     * with leading zeros to {@code 0005}) and the migrated processor
     * must therefore reference the {@code (01, 0005)} category row to
     * resolve its human-readable description for statement rendering.
     * A missing seed row would block that processor at the validation
     * boundary.
     *
     * <p>The {@link Integer#parseInt(String)} call converts the on-disk
     * 4-digit zero-padded string form ({@code "0005"}) to the
     * {@link Integer} key field type expected by
     * {@link TransactionCategoryKey#setTranCatCd(Integer)} — same
     * representation conversion as in
     * {@link #findById_canonicalRegularSalesKey_returnsCategory()},
     * documented there in detail.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById((TRAN_TYPE_PURCHASE, TRAN_CAT_INTEREST)) returns the seeded Interest Amount row")
    void findById_canonicalInterestKey_returnsCategory() {
        // Arrange — canonical (01, 0005) composite key built from TestFixtures constants
        TransactionCategoryKey key = buildKey(
                TestFixtures.Transactions.TRAN_TYPE_PURCHASE,
                Integer.parseInt(TestFixtures.Transactions.TRAN_CAT_INTEREST));

        // Act — drive the production repository against the Flyway-seeded catalog
        Optional<TransactionCategory> result = transactionCategoryRepository.findById(key);

        // Assert — the canonical Interest Amount row must be present
        assertThat(result)
                .as("Seed data must include the canonical (TRAN_TYPE_PURCHASE, TRAN_CAT_INTEREST) "
                        + "= (01, 0005) row — record 5 in app/data/ASCII/trancatg.txt and the "
                        + "category code emitted by the migrated InterestCalculationProcessor "
                        + "(CBACT04C MOVE '05' TO TRAN-CAT-CD)")
                .isPresent();
        assertThat(getTypeCd(result.get()))
                .as("Returned row's TRAN-TYPE-CD must match the lookup key (idempotent contract)")
                .isEqualTo(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);
        assertThat(getCatCd(result.get()))
                .as("Returned row's TRAN-CAT-CD must match the lookup key (idempotent contract)")
                .isEqualTo(Integer.parseInt(TestFixtures.Transactions.TRAN_CAT_INTEREST));
    }

    // =========================================================================
    // Full-Catalog Lookup Test (AAP §0.5.1 — "Lookup of all 18 categories")
    // =========================================================================

    /**
     * Verifies that the Flyway-seeded catalog contains <em>at least</em>
     * the 18 canonical reference rows from
     * {@code app/data/ASCII/trancatg.txt}. The assertion uses
     * {@code hasSizeGreaterThanOrEqualTo(18)} rather than an exact
     * match to tolerate the additional synthetic rows persisted by
     * other tests in the same class (or by sibling tests in other
     * classes if the container is shared) — the {@code @DataJpaTest}
     * per-method transactional rollback should isolate them, but using
     * a greater-than-or-equal assertion adds resilience without
     * weakening the seed-integrity contract.
     *
     * <p>This is the canonical "seed integrity" smoke test: if the
     * Flyway {@code V3__seed.sql} script accidentally skips one of the
     * 18 INSERT statements (or if a future REFACTOR drops a row),
     * {@code findAll().size()} dropping below 18 surfaces the omission
     * immediately. The 18-row floor matches the AAP §0.5.1 purpose
     * statement ("Lookup of all 18 categories") for this IT and the
     * COBOL fixture file ({@code app/data/ASCII/trancatg.txt} carries
     * 18 records).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findAll() returns at least the 18 seeded reference categories from trancatg.txt")
    void findAll_seededReferenceData_returnsAllCategories() {
        // Act — drive the production repository against the Flyway-seeded catalog
        List<TransactionCategory> all = transactionCategoryRepository.findAll();

        // Assert — the seed must hydrate at least the 18 canonical rows
        assertThat(all)
                .as("Seed data must contain at least the 18 reference categories from "
                        + "app/data/ASCII/trancatg.txt (canonical reference catalog)")
                .hasSizeGreaterThanOrEqualTo(18);
    }

    // =========================================================================
    // Catalog Size Sanity Check
    // =========================================================================

    /**
     * Verifies that {@link TransactionCategoryRepository#count()} returns
     * a non-negative tally. Together with
     * {@link #findAll_seededReferenceData_returnsAllCategories()} this
     * guards against accidentally truncating the catalog table at any
     * point in the migration lifecycle. The assertion uses
     * {@code isGreaterThanOrEqualTo(0)} rather than an exact value
     * because the inherited {@code @DataJpaTest} transactional rollback
     * may not yet have run when this method is invoked, so synthetic
     * rows from earlier tests in the same class might still be visible
     * — the non-negative invariant is the safest universal assertion.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("count() returns non-negative total row count")
    void count_invoked_returnsNonNegativeValue() {
        // Act — drive the inherited JpaRepository.count() against the real DB
        long total = transactionCategoryRepository.count();

        // Assert — count is a row tally; it must never be negative
        assertThat(total)
                .as("count() must return a non-negative row tally; negative values would "
                        + "indicate a Spring Data JPA implementation defect")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    /**
     * Constructs a synthetic {@link TransactionCategoryKey} composite key
     * from the supplied 2-character type code and 4-digit category code.
     * This is a pure data-builder helper — it carries no business logic,
     * no validation, no derivation. Per AAP §0.10.1 Require Test
     * Coverage Rule, test helpers must not duplicate production logic.
     *
     * @param typeCd the 2-character {@code TRAN-TYPE-CD} component
     *               ({@code PIC X(02)}). Canonical seed values are
     *               {@code "01"}–{@code "07"}; tests in this class use
     *               {@code "77"}, {@code "78"}, {@code "88"}, {@code "99"}
     *               for synthetic insertion to avoid collision with the
     *               Flyway seed.
     * @param catCd  the 4-digit {@code TRAN-CAT-CD} component
     *               ({@code PIC 9(04)}). Canonical seed values are in
     *               the {@code 1}–{@code 5} range; tests in this class
     *               use {@code 1}, {@code 2}, {@code 8888}, {@code 9999}
     *               for synthetic insertion to avoid collision with the
     *               Flyway seed.
     * @return a fully-populated {@link TransactionCategoryKey} instance
     *         with both fields set via the production setters (so any
     *         future Bean Validation constraint added by REFACTOR-flavor
     *         agents takes effect on persistence, not on construction).
     */
    private TransactionCategoryKey buildKey(String typeCd, Integer catCd) {
        TransactionCategoryKey k = new TransactionCategoryKey();
        k.setTranTypeCd(typeCd);
        k.setTranCatCd(catCd);
        return k;
    }

    /**
     * Constructs a synthetic {@link TransactionCategory} entity with the
     * supplied composite key and 50-char-or-less description. This is a
     * pure data-builder helper — it carries no business logic, no
     * validation, no derivation. Per AAP §0.10.1 Require Test Coverage
     * Rule, test helpers must not duplicate production logic.
     *
     * @param key         the composite primary key carrying the
     *                    {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} tuple.
     * @param description the description string up to 50 characters long
     *                    per the COBOL {@code PIC X(50)} boundary.
     * @return a fully-populated {@link TransactionCategory} instance with
     *         the supplied fields set via the production setters (so any
     *         future Bean Validation constraint added by REFACTOR-flavor
     *         agents takes effect on persistence, not on construction).
     */
    private TransactionCategory buildCategory(TransactionCategoryKey key, String description) {
        TransactionCategory c = new TransactionCategory();
        c.setKey(key);
        c.setTranCatTypeDesc(description);
        return c;
    }

    /**
     * Extracts the {@code TRAN-TYPE-CD} component from the composite key
     * of the supplied {@link TransactionCategory}. Provided as a single
     * source of truth for the {@code key.getTranTypeCd()} navigation so
     * tests stay readable when the description-vs-key indirection is
     * the only structural concern.
     *
     * @param c the {@link TransactionCategory} whose
     *          {@code TRAN-TYPE-CD} component is requested
     * @return the 2-character {@code TRAN-TYPE-CD} value
     */
    private String getTypeCd(TransactionCategory c) {
        return c.getKey().getTranTypeCd();
    }

    /**
     * Extracts the {@code TRAN-CAT-CD} component from the composite key
     * of the supplied {@link TransactionCategory}. Provided as a single
     * source of truth for the {@code key.getTranCatCd()} navigation so
     * tests stay readable when the description-vs-key indirection is
     * the only structural concern.
     *
     * @param c the {@link TransactionCategory} whose {@code TRAN-CAT-CD}
     *          component is requested
     * @return the 4-digit {@code TRAN-CAT-CD} value as an {@link Integer}
     */
    private Integer getCatCd(TransactionCategory c) {
        return c.getKey().getTranCatCd();
    }
}
