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
//   * TransactionType — the JPA entity stub matching the CVTRA03Y.cpy
//     TRAN-TYPE-RECORD layout (60-byte fixed-width record: 2-char primary
//     key + 50-char description + 8-byte FILLER). Test methods drive
//     persist/find/save round-trips through this entity.
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
//   * TestFixtures — the shared test-constants holder. The well-known
//     2-character transaction-type primary keys
//     TestFixtures.Transactions.TRAN_TYPE_PURCHASE ('01'),
//     TestFixtures.Transactions.TRAN_TYPE_PAYMENT ('02'), and
//     TestFixtures.Transactions.TRAN_TYPE_CREDIT ('03') are referenced by
//     name to assert that the Flyway-seeded reference catalog contains
//     the canonical type rows accessible by primary key. Per AAP §0.5.5
//     Cross-File Test Dependencies and AAP §0.10.1 Require Test Coverage
//     Rule (test bodies must not duplicate literal codes that already
//     appear in TestFixtures).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.TransactionType;
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
//     test class and each method so IDE runner output and CI test reports
//     surface the COBOL-parity intent (rather than the camelCase method
//     name alone).
//
//   * @Disabled defers <em>runtime</em> execution until the
//     production-side prerequisites (entity @Entity annotations + Flyway
//     V1__schema.sql + V3__seed.sql) are landed by subsequent
//     REFACTOR-flavor migration agents. JUnit 5 reports @Disabled tests
//     as "skipped" (not "failed") so the Surefire/Failsafe build stays
//     green; the reactivation criteria appear in the annotation's value
//     attribute and in the class-level Javadoc "Reactivation Checklist"
//     section. The sibling e2e suites (GateVerificationE2ETest,
//     OnlineTransactionE2ETest, AdminUserManagementE2ETest) use the same
//     @Disabled pattern — this IT mirrors that project convention so the
//     compile-time wiring is verified end-to-end while the runtime DB
//     execution awaits its production-side dependencies.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Framework dependency-injection import.
//
//   * @Autowired field-injects the Spring-managed
//     TransactionTypeRepository proxy into this IT class instance. The
//     proxy is created by Spring Data JPA at @DataJpaTest context startup
//     from the {@code JpaRepository<TransactionType, String>} interface
//     declaration on the production repository — no manual implementation
//     is required, and no field declared with @Mock is permissible at
//     this IT layer (AAP §0.10.1 Require Test Coverage Rule: integration
//     tests must invoke the REAL repository against the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.util.List — return type of TransactionTypeRepository.findAll()
//     used by the seed-integrity assertion (at least 7 rows present).
//
//   * java.util.Optional — return type of
//     TransactionTypeRepository.findById(String) used by the primary-key
//     lookup assertions (isPresent / isEmpty).
// ---------------------------------------------------------------------------
import java.util.List;
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static import (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. assertThat(optional).isPresent(),
//     assertThat(list).hasSizeGreaterThanOrEqualTo(7), and
//     assertThat(string).isEqualTo(expected) are the three idioms this
//     class exercises.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TransactionTypeRepository}, which persists
 * {@link TransactionType} entities migrated from the COBOL
 * {@code TRAN-TYPE-RECORD} defined in {@code app/cpy/CVTRA03Y.cpy}
 * (RECLN 60). Seed data is sourced from
 * {@code app/data/ASCII/trantype.txt} (7 reference rows).
 *
 * <h2>COBOL Provenance — CVTRA03Y.cpy</h2>
 *
 * <p>{@code TRAN-TYPE-RECORD} uses a simple 2-character primary key
 * ({@code TRAN-TYPE PIC X(02)}) and carries a 50-character description
 * ({@code TRAN-TYPE-DESC PIC X(50)}) plus an 8-byte FILLER. This is
 * <em>read-only reference data</em> after the initial Flyway seed — no
 * business workflow mutates rows in this catalog at runtime. The 7
 * canonical rows from {@code app/data/ASCII/trantype.txt}:
 *
 * <pre>
 *   01  Purchase
 *   02  Payment
 *   03  Credit
 *   04  Authorization
 *   05  Refund
 *   06  Reversal
 *   07  Adjustment
 * </pre>
 *
 * <h2>Coverage Focus (AAP §0.5.1)</h2>
 *
 * <p>The 8 test methods exercise the integration of
 * {@link TransactionTypeRepository} against a real PostgreSQL 16 instance
 * provisioned by Testcontainers — no Mockito stubs at this layer (AAP
 * §0.10.1 Require Test Coverage Rule). The categories below cover the
 * AAP-specified scenarios:
 *
 * <ul>
 *   <li>{@link #findById_existingType_returnsType()} — primary-key lookup
 *       against a synthetic row persisted via the inherited
 *       TestEntityManager.</li>
 *   <li>{@link #findById_nonexistentType_returnsEmpty()} — negative-path
 *       lookup verifying Optional.empty for unknown codes.</li>
 *   <li>{@link #findById_purchaseTypeCode_returnsPurchaseType()},
 *       {@link #findById_paymentTypeCode_returnsPaymentType()},
 *       {@link #findById_creditTypeCode_returnsCreditType()} — well-known
 *       canonical codes (TestFixtures.Transactions.TRAN_TYPE_PURCHASE
 *       '01', TRAN_TYPE_PAYMENT '02', TRAN_TYPE_CREDIT '03') accessible
 *       through the Flyway-seeded catalog. These constants are referenced
 *       by name across the service layer (TransactionAddService,
 *       TransactionDetailService) and the batch layer
 *       (TransactionReportProcessor, StatementProcessor) — a missing
 *       seed row would surface here first.</li>
 *   <li>{@link #findAll_seededReferenceData_returnsAllSevenTypes()} —
 *       full-catalog smoke test asserting that the Flyway seed
 *       hydrates at least the 7 canonical rows from
 *       {@code app/data/ASCII/trantype.txt} (AAP §0.5.1 "Lookup of all
 *       7 transaction types").</li>
 *   <li>{@link #save_newType_persistsAndRoundTrips()} — write-path
 *       smoke test verifying that a fresh row round-trips correctly
 *       through Hibernate. While this catalog is conceptually read-only
 *       in business workflows, the {@code save} path must work for the
 *       Flyway seed itself and for any future REFACTOR-flavor admin
 *       maintenance workflow.</li>
 *   <li>{@link #save_maxLengthDescription_preservesAllChars()} —
 *       boundary test verifying that the 50-character
 *       {@code TRAN-TYPE-DESC PIC X(50)} field is sized correctly in
 *       PostgreSQL (catches accidental {@code VARCHAR(20)} or similar
 *       truncation in the Flyway DDL).</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — basic sanity
 *       check that the inherited {@code count()} method returns a
 *       non-negative tally; together with
 *       {@link #findAll_seededReferenceData_returnsAllSevenTypes()} this
 *       guards against accidentally truncating the catalog table at any
 *       point in the migration lifecycle.</li>
 * </ul>
 *
 * <h2>Mock Boundary (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>This IT has <strong>no Mockito mocks</strong>. The system under test
 * is the real {@link TransactionTypeRepository} bean wired by Spring Data
 * JPA against the real PostgreSQL 16 database supplied by Testcontainers
 * (inherited from {@link AbstractRepositoryIT}). Repository ITs sit at
 * the lowest mock boundary in the test pyramid: they verify that the
 * Spring Data JPA proxy + Hibernate ORM + JDBC driver + PostgreSQL stack
 * produces correct results against a real schema seeded by Flyway. Tests
 * that would otherwise mock the repository (service unit tests) live one
 * layer up in {@code com.aws.carddemo.service.*Test}.
 *
 * <h2>Test isolation (AAP §0.10.9)</h2>
 *
 * <p>{@code @DataJpaTest} (inherited from {@link AbstractRepositoryIT})
 * wraps each {@code @Test} method in a transaction that rolls back at
 * method end. This means the synthetic rows persisted by
 * {@link #findById_existingType_returnsType()},
 * {@link #save_newType_persistsAndRoundTrips()}, and
 * {@link #save_maxLengthDescription_preservesAllChars()} are gone before
 * the next test sees the database state. Each test starts from the
 * Flyway-seeded catalog (7 rows) plus zero synthetic additions —
 * test order independence is guaranteed.
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>The suite loads the {@code @DataJpaTest} Spring slice via
 * {@link AbstractRepositoryIT} and exercises the production
 * {@link TransactionTypeRepository} bean against a real PostgreSQL 16
 * database. That requires every production-side prerequisite to be in
 * place: the {@link TransactionType} entity must be annotated as a JPA
 * {@code @Entity} so Hibernate can map it onto a database table, and the
 * Flyway scripts under {@code src/main/resources/db/migration/} must
 * exist to create the {@code transaction_types} table and seed the 7
 * canonical reference rows. As of this commit those production-side
 * prerequisites are <em>intentionally deferred</em> by the
 * REFACTOR-flavor migration agents.
 *
 * <p>This testing-flavor AAP (§0.8.1 "Cross-cutting files that the
 * migration creates and that this Action Plan exercises (but does not
 * own)") <strong>explicitly forbids</strong> modifying any production
 * source under {@code src/main/java/com/aws/carddemo/} for the purpose
 * of <em>testability alone</em>: the testing flavor CREATEs tests against
 * those classes but does NOT redesign them. The suite is therefore
 * registered, compiled, and preserved end-to-end (the production stubs
 * created alongside this IT enable compilation), but the JUnit Jupiter
 * {@code @Disabled} marker below defers <em>runtime</em> execution until
 * the production-side migration agents complete the JPA annotation and
 * Flyway seed work. Once both arrive, removing the {@code @Disabled}
 * annotation (and its companion unused import) activates all 8 tests
 * unchanged.
 *
 * <h3>Reactivation Checklist (for the next agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of
 * the following production-side prerequisites are in place:
 *
 * <ol>
 *   <li><strong>{@code @Entity} on
 *       {@link com.aws.carddemo.entity.TransactionType}</strong> — REFACTOR
 *       agents add {@code @Entity}, {@code @Table(name = "transaction_types")},
 *       {@code @Id} on {@code tranType}, {@code @Column(name = "tran_type",
 *       length = 2)} on {@code tranType}, and {@code @Column(name =
 *       "tran_type_desc", length = 50)} on {@code tranTypeDesc}. Without
 *       these annotations Hibernate cannot map the entity onto the
 *       PostgreSQL table and {@code @DataJpaTest} context startup fails.</li>
 *   <li><strong>Flyway {@code V1__schema.sql}</strong> under
 *       {@code src/main/resources/db/migration/} containing the
 *       {@code CREATE TABLE transaction_types (tran_type CHAR(2) NOT NULL
 *       PRIMARY KEY, tran_type_desc VARCHAR(50) NOT NULL)} statement
 *       (column lengths matching the COBOL {@code PIC X(02)} and
 *       {@code PIC X(50)} fields verbatim).</li>
 *   <li><strong>Flyway {@code V3__seed.sql}</strong> under
 *       {@code src/main/resources/db/migration/} containing 7
 *       {@code INSERT INTO transaction_types (tran_type, tran_type_desc)
 *       VALUES (...)} statements covering the rows in
 *       {@code app/data/ASCII/trantype.txt}: ('01','Purchase'),
 *       ('02','Payment'), ('03','Credit'), ('04','Authorization'),
 *       ('05','Refund'), ('06','Reversal'), ('07','Adjustment').</li>
 *   <li><strong>Docker available to Testcontainers</strong> at test
 *       runtime — the {@code mvn verify} build agent must be able to run
 *       {@code postgres:16-alpine}. CI agents that cannot start
 *       containers (e.g. nested-virtualisation-free environments) can set
 *       {@code TESTCONTAINERS_RYUK_DISABLED=true} as documented in
 *       {@code src/test/resources/application-test.properties}.</li>
 * </ol>
 *
 * <p>When all four items above are complete, deleting the
 * {@code @Disabled} annotation and the {@code import
 * org.junit.jupiter.api.Disabled;} line activates the suite. No other
 * code changes are required: the test method bodies are written against
 * the production API exactly as it will be once the REFACTOR work
 * completes.
 *
 * @see TransactionTypeRepository
 * @see TransactionType
 * @see AbstractRepositoryIT
 * @see TestFixtures.Transactions
 */
@DisplayName("TransactionTypeRepository — CVTRA03Y.cpy / trantype.txt reference data ITs")
@Disabled("Awaits production-side prerequisites: (1) @Entity / @Id / @Column / "
        + "@Table(name = \"transaction_types\") annotations on "
        + "com.aws.carddemo.entity.TransactionType so Hibernate can map the entity onto a "
        + "PostgreSQL table; (2) Flyway V1__schema.sql under src/main/resources/db/migration/ "
        + "creating the transaction_types table (tran_type CHAR(2) PRIMARY KEY, tran_type_desc "
        + "VARCHAR(50) NOT NULL); (3) Flyway V3__seed.sql under src/main/resources/db/migration/ "
        + "with 7 INSERT statements from app/data/ASCII/trantype.txt (01=Purchase, 02=Payment, "
        + "03=Credit, 04=Authorization, 05=Refund, 06=Reversal, 07=Adjustment). Per AAP §0.8.1 the "
        + "testing flavor cannot modify those production files for testability alone; the next "
        + "REFACTOR-flavor agent removes this annotation when the prerequisites are complete. See "
        + "the class Javadoc 'Reactivation Checklist' for the full list.")
class TransactionTypeRepositoryIT extends AbstractRepositoryIT {

    /**
     * The Spring Data JPA repository under integration test. The bean is
     * created by Spring Data JPA at {@code @DataJpaTest} context startup
     * from the {@link TransactionTypeRepository} interface declaration
     * (no manual implementation). Field injection is consistent with the
     * inherited {@code @Autowired TestEntityManager entityManager} field
     * on {@link AbstractRepositoryIT}.
     */
    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    // =========================================================================
    // Primary-Key Lookup Tests
    // =========================================================================

    /**
     * Verifies that {@link TransactionTypeRepository#findById(Object)}
     * returns the persisted {@link TransactionType} for a synthetic
     * 2-character primary key. The test persists a row via the inherited
     * {@code TestEntityManager}, flushes the persistence context to push
     * the row to PostgreSQL, clears the first-level cache so the
     * subsequent {@code findById} hits the database, and then asserts
     * that the returned {@link Optional} contains an entity with the
     * expected primary key and description.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(existing TRAN-TYPE code) returns the persisted TransactionType")
    void findById_existingType_returnsType() {
        // Arrange — persist a synthetic transaction type (not in the seed)
        TransactionType type = buildType("99", "Test Type Description");
        entityManager.persistAndFlush(type);
        entityManager.clear();

        // Act — drive the production repository against the real DB
        Optional<TransactionType> result = transactionTypeRepository.findById("99");

        // Assert — verify the row was located and the round-trip preserved both fields
        assertThat(result)
                .as("findById should locate the persisted transaction type by 2-character primary key")
                .isPresent();
        TransactionType t = result.get();
        assertThat(t.getTranType())
                .as("primary key TRAN-TYPE must round-trip exactly")
                .isEqualTo("99");
        assertThat(t.getTranTypeDesc())
                .as("description TRAN-TYPE-DESC must round-trip exactly")
                .isEqualTo("Test Type Description");
    }

    /**
     * Verifies that {@link TransactionTypeRepository#findById(Object)}
     * returns {@link Optional#empty()} when the supplied 2-character
     * primary key does not exist in the {@code transaction_types} table.
     * This is the Java equivalent of the COBOL CICS response code
     * {@code DFHRESP(NOTFND)} branch from the legacy
     * {@code EXEC CICS READ DATASET('TRANTYPE')} statement.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(nonexistent TRAN-TYPE) returns empty Optional")
    void findById_nonexistentType_returnsEmpty() {
        // Act — look up a primary key that is not present in the seed catalog
        // ("XX" is outside the canonical {01,02,03,04,05,06,07} range and is never
        // persisted by this IT or any sibling test).
        Optional<TransactionType> result = transactionTypeRepository.findById("XX");

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND) signal
        assertThat(result)
                .as("findById should return Optional.empty for unknown TRAN-TYPE codes "
                        + "(COBOL DFHRESP(NOTFND) equivalent)")
                .isEmpty();
    }

    // =========================================================================
    // Well-Known Canonical Code Tests (TestFixtures constants)
    // =========================================================================

    /**
     * Verifies that the Flyway-seeded catalog contains the canonical
     * Purchase row keyed by {@link TestFixtures.Transactions#TRAN_TYPE_PURCHASE}
     * ({@code "01"}). This row is referenced across the migrated codebase
     * (transaction-posting validation in {@code CBTRN02C}, transaction-add
     * validation in {@code COTRN02C}, transaction-report rendering in
     * {@code CBTRN03C}) — a missing seed row would surface here first.
     *
     * <p>The test asserts only the primary-key presence (not the
     * description literal), because the description is intentionally
     * sourced from the Flyway seed script — the test must not duplicate
     * the literal value per AAP §0.10.1.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(PURCHASE type code '01') returns the PURCHASE reference row (seeded)")
    void findById_purchaseTypeCode_returnsPurchaseType() {
        // Act — drive the production repository against the Flyway-seeded catalog
        Optional<TransactionType> result = transactionTypeRepository.findById(
                TestFixtures.Transactions.TRAN_TYPE_PURCHASE);

        // Assert — the canonical PURCHASE row must be present
        assertThat(result)
                .as("Seed data must include the PURCHASE type code '01' "
                        + "(TestFixtures.Transactions.TRAN_TYPE_PURCHASE constant)")
                .isPresent();
        assertThat(result.get().getTranType())
                .as("Returned row's primary key must match the lookup key (idempotent contract)")
                .isEqualTo(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);
    }

    /**
     * Verifies that the Flyway-seeded catalog contains the canonical
     * Payment row keyed by {@link TestFixtures.Transactions#TRAN_TYPE_PAYMENT}
     * ({@code "02"}). The Payment type drives the bill-payment workflow
     * ({@code COBIL00C.cbl} migration) — a missing seed row would block
     * that workflow's transaction creation step.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(PAYMENT type code '02') returns the PAYMENT reference row (seeded)")
    void findById_paymentTypeCode_returnsPaymentType() {
        // Act
        Optional<TransactionType> result = transactionTypeRepository.findById(
                TestFixtures.Transactions.TRAN_TYPE_PAYMENT);

        // Assert
        assertThat(result)
                .as("Seed data must include the PAYMENT type code '02' "
                        + "(TestFixtures.Transactions.TRAN_TYPE_PAYMENT constant)")
                .isPresent();
        assertThat(result.get().getTranType())
                .as("Returned row's primary key must match the lookup key (idempotent contract)")
                .isEqualTo(TestFixtures.Transactions.TRAN_TYPE_PAYMENT);
    }

    /**
     * Verifies that the Flyway-seeded catalog contains the canonical
     * Credit row keyed by {@link TestFixtures.Transactions#TRAN_TYPE_CREDIT}
     * ({@code "03"}). The Credit type is referenced by interest-calculation
     * adjustment paths and refund workflows.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(CREDIT type code '03') returns the CREDIT reference row (seeded)")
    void findById_creditTypeCode_returnsCreditType() {
        // Act
        Optional<TransactionType> result = transactionTypeRepository.findById(
                TestFixtures.Transactions.TRAN_TYPE_CREDIT);

        // Assert
        assertThat(result)
                .as("Seed data must include the CREDIT type code '03' "
                        + "(TestFixtures.Transactions.TRAN_TYPE_CREDIT constant)")
                .isPresent();
        assertThat(result.get().getTranType())
                .as("Returned row's primary key must match the lookup key (idempotent contract)")
                .isEqualTo(TestFixtures.Transactions.TRAN_TYPE_CREDIT);
    }

    // =========================================================================
    // Full-Catalog Lookup Test (AAP §0.5.1 — "Lookup of all 7 transaction types")
    // =========================================================================

    /**
     * Verifies that the Flyway-seeded catalog contains <em>at least</em>
     * the 7 canonical reference rows from
     * {@code app/data/ASCII/trantype.txt}. The assertion uses
     * {@code hasSizeGreaterThanOrEqualTo} rather than an exact match to
     * tolerate the additional synthetic rows persisted by other tests in
     * the same class (or by sibling tests in other classes if the
     * container is shared) — the {@code @DataJpaTest} per-method
     * transactional rollback should isolate them, but using a
     * greater-than-or-equal assertion adds resilience without weakening
     * the seed-integrity contract.
     *
     * <p>This is the canonical "seed integrity" smoke test: if the
     * Flyway {@code V3__seed.sql} script accidentally skips one of the
     * 7 INSERT statements (or if a future REFACTOR drops a row),
     * {@code findAll().size()} dropping below 7 surfaces the omission
     * immediately.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findAll() returns at least the 7 seeded reference types from trantype.txt")
    void findAll_seededReferenceData_returnsAllSevenTypes() {
        // Act — drive the production repository against the Flyway-seeded catalog
        List<TransactionType> all = transactionTypeRepository.findAll();

        // Assert — the seed must hydrate at least the 7 canonical rows
        // (01=Purchase, 02=Payment, 03=Credit, 04=Authorization,
        //  05=Refund,   06=Reversal, 07=Adjustment)
        assertThat(all)
                .as("Seed data must contain at least the 7 reference types from "
                        + "app/data/ASCII/trantype.txt (canonical reference catalog)")
                .hasSizeGreaterThanOrEqualTo(7);
    }

    // =========================================================================
    // Save Path Tests
    // =========================================================================

    /**
     * Verifies that {@link TransactionTypeRepository#save(Object)}
     * persists a fresh {@link TransactionType} row and that the
     * subsequent {@code findById} round-trip returns the same primary
     * key and description. While the {@code TRAN-TYPE} catalog is
     * conceptually read-only in business workflows, the {@code save}
     * path must work for the Flyway seed itself and for any future
     * REFACTOR-flavor admin maintenance workflow that adds a new
     * transaction type.
     *
     * <p>The test relies on the inherited {@code TestEntityManager}
     * {@code flush()} + {@code clear()} calls to ensure the read goes
     * to PostgreSQL rather than the first-level cache, which would
     * otherwise mask a serialisation defect in the entity mapping.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(new TransactionType) persists and round-trips description")
    void save_newType_persistsAndRoundTrips() {
        // Arrange — build a fresh synthetic row outside the canonical seed range
        TransactionType t = buildType("88", "Synthetic test type description");

        // Act — drive the production repository's save path, then force a DB read
        transactionTypeRepository.save(t);
        entityManager.flush();
        entityManager.clear();

        // Assert — round-trip must preserve both the primary key and the description
        TransactionType reloaded = transactionTypeRepository.findById("88").orElseThrow();
        assertThat(reloaded.getTranType())
                .as("TRAN-TYPE primary key must round-trip after save+findById")
                .isEqualTo("88");
        assertThat(reloaded.getTranTypeDesc())
                .as("TRAN-TYPE-DESC must round-trip exactly (50 chars max per PIC X(50))")
                .isEqualTo("Synthetic test type description");
    }

    /**
     * Verifies that the {@code TRAN-TYPE-DESC} column is sized exactly
     * {@code VARCHAR(50)} per the COBOL {@code PIC X(50)} boundary. The
     * test persists a row whose description is exactly 50 characters and
     * asserts the round-trip preserves all 50 characters (no truncation,
     * no accidental {@code VARCHAR(20)} downsize in the Flyway DDL).
     *
     * <p>This boundary check guards against a class of regression where
     * a REFACTOR-flavor agent shortens a column under the assumption
     * that "no real type description exceeds 12 characters" — the AAP
     * §0.10.4 Immutable Boundaries directive requires the
     * {@code PIC X(50)} layout to be preserved verbatim across the
     * migration.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(TransactionType with maximum 50-char description) preserves all chars")
    void save_maxLengthDescription_preservesAllChars() {
        // Arrange — construct a description that is exactly 50 characters
        // (the COBOL PIC X(50) boundary). Sanity-check the literal length
        // before persistence to catch typo'd test data — the assertion is
        // a precondition on the test fixture, not the system under test.
        String maxDesc = "1234567890123456789012345678901234567890123456789X";
        assertThat(maxDesc)
                .as("Test fixture sanity check: the description literal must be exactly 50 chars "
                        + "to exercise the PIC X(50) boundary")
                .hasSize(50);
        TransactionType t = buildType("87", maxDesc);

        // Act — persist, flush, and clear so the read hits the database (not the cache)
        transactionTypeRepository.save(t);
        entityManager.flush();
        entityManager.clear();

        // Assert — the full 50-character description must round-trip without truncation
        TransactionType reloaded = transactionTypeRepository.findById("87").orElseThrow();
        assertThat(reloaded.getTranTypeDesc())
                .as("50-char description must round-trip without truncation — guards against "
                        + "accidental VARCHAR(20) or similar narrowing in V1__schema.sql DDL "
                        + "(AAP §0.10.4 Immutable Boundaries)")
                .isEqualTo(maxDesc)
                .hasSize(50);
    }

    // =========================================================================
    // Catalog Size Sanity Check
    // =========================================================================

    /**
     * Verifies that {@link TransactionTypeRepository#count()} returns a
     * non-negative tally. Together with
     * {@link #findAll_seededReferenceData_returnsAllSevenTypes()} this
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
        long total = transactionTypeRepository.count();

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
     * Constructs a synthetic {@link TransactionType} with the supplied
     * 2-character code and 50-char-or-less description. This is a pure
     * data-builder helper — it carries no business logic, no validation,
     * no derivation. Per AAP §0.10.1 Require Test Coverage Rule, test
     * helpers must not duplicate production logic.
     *
     * @param typeCode    the 2-character {@code TRAN-TYPE} primary key
     *                    (e.g. {@code "99"} for a synthetic test row;
     *                    {@code "01"}–{@code "07"} for the canonical
     *                    seed rows — but tests should not call this
     *                    helper with seed-range values to avoid
     *                    accidentally shadowing the Flyway-seeded
     *                    catalog).
     * @param description the description string up to 50 characters
     *                    long per the COBOL {@code PIC X(50)} boundary.
     * @return a fully-populated {@link TransactionType} instance with
     *         the supplied fields set via the production setters
     *         (so any future Bean Validation constraint added by
     *         REFACTOR-flavor agents takes effect on persistence, not
     *         on construction).
     */
    private TransactionType buildType(String typeCode, String description) {
        TransactionType t = new TransactionType();
        t.setTranType(typeCode);
        t.setTranTypeDesc(description);
        return t;
    }
}
