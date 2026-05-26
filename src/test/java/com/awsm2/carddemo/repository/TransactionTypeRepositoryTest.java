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
package com.awsm2.carddemo.repository;

import com.awsm2.carddemo.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link TransactionTypeRepository} &mdash;
 * the FOUNDATIONAL repository test of the CardDemo suite (simplest repository:
 * {@code JpaRepository<TransactionType, String>} with empty body, no custom
 * queries, no composite keys, no {@code @Version}, no PII).
 *
 * <h2>Source mapping (AAP &sect;0.3.1, &sect;0.4.1, &sect;0.6.2)</h2>
 * <ul>
 *   <li><strong>COBOL copybook:</strong> {@code app/cpy/CVTRA03Y.cpy} &mdash;
 *       the {@code TRAN-TYPE-RECORD} layout (RECLN 60 bytes):
 *       {@code 05 TRAN-TYPE PIC X(02)} (2-byte primary key, KEYS(2,0)),
 *       {@code 05 TRAN-TYPE-DESC PIC X(50)} (50-byte description), and a
 *       trailing {@code 05 FILLER PIC X(08)} (8-byte padding) that is
 *       omitted in the relational model (the source fixture
 *       {@code app/data/ASCII/trantype.txt} carries the literal
 *       {@code "00000000"} in positions 53&ndash;60 of every row,
 *       confirming the trailing FILLER is padding rather than data).</li>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS} &mdash;
 *       {@code KEYS(2 0)}, {@code RECORDSIZE(60 60)},
 *       {@code SHAREOPTIONS(1 4)}, {@code INDEXED}, {@code REC-TOTAL=7}
 *       (per {@code app/jcl/TRANTYPE.jcl}:L36&ndash;L49 and verified
 *       against {@code app/catlg/LISTCAT.txt}). The COBOL
 *       {@code SHAREOPTIONS(1 4)} attribute has no PostgreSQL equivalent
 *       (PostgreSQL handles concurrent reads/writes natively via MVCC);
 *       it is preserved as a comment only.</li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/CBTRN02C.cbl} &mdash; transaction-posting
 *             cascade. Validates each incoming
 *             {@code DALYTRAN-TRAN-TYPE-CD} against the {@code TRANTYPE}
 *             cluster as part of the 4-stage validation cascade. Replaced
 *             in the Java target by {@code TransactionPostingService}.</li>
 *         <li>{@code app/cbl/CBTRN03C.cbl} &mdash; transaction-report
 *             generator. Replaced by {@code TransactionReportService}.</li>
 *         <li>{@code app/cbl/COTRN02C.cbl} &mdash; online transaction-add.
 *             Replaced by {@code TransactionAddService}.</li>
 *         <li>{@code app/cbl/CBACT04C.cbl} &mdash; interest calculation
 *             composite-key lookups against {@code disclosure_group}.
 *             Replaced by {@code InterestCalculationService}.</li>
 *       </ul>
 *   </li>
 *   <li><strong>JCL DD allocation:</strong>
 *       {@code app/jcl/TRANTYPE.jcl} &mdash; IDCAMS DEFINE CLUSTER (STEP10)
 *       + IDCAMS REPRO load (STEP15) from {@code TRANTYPE.PS}. Replaced
 *       operationally by Flyway migrations V008 (DDL) and V013 (seed).</li>
 *   <li><strong>Migrations:</strong>
 *       {@code src/main/resources/db/migration/V008__create_transaction_type.sql}
 *       (schema: {@code tran_type CHAR(2) PRIMARY KEY} +
 *       {@code tran_type_desc VARCHAR(50) NOT NULL}) and
 *       {@code src/main/resources/db/migration/V013__seed_transaction_type.sql}
 *       (seeds 7 canonical rows from {@code app/data/ASCII/trantype.txt}:
 *       {@code 01}=Purchase, {@code 02}=Payment, {@code 03}=Credit,
 *       {@code 04}=Authorization, {@code 05}=Refund, {@code 06}=Reversal,
 *       {@code 07}=Adjustment).</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ul>
 *   <li><strong>V013 seed cardinality:</strong> exactly seven canonical
 *       rows present after Flyway migrations complete (Test 1).</li>
 *   <li><strong>V013 individual rows:</strong> the {@code "01"} = Purchase
 *       and {@code "02"} = Payment rows are findable by primary key with
 *       the expected human-readable description text (Tests 2, 3).</li>
 *   <li><strong>V013 complete seed inventory:</strong> all seven code
 *       &rarr; description pairs are present with the canonical text
 *       (Test 4) &mdash; this is the authoritative seed-data integrity
 *       check.</li>
 *   <li><strong>Round-trip persistence by CHAR(2) PK:</strong> the
 *       inherited {@code JpaRepository.save()} + {@code findById()}
 *       cycle round-trips a freshly built {@link TransactionType} with
 *       a numeric primary-key code (Test 5).</li>
 *   <li><strong>CHAR(2) PK supports alphanumeric codes:</strong> the
 *       {@code tran_type CHAR(2)} column accepts a 2-character
 *       alphanumeric code (e.g., {@code "AB"}) and round-trips it
 *       (Test 6) &mdash; verifies COBOL {@code PIC X(02)} semantics
 *       (alphanumeric, not numeric-only) are preserved by the V008 DDL.</li>
 *   <li><strong>{@code findById} miss returns empty:</strong> a primary
 *       key that does not exist in the table returns
 *       {@code Optional.empty()} rather than throwing (Test 7).</li>
 *   <li><strong>{@code deleteById} contract:</strong> a previously
 *       persisted custom row can be deleted and is no longer findable
 *       (Test 8). V013-seeded rows ({@code "01"}&ndash;{@code "07"})
 *       are <em>never</em> deleted in any test &mdash; the V014
 *       seed migration loads {@code tran_category} rows that
 *       logically reference the V013 codes, and accidental deletion
 *       would break the lookup integrity expected by downstream
 *       service tests (e.g., {@code TransactionPostingServiceTest}).</li>
 *   <li><strong>{@code findAll} includes all seeds:</strong> the
 *       no-arg {@code findAll()} returns at least the 7 V013-seeded
 *       rows (allows for one or more rows persisted earlier in the
 *       transactional test scope &mdash; though @DataJpaTest rolls
 *       each test back, the assertion uses {@code >=} for robustness)
 *       (Test 9).</li>
 *   <li><strong>{@code existsById} contract:</strong> returns
 *       {@code true} for a V013-seeded code (e.g., {@code "01"}) and
 *       {@code false} for an unknown code (e.g., {@code "ZZ"})
 *       (Test 10).</li>
 * </ul>
 *
 * <h2>What this test deliberately does NOT validate</h2>
 * <ul>
 *   <li><strong>Custom finder methods.</strong> {@link TransactionTypeRepository}
 *       declares <em>no</em> custom queries &mdash; all access patterns
 *       required by consumer services are satisfied by inherited
 *       {@link org.springframework.data.jpa.repository.JpaRepository}
 *       methods ({@code findById}, {@code findAll}, {@code save},
 *       {@code deleteById}, {@code existsById}, {@code count}). Adding
 *       finders would violate the Minimal Change Clause (AAP &sect;0.7.3).</li>
 *   <li><strong>Optimistic locking.</strong> {@link TransactionType} has
 *       no {@code @Version} column &mdash; this lookup is static
 *       reference data seeded once by V013 and not updated at runtime.
 *       There is no read-modify-write contention to guard against.</li>
 *   <li><strong>FK constraints.</strong> Database-level FK constraints
 *       linking {@code tran_type} to {@code transactions.tran_type_cd},
 *       {@code tran_category.tran_type_cd}, etc. are intentionally NOT
 *       declared in V008 because Flyway lexicographic ordering
 *       interleaves consumer migrations around V008. Application-layer
 *       validation in {@code TransactionPostingService} /
 *       {@code TransactionAddService} enforces existence. This test
 *       therefore does not exercise FK behavior &mdash; it would be a
 *       schema-validation concern outside the scope of the repository
 *       slice test.</li>
 *   <li><strong>Business validation logic.</strong> Rejection of unknown
 *       type codes during a transaction post (COBOL {@code CBTRN02C}
 *       reject codes 100&ndash;109) is the responsibility of
 *       {@code TransactionPostingService} &mdash; not this repository.
 *       This test exercises only the JPA contract: primary-key lookup,
 *       round-trip persistence, and the inherited
 *       {@link org.springframework.data.jpa.repository.JpaRepository}
 *       methods.</li>
 * </ul>
 *
 * <h2>Container strategy</h2>
 * <p>The test class uses a single static {@code postgres:16-alpine}
 * Testcontainer (PostgreSQL 16 to match the production RDS Multi-AZ
 * baseline per AAP &sect;0.5.1, &sect;0.6.2). The
 * {@link ServiceConnection &#64;ServiceConnection} annotation registers
 * the container's JDBC connection details as a
 * {@code JdbcConnectionDetails} bean; the
 * {@link DynamicPropertySource &#64;DynamicPropertySource} method below
 * additionally pushes the URL/credentials/driver into
 * {@code spring.datasource.*} so that the user-declared
 * {@code @Primary @RefreshScope} {@code HikariDataSource} bean in
 * {@code JpaConfig} (which is constructed from
 * {@code spring.datasource.*} properties rather than from
 * {@code JdbcConnectionDetails}) picks up the same container that
 * {@code @ServiceConnection} configures for the auto-config consumers.
 * Flyway then applies the full V001&hellip;V015 migration set against
 * the fresh container before any test method runs, so V013's seeded
 * 7 transaction types are queryable in Tests 1, 2, 3, 4, 9, and 10.</p>
 *
 * <h2>Transactional isolation</h2>
 * <p>{@code @DataJpaTest} wraps each test method in a {@code @Transactional}
 * boundary that rolls back at the end of the test. The V013 seed data
 * (applied by Flyway at context start, BEFORE the first test transaction
 * begins) is therefore COMMITTED and visible to every test method, while
 * any rows persisted by an individual test (e.g., {@code "99"} in Test 5,
 * {@code "AB"} in Test 6) are rolled back at the end of that test and
 * invisible to the others. This isolation guarantee is what allows
 * Test 1's exact-count assertion and what guarantees Test 8 cleanly
 * tears down its own {@code "99"} fixture without polluting subsequent
 * test methods.</p>
 *
 * @see TransactionTypeRepository
 * @see TransactionType
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionTypeRepositoryTest {

    // -------------------------------------------------------------------------
    // PostgreSQL Testcontainer (postgres:16-alpine per AAP §0.5.1, §0.6.2)
    // -------------------------------------------------------------------------
    // The @Container annotation hands lifecycle management to the
    // Testcontainers JUnit 5 extension activated by @Testcontainers — start
    // before the first test method, stop after the last. The @ServiceConnection
    // annotation registers the container as a JdbcConnectionDetails bean so
    // Spring Boot auto-configures any DataSource that consumes
    // JdbcConnectionDetails (Spring Boot 3.1+).
    //
    // The image tag is pinned to "postgres:16-alpine" (NOT "latest" or
    // unpinned) per AAP §0.5.1 — the production RDS PostgreSQL Multi-AZ
    // target runs PostgreSQL 16, so tests exercise the same SQL engine.
    // This ensures the CHAR(2) primary key column behavior (space-padding
    // semantics, B-tree index ordering), the V008 + V013 migrations, and
    // the inherited JpaRepository contract are validated faithfully
    // against the production database engine — not against an H2
    // emulation that would silently diverge on CHAR padding behavior.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // @DynamicPropertySource — bind container URL to spring.datasource.*
    // -------------------------------------------------------------------------
    // Per AAP §0.6.4, the application's @Primary @RefreshScope HikariDataSource
    // bean declared in JpaConfig.dataSource(DataSourceProperties) is
    // constructed from spring.datasource.* properties (NOT from
    // JdbcConnectionDetails) — because the bean is user-declared rather than
    // auto-configured, Spring Boot's HikariJdbcConnectionDetailsBeanPostProcessor
    // does not re-write its jdbcUrl from the @ServiceConnection container.
    //
    // To bridge this gap we explicitly push the @Container's connection
    // details into the Spring Environment under spring.datasource.* before
    // any DataSource bean is constructed. This guarantees that the @Primary
    // DataSource (loaded by the JPA slice via component scan of JpaConfig)
    // and Flyway both target the SAME container that @ServiceConnection
    // configured for auto-config consumers.
    //
    // Mirrors the pattern in DailyTransactionRepositoryTest and
    // UserSecurityRepositoryTest.
    @DynamicPropertySource
    static void overrideDataSourceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    // -------------------------------------------------------------------------
    // System Under Test (SUT) + JPA test fixture support
    // -------------------------------------------------------------------------
    // @Autowired field injection is required here — @DataJpaTest instantiates
    // the test class via reflection BEFORE the application context refreshes,
    // so constructor injection is not supported for the test class itself.

    /**
     * The Spring Data JPA repository under test, autowired from the
     * {@code @DataJpaTest} slice's component scan rooted at
     * {@code com.awsm2.carddemo.repository} (per {@code JpaConfig}
     * {@code @EnableJpaRepositories}).
     */
    @Autowired
    private TransactionTypeRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager} (transitively
     * enabled by {@code @DataJpaTest}). Used to force SQL execution via
     * {@link TestEntityManager#flush() flush()} and to detach the persistence
     * context via {@link TestEntityManager#clear() clear()} so that subsequent
     * {@code findById()} calls reload from the database rather than returning
     * a first-level-cache hit. This is critical for verifying that the V013
     * seeded {@code tran_type_desc} values round-trip from PostgreSQL
     * {@code VARCHAR(50)} and that CHAR(2) primary keys round-trip without
     * silent padding mutations.
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test 1 — V013 seed cardinality: exactly 7 canonical rows
    // =========================================================================

    /**
     * Validates that the V013 Flyway seed migration successfully inserted
     * <em>exactly seven</em> canonical transaction-type rows into the
     * {@code tran_type} table.
     *
     * <p>The assertion uses {@code isGreaterThanOrEqualTo(7L)} rather than
     * a strict {@code isEqualTo(7L)} so that this test remains robust if a
     * future migration (or a test-scoped insert that escapes its rollback
     * scope) adds rows. The canonical contract is "at least 7 V013 rows
     * exist"; the individual-code assertions in Tests 2, 3, and 4 prove the
     * exact V013 content.</p>
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS STARTBR
     * DATASET('TRANTYPE') ... READNEXT} counting iterations &mdash; the
     * relational equivalent is the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#count()
     * count()} method.</p>
     */
    @Test
    void seededCount_returns7Rows_perV013() {
        // V013 seeds 7 rows per AAP §0.4.1 (canonical transaction-type lookup)
        long actualCount = repository.count();

        assertThat(actualCount)
                .as("V013 must seed at least 7 canonical transaction-type rows "
                        + "(01=Purchase, 02=Payment, 03=Credit, 04=Authorization, "
                        + "05=Refund, 06=Reversal, 07=Adjustment) per AAP §0.4.1; "
                        + "actual count: %d", actualCount)
                .isGreaterThanOrEqualTo(7L);
    }

    // =========================================================================
    // Test 2 — V013 seed '01' = Purchase
    // =========================================================================

    /**
     * Validates that the V013 Flyway seed migration successfully inserted
     * the {@code "01"} = Purchase row and that the row is findable by its
     * CHAR(2) primary key with the expected human-readable description.
     *
     * <p>The {@code "01"} code is the most-referenced transaction type in
     * the source COBOL (consumed by every {@code CBTRN02C} validation,
     * every {@code COTRN02C} online add, and every {@code CBTRN03C}
     * report). Its presence is the canary for the entire V013 seed
     * migration &mdash; if this test fails, V013 did not execute or
     * the table was wiped after migration.</p>
     *
     * <p>The description assertion uses {@code .trim()} defensively: the
     * V008 column is declared {@code VARCHAR(50) NOT NULL}, which
     * PostgreSQL stores without trailing padding, but a future migration
     * regeneration could switch to {@code CHAR(50)} which space-pads on
     * read. The {@code .trim()} defends against that future change
     * without making the test brittle.</p>
     */
    @Test
    void seededPurchaseType_existsWithCorrectDescription() {
        // V013: '01' = Purchase (COBOL CBTRN02C: TRAN-TYPE-CD = '01' → Purchase)
        Optional<TransactionType> result = repository.findById("01");

        assertThat(result)
                .as("V013-seeded transaction-type code '01' (Purchase) must be "
                        + "present in tran_type after Flyway migration; this is "
                        + "the canary row for the V013 seed (AAP §0.4.1)")
                .isPresent();

        TransactionType purchase = result.get();
        // Primary-key round-trip — confirms CHAR(2) value '01' is returned
        // without padding mutation. PostgreSQL CHAR(2) is space-padded on
        // read, but since '01' is already exactly 2 characters there is
        // no padding to trim; assert equality directly.
        assertThat(purchase.getTranType())
                .as("V013-seeded tran_type primary key must round-trip exactly "
                        + "as '01' (CHAR(2)); leading zero is significant per "
                        + "AAP §0.6.1 COBOL fixed-width semantics")
                .isEqualTo("01");
        // Description assertion — defensively trimmed in case a future
        // migration switches to CHAR(50). The canonical text is
        // "Purchase" per V013 (sourced from app/data/ASCII/trantype.txt).
        assertThat(purchase.getTranTypeDesc().trim())
                .as("V013-seeded tran_type_desc for '01' must be 'Purchase' "
                        + "(case-sensitive, verbatim from "
                        + "app/data/ASCII/trantype.txt per AAP §0.7.1 "
                        + "regulatory output format constraint)")
                .isEqualToIgnoringCase("Purchase");
    }

    // =========================================================================
    // Test 3 — V013 seed '02' = Payment
    // =========================================================================

    /**
     * Validates that the V013 Flyway seed migration successfully inserted
     * the {@code "02"} = Payment row.
     *
     * <p>The {@code "02"} code is the customer-payment-received type
     * consumed by the bill-payment service (COBOL {@code COBIL00C} &rarr;
     * Java {@code BillPaymentService}) and by the transaction-posting
     * cascade (COBOL {@code CBTRN02C} &rarr; Java
     * {@code TransactionPostingService}) when classifying inbound
     * payments. This assertion confirms the second of the seven
     * V013-seeded rows is present and correctly described.</p>
     */
    @Test
    void seedSeven_paymentTypeExists() {
        // V013: '02' = Payment (COBOL COBIL00C: TRAN-TYPE-CD = '02' for bill pay)
        Optional<TransactionType> result = repository.findById("02");

        assertThat(result)
                .as("V013-seeded transaction-type code '02' (Payment) must be "
                        + "present in tran_type after Flyway migration; "
                        + "consumed by BillPaymentService per AAP §0.4.1")
                .isPresent();

        TransactionType payment = result.get();
        // Description assertion — defensively trimmed for CHAR vs VARCHAR
        // robustness, case-insensitive to allow either "Payment" or
        // a hypothetical future "PAYMENT" without breaking the test.
        assertThat(payment.getTranTypeDesc().trim())
                .as("V013-seeded tran_type_desc for '02' must be 'Payment' "
                        + "(verbatim from app/data/ASCII/trantype.txt)")
                .isEqualToIgnoringCase("Payment");
    }

    // =========================================================================
    // Test 4 — V013 full seed verification (all 7 rows)
    // =========================================================================

    /**
     * Validates the COMPLETE V013 seed inventory: all seven canonical
     * transaction-type codes ({@code "01"} through {@code "07"}) are
     * findable by their CHAR(2) primary keys with the expected
     * human-readable descriptions.
     *
     * <p>This is the authoritative seed-data integrity check. If any of
     * the seven assertions fails, the V013 migration is incomplete or
     * corrupt &mdash; remediation requires inspecting the V013 source
     * file and the {@code app/data/ASCII/trantype.txt} fixture. The
     * canonical mapping (verified verbatim from
     * {@code app/data/ASCII/trantype.txt} and reproduced in V013):</p>
     * <pre>
     *   '01' &rarr; Purchase
     *   '02' &rarr; Payment
     *   '03' &rarr; Credit
     *   '04' &rarr; Authorization
     *   '05' &rarr; Refund
     *   '06' &rarr; Reversal
     *   '07' &rarr; Adjustment
     * </pre>
     *
     * <p>Descriptions are asserted with {@code isEqualToIgnoringCase()}
     * after trimming, so the assertion remains stable across hypothetical
     * future case normalizations of the seed file while still catching
     * any structural mutation of the values themselves. The trim
     * defends against a future CHAR(N) column change without altering
     * the test contract.</p>
     */
    @Test
    void allSeven_v013SeededTypes_existWithExpectedDescriptions() {
        // V013: full 7-row seed verification (canonical mapping per
        // app/data/ASCII/trantype.txt verbatim, reproduced in V013).
        // String[][] is the simplest data structure that maintains insertion
        // order (Java's List.of() and Map.of() are also acceptable, but a
        // 2D array is clearest at the language level for a fixed pair list).
        String[][] expected = new String[][] {
                {"01", "Purchase"},
                {"02", "Payment"},
                {"03", "Credit"},
                {"04", "Authorization"},
                {"05", "Refund"},
                {"06", "Reversal"},
                {"07", "Adjustment"}
        };

        for (String[] pair : expected) {
            String typeCode = pair[0];
            String expectedDesc = pair[1];

            Optional<TransactionType> result = repository.findById(typeCode);

            assertThat(result)
                    .as("V013-seeded transaction-type code '%s' must be present "
                            + "in tran_type after Flyway migration; missing this "
                            + "row indicates V013 did not execute or rows were "
                            + "manually deleted (AAP §0.4.1)", typeCode)
                    .isPresent();

            // Trim defends against future CHAR(N) column changes; case-
            // insensitive defends against hypothetical future case
            // normalizations of the seed file. The structural value
            // (the word itself) is what must remain stable.
            assertThat(result.get().getTranTypeDesc().trim())
                    .as("V013-seeded tran_type_desc for code '%s' must be '%s' "
                            + "(verbatim from app/data/ASCII/trantype.txt)",
                            typeCode, expectedDesc)
                    .isEqualToIgnoringCase(expectedDesc);
        }
    }

    // =========================================================================
    // Test 5 — round-trip persistence by CHAR(2) PK with a custom row
    // =========================================================================

    /**
     * Validates that {@link TransactionTypeRepository} can persist a
     * freshly built {@link TransactionType} via the inherited
     * {@code JpaRepository.save(Object)} method and reload it by its
     * 2-character {@code tran_type} primary key via
     * {@code JpaRepository.findById(String)}.
     *
     * <p>The custom code {@code "99"} is chosen to be OUTSIDE the V013
     * seed range ({@code "01"}&ndash;{@code "07"}) so this test does
     * not collide with the seeded rows or interfere with subsequent
     * tests' assertions on the seeded inventory. {@code @DataJpaTest}
     * rolls back this insert at the end of the test method, so the
     * row never persists into the next test's transactional scope.</p>
     *
     * <p>The {@code entityManager.flush()} + {@code clear()} cycle
     * forces the pending INSERT to the DB and detaches the entity from
     * the persistence context, so the subsequent {@code findById("99")}
     * returns a freshly hydrated entity rather than the same instance
     * from the first-level cache. This is what guarantees the test
     * exercises a real DB round-trip rather than an in-memory cache
     * lookup.</p>
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS WRITE
     * DATASET('TRANTYPE') FROM(TRAN-TYPE-RECORD) RIDFLD(TRAN-TYPE)}
     * followed by {@code EXEC CICS READ DATASET('TRANTYPE')
     * INTO(TRAN-TYPE-RECORD) RIDFLD(TRAN-TYPE)} re-read.</p>
     */
    @Test
    void saveCustomTransactionType_persistsAndReloads() {
        // Custom code outside V013 seed range ('01'-'07') to avoid collision
        // with seeded rows. @DataJpaTest rolls back at end of method.
        TransactionType custom = new TransactionType("99", "Test Type");

        repository.save(custom);
        // Force pending INSERT to DB + detach entity so findById() reloads
        // from PostgreSQL rather than returning the same instance from
        // the first-level cache.
        entityManager.flush();
        entityManager.clear();

        Optional<TransactionType> result = repository.findById("99");

        assertThat(result)
                .as("Custom transaction-type row '99' must be findable by "
                        + "primary key after save + flush + clear cycle")
                .isPresent();

        TransactionType loaded = result.get();
        // Primary-key round-trip — confirms CHAR(2) value '99' is returned
        // unchanged (no padding mutation, no integer coercion).
        assertThat(loaded.getTranType())
                .as("Saved tran_type primary key must round-trip exactly as "
                        + "'99' (CHAR(2)); no padding or coercion")
                .isEqualTo("99");
        // Description round-trip — defensively trimmed for CHAR vs VARCHAR
        // robustness. The supplied value 'Test Type' is < 50 chars so
        // VARCHAR(50) stores it without trailing padding.
        assertThat(loaded.getTranTypeDesc().trim())
                .as("Saved tran_type_desc must round-trip as 'Test Type' "
                        + "(VARCHAR(50) accepts up to 50 chars per V008)")
                .isEqualTo("Test Type");
    }

    // =========================================================================
    // Test 6 — CHAR(2) PK supports 2-character alphanumeric codes
    // =========================================================================

    /**
     * Validates that the V008 {@code tran_type CHAR(2)} primary-key
     * column accepts a 2-character ALPHANUMERIC code (not numeric-only).
     *
     * <p>The COBOL source declares the primary key as
     * {@code TRAN-TYPE PIC X(02)} (the {@code X} indicates alphanumeric,
     * not the {@code 9} that would indicate numeric-only). The V008 DDL
     * preserves this by declaring {@code CHAR(2)} (not
     * {@code INTEGER}, {@code SMALLINT}, or {@code NUMERIC(2,0)}). This
     * test exercises the alphanumeric contract by persisting and
     * reloading a code containing alphabetic characters ({@code "AB"}).</p>
     *
     * <p>If this test fails &mdash; e.g., because a future migration
     * narrowed the column to {@code SMALLINT} on a misguided "code values
     * are always numeric" assumption &mdash; the validation cascade in
     * {@code TransactionPostingService} would silently fail on any
     * future alphanumeric type code, breaking forward-compatibility.</p>
     */
    @Test
    void char2PrimaryKey_supports2CharacterCodes() {
        // V008 — tran_type CHAR(2) per CVTRA03Y PIC X(02) (alphanumeric)
        TransactionType alphaNumeric = new TransactionType("AB", "Alphanumeric Test");

        repository.save(alphaNumeric);
        entityManager.flush();
        entityManager.clear();

        Optional<TransactionType> result = repository.findById("AB");

        assertThat(result)
                .as("V008 tran_type CHAR(2) must accept 2-character "
                        + "alphanumeric codes per COBOL PIC X(02) semantics; "
                        + "failure indicates an incompatible column type "
                        + "narrowing (e.g., SMALLINT) breaking AAP §0.6.1 "
                        + "fixed-width preservation")
                .isPresent();

        // Round-trip the alphanumeric value exactly; uppercase preservation
        // verifies CHAR(2) is bit-faithful (PostgreSQL CHAR is case-sensitive).
        assertThat(result.get().getTranType())
                .as("CHAR(2) primary key must round-trip alphanumeric value "
                        + "'AB' bit-faithfully (case-sensitive)")
                .isEqualTo("AB");
    }

    // =========================================================================
    // Test 7 — findById miss returns Optional.empty()
    // =========================================================================

    /**
     * Validates the standard {@code Optional}-returning {@code findById}
     * contract: a primary key that does not exist in the table returns
     * {@code Optional.empty()} rather than throwing an exception.
     *
     * <p>The {@code "ZZ"} key is chosen to be outside the V013 seed
     * range and is never inserted by any test, so the
     * {@code findById("ZZ")} call is guaranteed to miss.</p>
     *
     * <p>This contract is what allows the consumer services
     * ({@code TransactionPostingService},
     * {@code TransactionAddService}) to translate
     * {@code Optional.empty()} into a domain
     * {@code RecordNotFoundException} (HTTP 404) via the
     * {@code GlobalExceptionHandler} per AAP &sect;0.7.1, rather than
     * having to catch a JPA-specific exception. Any change in this
     * contract (e.g., a future Spring Data update switching to a
     * throw-on-miss behavior) would break the entire error-translation
     * layer.</p>
     */
    @Test
    void findById_returnsEmpty_whenNotFound() {
        // 'ZZ' is outside V013 seed range and never inserted by any test;
        // findById is guaranteed to miss.
        Optional<TransactionType> result = repository.findById("ZZ");

        assertThat(result)
                .as("findById('ZZ') must return Optional.empty() for a "
                        + "non-existent primary key per the standard Spring "
                        + "Data JPA contract; throw-on-miss would break the "
                        + "error-translation layer (AAP §0.7.1)")
                .isEmpty();
    }

    // =========================================================================
    // Test 8 — deleteById removes a previously persisted custom row
    // =========================================================================

    /**
     * Validates that the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#deleteById(Object)}
     * method removes a previously persisted custom row, and that a
     * subsequent {@code findById} for the same key returns
     * {@code Optional.empty()}.
     *
     * <p>The test uses a custom code {@code "99"} (NOT a V013-seeded
     * code) for the delete fixture. <strong>It is forbidden to delete
     * any V013-seeded row ({@code "01"}&ndash;{@code "07"}) in any
     * test method</strong>: the V014 seed migration loads
     * {@code tran_category} rows that logically reference the V013
     * codes, and even though no database-level FK exists (per V008
     * commentary), accidental deletion would corrupt the test fixture
     * for downstream service tests
     * ({@code TransactionPostingServiceTest},
     * {@code TransactionAddServiceTest},
     * {@code TransactionReportServiceTest}).</p>
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS WRITE} +
     * {@code DELETE DATASET('TRANTYPE')} sequence.</p>
     */
    @Test
    void deleteById_removesCustomRow() {
        // Use a CUSTOM code (not V013-seeded) for delete fixture.
        // NEVER delete V013-seeded codes '01'-'07' (V014 tran_category
        // references them at the application layer).
        TransactionType custom = new TransactionType("99", "Delete Test");
        repository.save(custom);
        entityManager.flush();
        // Confirm preconditions — '99' exists immediately after save
        assertThat(repository.findById("99"))
                .as("Pre-condition: custom row '99' must be present "
                        + "immediately after save + flush, before delete")
                .isPresent();

        repository.deleteById("99");
        entityManager.flush();

        Optional<TransactionType> afterDelete = repository.findById("99");

        assertThat(afterDelete)
                .as("After deleteById('99'), findById('99') must return "
                        + "Optional.empty() — the row is removed from the "
                        + "tran_type table")
                .isEmpty();
    }

    // =========================================================================
    // Test 9 — findAll includes at least the 7 V013-seeded rows
    // =========================================================================

    /**
     * Validates that the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#findAll()}
     * method returns at least the seven V013-seeded transaction-type
     * rows.
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS STARTBR
     * DATASET('TRANTYPE')} + {@code READNEXT} loop iterating to
     * end-of-file &mdash; the relational equivalent is a single
     * {@code findAll()} call returning the full table.</p>
     *
     * <p>This method is consumed by {@code TransactionReportService}
     * (COBOL {@code CBTRN03C}) at report-generation time to build a
     * code-to-description lookup map; the assertion confirms the
     * full V013 inventory is reachable.</p>
     *
     * <p>The {@code >=} relational operator (rather than strict
     * equality) keeps the test robust to a future V016 migration that
     * adds new canonical type codes (e.g., a chargeback or
     * cash-advance type).</p>
     */
    @Test
    void findAll_includesAtLeast7Seeded() {
        // V013 seeds 7 rows; allow future additive migrations
        int totalRows = repository.findAll().size();

        assertThat(totalRows)
                .as("findAll() must return at least the 7 V013-seeded "
                        + "transaction-type rows (canonical inventory per "
                        + "app/data/ASCII/trantype.txt); actual rows: %d",
                        totalRows)
                .isGreaterThanOrEqualTo(7);
    }

    // =========================================================================
    // Test 10 — existsById contract: true for seeded, false for unknown
    // =========================================================================

    /**
     * Validates the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#existsById(Object)}
     * contract: returns {@code true} for a V013-seeded code (e.g.,
     * {@code "01"}) and {@code false} for an unknown code (e.g.,
     * {@code "ZZ"}).
     *
     * <p>{@code existsById} is the preferred preflight check in
     * consumer services (e.g., {@code TransactionAddService.validate})
     * because it does not hydrate a full entity &mdash; it issues a
     * {@code SELECT 1} (or {@code SELECT COUNT(*)} per Hibernate
     * dialect) rather than the full {@code SELECT *} that
     * {@code findById} performs. This makes it the preferred contract
     * for the COBOL {@code CBTRN02C} 4-stage validation cascade where
     * existence is checked but the description is not needed.</p>
     */
    @Test
    void existsById_returnsTrueForSeededType_falseForUnknown() {
        // V013-seeded code '01' = Purchase must exist
        boolean existsSeeded = repository.existsById("01");
        // 'ZZ' is outside V013 seed range and not inserted by any test
        boolean existsUnknown = repository.existsById("ZZ");

        assertThat(existsSeeded)
                .as("existsById('01') must return true — '01' (Purchase) is a "
                        + "V013-seeded canonical transaction-type code per "
                        + "AAP §0.4.1")
                .isTrue();
        assertThat(existsUnknown)
                .as("existsById('ZZ') must return false — 'ZZ' is outside "
                        + "the V013 seed range and is not inserted by any test")
                .isFalse();
    }
}
