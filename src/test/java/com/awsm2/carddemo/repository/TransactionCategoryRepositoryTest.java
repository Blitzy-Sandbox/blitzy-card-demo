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

import com.awsm2.carddemo.domain.TransactionCategory;
import com.awsm2.carddemo.domain.TransactionCategory.TransactionCategoryId;
import java.util.Optional;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link TransactionCategoryRepository}
 * &mdash; validates the {@code tran_category} lookup table's JPA contract
 * end-to-end against a real PostgreSQL 16 engine running in a Testcontainers
 * Docker container.
 *
 * <h2>Source mapping (AAP &sect;0.3.1, &sect;0.4.1, &sect;0.6.2)</h2>
 * <ul>
 *   <li><strong>COBOL copybook:</strong> {@code app/cpy/CVTRA04Y.cpy} &mdash;
 *       the {@code TRAN-CAT-RECORD} layout (RECLN 60 bytes):
 *       <pre>
 *         05 TRAN-CAT-KEY.                       (composite 6-byte key)
 *            10 TRAN-TYPE-CD       PIC X(02).    -&gt; @EmbeddedId sub-field
 *            10 TRAN-CAT-CD        PIC 9(04).    -&gt; @EmbeddedId sub-field
 *         05 TRAN-CAT-TYPE-DESC    PIC X(50).    -&gt; tranCatTypeDesc (String)
 *         05 FILLER                PIC X(04).    -&gt; OMITTED (padding)
 *       </pre>
 *       The trailing {@code FILLER PIC X(04)} (bytes 57&ndash;60) is omitted
 *       from the JPA mapping &mdash; the source fixture
 *       {@code app/data/ASCII/trancatg.txt} carries the literal {@code "0000"}
 *       in those positions for every row, confirming padding rather than
 *       data.</li>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS} &mdash; {@code KEYS(6 0)},
 *       {@code RECORDSIZE(60 60)}, {@code SHAREOPTIONS(2 3)}, {@code INDEXED},
 *       {@code REC-TOTAL=18} (per {@code app/jcl/TRANCATG.jcl}:L36&ndash;L48
 *       verified against {@code app/catlg/LISTCAT.txt}: {@code KEYLEN=6},
 *       {@code RKP=0}, {@code MAXLRECL=60}, {@code AVGLRECL=60}). The 6-byte
 *       composite key decomposes into the 2-byte {@code TRAN-TYPE-CD} +
 *       4-byte {@code TRAN-CAT-CD} sub-fields whose widths sum exactly to 6
 *       bytes. The COBOL {@code SHAREOPTIONS(2 3)} attribute has no
 *       PostgreSQL equivalent (PostgreSQL handles concurrent reads/writes
 *       natively via MVCC); it is preserved as a comment only.</li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/CBTRN02C.cbl} &mdash; transaction-posting
 *             cascade. Validates each incoming
 *             {@code (DALYTRAN-TRAN-TYPE-CD, DALYTRAN-CAT-CD)} pair
 *             against the {@code TRANCATG} cluster during the 4-stage
 *             validation cascade. Replaced in the Java target by
 *             {@code TransactionPostingService}.</li>
 *         <li>{@code app/cbl/CBTRN03C.cbl} &mdash; transaction-report
 *             generator. Replaced by {@code TransactionReportService}.</li>
 *         <li>{@code app/cbl/COTRN02C.cbl} &mdash; online transaction-add.
 *             Replaced by {@code TransactionAddService}.</li>
 *         <li>{@code app/cbl/CBACT04C.cbl} &mdash; interest calculation
 *             composite-key lookups against {@code disclosure_group}
 *             include the {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} pair as
 *             a reference dimension. Replaced by
 *             {@code InterestCalculationService}.</li>
 *       </ul>
 *   </li>
 *   <li><strong>JCL DD allocation:</strong>
 *       {@code app/jcl/TRANCATG.jcl} &mdash; IDCAMS DEFINE CLUSTER (STEP10)
 *       + IDCAMS REPRO load (STEP15) from {@code TRANCATG.PS}. Replaced
 *       operationally by Flyway migrations V009 (DDL) and V014 (seed).</li>
 *   <li><strong>Migrations:</strong>
 *       {@code src/main/resources/db/migration/V008__create_transaction_type.sql}
 *       (parent {@code tran_type} table; FK target),
 *       {@code src/main/resources/db/migration/V009__create_transaction_category.sql}
 *       (composite PK on
 *       {@code (tran_type_cd CHAR(2), tran_cat_cd INTEGER)} +
 *       {@code tran_cat_type_desc VARCHAR(50) NOT NULL} + FK
 *       {@code fk_tran_category_tran_type} on
 *       {@code tran_type_cd -&gt; tran_type(tran_type) ON DELETE NO ACTION}),
 *       {@code src/main/resources/db/migration/V013__seed_transaction_type.sql}
 *       (seeds 7 canonical {@code tran_type} parent rows
 *       {@code '01'..'07'}), and
 *       {@code src/main/resources/db/migration/V014__seed_transaction_category.sql}
 *       (seeds 18 canonical rows from {@code app/data/ASCII/trancatg.txt}:
 *       5 Purchase categories, 3 Payment categories, 3 Credit categories,
 *       3 Authorization categories, 1 Refund category, 2 Reversal
 *       categories, 1 Adjustment category).</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ul>
 *   <li><strong>V014 seed cardinality:</strong> at least 18 canonical rows
 *       present after Flyway migrations complete (Test 1).</li>
 *   <li><strong>V014 known seed row:</strong> the {@code ('01', 1) =
 *       'Regular Sales Draft'} row is findable by composite primary key
 *       with the expected human-readable description text (Test 2).</li>
 *   <li><strong>Composite primary key uniquely identifies rows:</strong>
 *       two seed rows sharing the same {@code tran_type_cd} but differing
 *       {@code tran_cat_cd} are distinct entities with distinct
 *       descriptions (Test 3) &mdash; exercises the composite-PK
 *       discrimination guaranteed by the V009 DDL.</li>
 *   <li><strong>Round-trip persistence with composite key + FK:</strong>
 *       a freshly built {@link TransactionCategory} with a custom
 *       2-field composite key persists and reloads via the inherited
 *       {@code JpaRepository.save} / {@code findById} cycle, after
 *       seeding a matching parent {@code tran_type} row via native SQL
 *       (Test 4) &mdash; the FK
 *       {@code fk_tran_category_tran_type} requires the parent row to
 *       exist before the child row can be inserted.</li>
 *   <li><strong>{@code @EmbeddedId} composite-key contract:</strong>
 *       {@link TransactionCategoryId#equals(Object) equals} and
 *       {@link TransactionCategoryId#hashCode() hashCode} obey the
 *       value-based identity contract required by JPA &sect;2.4
 *       (Test 5).</li>
 *   <li><strong>{@code findById} miss returns empty:</strong> an unknown
 *       composite key returns {@code Optional.empty()} rather than
 *       throwing (Test 6).</li>
 *   <li><strong>{@code deleteById} contract on composite key:</strong>
 *       a previously persisted custom row can be deleted by its
 *       {@link TransactionCategoryId} and is no longer findable
 *       (Test 7).</li>
 *   <li><strong>{@code findAll} includes all seeded rows:</strong> the
 *       no-arg {@code findAll()} returns at least the 18 V014-seeded
 *       rows (Test 8).</li>
 * </ul>
 *
 * <h2>What this test deliberately does NOT validate</h2>
 * <ul>
 *   <li><strong>Custom finder methods.</strong>
 *       {@link TransactionCategoryRepository} declares <em>no</em> custom
 *       queries &mdash; all access patterns required by consumer services
 *       are satisfied by inherited
 *       {@link org.springframework.data.jpa.repository.JpaRepository}
 *       methods ({@code findById}, {@code findAll}, {@code save},
 *       {@code deleteById}, {@code existsById}, {@code count}). Adding
 *       finders would violate the Minimal Change Clause (AAP
 *       &sect;0.7.3).</li>
 *   <li><strong>Optimistic locking.</strong> {@link TransactionCategory}
 *       has no {@code @Version} column &mdash; this lookup is static
 *       reference data seeded once by V014 and not updated at runtime.
 *       There is no read-modify-write contention to guard against.</li>
 *   <li><strong>Business validation logic.</strong> Rejection of unknown
 *       {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} pairs during a transaction
 *       post (COBOL {@code CBTRN02C} reject codes 100&ndash;109) is the
 *       responsibility of {@code TransactionPostingService} &mdash; not
 *       this repository. This test exercises only the JPA contract:
 *       composite-key lookup, round-trip persistence, FK enforcement,
 *       and the inherited
 *       {@link org.springframework.data.jpa.repository.JpaRepository}
 *       methods.</li>
 *   <li><strong>FK referential cascade behavior.</strong> The V009
 *       {@code ON DELETE NO ACTION} clause on
 *       {@code fk_tran_category_tran_type} would prevent deletion of a
 *       {@code tran_type} row that still has dependent
 *       {@code tran_category} rows. This test does NOT exercise that
 *       reverse-direction cascade (deleting a tran_type) because
 *       deleting any V013-seeded {@code tran_type} row would break
 *       downstream service tests; the cascade behavior is asserted
 *       indirectly by Test 4's successful insert of
 *       {@code ('TT', 9999)} only AFTER seeding the parent
 *       {@code tran_type 'TT'} row via native SQL.</li>
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
 * the fresh container before any test method runs, so V013's 7
 * {@code tran_type} parent rows and V014's 18 {@code tran_category}
 * child rows are queryable in Tests 1, 2, 3, and 8.</p>
 *
 * <h2>Transactional isolation</h2>
 * <p>{@code @DataJpaTest} wraps each test method in a {@code @Transactional}
 * boundary that rolls back at the end of the test. The V013 + V014 seed
 * data (applied by Flyway at context start, BEFORE the first test
 * transaction begins) is therefore COMMITTED and visible to every test
 * method, while any rows persisted by an individual test (e.g., the
 * custom {@code ('TT', 9999)} row in Tests 4 and 7, plus its companion
 * {@code tran_type 'TT'} parent inserted via native SQL) are rolled back
 * at the end of that test and invisible to the others. This isolation
 * guarantee is what allows Test 1's count assertion to remain stable and
 * what allows Tests 4 and 7 to independently set up the same
 * {@code 'TT'} fixture without conflicting on the unique-key
 * constraint.</p>
 *
 * @see TransactionCategoryRepository
 * @see TransactionCategory
 * @see TransactionCategoryId
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionCategoryRepositoryTest {

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
    // target runs PostgreSQL 16, so tests exercise the same SQL engine,
    // ensuring CHAR(2) primary-key padding semantics, INTEGER composite-
    // key behavior, the FK constraint behavior of
    // fk_tran_category_tran_type, and PostgreSQL-specific DDL semantics
    // are validated against the engine the application will actually run
    // on in production.
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
    // Mirrors the pattern in TransactionTypeRepositoryTest,
    // DisclosureGroupRepositoryTest, DailyTransactionRepositoryTest, and
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
     *
     * <p>The repository is the
     * {@code JpaRepository<TransactionCategory, TransactionCategoryId>}
     * interface declared with an empty body per the Minimal Change Clause
     * (AAP &sect;0.7.3) &mdash; all 8 tests exercise the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository}
     * methods only.</p>
     */
    @Autowired
    private TransactionCategoryRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager} (transitively
     * enabled by {@code @DataJpaTest}). Used to:
     * <ul>
     *   <li>force pending SQL execution via
     *       {@link TestEntityManager#flush() flush()} so subsequent
     *       {@code findById()} calls reload from the database rather than
     *       returning a first-level-cache hit;</li>
     *   <li>detach managed entities via
     *       {@link TestEntityManager#clear() clear()} so the
     *       {@code @EmbeddedId} hydration code path actually executes on
     *       reload (rather than returning the same instance from the
     *       persistence context); and</li>
     *   <li>execute native SQL via
     *       {@link TestEntityManager#getEntityManager()} for inserting
     *       the parent {@code tran_type} row required by the V009 FK
     *       constraint {@code fk_tran_category_tran_type} before saving
     *       a custom {@link TransactionCategory} child row (Tests 4 and
     *       7).</li>
     * </ul>
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test 1 — V014 seed cardinality: at least 18 canonical rows
    // =========================================================================

    /**
     * Validates that the V014 Flyway seed migration successfully inserted
     * <em>at least 18</em> canonical transaction-category rows into the
     * {@code tran_category} table.
     *
     * <p>The assertion uses {@code isGreaterThanOrEqualTo(18L)} rather
     * than a strict {@code isEqualTo(18L)} so that this test remains
     * robust if a future migration (or a test-scoped insert that
     * escapes its rollback scope) adds rows. The canonical contract is
     * "at least 18 V014 rows exist" per AAP &sect;0.4.1 (5 Purchase + 3
     * Payment + 3 Credit + 3 Authorization + 1 Refund + 2 Reversal + 1
     * Adjustment = 18) &mdash; the individual-row assertion in Test 2
     * proves the exact V014 content for a known seed row.</p>
     *
     * <p>The corresponding COBOL pattern of {@code EXEC CICS STARTBR
     * DATASET('TRANCATG') ... READNEXT} counting iterations is replaced
     * by the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#count()
     * count()} method &mdash; a single {@code SELECT COUNT(*)} that
     * runs in O(log n) via the PostgreSQL B-tree on the composite
     * primary key.</p>
     */
    @Test
    void seededCount_returns18Rows_perV014() {
        // V014 seeds 18 rows per AAP §0.4.1 (canonical transaction-category
        // lookup: 5 Purchase + 3 Payment + 3 Credit + 3 Authorization +
        // 1 Refund + 2 Reversal + 1 Adjustment = 18)
        long actualCount = repository.count();

        assertThat(actualCount)
                .as("V014 must seed at least 18 canonical transaction-category "
                        + "rows (5 Purchase + 3 Payment + 3 Credit + 3 "
                        + "Authorization + 1 Refund + 2 Reversal + 1 Adjustment "
                        + "= 18) per AAP §0.4.1; actual count: %d", actualCount)
                .isGreaterThanOrEqualTo(18L);
    }

    // =========================================================================
    // Test 2 — V014 known seed row: ('01', 1) = 'Regular Sales Draft'
    // =========================================================================

    /**
     * Validates that the V014 Flyway seed migration successfully inserted
     * the {@code ('01', 1) = 'Regular Sales Draft'} row and that the row
     * is findable by its 2-field composite primary key with a non-null,
     * non-empty human-readable description.
     *
     * <p>The {@code ('01', 1)} key is the canonical first row of V014
     * (line 58 of {@code V014__seed_transaction_category.sql}) and the
     * most-referenced category in the source COBOL (it is the
     * {@code 'Regular Sales Draft'} classification consumed by every
     * {@code CBTRN02C} validation cascade and every
     * {@code CBTRN03C}-generated report). Its presence is the canary for
     * the V014 seed migration &mdash; if this test fails, V014 did not
     * execute or the table was wiped after migration.</p>
     *
     * <p>The description assertion uses {@code .trim()} defensively: the
     * V009 column is declared {@code VARCHAR(50) NOT NULL}, which
     * PostgreSQL stores without trailing padding, but a future migration
     * regeneration could switch to {@code CHAR(50)} which space-pads on
     * read. The {@code .trim()} defends against that future change
     * without making the test brittle.</p>
     *
     * <p>The corresponding COBOL pattern is {@code EXEC CICS READ
     * DATASET('TRANCATG') INTO(TRAN-CAT-RECORD)
     * RIDFLD(TRAN-CAT-KEY)} where the 6-byte composite key is the
     * concatenation of {@code TRAN-TYPE-CD = '01'} and
     * {@code TRAN-CAT-CD = '0001'}.</p>
     */
    @Test
    void seededRowsExist_validateKnownEntry() {
        // V014 seed verification — ('01', 1) = 'Regular Sales Draft'
        // (canonical first row, line 58 of V014__seed_transaction_category.sql)
        TransactionCategoryId id = new TransactionCategoryId("01", 1);

        Optional<TransactionCategory> result = repository.findById(id);

        assertThat(result)
                .as("V014-seeded row ('01', 1) = 'Regular Sales Draft' must "
                        + "be present in tran_category after Flyway migration; "
                        + "this is the canary row for the V014 seed (AAP §0.4.1) "
                        + "and the most-referenced category in the source COBOL "
                        + "(CBTRN02C validation cascade and CBTRN03C report)")
                .isPresent();

        TransactionCategory category = result.get();

        // Composite-key round-trip — confirms CHAR(2) value '01' and
        // INTEGER value 1 are returned without padding mutation or
        // numeric coercion.
        assertThat(category.getId().getTranTypeCd())
                .as("V014-seeded tran_type_cd sub-field must round-trip "
                        + "exactly as '01' (CHAR(2)); leading zero is "
                        + "significant per AAP §0.6.1 COBOL fixed-width "
                        + "semantics")
                .isEqualTo("01");
        assertThat(category.getId().getTranCatCd())
                .as("V014-seeded tran_cat_cd sub-field must round-trip "
                        + "exactly as Integer 1 (INTEGER); COBOL PIC 9(04) "
                        + "'0001' becomes Java Integer 1 in PostgreSQL per "
                        + "AAP §0.6.1")
                .isEqualTo(1);

        // Description assertion — non-null + non-blank (per the
        // VARCHAR(50) NOT NULL constraint and the V014 seed contract).
        // Defensively trimmed in case a future migration switches to
        // CHAR(50). The canonical text is 'Regular Sales Draft' per V014
        // (sourced verbatim from app/data/ASCII/trancatg.txt).
        assertThat(category.getTranCatTypeDesc())
                .as("V014-seeded tran_cat_type_desc must be non-null per "
                        + "the VARCHAR(50) NOT NULL constraint declared in V009")
                .isNotNull();
        assertThat(category.getTranCatTypeDesc().trim())
                .as("V014-seeded tran_cat_type_desc for ('01', 1) must be "
                        + "'Regular Sales Draft' (verbatim from "
                        + "app/data/ASCII/trancatg.txt per AAP §0.7.1 "
                        + "regulatory output format constraint); the trim "
                        + "defends against a future CHAR(N) column change")
                .isNotEmpty()
                .isEqualTo("Regular Sales Draft");
    }

    // =========================================================================
    // Test 3 — composite primary key uniquely identifies rows sharing type_cd
    // =========================================================================

    /**
     * Validates that the V009 composite {@code PRIMARY KEY (tran_type_cd,
     * tran_cat_cd)} uniquely identifies a row: two rows sharing the
     * same {@code tran_type_cd} but with differing {@code tran_cat_cd}
     * are distinct entities with distinct descriptions.
     *
     * <p>The test fetches two seeded rows that share {@code tran_type_cd
     * = '01'} (the Purchase type) but differ in {@code tran_cat_cd}:</p>
     * <ul>
     *   <li>{@code ('01', 1)} &rarr; {@code 'Regular Sales Draft'}
     *       (V014 line 58);</li>
     *   <li>{@code ('01', 2)} &rarr; {@code 'Regular Cash Advance'}
     *       (V014 line 59).</li>
     * </ul>
     * <p>and asserts they are both present, with different descriptions.
     * This exercises the composite-PK discrimination semantics: if the
     * V009 DDL had mistakenly declared a single-column PK on
     * {@code tran_type_cd} alone, V014 would have failed at row 2 with a
     * duplicate-key violation. The fact that 5 rows with
     * {@code tran_type_cd = '01'} all coexist (lines 58&ndash;62 of V014)
     * is itself confirmation that the composite PK is correctly
     * declared. This test asserts the runtime consequence: two seeded
     * rows with the same leading sub-field and different trailing
     * sub-field are addressable as distinct entities.</p>
     *
     * <p>The corresponding COBOL pattern uses {@code EXEC CICS READ
     * DATASET('TRANCATG') RIDFLD(TRAN-CAT-KEY)} with different 6-byte
     * composite keys {@code '010001'} and {@code '010002'} to retrieve
     * distinct records.</p>
     */
    @Test
    void compositeKeyDistinguishesRows() {
        // Composite (type_cd, cat_cd) uniquely identifies a category.
        // ('01', 1) and ('01', 2) share tran_type_cd but differ in
        // tran_cat_cd. Both are V014-seeded (lines 58, 59 of V014).
        TransactionCategoryId idSalesDraft = new TransactionCategoryId("01", 1);
        TransactionCategoryId idCashAdvance = new TransactionCategoryId("01", 2);

        Optional<TransactionCategory> resultSalesDraft = repository.findById(idSalesDraft);
        Optional<TransactionCategory> resultCashAdvance = repository.findById(idCashAdvance);

        assertThat(resultSalesDraft)
                .as("V014-seeded row ('01', 1) = 'Regular Sales Draft' must "
                        + "be present in tran_category after Flyway migration "
                        + "(AAP §0.4.1)")
                .isPresent();
        assertThat(resultCashAdvance)
                .as("V014-seeded row ('01', 2) = 'Regular Cash Advance' must "
                        + "be present in tran_category after Flyway migration "
                        + "(AAP §0.4.1)")
                .isPresent();

        // Distinct rows — descriptions must differ. If the composite PK
        // were mis-declared as single-column on tran_type_cd alone, V014
        // would have failed at row 2 with a duplicate-key violation, but
        // since both rows coexist we additionally assert their
        // descriptions are different to confirm that they are
        // independently addressable entities (not aliases of the same
        // row).
        String descSalesDraft = resultSalesDraft.get().getTranCatTypeDesc().trim();
        String descCashAdvance = resultCashAdvance.get().getTranCatTypeDesc().trim();

        assertThat(descSalesDraft)
                .as("('01', 1) description must round-trip as "
                        + "'Regular Sales Draft' (V014 line 58, verbatim from "
                        + "app/data/ASCII/trancatg.txt per AAP §0.7.1)")
                .isEqualTo("Regular Sales Draft");
        assertThat(descCashAdvance)
                .as("('01', 2) description must round-trip as "
                        + "'Regular Cash Advance' (V014 line 59, verbatim from "
                        + "app/data/ASCII/trancatg.txt per AAP §0.7.1)")
                .isEqualTo("Regular Cash Advance");
        assertThat(descSalesDraft)
                .as("Composite key ('01', 1) and ('01', 2) must address "
                        + "DISTINCT rows with DIFFERENT descriptions; failure "
                        + "would indicate the V009 composite primary key on "
                        + "(tran_type_cd, tran_cat_cd) is mis-declared as a "
                        + "single-column PK or that findById is ignoring the "
                        + "second sub-field of the @EmbeddedId")
                .isNotEqualTo(descCashAdvance);
    }

    // =========================================================================
    // Test 4 — round-trip persistence with custom composite key + FK
    // =========================================================================

    /**
     * Validates that {@link TransactionCategoryRepository} can persist a
     * freshly built {@link TransactionCategory} via the inherited
     * {@code JpaRepository.save(Object)} method and reload it by its
     * 2-field {@link TransactionCategoryId} composite primary key via
     * {@code JpaRepository.findById(Object)} &mdash; <em>after</em> first
     * seeding a matching parent {@code tran_type} row to satisfy the V009
     * {@code fk_tran_category_tran_type ON DELETE NO ACTION} foreign-key
     * constraint.
     *
     * <p>The custom composite key {@code ("TT", 9999)} is chosen to be
     * OUTSIDE the V014 seed ranges so this test does not collide with
     * the seeded rows or interfere with subsequent tests' assertions on
     * the seeded inventory:</p>
     * <ul>
     *   <li>{@code "TT"} is not one of the V013-seeded codes
     *       {@code "01"}&ndash;{@code "07"};</li>
     *   <li>{@code 9999} is the COBOL {@code PIC 9(04)} maximum and is
     *       not present in V014's {@code 1}&ndash;{@code 5} range.</li>
     * </ul>
     * <p>{@code @DataJpaTest} rolls back BOTH the parent
     * {@code tran_type 'TT'} insert AND the child
     * {@code tran_category ('TT', 9999)} insert at the end of the test
     * method, so neither row persists into the next test's transactional
     * scope.</p>
     *
     * <p>The FK constraint {@code fk_tran_category_tran_type} requires
     * the parent row to be present at INSERT time. The native-SQL
     * INSERT into {@code tran_type} (via
     * {@link TestEntityManager#getEntityManager()}) seeds the parent;
     * the child INSERT then succeeds. If the parent were omitted, the
     * child INSERT would fail with a foreign-key-violation error,
     * confirming the constraint is correctly enforced at the database
     * tier per AAP &sect;0.6.2. The participation of the native-SQL
     * INSERT in the same {@code @Transactional} boundary as the JPA
     * repository {@code save()} call is what makes the FK satisfiable
     * within a single test method &mdash; both INSERTs land in the same
     * transaction, so the FK can see the parent row when validating the
     * child.</p>
     *
     * <p>The {@code entityManager.flush()} + {@code clear()} cycle
     * forces the pending INSERT to the DB and detaches the entity from
     * the persistence context, so the subsequent {@code findById(new
     * TransactionCategoryId("TT", 9999))} returns a freshly hydrated
     * entity rather than the same instance from the first-level cache.
     * This is what guarantees the test exercises a real DB round-trip
     * rather than an in-memory cache lookup &mdash; critical for
     * verifying that the {@code @EmbeddedId} composite-key hydration
     * code path actually executes.</p>
     *
     * <p>The corresponding COBOL pattern is {@code EXEC CICS WRITE
     * DATASET('TRANTYPE') FROM(TRAN-TYPE-RECORD) RIDFLD(TRAN-TYPE)}
     * (parent insert) followed by {@code EXEC CICS WRITE
     * DATASET('TRANCATG') FROM(TRAN-CAT-RECORD) RIDFLD(TRAN-CAT-KEY)}
     * (child insert) and finally {@code EXEC CICS READ
     * DATASET('TRANCATG') INTO(TRAN-CAT-RECORD)
     * RIDFLD(TRAN-CAT-KEY)} (re-read).</p>
     */
    @Test
    void saveCustomCategory_persistsAndReloads() {
        // Seed parent tran_type row 'TT' via native SQL — required by the
        // V009 FK fk_tran_category_tran_type ON DELETE NO ACTION. Both
        // INSERTs participate in the same @Transactional boundary, so the
        // FK can see the parent row when validating the child INSERT
        // below. The native-SQL INSERT is necessary because TransactionType
        // is in the sibling repository package and not directly autowired
        // in this @DataJpaTest slice — using native SQL keeps the test
        // self-contained and consistent with the FK-enforcement layer
        // being exercised.
        entityManager.getEntityManager()
                .createNativeQuery(
                        "INSERT INTO tran_type (tran_type, tran_type_desc) "
                                + "VALUES ('TT', 'Test Type')")
                .executeUpdate();

        // Custom composite key OUTSIDE V014 seed range to avoid collision.
        // ('TT', 9999) — 'TT' is not '01'-'07' (V013 codes); 9999 is the
        // COBOL PIC 9(04) maximum (not in V014's 1-5 range).
        TransactionCategory custom = new TransactionCategory(
                "TT", 9999, "Test Category Description");

        repository.save(custom);
        // Force pending INSERT to DB + detach entity so findById reloads
        // from PostgreSQL rather than returning the same instance from
        // the first-level cache. This is what makes this a real round-trip
        // test of the @EmbeddedId hydration code path.
        entityManager.flush();
        entityManager.clear();

        Optional<TransactionCategory> result =
                repository.findById(new TransactionCategoryId("TT", 9999));

        assertThat(result)
                .as("Custom transaction-category row ('TT', 9999) must be "
                        + "findable by 2-field composite key after save + "
                        + "flush + clear cycle; failure indicates either "
                        + "(a) the @EmbeddedId hydration code path is broken, "
                        + "or (b) the V009 FK fk_tran_category_tran_type "
                        + "rejected the child INSERT because the parent "
                        + "tran_type 'TT' row was not visible in the same "
                        + "transaction")
                .isPresent();

        TransactionCategory loaded = result.get();

        // Composite-key sub-field round-trip — confirms CHAR(2) value
        // 'TT' and INTEGER value 9999 are returned unchanged (no padding
        // mutation, no integer coercion).
        assertThat(loaded.getId().getTranTypeCd())
                .as("Saved tran_type_cd sub-field must round-trip exactly "
                        + "as 'TT' (CHAR(2)); no padding or coercion")
                .isEqualTo("TT");
        assertThat(loaded.getId().getTranCatCd())
                .as("Saved tran_cat_cd sub-field must round-trip exactly "
                        + "as Integer 9999 (INTEGER); COBOL PIC 9(04) "
                        + "max value preserved per AAP §0.6.1")
                .isEqualTo(9999);

        // Description round-trip — defensively trimmed for CHAR vs VARCHAR
        // robustness. The supplied value 'Test Category Description' is
        // < 50 chars so VARCHAR(50) stores it without trailing padding.
        assertThat(loaded.getTranCatTypeDesc().trim())
                .as("Saved tran_cat_type_desc must round-trip as "
                        + "'Test Category Description' (VARCHAR(50) accepts "
                        + "up to 50 chars per V009)")
                .isEqualTo("Test Category Description");
    }

    // =========================================================================
    // Test 5 — @EmbeddedId composite-key equals/hashCode contract
    // =========================================================================

    /**
     * Validates that the {@link TransactionCategoryId} composite-key class
     * obeys the value-based identity contract required by JPA &sect;2.4
     * (Jakarta Persistence specification): two
     * {@code TransactionCategoryId} instances with identical field values
     * must be {@link Object#equals(Object) equal} AND have identical
     * {@link Object#hashCode() hashCode}; an instance with any differing
     * sub-field must be non-equal.
     *
     * <p>This contract is what allows the JPA persistence context to
     * use {@code TransactionCategoryId} as a {@link java.util.HashMap}
     * key in its identity-tracking machinery, and what allows
     * {@code @DataJpaTest} test code to construct fresh
     * {@code TransactionCategoryId} instances for each {@code findById}
     * call without worrying about reference equality. Without this
     * contract, JPA would create duplicate managed-entity references
     * for the same composite key &mdash; a class of subtle inconsistency
     * that breaks the "one entity per identity per persistence context"
     * invariant.</p>
     *
     * <p>The test builds three {@code TransactionCategoryId} instances:</p>
     * <ul>
     *   <li>{@code idA = ("01", 1)}: the canonical reference;</li>
     *   <li>{@code idB = ("01", 1)}: built independently from
     *       {@code idA} with identical field values &mdash; must be
     *       value-equal;</li>
     *   <li>{@code idC = ("01", 2)}: identical to {@code idA} except the
     *       second sub-field (catCd) differs &mdash; must be NOT
     *       value-equal.</li>
     * </ul>
     *
     * <p>The {@code idA != idB} reference check is implicit
     * (different constructor invocations produce different object
     * references); the test does NOT use {@code ==} for equality.</p>
     *
     * <p>The corresponding COBOL pattern is the implicit value-based
     * equality of two {@code TRAN-CAT-KEY} group-level items whose
     * sub-field bytes match; the Java target preserves this via the
     * {@code equals()} / {@code hashCode()} contract on
     * {@link TransactionCategoryId}.</p>
     */
    @Test
    void compositeKeyId_equalsAndHashCode() {
        // Two independent instances with IDENTICAL field values; must be
        // value-equal and hash-equal per JPA §2.4 composite-key contract.
        TransactionCategoryId idA = new TransactionCategoryId("01", 1);
        TransactionCategoryId idB = new TransactionCategoryId("01", 1);

        // Third instance with the SAME type_cd but DIFFERENT cat_cd; must
        // NOT be value-equal. If composite-key equals() ignored the
        // tranCatCd sub-field, the JPA persistence context would conflate
        // distinct ('01', 1) and ('01', 2) entries.
        TransactionCategoryId idC = new TransactionCategoryId("01", 2);

        // Value equality — must hold for two independently-constructed
        // composite keys with identical field values per JPA §2.4.
        assertThat(idA)
                .as("Two TransactionCategoryId instances with identical "
                        + "(tranTypeCd, tranCatCd) field values must be "
                        + "value-equal per the JPA composite-key contract "
                        + "(JPA §2.4); failure indicates a missing or "
                        + "incorrect equals() implementation in "
                        + "TransactionCategoryId")
                .isEqualTo(idB);

        // Hash-code consistency — equals contract requires
        // a.equals(b) == true => a.hashCode() == b.hashCode().
        // This is what allows the JPA persistence context to use
        // composite-key instances as HashMap keys.
        assertThat(idA.hashCode())
                .as("Hashcode of two value-equal TransactionCategoryId "
                        + "instances must be identical; violation breaks the "
                        + "JPA identity-tracking HashMap and corrupts the "
                        + "persistence context per JPA §2.4")
                .isEqualTo(idB.hashCode());

        // Negative case — a differing tranCatCd must make the composite
        // key non-equal. If this assertion fails, equals() is ignoring
        // the tranCatCd sub-field, which would silently merge logically
        // distinct ('01', 1) and ('01', 2) rows in the persistence
        // context.
        assertThat(idA)
                .as("Two TransactionCategoryId instances differing only in "
                        + "tranCatCd (1 vs 2) must NOT be value-equal; "
                        + "failure indicates equals() ignores the tranCatCd "
                        + "sub-field, which would silently merge distinct "
                        + "rows in the JPA persistence context per JPA §2.4")
                .isNotEqualTo(idC);
    }

    // =========================================================================
    // Test 6 — findById miss returns Optional.empty()
    // =========================================================================

    /**
     * Validates the standard {@code Optional}-returning {@code findById}
     * contract: a composite primary key that does not exist in the
     * table returns {@code Optional.empty()} rather than throwing an
     * exception.
     *
     * <p>The composite key {@code ("ZZ", 9999)} is chosen to be entirely
     * outside the V014 seed ranges:</p>
     * <ul>
     *   <li>{@code "ZZ"} is not one of the V013-seeded codes
     *       {@code "01"}&ndash;{@code "07"};</li>
     *   <li>{@code 9999} is not one of the V014-seeded
     *       {@code 1}&ndash;{@code 5} range.</li>
     * </ul>
     * <p>so the {@code findById} call is guaranteed to miss.</p>
     *
     * <p>This contract is what allows the consumer services
     * ({@code TransactionPostingService},
     * {@code TransactionAddService},
     * {@code TransactionReportService}) to translate
     * {@code Optional.empty()} into a domain
     * {@code RecordNotFoundException} (HTTP 404) or into the COBOL
     * {@code CBTRN02C} reject-code path (codes 100&ndash;109) via the
     * {@code GlobalExceptionHandler} per AAP &sect;0.7.1, rather than
     * having to catch a JPA-specific exception. Any change in this
     * contract (e.g., a future Spring Data update switching to a
     * throw-on-miss behavior) would break the entire error-translation
     * layer and the 4-stage validation cascade in
     * {@code TransactionPostingService}.</p>
     *
     * <p>The corresponding COBOL pattern is {@code EXEC CICS READ
     * DATASET('TRANCATG') INTO(TRAN-CAT-RECORD)
     * RIDFLD(TRAN-CAT-KEY)} returning {@code FILE STATUS '23'} (NOTFND)
     * &mdash; a routine, non-error control-flow signal that the
     * application handles by branching to its
     * {@code REJECT-RECORD} paragraph.</p>
     */
    @Test
    void findByCompositeId_emptyWhenNotFound() {
        // ('ZZ', 9999) is entirely outside V014 seed ranges:
        //   - 'ZZ' is not '01'-'07' (V013 codes)
        //   - 9999 is not 1-5 (V014 cat codes)
        // findById is guaranteed to miss.
        Optional<TransactionCategory> result =
                repository.findById(new TransactionCategoryId("ZZ", 9999));

        assertThat(result)
                .as("findById on unknown composite key ('ZZ', 9999) must "
                        + "return Optional.empty() for a non-existent "
                        + "composite primary key per the standard Spring Data "
                        + "JPA contract; throw-on-miss would break the "
                        + "error-translation layer (AAP §0.7.1) and the COBOL "
                        + "CBTRN02C FILE STATUS '23' (NOTFND) reject branch "
                        + "(reject codes 100-109)")
                .isEmpty();
    }

    // =========================================================================
    // Test 7 — deleteById removes a previously persisted custom row
    // =========================================================================

    /**
     * Validates that the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#deleteById(Object)}
     * method removes a previously persisted custom row, and that a
     * subsequent {@code findById} for the same composite key returns
     * {@code Optional.empty()}.
     *
     * <p>The test uses a custom composite key {@code ("TT", 9999)} for
     * the delete fixture, mirroring the same composite key as Test 4 but
     * isolated by {@code @DataJpaTest}'s per-method rollback boundary so
     * the two tests do not interfere with each other.
     * <strong>It is FORBIDDEN to delete any V014-seeded row (the 18
     * canonical {@code ('01', 1)}&hellip;{@code ('07', 1)} rows) in any
     * test method</strong>: those rows are the canonical reference data
     * that {@code TransactionPostingServiceTest},
     * {@code TransactionAddServiceTest},
     * {@code TransactionReportServiceTest}, and
     * {@code InterestCalculationServiceTest} depend on. Accidental
     * deletion would corrupt downstream service test fixtures.</p>
     *
     * <p>Although {@code @DataJpaTest} rolls back at the end of each
     * test, a delete-then-rollback sequence cannot un-delete a V014-
     * seeded row if the rollback fails or if the test is run with
     * {@code @Commit} for diagnostics. Confining all destructive
     * operations to custom (non-seeded) composite keys is therefore
     * defense-in-depth.</p>
     *
     * <p>Like Test 4, this test first seeds the parent
     * {@code tran_type 'TT'} row via native SQL to satisfy the V009 FK
     * {@code fk_tran_category_tran_type ON DELETE NO ACTION}, then
     * inserts the child {@code ('TT', 9999)} row, flushes, deletes by
     * composite key, flushes again, and asserts the row is gone. Both
     * the parent INSERT and the child INSERT are rolled back at the end
     * of the test along with the DELETE itself, so the test cleanly
     * tears itself down.</p>
     *
     * <p>The corresponding COBOL pattern is {@code EXEC CICS WRITE
     * DATASET('TRANCATG') FROM(TRAN-CAT-RECORD)} (insert) followed by
     * {@code EXEC CICS DELETE DATASET('TRANCATG')
     * RIDFLD(TRAN-CAT-KEY)} (delete by composite key).</p>
     */
    @Test
    void deleteById_removesByCompositeKey_forCustomRow() {
        // Seed parent tran_type 'TT' via native SQL — required by V009 FK.
        // Same pattern as Test 4 but isolated by @DataJpaTest per-method
        // rollback so this test's 'TT' parent does not collide with
        // Test 4's 'TT' parent.
        entityManager.getEntityManager()
                .createNativeQuery(
                        "INSERT INTO tran_type (tran_type, tran_type_desc) "
                                + "VALUES ('TT', 'Test Type')")
                .executeUpdate();

        // Use a CUSTOM composite key (not V014-seeded) for the delete
        // fixture. NEVER delete V014-seeded rows (any of the 18 canonical
        // rows in tran_type codes '01'-'07' with cat codes 1-5).
        TransactionCategory custom = new TransactionCategory(
                "TT", 9999, "Test Category Description");
        repository.save(custom);
        entityManager.flush();

        TransactionCategoryId key = new TransactionCategoryId("TT", 9999);

        // Confirm preconditions — the custom row exists immediately after
        // save + flush; if this fails, the delete-then-find assertion below
        // would be meaningless (we'd be testing whether deleteById removes
        // a row that was never persisted).
        assertThat(repository.findById(key))
                .as("Pre-condition: custom row ('TT', 9999) must be present "
                        + "immediately after save + flush, before delete is "
                        + "exercised")
                .isPresent();

        repository.deleteById(key);
        entityManager.flush();

        Optional<TransactionCategory> afterDelete = repository.findById(key);

        assertThat(afterDelete)
                .as("After deleteById on composite key ('TT', 9999), "
                        + "findById must return Optional.empty() — the row is "
                        + "removed from the tran_category table by composite "
                        + "key (replaces COBOL EXEC CICS DELETE "
                        + "DATASET('TRANCATG') RIDFLD(TRAN-CAT-KEY))")
                .isEmpty();
    }

    // =========================================================================
    // Test 8 — findAll includes at least the 18 V014-seeded rows
    // =========================================================================

    /**
     * Validates that the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#findAll()}
     * method returns at least the eighteen V014-seeded transaction-
     * category rows.
     *
     * <p>The corresponding COBOL pattern is {@code EXEC CICS STARTBR
     * DATASET('TRANCATG')} + {@code READNEXT} loop iterating to
     * end-of-file &mdash; the relational equivalent is a single
     * {@code findAll()} call returning the full table.</p>
     *
     * <p>This method is consumed by {@code TransactionReportService}
     * (COBOL {@code CBTRN03C}) at report-generation time to build a
     * composite-key-to-description lookup map; the assertion confirms
     * the full V014 inventory is reachable. It is also consumed by
     * {@code InterestCalculationService} (COBOL {@code CBACT04C}) and
     * by {@code ValidationLookupService} for the
     * {@code (transaction-type, category)} composite-key validation
     * cache.</p>
     *
     * <p>The {@code >=} relational operator (rather than strict
     * equality) keeps the test robust to a future V016 migration that
     * adds new canonical category codes (e.g., a new chargeback or
     * dispute category).</p>
     */
    @Test
    void findAll_includesAtLeast18Seeded() {
        // V014 seeds 18 rows; allow future additive migrations
        int totalRows = repository.findAll().size();

        assertThat(totalRows)
                .as("findAll() must return at least the 18 V014-seeded "
                        + "transaction-category rows (canonical inventory "
                        + "per app/data/ASCII/trancatg.txt: 5 Purchase + 3 "
                        + "Payment + 3 Credit + 3 Authorization + 1 Refund "
                        + "+ 2 Reversal + 1 Adjustment = 18); actual rows: %d",
                        totalRows)
                .isGreaterThanOrEqualTo(18);
    }
}

