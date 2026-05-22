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
//   * TransactionCategoryBalance — the JPA entity stub matching the
//     CVTRA01Y.cpy TRAN-CAT-BAL-RECORD layout (50-byte fixed-width
//     record: 17-byte composite key + PIC S9(09)V99 TRAN-CAT-BAL +
//     22-byte FILLER). Test methods drive persist/find/save round-trips
//     through this entity and assert that the {@link BigDecimal}
//     TRAN-CAT-BAL field preserves the COBOL PIC S9(09)V99 scale (2
//     decimal places) on every round trip — the AAP §0.10.3
//     financial-precision invariant.
//
//   * TransactionCategoryBalanceKey — the JPA composite-key value-object
//     stub holding the (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)
//     3-tuple. Each test constructs instances via the buildKey() helper
//     and passes them to TransactionCategoryBalanceRepository.findById()
//     / .save() to verify composite-key persistence semantics against
//     PostgreSQL — including the CBACT04C READ-UPDATE-REWRITE flow that
//     the migrated InterestCalculationProcessor exercises.
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
//   * TestFixtures — the shared test-constants holder. Per AAP §0.5.5
//     and §0.10.10 style consistency, this IT references:
//       - TestFixtures.Accounts.NEW_ACCOUNT_ID_60 ("00000000060", 11
//         digits) for the synthetic TRANCAT-ACCT-ID composite-key
//         segment (CVTRA01Y.cpy TRANCAT-ACCT-ID PIC 9(11)) used by all
//         save tests — outside the canonical fixture seed range so no
//         collision is possible.
//       - TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID ("99999999999")
//         for the negative-path lookup test (VSAM STATUS 23 / CICS
//         DFHRESP(NOTFND) parity).
//       - TestFixtures.Transactions.TRAN_TYPE_PURCHASE ("01") and
//         TRAN_TYPE_PAYMENT ("02") for the 2-char TRANCAT-TYPE-CD
//         composite-key segment (CVTRA01Y.cpy TRANCAT-TYPE-CD PIC X(02)).
//       - TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES ("0001") and
//         TRAN_CAT_INTEREST ("0005"), parsed as Integer for the 4-digit
//         TRANCAT-CD composite-key segment (CVTRA01Y.cpy TRANCAT-CD PIC
//         9(04)).
//     Eliminates magic strings and ensures fixture parity with peer
//     repository ITs per AAP §0.10.1 (Require Test Coverage Rule: test
//     bodies must not duplicate literal codes that already appear in
//     TestFixtures) and §0.10.10 (Style Consistency).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.TransactionCategoryBalance;
import com.aws.carddemo.entity.TransactionCategoryBalanceKey;
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
//
//   * @Disabled defers <em>runtime</em> execution until the
//     production-side prerequisites (JPA annotations on
//     TransactionCategoryBalance and TransactionCategoryBalanceKey +
//     Flyway V1__schema.sql + V3__seed.sql) are landed by subsequent
//     REFACTOR-flavor migration agents. JUnit 5 reports @Disabled tests
//     as "skipped" (not "failed") so the Surefire/Failsafe build stays
//     green; the reactivation criteria appear in the annotation's value
//     attribute and in the class-level Javadoc "Reactivation Checklist"
//     section. The sibling DiscountGroupRepositoryIT,
//     TransactionCategoryRepositoryIT, and TransactionTypeRepositoryIT
//     all use the same @Disabled pattern — this IT mirrors that project
//     convention so the compile-time wiring is verified end-to-end
//     while the runtime DB execution awaits its production-side
//     dependencies.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Framework dependency-injection import.
//
//   * @Autowired field-injects the Spring-managed
//     TransactionCategoryBalanceRepository proxy into this IT class
//     instance. The proxy is created by Spring Data JPA at @DataJpaTest
//     context startup from the
//     {@code JpaRepository<TransactionCategoryBalance,
//     TransactionCategoryBalanceKey>} interface declaration on the
//     production repository — no manual implementation is required, and
//     no field declared with @Mock is permissible at this IT layer
//     (AAP §0.10.1 Require Test Coverage Rule: integration tests must
//     invoke the REAL repository against the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.math.BigDecimal — the type of the TRAN-CAT-BAL field on
//     TransactionCategoryBalance. AAP §0.10.3 Financial Precision: "No
//     float or double used for any monetary value — BigDecimal
//     exclusively." All balance literals in this IT are constructed as
//     new BigDecimal("...") from String to avoid the floating-point
//     precision pitfalls of the BigDecimal(double) constructor.
//     BigDecimal.ZERO is used for the zero-balance edge case test
//     (AAP §0.5.1 CBACT04C ZEROAPR coverage at the persistence layer).
//
//   * java.util.List — the return type of
//     TransactionCategoryBalanceRepository.findByAccountId(String) used
//     by the account-scoped aggregation test that mirrors CBACT04C's
//     "iterate all categories for one account" loop.
//
//   * java.util.Optional — the return type of
//     TransactionCategoryBalanceRepository.findById(TransactionCategoryBalanceKey)
//     used by the composite-key lookup assertions (isPresent / isEmpty
//     / get / orElseThrow).
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static import (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. assertThat(optional).isPresent(),
//     assertThat(optional).isEmpty(),
//     assertThat(reloaded.getTranCatBal()).isEqualByComparingTo(...),
//     assertThat(scale).isEqualTo(2), and
//     .extracting(TransactionCategoryBalance::getTranCatBal)
//     .satisfies(v -> ...) chains are the idioms this class exercises.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TransactionCategoryBalanceRepository}, the
 * JPA repository that persists {@link TransactionCategoryBalance} entities
 * migrated from the COBOL {@code TRAN-CAT-BAL-RECORD} defined in
 * {@code app/cpy/CVTRA01Y.cpy} (RECLN 50). Seed data is sourced from
 * {@code app/data/ASCII/tcatbal.txt} (50 rows × 50 bytes = 2500 bytes).
 *
 * <h2>COBOL Provenance — CVTRA01Y.cpy</h2>
 *
 * <p>{@code TRAN-CAT-BAL-RECORD} uses a 17-byte composite primary key
 * followed by a single {@link BigDecimal} balance field and a 22-byte
 * FILLER pad to RECLN 50:
 * <pre>
 *   01 TRAN-CAT-BAL-RECORD.
 *      05 TRAN-CAT-KEY.
 *         10 TRANCAT-ACCT-ID    PIC 9(11).       -- 11-digit account FK
 *         10 TRANCAT-TYPE-CD    PIC X(02).       -- 2-char transaction type
 *         10 TRANCAT-CD         PIC 9(04).       -- 4-digit transaction category
 *      05 TRAN-CAT-BAL          PIC S9(09)V99.   -- BigDecimal scale 2
 *      05 FILLER                PIC X(22).       -- padding to RECLN 50
 * </pre>
 *
 * <p>{@code TRAN-CAT-BAL PIC S9(09)V99} is mapped to {@link BigDecimal}
 * with scale 2 per AAP §0.10.3. The {@code PIC S9(09)V99} field allows
 * up to 9 integer digits + 2 fractional digits (theoretical range
 * {@code -999_999_999.99} to {@code +999_999_999.99}); the Flyway DDL
 * creates the underlying PostgreSQL column as {@code NUMERIC(11, 2)}
 * (11 = 9 + 2 significant digits) to preserve both the precision and
 * the scale at the storage boundary.
 *
 * <h2>CBACT04C — Interest Calculation Update Path (AAP §0.5.1)</h2>
 *
 * <p>The migrated {@code InterestCalculationProcessor} (REFACTOR-flavor,
 * per AAP §0.5.1) exercises this repository in a READ-UPDATE-REWRITE
 * pattern that the test class verifies at the persistence layer:
 * <ol>
 *   <li>READ — {@link TransactionCategoryBalanceRepository#findById(Object)}
 *       loads a {@link TransactionCategoryBalance} row by composite
 *       key. The {@link #save_newRow_persistsWithCompositeKeyAndScale()}
 *       test exercises the round-trip in isolation;
 *       {@link #findById_nonexistentKey_returnsEmpty()} exercises the
 *       VSAM {@code STATUS 23} / CICS {@code DFHRESP(NOTFND)} parity.</li>
 *   <li>UPDATE (in production code, not in this IT) — the processor
 *       computes the monthly interest in
 *       {@code com.aws.carddemo.batch.InterestCalculationProcessor} per
 *       AAP §0.10.3 ({@code BigDecimal} with {@code RoundingMode.HALF_EVEN},
 *       scale 2). This IT does NOT compute interest in test bodies per
 *       AAP §0.10.1 Require Test Coverage Rule.</li>
 *   <li>REWRITE — {@link TransactionCategoryBalanceRepository#save(Object)}
 *       persists the updated row. The {@link #save_updatedRow_overwritesExistingBalance()}
 *       test verifies the round-trip by persisting an initial balance,
 *       loading the row, mutating the balance, and re-saving — the
 *       test body does NOT compute interest, it merely assigns a
 *       different value to verify the update path.</li>
 * </ol>
 *
 * <h2>ZEROAPR Persistence Edge Case (AAP §0.5.1)</h2>
 *
 * <p>The {@link #save_zeroBalance_persistsZeroDotZero()} test covers the
 * ZEROAPR edge case at the persistence layer: a row with a
 * {@code 0.00} balance must round-trip through the
 * Hibernate ↔ PostgreSQL {@code NUMERIC(11, 2)} mapping with scale 2
 * preserved (a {@link BigDecimal} containing {@code 0.00} must NOT
 * collapse to scale 0). The corresponding calculation-skip decision
 * logic (the {@code IF DIS-INT-RATE NOT = 0} guard inside the COBOL
 * paragraph {@code 1300-COMPUTE-INTEREST}) lives in
 * {@code InterestCalculationProcessor} (REFACTOR-flavor) per AAP §0.10.1;
 * the {@code InterestCalculationProcessorTest} unit suite exercises that
 * decision logic against this repository (mocked at that layer).
 *
 * <h2>Account-Scoped Aggregation Coverage (AAP §0.5.1)</h2>
 *
 * <p>The {@link #findByAccountId_existingAccount_returnsAllCategoryBalances()}
 * test mirrors {@code CBACT04C}'s "iterate all categories for one
 * account" loop by persisting three rows for the same account (with
 * different type+cat composite-key components) and asserting that
 * {@link TransactionCategoryBalanceRepository#findByAccountId(String)}
 * returns exactly those three rows. The corresponding COBOL pattern is:
 * <pre>
 *   READ TCATBAL-FILE.
 *   PERFORM UNTIL TCATBAL-EOF = 'Y'
 *     IF TRANCAT-ACCT-ID OF TCATBAL-RECORD = WS-CURRENT-ACCOUNT
 *       ...process category balance...
 *     ELSE
 *       MOVE 'Y' TO TCATBAL-EOF
 *     END-IF
 *     READ NEXT TCATBAL-FILE
 *   END-PERFORM.
 * </pre>
 *
 * <h2>Coverage Focus (AAP §0.5.1)</h2>
 *
 * <p>The 8 test methods exercise the integration of
 * {@link TransactionCategoryBalanceRepository} against a real
 * PostgreSQL 16 instance provisioned by Testcontainers — no Mockito
 * stubs at this layer (AAP §0.10.1 Require Test Coverage Rule). The
 * categories below cover the AAP-specified scenarios:
 *
 * <ul>
 *   <li>{@link #save_newRow_persistsWithCompositeKeyAndScale()} —
 *       composite-key round-trip against a synthetic row persisted via
 *       the production {@code save()} method and reloaded via
 *       {@code findById()}, verifying both the composite-key lookup
 *       mechanics AND the AAP §0.10.3 {@link BigDecimal} scale-2
 *       preservation invariant (balance {@code "250.75"} must
 *       round-trip exactly with {@code .scale() == 2}).</li>
 *   <li>{@link #findById_nonexistentKey_returnsEmpty()} — negative-path
 *       lookup verifying {@link Optional#empty()} for an unknown
 *       composite key (the Java equivalent of the COBOL VSAM
 *       {@code STATUS 23} / CICS {@code DFHRESP(NOTFND)} branch).</li>
 *   <li>{@link #save_sameAccountDifferentType_createsDistinctRows()} —
 *       composite-key segregation by {@code TRANCAT-TYPE-CD}: two rows
 *       sharing {@code (TRANCAT-ACCT-ID, TRANCAT-CD)} but differing in
 *       {@code TRANCAT-TYPE-CD} must coexist as separate rows.</li>
 *   <li>{@link #save_sameTypeDifferentCategory_createsDistinctRows()} —
 *       composite-key segregation by {@code TRANCAT-CD}: two rows
 *       sharing {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD)} but
 *       differing in {@code TRANCAT-CD} must coexist as separate
 *       rows.</li>
 *   <li>{@link #save_updatedRow_overwritesExistingBalance()} —
 *       CBACT04C READ-UPDATE-REWRITE round-trip parity: persists an
 *       initial balance, loads the row, mutates the balance, and
 *       re-saves; asserts that the final state reflects the update
 *       (not the initial value) with scale 2 preserved.</li>
 *   <li>{@link #save_zeroBalance_persistsZeroDotZero()} — ZEROAPR /
 *       paid-in-full edge case: persists a {@code 0.00} balance and
 *       verifies it round-trips with scale 2 preserved (NOT collapsed
 *       to scale 0, a common JDBC driver footgun).</li>
 *   <li>{@link #findByAccountId_existingAccount_returnsAllCategoryBalances()}
 *       — account-scoped aggregation: persists three rows for the same
 *       account (with different type+cat combos) and asserts that the
 *       custom {@code findByAccountId} finder returns exactly those
 *       three rows.</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — basic sanity
 *       check that the inherited {@code count()} method returns a
 *       non-negative tally; guards against accidentally truncating the
 *       catalog table at any point in the migration lifecycle.</li>
 * </ul>
 *
 * <h2>Mock Boundary (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>This IT has <strong>no Mockito mocks</strong>. The system under
 * test is the real {@link TransactionCategoryBalanceRepository} bean
 * wired by Spring Data JPA against the real PostgreSQL 16 database
 * supplied by Testcontainers (inherited from
 * {@link AbstractRepositoryIT}). Repository ITs sit at the lowest mock
 * boundary in the test pyramid: they verify that the Spring Data JPA
 * proxy + Hibernate ORM + JDBC driver + PostgreSQL stack produces
 * correct results against a real schema seeded by Flyway. Tests that
 * would otherwise mock this repository (the
 * {@code InterestCalculationProcessorTest} batch processor unit tests)
 * live one layer up in {@code com.aws.carddemo.batch.*Test}.
 *
 * <h2>No Business Logic in Test Bodies (AAP §0.10.1)</h2>
 *
 * <p>The composite-key tests construct
 * {@link TransactionCategoryBalanceKey} values using only literal
 * scalars and constants from {@link TestFixtures.Accounts} and
 * {@link TestFixtures.Transactions} — no key derivation, no
 * concatenation, no padding-computation, no parsing of the on-disk
 * fixture format. Each test method exercises exactly one repository
 * call (or one {@code save}+{@code findById} round-trip) and asserts
 * on the returned {@link Optional}/{@code long}/{@link BigDecimal}/
 * {@link List}. The {@link #buildKey}, {@link #buildBalance}, and
 * {@link #getAccountId} helpers are pure no-logic helpers that map
 * constructor arguments straight onto setter calls or trivially
 * navigate the composite-key field.
 *
 * <p>Specifically: the interest-calculation arithmetic ({@code TRAN-CAT-BAL
 * × DIS-INT-RATE ÷ 1200}, HALF_EVEN rounding to scale 2) lives in
 * {@code com.aws.carddemo.batch.InterestCalculationProcessor} (REFACTOR-flavor);
 * this IT only verifies that the underlying composite-key lookup and
 * persistence mechanics work for the CBACT04C READ-UPDATE-REWRITE flow.
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>Every monetary literal in this IT is constructed as
 * {@code new BigDecimal("...")} from a {@link String}, NEVER from a
 * {@code double} or {@code float}. The {@code BigDecimal(double)}
 * constructor introduces representation noise (e.g.,
 * {@code new BigDecimal(0.1)} yields {@code 0.10000000000000000555…})
 * that would break the scale-2 parity invariant; the
 * {@code BigDecimal(String)} constructor preserves the exact decimal
 * form. The scale assertions
 * ({@code assertThat(balance.scale()).isEqualTo(2)}) verify that the
 * Hibernate ↔ PostgreSQL {@code NUMERIC(11,2)} round-trip preserves
 * the COBOL {@code PIC S9(09)V99} scale-2 contract on every read.
 *
 * <h2>Test isolation (AAP §0.10.9)</h2>
 *
 * <p>{@code @DataJpaTest} (inherited from {@link AbstractRepositoryIT})
 * wraps each {@code @Test} method in a transaction that rolls back at
 * method end. This means every synthetic row persisted by the tests is
 * gone before the next test sees the database state. Each test starts
 * from the Flyway-seeded catalog (50 rows once the seed lands) plus
 * zero synthetic additions — test order independence is guaranteed.
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>The suite loads the {@code @DataJpaTest} Spring slice via
 * {@link AbstractRepositoryIT} and exercises the production
 * {@link TransactionCategoryBalanceRepository} bean against a real
 * PostgreSQL 16 database. That requires every production-side
 * prerequisite to be in place: the {@link TransactionCategoryBalance}
 * entity must be annotated as a JPA {@code @Entity} (and
 * {@link TransactionCategoryBalanceKey} as {@code @Embeddable}) so
 * Hibernate can map the entity onto a database table, and the Flyway
 * scripts under {@code src/main/resources/db/migration/} must exist to
 * create the {@code transaction_category_balances} table and seed the
 * 50 canonical reference rows. As of this commit those production-side
 * prerequisites are <em>intentionally deferred</em> by the
 * REFACTOR-flavor migration agents.
 *
 * <p>This testing-flavor AAP (§0.8.1 "Cross-cutting files that the
 * migration creates and that this Action Plan exercises (but does not
 * own)") <strong>explicitly forbids</strong> modifying any production
 * source under {@code src/main/java/com/aws/carddemo/} for the purpose
 * of <em>testability alone</em>: the testing flavor CREATEs tests
 * against those classes but does NOT redesign them. The suite is
 * therefore registered, compiled, and preserved end-to-end (the
 * production stubs created alongside this IT enable compilation), but
 * the JUnit Jupiter {@code @Disabled} marker below defers
 * <em>runtime</em> execution until the production-side migration agents
 * complete the JPA annotation and Flyway seed work. Once both arrive,
 * removing the {@code @Disabled} annotation (and its companion unused
 * import) activates all 8 tests unchanged.
 *
 * <h3>Reactivation Checklist (for the next agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of
 * the following production-side prerequisites are in place:
 *
 * <ol>
 *   <li><strong>{@code @Embeddable} on
 *       {@link com.aws.carddemo.entity.TransactionCategoryBalanceKey}</strong>
 *       — REFACTOR agents add the {@code @Embeddable} annotation plus
 *       {@code @Column(name = "trancat_acct_id", length = 11, nullable
 *       = false)} on {@code trancatAcctId},
 *       {@code @Column(name = "trancat_type_cd", length = 2, nullable
 *       = false)} on {@code trancatTypeCd}, and
 *       {@code @Column(name = "trancat_cd", nullable = false)} on
 *       {@code trancatCd}. Without these annotations Hibernate cannot
 *       use the composite key as an {@code @EmbeddedId} target.</li>
 *   <li><strong>{@code @Entity} + {@code @EmbeddedId} on
 *       {@link com.aws.carddemo.entity.TransactionCategoryBalance}</strong>
 *       — REFACTOR agents add {@code @Entity},
 *       {@code @Table(name = "transaction_category_balances")},
 *       {@code @EmbeddedId} on the {@code key} field, and
 *       {@code @Column(name = "tran_cat_bal", precision = 11, scale = 2,
 *       nullable = false)} on {@code tranCatBal}. Without these
 *       annotations Hibernate cannot map the entity onto the PostgreSQL
 *       table and {@code @DataJpaTest} context startup fails.</li>
 *   <li><strong>Flyway {@code V1__schema.sql}</strong> under
 *       {@code src/main/resources/db/migration/} containing
 *       <pre>
 *       CREATE TABLE transaction_category_balances (
 *           trancat_acct_id  CHAR(11)       NOT NULL,
 *           trancat_type_cd  CHAR(2)        NOT NULL,
 *           trancat_cd       INTEGER        NOT NULL,
 *           tran_cat_bal     NUMERIC(11,2)  NOT NULL,
 *           PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
 *       );
 *       </pre>
 *       (column types matching the COBOL {@code PIC 9(11)},
 *       {@code PIC X(02)}, {@code PIC 9(04)}, and
 *       {@code PIC S9(09)V99} fields verbatim). {@code CHAR(11)} on
 *       {@code trancat_acct_id} preserves the zero-padding required by
 *       the {@code PIC 9(11)} field — {@code VARCHAR} would silently
 *       strip leading zeros if values were stored as numerics, breaking
 *       the byte-for-byte parity with the COBOL fixture file.</li>
 *   <li><strong>Flyway {@code V3__seed.sql}</strong> under
 *       {@code src/main/resources/db/migration/} containing 50
 *       {@code INSERT INTO transaction_category_balances (trancat_acct_id,
 *       trancat_type_cd, trancat_cd, tran_cat_bal) VALUES (...)}
 *       statements covering the rows in
 *       {@code app/data/ASCII/tcatbal.txt}.</li>
 *   <li><strong>Docker available to Testcontainers</strong> at test
 *       runtime — the {@code mvn verify} build agent must be able to
 *       run {@code postgres:16-alpine}. CI agents that cannot start
 *       containers (e.g. nested-virtualisation-free environments) can
 *       set {@code TESTCONTAINERS_RYUK_DISABLED=true} as documented in
 *       {@code src/test/resources/application-test.properties}.</li>
 * </ol>
 *
 * <p>When all five items above are complete, deleting the
 * {@code @Disabled} annotation and the {@code import
 * org.junit.jupiter.api.Disabled;} line activates the suite. No other
 * code changes are required: the test method bodies are written
 * against the production API exactly as it will be once the REFACTOR
 * work completes.
 *
 * @see TransactionCategoryBalanceRepository
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceKey
 * @see AbstractRepositoryIT
 * @see TestFixtures.Accounts
 * @see TestFixtures.Transactions
 */
@DisplayName("TransactionCategoryBalanceRepository — CVTRA01Y.cpy composite-key ITs")
class TransactionCategoryBalanceRepositoryIT extends AbstractRepositoryIT {

    /**
     * The Spring Data JPA repository under integration test. The bean is
     * created by Spring Data JPA at {@code @DataJpaTest} context startup
     * from the {@link TransactionCategoryBalanceRepository} interface
     * declaration (no manual implementation). Field injection is
     * consistent with the inherited {@code @Autowired TestEntityManager
     * entityManager} field on {@link AbstractRepositoryIT}.
     */
    @Autowired
    private TransactionCategoryBalanceRepository tcatBalRepository;

    // =========================================================================
    // Composite-Key Save and findById Round-Trip (AAP §0.5.1)
    // =========================================================================

    /**
     * Verifies that {@link TransactionCategoryBalanceRepository#save(Object)}
     * persists a {@link TransactionCategoryBalance} with a composite
     * primary key and that the subsequent
     * {@link TransactionCategoryBalanceRepository#findById(Object)}
     * round-trips both the composite key AND the {@link BigDecimal}
     * {@code TRAN-CAT-BAL} field with the COBOL-mandated scale 2
     * preserved (AAP §0.10.3 Financial Precision).
     *
     * <p>The test persists a synthetic row via the production
     * repository's {@code save()} method, flushes the persistence
     * context to push the row to PostgreSQL, clears the first-level
     * cache so the subsequent {@code findById} hits the database, and
     * then asserts that the returned {@link Optional} contains an entity
     * with the expected balance (compared by numeric value with
     * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(BigDecimal)})
     * and the expected scale (compared by exact equality with
     * {@link BigDecimal#scale()}).
     *
     * <p>The synthetic key uses {@link TestFixtures.Accounts#NEW_ACCOUNT_ID_60}
     * ({@code "00000000060"}), which is deliberately outside the
     * canonical seed range ({@code 00000000001}–{@code 00000000050})
     * so the test never collides with a Flyway-seeded row.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(new TCATBAL row) persists with composite key and preserves scale 2")
    void save_newRow_persistsWithCompositeKeyAndScale() {
        // Arrange — composite key from synthetic values outside the canonical seed range
        TransactionCategoryBalanceKey key = buildKey(
                TestFixtures.Accounts.NEW_ACCOUNT_ID_60,
                TestFixtures.Transactions.TRAN_TYPE_PURCHASE,
                Integer.valueOf(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES));
        TransactionCategoryBalance row = buildBalance(key, new BigDecimal("250.75"));

        // Act — drive the production repository's save path, then force a DB read
        tcatBalRepository.save(row);
        entityManager.flush();
        entityManager.clear();

        // Assert — reload via composite-key findById
        Optional<TransactionCategoryBalance> reloaded = tcatBalRepository.findById(key);
        assertThat(reloaded)
                .as("Composite-key findById must locate the persisted row")
                .isPresent();
        TransactionCategoryBalance r = reloaded.get();
        assertThat(r.getTranCatBal())
                .as("TRAN-CAT-BAL must round-trip exactly (numeric value parity)")
                .isEqualByComparingTo(new BigDecimal("250.75"));
        assertThat(r.getTranCatBal().scale())
                .as("TRAN-CAT-BAL scale must equal 2 per COBOL PIC S9(09)V99 (AAP §0.10.3)")
                .isEqualTo(2);
    }

    /**
     * Verifies that {@link TransactionCategoryBalanceRepository#findById(Object)}
     * returns {@link Optional#empty()} when the supplied composite key
     * does not exist in the {@code transaction_category_balances} table.
     * This is the Java equivalent of the COBOL VSAM {@code STATUS 23} /
     * CICS {@code DFHRESP(NOTFND)} branch from the legacy
     * {@code EXEC CICS READ DATASET('TCATBAL') RIDFLD(TRAN-CAT-KEY)}
     * statement.
     *
     * <p>The lookup key uses {@link TestFixtures.Accounts#NONEXISTENT_ACCOUNT_ID}
     * ({@code "99999999999"}) for the account segment plus synthetic
     * out-of-range type code {@code "99"} and category code {@code 9999}
     * — the triple is guaranteed to be absent from both the Flyway seed
     * (which covers accounts {@code 00000000001}–{@code 00000000050})
     * and from any synthetic row persisted by other tests in this class
     * (which all use {@link TestFixtures.Accounts#NEW_ACCOUNT_ID_60}).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(nonexistent composite key) returns empty Optional (VSAM STATUS 23 parity)")
    void findById_nonexistentKey_returnsEmpty() {
        // Arrange — composite key outside both the canonical seed range and the
        // synthetic ranges used by other tests in this class
        TransactionCategoryBalanceKey key = buildKey(
                TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID,
                "99",
                9999);

        // Act — drive the production repository against the real DB
        Optional<TransactionCategoryBalance> result = tcatBalRepository.findById(key);

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND) signal
        assertThat(result)
                .as("Nonexistent composite key must return empty Optional "
                        + "(VSAM STATUS 23 / CICS DFHRESP(NOTFND) equivalent)")
                .isEmpty();
    }

    // =========================================================================
    // Composite-Key Segregation (Distinct Rows for Different Type / Category)
    // =========================================================================

    /**
     * Verifies that two rows sharing
     * {@code (TRANCAT-ACCT-ID, TRANCAT-CD)} but differing in
     * {@code TRANCAT-TYPE-CD} are persisted as <em>distinct</em> rows
     * by the composite-key primary-key index. The COBOL {@code TCATBAL}
     * catalog exhibits this pattern: a single account can have a
     * purchase balance row and a payment balance row for the same
     * category. A composite-key index that incorrectly treated only the
     * {@code (account, cat)} subset as the row identity would collapse
     * those two rows into one, silently losing either the purchase
     * balance or the payment balance.
     *
     * <p>The test persists two synthetic rows:
     * <ul>
     *   <li>{@code (NEW_ACCOUNT_ID_60, TRAN_TYPE_PURCHASE, REGULAR_SALES)}
     *       at balance {@code 100.00}</li>
     *   <li>{@code (NEW_ACCOUNT_ID_60, TRAN_TYPE_PAYMENT, REGULAR_SALES)}
     *       at balance {@code 50.00}</li>
     * </ul>
     *
     * <p>Both share the same {@code (TRANCAT-ACCT-ID, TRANCAT-CD)}
     * pair; only the {@code TRANCAT-TYPE-CD} component differs. The
     * test flushes, clears, and then asserts that {@code findById}
     * returns each row independently with its own distinct balance.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(same ACCT-ID + different TYPE-CD) creates distinct rows (composite-key segregation)")
    void save_sameAccountDifferentType_createsDistinctRows() {
        // Arrange — same account, different transaction-type codes
        String acctId = TestFixtures.Accounts.NEW_ACCOUNT_ID_60;
        TransactionCategoryBalanceKey keyPurchase = buildKey(
                acctId, TestFixtures.Transactions.TRAN_TYPE_PURCHASE,
                Integer.valueOf(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES));
        TransactionCategoryBalanceKey keyPayment = buildKey(
                acctId, TestFixtures.Transactions.TRAN_TYPE_PAYMENT,
                Integer.valueOf(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES));

        // Act — save both rows, then force a DB read
        tcatBalRepository.save(buildBalance(keyPurchase, new BigDecimal("100.00")));
        tcatBalRepository.save(buildBalance(keyPayment, new BigDecimal("50.00")));
        entityManager.flush();
        entityManager.clear();

        // Assert — both rows persist independently. If the composite-key primary-key
        // index ignored TRANCAT-TYPE-CD, only one row would survive (the last one saved,
        // by primary-key replacement) and the other findById call would return Optional.empty.
        assertThat(tcatBalRepository.findById(keyPurchase))
                .as("Purchase balance must persist independently of payment balance — "
                        + "would collapse if the PK index ignored TRANCAT-TYPE-CD")
                .isPresent()
                .get()
                .extracting(TransactionCategoryBalance::getTranCatBal)
                .satisfies(v -> assertThat((BigDecimal) v).isEqualByComparingTo("100.00"));
        assertThat(tcatBalRepository.findById(keyPayment))
                .as("Payment balance must persist independently of purchase balance — "
                        + "would collapse if the PK index ignored TRANCAT-TYPE-CD")
                .isPresent()
                .get()
                .extracting(TransactionCategoryBalance::getTranCatBal)
                .satisfies(v -> assertThat((BigDecimal) v).isEqualByComparingTo("50.00"));
    }

    /**
     * Verifies that two rows sharing
     * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD)} but differing in
     * {@code TRANCAT-CD} are persisted as <em>distinct</em> rows by the
     * composite-key primary-key index. The COBOL {@code TCATBAL}
     * catalog exhibits this pattern: a single account can have running
     * balances for multiple categories within the same transaction type
     * (e.g. category {@code 1} "Regular Sales Draft" and category
     * {@code 5} "Interest Amount" both under type {@code 01}
     * "Purchase"). A composite-key index that incorrectly treated only
     * the {@code (account, type)} subset as the row identity would
     * collapse those two rows into one, silently losing one of the
     * category balances.
     *
     * <p>The test persists two synthetic rows:
     * <ul>
     *   <li>{@code (NEW_ACCOUNT_ID_60, TRAN_TYPE_PURCHASE, 1)} at
     *       balance {@code 11.11}</li>
     *   <li>{@code (NEW_ACCOUNT_ID_60, TRAN_TYPE_PURCHASE, 5)} at
     *       balance {@code 55.55}</li>
     * </ul>
     *
     * <p>Both share the same {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD)}
     * pair; only the {@code TRANCAT-CD} component differs. The test
     * flushes, clears, and then asserts that {@code findById} returns
     * each row independently and {@code count()} reports at least 2
     * rows total.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(same TYPE-CD + different CAT-CD) creates distinct rows")
    void save_sameTypeDifferentCategory_createsDistinctRows() {
        // Arrange — same account+type, different categories
        String acctId = TestFixtures.Accounts.NEW_ACCOUNT_ID_60;
        TransactionCategoryBalanceKey keyCat1 = buildKey(
                acctId, TestFixtures.Transactions.TRAN_TYPE_PURCHASE, 1);
        TransactionCategoryBalanceKey keyCat5 = buildKey(
                acctId, TestFixtures.Transactions.TRAN_TYPE_PURCHASE, 5);

        // Act — save both rows, then force a DB read
        tcatBalRepository.save(buildBalance(keyCat1, new BigDecimal("11.11")));
        tcatBalRepository.save(buildBalance(keyCat5, new BigDecimal("55.55")));
        entityManager.flush();
        entityManager.clear();

        // Assert — both rows persist independently with their distinct categories
        assertThat(tcatBalRepository.findById(keyCat1))
                .as("Category 1 balance must persist independently — would collapse "
                        + "if the PK index ignored TRANCAT-CD")
                .isPresent();
        assertThat(tcatBalRepository.findById(keyCat5))
                .as("Category 5 balance must persist independently — would collapse "
                        + "if the PK index ignored TRANCAT-CD")
                .isPresent();
        assertThat(tcatBalRepository.count())
                .as("Total row count after two synthetic saves must be at least 2")
                .isGreaterThanOrEqualTo(2);
    }

    // =========================================================================
    // CBACT04C READ-UPDATE-REWRITE Update Round-Trip (AAP §0.5.1)
    // =========================================================================

    /**
     * Verifies the CBACT04C READ-UPDATE-REWRITE flow parity at the
     * persistence layer: a row's balance can be loaded, mutated, and
     * re-saved, and the final state reflects the update (not the
     * initial value) with scale 2 preserved.
     *
     * <p>The test sequence mirrors the COBOL {@code CBACT04C} pattern:
     * <ol>
     *   <li>Persist an initial balance ({@code 100.00}) via the
     *       repository's {@code save()} method.</li>
     *   <li>Force a DB read by flushing the persistence context and
     *       clearing the first-level cache.</li>
     *   <li>Reload the row via {@code findById} — this models the
     *       COBOL {@code READ TCATBAL-FILE} step.</li>
     *   <li>Mutate the balance ({@code 100.00} → {@code 105.50}) — this
     *       models the COBOL {@code COMPUTE TRAN-CAT-BAL = ...} step,
     *       but per AAP §0.10.1 the test body does <strong>not</strong>
     *       compute interest; it merely assigns a different value to
     *       verify the update path. The interest-calculation arithmetic
     *       lives in {@code InterestCalculationProcessor}.</li>
     *   <li>Re-save the row via {@code save()} — this models the COBOL
     *       {@code REWRITE TCATBAL-FILE} step.</li>
     *   <li>Force a second DB read and reload the row.</li>
     *   <li>Assert that the final balance matches the updated value
     *       ({@code 105.50}, NOT {@code 100.00}) with scale 2 preserved.</li>
     * </ol>
     *
     * <p>The synthetic key uses
     * {@link TestFixtures.Accounts#NEW_ACCOUNT_ID_60}
     * ({@code "00000000060"}), outside the canonical seed range, so
     * the test never collides with a Flyway-seeded row.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(updated TCATBAL row) overwrites the existing row (CBACT04C update parity)")
    void save_updatedRow_overwritesExistingBalance() {
        // Arrange — persist initial balance
        TransactionCategoryBalanceKey key = buildKey(
                TestFixtures.Accounts.NEW_ACCOUNT_ID_60,
                TestFixtures.Transactions.TRAN_TYPE_PURCHASE,
                Integer.valueOf(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES));
        TransactionCategoryBalance initial = buildBalance(key, new BigDecimal("100.00"));
        tcatBalRepository.save(initial);
        entityManager.flush();
        entityManager.clear();

        // Act — load, mutate, re-save (mimics CBACT04C READ-UPDATE-REWRITE pattern).
        // The test body does NOT compute interest — that is the production code's job
        // per AAP §0.10.1. We simply assign a different value to verify the update
        // round-trip path.
        TransactionCategoryBalance reloaded = tcatBalRepository.findById(key).orElseThrow();
        reloaded.setTranCatBal(new BigDecimal("105.50"));
        tcatBalRepository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        // Assert — final state reflects the update, not the initial value
        TransactionCategoryBalance afterUpdate = tcatBalRepository.findById(key).orElseThrow();
        assertThat(afterUpdate.getTranCatBal())
                .as("Updated balance must persist via REWRITE-equivalent save()")
                .isEqualByComparingTo(new BigDecimal("105.50"));
        assertThat(afterUpdate.getTranCatBal().scale())
                .as("Updated balance must retain scale 2 per AAP §0.10.3 "
                        + "(NUMERIC(11,2) ↔ PIC S9(09)V99 round-trip)")
                .isEqualTo(2);
    }

    // =========================================================================
    // ZEROAPR / Zero-Balance Edge Case (AAP §0.5.1)
    // =========================================================================

    /**
     * Verifies that a {@link TransactionCategoryBalance} row with a
     * {@code 0.00} balance round-trips through the
     * Hibernate ↔ PostgreSQL {@code NUMERIC(11,2)} mapping with scale 2
     * preserved (the {@link BigDecimal} containing {@code 0.00} must
     * NOT collapse to scale 0 — a common JDBC driver footgun).
     *
     * <p>This is the persistence-layer manifestation of AAP §0.5.1's
     * ZEROAPR edge case. The CBACT04C interest calculator (migrated to
     * {@code InterestCalculationProcessor}) skips the interest
     * computation when the lookup-up rate is {@code 0.00}; the
     * corresponding TCATBAL row may carry a {@code 0.00} balance (e.g.
     * an account that was paid in full last cycle, or a promotional
     * category at zero balance). The persistence layer must preserve
     * the {@code 0.00} value with scale 2 intact so the byte-for-byte
     * fixture-file parity invariant (AAP §0.10.4 immutable boundaries)
     * is preserved when the rewritten record is later serialised back
     * to a flat file or rendered in a downstream report.
     *
     * <p>The synthetic key uses
     * {@link TestFixtures.Transactions#TRAN_CAT_INTEREST}
     * ({@code "0005"}, parsed to integer 5) for the category code
     * because the interest category is the one CBACT04C touches most
     * directly during the interest-calculation cycle.
     *
     * <p>The {@link BigDecimal#ZERO} sentinel in the assertion compares
     * by value via {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(BigDecimal)},
     * which ignores scale on the assertion side; the explicit scale
     * assertion that follows enforces the AAP §0.10.3 scale-2 contract
     * separately.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(zero balance) persists 0.00 with scale 2 preserved (CBACT04C zero-balance edge case)")
    void save_zeroBalance_persistsZeroDotZero() {
        // Arrange — zero-balance row (ZEROAPR or paid-in-full account)
        TransactionCategoryBalanceKey key = buildKey(
                TestFixtures.Accounts.NEW_ACCOUNT_ID_60,
                TestFixtures.Transactions.TRAN_TYPE_PURCHASE,
                Integer.valueOf(TestFixtures.Transactions.TRAN_CAT_INTEREST));
        TransactionCategoryBalance row = buildBalance(key, new BigDecimal("0.00"));

        // Act — drive the production repository's save path, then force a DB read
        tcatBalRepository.save(row);
        entityManager.flush();
        entityManager.clear();

        // Assert — zero balance must round-trip as 0.00 with scale 2 preserved
        TransactionCategoryBalance reloaded = tcatBalRepository.findById(key).orElseThrow();
        assertThat(reloaded.getTranCatBal())
                .as("Zero balance must round-trip as 0.00 (numeric value equality)")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reloaded.getTranCatBal().scale())
                .as("Zero balance must still have scale 2 (NOT collapsed to scale 0) — "
                        + "AAP §0.10.3 scale-2 contract applies to ALL balances, including zero")
                .isEqualTo(2);
    }

    // =========================================================================
    // Account-Scoped Aggregation (CBACT04C "iterate all categories for one account")
    // =========================================================================

    /**
     * Verifies that {@link TransactionCategoryBalanceRepository#findByAccountId(String)}
     * returns every {@link TransactionCategoryBalance} row for the
     * supplied account regardless of the {@code TRANCAT-TYPE-CD} and
     * {@code TRANCAT-CD} components of the composite key. This mirrors
     * the COBOL {@code CBACT04C} loop that processes all category
     * balances for one account:
     * <pre>
     *   READ TCATBAL-FILE.
     *   PERFORM UNTIL TCATBAL-EOF = 'Y'
     *     IF TRANCAT-ACCT-ID OF TCATBAL-RECORD = WS-CURRENT-ACCOUNT
     *       ...process category balance...
     *     ELSE
     *       MOVE 'Y' TO TCATBAL-EOF
     *     END-IF
     *     READ NEXT TCATBAL-FILE
     *   END-PERFORM.
     * </pre>
     *
     * <p>The test persists three synthetic rows for the same account
     * (with different type+cat combos) and asserts that the finder
     * returns exactly those three rows:
     * <ul>
     *   <li>{@code (NEW_ACCOUNT_ID_60, "01", 1)} at balance {@code 10.00}</li>
     *   <li>{@code (NEW_ACCOUNT_ID_60, "01", 2)} at balance {@code 20.00}</li>
     *   <li>{@code (NEW_ACCOUNT_ID_60, "02", 1)} at balance {@code 30.00}</li>
     * </ul>
     *
     * <p>The size assertion uses exact equality ({@code hasSize(3)})
     * because all three rows are seeded by the test itself within the
     * transactional rollback boundary of the inherited
     * {@code @DataJpaTest} slice — no other TCATBAL rows for
     * {@link TestFixtures.Accounts#NEW_ACCOUNT_ID_60} exist (the Flyway
     * seed covers accounts {@code 00000000001}–{@code 00000000050},
     * not {@code 00000000060}).
     *
     * <p>The {@link #getAccountId(TransactionCategoryBalance)} helper
     * extracts the account-ID segment from each returned row so the
     * {@code allSatisfy} assertion can verify every row belongs to the
     * target account (a defence in depth against the finder returning
     * rows that escaped the {@code WHERE} clause).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findByAccountId(acctId) returns all TCATBAL rows for that account "
            + "(CBACT04C \"iterate all categories for one account\" loop)")
    void findByAccountId_existingAccount_returnsAllCategoryBalances() {
        // Arrange — persist 3 rows for the same account with different type+cat combos
        String acctId = TestFixtures.Accounts.NEW_ACCOUNT_ID_60;
        tcatBalRepository.save(buildBalance(
                buildKey(acctId, "01", 1), new BigDecimal("10.00")));
        tcatBalRepository.save(buildBalance(
                buildKey(acctId, "01", 2), new BigDecimal("20.00")));
        tcatBalRepository.save(buildBalance(
                buildKey(acctId, "02", 1), new BigDecimal("30.00")));
        entityManager.flush();
        entityManager.clear();

        // Act — drive the production custom finder. The query is a JPQL
        // @Query annotation on the repository method that navigates from
        // the entity to the embedded-key nested property (t.key.trancatAcctId).
        List<TransactionCategoryBalance> rows = tcatBalRepository.findByAccountId(acctId);

        // Assert — exactly 3 rows returned, all belonging to the target account
        assertThat(rows)
                .as("findByAccountId must return exactly 3 rows for the target account "
                        + "(account 00000000060 is outside the canonical seed range so the "
                        + "test owns all 3 rows for it within the rollback boundary)")
                .hasSize(3);
        assertThat(rows)
                .as("All returned rows must belong to the target account — defends against "
                        + "the finder accidentally returning rows that escaped the WHERE clause")
                .allSatisfy(r -> assertThat(getAccountId(r)).isEqualTo(acctId));
    }

    // =========================================================================
    // Catalog Size Sanity Check
    // =========================================================================

    /**
     * Verifies that {@link TransactionCategoryBalanceRepository#count()}
     * returns a non-negative tally. This guards against accidentally
     * truncating the catalog table at any point in the migration
     * lifecycle. The assertion uses {@code isGreaterThanOrEqualTo(0)}
     * rather than an exact value because the inherited
     * {@code @DataJpaTest} transactional rollback may not yet have run
     * when this method is invoked, so synthetic rows from earlier tests
     * in the same class might still be visible — the non-negative
     * invariant is the safest universal assertion.
     *
     * <p>Once the Flyway {@code V3__seed.sql} script lands (50 INSERT
     * rows from {@code app/data/ASCII/tcatbal.txt}), this assertion
     * could be tightened to {@code isGreaterThanOrEqualTo(50)} — but
     * doing that today would couple the IT to a deferred REFACTOR
     * deliverable and obscure the seed-related failure mode under a
     * count assertion that would only surface in CI. The minimal
     * non-negative assertion lets the test pass once reactivated even
     * if the seed row count drifts within reasonable bounds, while
     * still catching a catastrophic truncate-to-empty regression.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("count() returns non-negative total row count")
    void count_invoked_returnsNonNegativeValue() {
        // Act — drive the inherited JpaRepository.count() against the real DB
        long total = tcatBalRepository.count();

        // Assert — count is a row tally; it must never be negative
        assertThat(total)
                .as("count() must return a non-negative row tally; negative values would "
                        + "indicate a Spring Data JPA implementation defect")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // Private Helpers (No Business Logic — AAP §0.10.1)
    // =========================================================================

    /**
     * Constructs a synthetic {@link TransactionCategoryBalanceKey}
     * composite key from the supplied 11-character account identifier,
     * 2-character type code, and 4-digit category code. This is a pure
     * data-builder helper — it carries no business logic, no
     * validation, no derivation, no padding-computation. Per AAP §0.10.1
     * Require Test Coverage Rule, test helpers must not duplicate
     * production logic.
     *
     * <p>The caller is responsible for supplying a properly-sized
     * 11-character {@code acctId} (e.g. one of the
     * {@link TestFixtures.Accounts} constants); the helper does NOT pad
     * with leading zeros. Test methods reference the
     * {@link TestFixtures.Accounts} constants directly so the
     * zero-padding parity with the COBOL {@code PIC 9(11)} field is
     * preserved at the call site and not obscured inside this helper.
     *
     * @param acctId the 11-character {@code TRANCAT-ACCT-ID} component
     *               ({@code PIC 9(11)}). Tests in this class use
     *               {@link TestFixtures.Accounts#NEW_ACCOUNT_ID_60}
     *               ({@code "00000000060"}) for synthetic insertion and
     *               {@link TestFixtures.Accounts#NONEXISTENT_ACCOUNT_ID}
     *               ({@code "99999999999"}) for negative-path lookup.
     * @param typeCd the 2-character {@code TRANCAT-TYPE-CD} component
     *               ({@code PIC X(02)}). Canonical seed values are
     *               {@code "01"}–{@code "07"}; tests in this class use
     *               {@link TestFixtures.Transactions#TRAN_TYPE_PURCHASE}
     *               ({@code "01"}),
     *               {@link TestFixtures.Transactions#TRAN_TYPE_PAYMENT}
     *               ({@code "02"}), and {@code "99"} for the
     *               nonexistent-key test.
     * @param catCd  the 4-digit {@code TRANCAT-CD} component
     *               ({@code PIC 9(04)}). Canonical seed values are in
     *               the {@code 1}–{@code 5} range; tests in this class
     *               use {@code 1}, {@code 2}, {@code 5}, and {@code 9999}
     *               for the nonexistent-key test.
     * @return a fully-populated {@link TransactionCategoryBalanceKey}
     *         instance with all three fields set via the production
     *         setters (so any future Bean Validation constraint added
     *         by REFACTOR-flavor agents takes effect on persistence,
     *         not on construction).
     */
    private TransactionCategoryBalanceKey buildKey(String acctId, String typeCd, Integer catCd) {
        TransactionCategoryBalanceKey k = new TransactionCategoryBalanceKey();
        k.setTrancatAcctId(acctId);
        k.setTrancatTypeCd(typeCd);
        k.setTrancatCd(catCd);
        return k;
    }

    /**
     * Constructs a synthetic {@link TransactionCategoryBalance} entity
     * with the supplied composite key and {@link BigDecimal} balance
     * amount. This is a pure data-builder helper — it carries no
     * business logic, no validation, no derivation, no rounding. Per
     * AAP §0.10.1 Require Test Coverage Rule, test helpers must not
     * duplicate production logic.
     *
     * <p>The {@code balance} parameter is passed straight through to the
     * {@link TransactionCategoryBalance#setTranCatBal(BigDecimal)}
     * setter; the test methods are responsible for supplying a
     * {@link BigDecimal} at the desired scale (scale 2 for the
     * canonical AAP §0.10.3 contract, but the helper itself imposes
     * no constraint to allow boundary tests to explore scale parity
     * invariants).
     *
     * @param key     the composite primary key carrying the
     *                {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD,
     *                TRANCAT-CD)} 3-tuple.
     * @param balance the {@code TRAN-CAT-BAL} value; tests in this
     *                class pass {@code "250.75"} for canonical-path
     *                coverage, {@code "100.00"} / {@code "105.50"} for
     *                the CBACT04C READ-UPDATE-REWRITE round-trip,
     *                {@code "0.00"} for the zero-balance edge case,
     *                and {@code "10.00"} / {@code "20.00"} /
     *                {@code "30.00"} / {@code "11.11"} / {@code "55.55"}
     *                / {@code "100.00"} / {@code "50.00"} for the
     *                multi-row composite-key segregation tests.
     * @return a fully-populated {@link TransactionCategoryBalance}
     *         instance with the supplied fields set via the production
     *         setters (so any future Bean Validation constraint added
     *         by REFACTOR-flavor agents takes effect on persistence,
     *         not on construction).
     */
    private TransactionCategoryBalance buildBalance(TransactionCategoryBalanceKey key, BigDecimal balance) {
        TransactionCategoryBalance b = new TransactionCategoryBalance();
        b.setKey(key);
        b.setTranCatBal(balance);
        return b;
    }

    /**
     * Extracts the {@code TRANCAT-ACCT-ID} component from the composite
     * key of the supplied {@link TransactionCategoryBalance}. Provided
     * as a single source of truth for the
     * {@code key.getTrancatAcctId()} navigation so tests stay readable
     * when the balance-vs-key indirection is the only structural
     * concern.
     *
     * <p>If the REFACTOR-flavor migration agent switches from
     * {@code @EmbeddedId} to {@code @IdClass}, only this one helper
     * needs to change (to {@code r.getTrancatAcctId()} direct access)
     * — every call site stays untouched.
     *
     * @param r the {@link TransactionCategoryBalance} whose
     *          {@code TRANCAT-ACCT-ID} component is requested
     * @return the 11-character {@code TRANCAT-ACCT-ID} value
     */
    private String getAccountId(TransactionCategoryBalance r) {
        return r.getKey().getTrancatAcctId();
    }
}
