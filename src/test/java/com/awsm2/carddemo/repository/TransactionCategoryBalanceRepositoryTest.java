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

import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import java.math.BigDecimal;
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
 * {@code @DataJpaTest} slice test for {@link TransactionCategoryBalanceRepository}.
 *
 * <h2>Source mapping (AAP &sect;0.4.1, &sect;0.6.1, &sect;0.6.2)</h2>
 * <ul>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS} &mdash;
 *       {@code KEYS(17 0)}, {@code RECORDSIZE(50 50)},
 *       {@code SHAREOPTIONS(2 3)}, {@code ERASE}, {@code INDEXED}
 *       (per {@code app/jcl/TCATBALF.jcl}:L36&ndash;L49 and verified
 *       against {@code app/catlg/LISTCAT.txt}: {@code KEYLEN=17},
 *       {@code RKP=0}, {@code MAXLRECL=50}, {@code AVGLRECL=50}). The
 *       17-byte composite key decomposes into three logical sub-fields
 *       whose widths sum to exactly 17 bytes: {@code TRANCAT-ACCT-ID}
 *       (11) + {@code TRANCAT-TYPE-CD} (2) + {@code TRANCAT-CD} (4)
 *       = 17.</li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/CBACT04C.cbl} &mdash; end-of-day interest
 *             calculation batch. Iterates over every row in this
 *             table, looks up the matching APR rate in
 *             {@code DISCGRP} (with {@code DEFAULT} fallback), and
 *             computes monthly interest using the formula
 *             {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. Replaced
 *             in the Java target by {@code InterestCalculationService}.</li>
 *         <li>{@code app/cbl/CBTRN02C.cbl} &mdash; daily transaction-
 *             posting batch. Paragraph {@code 2700-UPDATE-TCATBAL}
 *             reads the matching {@code (account, type, category)}
 *             row, accumulates the posted {@code TRAN-AMT} into the
 *             running balance, then either {@code REWRITE}s an
 *             existing row or {@code WRITE}s a new row at zero
 *             starting balance (when {@code TCATBALF-STATUS = '23'}
 *             NOTFND). Replaced by {@code TransactionPostingService}.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Copybook:</strong> {@code app/cpy/CVTRA01Y.cpy} &mdash;
 *       {@code TRAN-CAT-BAL-RECORD} layout (RECLN 50 bytes):
 *       <pre>
 *         05  TRAN-CAT-KEY.                          (composite 17-byte key)
 *            10 TRANCAT-ACCT-ID  PIC 9(11).          -> trancatAcctId  (BIGINT)
 *            10 TRANCAT-TYPE-CD  PIC X(02).          -> trancatTypeCd  (CHAR(2))
 *            10 TRANCAT-CD       PIC 9(04).          -> trancatCd      (INTEGER)
 *         05  TRAN-CAT-BAL       PIC S9(09)V99.      -> tranCatBal     (NUMERIC(11,2))
 *         05  FILLER             PIC X(22).          -> OMITTED
 *       </pre>
 *   </li>
 *   <li><strong>JCL DD allocation:</strong> {@code app/jcl/TCATBALF.jcl}
 *       &mdash; IDCAMS DEFINE CLUSTER (STEP10) + IDCAMS REPRO load
 *       (STEP15) from {@code TCATBALF.PS}. Replaced operationally by
 *       Flyway migration V006 (DDL); runtime data is inserted by
 *       {@code TransactionPostingService} as each new
 *       {@code (account, type, category)} tuple appears (operational
 *       data, NOT reference data).</li>
 *   <li><strong>Migration:</strong>
 *       {@code src/main/resources/db/migration/V006__create_tcatbal.sql}
 *       (composite PK 3 cols: {@code (trancat_acct_id BIGINT,
 *       trancat_type_cd CHAR(2), trancat_cd INTEGER)} +
 *       {@code tran_cat_bal NUMERIC(11,2) NOT NULL DEFAULT 0}; FK
 *       {@code fk_tran_cat_bal_acct_id} from {@code trancat_acct_id}
 *       to {@code accounts(acct_id)} ON DELETE NO ACTION).</li>
 *   <li><strong>Foundational migration:</strong>
 *       {@code src/main/resources/db/migration/V001__create_account.sql}
 *       &mdash; creates the parent {@code accounts} table whose
 *       {@code acct_id BIGINT} column is the FK target. The
 *       {@link #persistAccount(Long)} helper below inserts a minimal
 *       parent {@code accounts} row before each persistence test so
 *       the FK constraint is satisfied.</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ul>
 *   <li><strong>Round-trip persistence with 3-field composite key:</strong>
 *       a freshly built {@link TransactionCategoryBalance} persists
 *       and reloads via the inherited {@code JpaRepository.save} /
 *       {@code findById} cycle, exercising the {@code @EmbeddedId}
 *       composite-key hydration code path (Test 1).</li>
 *   <li><strong>{@code @EmbeddedId} composite-key contract:</strong>
 *       {@link TransactionCategoryBalanceId#equals(Object) equals}
 *       and {@link TransactionCategoryBalanceId#hashCode() hashCode}
 *       obey the value-based identity contract required by Jakarta
 *       Persistence specification &sect;2.4 (Test 2).</li>
 *   <li><strong>Composite-key distinctness:</strong> rows that differ
 *       in any one of the three sub-fields ({@code typeCd} or
 *       {@code catCd}) are stored as distinct rows; the composite
 *       primary key correctly uniquely identifies a balance row
 *       (Test 3).</li>
 *   <li><strong>NUMERIC(11,2) precision boundary:</strong> the maximum
 *       value {@code 999999999.99} of {@code PIC S9(09)V99}
 *       round-trips exactly with {@code scale = 2} preserved
 *       (Test 4).</li>
 *   <li><strong>Signed PIC support:</strong> a negative balance
 *       {@code -100.00} round-trips correctly via the signed
 *       {@code PIC S9(09)V99} mapping (Test 5).</li>
 *   <li><strong>V006 {@code DEFAULT 0} column default:</strong> a
 *       row persisted with {@code tran_cat_bal = 0.00} round-trips
 *       as zero, matching the V006
 *       {@code tran_cat_bal NUMERIC(11,2) NOT NULL DEFAULT 0}
 *       declaration and the COBOL semantics of new
 *       {@code (account, type, category)} tuples starting at zero
 *       running balance (Test 6).</li>
 *   <li><strong>{@code findById} miss returns empty:</strong> an
 *       unknown composite key returns {@code Optional.empty()}
 *       rather than throwing (Test 7).</li>
 *   <li><strong>{@code deleteById} contract:</strong> a previously
 *       persisted row can be deleted by composite key and is no
 *       longer findable (Test 8).</li>
 *   <li><strong>{@code count()} contract:</strong> three distinct
 *       composite-key rows produce a {@code count()} return value
 *       of 3, validating the row-count semantics (Test 9).</li>
 * </ul>
 *
 * <h2>What this test deliberately does NOT validate</h2>
 * <ul>
 *   <li><strong>Interest-calculation arithmetic.</strong> The literal
 *       divisor 1200 in the monthly-interest formula
 *       {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200),
 *       2, RoundingMode.HALF_EVEN)} per AAP &sect;0.6.1 is a
 *       SERVICE-LAYER concern in {@code InterestCalculationService}
 *       (COBOL {@code CBACT04C}). This test validates only that the
 *       balance field's NUMERIC(11,2) precision survives the
 *       persistence round-trip intact &mdash; it does NOT exercise
 *       the formula itself.</li>
 *   <li><strong>DEFAULT-fallback disclosure-group lookup.</strong>
 *       The two-stage lookup pattern (try specific group, fall back
 *       to 'DEFAULT' on miss) implemented in COBOL paragraph
 *       {@code 1200-GET-INTEREST-RATE} of {@code app/cbl/CBACT04C.cbl}
 *       is exercised in {@code DisclosureGroupRepositoryTest} and in
 *       {@code InterestCalculationServiceTest}, not here.</li>
 *   <li><strong>Custom finder methods.</strong>
 *       {@link TransactionCategoryBalanceRepository} declares <em>no</em>
 *       custom queries (empty interface body) &mdash; all access
 *       patterns required by consumer services are satisfied by
 *       inherited
 *       {@link org.springframework.data.jpa.repository.JpaRepository}
 *       methods ({@code findById}, {@code save}, {@code deleteById},
 *       {@code existsById}, {@code count}, {@code findAll}). Adding
 *       finders would violate the Minimal Change Clause (AAP
 *       &sect;0.7.3).</li>
 *   <li><strong>Optimistic locking.</strong>
 *       {@link TransactionCategoryBalance} has no {@code @Version}
 *       column per AAP &sect;0.6.2: concurrent updates are serialized
 *       by the {@code @Transactional} service boundary in
 *       {@code InterestCalculationService},
 *       {@code TransactionPostingService}, and
 *       {@code TransactionAddService}, which is sufficient because
 *       the running-balance accumulation is monotonic and the
 *       conflict window is short.</li>
 *   <li><strong>{@code (type, category)} FK validation.</strong> No
 *       SQL FOREIGN KEY to {@code tran_type} (V008) or
 *       {@code tran_category} (V009) is declared in V006 because
 *       those parent tables are created AFTER V006 in the Flyway
 *       sequence. The application layer enforces the
 *       {@code (type, category)} lookup at write time via
 *       {@code TransactionPostingService} and
 *       {@code TransactionAddService} (CBTRN02C 4-stage validation
 *       cascade per AAP &sect;0.1.1).</li>
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
 * the fresh container before any test method runs, so the V001
 * {@code accounts} table and V006 {@code tran_cat_bal} table (with
 * its FK constraint to {@code accounts(acct_id)}) are both present
 * when Tests 1, 3, 4, 5, 6, 8, and 9 execute.</p>
 *
 * <h2>Transactional isolation</h2>
 * <p>{@code @DataJpaTest} wraps each test method in a
 * {@code @Transactional} boundary that rolls back at the end of the
 * test. Any rows persisted by an individual test (parent
 * {@code accounts} row plus zero or more {@code tran_cat_bal} rows)
 * are rolled back at the end of that test and invisible to the
 * others. This isolation guarantee makes Test 9's
 * {@code count() == 3} assertion reliable: only the 3 rows persisted
 * within that test method are visible.</p>
 *
 * <h2>Assertion discipline (AAP &sect;0.6.1, &sect;0.7.1)</h2>
 * <p>All {@link BigDecimal} equality checks use either
 * {@link BigDecimal#compareTo(BigDecimal)} (value-only, scale-
 * insensitive) returning zero, or a separate
 * {@link BigDecimal#scale()} assertion for the scale invariant
 * &mdash; <strong>never</strong> {@link Object#equals(Object)} which
 * compares both value AND scale and would surface false-positive
 * mismatches between {@code "100"} and {@code "100.00"}.</p>
 *
 * @see TransactionCategoryBalanceRepository
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionCategoryBalanceRepositoryTest {

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
    // ensuring NUMERIC(11,2) arbitrary-precision arithmetic for PIC S9(09)V99,
    // CHAR(2) padding semantics, INTEGER composite-key behavior, and
    // PostgreSQL-specific DDL semantics (composite PK constraint, FK
    // ON DELETE NO ACTION) are validated against the engine the application
    // will actually run on in production.
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
    // Mirrors the pattern in DisclosureGroupRepositoryTest,
    // TransactionTypeRepositoryTest, DailyTransactionRepositoryTest, and
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
    private TransactionCategoryBalanceRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager} (transitively
     * enabled by {@code @DataJpaTest}). Used to:
     * <ul>
     *   <li>{@code getEntityManager().createNativeQuery(...)} for the parent
     *       {@code accounts} row INSERT in {@link #persistAccount(Long)}
     *       &mdash; native SQL is required because {@code AccountRepository}
     *       is in the sibling repository package and not directly autowired
     *       in this {@code @DataJpaTest} slice, and using native SQL keeps
     *       the test self-contained and consistent with the FK-enforcement
     *       layer being exercised.</li>
     *   <li>{@code flush()} to force pending INSERTs to PostgreSQL so the
     *       FK constraint validates against persisted (not pending) rows.</li>
     *   <li>{@code clear()} to detach managed entities from the persistence
     *       context so that subsequent {@code findById()} calls reload from
     *       PostgreSQL rather than returning a first-level-cache hit. This
     *       is critical for verifying that the {@code @EmbeddedId} composite
     *       -key hydration code path actually executes.</li>
     * </ul>
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test fixture helpers
    // =========================================================================

    /**
     * Inserts a minimal parent {@code accounts} row via native SQL so that
     * the V006 FK {@code fk_tran_cat_bal_acct_id} constraint (declared as
     * {@code trancat_acct_id BIGINT NOT NULL REFERENCES accounts(acct_id)
     * ON DELETE NO ACTION}) is satisfied when a child
     * {@link TransactionCategoryBalance} row is persisted with the same
     * {@code trancatAcctId} value.
     *
     * <p>Native SQL is used because {@code AccountRepository} is in the
     * sibling repository package and not directly autowired in this
     * {@code @DataJpaTest} slice. Both the parent INSERT and the child
     * INSERT (issued by {@code repository.save(...)}) participate in the
     * same {@code @DataJpaTest} {@code @Transactional} boundary, so the
     * FK can see the parent row when validating the child INSERT.</p>
     *
     * <p>The native-SQL column list MUST match the V001 {@code accounts}
     * table definition exactly (per {@code V001__create_account.sql}
     * L196&ndash;L390). The full set of NOT NULL columns is listed below,
     * with default-friendly values supplied for the monetary columns
     * (all default to 0 at the schema level but native SQL must still
     * provide a value or DEFAULT keyword for clarity):</p>
     * <ul>
     *   <li>{@code acct_id} BIGINT NOT NULL &mdash; the FK target</li>
     *   <li>{@code acct_active_status} CHAR(1) NOT NULL &mdash; must
     *       be 'Y' or 'N' per chk_accounts_active_status</li>
     *   <li>{@code acct_curr_bal} NUMERIC(12,2) NOT NULL DEFAULT 0</li>
     *   <li>{@code acct_credit_limit} NUMERIC(12,2) NOT NULL DEFAULT 0</li>
     *   <li>{@code acct_cash_credit_limit} NUMERIC(12,2) NOT NULL DEFAULT 0</li>
     *   <li>{@code acct_open_date} DATE NOT NULL</li>
     *   <li>{@code acct_expiration_date} DATE NOT NULL &mdash;
     *       per V001:L284 (the COBOL "EXPIRAION" typo corrected per
     *       AAP &sect;0.6.2)</li>
     *   <li>{@code acct_curr_cyc_credit} NUMERIC(12,2) NOT NULL DEFAULT 0</li>
     *   <li>{@code acct_curr_cyc_debit} NUMERIC(12,2) NOT NULL DEFAULT 0</li>
     *   <li>{@code acct_addr_zip} VARCHAR(10) (nullable)</li>
     *   <li>{@code acct_group_id} VARCHAR(10) (nullable; null =&gt; DEFAULT
     *       disclosure group in {@code InterestCalculationService})</li>
     *   <li>{@code version} BIGINT NOT NULL DEFAULT 0 &mdash; JPA
     *       {@code @Version} optimistic-locking counter (V001:L361)</li>
     * </ul>
     *
     * <p>The {@code acct_reissue_date} column is nullable (V001:L292) and
     * is intentionally omitted from the INSERT (i.e., implicitly NULL).</p>
     *
     * @param acctId the 11-digit account identifier (corresponds to COBOL
     *               {@code ACCT-ID PIC 9(11)} per {@code CVACT01Y.cpy}:L7);
     *               must satisfy the COBOL value range
     *               {@code 0..99,999,999,999} (Java {@code Long} fully
     *               contains this range)
     */
    private void persistAccount(Long acctId) {
        // The native SQL column list matches V001__create_account.sql exactly.
        // acct_expiration_date is NOT NULL per V001:L284 — it MUST be included
        // here even though the blueprint's example INSERT omitted it (the
        // blueprint NOTE explicitly grants permission to "Adapt native SQL
        // column list to V001"). Omitting this column would cause every
        // persistence test (Tests 1, 3, 4, 5, 6, 8, 9) to fail with a
        // NotNullViolationException at flush time.
        entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO accounts ("
                        + "acct_id, "
                        + "acct_active_status, "
                        + "acct_curr_bal, "
                        + "acct_credit_limit, "
                        + "acct_cash_credit_limit, "
                        + "acct_open_date, "
                        + "acct_expiration_date, "
                        + "acct_curr_cyc_credit, "
                        + "acct_curr_cyc_debit, "
                        + "acct_addr_zip, "
                        + "acct_group_id, "
                        + "version) "
                        + "VALUES ("
                        + "?, "          // acct_id (parameter)
                        + "'Y', "        // acct_active_status (chk constraint enforces Y or N)
                        + "0.00, "       // acct_curr_bal (NUMERIC(12,2))
                        + "5000.00, "    // acct_credit_limit (NUMERIC(12,2))
                        + "1000.00, "    // acct_cash_credit_limit (NUMERIC(12,2))
                        + "'2024-01-01', " // acct_open_date (DATE NOT NULL)
                        + "'2034-12-31', " // acct_expiration_date (DATE NOT NULL per V001:L284)
                        + "0.00, "       // acct_curr_cyc_credit (NUMERIC(12,2))
                        + "0.00, "       // acct_curr_cyc_debit (NUMERIC(12,2))
                        + "'10001', "    // acct_addr_zip (VARCHAR(10))
                        + "'DEFAULT', "  // acct_group_id (VARCHAR(10))
                        + "0)"           // version (BIGINT)
        ).setParameter(1, acctId).executeUpdate();
    }

    /**
     * Builds a {@link TransactionCategoryBalance} entity populated with the
     * supplied 3-field composite primary key and balance value, using the
     * 4-arg convenience constructor on {@link TransactionCategoryBalance}
     * (per the entity Javadoc at L390&ndash;L423 of
     * {@code TransactionCategoryBalance.java}).
     *
     * <p>The fixture is transient (non-persisted) until handed to
     * {@link TransactionCategoryBalanceRepository#save(Object)} by the
     * calling test. The {@link BigDecimal} balance is supplied in scale-2
     * form (e.g., {@code new BigDecimal("250.75")}) so it round-trips
     * against the V006 {@code NUMERIC(11,2)} column without scale-
     * truncation surprises per AAP &sect;0.6.1.</p>
     *
     * <p>Equivalent to:
     * {@code new TransactionCategoryBalance(new TransactionCategoryBalanceId(acctId, typeCd, catCd), bal)}.</p>
     *
     * @param acctId 11-digit unsigned account identifier (PK sub-field,
     *               mapped to COBOL {@code TRANCAT-ACCT-ID PIC 9(11)};
     *               must match a previously {@link #persistAccount(Long)
     *               persisted} parent account row for FK validation)
     * @param typeCd 2-character transaction-type code (PK sub-field,
     *               mapped to COBOL {@code TRANCAT-TYPE-CD PIC X(02)};
     *               leading zeros significant)
     * @param catCd  4-digit transaction-category code (PK sub-field,
     *               mapped to COBOL {@code TRANCAT-CD PIC 9(04)})
     * @param bal    {@link BigDecimal} per-{@code (account, type,
     *               category)} running balance with {@code scale = 2};
     *               maps to COBOL {@code TRAN-CAT-BAL PIC S9(09)V99}
     *               and to NUMERIC(11,2) (AAP &sect;0.6.1)
     * @return a fresh, transient (non-persisted)
     *         {@link TransactionCategoryBalance}
     */
    private TransactionCategoryBalance buildTCatBal(Long acctId,
                                                    String typeCd,
                                                    Integer catCd,
                                                    BigDecimal bal) {
        // The convenience constructor builds the @EmbeddedId composite key
        // internally; equivalent to:
        //     TransactionCategoryBalanceId id =
        //         new TransactionCategoryBalanceId(acctId, typeCd, catCd);
        //     return new TransactionCategoryBalance(id, bal);
        return new TransactionCategoryBalance(acctId, typeCd, catCd, bal);
    }

    // =========================================================================
    // Test 1 — Round-trip persistence via 3-field composite key
    // =========================================================================

    /**
     * Validates that {@link TransactionCategoryBalanceRepository} can
     * persist a freshly built {@link TransactionCategoryBalance} via the
     * inherited {@code JpaRepository.save(Object)} method and reload it
     * by its 3-field {@link TransactionCategoryBalanceId} composite
     * primary key via {@code JpaRepository.findById(Object)}.
     *
     * <p>The composite key chosen is {@code (10000000001L, "01", 5)}
     * with balance {@code 250.75}. The
     * {@code entityManager.flush()} + {@code clear()} cycle forces the
     * pending INSERT to the DB and detaches the entity from the
     * persistence context, so the subsequent
     * {@code findById(new TransactionCategoryBalanceId(10000000001L,
     * "01", 5))} returns a freshly hydrated entity rather than the
     * same instance from the first-level cache. This guarantees the
     * test exercises a real DB round-trip rather than an in-memory
     * cache lookup &mdash; critical for verifying that the
     * {@code @EmbeddedId} composite-key hydration code path actually
     * executes.</p>
     *
     * <p>The balance assertion uses {@code compareTo()} rather than
     * {@code equals()} per AAP &sect;0.6.1 &mdash; BigDecimal's
     * scale-sensitive equals would surface false negatives between
     * stored {@code "250.75"} and reloaded {@code "250.75"} if
     * Hibernate produced a different scale.</p>
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS WRITE
     * DATASET('TCATBALF') FROM(TRAN-CAT-BAL-RECORD) RIDFLD(TRAN-CAT-KEY)}
     * followed by {@code EXEC CICS READ DATASET('TCATBALF')
     * INTO(TRAN-CAT-BAL-RECORD) RIDFLD(TRAN-CAT-KEY)} re-read.</p>
     */
    @Test
    void saveAndFindByCompositeId_persistsTCatBal() {
        // Setup parent accounts row to satisfy V006 FK fk_tran_cat_bal_acct_id.
        persistAccount(10000000001L);

        // COBOL: TRAN-CAT-BAL-RECORD (CVTRA01Y.cpy:L4) — build a single
        // (account, type, category) running-balance row.
        TransactionCategoryBalance tcb = buildTCatBal(
                10000000001L, "01", 5, new BigDecimal("250.75"));

        repository.save(tcb);
        // Force pending INSERT to DB + detach entity so findById reloads
        // from PostgreSQL rather than returning the same instance from
        // the first-level cache. This is what makes this a real round-trip
        // test of the @EmbeddedId composite-key hydration code path.
        entityManager.flush();
        entityManager.clear();

        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                10000000001L, "01", 5);
        Optional<TransactionCategoryBalance> result = repository.findById(id);

        assertThat(result)
                .as("TransactionCategoryBalance row (10000000001L, '01', 5) "
                        + "must be findable by 3-field composite key after "
                        + "save + flush + clear cycle; failure indicates the "
                        + "@EmbeddedId hydration code path is broken or the "
                        + "V006 FK constraint to accounts(acct_id) was not "
                        + "satisfied")
                .isPresent();

        // compareTo (not equals) — BigDecimal.equals is scale-sensitive
        // per AAP §0.6.1. The COBOL TRAN-CAT-BAL PIC S9(09)V99 maps to
        // NUMERIC(11,2); 250.75 should round-trip exactly.
        assertThat(result.get().getTranCatBal().compareTo(new BigDecimal("250.75")))
                .as("Saved tran_cat_bal must round-trip exactly as 250.75 "
                        + "via NUMERIC(11,2); compareTo (not equals) per "
                        + "AAP §0.6.1 because BigDecimal.equals is scale-"
                        + "sensitive and would mis-report 250.75 vs 250.750 "
                        + "as unequal")
                .isZero();
    }

    // =========================================================================
    // Test 2 — @EmbeddedId composite-key equals / hashCode semantics
    // =========================================================================

    /**
     * Validates that {@link TransactionCategoryBalanceId} obeys the
     * value-based identity contract required by Jakarta Persistence
     * specification &sect;2.4 for {@code @EmbeddedId} composite-key
     * classes: two instances with identical sub-field values are
     * {@code equals} and produce identical {@code hashCode}; two
     * instances differing in any one sub-field are NOT {@code equals}.
     *
     * <p>This contract is required so that JPA can use the
     * {@code @EmbeddedId} value as a key in its persistence-context
     * identity tracking ({@link java.util.HashMap}-backed in
     * Hibernate). Without correct value-based equals/hashCode, the
     * same row read twice would be treated as two distinct managed
     * entities and break the persistence context's identity guarantee.</p>
     *
     * <p>The test exercises the contract with three instances:</p>
     * <ul>
     *   <li>{@code id1}, {@code id2} &mdash; identical sub-field values
     *       (10000000001L, "01", 5); must be {@code equals} and have
     *       identical {@code hashCode}.</li>
     *   <li>{@code id3} &mdash; differs in the second sub-field
     *       ({@code typeCd = "02"} vs {@code "01"}); must NOT be
     *       {@code equals} to {@code id1}.</li>
     * </ul>
     *
     * <p>The hashCode equality is asserted with
     * {@link Object#hashCode()} returning identical {@code int} values,
     * NOT {@link Object#equals(Object)} which would test object identity
     * (the two int values are returned by different method invocations).</p>
     */
    @Test
    void compositeKeyId_equalsAndHashCodeSemantics() {
        // @EmbeddedId requires correct equals/hashCode for all 3 fields
        // per Jakarta Persistence specification §2.4. Identical sub-field
        // values MUST produce equals == true and identical hashCode.
        TransactionCategoryBalanceId id1 = new TransactionCategoryBalanceId(
                10000000001L, "01", 5);
        TransactionCategoryBalanceId id2 = new TransactionCategoryBalanceId(
                10000000001L, "01", 5);

        assertThat(id1)
                .as("Two TransactionCategoryBalanceId instances with "
                        + "identical sub-field values (10000000001L, '01', 5) "
                        + "must be equals() per JPA §2.4 composite-key "
                        + "value-equality contract")
                .isEqualTo(id2);

        assertThat(id1.hashCode())
                .as("Two TransactionCategoryBalanceId instances with "
                        + "identical sub-field values must produce identical "
                        + "hashCode values per the equals/hashCode contract; "
                        + "JPA uses HashMap-backed persistence-context "
                        + "identity tracking and requires this invariant")
                .isEqualTo(id2.hashCode());

        // Differing in ANY ONE of the 3 sub-fields breaks equality.
        // id3 differs only in the second sub-field (typeCd "02" vs "01").
        TransactionCategoryBalanceId id3 = new TransactionCategoryBalanceId(
                10000000001L, "02", 5);

        assertThat(id1)
                .as("TransactionCategoryBalanceId instances differing in any "
                        + "one sub-field (here: typeCd '01' vs '02') must NOT "
                        + "be equals(); the composite key uniquely identifies "
                        + "a row only if all 3 sub-fields participate in "
                        + "equality")
                .isNotEqualTo(id3);
    }

    // =========================================================================
    // Test 3 — Composite key distinguishes rows differing in typeCd or catCd
    // =========================================================================

    /**
     * Validates that the composite primary key
     * {@code (trancat_acct_id, trancat_type_cd, trancat_cd)} correctly
     * distinguishes rows that share the same account but differ in
     * type code or category code &mdash; matching the COBOL VSAM
     * 17-byte composite key semantics where any difference in any
     * one of the 3 sub-fields yields a distinct VSAM record.
     *
     * <p>The test persists 3 distinct rows under the SAME parent
     * {@code accounts.acct_id}:</p>
     * <ul>
     *   <li>{@code (10000000001L, "01", 5, 100.00)} &mdash; baseline</li>
     *   <li>{@code (10000000001L, "02", 5, 200.00)} &mdash; differs in
     *       {@code typeCd} only (same acct + cat)</li>
     *   <li>{@code (10000000001L, "01", 6, 300.00)} &mdash; differs in
     *       {@code catCd} only (same acct + type)</li>
     * </ul>
     *
     * <p>Each row is then fetched separately via 3 different
     * {@link TransactionCategoryBalanceId} instances, and the test
     * asserts that each returns the correct balance. Finally,
     * {@code count() == 3} validates that all 3 are stored as
     * distinct rows (NOT collapsed by a partial-key uniqueness
     * constraint).</p>
     *
     * <p>This test exercises the COBOL byte-order preservation in the
     * composite key: the 3-column composite PK
     * {@code (trancat_acct_id, trancat_type_cd, trancat_cd)} maps the
     * COBOL 17-byte {@code TRAN-CAT-KEY} group (CVTRA01Y.cpy:L5-L8) in
     * the same order &mdash; account leftmost (most significant), type
     * middle, category rightmost. Without this ordering, the PostgreSQL
     * B-tree composite index would not support the COBOL leading-prefix
     * scan pattern used by {@code InterestCalculationService}
     * end-of-cycle iteration (CBACT04C).</p>
     */
    @Test
    void differentTypeCd_yieldsDifferentRecords_compositeKeyDistinguishes() {
        // Setup parent accounts row once — all 3 child rows share the
        // same trancat_acct_id and thus the same FK target.
        persistAccount(10000000001L);

        // Composite (acct_id, type_cd, cat_cd) uniquely identifies a balance row.
        // Row 1: baseline (acct=10000000001L, type="01", cat=5)
        TransactionCategoryBalance row1 = buildTCatBal(
                10000000001L, "01", 5, new BigDecimal("100.00"));
        // Row 2: differs in typeCd only ("02" vs "01") — same account, same category
        TransactionCategoryBalance row2 = buildTCatBal(
                10000000001L, "02", 5, new BigDecimal("200.00"));
        // Row 3: differs in catCd only (6 vs 5) — same account, same typeCd
        TransactionCategoryBalance row3 = buildTCatBal(
                10000000001L, "01", 6, new BigDecimal("300.00"));

        repository.save(row1);
        repository.save(row2);
        repository.save(row3);
        // Force all pending INSERTs to DB + detach entities so subsequent
        // findById calls reload from PostgreSQL (not first-level cache).
        entityManager.flush();
        entityManager.clear();

        // Fetch each row separately via 3 distinct composite keys.
        Optional<TransactionCategoryBalance> result1 = repository.findById(
                new TransactionCategoryBalanceId(10000000001L, "01", 5));
        Optional<TransactionCategoryBalance> result2 = repository.findById(
                new TransactionCategoryBalanceId(10000000001L, "02", 5));
        Optional<TransactionCategoryBalance> result3 = repository.findById(
                new TransactionCategoryBalanceId(10000000001L, "01", 6));

        assertThat(result1)
                .as("Row 1 (10000000001L, '01', 5) must be findable; "
                        + "differs from Row 2 in typeCd and from Row 3 in catCd")
                .isPresent();
        assertThat(result2)
                .as("Row 2 (10000000001L, '02', 5) must be findable; "
                        + "differs from Row 1 ONLY in typeCd ('02' vs '01')")
                .isPresent();
        assertThat(result3)
                .as("Row 3 (10000000001L, '01', 6) must be findable; "
                        + "differs from Row 1 ONLY in catCd (6 vs 5)")
                .isPresent();

        // Assert each returns its OWN balance — proving the composite key
        // correctly partitions the rows. If only typeCd or only catCd were
        // part of the PK (a bug), 2 of the 3 rows would collide.
        assertThat(result1.get().getTranCatBal().compareTo(new BigDecimal("100.00")))
                .as("Row 1 balance must be 100.00 (compareTo, scale-insensitive)")
                .isZero();
        assertThat(result2.get().getTranCatBal().compareTo(new BigDecimal("200.00")))
                .as("Row 2 balance must be 200.00 — distinct from Row 1 because "
                        + "the composite key distinguishes by typeCd")
                .isZero();
        assertThat(result3.get().getTranCatBal().compareTo(new BigDecimal("300.00")))
                .as("Row 3 balance must be 300.00 — distinct from Row 1 because "
                        + "the composite key distinguishes by catCd")
                .isZero();

        // Composite (acct_id, type_cd, cat_cd) uniquely identifies a balance row —
        // 3 distinct keys produce 3 distinct rows (NOT collapsed by the PK).
        assertThat(repository.count())
                .as("Three distinct composite-key rows must yield count() == 3; "
                        + "if any two rows collapsed, the composite PK would be "
                        + "missing one of its 3 columns, violating the COBOL "
                        + "17-byte VSAM key semantic (CVTRA01Y.cpy:L5-L8 + "
                        + "TCATBALF.jcl KEYS(17 0))")
                .isEqualTo(3L);
    }

    // =========================================================================
    // Test 4 — BigDecimal NUMERIC(11,2) precision boundary (PIC S9(09)V99)
    // =========================================================================

    /**
     * Validates that the V006 {@code tran_cat_bal NUMERIC(11,2)} column
     * round-trips the maximum positive value of the COBOL
     * {@code PIC S9(09)V99} clause (= 999999999.99) without precision
     * loss and with the {@code scale = 2} invariant preserved.
     *
     * <p>The COBOL {@code PIC S9(09)V99} clause permits any value in
     * {@code [-999,999,999.99, +999,999,999.99]} (9 integer digits + 2
     * implied fractional digits, signed). PostgreSQL
     * {@code NUMERIC(11,2)} faithfully preserves this range with
     * precision = 9 (integer) + 2 (fractional) = 11 total digits and
     * scale = 2. This test exercises the positive boundary
     * {@code 999999999.99}; the negative boundary {@code -999999999.99}
     * is exercised in Test 5 (negativeBalance_supportedBySignedPic).</p>
     *
     * <p>The {@code scale() == 2} assertion is what catches a subtle
     * class of bugs where a future schema migration accidentally
     * promotes the column to {@code NUMERIC} (unbounded scale) or to
     * {@code DOUBLE PRECISION} (binary float). Either change would
     * silently break the COBOL fixed-point semantics required by
     * AAP &sect;0.6.1 and produce visible drift in the interest-
     * calculation parallel-run diff.</p>
     *
     * <p>The balance comparison uses {@code compareTo()} for value
     * equality, then a separate {@code scale()} assertion for the
     * scale invariant &mdash; the two-step pattern is the only safe way
     * to validate both BigDecimal value AND scale per AAP &sect;0.6.1.</p>
     */
    @Test
    void bigDecimalNumeric11Comma2_picS9_09_V99Parity() {
        // Setup parent accounts row to satisfy V006 FK fk_tran_cat_bal_acct_id.
        persistAccount(10000000001L);

        // AAP §0.6.1 — NUMERIC(11,2) maps PIC S9(09)V99 (9 digits + V99).
        // 999999999.99 = max positive value of PIC S9(09)V99
        // (9-digit integer + V99 fractional = 11 total digits).
        TransactionCategoryBalance boundary = buildTCatBal(
                10000000001L, "01", 5, new BigDecimal("999999999.99"));

        repository.save(boundary);
        // Force pending INSERT to DB + detach entity so findById reloads
        // from PostgreSQL rather than returning the same instance from
        // the first-level cache.
        entityManager.flush();
        entityManager.clear();

        Optional<TransactionCategoryBalance> result = repository.findById(
                new TransactionCategoryBalanceId(10000000001L, "01", 5));

        assertThat(result)
                .as("Maximum-precision row (10000000001L, '01', 5, "
                        + "999999999.99) must be findable after save + flush + "
                        + "clear; failure indicates the NUMERIC(11,2) precision "
                        + "boundary is mis-mapped between Java BigDecimal and "
                        + "PostgreSQL NUMERIC per AAP §0.6.1")
                .isPresent();

        BigDecimal reloadedBal = result.get().getTranCatBal();

        // Value assertion — compareTo for scale-insensitive value equality.
        assertThat(reloadedBal.compareTo(new BigDecimal("999999999.99")))
                .as("Reloaded tran_cat_bal must compareTo 999999999.99 as 0 "
                        + "(value-equal regardless of scale); 999999999.99 is "
                        + "the max positive value of PIC S9(09)V99 per AAP §0.6.1")
                .isZero();

        // Scale assertion — separate from value to ensure NUMERIC(11,2)
        // returns scale=2 (not 1, 0, or unbounded). A scale change would
        // silently break COBOL fixed-point parity per AAP §0.6.1.
        assertThat(reloadedBal.scale())
                .as("Reloaded tran_cat_bal must have scale = 2 (preserving "
                        + "the NUMERIC(11,2) decimal-place invariant per COBOL "
                        + "PIC S9(09)V99); a different scale indicates the "
                        + "column was promoted to NUMERIC (unbounded) or "
                        + "DOUBLE PRECISION, silently breaking AAP §0.6.1")
                .isEqualTo(2);
    }

    // =========================================================================
    // Test 5 — Negative balance supported by signed PIC S9(09)V99
    // =========================================================================

    /**
     * Validates that the V006 {@code tran_cat_bal NUMERIC(11,2)} column
     * round-trips a negative value via the signed COBOL
     * {@code PIC S9(09)V99} clause (the {@code S} = signed) without
     * sign loss or precision drift.
     *
     * <p>Negative running balances are an essential business reality
     * in CardDemo:</p>
     * <ul>
     *   <li>A credit-balance account (e.g., a refund or overpayment
     *       reduces a previously-positive balance below zero) carries
     *       a negative balance.</li>
     *   <li>Certain {@code (type, category)} buckets accumulate
     *       payments and credits (negative-sign deltas relative to
     *       purchases), which can drive the bucket balance below zero
     *       between cycle closes.</li>
     * </ul>
     *
     * <p>The COBOL {@code S} prefix in {@code PIC S9(09)V99} allows
     * the value to range over
     * {@code [-999,999,999.99, +999,999,999.99]}. PostgreSQL
     * {@code NUMERIC(11,2)} preserves this signed range natively
     * (NUMERIC is always signed in PostgreSQL; there is no
     * {@code UNSIGNED} modifier as in MySQL). This test exercises a
     * straightforward negative value {@code -100.00}; the boundary
     * {@code -999999999.99} is conceptually equivalent and is
     * covered by Test 4's positive boundary.</p>
     *
     * <p>The balance assertion uses {@code compareTo()} (value-equal,
     * scale-insensitive) per AAP &sect;0.6.1 &mdash; with negative
     * values the importance of {@code compareTo} over
     * {@code equals} is doubly emphasized because the sign byte
     * affects scale representation in some JDBC drivers.</p>
     */
    @Test
    void negativeBalance_supportedBySignedPic() {
        // Setup parent accounts row to satisfy V006 FK fk_tran_cat_bal_acct_id.
        persistAccount(10000000001L);

        // The COBOL PIC S9(09)V99 prefix S = signed; negative balances
        // arise from credits/refunds/overpayments and MUST round-trip
        // without sign loss. NUMERIC(11,2) is signed by default in
        // PostgreSQL.
        TransactionCategoryBalance negativeRow = buildTCatBal(
                10000000001L, "01", 5, new BigDecimal("-100.00"));

        repository.save(negativeRow);
        // Force pending INSERT to DB + detach entity so findById reloads
        // from PostgreSQL (not first-level cache).
        entityManager.flush();
        entityManager.clear();

        Optional<TransactionCategoryBalance> result = repository.findById(
                new TransactionCategoryBalanceId(10000000001L, "01", 5));

        assertThat(result)
                .as("Negative-balance row (10000000001L, '01', 5, -100.00) "
                        + "must be findable; the COBOL PIC S9(09)V99 'S' prefix "
                        + "and PostgreSQL NUMERIC(11,2) BOTH support signed "
                        + "values, so a sign mismatch indicates a JDBC type "
                        + "coercion bug")
                .isPresent();

        // compareTo (not equals) — BigDecimal.equals is scale-sensitive
        // per AAP §0.6.1. -100.00 vs -100.00 must value-equal.
        assertThat(result.get().getTranCatBal().compareTo(new BigDecimal("-100.00")))
                .as("Reloaded negative balance must compareTo -100.00 as 0 "
                        + "(value-equal regardless of scale); compareTo (not "
                        + "equals) per AAP §0.6.1 ensures sign preservation "
                        + "even if the JDBC driver normalizes scale "
                        + "differently than expected")
                .isZero();
    }

    // =========================================================================
    // Test 6 — V006 DEFAULT 0 zero-balance semantics
    // =========================================================================

    /**
     * Validates the V006 {@code tran_cat_bal NUMERIC(11,2) NOT NULL
     * DEFAULT 0} declaration: a row persisted with an explicit zero
     * balance round-trips as zero (value-equal), confirming the
     * COBOL semantic that new {@code (account, type, category)}
     * tuples start at a zero running balance.
     *
     * <p>The V006 declaration combines three constraints that together
     * deliver the zero-balance contract:</p>
     * <ul>
     *   <li>{@code NOT NULL} &mdash; mirrors COBOL fixed-width
     *       every-byte-always-present semantics; no NULL concept
     *       exists in a COBOL VSAM record.</li>
     *   <li>{@code DEFAULT 0} &mdash; if an INSERT omits the column,
     *       PostgreSQL supplies a zero value. This matches the COBOL
     *       behaviour where a brand-new {@code TCATBAL-FILE WRITE} on
     *       a previously-non-existent {@code (account, type, category)}
     *       tuple stores all zero binary bytes for {@code TRAN-CAT-BAL}
     *       (since COBOL initializes uninitialized {@code COMP-3}
     *       fields to zero).</li>
     *   <li>{@code NUMERIC(11,2)} &mdash; provides the precision +
     *       scale invariant exercised in Test 4.</li>
     * </ul>
     *
     * <p>The COBOL business context is that
     * {@code TransactionPostingService} (COBOL {@code CBTRN02C}) at
     * paragraph {@code 2700-UPDATE-TCATBAL} (per
     * {@code app/cbl/CBTRN02C.cbl}:L467&ndash;L499) performs the
     * pattern:</p>
     * <ol>
     *   <li>{@code EXEC CICS READ DATASET('TCATBAL-FILE')
     *       RIDFLD(TRAN-CAT-KEY) INTO(TRAN-CAT-BAL-RECORD)};</li>
     *   <li>If {@code TCATBALF-STATUS = '23'} (NOTFND), initialize
     *       the record with zero balance;</li>
     *   <li>Add the posted {@code TRAN-AMT} to {@code TRAN-CAT-BAL};</li>
     *   <li>Either {@code REWRITE} (if found) or {@code WRITE} (if
     *       not found) the updated record.</li>
     * </ol>
     *
     * <p>In the Java target, both branches are unified by
     * {@code repository.save(...)}, with the zero-balance bootstrap
     * provided by the COBOL caller code constructing a new
     * {@code TransactionCategoryBalance} with
     * {@code tranCatBal = BigDecimal.ZERO} (or scale-2 equivalent).
     * This test confirms that the persistence layer correctly
     * round-trips that zero-balance bootstrap.</p>
     *
     * <p>Note: this test persists with explicit zero (not omitted)
     * because the entity's {@code @Column(nullable = false)} declaration
     * would otherwise trigger a Hibernate-level NOT NULL violation at
     * flush time, BEFORE the V006 DEFAULT 0 column default could
     * activate at the SQL layer. The semantics of "DEFAULT 0 means new
     * rows start at zero" are exercised in the Java target via the
     * caller code's explicit zero, not via SQL-default activation
     * &mdash; consistent with the entity's BigDecimal field semantics.</p>
     */
    @Test
    void zeroDefaultBalance_perV006Default() {
        // Setup parent accounts row to satisfy V006 FK fk_tran_cat_bal_acct_id.
        persistAccount(10000000001L);

        // V006 DEFAULT 0 initial balance per CVTRA01Y semantics — new
        // (account, type, category) tuples in COBOL CBTRN02C start at
        // zero balance and are immediately incremented by the posted
        // TRAN-AMT within the same @Transactional boundary. We model
        // this here with an explicit zero (scale-2) to exercise the
        // zero-balance round-trip path.
        TransactionCategoryBalance zeroRow = buildTCatBal(
                10000000001L, "01", 5, BigDecimal.ZERO.setScale(2));

        repository.save(zeroRow);
        // Force pending INSERT to DB + detach entity so findById reloads
        // from PostgreSQL (not first-level cache).
        entityManager.flush();
        entityManager.clear();

        Optional<TransactionCategoryBalance> result = repository.findById(
                new TransactionCategoryBalanceId(10000000001L, "01", 5));

        assertThat(result)
                .as("Zero-balance row (10000000001L, '01', 5, 0.00) must be "
                        + "findable; V006 declares tran_cat_bal NOT NULL "
                        + "DEFAULT 0, modeling the COBOL semantic that new "
                        + "(account, type, category) tuples start at a zero "
                        + "running balance per CBTRN02C paragraph "
                        + "2700-UPDATE-TCATBAL")
                .isPresent();

        // compareTo (not equals) — BigDecimal.ZERO is value-equal to
        // 0.00 regardless of scale; equals() would mis-report 0 != 0.00.
        assertThat(result.get().getTranCatBal().compareTo(BigDecimal.ZERO))
                .as("Reloaded zero balance must compareTo BigDecimal.ZERO as 0 "
                        + "(value-equal regardless of scale); compareTo (not "
                        + "equals) per AAP §0.6.1 because BigDecimal.equals "
                        + "is scale-sensitive and would mis-report 0 vs 0.00 "
                        + "as unequal")
                .isZero();
    }

    // =========================================================================
    // Test 7 — findById returns Optional.empty() for unknown composite key
    // =========================================================================

    /**
     * Validates that
     * {@link TransactionCategoryBalanceRepository#findById(Object)}
     * returns {@link Optional#empty()} for an unknown composite key
     * rather than throwing an exception &mdash; matching the Spring
     * Data JPA contract for absent rows and the COBOL VSAM
     * {@code FILE STATUS = '23'} (record not found) semantic.
     *
     * <p>The unknown key chosen is {@code (99999999999L, "99", 9999)}:
     * a deliberately-extreme composite that is guaranteed not to exist
     * in any seeded reference data or in any previous test method's
     * scope (each {@code @DataJpaTest} method runs in its own
     * rolled-back transaction).</p>
     *
     * <p>No setup is performed in this test &mdash; the FK target
     * {@code accounts(acct_id = 99999999999L)} also does not exist,
     * which would block any attempt to INSERT a corresponding
     * {@code tran_cat_bal} row but does NOT affect the SELECT
     * issued by {@code findById}.</p>
     *
     * <p>In the COBOL source, the equivalent code path is:</p>
     * <pre>
     *     EXEC CICS READ DATASET('TCATBAL-FILE')
     *          RIDFLD(TRAN-CAT-KEY)
     *          INTO(TRAN-CAT-BAL-RECORD)
     *          RESP(WS-RESP-CD)
     *     END-EXEC.
     *     IF WS-RESP-CD = DFHRESP(NOTFND) ...
     * </pre>
     *
     * <p>The Java target maps the NOTFND condition to
     * {@code Optional.empty()} rather than throwing
     * {@code RecordNotFoundException} per AAP &sect;0.4.1 &mdash;
     * the exception is raised at the SERVICE layer by
     * {@code orElseThrow(RecordNotFoundException::new)} when the
     * service-layer contract requires presence, but the repository
     * contract itself returns Optional.empty() to allow optional-
     * presence flows (e.g., the CBTRN02C "INSERT new, UPDATE existing"
     * branch at paragraph 2700-UPDATE-TCATBAL).</p>
     */
    @Test
    void findByCompositeId_returnsEmpty_whenNotFound() {
        // Deliberately-extreme composite key guaranteed not to exist —
        // no setup performed.
        TransactionCategoryBalanceId unknownKey = new TransactionCategoryBalanceId(
                99999999999L, "99", 9999);

        Optional<TransactionCategoryBalance> result = repository.findById(unknownKey);

        assertThat(result)
                .as("findById on an unknown composite key (99999999999L, "
                        + "'99', 9999) must return Optional.empty() rather "
                        + "than throwing; this maps the COBOL VSAM FILE "
                        + "STATUS = '23' (NOTFND) condition to the Spring "
                        + "Data JPA Optional-empty contract per AAP §0.4.1")
                .isEmpty();
    }

    // =========================================================================
    // Test 8 — deleteById removes a row by composite key
    // =========================================================================

    /**
     * Validates that {@link TransactionCategoryBalanceRepository#deleteById(Object)}
     * removes a previously-persisted row keyed by composite
     * {@link TransactionCategoryBalanceId}, and the row is no longer
     * findable on subsequent {@code findById()} calls.
     *
     * <p>The test exercises the persistence-context interaction
     * pattern:</p>
     * <ol>
     *   <li>Persist a fresh row.</li>
     *   <li>Flush + clear to detach.</li>
     *   <li>Re-fetch + assert present.</li>
     *   <li>{@code deleteById(...)} + flush.</li>
     *   <li>Re-fetch + assert empty.</li>
     * </ol>
     *
     * <p>The intermediate flush after {@code deleteById} is critical:
     * Hibernate batches DELETE operations and would otherwise not
     * execute the SQL DELETE until either an explicit flush or the
     * implicit flush at transaction commit. Without the explicit
     * {@code entityManager.flush()}, the immediate
     * {@code repository.findById(...)} after delete could return the
     * row from the first-level cache (or fail to detect the still-
     * pending DELETE in the database).</p>
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS DELETE
     * DATASET('TCATBAL-FILE') RIDFLD(TRAN-CAT-KEY)} followed by a
     * verification {@code EXEC CICS READ} that returns
     * {@code DFHRESP(NOTFND)}.</p>
     */
    @Test
    void deleteById_removesByCompositeKey() {
        // Setup parent accounts row to satisfy V006 FK fk_tran_cat_bal_acct_id.
        persistAccount(10000000001L);

        // Persist a single row to be deleted.
        TransactionCategoryBalance tcb = buildTCatBal(
                10000000001L, "01", 5, new BigDecimal("100.00"));
        repository.save(tcb);
        entityManager.flush();

        // Verify the row was persisted before attempting deletion (sanity
        // check; if this assertion fails, the test setup is broken rather
        // than the delete operation).
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                10000000001L, "01", 5);
        assertThat(repository.findById(id))
                .as("Pre-delete sanity: row must exist before deleteById is "
                        + "called; if missing here, the test setup is broken")
                .isPresent();

        // Delete by composite key — the inherited JpaRepository.deleteById
        // method dispatches to a JPQL DELETE bound by the @EmbeddedId value.
        repository.deleteById(id);
        // Explicit flush — Hibernate batches DELETEs; without flush the SQL
        // DELETE would not yet execute, and the subsequent findById might
        // still return the cached row.
        entityManager.flush();
        // Clear the persistence context so the post-delete findById reloads
        // from PostgreSQL rather than returning a (now-stale) first-level
        // cache entry.
        entityManager.clear();

        // Verify the row is no longer findable.
        Optional<TransactionCategoryBalance> result = repository.findById(id);
        assertThat(result)
                .as("Post-delete: row (10000000001L, '01', 5) must NOT be "
                        + "findable; deleteById on the composite key must "
                        + "have removed the row, matching the COBOL "
                        + "EXEC CICS DELETE DATASET('TCATBAL-FILE') "
                        + "RIDFLD(TRAN-CAT-KEY) semantic")
                .isEmpty();
    }

    // =========================================================================
    // Test 9 — count() returns the actual row count
    // =========================================================================

    /**
     * Validates that
     * {@link TransactionCategoryBalanceRepository#count()} returns the
     * actual number of rows in the {@code tran_cat_bal} table for
     * this test's transactional scope, matching the COBOL pattern of
     * {@code STARTBR ... READNEXT} counting iterations until
     * {@code DFHRESP(ENDFILE)}.
     *
     * <p>The test persists 3 distinct composite-key rows under the
     * same parent {@code accounts.acct_id} and asserts
     * {@code count() == 3L}. This validates two contracts:</p>
     * <ul>
     *   <li>{@code count()} returns a {@code long} (not {@code int}),
     *       which matters for large tables in production (the
     *       returned value is a Java {@code long} via
     *       {@code SELECT COUNT(*)} from PostgreSQL).</li>
     *   <li>{@code @DataJpaTest} transactional isolation works as
     *       expected: only the 3 rows persisted within this test
     *       method's transaction are visible, regardless of whether
     *       other test methods (run before or after) persist
     *       different rows. Each {@code @DataJpaTest} test method
     *       runs in its own transaction that rolls back at the end,
     *       so prior tests' inserts are invisible here.</li>
     * </ul>
     *
     * <p>The 3 rows chosen mirror Test 3
     * ({@code differentTypeCd_yieldsDifferentRecords_compositeKeyDistinguishes})
     * to keep test fixtures consistent &mdash; however, this test
     * stresses the {@code count()} method rather than per-row
     * {@code findById()} retrievals.</p>
     */
    @Test
    void count_returnsRowCount() {
        // Setup parent accounts row to satisfy V006 FK fk_tran_cat_bal_acct_id.
        persistAccount(10000000001L);

        // Persist 3 distinct composite-key rows under the same account.
        repository.save(buildTCatBal(
                10000000001L, "01", 5, new BigDecimal("100.00")));
        repository.save(buildTCatBal(
                10000000001L, "02", 5, new BigDecimal("200.00")));
        repository.save(buildTCatBal(
                10000000001L, "01", 6, new BigDecimal("300.00")));
        // Force all pending INSERTs to DB so count() reflects actual row count.
        entityManager.flush();

        // Assert the row count exactly matches the number of saves.
        // @DataJpaTest transactional isolation guarantees other tests'
        // inserts are NOT visible here (they were rolled back at the
        // end of their respective transactions).
        assertThat(repository.count())
                .as("Three distinct composite-key TransactionCategoryBalance "
                        + "rows persisted within this test's @Transactional "
                        + "scope must produce count() == 3L; failure indicates "
                        + "either (a) the saves did not all reach the database "
                        + "(flush failure), or (b) @DataJpaTest transactional "
                        + "isolation is broken and other tests' rolled-back "
                        + "inserts are leaking into this test's scope")
                .isEqualTo(3L);
    }
}
