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

import com.awsm2.carddemo.domain.Account;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @DataJpaTest} slice test for {@link AccountRepository}.
 *
 * <h2>Source mapping (AAP &sect;0.6.1, &sect;0.6.2, &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS} &mdash;
 *       {@code KEYS(11 0)}, {@code RECORDSIZE(300 300)},
 *       {@code SHAREOPTIONS(2 3)}, {@code ERASE}, {@code INDEXED}
 *       (per {@code app/jcl/ACCTFILE.jcl}:L36&ndash;L49 and verified
 *       against {@code app/catlg/LISTCAT.txt}:L22&ndash;L79 &mdash;
 *       KEYLEN=11, RKP=0, MAXLRECL=300, AVGLRECL=300, INDEXED,
 *       SHROPTNS(2,3)). The KSDS has NO alternate index (AIX) or PATH
 *       per LISTCAT.txt &mdash; all COBOL access is by primary key
 *       (random read on {@code ACCT-ID}) or sequential scan
 *       ({@code app/cbl/CBACT01C.cbl}); the PostgreSQL B-tree on the
 *       PK satisfies both patterns.</li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/COACTVWC.cbl} &mdash; account view: random
 *             read by {@code ACCT-ID}, joined with {@code CUSTOMER} via
 *             the {@code CXACAIX} alternate index over {@code CARDXREF}
 *             to render the account-view 3270 screen.</li>
 *         <li>{@code app/cbl/COACTUPC.cbl} &mdash; account update: the
 *             ONLY COBOL program in the entire source tree with an
 *             explicit {@code EXEC CICS SYNCPOINT ROLLBACK} and an
 *             explicit before/after image comparison pattern. The Java
 *             target replaces this with {@code @Transactional(
 *             rollbackFor = Exception.class)} on
 *             {@code AccountUpdateService} (service-layer transactional
 *             boundary) PLUS JPA {@code @Version} on {@link Account}
 *             (entity-layer optimistic locking) per AAP &sect;0.7.1.
 *             Tests 4 and 5 in THIS file validate the latter.</li>
 *         <li>{@code app/cbl/CBACT01C.cbl} &mdash; batch sequential
 *             scanner that emits every row in the {@code accounts}
 *             table; the Java target is {@code AccountFileReaderService}
 *             which delegates to {@code repository.findAll(Pageable)}
 *             for chunked Spring Batch iteration.</li>
 *         <li>{@code app/cbl/CBACT04C.cbl} &mdash; end-of-cycle interest
 *             calculation; the Java target is
 *             {@code InterestCalculationService} which reads
 *             {@link Account#getAcctCurrBal() acctCurrBal} via this
 *             repository and applies the literal COBOL formula
 *             {@code (balance * rate) / 1200} with
 *             {@link RoundingMode#HALF_EVEN HALF_EVEN} (AAP &sect;0.6.1
 *             /&nbsp;&sect;0.7.3 Minimal Change Clause &mdash; no
 *             algebraic simplification of the divisor 1200).</li>
 *         <li>{@code app/cbl/CBTRN02C.cbl} &mdash; 4-stage transaction-
 *             posting validation cascade (XREF &rarr; Account &rarr;
 *             Credit limit &rarr; Card expiration); reject codes
 *             100&ndash;109 preserved verbatim per AAP &sect;0.1.1. The
 *             Java target {@code TransactionPostingService} consumes
 *             this repository at Stage 2 (account lookup) and Stage 3
 *             (credit-limit check).</li>
 *         <li>{@code app/cbl/COBIL00C.cbl} &mdash; bill payment with
 *             dual write (account balance update + transaction row
 *             insert + {@code account.updated} MSK event publish);
 *             {@code @Transactional} boundary in
 *             {@code BillPaymentService}.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Copybook:</strong> {@code app/cpy/CVACT01Y.cpy}
 *       ({@code ACCOUNT-RECORD} layout, RECLN 300; 12 business fields
 *       plus a 178-byte trailing {@code FILLER PIC X(178)}):
 *       <pre>
 *         05  ACCT-ID                PIC 9(11).             (L5)
 *         05  ACCT-ACTIVE-STATUS     PIC X(01).             (L6)
 *         05  ACCT-CURR-BAL          PIC S9(10)V99.         (L7)
 *         05  ACCT-CREDIT-LIMIT      PIC S9(10)V99.         (L8)
 *         05  ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99.         (L9)
 *         05  ACCT-OPEN-DATE         PIC X(10).             (L10)
 *         05  ACCT-EXPIRAION-DATE    PIC X(10).             (L11) [COBOL typo: "EXPIRAION"]
 *         05  ACCT-REISSUE-DATE      PIC X(10).             (L12)
 *         05  ACCT-CURR-CYC-CREDIT   PIC S9(10)V99.         (L13)
 *         05  ACCT-CURR-CYC-DEBIT    PIC S9(10)V99.         (L14)
 *         05  ACCT-ADDR-ZIP          PIC X(10).             (L15)
 *         05  ACCT-GROUP-ID          PIC X(10).             (L16)
 *         05  FILLER                 PIC X(178).            (L17, OMITTED in JPA)
 *       </pre>
 *   </li>
 *   <li><strong>JCL DD allocation:</strong> {@code app/jcl/ACCTFILE.jcl}
 *       &mdash; IDCAMS DEFINE CLUSTER (STEP10:L33&ndash;L49) + IDCAMS
 *       REPRO load (STEP15:L54&ndash;L62) from
 *       {@code AWS.M2.CARDDEMO.ACCTDATA.PS} into the KSDS. Bulk
 *       account master data is loaded to RDS by an AWS Glue Spark job
 *       per AAP &sect;0.6.2; Flyway V001 creates the schema but does
 *       NOT seed account rows.</li>
 *   <li><strong>Flyway migration:</strong>
 *       {@code src/main/resources/db/migration/V001__create_account.sql}
 *       creates the 13-column {@code accounts} table:
 *       {@code acct_id BIGINT PK}, {@code acct_active_status CHAR(1) NOT NULL}
 *       (with {@code chk_accounts_active_status CHECK} restricting to
 *       {@code 'Y'} or {@code 'N'}), 5 monetary
 *       {@code NUMERIC(12,2) NOT NULL DEFAULT 0} columns (PIC S9(10)V99
 *       parity per AAP &sect;0.6.1), 3 DATE columns
 *       ({@code acct_open_date} NN, {@code acct_expiration_date} NN [the
 *       typo-corrected column], {@code acct_reissue_date} nullable),
 *       {@code acct_addr_zip VARCHAR(10)} (nullable),
 *       {@code acct_group_id VARCHAR(10)} (nullable), and
 *       {@code version BIGINT NOT NULL DEFAULT 0} for JPA
 *       {@code @Version} optimistic locking. The application asserts
 *       this schema via Hibernate {@code ddl-auto: validate} at startup.</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ol>
 *   <li><strong>Round-trip persistence by BIGINT PK</strong> &mdash;
 *       {@code save()} + {@code findById()} of a freshly built
 *       {@link Account} keyed by {@code acctId}
 *       ({@link #saveAndFindById_persistsAccountByAcctId() Test 1}).</li>
 *   <li><strong>{@code NUMERIC(12,2)} precision parity with COBOL
 *       {@code PIC S9(10)V99}</strong> &mdash; the boundary value
 *       {@code 9999999999.99} (10 integer digits + 2 fractional digits =
 *       12 precision) round-trips intact through the
 *       {@code acct_curr_bal} column
 *       ({@link #bigDecimalPrecision_storesNumeric12Comma2_picS9_10_V99Parity() Test 2}).</li>
 *   <li><strong>Signed numeric semantics</strong> &mdash; the COBOL
 *       {@code PIC S} prefix permits negative values; a
 *       {@code -500.00} debit round-trips through {@code acct_curr_cyc_debit}
 *       ({@link #bigDecimalNegativeBalance_supportedBySignedPic() Test 3}).</li>
 *   <li><strong>JPA {@code @Version} optimistic locking</strong> replaces
 *       COBOL {@code COACTUPC} before/after image comparison &mdash;
 *       concurrent writes against a stale entity throw
 *       {@link OptimisticLockingFailureException}
 *       ({@link #versionFieldOptimisticLocking_throwsOnConcurrentUpdate_replacingCoactupcBeforeAfterImage() Test 4}).</li>
 *   <li><strong>{@code @Version} auto-increments on every save</strong>
 *       ({@link #successfulUpdate_incrementsVersion() Test 5}).</li>
 *   <li><strong>Nullable date column</strong> &mdash; {@code acct_reissue_date}
 *       (the only truly nullable date column per V001) persists and
 *       reloads as {@code null}
 *       ({@link #nullableReissueDate_persistsWithNull() Test 6}).</li>
 *   <li><strong>{@code deleteById}</strong> contract
 *       ({@link #deleteById_removesAccount() Test 7}).</li>
 *   <li><strong>{@code existsById}</strong> contract
 *       ({@link #existsById_returnsTrueWhenPresent_falseWhenAbsent() Test 8}).</li>
 *   <li><strong>{@code count}</strong> returns the persisted row count
 *       ({@link #count_returnsRowCount() Test 9}).</li>
 *   <li><strong>Banker's rounding ({@link RoundingMode#HALF_EVEN})</strong>
 *       at the arithmetic boundary preserves scale=2 round-trip
 *       ({@link #arithmeticPreservesScale_halfEvenRounding() Test 10}).</li>
 * </ol>
 *
 * <h2>Note on absent custom queries (AAP &sect;0.7.3)</h2>
 * <p>{@link AccountRepository} has an empty interface body &mdash; it
 * extends {@code JpaRepository<Account, Long>} and declares NO custom
 * derived queries, NO {@code @Query} annotations, and NO native SQL
 * methods. All consumer access patterns
 * ({@code AccountViewService}, {@code AccountUpdateService},
 * {@code AccountFileReaderService}, {@code TransactionPostingService},
 * {@code BillPaymentService}, {@code InterestCalculationService},
 * {@code StatementGenerationService}) are satisfied by inherited
 * {@link org.springframework.data.jpa.repository.JpaRepository}
 * methods. Tests 1&ndash;10 exclusively exercise the inherited
 * contract.</p>
 *
 * <h2>Transactional boundary &mdash; what this test does NOT cover</h2>
 * <p>The {@code @Transactional(rollbackFor = Exception.class)} that
 * replaces the COBOL {@code EXEC CICS SYNCPOINT ROLLBACK} (only
 * explicit instance: {@code COACTUPC.cbl} dual-update of account +
 * customer rows) lives at the <em>service</em> layer
 * ({@code AccountUpdateService}, {@code BillPaymentService},
 * {@code TransactionPostingService}). Repository-slice tests
 * intentionally do NOT validate transactional rollback semantics &mdash;
 * those are covered by service-layer tests that exercise the full
 * service-method &amp; rollback contract.</p>
 *
 * @see AccountRepository
 * @see Account
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountRepositoryTest {

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
    // target runs PostgreSQL 16, so tests exercise the same SQL engine
    // ensuring BIGINT primary key, NUMERIC(12,2) arbitrary-precision
    // arithmetic for the 5 monetary fields, BIGINT version column for
    // @Version optimistic locking, DATE column types for the 3 LocalDate
    // fields, CHAR(1) acct_active_status with chk_accounts_active_status
    // CHECK constraint, VARCHAR(10) for acct_addr_zip and acct_group_id,
    // Flyway V001 migration application, and PostgreSQL-specific SQL
    // semantics are validated faithfully against the engine the
    // application will actually run on in production.
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
    // Mirrors the pattern established in CustomerRepositoryTest,
    // TransactionTypeRepositoryTest, DisclosureGroupRepositoryTest,
    // DailyTransactionRepositoryTest, and UserSecurityRepositoryTest.
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
     * {@code @EnableJpaRepositories}). The repository has an empty
     * interface body &mdash; this field exercises only inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository}
     * methods ({@code save}, {@code findById}, {@code count},
     * {@code deleteById}, {@code existsById}, {@code findAll}).
     */
    @Autowired
    private AccountRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager}
     * (transitively enabled by {@code @DataJpaTest}). Used to force
     * SQL execution via {@link TestEntityManager#flush() flush()} and
     * to detach the persistence context via
     * {@link TestEntityManager#clear() clear()} so that subsequent
     * {@code findById()} calls reload from the database rather than
     * returning a first-level-cache hit. This is critical for verifying
     * that monetary fields (acctCurrBal, acctCreditLimit, etc.) and
     * the JPA {@code @Version} column round-trip from PostgreSQL
     * NUMERIC(12,2) / BIGINT intact and not merely echoed from the
     * entity-manager cache. Test 4 additionally uses
     * {@code entityManager.getEntityManager().detach(entity)} to
     * simulate two independent persistence contexts within a single
     * test method for {@code @Version} optimistic-locking validation.
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test fixture builder
    // =========================================================================

    /**
     * Builds a transient (non-persisted) {@link Account} entity populated
     * with the supplied identity / balance / credit-limit fields plus a
     * canonical set of valid defaults for every other field defined in
     * {@code app/cpy/CVACT01Y.cpy}. The fixture is intentionally minimal:
     * required NOT NULL columns are populated with realistic
     * golden-fixture-style values; nullable fields ({@code acctReissueDate},
     * {@code acctAddrZip}, {@code acctGroupId}) get sensible defaults.
     *
     * <p>The 12 setters are invoked in the SAME order as the COBOL
     * {@code ACCOUNT-RECORD} layout
     * ({@code app/cpy/CVACT01Y.cpy}:L5&ndash;L16) so the Java source
     * remains visually parallel to the source-of-truth COBOL record
     * layout per AAP &sect;0.7.3 refactor discipline.</p>
     *
     * <p>The cash credit limit is derived as 20&#37; of the supplied
     * total credit limit (i.e., {@code creditLimit / 5}) using
     * {@link RoundingMode#HALF_EVEN HALF_EVEN} banker's rounding per
     * AAP &sect;0.6.1 to demonstrate the disciplined arithmetic boundary
     * even in test fixtures &mdash; the production
     * {@code TransactionPostingService} applies the same rounding mode
     * for its cash-advance-vs-purchase routing logic.</p>
     *
     * <p>The {@link Account#getVersion() version} field is intentionally
     * left {@code null} &mdash; Hibernate initialises it to {@code 0L}
     * on first {@code save()} and increments it on every subsequent
     * managed update. Manual assignment would defeat the optimistic-
     * locking guarantee per AAP &sect;0.7.1.</p>
     *
     * @param acctId      11-digit unsigned numeric account identifier
     *                    (PK, mapped to {@code acct_id BIGINT})
     * @param currBal     opening current balance (mapped to
     *                    {@code acct_curr_bal NUMERIC(12,2)})
     * @param creditLimit total credit limit (mapped to
     *                    {@code acct_credit_limit NUMERIC(12,2)})
     * @return a fresh, transient (non-persisted) {@link Account}
     */
    private Account buildAccount(Long acctId, BigDecimal currBal, BigDecimal creditLimit) {
        Account a = new Account();
        // COBOL: CVACT01Y.cpy:L5 ACCT-ID PIC 9(11) -> BIGINT PK
        a.setAcctId(acctId);
        // COBOL: CVACT01Y.cpy:L6 ACCT-ACTIVE-STATUS PIC X(01) -> CHAR(1) NN; chk constraint enforces ('Y','N')
        a.setAcctActiveStatus("Y");
        // COBOL: CVACT01Y.cpy:L7 ACCT-CURR-BAL PIC S9(10)V99 -> NUMERIC(12,2) NN
        a.setAcctCurrBal(currBal);
        // COBOL: CVACT01Y.cpy:L8 ACCT-CREDIT-LIMIT PIC S9(10)V99 -> NUMERIC(12,2) NN
        a.setAcctCreditLimit(creditLimit);
        // COBOL: CVACT01Y.cpy:L9 ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 -> NUMERIC(12,2) NN
        // AAP §0.6.1 — RoundingMode.HALF_EVEN at every arithmetic boundary
        a.setAcctCashCreditLimit(creditLimit.divide(new BigDecimal("5"), 2, RoundingMode.HALF_EVEN));
        // COBOL: CVACT01Y.cpy:L10 ACCT-OPEN-DATE PIC X(10) -> DATE NN
        a.setAcctOpenDate(LocalDate.of(2024, 1, 1));
        // COBOL: CVACT01Y.cpy:L11 ACCT-EXPIRAION-DATE [typo] PIC X(10) -> DATE NN (typo corrected to "expiration" per AAP §0.4.1)
        a.setAcctExpirationDate(LocalDate.of(2034, 12, 31));
        // COBOL: CVACT01Y.cpy:L12 ACCT-REISSUE-DATE PIC X(10) -> DATE (nullable)
        a.setAcctReissueDate(null);
        // COBOL: CVACT01Y.cpy:L13 ACCT-CURR-CYC-CREDIT PIC S9(10)V99 -> NUMERIC(12,2) NN
        a.setAcctCurrCycCredit(BigDecimal.ZERO.setScale(2));
        // COBOL: CVACT01Y.cpy:L14 ACCT-CURR-CYC-DEBIT PIC S9(10)V99 -> NUMERIC(12,2) NN
        a.setAcctCurrCycDebit(BigDecimal.ZERO.setScale(2));
        // COBOL: CVACT01Y.cpy:L15 ACCT-ADDR-ZIP PIC X(10) -> VARCHAR(10) (nullable)
        a.setAcctAddrZip("10001");
        // COBOL: CVACT01Y.cpy:L16 ACCT-GROUP-ID PIC X(10) -> VARCHAR(10) (nullable; null => DEFAULT disclosure group)
        a.setAcctGroupId("DEFAULT");
        // The trailing COBOL FILLER PIC X(178) is OMITTED from JPA entity per AAP §0.6.2.
        // The version field is auto-managed by @Version — never set manually.
        return a;
    }

    // =========================================================================
    // Test 1 — Round-trip persistence by BIGINT PK
    // =========================================================================

    /**
     * Validates the foundational JpaRepository contract: a fresh
     * {@link Account} {@code save()}'d via the repository can be
     * retrieved by its primary key with {@code findById()} and the
     * monetary fields round-trip without precision loss.
     *
     * <p>This test replaces the COBOL CICS pattern
     * {@code EXEC CICS WRITE DATASET('ACCTDAT') FROM(ACCT-RECORD)
     * RIDFLD(ACCT-ID)} followed by
     * {@code EXEC CICS READ DATASET('ACCTDAT') INTO(ACCT-RECORD)
     * RIDFLD(ACCT-ID)} &mdash; the canonical write-then-read pattern
     * used by every CardDemo program that touches the {@code ACCTDAT}
     * file.</p>
     */
    @Test
    void saveAndFindById_persistsAccountByAcctId() {
        // Arrange: build a transient Account fixture
        Account a = buildAccount(10000000001L, new BigDecimal("1000.00"), new BigDecimal("5000.00"));

        // Act: persist + flush + clear → forces SQL execution + detaches the persistence context
        // so the subsequent findById() reloads from the database (NOT from L1 cache)
        repository.save(a);
        entityManager.flush();
        entityManager.clear();

        // Assert: the row is present and the monetary fields round-trip intact
        Optional<Account> reloaded = repository.findById(10000000001L);
        assertThat(reloaded).isPresent();
        Account reloadedAccount = reloaded.get();
        // BigDecimal.equals() compares BOTH value AND scale — use compareTo() == 0 for value-equality
        assertThat(reloadedAccount.getAcctCurrBal()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("1000.00"));
        assertThat(reloadedAccount.getAcctCreditLimit()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("5000.00"));
        assertThat(reloadedAccount.getAcctId()).isEqualTo(10000000001L);
        assertThat(reloadedAccount.getAcctActiveStatus()).isEqualTo("Y");
    }

    // =========================================================================
    // Test 2 — NUMERIC(12,2) precision parity with PIC S9(10)V99
    // =========================================================================

    /**
     * Validates that the {@code acct_curr_bal NUMERIC(12,2)} column
     * preserves the COBOL {@code PIC S9(10)V99} precision boundary &mdash;
     * specifically, the maximum positive value {@code 9999999999.99}
     * (10 integer digits + 2 fractional digits = 12 total precision) is
     * stored and reloaded intact without rounding or truncation.
     *
     * <p>Per AAP &sect;0.6.1, the precision mapping is
     * mandatory:
     * <ul>
     *   <li>COBOL {@code PIC S9(10)V99} = signed, 10 integer digits + 2
     *       fractional digits = range
     *       &#x2212;9,999,999,999.99 ..&nbsp;+9,999,999,999.99</li>
     *   <li>PostgreSQL {@code NUMERIC(12,2)} = arbitrary-precision exact
     *       arithmetic; precision = 10 (integer) + 2 (fractional) = 12;
     *       scale = 2</li>
     *   <li>Java {@link BigDecimal} arbitrary-precision exact decimal</li>
     * </ul>
     * The test demonstrates that the full COBOL value range is reachable
     * end-to-end through the persistence stack &mdash; a foundational
     * parity guarantee for behavioural validation against the COBOL
     * source.</p>
     */
    @Test
    void bigDecimalPrecision_storesNumeric12Comma2_picS9_10_V99Parity() {
        // AAP §0.6.1 — NUMERIC(12,2) maps PIC S9(10)V99 exactly
        // Maximum positive value: 10 integer digits + V99 = 12-digit precision boundary
        BigDecimal maxBalance = new BigDecimal("9999999999.99");

        // Arrange: build an Account at the maximum-balance boundary
        Account a = buildAccount(10000000002L, maxBalance, new BigDecimal("9999999999.99"));

        // Act: persist + flush + clear → forces SQL execution + detaches persistence context
        repository.save(a);
        entityManager.flush();
        entityManager.clear();

        // Assert: the boundary value round-trips intact via NUMERIC(12,2)
        Optional<Account> reloaded = repository.findById(10000000002L);
        assertThat(reloaded).isPresent();
        BigDecimal storedBalance = reloaded.get().getAcctCurrBal();
        // Value equality via compareTo (BigDecimal.equals() compares scale too)
        assertThat(storedBalance).usingComparator(BigDecimal::compareTo).isEqualTo(maxBalance);
        // Scale is preserved at 2 (V99 = 2 fractional digits)
        assertThat(storedBalance.scale()).isEqualTo(2);
        // Precision ≤ 12 — PIC S9(10)V99 fits in NUMERIC(12,2)
        assertThat(storedBalance.precision()).isLessThanOrEqualTo(12);
    }

    // =========================================================================
    // Test 3 — Signed numeric: negative balances supported by PIC S prefix
    // =========================================================================

    /**
     * Validates that the COBOL {@code PIC S} signed-numeric prefix is
     * preserved across the JPA persistence stack &mdash; a negative
     * value in a {@code NUMERIC(12,2)} column round-trips intact.
     *
     * <p>The {@code S} in {@code PIC S9(10)V99} declares the value as
     * <em>signed</em>; negative balances arise routinely in the COBOL
     * source &mdash; for example, when {@link Account#getAcctCurrCycDebit() acctCurrCycDebit}
     * accumulates a reversal or refund larger than the credits posted in
     * the cycle. PostgreSQL {@code NUMERIC(12,2)} is inherently signed
     * (the precision count excludes the sign bit), so the mapping is
     * straight-through.</p>
     *
     * <p>Production code paths that produce negative values include:
     * {@code BillPaymentService} (refunds), {@code TransactionPostingService}
     * (chargebacks / reversals per {@code CBTRN02C} validation cascade),
     * and {@code InterestCalculationService} (negative interest accruals
     * on credit-balance accounts).</p>
     */
    @Test
    void bigDecimalNegativeBalance_supportedBySignedPic() {
        // PIC S9(10)V99 — signed numeric, supports negative balances
        BigDecimal negativeDebit = new BigDecimal("-500.00");

        // Arrange: build an Account with a negative cycle-debit value
        Account a = buildAccount(10000000003L, new BigDecimal("1000.00"), new BigDecimal("5000.00"));
        a.setAcctCurrCycDebit(negativeDebit);

        // Act
        repository.save(a);
        entityManager.flush();
        entityManager.clear();

        // Assert: negative value preserved across the round-trip
        Optional<Account> reloaded = repository.findById(10000000003L);
        assertThat(reloaded).isPresent();
        BigDecimal storedDebit = reloaded.get().getAcctCurrCycDebit();
        assertThat(storedDebit).usingComparator(BigDecimal::compareTo).isEqualTo(negativeDebit);
        // Confirm the value is actually negative (defensive check)
        assertThat(storedDebit.signum()).isEqualTo(-1);
    }

    // =========================================================================
    // Test 4 — @Version optimistic locking replaces COACTUPC.cbl
    //          before/after image comparison
    // =========================================================================

    /**
     * Validates the JPA {@code @Version} optimistic-locking contract
     * that replaces the COBOL before/after image comparison pattern in
     * {@code app/cbl/COACTUPC.cbl}.
     *
     * <p><strong>COBOL pattern (replaced):</strong> {@code COACTUPC.cbl}
     * performs {@code EXEC CICS READ DATASET('ACCTDAT') UPDATE} to lock
     * the row, captures a before-image, applies field-level updates,
     * captures an after-image, then issues
     * {@code EXEC CICS REWRITE DATASET('ACCTDAT')} only if the
     * before-image matches the database state at REWRITE time. If a
     * concurrent CICS task has modified the row in the interim, the
     * REWRITE detects the snapshot mismatch and the program issues
     * {@code EXEC CICS SYNCPOINT ROLLBACK} to abort the unit of work.</p>
     *
     * <p><strong>JPA pattern (replacement, AAP &sect;0.7.1):</strong>
     * The {@link Account} entity carries a {@code @Version} {@code Long}
     * column. On every {@code save()}, Hibernate:</p>
     * <ol>
     *   <li>Auto-increments the {@code version} column on the in-memory
     *       entity.</li>
     *   <li>Issues an {@code UPDATE} whose {@code WHERE} clause includes
     *       the previously-loaded {@code version} value.</li>
     *   <li>If another transaction has committed in the interim, the
     *       {@code WHERE} matches 0 rows; Hibernate detects this and
     *       throws
     *       {@link org.hibernate.StaleObjectStateException} which
     *       Spring's persistence exception translation (enabled by
     *       {@code @Repository}) wraps as
     *       {@link OptimisticLockingFailureException} (more precisely,
     *       {@link org.springframework.orm.ObjectOptimisticLockingFailureException}).</li>
     *   <li>The {@code GlobalExceptionHandler}
     *       ({@code @RestControllerAdvice}) maps this to the domain
     *       {@code ConcurrentModificationException} and returns HTTP
     *       409 Conflict to the REST caller, preserving the COBOL's
     *       "snapshot mismatch" error semantic.</li>
     * </ol>
     *
     * <p><strong>Test choreography</strong> (simulating two independent
     * persistence contexts inside a single {@code @DataJpaTest} method
     * via {@code detach()} + {@code clear()} + re-fetch):</p>
     * <ol>
     *   <li>Persist an Account; flush + clear so the row is fully
     *       materialised in the database.</li>
     *   <li>Load {@code snap1} (snapshot 1) via {@code findById()}.</li>
     *   <li>Detach {@code snap1} from the persistence context (it is now
     *       unmanaged but retains its loaded {@code version} value).</li>
     *   <li>Clear the persistence context so the next find returns a
     *       fresh managed entity rather than a cache hit.</li>
     *   <li>Load {@code snap2} via {@code findById()} (managed; same
     *       initial {@code version} value as {@code snap1}).</li>
     *   <li>Modify {@code snap2}; {@code save()} + {@code flush()}.
     *       The database {@code version} is now incremented.</li>
     *   <li>Modify {@code snap1} (which still holds the OLD version);
     *       attempt {@code save()} + {@code flush()}. Hibernate detects
     *       the stale version, the {@code WHERE} clause matches 0 rows,
     *       and {@link OptimisticLockingFailureException} is thrown.</li>
     * </ol>
     */
    @Test
    void versionFieldOptimisticLocking_throwsOnConcurrentUpdate_replacingCoactupcBeforeAfterImage() {
        // AAP §0.7.1 — @Version replaces COACTUPC.cbl before/after image comparison;
        // SYNCPOINT ROLLBACK semantics live at the service layer via @Transactional.

        // ----- Step 1: Persist + flush + clear so the row is fully materialised -----
        Account initial = buildAccount(10000000004L, new BigDecimal("1000.00"), new BigDecimal("5000.00"));
        repository.save(initial);
        entityManager.flush();
        entityManager.clear();

        // ----- Step 2: Load snap1 (snapshot 1) -----
        Account snap1 = repository.findById(10000000004L).orElseThrow();
        Long initialVersion = snap1.getVersion();
        assertThat(initialVersion).isNotNull();

        // ----- Step 3: Detach snap1 from the persistence context -----
        // It is now unmanaged but retains the loaded version value.
        entityManager.getEntityManager().detach(snap1);

        // ----- Step 4: Clear the persistence context -----
        entityManager.clear();

        // ----- Step 5: Load snap2 (snapshot 2) — same row, same initial version -----
        Account snap2 = repository.findById(10000000004L).orElseThrow();
        assertThat(snap2.getVersion()).isEqualTo(initialVersion);

        // ----- Step 6: Modify and save snap2 — version increments in DB -----
        snap2.setAcctCurrBal(new BigDecimal("2000.00"));
        repository.save(snap2);
        entityManager.flush();
        entityManager.clear();

        // ----- Step 7: Modify snap1 (still holds OLD version) and attempt to save → expect OptimisticLockingFailureException -----
        snap1.setAcctCurrBal(new BigDecimal("3000.00"));
        assertThatThrownBy(() -> {
            repository.save(snap1);
            entityManager.flush();
        }).isInstanceOf(OptimisticLockingFailureException.class);
    }

    // =========================================================================
    // Test 5 — Successful update increments @Version
    // =========================================================================

    /**
     * Validates that a successful {@code save()} of a managed
     * {@link Account} auto-increments the {@code @Version} column &mdash;
     * the positive-path complement to Test 4. Together, Tests 4 and 5
     * fully cover the {@code @Version} semantic that replaces the COBOL
     * before/after image comparison in {@code COACTUPC.cbl}.
     *
     * <p>Hibernate increments the {@code version} value on every
     * managed-entity update so the next read observes a fresh version
     * and any concurrent stale write fails per the contract in Test 4.</p>
     */
    @Test
    void successfulUpdate_incrementsVersion() {
        // @Version is auto-incremented on each managed update
        // Arrange: persist an Account and capture its initial version
        Account a = buildAccount(10000000005L, new BigDecimal("1000.00"), new BigDecimal("5000.00"));
        repository.save(a);
        entityManager.flush();
        entityManager.clear();

        Account loaded = repository.findById(10000000005L).orElseThrow();
        Long v0 = loaded.getVersion();
        assertThat(v0).isNotNull();

        // Act: modify balance and save → Hibernate increments version on UPDATE
        loaded.setAcctCurrBal(new BigDecimal("1500.00"));
        repository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        // Assert: reload and verify version incremented exactly once
        Account reloaded = repository.findById(10000000005L).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(v0 + 1L);
        // Sanity: balance change persisted
        assertThat(reloaded.getAcctCurrBal()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("1500.00"));
    }

    // =========================================================================
    // Test 6 — Nullable acct_reissue_date persists with NULL
    // =========================================================================

    /**
     * Validates that the {@code acct_reissue_date} column (the only
     * truly nullable date column per V001) accepts and round-trips
     * {@code null} values.
     *
     * <p><strong>NOTE on the V001 schema:</strong> per
     * {@code src/main/resources/db/migration/V001__create_account.sql}
     * line 284, {@code acct_expiration_date DATE NOT NULL} and per
     * line 292, {@code acct_reissue_date DATE} (nullable). Only the
     * reissue date is nullable; the expiration date is required (every
     * account has a known expiration date). The COBOL source carries
     * an all-spaces value in {@code acct_reissue_date} for accounts
     * that have never been reissued, which maps to PostgreSQL
     * {@code NULL} per the standard COBOL-to-PostgreSQL
     * all-spaces-&gt;NULL convention.</p>
     *
     * <p>This is consistent with the COBOL business rule:
     * {@code ACCT-OPEN-DATE} and {@code ACCT-EXPIRAION-DATE} (the
     * typo-corrected expiration column) are always populated for every
     * account; only {@code ACCT-REISSUE-DATE} can be all-spaces.</p>
     */
    @Test
    void nullableReissueDate_persistsWithNull() {
        // V001 — acct_reissue_date is nullable (the only nullable date in CVACT01Y);
        // acct_expiration_date is NOT NULL per V001:L284
        Account a = buildAccount(10000000006L, new BigDecimal("1000.00"), new BigDecimal("5000.00"));
        // buildAccount already sets acctReissueDate to null; assert the precondition for clarity
        assertThat(a.getAcctReissueDate()).isNull();
        // acctExpirationDate is required (NOT NULL); buildAccount populates it.
        assertThat(a.getAcctExpirationDate()).isNotNull();

        // Act
        repository.save(a);
        entityManager.flush();
        entityManager.clear();

        // Assert: reissue date persists as null; expiration date persists with its set value
        Optional<Account> reloaded = repository.findById(10000000006L);
        assertThat(reloaded).isPresent();
        Account reloadedAccount = reloaded.get();
        assertThat(reloadedAccount.getAcctReissueDate()).isNull();
        assertThat(reloadedAccount.getAcctExpirationDate()).isEqualTo(LocalDate.of(2034, 12, 31));
        // Open date is NOT NULL and was set in the fixture
        assertThat(reloadedAccount.getAcctOpenDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    // =========================================================================
    // Test 7 — deleteById removes the row
    // =========================================================================

    /**
     * Validates the {@code deleteById} contract: a previously persisted
     * Account row can be deleted and is no longer retrievable by
     * primary key.
     *
     * <p><strong>Production caveat (AAP &sect;0.7.3 Minimal Change
     * Clause):</strong> the COBOL source treats accounts as
     * <em>logically closed</em> via {@code acctActiveStatus = 'N'}
     * rather than physically deleted. Real production flows preserve
     * this behaviour. {@code deleteById} is exposed by
     * {@link org.springframework.data.jpa.repository.JpaRepository}
     * for completeness and operational scenarios (test fixture
     * teardown, GDPR right-to-erasure) but is not used in routine
     * business flows.</p>
     */
    @Test
    void deleteById_removesAccount() {
        // Arrange: persist an Account
        Account a = buildAccount(10000000007L, new BigDecimal("1000.00"), new BigDecimal("5000.00"));
        repository.save(a);
        entityManager.flush();
        assertThat(repository.findById(10000000007L)).isPresent();

        // Act: delete by primary key + flush to issue the DELETE
        repository.deleteById(10000000007L);
        entityManager.flush();

        // Assert: row no longer present
        assertThat(repository.findById(10000000007L)).isEmpty();
    }

    // =========================================================================
    // Test 8 — existsById contract
    // =========================================================================

    /**
     * Validates the {@code existsById} contract: returns {@code true}
     * for a persisted account, {@code false} for an absent account ID.
     *
     * <p>Used by {@code TransactionPostingService} during the XREF
     * &rarr; Account validation hand-off at Stage 2 of the 4-stage
     * validation cascade (per AAP &sect;0.1.1, reject code 101 for
     * "account not found"). The check is cheap (the underlying
     * Hibernate query is {@code SELECT 1 ... LIMIT 1}) and avoids the
     * overhead of materialising the full entity when only existence is
     * needed.</p>
     */
    @Test
    void existsById_returnsTrueWhenPresent_falseWhenAbsent() {
        // Arrange: persist an Account
        Account a = buildAccount(10000000008L, new BigDecimal("1000.00"), new BigDecimal("5000.00"));
        repository.save(a);
        entityManager.flush();

        // Assert
        assertThat(repository.existsById(10000000008L)).isTrue();
        assertThat(repository.existsById(99999999999L)).isFalse();
    }

    // =========================================================================
    // Test 9 — count returns the row count
    // =========================================================================

    /**
     * Validates the {@code count} contract: returns the total number of
     * rows in the {@code accounts} table.
     *
     * <p>Used by operational dashboards and reporting flows
     * ({@code AccountFileReaderService} reports its scan progress as
     * "row k of {@code count}"). The underlying Hibernate query is
     * {@code SELECT COUNT(*) FROM accounts}; PostgreSQL plans this as
     * an index-only scan when {@code accounts_visibility} is current,
     * making it efficient for moderately-sized tables.</p>
     *
     * <p>Per AAP &sect;0.6.2, V001 does NOT seed account master data
     * via Flyway &mdash; bulk loading is performed by an AWS Glue Spark
     * job. The expected row count in this test is therefore solely the
     * three rows this test inserts; the {@code @DataJpaTest} slice rolls
     * back at test-method exit so earlier tests in the same class do
     * NOT contribute to the count.</p>
     */
    @Test
    void count_returnsRowCount() {
        // Arrange: persist 3 distinct accounts
        repository.save(buildAccount(10000000009L, new BigDecimal("1000.00"), new BigDecimal("5000.00")));
        repository.save(buildAccount(10000000010L, new BigDecimal("2000.00"), new BigDecimal("6000.00")));
        repository.save(buildAccount(10000000011L, new BigDecimal("3000.00"), new BigDecimal("7000.00")));
        entityManager.flush();

        // Assert: count reflects the 3 inserted rows
        // @DataJpaTest rolls back the surrounding transaction at test-method exit
        // so earlier tests' inserts have not been committed to the test container's accounts table.
        assertThat(repository.count()).isEqualTo(3L);
    }

    // =========================================================================
    // Test 10 — BigDecimal arithmetic preserves scale via HALF_EVEN
    // =========================================================================

    /**
     * Validates that {@link RoundingMode#HALF_EVEN HALF_EVEN} (banker's
     * rounding) at the arithmetic boundary preserves the canonical
     * {@code scale = 2} round-trip through {@code NUMERIC(12,2)}.
     *
     * <p>Per AAP &sect;0.6.1, ALL monetary arithmetic in the Java target
     * MUST use {@link BigDecimal} with
     * {@link RoundingMode#HALF_EVEN HALF_EVEN} at every arithmetic
     * boundary to preserve parity with COBOL {@code PIC 9} decimal-
     * arithmetic semantics. The choice of {@code HALF_EVEN} (rather than
     * {@code HALF_UP} or {@code HALF_DOWN}) reflects the COBOL default
     * "round to nearest even" behaviour for {@code ROUNDED} arithmetic
     * &mdash; the SAME rounding mode the IBM Enterprise COBOL runtime
     * applies to {@code COMPUTE ... ROUNDED} statements.</p>
     *
     * <p>The test multiplies {@code 100.00 * 0.12345} = {@code 12.345}
     * which, when rounded to scale 2 with HALF_EVEN, becomes
     * {@code 12.34} (the trailing 5 rounds DOWN to the nearest even
     * digit, here 4 vs 5 &rarr; 4). This is the canonical HALF_EVEN
     * test case that distinguishes it from HALF_UP (which would round
     * to {@code 12.35}).</p>
     *
     * <p><strong>Note on test independence:</strong> the production
     * service classes ({@code InterestCalculationService},
     * {@code BillPaymentService}, {@code TransactionPostingService})
     * each apply HALF_EVEN at their own arithmetic boundary. This
     * repository test directly exercises {@link BigDecimal} arithmetic
     * within the test method itself to demonstrate the rounding
     * boundary &mdash; the repository merely persists the already-
     * rounded value.</p>
     */
    @Test
    void arithmeticPreservesScale_halfEvenRounding() {
        // AAP §0.6.1 — RoundingMode.HALF_EVEN at every arithmetic boundary
        // Canonical HALF_EVEN test: 100.00 * 0.12345 = 12.345 → rounds to 12.34 (round to nearest even)
        // HALF_UP would round to 12.35; HALF_EVEN rounds the trailing 5 to the NEAREST EVEN digit
        BigDecimal rounded = BigDecimal.valueOf(100)
                .multiply(new BigDecimal("0.12345"))
                .setScale(2, RoundingMode.HALF_EVEN);

        // Sanity assertion: the arithmetic yields the expected rounded value
        assertThat(rounded).usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("12.34"));
        assertThat(rounded.scale()).isEqualTo(2);

        // Arrange + Act: persist the rounded value through NUMERIC(12,2)
        Account a = buildAccount(10000000012L, new BigDecimal("100.00"), new BigDecimal("5000.00"));
        a.setAcctCurrBal(rounded);
        repository.save(a);
        entityManager.flush();
        entityManager.clear();

        // Assert: round-trip preserves the rounded value via NUMERIC(12,2)
        Optional<Account> reloaded = repository.findById(10000000012L);
        assertThat(reloaded).isPresent();
        BigDecimal storedBalance = reloaded.get().getAcctCurrBal();
        assertThat(storedBalance).usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("12.34"));
        assertThat(storedBalance.scale()).isEqualTo(2);
    }
}
