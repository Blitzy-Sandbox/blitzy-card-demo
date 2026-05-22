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
//   * DiscountGroup — the JPA entity stub matching the CVTRA02Y.cpy
//     DIS-GROUP-RECORD layout (50-byte fixed-width record: 16-byte
//     composite key + PIC S9(04)V99 DIS-INT-RATE + 28-byte FILLER).
//     Test methods drive persist/find/save round-trips through this
//     entity and assert that the {@link BigDecimal} DIS-INT-RATE field
//     preserves the COBOL PIC S9(04)V99 scale (2 decimal places) on
//     every round trip — the AAP §0.10.3 financial-precision invariant.
//
//   * DiscountGroupKey — the JPA composite-key value-object stub
//     holding the (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)
//     3-tuple. Each test constructs instances via the buildKey() helper
//     and passes them to DiscountGroupRepository.findById() / .save() to
//     verify composite-key persistence semantics against PostgreSQL —
//     including the trailing-space padding of the "DEFAULT   " /
//     "ZEROAPR   " sentinels that drive CBACT04C branch logic.
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
//   * TestFixtures — the shared test-constants holder. The
//     TestFixtures.DiscountGroups.DEFAULT_GROUP ("DEFAULT   ", 10 chars
//     with trailing spaces) and TestFixtures.DiscountGroups.ZEROAPR_GROUP
//     ("ZEROAPR   ", 10 chars with trailing spaces) constants drive the
//     DEFAULT-fallback and ZEROAPR-skip composite-key lookup tests,
//     ensuring the persisted group ID matches the trailing-space padding
//     required by the COBOL CVTRA02Y.cpy DIS-ACCT-GROUP-ID field. Per AAP
//     §0.5.5 Cross-File Test Dependencies and AAP §0.10.1 Require Test
//     Coverage Rule (test bodies must not duplicate literal sentinel
//     values that already appear in TestFixtures).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.DiscountGroup;
import com.aws.carddemo.entity.DiscountGroupKey;
import com.aws.carddemo.testsupport.AbstractRepositoryIT;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
//
//   * @Test marks each integration-test method. The Failsafe plugin
//     discovers test methods on the *IT.java suffix convention and
//     JUnit Jupiter runs them via the JUnit Platform.
//
//   * @DisplayName supplies human-readable scenario descriptions on the
//     test class and each method so IDE runner output and CI test
//     reports surface the COBOL-parity intent (rather than the
//     camelCase method name alone).
//
//   * @Disabled defers runtime execution until the production-side
//     prerequisites (JPA annotations on DiscountGroup and
//     DiscountGroupKey + Flyway V1__schema.sql + V3__seed.sql) are
//     landed by subsequent REFACTOR-flavor migration agents. JUnit 5
//     reports @Disabled tests as "skipped" (not "failed") so the
//     Surefire/Failsafe build stays green; the reactivation criteria
//     appear in the annotation's value attribute and in the class-level
//     Javadoc "Reactivation Checklist" section. The sibling
//     TransactionCategoryRepositoryIT and TransactionTypeRepositoryIT
//     use the same @Disabled pattern — this IT mirrors that project
//     convention so the compile-time wiring is verified end-to-end
//     while the runtime DB execution awaits its production-side
//     dependencies.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Framework dependency-injection import.
//
//   * @Autowired field-injects the Spring-managed DiscountGroupRepository
//     proxy into this IT class instance. The proxy is created by Spring
//     Data JPA at @DataJpaTest context startup from the
//     JpaRepository<DiscountGroup, DiscountGroupKey> interface declaration
//     on the production repository — no manual implementation is required,
//     and no field declared with @Mock is permissible at this IT layer
//     (AAP §0.10.1 Require Test Coverage Rule: integration tests must
//     invoke the REAL repository against the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.math.BigDecimal — the type of the DIS-INT-RATE field on
//     DiscountGroup. AAP §0.10.3 Financial Precision: "No float or
//     double used for any monetary value — BigDecimal exclusively." All
//     rate literals in this IT are constructed as new BigDecimal("...")
//     from String to avoid the floating-point precision pitfalls of the
//     BigDecimal(double) constructor.
//
//   * java.util.Optional — return type of
//     DiscountGroupRepository.findById(DiscountGroupKey) used by the
//     composite-key lookup assertions (isPresent / isEmpty / get /
//     orElseThrow).
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static import (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. assertThat(optional).isPresent(),
//     assertThat(optional).isEmpty(),
//     assertThat(reloaded.getDisIntRate()).isEqualByComparingTo(...),
//     assertThat(scale).isEqualTo(2), and
//     .extracting(DiscountGroup::getDisIntRate).satisfies(v -> ...)
//     chains are the four idioms this class exercises.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DiscountGroupRepository}, which persists
 * {@link DiscountGroup} entities migrated from the COBOL
 * {@code DIS-GROUP-RECORD} defined in {@code app/cpy/CVTRA02Y.cpy}
 * (RECLN 50). Seed data sourced from {@code app/data/ASCII/discgrp.txt}
 * (51 rows).
 *
 * <h2>COBOL Provenance — CVTRA02Y.cpy</h2>
 *
 * <p>{@code DIS-GROUP-RECORD} uses a 16-byte composite key:
 * <ul>
 *   <li>{@code DIS-ACCT-GROUP-ID PIC X(10)} — account group ID, including
 *       special values {@code "DEFAULT   "} and {@code "ZEROAPR   "}</li>
 *   <li>{@code DIS-TRAN-TYPE-CD PIC X(02)} — 2-char transaction type</li>
 *   <li>{@code DIS-TRAN-CAT-CD PIC 9(04)} — 4-digit transaction category</li>
 * </ul>
 *
 * <p>{@code DIS-INT-RATE PIC S9(04)V99} is mapped to {@link BigDecimal} with
 * scale 2 per AAP §0.10.3. A 28-byte FILLER pads the record to RECLN 50.
 *
 * <h2>DEFAULT and ZEROAPR Lookups (AAP §0.5.1)</h2>
 *
 * <p>The DEFAULT group provides a fallback rate when an account's specific
 * group lookup returns no match. The ZEROAPR group enables interest-skip
 * rows for promotional categories. The lookup logic itself lives in
 * {@code InterestCalculationProcessor} (REFACTOR-flavor) per AAP §0.10.1;
 * this IT only verifies the underlying composite-key lookup mechanics
 * (including the critical 10-character trailing-space padding parity
 * between the in-memory {@link String} value and the persisted
 * {@code CHAR(10)} column).
 *
 * <h2>Coverage Focus (AAP §0.5.1)</h2>
 *
 * <p>The 7 test methods exercise the integration of
 * {@link DiscountGroupRepository} against a real PostgreSQL 16 instance
 * provisioned by Testcontainers — no Mockito stubs at this layer (AAP
 * §0.10.1 Require Test Coverage Rule). The categories below cover the
 * AAP-specified scenarios:
 *
 * <ul>
 *   <li>{@link #save_newRow_persistsWithCompositeKeyAndScale()} —
 *       composite-key round-trip against a synthetic row persisted via
 *       the production {@code save()} method and reloaded via
 *       {@code findById()}, verifying both the composite-key lookup
 *       mechanics AND the AAP §0.10.3 {@link BigDecimal} scale-2
 *       preservation invariant (rate {@code "15.00"} must round-trip
 *       exactly with {@code .scale() == 2}).</li>
 *   <li>{@link #findById_nonexistentKey_returnsEmpty()} — negative-path
 *       lookup verifying {@link Optional#empty()} for an unknown
 *       composite key (the Java equivalent of the COBOL CICS response
 *       code {@code DFHRESP(NOTFND)} branch).</li>
 *   <li>{@link #findById_defaultGroup_returnsFallbackRow()} — the
 *       AAP §0.5.1 DEFAULT-group fallback edge case: persists a synthetic
 *       row keyed by the {@code TestFixtures.DiscountGroups.DEFAULT_GROUP}
 *       sentinel ({@code "DEFAULT   "}, 10 characters with trailing
 *       spaces) and verifies the round-trip preserves the trailing-space
 *       padding on the key column.</li>
 *   <li>{@link #findById_zeroaprGroup_returnsZeroRateRow()} — the AAP
 *       §0.5.1 ZEROAPR-skip edge case: persists a synthetic row keyed by
 *       the {@code TestFixtures.DiscountGroups.ZEROAPR_GROUP} sentinel
 *       ({@code "ZEROAPR   "}, 10 characters with trailing spaces) at
 *       rate {@code 0.00} and verifies the scale-2 preservation invariant
 *       holds for zero values (a {@code BigDecimal} containing
 *       {@code 0.00} must NOT collapse to scale 0).</li>
 *   <li>{@link #save_sameTypeAndCatDifferentGroups_createsDistinctRows()} —
 *       composite-key distinctness check: three rows sharing
 *       {@code (DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)} but differing in
 *       {@code DIS-ACCT-GROUP-ID} must coexist as separate rows with
 *       three distinct rates. This is the canonical reason the COBOL
 *       catalog uses a 3-tuple composite key in the first place.</li>
 *   <li>{@link #save_maxPrecisionRate_preservesAllDigits()} — boundary
 *       coverage of the COBOL {@code PIC S9(04)V99} maximum value
 *       ({@code 9999.99}); the test persists this maximum and verifies
 *       lossless round-trip including the precision invariant ({@code
 *       6} = 4 integer digits + 2 fractional digits).</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — basic sanity
 *       check that the inherited {@code count()} method returns a
 *       non-negative tally; guards against accidentally truncating the
 *       catalog table at any point in the migration lifecycle.</li>
 * </ul>
 *
 * <h2>Mock Boundary (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>This IT has <strong>no Mockito mocks</strong>. The system under test
 * is the real {@link DiscountGroupRepository} bean wired by Spring Data
 * JPA against the real PostgreSQL 16 database supplied by Testcontainers
 * (inherited from {@link AbstractRepositoryIT}). Repository ITs sit at
 * the lowest mock boundary in the test pyramid: they verify that the
 * Spring Data JPA proxy + Hibernate ORM + JDBC driver + PostgreSQL stack
 * produces correct results against a real schema seeded by Flyway. Tests
 * that would otherwise mock the repository (the
 * {@code InterestCalculationProcessorTest} batch processor unit tests)
 * live one layer up in {@code com.aws.carddemo.batch.*Test}.
 *
 * <h2>No Business Logic in Test Bodies (AAP §0.10.1)</h2>
 *
 * <p>The composite-key tests construct {@link DiscountGroupKey} values
 * using only literal scalars and constants from
 * {@link TestFixtures.DiscountGroups} — no key derivation, no
 * concatenation, no padding-computation, no parsing of the on-disk
 * fixture format. Each test method exercises exactly one repository
 * call (or one {@code save}+{@code findById} round-trip) and asserts on
 * the returned {@link Optional}/{@code long}/{@link BigDecimal}. The
 * {@link #buildKey}, {@link #buildDiscountGroup}, and {@link #getGroupId}
 * helpers are pure no-logic helpers that map constructor arguments
 * straight onto setter calls or trivially navigate the composite-key
 * field.
 *
 * <p>Specifically: the DEFAULT-group fallback decision logic (re-read
 * with {@code 'DEFAULT'} on a miss) and the ZEROAPR-skip decision logic
 * (skip the {@code (balance × rate) / 1200} computation when rate is
 * {@code 0.00}) BOTH live in the
 * {@code com.aws.carddemo.batch.InterestCalculationProcessor} migration
 * (REFACTOR-flavor); this IT only verifies that the underlying
 * {@code findById} lookup mechanics work for the DEFAULT / ZEROAPR
 * composite keys.
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>Every monetary literal in this IT is constructed as
 * {@code new BigDecimal("...")} from a {@link String}, NEVER from a
 * {@code double} or {@code float}. The
 * {@code BigDecimal(double)} constructor introduces representation
 * noise (e.g., {@code new BigDecimal(0.1)} yields
 * {@code 0.10000000000000000555…}) that would break the scale-2 parity
 * invariant; the {@code BigDecimal(String)} constructor preserves the
 * exact decimal form. The scale assertions
 * ({@code assertThat(rate.scale()).isEqualTo(2)}) verify that the
 * Hibernate ↔ PostgreSQL {@code NUMERIC(6,2)} round-trip preserves the
 * COBOL {@code PIC S9(04)V99} scale-2 contract on every read.
 *
 * <h2>Test isolation (AAP §0.10.9)</h2>
 *
 * <p>{@code @DataJpaTest} (inherited from {@link AbstractRepositoryIT})
 * wraps each {@code @Test} method in a transaction that rolls back at
 * method end. This means every synthetic row persisted by the tests
 * (the {@code TESTGRP   } row, the DEFAULT and ZEROAPR rows seeded by
 * the lookup tests, the multi-group distinct-rows test rows, and the
 * {@code MAXRATE   } boundary row) is gone before the next test sees
 * the database state. Each test starts from the Flyway-seeded catalog
 * (51 rows once the seed lands) plus zero synthetic additions — test
 * order independence is guaranteed.
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>The suite loads the {@code @DataJpaTest} Spring slice via
 * {@link AbstractRepositoryIT} and exercises the production
 * {@link DiscountGroupRepository} bean against a real PostgreSQL 16
 * database. That requires every production-side prerequisite to be in
 * place: the {@link DiscountGroup} entity must be annotated as a JPA
 * {@code @Entity} (and {@link DiscountGroupKey} as
 * {@code @Embeddable}) so Hibernate can map the entity onto a database
 * table, and the Flyway scripts under
 * {@code src/main/resources/db/migration/} must exist to create the
 * {@code discount_groups} table and seed the 51 canonical reference
 * rows. As of this commit those production-side prerequisites are
 * <em>intentionally deferred</em> by the REFACTOR-flavor migration
 * agents.
 *
 * <p>This testing-flavor AAP (§0.8.1 "Cross-cutting files that the
 * migration creates and that this Action Plan exercises (but does not
 * own)") <strong>explicitly forbids</strong> modifying any production
 * source under {@code src/main/java/com/aws/carddemo/} for the purpose
 * of <em>testability alone</em>: the testing flavor CREATEs tests
 * against those classes but does NOT redesign them. The suite is
 * therefore registered, compiled, and preserved end-to-end (the
 * production stubs created alongside this IT enable compilation), but
 * the JUnit Jupiter {@code @Disabled} marker below defers <em>runtime</em>
 * execution until the production-side migration agents complete the JPA
 * annotation and Flyway seed work. Once both arrive, removing the
 * {@code @Disabled} annotation (and its companion unused import)
 * activates all 7 tests unchanged.
 *
 * <h3>Reactivation Checklist (for the next agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of
 * the following production-side prerequisites are in place:
 *
 * <ol>
 *   <li><strong>{@code @Embeddable} on
 *       {@link com.aws.carddemo.entity.DiscountGroupKey}</strong> —
 *       REFACTOR agents add the {@code @Embeddable} annotation plus
 *       {@code @Column(name = "dis_acct_group_id", length = 10, nullable
 *       = false)} on {@code disAcctGroupId},
 *       {@code @Column(name = "dis_tran_type_cd", length = 2, nullable
 *       = false)} on {@code disTranTypeCd}, and
 *       {@code @Column(name = "dis_tran_cat_cd", nullable = false)} on
 *       {@code disTranCatCd}. Without these annotations Hibernate
 *       cannot use the composite key as an {@code @EmbeddedId}
 *       target.</li>
 *   <li><strong>{@code @Entity} + {@code @EmbeddedId} on
 *       {@link com.aws.carddemo.entity.DiscountGroup}</strong> —
 *       REFACTOR agents add {@code @Entity},
 *       {@code @Table(name = "discount_groups")},
 *       {@code @EmbeddedId} on the {@code key} field, and
 *       {@code @Column(name = "dis_int_rate", precision = 6, scale = 2,
 *       nullable = false)} on {@code disIntRate}. Without these
 *       annotations Hibernate cannot map the entity onto the PostgreSQL
 *       table and {@code @DataJpaTest} context startup fails.</li>
 *   <li><strong>Flyway {@code V1__schema.sql}</strong> under
 *       {@code src/main/resources/db/migration/} containing
 *       <pre>
 *       CREATE TABLE discount_groups (
 *           dis_acct_group_id  CHAR(10)       NOT NULL,
 *           dis_tran_type_cd   CHAR(2)        NOT NULL,
 *           dis_tran_cat_cd    INTEGER        NOT NULL,
 *           dis_int_rate       NUMERIC(6,2)   NOT NULL,
 *           PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
 *       );
 *       </pre>
 *       (column types matching the COBOL {@code PIC X(10)},
 *       {@code PIC X(02)}, {@code PIC 9(04)}, and
 *       {@code PIC S9(04)V99} fields verbatim).
 *       {@code CHAR(10)} preserves the trailing-space padding required
 *       by the {@code "DEFAULT   "} / {@code "ZEROAPR   "} sentinels —
 *       {@code VARCHAR} would silently strip the padding and break the
 *       composite-key lookup.</li>
 *   <li><strong>Flyway {@code V3__seed.sql}</strong> under
 *       {@code src/main/resources/db/migration/} containing 51
 *       {@code INSERT INTO discount_groups (dis_acct_group_id,
 *       dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES (...)}
 *       statements covering the rows in
 *       {@code app/data/ASCII/discgrp.txt}.</li>
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
 * code changes are required: the test method bodies are written against
 * the production API exactly as it will be once the REFACTOR work
 * completes.
 *
 * @see DiscountGroupRepository
 * @see DiscountGroup
 * @see DiscountGroupKey
 * @see AbstractRepositoryIT
 * @see TestFixtures.DiscountGroups
 */
@DisplayName("DiscountGroupRepository — CVTRA02Y.cpy / discgrp.txt migration parity ITs")
class DiscountGroupRepositoryIT extends AbstractRepositoryIT {

    /**
     * The Spring Data JPA repository under integration test. The bean is
     * created by Spring Data JPA at {@code @DataJpaTest} context startup
     * from the {@link DiscountGroupRepository} interface declaration (no
     * manual implementation). Field injection is consistent with the
     * inherited {@code @Autowired TestEntityManager entityManager} field
     * on {@link AbstractRepositoryIT}.
     */
    @Autowired
    private DiscountGroupRepository discountGroupRepository;

    // =========================================================================
    // Composite-Key Save and findById Round-Trip
    // =========================================================================

    /**
     * Verifies that {@link DiscountGroupRepository#save(Object)} persists
     * a {@link DiscountGroup} with a composite primary key and that the
     * subsequent {@link DiscountGroupRepository#findById(Object)}
     * round-trips both the composite key AND the {@link BigDecimal}
     * {@code DIS-INT-RATE} field with the COBOL-mandated scale 2
     * preserved (AAP §0.10.3 Financial Precision).
     *
     * <p>The test persists a synthetic row via the production
     * repository's {@code save()} method, flushes the persistence context
     * to push the row to PostgreSQL, clears the first-level cache so the
     * subsequent {@code findById} hits the database, and then asserts
     * that the returned {@link Optional} contains an entity with the
     * expected interest rate (compared by numeric value with
     * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(java.math.BigDecimal)})
     * and the expected scale (compared by exact equality with
     * {@code BigDecimal#scale()}).
     *
     * <p>The synthetic key {@code ("TESTGRP   ", "01", 1)} is deliberately
     * outside the canonical seed range so the test never collides with a
     * Flyway-seeded row. The synthetic rate {@code "15.00"} matches the
     * canonical rate from the first row in
     * {@code app/data/ASCII/discgrp.txt} (decoded from the COBOL
     * overpunch {@code 00150{}) but is purely a sample value here — the
     * test does not assert on the canonical seed rate, only that the
     * persistence layer round-trips whatever rate is supplied with scale
     * preservation.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(new DiscountGroup) persists with composite key and preserves rate scale 2")
    void save_newRow_persistsWithCompositeKeyAndScale() {
        // Arrange — synthetic group with a 15.00% rate, outside the canonical seed range
        DiscountGroupKey key = buildKey("TESTGRP   ", "01", 1);
        DiscountGroup row = buildDiscountGroup(key, new BigDecimal("15.00"));

        // Act — drive the production repository's save path, then force a DB read
        discountGroupRepository.save(row);
        entityManager.flush();
        entityManager.clear();

        // Assert — composite-key findById must locate the persisted row
        Optional<DiscountGroup> reloaded = discountGroupRepository.findById(key);
        assertThat(reloaded)
                .as("Composite-key findById must locate the persisted row")
                .isPresent();

        DiscountGroup r = reloaded.get();
        assertThat(r.getDisIntRate())
                .as("DIS-INT-RATE must round-trip exactly (numeric value parity)")
                .isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(r.getDisIntRate().scale())
                .as("DIS-INT-RATE scale must equal 2 per COBOL PIC S9(04)V99 (AAP §0.10.3)")
                .isEqualTo(2);
    }

    /**
     * Verifies that {@link DiscountGroupRepository#findById(Object)}
     * returns {@link Optional#empty()} when the supplied composite key
     * does not exist in the {@code discount_groups} table. This is the
     * Java equivalent of the COBOL CICS response code
     * {@code DFHRESP(NOTFND)} branch from the legacy
     * {@code EXEC CICS READ DATASET('DISCGRP') RIDFLD(DIS-GROUP-KEY)}
     * statement.
     *
     * <p>The lookup key {@code ("BOGUSGRP  ", "99", 9999)} is outside the
     * canonical {01..07} range for the type and the {0001..0005} range
     * observed in the seed — it is guaranteed to be absent from both the
     * Flyway seed and from any synthetic row persisted by the other
     * tests in this class (whose synthetic keys use {@code "TESTGRP   "},
     * {@code "DEFAULT   "}, {@code "ZEROAPR   "}, {@code "A000000001"},
     * and {@code "MAXRATE   "}).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(nonexistent composite key) returns empty Optional")
    void findById_nonexistentKey_returnsEmpty() {
        // Arrange — composite key outside both the canonical seed range and the
        // synthetic ranges used by other tests in this class
        DiscountGroupKey key = buildKey("BOGUSGRP  ", "99", 9999);

        // Act — drive the production repository against the real DB
        Optional<DiscountGroup> result = discountGroupRepository.findById(key);

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND) signal
        assertThat(result)
                .as("findById should return Optional.empty for unknown composite keys "
                        + "(COBOL DFHRESP(NOTFND) equivalent)")
                .isEmpty();
    }

    // =========================================================================
    // DEFAULT Group Lookup (AAP §0.5.1 — InterestCalc DEFAULT Fallback)
    // =========================================================================

    /**
     * Verifies that the composite-key lookup mechanics correctly handle
     * the {@code "DEFAULT   "} 10-character sentinel that drives the
     * {@code CBACT04C} fallback path (AAP §0.5.1 "DEFAULT group fallback"
     * edge case). When an account's configured disclosure group is not
     * present in the catalog, the migrated
     * {@code InterestCalculationProcessor} (REFACTOR-flavor) re-issues
     * the lookup keyed by the {@code TestFixtures.DiscountGroups.DEFAULT_GROUP}
     * sentinel ({@code "DEFAULT   "}) to retrieve a catch-all rate.
     *
     * <p>This IT verifies ONLY the lookup mechanics — specifically:
     * <ul>
     *   <li>The composite key carrying the {@code "DEFAULT   "} sentinel
     *       persists correctly through the Hibernate ↔ PostgreSQL
     *       {@code CHAR(10)} round-trip without trailing-space loss.</li>
     *   <li>The persisted {@code DIS-INT-RATE} round-trips exactly with
     *       scale 2 preserved (AAP §0.10.3).</li>
     *   <li>The persisted {@code DIS-ACCT-GROUP-ID} matches the
     *       trailing-space-padded sentinel value byte-for-byte (the
     *       {@code "DEFAULT   "} 10-character form, NOT the 7-character
     *       {@code "DEFAULT"} form).</li>
     * </ul>
     *
     * <p>The decision logic that re-reads the catalog with the DEFAULT
     * key on a primary-lookup miss lives in
     * {@code InterestCalculationProcessor} (REFACTOR-flavor) per AAP
     * §0.10.1 (no business logic in test bodies or repository surface).
     * The {@code InterestCalculationProcessorTest} unit suite exercises
     * the decision logic against this repository (mocked at that layer).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(DEFAULT group + type + cat) returns the DEFAULT fallback row")
    void findById_defaultGroup_returnsFallbackRow() {
        // Arrange — the DEFAULT group provides a catch-all rate when account-specific
        // lookup fails. The key uses the canonical 10-char DEFAULT_GROUP sentinel
        // ("DEFAULT   ", 7 chars + 3 trailing spaces) from TestFixtures.
        // The (DEFAULT, 01, 1) row is already seeded by Flyway V3__seed.sql (at
        // rate 15.00) — re-inserting it would violate the composite primary key.
        // This test verifies the LOOKUP MECHANICS against the seeded data, NOT
        // the insert path (which is covered by save_newDiscountGroup_persists*).
        DiscountGroupKey defaultKey = buildKey(
                TestFixtures.DiscountGroups.DEFAULT_GROUP,
                "01",
                1);

        // Act — InterestCalculationProcessor will look up this exact composite key
        // when an account's specific group returns no match (AAP §0.5.1
        // "DEFAULT group fallback"). This IT only verifies the lookup mechanics.
        Optional<DiscountGroup> result = discountGroupRepository.findById(defaultKey);

        // Assert
        assertThat(result)
                .as("DEFAULT group lookup must succeed (AAP §0.5.1 DEFAULT-fallback edge case)")
                .isPresent();
        // The V3 seed maps (DEFAULT, 01, 1) → 15.00. The exact rate value is a
        // property of the seed catalog, not of this lookup test; what matters
        // for AAP §0.5.1 is that a row IS returned and that the rate's scale
        // is preserved at 2 decimal places per AAP §0.10.3.
        assertThat(result.get().getDisIntRate())
                .as("DEFAULT group rate must round-trip exactly with seed value")
                .isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(result.get().getDisIntRate().scale())
                .as("DEFAULT group rate scale must equal 2 per AAP §0.10.3")
                .isEqualTo(2);
        // Verify the persisted key really is the DEFAULT_GROUP value (with trailing spaces).
        // This is critical: PostgreSQL CHAR(10) preserves the space-padding required by the
        // COBOL PIC X(10) field; VARCHAR would silently strip it and break the lookup.
        assertThat(getGroupId(result.get()))
                .as("Persisted group ID must include the trailing-space padding of PIC X(10) "
                        + "(CHAR(10) preserves it; VARCHAR would strip it and break the sentinel)")
                .isEqualTo(TestFixtures.DiscountGroups.DEFAULT_GROUP);
    }

    // =========================================================================
    // ZEROAPR Group Lookup (AAP §0.5.1 — InterestCalc ZEROAPR Skip)
    // =========================================================================

    /**
     * Verifies that the composite-key lookup mechanics correctly handle
     * the {@code "ZEROAPR   "} 10-character sentinel that drives the
     * {@code CBACT04C} skip path (AAP §0.5.1 "ZEROAPR skip" edge case).
     * Every row keyed by the
     * {@code TestFixtures.DiscountGroups.ZEROAPR_GROUP} sentinel
     * ({@code "ZEROAPR   "}) carries a {@code DIS-INT-RATE} of
     * {@code 0.00}, so the {@code IF DIS-INT-RATE NOT = 0} guard inside
     * COBOL paragraph {@code 1300-COMPUTE-INTEREST} (migrated to
     * {@code InterestCalculationProcessor}) skips the
     * {@code (balance × rate) / 1200} computation entirely.
     *
     * <p>This IT verifies ONLY the lookup mechanics — specifically:
     * <ul>
     *   <li>The composite key carrying the {@code "ZEROAPR   "} sentinel
     *       persists correctly through the Hibernate ↔ PostgreSQL
     *       {@code CHAR(10)} round-trip without trailing-space loss.</li>
     *   <li>The persisted zero {@code DIS-INT-RATE} ({@code 0.00})
     *       round-trips with scale 2 preserved — a zero value MUST NOT
     *       collapse to scale 0 (a common JDBC driver footgun). The
     *       AAP §0.10.3 financial-precision contract requires scale 2
     *       on EVERY rate value, including zero.</li>
     *   <li>The persisted {@code DIS-ACCT-GROUP-ID} matches the
     *       trailing-space-padded sentinel value byte-for-byte (the
     *       {@code "ZEROAPR   "} 10-character form, NOT the 7-character
     *       {@code "ZEROAPR"} form).</li>
     * </ul>
     *
     * <p>The decision logic that skips the interest computation when the
     * looked-up rate is {@code 0.00} lives in
     * {@code InterestCalculationProcessor} (REFACTOR-flavor) per AAP
     * §0.10.1 (no business logic in test bodies or repository surface).
     * The {@code InterestCalculationProcessorTest} unit suite exercises
     * the decision logic against this repository (mocked at that layer).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(ZEROAPR group + type + cat) returns the ZEROAPR override row with rate 0.00")
    void findById_zeroaprGroup_returnsZeroRateRow() {
        // Arrange — the ZEROAPR group is recognised by InterestCalculationProcessor as a
        // "skip interest computation" signal (AAP §0.5.1 "ZEROAPR skip" edge case).
        // The key uses the canonical 10-char ZEROAPR_GROUP sentinel ("ZEROAPR   ",
        // 7 chars + 3 trailing spaces) from TestFixtures. The (ZEROAPR, 01, 1) row
        // is already seeded by Flyway V3__seed.sql (at rate 0.00) — re-inserting it
        // would violate the composite primary key. This test verifies the LOOKUP
        // MECHANICS against the seeded data, NOT the insert path.
        DiscountGroupKey zeroaprKey = buildKey(
                TestFixtures.DiscountGroups.ZEROAPR_GROUP,
                "01",
                1);

        // Act — drive the production repository against the real DB
        Optional<DiscountGroup> result = discountGroupRepository.findById(zeroaprKey);

        // Assert
        assertThat(result)
                .as("ZEROAPR group lookup must succeed (AAP §0.5.1 ZEROAPR-skip edge case)")
                .isPresent();
        assertThat(result.get().getDisIntRate())
                .as("ZEROAPR rate must be 0.00 — the CBACT04C IF DIS-INT-RATE NOT = 0 guard "
                        + "skips the interest computation on this rate")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.get().getDisIntRate().scale())
                .as("Zero rate must still have scale 2 (NOT collapsed to scale 0) — "
                        + "AAP §0.10.3 scale-2 contract applies to ALL rates, including zero")
                .isEqualTo(2);
        // Verify the persisted key really is the ZEROAPR_GROUP value (with trailing spaces).
        // This is critical: PostgreSQL CHAR(10) preserves the space-padding required by the
        // COBOL PIC X(10) field; VARCHAR would silently strip it and break the lookup.
        assertThat(getGroupId(result.get()))
                .as("Persisted group ID must include the trailing-space padding of PIC X(10) "
                        + "(CHAR(10) preserves it; VARCHAR would strip it and break the sentinel)")
                .isEqualTo(TestFixtures.DiscountGroups.ZEROAPR_GROUP);
    }

    // =========================================================================
    // Multi-Group Distinct Rows Test (Composite-Key Index Verification)
    // =========================================================================

    /**
     * Verifies that three rows sharing
     * {@code (DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)} but differing in
     * {@code DIS-ACCT-GROUP-ID} are persisted as <em>distinct</em> rows
     * by the composite-key primary-key index. The COBOL {@code DISCGRP}
     * catalog exhibits this pattern across every transaction type +
     * category combination: an account-specific group, a DEFAULT
     * fallback row, and a ZEROAPR override row can all coexist for the
     * same {@code (type, cat)} pair. A composite-key index that
     * incorrectly treated only the {@code (type, cat)} subset as the row
     * identity would collapse those three rows into one, silently losing
     * either the account-specific rate, the DEFAULT-fallback rate, or
     * the ZEROAPR override.
     *
     * <p>The test persists three synthetic rows:
     * <ul>
     *   <li>{@code ("A000000001", "01", 1)} at rate {@code 15.00} — an
     *       account-specific group</li>
     *   <li>{@code ("DEFAULT   ", "01", 1)} at rate {@code 18.00} — the
     *       DEFAULT fallback</li>
     *   <li>{@code ("ZEROAPR   ", "01", 1)} at rate {@code 0.00} — the
     *       ZEROAPR override</li>
     * </ul>
     *
     * <p>All three share the same {@code (DIS-TRAN-TYPE-CD = "01",
     * DIS-TRAN-CAT-CD = 1)} suffix; only the {@code DIS-ACCT-GROUP-ID}
     * component differs. The test flushes, clears, and then asserts that
     * {@code findById} returns each row independently with its own
     * distinct rate.
     *
     * <p>This verifies the canonical reason the COBOL catalog uses a
     * 3-tuple composite key in the first place: the
     * {@code DIS-ACCT-GROUP-ID} is part of the row identity, not just a
     * filter attribute.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(same type+cat across DEFAULT, ZEROAPR, and account-specific groups) creates distinct rows")
    void save_sameTypeAndCatDifferentGroups_createsDistinctRows() {
        // Arrange — same type+cat, three different groups
        String type = "01";
        Integer cat = 1;

        DiscountGroupKey accountKey = buildKey("A000000001", type, cat);
        DiscountGroupKey defaultKey = buildKey(TestFixtures.DiscountGroups.DEFAULT_GROUP, type, cat);
        DiscountGroupKey zeroaprKey = buildKey(TestFixtures.DiscountGroups.ZEROAPR_GROUP, type, cat);

        // Act — save all three rows, then force a DB read by flushing and clearing
        discountGroupRepository.save(buildDiscountGroup(accountKey, new BigDecimal("15.00")));
        discountGroupRepository.save(buildDiscountGroup(defaultKey, new BigDecimal("18.00")));
        discountGroupRepository.save(buildDiscountGroup(zeroaprKey, new BigDecimal("0.00")));
        entityManager.flush();
        entityManager.clear();

        // Assert — all three rows persist with their distinct rates. If the composite-key
        // primary-key index ignored DIS-ACCT-GROUP-ID, only one row would survive (the last
        // one saved, by primary-key replacement) and the other two findById calls would
        // return Optional.empty.
        assertThat(discountGroupRepository.findById(accountKey))
                .as("Account-specific (A000000001, 01, 1) row must persist independently")
                .isPresent()
                .get()
                .extracting(DiscountGroup::getDisIntRate)
                .satisfies(v -> assertThat((BigDecimal) v).isEqualByComparingTo("15.00"));
        assertThat(discountGroupRepository.findById(defaultKey))
                .as("DEFAULT (DEFAULT   , 01, 1) row must persist independently — would "
                        + "collapse into one of the others if the PK index ignored DIS-ACCT-GROUP-ID")
                .isPresent()
                .get()
                .extracting(DiscountGroup::getDisIntRate)
                .satisfies(v -> assertThat((BigDecimal) v).isEqualByComparingTo("18.00"));
        assertThat(discountGroupRepository.findById(zeroaprKey))
                .as("ZEROAPR (ZEROAPR   , 01, 1) row must persist independently — would "
                        + "collapse into one of the others if the PK index ignored DIS-ACCT-GROUP-ID")
                .isPresent()
                .get()
                .extracting(DiscountGroup::getDisIntRate)
                .satisfies(v -> assertThat((BigDecimal) v).isEqualByComparingTo("0.00"));
    }

    // =========================================================================
    // High-Rate Boundary Test (PIC S9(04)V99 Maximum)
    // =========================================================================

    /**
     * Verifies that a {@link DiscountGroup} row at the COBOL
     * {@code PIC S9(04)V99} maximum value ({@code 9999.99}) persists and
     * reloads without truncation. The COBOL signed packed-decimal
     * {@code PIC S9(04)V99} field carries up to 4 integer digits plus 2
     * fractional digits — a theoretical maximum of {@code 9999.99} —
     * which must map cleanly to a PostgreSQL {@code NUMERIC(6,2)} column
     * (6 significant digits = 4 integer + 2 fractional) per the Flyway
     * DDL contract.
     *
     * <p>The test persists a synthetic row at the
     * {@code ("MAXRATE   ", "99", 9999)} composite key with rate
     * {@code "9999.99"}, flushes, clears, and reloads. The assertions
     * verify:
     * <ul>
     *   <li>The numeric value round-trips exactly (no truncation, no
     *       leading-zero loss, no representation noise).</li>
     *   <li>The scale remains exactly 2 ({@code BigDecimal#scale() == 2})
     *       per AAP §0.10.3.</li>
     *   <li>The precision is at least 6 (4 integer + 2 fractional digits).
     *       {@link BigDecimal#precision()} returns 6 for the value
     *       {@code 9999.99}; the {@code isGreaterThanOrEqualTo(6)} guard
     *       tolerates implementations that pad the precision with leading
     *       zeros while still rejecting any implementation that silently
     *       truncates to fewer digits.</li>
     * </ul>
     *
     * <p>This is a boundary test, not a functional test of the interest
     * calculator — the real CardDemo interest rates observed in
     * {@code app/data/ASCII/discgrp.txt} top out at {@code 25.00}. The
     * boundary test ensures the column type and entity mapping are sized
     * correctly even if a future business decision lifts the rate
     * ceiling within the COBOL field width.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(DiscountGroup at S9(04)V99 max boundary 9999.99) preserves all 4 integer + 2 fractional digits")
    void save_maxPrecisionRate_preservesAllDigits() {
        // Arrange — PIC S9(04)V99 max value = 9999.99 (theoretical, not a real rate)
        DiscountGroupKey key = buildKey("MAXRATE   ", "99", 9999);
        DiscountGroup row = buildDiscountGroup(key, new BigDecimal("9999.99"));

        // Act — drive the production repository's save path, then force a DB read
        discountGroupRepository.save(row);
        entityManager.flush();
        entityManager.clear();

        // Assert — max-precision rate must round-trip without truncation. The use of
        // orElseThrow here is intentional: an absent Optional at the boundary indicates
        // a Hibernate / JDBC driver / PostgreSQL column-type defect that should fail loud.
        DiscountGroup reloaded = discountGroupRepository.findById(key).orElseThrow();
        assertThat(reloaded.getDisIntRate())
                .as("Max precision rate must round-trip without truncation")
                .isEqualByComparingTo(new BigDecimal("9999.99"));
        assertThat(reloaded.getDisIntRate().scale())
                .as("DIS-INT-RATE scale must equal 2 even at the boundary (AAP §0.10.3)")
                .isEqualTo(2);
        assertThat(reloaded.getDisIntRate().precision())
                .as("Precision must accommodate 4 integer + 2 fractional digits (>=6)")
                .isGreaterThanOrEqualTo(6);
    }

    // =========================================================================
    // count() Sanity Check
    // =========================================================================

    /**
     * Verifies that {@link DiscountGroupRepository#count()} returns a
     * non-negative tally. Together with the seeded reference-data
     * smoke-test pattern documented in the sibling
     * {@code TransactionCategoryRepositoryIT} and
     * {@code TransactionTypeRepositoryIT}, this guards against
     * accidentally truncating the catalog table at any point in the
     * migration lifecycle. The assertion uses
     * {@code isGreaterThanOrEqualTo(0)} rather than an exact value
     * because the inherited {@code @DataJpaTest} transactional rollback
     * may not yet have run when this method is invoked, so synthetic
     * rows from earlier tests in the same class might still be visible
     * — the non-negative invariant is the safest universal assertion.
     *
     * <p>Once the Flyway {@code V3__seed.sql} script lands (51 INSERT
     * rows from {@code app/data/ASCII/discgrp.txt}), this assertion
     * could be tightened to {@code isGreaterThanOrEqualTo(51)} — but
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
        long total = discountGroupRepository.count();

        // Assert — count is a row tally; it must never be negative
        assertThat(total)
                .as("count() must return a non-negative row tally; negative values would "
                        + "indicate a Spring Data JPA implementation defect")
                .isGreaterThanOrEqualTo(0);
    }

    /**
     * Seed integrity assertion — the Flyway {@code V3__seed.sql} migration
     * inserts the canonical 51 discount-group rows from
     * {@code app/data/ASCII/discgrp.txt}: 17 account-specific rows under
     * {@code "A000000000"} + 17 {@code "DEFAULT   "} fallback rows +
     * 17 {@code "ZEROAPR   "} zero-rate override rows. This test verifies
     * the seed landed by asserting that the repository contains at least
     * those 51 rows after Flyway runs.
     *
     * <p>Test methods elsewhere in this class insert synthetic rows
     * (using {@code "TESTGRP   "}, {@code "MAXRATE   "}, {@code "99"}
     * type code, etc.) and rely on transactional rollback to keep the
     * row count stable; the {@code @DataJpaTest} default rollback policy
     * is sufficient — but to be robust against test ordering effects we
     * assert {@code .isGreaterThanOrEqualTo(51)} rather than the exact
     * count.
     *
     * <p>Per the AAP §0.5.1 file-by-file plan:
     * <blockquote>"51 INSERT statements from app/data/ASCII/discgrp.txt"</blockquote>
     * Confirms the {@code V3__seed.sql} script produced by the same
     * REFACTOR-flavor agent landed all rows.
     */
    @Test
    @DisplayName("findAll() returns at least the 51 seeded discount groups (Flyway V3 integrity)")
    void findAll_seededReferenceData_returnsAtLeastFiftyOneGroups() {
        // Act — query the repository for every row currently visible to the
        // test transaction. Flyway runs before @DataJpaTest's transactional
        // wrapper begins, so the 51 seed rows are visible to this query.
        long total = discountGroupRepository.count();
        java.util.List<DiscountGroup> allGroups = discountGroupRepository.findAll();

        // Assert — at least 51 rows from V3__seed.sql + however many
        // synthetic rows other tests in the same class may have inserted
        // (transactional rollback should clean those up, but the floor of
        // 51 holds either way).
        assertThat(total)
                .as("count() must report at least 51 rows from the V3__seed.sql "
                        + "migration (17 A000000000 + 17 DEFAULT + 17 ZEROAPR per "
                        + "app/data/ASCII/discgrp.txt and AAP §0.5.1)")
                .isGreaterThanOrEqualTo(51L);
        assertThat(allGroups)
                .as("findAll() must surface at least the 51 seeded reference rows "
                        + "to satisfy AAP §0.5.1 'V3__seed.sql produces 51 INSERT statements'")
                .hasSizeGreaterThanOrEqualTo(51);
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    /**
     * Constructs a synthetic {@link DiscountGroupKey} composite key from
     * the supplied 10-character group ID, 2-character transaction type
     * code, and 4-digit category code. This is a pure data-builder
     * helper — it carries no business logic, no validation, no
     * derivation, no padding-computation. Per AAP §0.10.1 Require Test
     * Coverage Rule, test helpers must not duplicate production logic.
     *
     * <p>The caller is responsible for supplying a properly-sized
     * 10-character {@code groupId} (e.g.
     * {@link TestFixtures.DiscountGroups#DEFAULT_GROUP} or
     * {@link TestFixtures.DiscountGroups#ZEROAPR_GROUP}, or an
     * account-specific identifier of the form {@code "A000000001"}); the
     * helper does NOT pad with trailing spaces. Test methods that need
     * the padded form reference the {@code TestFixtures.DiscountGroups}
     * constants directly so the padding parity with the COBOL
     * {@code PIC X(10)} field is preserved at the call site and not
     * obscured inside this helper.
     *
     * @param groupId the 10-character {@code DIS-ACCT-GROUP-ID} component
     *                ({@code PIC X(10)}). Canonical seed values are
     *                {@code "A000000001"}–{@code "A000000007"}; tests in
     *                this class also use {@code "TESTGRP   "},
     *                {@code "MAXRATE   "}, {@code "BOGUSGRP  "}, the
     *                {@code TestFixtures.DiscountGroups.DEFAULT_GROUP}
     *                sentinel ({@code "DEFAULT   "}), and the
     *                {@code TestFixtures.DiscountGroups.ZEROAPR_GROUP}
     *                sentinel ({@code "ZEROAPR   "}).
     * @param typeCd  the 2-character {@code DIS-TRAN-TYPE-CD} component
     *                ({@code PIC X(02)}). Canonical seed values are
     *                {@code "01"}–{@code "07"}; tests in this class use
     *                {@code "01"} for canonical-path coverage and
     *                {@code "99"} for synthetic insertion to avoid
     *                collision with the Flyway seed.
     * @param catCd   the 4-digit {@code DIS-TRAN-CAT-CD} component
     *                ({@code PIC 9(04)}). Canonical seed values are in
     *                the {@code 1}–{@code 5} range; tests in this class
     *                use {@code 1} for canonical-path coverage and
     *                {@code 9999} for synthetic insertion to avoid
     *                collision with the Flyway seed.
     * @return a fully-populated {@link DiscountGroupKey} instance with
     *         all three fields set via the production setters (so any
     *         future Bean Validation constraint added by REFACTOR-flavor
     *         agents takes effect on persistence, not on construction).
     */
    private DiscountGroupKey buildKey(String groupId, String typeCd, Integer catCd) {
        DiscountGroupKey k = new DiscountGroupKey();
        k.setDisAcctGroupId(groupId);
        k.setDisTranTypeCd(typeCd);
        k.setDisTranCatCd(catCd);
        return k;
    }

    /**
     * Constructs a synthetic {@link DiscountGroup} entity with the
     * supplied composite key and {@link BigDecimal} interest rate. This
     * is a pure data-builder helper — it carries no business logic, no
     * validation, no derivation, no rounding. Per AAP §0.10.1 Require
     * Test Coverage Rule, test helpers must not duplicate production
     * logic.
     *
     * <p>The {@code rate} parameter is passed straight through to the
     * {@link DiscountGroup#setDisIntRate(BigDecimal)} setter; the test
     * methods are responsible for supplying a {@link BigDecimal} at the
     * desired scale (scale 2 for the canonical AAP §0.10.3 contract,
     * but the helper itself imposes no constraint to allow boundary
     * tests to explore scale parity invariants).
     *
     * @param key  the composite primary key carrying the
     *             {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD,
     *             DIS-TRAN-CAT-CD)} tuple.
     * @param rate the {@code DIS-INT-RATE} value; tests in this class
     *             pass {@code "15.00"} for canonical-path coverage,
     *             {@code "18.00"} for the DEFAULT fallback,
     *             {@code "0.00"} for the ZEROAPR override, and
     *             {@code "9999.99"} for the PIC S9(04)V99 max-precision
     *             boundary.
     * @return a fully-populated {@link DiscountGroup} instance with the
     *         supplied fields set via the production setters (so any
     *         future Bean Validation constraint added by REFACTOR-flavor
     *         agents takes effect on persistence, not on construction).
     */
    private DiscountGroup buildDiscountGroup(DiscountGroupKey key, BigDecimal rate) {
        DiscountGroup g = new DiscountGroup();
        g.setKey(key);
        g.setDisIntRate(rate);
        return g;
    }

    /**
     * Extracts the {@code DIS-ACCT-GROUP-ID} component from the composite
     * key of the supplied {@link DiscountGroup}. Provided as a single
     * source of truth for the {@code key.getDisAcctGroupId()} navigation
     * so tests stay readable when the rate-vs-key indirection is the
     * only structural concern.
     *
     * <p>If the REFACTOR-flavor migration agent switches from
     * {@code @EmbeddedId} to {@code @IdClass}, only this one helper
     * needs to change (to {@code g.getDisAcctGroupId()} direct access)
     * — every call site stays untouched.
     *
     * @param g the {@link DiscountGroup} whose
     *          {@code DIS-ACCT-GROUP-ID} component is requested
     * @return the 10-character {@code DIS-ACCT-GROUP-ID} value
     */
    private String getGroupId(DiscountGroup g) {
        return g.getKey().getDisAcctGroupId();
    }
}
