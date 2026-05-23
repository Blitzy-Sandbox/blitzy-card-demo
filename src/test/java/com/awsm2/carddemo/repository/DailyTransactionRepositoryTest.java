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

import com.awsm2.carddemo.domain.DailyTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
 * {@code @DataJpaTest} slice test for {@link DailyTransactionRepository}.
 *
 * <h2>Source mapping (AAP &sect;0.3.1, &sect;0.4.1)</h2>
 * <ul>
 *   <li><strong>PS file:</strong> {@code AWS.M2.CARDDEMO.DALYTRAN.PS}
 *       (sequential physical-sequential dataset &mdash; <em>not</em> a VSAM
 *       KSDS). In the Java/AWS target it becomes the {@code daily_transactions}
 *       staging table.</li>
 *   <li><strong>COBOL programs:</strong> {@code app/cbl/CBTRN01C.cbl}
 *       (sequential reader / dump utility) and
 *       {@code app/cbl/CBTRN02C.cbl} (4-stage validation cascade and
 *       transaction posting to {@code TRANSACT.VSAM.KSDS}). Both are
 *       preserved frozen as REFERENCE; their behavior is reproduced
 *       in the Java target by {@code DailyTransactionPostingJob}
 *       (Spring Batch) and {@code TransactionPostingService}.</li>
 *   <li><strong>Copybook:</strong> {@code app/cpy/CVTRA06Y.cpy} &mdash;
 *       350-byte fixed-width {@code DALYTRAN-RECORD} layout
 *       (byte-identical to {@code CVTRA05Y.cpy} {@code TRAN-RECORD}
 *       with the {@code DALYTRAN-} field-name prefix substituted for
 *       {@code TRAN-}).</li>
 *   <li><strong>JCL DD allocation:</strong> {@code app/jcl/POSTTRAN.jcl}
 *       ({@code STEP15 EXEC PGM=CBTRN02C; DALYTRAN DD
 *       DSN=AWS.M2.CARDDEMO.DALYTRAN.PS}). Replaced operationally by
 *       AWS Step Functions task state invoking the Spring Batch job
 *       via AWS Batch.</li>
 *   <li><strong>Migration:</strong>
 *       {@code src/main/resources/db/migration/V011__create_daily_transaction.sql}
 *       &mdash; creates the {@code daily_transactions} staging table
 *       (plural &mdash; relational pluralisation convention) plus the
 *       {@code idx_daily_transactions_card_num} secondary index AND
 *       <em>intentionally omits foreign-key constraints</em> per the
 *       staging-table policy (AAP &sect;0.3.1).</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ul>
 *   <li>The custom derived query
 *       {@link DailyTransactionRepository#findByDalytranCardNum(String)}
 *       returns all staging rows for a given PAN and is backed by the
 *       {@code idx_daily_transactions_card_num} index (Tests 2 and 3).</li>
 *   <li>Standard {@link org.springframework.data.jpa.repository.JpaRepository}
 *       methods inherited from Spring Data JPA &mdash; {@code save},
 *       {@code findById}, {@code deleteAll}, {@code count} &mdash;
 *       round-trip {@link DailyTransaction} entities faithfully against
 *       the V011 PostgreSQL schema (Tests 1, 5, 6).</li>
 *   <li>The staging table accepts orphan/unparented rows with NO
 *       foreign-key violation, validating the V011 design decision that
 *       supports the bulk-ingest pattern (S3 &rarr; RDS Glue jobs) per
 *       AAP &sect;0.3.1 (Test 4).</li>
 *   <li>{@code dalytran_amt} ({@code NUMERIC(11, 2)}) preserves
 *       {@link BigDecimal} value <em>and</em> {@code scale = 2} through a
 *       persist-flush-clear-reload cycle, matching the COBOL
 *       {@code DALYTRAN-AMT PIC S9(09)V99} precision invariant per AAP
 *       &sect;0.6.1 (Test 7).</li>
 * </ul>
 *
 * <h2>Container strategy</h2>
 * <p>The test class uses a single static {@code postgres:16-alpine}
 * Testcontainer (PostgreSQL 16 to match RDS production
 * Multi-AZ baseline per AAP &sect;0.5.1, &sect;0.6.2). The
 * {@link ServiceConnection &#64;ServiceConnection} annotation registers
 * the container's JDBC connection details as a {@code JdbcConnectionDetails}
 * bean; the {@link DynamicPropertySource &#64;DynamicPropertySource}
 * method below additionally pushes the URL/credentials/driver into
 * {@code spring.datasource.*} so that any user-defined {@code @Primary}
 * {@link javax.sql.DataSource DataSource} bean discovered by the slice
 * picks up the same container (matches the pattern in
 * {@code CardDemoApplicationTests}). Flyway then applies the full
 * V001&hellip;V015 migration set against the fresh container before
 * any test method runs.</p>
 *
 * <h2>Assertion discipline (AAP &sect;0.6.1, &sect;0.7.1)</h2>
 * <p>All {@link BigDecimal} equality checks use either
 * {@link BigDecimal#compareTo(BigDecimal)} (value-only, scale-insensitive)
 * or AssertJ's {@code usingComparator(BigDecimal::compareTo)} fluent API
 * &mdash; <strong>never</strong> {@link Object#equals(Object)} which
 * compares both value AND scale and would surface false-positive
 * mismatches between {@code "50"} and {@code "50.00"}.</p>
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DailyTransactionRepositoryTest {

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
    // ensuring NUMERIC(11,2) arbitrary-precision arithmetic, TIMESTAMP(6)
    // microsecond resolution, and PostgreSQL-specific DDL semantics are
    // validated against the engine the application will actually run on
    // in production.
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
    // DataSource (if loaded by the JPA slice via component scan of JpaConfig)
    // and Flyway both target the SAME container that @ServiceConnection
    // configured for auto-config consumers.
    //
    // Mirrors the pattern in CardDemoApplicationTests.overrideDataSourceUrl.
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
     * {@code @DataJpaTest} slice's component scan.
     */
    @Autowired
    private DailyTransactionRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager} (transitively
     * enabled by {@code @DataJpaTest}). Used to force SQL execution via
     * {@link TestEntityManager#flush() flush()} and to detach the persistence
     * context via {@link TestEntityManager#clear() clear()} so that subsequent
     * {@code findById()} calls reload from the database rather than returning
     * a first-level-cache hit.
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test fixture builder
    // =========================================================================

    /**
     * Builds a {@link DailyTransaction} entity populated with deterministic
     * default values across all 13 non-{@code FILLER} columns of the
     * {@code DALYTRAN-RECORD} layout (per {@code app/cpy/CVTRA06Y.cpy}). The
     * three parameters expose the variation points used by individual tests:
     *
     * <ul>
     *   <li>{@code id} &mdash; {@code DALYTRAN-ID PIC X(16)} primary key</li>
     *   <li>{@code cardNum} &mdash; {@code DALYTRAN-CARD-NUM PIC X(16)};
     *       the indexed column queried by
     *       {@link DailyTransactionRepository#findByDalytranCardNum(String)}</li>
     *   <li>{@code amt} &mdash; {@code DALYTRAN-AMT PIC S9(09)V99}; the
     *       NUMERIC(11,2) precision-boundary field validated in Test 7</li>
     * </ul>
     *
     * <p>The remaining fields receive PCI-safe (non-real) defaults that
     * mirror the shape of the {@code app/data/ASCII/dailytran.txt} golden
     * fixture so tests can be inspected by SMEs during the parallel-run
     * validation window.</p>
     *
     * @param id      16-char alphanumeric DALYTRAN-ID primary key
     * @param cardNum 16-digit DALYTRAN-CARD-NUM PAN (PCI-DSS test PAN —
     *                NOT a real cardholder number)
     * @param amt     {@link BigDecimal} monetary amount with scale=2
     * @return a fresh, transient (non-persisted) {@link DailyTransaction}
     */
    private DailyTransaction buildDalyTran(String id, String cardNum, BigDecimal amt) {
        DailyTransaction d = new DailyTransaction();
        // VARCHAR(16) — primary key; DALYTRAN-ID PIC X(16)
        d.setDalytranId(id);
        // CHAR(2)  — DALYTRAN-TYPE-CD PIC X(02); e.g., '01' purchase
        d.setDalytranTypeCd("01");
        // INTEGER  — DALYTRAN-CAT-CD PIC 9(04); 1..9999 transaction-category
        d.setDalytranCatCd(5);
        // VARCHAR(10) — DALYTRAN-SOURCE PIC X(10) (left-trimmed for storage)
        d.setDalytranSource("ONLINE");
        // VARCHAR(100) — DALYTRAN-DESC PIC X(100); free-text description
        d.setDalytranDesc("Daily transaction test");
        // NUMERIC(11,2) — DALYTRAN-AMT PIC S9(09)V99; signed monetary amount
        // (per AAP §0.6.1: BigDecimal scale=2, RoundingMode.HALF_EVEN at
        // arithmetic boundaries; never double/float)
        d.setDalytranAmt(amt);
        // BIGINT — DALYTRAN-MERCHANT-ID PIC 9(09); unsigned 9-digit merchant
        d.setDalytranMerchantId(123456789L);
        // VARCHAR(50) — DALYTRAN-MERCHANT-NAME PIC X(50)
        d.setDalytranMerchantName("ACME MERCHANT");
        // VARCHAR(50) — DALYTRAN-MERCHANT-CITY PIC X(50)
        d.setDalytranMerchantCity("NEW YORK");
        // VARCHAR(10) — DALYTRAN-MERCHANT-ZIP PIC X(10)
        d.setDalytranMerchantZip("10001");
        // VARCHAR(16) — DALYTRAN-CARD-NUM PIC X(16); indexed for batch lookup
        d.setDalytranCardNum(cardNum);
        // TIMESTAMP(6) — DALYTRAN-ORIG-TS PIC X(26); upstream origination ts
        // Slight backdating so dalytranProcTs > dalytranOrigTs as expected
        // by downstream batch reconciliation logic.
        d.setDalytranOrigTs(LocalDateTime.now().minusSeconds(1));
        // TIMESTAMP(6) — DALYTRAN-PROC-TS PIC X(26); posting-pipeline ts
        d.setDalytranProcTs(LocalDateTime.now());
        return d;
    }

    // =========================================================================
    // Test 1 — save + findById round-trips a DalyTran by its DALYTRAN-ID
    // =========================================================================

    /**
     * Validates that {@link DailyTransactionRepository} can persist a
     * {@link DailyTransaction} via the inherited
     * {@code JpaRepository.save(Object)} method and reload it by its
     * primary key via {@code JpaRepository.findById(String)}.
     *
     * <p>Replaces the COBOL pattern of {@code WRITE DALYTRAN-RECORD}
     * (sequential write into {@code AWS.M2.CARDDEMO.DALYTRAN.PS}) followed
     * by a positioned re-read &mdash; the relational equivalent is a
     * round-trip through {@code save()} + {@code findById()} that exercises
     * Hibernate INSERT, SQL flush, persistence-context clear, and SELECT
     * BY PK.</p>
     *
     * <p>The {@code dalytranAmt} assertion uses
     * {@link BigDecimal#compareTo(BigDecimal)} (via AssertJ's
     * {@code usingComparator}) rather than {@code equals} so a stored
     * {@code "50.00"} is treated equal to an input {@code "50.00"}
     * regardless of whether the JDBC driver returns scale 2 or 0
     * (per AAP &sect;0.6.1 &sect;0.7.1).</p>
     */
    @Test
    void saveAndFindById_persistsDalyTranByDalytranId() {
        DailyTransaction input = buildDalyTran(
                "DTRAN00000000001",
                "4111111111111111",
                new BigDecimal("50.00"));

        repository.save(input);
        // Force the pending INSERT to the DB and detach the entity so the
        // subsequent findById() returns a freshly hydrated entity (rather
        // than the same instance from the first-level cache).
        entityManager.flush();
        entityManager.clear();

        DailyTransaction loaded = repository.findById("DTRAN00000000001")
                .orElseThrow(() -> new AssertionError(
                        "Expected DailyTransaction with id DTRAN00000000001 to be present"));

        // Identity assertions — verify primary-key round-trip
        assertThat(loaded.getDalytranId()).isEqualTo("DTRAN00000000001");
        assertThat(loaded.getDalytranCardNum()).isEqualTo("4111111111111111");
        // Monetary assertion — AAP §0.6.1: BigDecimal compareTo() never equals()
        assertThat(loaded.getDalytranAmt())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("50.00"));
        // Additional shape assertions — ensure the full record was persisted
        assertThat(loaded.getDalytranTypeCd()).isEqualTo("01");
        assertThat(loaded.getDalytranCatCd()).isEqualTo(5);
        assertThat(loaded.getDalytranMerchantId()).isEqualTo(123456789L);
    }

    // =========================================================================
    // Test 2 — findByDalytranCardNum returns all staging rows for a card
    // =========================================================================

    /**
     * Validates the custom derived query
     * {@link DailyTransactionRepository#findByDalytranCardNum(String)} —
     * persists 3 rows for one card and 1 row for a different card, then
     * asserts that the lookup returns exactly the 3 matching rows.
     *
     * <p>Replaces the COBOL pattern of
     * {@code READ TRANSACT BROWSE BY CARD-NUM} (CICS STARTBR/READNEXT
     * against the {@code TRANSACT.VSAM.AIX} alternate index) &mdash;
     * the relational equivalent is a derived query backed by the
     * {@code idx_daily_transactions_card_num} secondary index declared in
     * V011. Per AAP &sect;0.6.2, VSAM AIX/PATH chains become PostgreSQL
     * secondary indexes.</p>
     */
    @Test
    void findByDalytranCardNum_returnsAllDalyTransForCard() {
        final String targetCard = "4111111111111111";
        final String otherCard = "5500000000000004";

        // V011 idx_daily_transactions_card_num supports this query — three
        // matching rows under unique primary keys plus one decoy row for a
        // different card validate that the index returns only the matches.
        repository.save(buildDalyTran("DTRAN00000000010", targetCard, new BigDecimal("10.00")));
        repository.save(buildDalyTran("DTRAN00000000011", targetCard, new BigDecimal("20.00")));
        repository.save(buildDalyTran("DTRAN00000000012", targetCard, new BigDecimal("30.00")));
        repository.save(buildDalyTran("DTRAN00000000013", otherCard, new BigDecimal("99.99")));

        entityManager.flush();
        entityManager.clear();

        List<DailyTransaction> result = repository.findByDalytranCardNum(targetCard);

        assertThat(result).hasSize(3);
        assertThat(result)
                .extracting(DailyTransaction::getDalytranCardNum)
                .containsOnly(targetCard);
        assertThat(result)
                .extracting(DailyTransaction::getDalytranId)
                .containsExactlyInAnyOrder(
                        "DTRAN00000000010",
                        "DTRAN00000000011",
                        "DTRAN00000000012");
    }

    // =========================================================================
    // Test 3 — findByDalytranCardNum returns empty list for unknown card
    // =========================================================================

    /**
     * Validates that
     * {@link DailyTransactionRepository#findByDalytranCardNum(String)} returns
     * an empty (but non-{@code null}) {@link List} when no staging rows
     * exist for the given card number.
     *
     * <p>Spring Data JPA derived queries return {@code List} (never
     * {@code null}) by contract — an empty result is the Java equivalent of
     * a COBOL {@code FILE-STATUS '23'} (NOTFND) on the
     * {@code TRANSACT.VSAM.AIX} alternate index. Per AAP &sect;0.7.1, the
     * Java target maps COBOL {@code FILE-STATUS '23'} to a typed
     * {@code RecordNotFoundException} for <em>single-record</em> lookups,
     * but multi-row derived queries naturally return an empty collection
     * (no exception is thrown).</p>
     */
    @Test
    void findByDalytranCardNum_emptyForUnknownCard_returnsEmptyList() {
        // No setup — the staging table starts empty for each test class
        // (Flyway baseline) and no rows for the lookup card are inserted.
        // The lookup must return an empty list rather than throwing.
        List<DailyTransaction> result = repository.findByDalytranCardNum("9999999999999999");

        assertThat(result)
                .as("findByDalytranCardNum must return an empty (never null) "
                        + "list when no staging rows match — mirrors COBOL "
                        + "FILE-STATUS '23' NOTFND semantics for browse queries")
                .isNotNull()
                .isEmpty();
    }

    // =========================================================================
    // Test 4 — no FK constraints: orphan rows persist successfully
    // =========================================================================

    /**
     * Validates that the {@code daily_transactions} staging table accepts
     * a row whose {@code dalytran_card_num} value does NOT exist in the
     * {@code cards} table — proving the V011 design decision to omit
     * foreign-key constraints on the staging table.
     *
     * <p>AAP &sect;0.3.1 &mdash; V011 declares NO FK constraints on staging
     * table for ingest performance: the staging table holds <em>unvalidated</em>
     * incoming transactions and a single batch may contain rows for cards that
     * have not yet been ingested into the {@code cards} table. Enforcing a FK
     * would force a strict load ordering and break the bulk-ingest pattern
     * (S3 &rarr; RDS Glue jobs). Validation is enforced in the Java service
     * layer ({@code TransactionPostingService}) preserving COBOL reject codes
     * 100&ndash;109.</p>
     *
     * <p>If a FK constraint were inadvertently introduced into V011, this
     * test would fail with a PostgreSQL constraint violation
     * ({@code DataIntegrityViolationException}) at
     * {@link TestEntityManager#flush()}.</p>
     */
    @Test
    void noForeignKeyConstraints_canPersistWithoutParentEntities_supportsStagingPattern() {
        // Card number does NOT exist in the cards table (V002) — would be a
        // FK violation if dalytran_card_num were a foreign key to cards.card_num
        DailyTransaction orphan = buildDalyTran(
                "DTRAN00000000099",
                "0000000000009999",
                new BigDecimal("75.50"));

        repository.save(orphan);
        // AAP §0.3.1 — V011 declares NO FK constraints on staging table for
        // ingest performance: flush MUST succeed even though the parent card
        // record does not exist. This is the contractual behavior validated
        // here.
        entityManager.flush();
        entityManager.clear();

        // Round-trip the orphan to confirm persistence succeeded
        DailyTransaction loaded = repository.findById("DTRAN00000000099")
                .orElseThrow(() -> new AssertionError(
                        "Orphan DailyTransaction should be persistable to the "
                                + "daily_transactions staging table without a "
                                + "matching cards row (V011 omits FKs)"));

        assertThat(loaded.getDalytranCardNum()).isEqualTo("0000000000009999");
        assertThat(loaded.getDalytranAmt())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("75.50"));
    }

    // =========================================================================
    // Test 5 — deleteAll clears the staging table (CBTRN02C cleanup pattern)
    // =========================================================================

    /**
     * Validates that
     * {@link org.springframework.data.jpa.repository.JpaRepository#deleteAll()}
     * truncates the staging table contents.
     *
     * <p>CBTRN02C clears DALYTRAN after posting to TRANSACT — the COBOL
     * end-of-day cleanup pattern that drops the unprocessed daily-transaction
     * file once its rows have been promoted to the canonical
     * {@code TRANSACT.VSAM.KSDS}. The relational equivalent invoked by
     * {@code DailyTransactionPostingJob} after all rows have been posted is
     * {@code repository.deleteAll()}, which the Spring Batch tasklet runs
     * inside a {@code @Transactional} boundary so a partial cleanup never
     * leaves the staging table in an inconsistent state.</p>
     */
    @Test
    void deleteAll_clearsStagingTable_supportsPostingCleanup() {
        // Persist 5 staging rows — emulates a small end-of-day batch
        for (int i = 1; i <= 5; i++) {
            String id = String.format("DTRAN0000000%04d", i);
            repository.save(buildDalyTran(
                    id,
                    "4111111111111111",
                    new BigDecimal("100.00")));
        }
        entityManager.flush();

        // CBTRN02C clears DALYTRAN after posting to TRANSACT — the relational
        // equivalent is deleteAll() invoked by DailyTransactionPostingJob
        // after the final chunk has been committed to the transactions
        // journal.
        repository.deleteAll();
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.count())
                .as("deleteAll() must leave the staging table empty so the "
                        + "next end-of-day batch starts from a clean slate")
                .isZero();
    }

    // =========================================================================
    // Test 6 — count returns the total staging-row count
    // =========================================================================

    /**
     * Validates that
     * {@link org.springframework.data.jpa.repository.JpaRepository#count()}
     * returns the exact number of rows currently in the staging table.
     *
     * <p>Used operationally by the {@code DailyTransactionPostingJob}
     * pre-flight check (assert non-zero staging rows before launching the
     * 4-stage validation cascade) and by the end-of-day reconciliation
     * report. Replaces the COBOL pattern of {@code PERFORM UNTIL EOF}
     * over the sequential DALYTRAN file with a {@code COUNTER} accumulator
     * &mdash; the relational equivalent is a single
     * {@code SELECT COUNT(*) FROM daily_transactions}.</p>
     */
    @Test
    void count_returnsTotalStagingRows() {
        // Persist 7 staging rows with distinct primary keys and a mix of
        // card numbers so the count reflects the total — not the number of
        // distinct cards.
        repository.save(buildDalyTran("DTRAN00000000301", "4111111111111111", new BigDecimal("1.00")));
        repository.save(buildDalyTran("DTRAN00000000302", "4111111111111111", new BigDecimal("2.00")));
        repository.save(buildDalyTran("DTRAN00000000303", "5500000000000004", new BigDecimal("3.00")));
        repository.save(buildDalyTran("DTRAN00000000304", "5500000000000004", new BigDecimal("4.00")));
        repository.save(buildDalyTran("DTRAN00000000305", "6011111111111117", new BigDecimal("5.00")));
        repository.save(buildDalyTran("DTRAN00000000306", "6011111111111117", new BigDecimal("6.00")));
        repository.save(buildDalyTran("DTRAN00000000307", "3782822463100050", new BigDecimal("7.00")));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.count())
                .as("count() must return the total number of staging rows "
                        + "across all card numbers — 7 rows persisted, 7 expected")
                .isEqualTo(7L);
    }

    // =========================================================================
    // Test 7 — BigDecimal precision round-trip (PIC S9(09)V99 = NUMERIC(11,2))
    // =========================================================================

    /**
     * Validates that the {@code dalytran_amt} column (declared in V011 as
     * {@code NUMERIC(11, 2)}) preserves {@link BigDecimal} value AND
     * {@code scale = 2} through a full persist-flush-clear-reload cycle
     * at the upper boundary of the COBOL {@code PIC S9(09)V99} range
     * ({@code 999999999.99}).
     *
     * <p>AAP &sect;0.6.1 &mdash; NUMERIC(11,2) maps PIC S9(09)V99 exactly:
     * 9 integer digits + 2 fractional digits with banker's-rounding
     * ({@code RoundingMode.HALF_EVEN}) applied at every Java arithmetic
     * boundary. The assertions deliberately use
     * {@link BigDecimal#compareTo(BigDecimal)} (value-only) AND
     * {@link BigDecimal#scale()} (scale-only) <strong>separately</strong>
     * rather than {@link Object#equals(Object) BigDecimal.equals()} (which
     * compares both, surfacing false-positive mismatches like
     * {@code "1000" != "1000.00"}).</p>
     *
     * <p>If the schema were inadvertently changed to a lower precision
     * (e.g., {@code NUMERIC(9, 2)}), the
     * {@link org.springframework.data.jpa.repository.JpaRepository#save save()}
     * call would raise a {@code DataIntegrityViolationException} at flush
     * time. If the scale were changed (e.g., {@code NUMERIC(11, 0)}), the
     * {@code scale()} assertion below would fail. Both branches catch the
     * precision-parity regression early.</p>
     */
    @Test
    void bigDecimalPrecision_storesNumeric11Comma2() {
        // Upper boundary of PIC S9(09)V99 — 9 integer digits, 2 fractional
        // digits, signed positive value. AAP §0.6.1 — NUMERIC(11,2) maps
        // PIC S9(09)V99 exactly: the persisted value must round-trip with
        // value AND scale preserved.
        final BigDecimal boundary = new BigDecimal("999999999.99");

        DailyTransaction input = buildDalyTran(
                "DTRAN00000000901",
                "4111111111111111",
                boundary);

        repository.save(input);
        // Force INSERT to commit at the JDBC layer and detach the entity
        // so the subsequent findById() returns a value reloaded from the
        // DB rather than the in-memory copy passed to save().
        entityManager.flush();
        entityManager.clear();

        DailyTransaction loaded = repository.findById("DTRAN00000000901")
                .orElseThrow(() -> new AssertionError(
                        "Expected DailyTransaction at PIC S9(09)V99 upper boundary "
                                + "(999999999.99) to round-trip through NUMERIC(11,2)"));

        BigDecimal reloadedAmt = loaded.getDalytranAmt();

        // Value assertion — compareTo() is scale-insensitive (NEVER equals())
        // per AAP §0.6.1: BigDecimal.equals() compares both value and scale,
        // surfacing false-positive mismatches between "999999999.99" and
        // "999999999.990" or "999999999.99" and "1.0E+9".
        assertThat(reloadedAmt)
                .as("dalytran_amt must round-trip the upper PIC S9(09)V99 "
                        + "boundary value (999999999.99) unchanged through "
                        + "NUMERIC(11,2) — value comparison via BigDecimal.compareTo")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(boundary);

        // Scale assertion — the V011 NUMERIC(11,2) declaration guarantees
        // scale=2 on the persisted value. If the scale were not preserved
        // (e.g., a misconfigured column type), batch interest-rate
        // calculations downstream would silently produce wrong totals.
        assertThat(reloadedAmt.scale())
                .as("dalytran_amt scale must be exactly 2 after round-trip — "
                        + "NUMERIC(11,2) preserves both value and scale per "
                        + "AAP §0.6.1 BigDecimal/HALF_EVEN arithmetic discipline")
                .isEqualTo(2);
    }
}
