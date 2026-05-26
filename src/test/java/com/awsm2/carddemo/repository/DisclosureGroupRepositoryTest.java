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

import com.awsm2.carddemo.domain.DisclosureGroup;
import com.awsm2.carddemo.domain.DisclosureGroup.DisclosureGroupId;
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
 * {@code @DataJpaTest} slice test for {@link DisclosureGroupRepository}.
 *
 * <h2>Source mapping (AAP &sect;0.6.1, &sect;0.6.2)</h2>
 * <ul>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS} &mdash;
 *       {@code KEYS(16 0)}, {@code RECORDSIZE(50 50)},
 *       {@code SHAREOPTIONS(2 3)}, {@code INDEXED}, {@code REC-TOTAL=51}
 *       (per {@code app/jcl/DISCGRP.jcl}:L36&ndash;L49 and verified
 *       against {@code app/catlg/LISTCAT.txt}). The COBOL
 *       {@code SHAREOPTIONS(2 3)} attribute has no PostgreSQL equivalent
 *       (PostgreSQL handles concurrent reads/writes natively via MVCC);
 *       it is preserved as a comment only.</li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/CBACT04C.cbl} &mdash; end-of-day interest
 *             calculation batch (L416-L438 implements the DEFAULT
 *             fallback two-stage lookup pattern). Replaced in the Java
 *             target by {@code InterestCalculationService}.</li>
 *         <li>{@code app/cbl/CBSTM03A.CBL} / {@code CBSTM03B.CBL}
 *             &mdash; statement generation; joins disclosure_group to
 *             embed APR percentages on statements. Replaced by
 *             {@code StatementGenerationService}.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Copybook:</strong> {@code app/cpy/CVTRA02Y.cpy} &mdash;
 *       {@code DIS-GROUP-RECORD} layout (RECLN 50 bytes):
 *       <pre>
 *         05  DIS-GROUP-KEY.                          (composite 16-byte key)
 *            10 DIS-ACCT-GROUP-ID   PIC X(10).        -> @EmbeddedId field
 *            10 DIS-TRAN-TYPE-CD    PIC X(02).        -> @EmbeddedId field
 *            10 DIS-TRAN-CAT-CD     PIC 9(04).        -> @EmbeddedId field
 *         05  DIS-INT-RATE          PIC S9(04)V99.    -> disIntRate
 *         05  FILLER                PIC X(28).        -> OMITTED
 *       </pre>
 *   </li>
 *   <li><strong>JCL DD allocation:</strong> {@code app/jcl/DISCGRP.jcl}
 *       &mdash; IDCAMS DEFINE CLUSTER (STEP10) + IDCAMS REPRO load
 *       (STEP15) from {@code DISCGRP.PS}. Replaced operationally by
 *       Flyway migrations V007 (DDL) and V012 (seed).</li>
 *   <li><strong>Migrations:</strong>
 *       {@code src/main/resources/db/migration/V007__create_disclosure_group.sql}
 *       (composite PK: {@code (dis_acct_group_id VARCHAR(10),
 *       dis_tran_type_cd CHAR(2), dis_tran_cat_cd INTEGER)} +
 *       {@code dis_int_rate NUMERIC(6,2) NOT NULL DEFAULT 0}) and
 *       {@code src/main/resources/db/migration/V012__seed_disclosure_group.sql}
 *       (seeds 51 canonical rows in three blocks of 17 from
 *       {@code app/data/ASCII/discgrp.txt}: 'A000000000' real ops group,
 *       'DEFAULT' fallback group, 'ZEROAPR' promotional group).</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ul>
 *   <li><strong>V012 'DEFAULT' fallback group:</strong> a seeded
 *       'DEFAULT' row is fetchable by composite primary key
 *       (Test 1).</li>
 *   <li><strong>V012 'ZEROAPR' promotional zero-rate group:</strong> a
 *       seeded 'ZEROAPR' row exists with {@code dis_int_rate = 0.00}
 *       (Test 2).</li>
 *   <li><strong>V012 seed cardinality:</strong> at least 51 rows present
 *       after Flyway migrations complete &mdash; 17 'A000000000' + 17
 *       'DEFAULT' + 17 'ZEROAPR' (Test 3).</li>
 *   <li><strong>Round-trip persistence with composite key:</strong> a
 *       freshly built {@link DisclosureGroup} with a custom
 *       3-field composite key persists and reloads via the inherited
 *       {@code JpaRepository.save} / {@code findById} cycle (Test 4).</li>
 *   <li><strong>NUMERIC(6,2) precision boundary:</strong> the maximum
 *       value {@code 9999.99} of {@code PIC S9(04)V99} round-trips
 *       exactly with {@code scale = 2} preserved (Test 5).</li>
 *   <li><strong>VARCHAR(10) (not CHAR(10)) ergonomics:</strong> a
 *       trimmed 'DEFAULT' value (7 chars, not 10) is stored without
 *       space-padding, enabling clean string-equal comparison without
 *       trailing-space normalization in service code (Test 6).</li>
 *   <li><strong>{@code @EmbeddedId} composite-key contract:</strong>
 *       {@link DisclosureGroupId#equals(Object) equals} and
 *       {@link DisclosureGroupId#hashCode() hashCode} obey the value-
 *       based identity contract required by JPA &sect;2.4 (Test 7).</li>
 *   <li><strong>{@code findById} miss returns empty:</strong> an
 *       unknown composite key returns {@code Optional.empty()} rather
 *       than throwing (Test 8).</li>
 *   <li><strong>{@code deleteById} contract:</strong> a previously
 *       persisted custom row can be deleted and is no longer findable
 *       (Test 9).</li>
 * </ul>
 *
 * <h2>What this test deliberately does NOT validate</h2>
 * <ul>
 *   <li><strong>DEFAULT-fallback lookup pattern.</strong> The two-stage
 *       lookup pattern (try specific group, fall back to 'DEFAULT' on
 *       miss) implemented in COBOL paragraph
 *       {@code 1200-GET-INTEREST-RATE} of {@code app/cbl/CBACT04C.cbl}
 *       (L416-L438) is a SERVICE-LAYER concern that lives in
 *       {@code InterestCalculationService} per AAP &sect;0.6.1 and the
 *       Repository's Javadoc. This repository test exercises only the
 *       JPA contract; the fallback orchestration is unit-tested
 *       separately in {@code InterestCalculationServiceTest}.</li>
 *   <li><strong>Custom finder methods.</strong>
 *       {@link DisclosureGroupRepository} declares <em>no</em> custom
 *       queries &mdash; all access patterns required by consumer
 *       services are satisfied by inherited
 *       {@link org.springframework.data.jpa.repository.JpaRepository}
 *       methods ({@code findById}, {@code findAll}, {@code save},
 *       {@code deleteById}, {@code existsById}, {@code count}). Adding
 *       finders would violate the Minimal Change Clause (AAP
 *       &sect;0.7.3).</li>
 *   <li><strong>Optimistic locking.</strong> {@link DisclosureGroup} has
 *       no {@code @Version} column &mdash; this lookup is static
 *       reference data seeded once by V012 and not updated at runtime.
 *       There is no read-modify-write contention to guard against.</li>
 *   <li><strong>Interest-calculation arithmetic.</strong> The literal
 *       divisor 1200 in the monthly-interest formula
 *       {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200),
 *       2, RoundingMode.HALF_EVEN)} per AAP &sect;0.6.1 is a SERVICE-
 *       LAYER concern. This test validates only that the rate field's
 *       NUMERIC(6,2) precision survives the persistence round-trip
 *       intact &mdash; it does NOT exercise the formula itself.</li>
 *   <li><strong>FK constraints.</strong> Database-level FK constraints
 *       linking {@code disclosure_group.dis_tran_type_cd} to
 *       {@code tran_type.tran_type} and
 *       {@code disclosure_group.dis_tran_cat_cd} to
 *       {@code tran_category.tran_cat_cd} are intentionally NOT
 *       declared in V007. Application-layer validation in
 *       {@code InterestCalculationService} enforces existence. This
 *       test therefore does not exercise FK behavior.</li>
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
 * the fresh container before any test method runs, so V012's seeded 51
 * disclosure-group rows are queryable in Tests 1, 2, 3.</p>
 *
 * <h2>Transactional isolation</h2>
 * <p>{@code @DataJpaTest} wraps each test method in a {@code @Transactional}
 * boundary that rolls back at the end of the test. The V012 seed data
 * (applied by Flyway at context start, BEFORE the first test transaction
 * begins) is therefore COMMITTED and visible to every test method, while
 * any rows persisted by an individual test (e.g., {@code ("CUSTOM01",
 * "99", 9999)} in Tests 4, 5, 6, 9) are rolled back at the end of that
 * test and invisible to the others. This isolation guarantee preserves
 * the 51-row V012 seed inventory for Test 3's count assertion.</p>
 *
 * @see DisclosureGroupRepository
 * @see DisclosureGroup
 * @see DisclosureGroupId
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DisclosureGroupRepositoryTest {

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
    // ensuring NUMERIC(6,2) arbitrary-precision arithmetic, VARCHAR(10) vs
    // CHAR(10) padding semantics, INTEGER composite-key behavior, and
    // PostgreSQL-specific DDL semantics are validated against the engine
    // the application will actually run on in production.
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
     * {@code @EnableJpaRepositories}).
     */
    @Autowired
    private DisclosureGroupRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager} (transitively
     * enabled by {@code @DataJpaTest}). Used to force SQL execution via
     * {@link TestEntityManager#flush() flush()} and to detach the persistence
     * context via {@link TestEntityManager#clear() clear()} so that subsequent
     * {@code findById()} calls reload from the database rather than returning
     * a first-level-cache hit. This is critical for verifying that the V012
     * seeded {@code dis_int_rate} values round-trip from PostgreSQL
     * {@code NUMERIC(6,2)} and that custom composite-key inserts round-trip
     * without silent type coercion.
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test fixture builder
    // =========================================================================

    /**
     * Builds a {@link DisclosureGroup} entity populated with the supplied
     * 3-field composite primary key and interest-rate value, using the
     * 4-arg convenience constructor on {@link DisclosureGroup}.
     *
     * <p>The fixture is transient (non-persisted) until handed to
     * {@link DisclosureGroupRepository#save(Object)} by the calling test.
     * The {@link BigDecimal} rate is supplied in scale-2 form (e.g.,
     * {@code new BigDecimal("19.99")}) so it round-trips against the V007
     * {@code NUMERIC(6,2)} column without scale-truncation surprises per
     * AAP &sect;0.6.1.</p>
     *
     * @param groupId 10-char alphanumeric DIS-ACCT-GROUP-ID (e.g.,
     *                {@code "CUSTOM01"}); maps to VARCHAR(10) column
     *                {@code dis_acct_group_id} and is the first sub-field
     *                of the composite key (PIC X(10))
     * @param typeCd  2-char DIS-TRAN-TYPE-CD (e.g., {@code "99"}); maps to
     *                CHAR(2) column {@code dis_tran_type_cd} and is the
     *                second sub-field of the composite key (PIC X(02))
     * @param catCd   4-digit DIS-TRAN-CAT-CD (e.g., {@code 9999}); maps to
     *                INTEGER column {@code dis_tran_cat_cd} and is the
     *                third sub-field of the composite key (PIC 9(04))
     * @param rate    {@link BigDecimal} interest-rate percentage with
     *                {@code scale = 2}; maps to NUMERIC(6,2) column
     *                {@code dis_int_rate} (PIC S9(04)V99 per AAP
     *                &sect;0.6.1)
     * @return a fresh, transient (non-persisted) {@link DisclosureGroup}
     */
    private DisclosureGroup buildDisclosureGroup(String groupId,
                                                 String typeCd,
                                                 Integer catCd,
                                                 BigDecimal rate) {
        // DisclosureGroup exposes a 4-arg convenience constructor that
        // builds the @EmbeddedId composite key internally (per the entity
        // Javadoc at L382-L416 of DisclosureGroup.java). Equivalent to:
        //     DisclosureGroupId id = new DisclosureGroupId(groupId, typeCd, catCd);
        //     return new DisclosureGroup(id, rate);
        return new DisclosureGroup(groupId, typeCd, catCd, rate);
    }

    // =========================================================================
    // Test 1 — V012 'DEFAULT' fallback group exists
    // =========================================================================

    /**
     * Validates that the V012 Flyway seed migration successfully inserted
     * the 'DEFAULT' fallback rows and at least one such row is fetchable
     * by composite primary key with a non-null interest rate.
     *
     * <p>The 'DEFAULT' rows are the cornerstone of the COBOL
     * {@code CBACT04C} two-stage lookup pattern (paragraph
     * {@code 1200-GET-INTEREST-RATE} at
     * {@code app/cbl/CBACT04C.cbl}:L416-L438): when the primary lookup
     * for a specific account-group ID misses, the service code re-issues
     * the lookup with group ID set to the literal 'DEFAULT'. This test
     * is the canary that proves the 'DEFAULT' rows are present and
     * findable &mdash; if it fails, V012 did not execute or the
     * 'DEFAULT' block was wiped after migration.</p>
     *
     * <p>The composite key chosen, {@code ("DEFAULT", "01", 1)},
     * corresponds to the first row of Block B in V012
     * (line 121 of {@code V012__seed_disclosure_group.sql}).</p>
     *
     * <p>This test exercises only the JPA contract &mdash; the
     * fallback ORCHESTRATION (try specific group, fall back to 'DEFAULT'
     * on miss) is a SERVICE-LAYER concern unit-tested separately in
     * {@code InterestCalculationServiceTest} per AAP &sect;0.6.1 and
     * the repository's Javadoc.</p>
     */
    @Test
    void seededDefaultGroup_existsInDatabase() {
        // V012 seeded 'DEFAULT' fallback group per CBACT04C (L416-L438) two-stage lookup.
        // ("DEFAULT", "01", 1) is the first 'DEFAULT' row (Block B line 121 of V012).
        DisclosureGroupId id = new DisclosureGroupId("DEFAULT", "01", 1);

        Optional<DisclosureGroup> result = repository.findById(id);

        assertThat(result)
                .as("V012-seeded 'DEFAULT' fallback group row "
                        + "(DEFAULT, '01', 1) must be present in disclosure_group "
                        + "after Flyway migration; this row is the canary for the "
                        + "DEFAULT-fallback two-stage lookup pattern implemented "
                        + "in InterestCalculationService per "
                        + "app/cbl/CBACT04C.cbl:L416-L438")
                .isPresent();

        // disIntRate must be non-null for the row to be valid for the
        // two-stage lookup pattern; the V007 column is NOT NULL.
        assertThat(result.get().getDisIntRate())
                .as("V012-seeded 'DEFAULT' group's dis_int_rate must be "
                        + "non-null (V007 declares NUMERIC(6,2) NOT NULL DEFAULT 0); "
                        + "a null rate would break the COBOL CBACT04C arithmetic "
                        + "boundary per AAP §0.6.1")
                .isNotNull();
    }



    // =========================================================================
    // Test 2 — V012 'ZEROAPR' promotional group exists with zero rate
    // =========================================================================

    /**
     * Validates that the V012 Flyway seed migration successfully inserted
     * the 'ZEROAPR' promotional-zero-APR rows and at least one such row
     * is fetchable by composite primary key with an interest rate of
     * exactly {@code 0.00}.
     *
     * <p>The 'ZEROAPR' rows implement the promotional-zero-APR short
     * circuit in COBOL {@code CBACT04C}: when an account's
     * {@code ACCT-GROUP-ID} is the literal 'ZEROAPR', the lookup hits a
     * row with {@code DIS-INT-RATE = 0.00} on the first try (no
     * DEFAULT fallback is taken), and the service short-circuits to a
     * zero monthly interest charge regardless of balance. This test
     * confirms the 'ZEROAPR' rows are present and that all of them
     * carry a zero rate per the V012 seed.</p>
     *
     * <p>The composite key chosen, {@code ("ZEROAPR", "01", 1)},
     * corresponds to the first row of Block C in V012
     * (line 142 of {@code V012__seed_disclosure_group.sql}).</p>
     *
     * <p>The rate comparison uses
     * {@link BigDecimal#compareTo(BigDecimal)} returning zero, NOT
     * {@link Object#equals(Object)} &mdash; {@code BigDecimal.equals()}
     * is scale-sensitive and would report {@code 0} and {@code 0.00}
     * as unequal, which is a notorious source of false negatives in
     * financial tests per AAP &sect;0.6.1.</p>
     */
    @Test
    void seededZeroAprGroup_existsInDatabase_withZeroRate() {
        // V012 'ZEROAPR' promotional zero-interest group.
        // ("ZEROAPR", "01", 1) is the first 'ZEROAPR' row (Block C line 142 of V012).
        DisclosureGroupId id = new DisclosureGroupId("ZEROAPR", "01", 1);

        Optional<DisclosureGroup> result = repository.findById(id);

        assertThat(result)
                .as("V012-seeded 'ZEROAPR' promotional group row "
                        + "(ZEROAPR, '01', 1) must be present in disclosure_group "
                        + "after Flyway migration; this group enables the "
                        + "zero-APR short-circuit in InterestCalculationService "
                        + "per AAP §0.6.1")
                .isPresent();

        // compareTo() returns 0 for value-equal regardless of scale.
        // BigDecimal.equals() would compare both value AND scale and
        // could falsely report 0 != 0.00 if the JDBC driver returned a
        // different scale than expected. compareTo() is the only safe
        // BigDecimal equality check for financial values per AAP §0.6.1.
        assertThat(result.get().getDisIntRate().compareTo(BigDecimal.ZERO))
                .as("V012-seeded 'ZEROAPR' group's dis_int_rate must "
                        + "compareTo BigDecimal.ZERO as 0 (i.e., value-equal "
                        + "regardless of scale); compareTo (not equals) is "
                        + "mandated for BigDecimal equality per AAP §0.6.1 "
                        + "because BigDecimal.equals is scale-sensitive")
                .isZero();
    }

    // =========================================================================
    // Test 3 — V012 seed cardinality: at least 51 rows
    // =========================================================================

    /**
     * Validates that the V012 Flyway seed migration successfully inserted
     * <em>at least</em> 51 canonical disclosure-group rows into the
     * {@code disclosure_group} table.
     *
     * <p>The assertion uses {@code isGreaterThanOrEqualTo(51L)} rather
     * than a strict {@code isEqualTo(51L)} so that this test remains
     * robust if a future migration (or a test-scoped insert that escapes
     * its rollback scope) adds rows. The canonical contract is "at least
     * 51 V012 rows exist" &mdash; 17 'A000000000' + 17 'DEFAULT' + 17
     * 'ZEROAPR' = 51 per AAP &sect;0.4.1 and the
     * {@code app/data/ASCII/discgrp.txt} golden fixture
     * ({@code REC-TOTAL = 51} per {@code app/catlg/LISTCAT.txt}). The
     * individual-block assertions in Tests 1 and 2 prove the canonical
     * presence of the 'DEFAULT' and 'ZEROAPR' blocks; this test bounds
     * the total cardinality.</p>
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS STARTBR
     * DATASET('DISCGRP') ... READNEXT} counting iterations &mdash; the
     * relational equivalent is the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#count()
     * count()} method.</p>
     */
    @Test
    void seededRowCount_is51_perAap() {
        // V012 seeds exactly 51 disclosure-group rows per AAP §0.4.1:
        //   17 'A000000000' (Block A, lines 100-116 of V012)
        // + 17 'DEFAULT'    (Block B, lines 121-137 of V012)
        // + 17 'ZEROAPR'    (Block C, lines 142-158 of V012)
        // = 51 total
        long actualCount = repository.count();

        assertThat(actualCount)
                .as("V012 must seed at least 51 canonical disclosure-group "
                        + "rows (17 'A000000000' + 17 'DEFAULT' + 17 'ZEROAPR') "
                        + "per AAP §0.4.1; LISTCAT.txt confirms REC-TOTAL=51 "
                        + "for AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS; actual count: %d",
                        actualCount)
                .isGreaterThanOrEqualTo(51L);
    }



    // =========================================================================
    // Test 4 — round-trip persistence with custom composite key
    // =========================================================================

    /**
     * Validates that {@link DisclosureGroupRepository} can persist a
     * freshly built {@link DisclosureGroup} via the inherited
     * {@code JpaRepository.save(Object)} method and reload it by its
     * 3-field {@link DisclosureGroupId} composite primary key via
     * {@code JpaRepository.findById(Object)}.
     *
     * <p>The custom composite key {@code ("CUSTOM01", "99", 9999)} is
     * chosen to be OUTSIDE the V012 seed ranges so this test does not
     * collide with the seeded rows or interfere with subsequent tests'
     * assertions on the seeded inventory. {@code @DataJpaTest} rolls
     * back this insert at the end of the test method, so the row never
     * persists into the next test's transactional scope.</p>
     *
     * <p>The {@code entityManager.flush()} + {@code clear()} cycle
     * forces the pending INSERT to the DB and detaches the entity from
     * the persistence context, so the subsequent
     * {@code findById(new DisclosureGroupId("CUSTOM01", "99", 9999))}
     * returns a freshly hydrated entity rather than the same instance
     * from the first-level cache. This is what guarantees the test
     * exercises a real DB round-trip rather than an in-memory cache
     * lookup &mdash; critical for verifying that the @EmbeddedId
     * composite-key hydration code path actually executes.</p>
     *
     * <p>The rate assertion uses {@code compareTo()} rather than
     * {@code equals()} per AAP &sect;0.6.1 &mdash; BigDecimal's
     * scale-sensitive equals would surface false negatives between
     * stored {@code "19.99"} and reloaded {@code "19.99"} if Hibernate
     * produced a different scale.</p>
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS WRITE
     * DATASET('DISCGRP') FROM(DIS-GROUP-RECORD) RIDFLD(DIS-GROUP-KEY)}
     * followed by {@code EXEC CICS READ DATASET('DISCGRP')
     * INTO(DIS-GROUP-RECORD) RIDFLD(DIS-GROUP-KEY)} re-read.</p>
     */
    @Test
    void saveCustomGroup_persistsAndReloads() {
        // Custom composite key OUTSIDE V012 seed ranges to avoid collision.
        // ("CUSTOM01", "99", 9999) — 'CUSTOM01' is not 'A000000000', 'DEFAULT',
        // or 'ZEROAPR'; '99' is not '01'-'07'; 9999 is the COBOL PIC 9(04) max.
        DisclosureGroup custom =
                buildDisclosureGroup("CUSTOM01", "99", 9999, new BigDecimal("19.99"));

        repository.save(custom);
        // Force pending INSERT to DB + detach entity so findById reloads
        // from PostgreSQL rather than returning the same instance from
        // the first-level cache. This is what makes this a real round-trip
        // test of the @EmbeddedId hydration code path.
        entityManager.flush();
        entityManager.clear();

        Optional<DisclosureGroup> result =
                repository.findById(new DisclosureGroupId("CUSTOM01", "99", 9999));

        assertThat(result)
                .as("Custom disclosure-group row ('CUSTOM01', '99', 9999) "
                        + "must be findable by 3-field composite key after "
                        + "save + flush + clear cycle; failure indicates the "
                        + "@EmbeddedId hydration code path is broken")
                .isPresent();

        // compareTo (not equals) — BigDecimal.equals is scale-sensitive
        // per AAP §0.6.1.
        assertThat(result.get().getDisIntRate().compareTo(new BigDecimal("19.99")))
                .as("Saved dis_int_rate must round-trip exactly as 19.99 "
                        + "via NUMERIC(6,2); compareTo (not equals) per AAP §0.6.1")
                .isZero();
    }

    // =========================================================================
    // Test 5 — BigDecimal NUMERIC(6,2) precision boundary (PIC S9(04)V99)
    // =========================================================================

    /**
     * Validates that the V007 {@code dis_int_rate NUMERIC(6,2)} column
     * round-trips the maximum positive value of the COBOL
     * {@code PIC S9(04)V99} clause (= 9999.99) without precision loss
     * and with the scale = 2 invariant preserved.
     *
     * <p>The COBOL {@code PIC S9(04)V99} clause permits any value in
     * {@code [-9999.99, +9999.99]}; PostgreSQL {@code NUMERIC(6,2)}
     * faithfully preserves this range with precision = 4 integer digits
     * + 2 fraction digits = 6 total digits. This test exercises the
     * positive boundary {@code 9999.99}; the negative boundary
     * {@code -9999.99} is exercised analogously but is not seeded by
     * V012 (no negative rates exist in the canonical fixture
     * {@code app/data/ASCII/discgrp.txt}).</p>
     *
     * <p>The {@code scale() == 2} assertion is what catches a subtle
     * class of bugs where a future schema migration accidentally
     * promotes the column to {@code NUMERIC} (unbounded scale) or to
     * {@code DOUBLE PRECISION} (binary float). Either change would
     * silently break the COBOL fixed-point semantics required by
     * AAP &sect;0.6.1 and produce visible drift in the interest-
     * calculation parallel-run diff.</p>
     *
     * <p>The rate comparison uses {@code compareTo()} for value
     * equality, then a separate {@code scale()} assertion for the
     * scale invariant &mdash; the two-step pattern is the only safe way
     * to validate both BigDecimal value AND scale per AAP &sect;0.6.1.</p>
     */
    @Test
    void bigDecimalNumeric6Comma2_picS9_04_V99Parity() {
        // AAP §0.6.1 — NUMERIC(6,2) maps PIC S9(04)V99 (4 digits + V99).
        // 9999.99 = max positive value of PIC S9(04)V99 (4-digit integer + V99).
        // Custom composite key OUTSIDE V012 seed ranges.
        DisclosureGroup boundary =
                buildDisclosureGroup("CUSTOM02", "99", 9998, new BigDecimal("9999.99"));

        repository.save(boundary);
        entityManager.flush();
        entityManager.clear();

        Optional<DisclosureGroup> result =
                repository.findById(new DisclosureGroupId("CUSTOM02", "99", 9998));

        assertThat(result)
                .as("Maximum-precision custom row ('CUSTOM02', '99', 9998, "
                        + "9999.99) must be findable after save + flush + clear; "
                        + "failure indicates the NUMERIC(6,2) precision boundary "
                        + "is mis-mapped between Java BigDecimal and PostgreSQL "
                        + "NUMERIC per AAP §0.6.1")
                .isPresent();

        BigDecimal reloadedRate = result.get().getDisIntRate();

        // Value assertion — compareTo for scale-insensitive value equality.
        assertThat(reloadedRate.compareTo(new BigDecimal("9999.99")))
                .as("Reloaded dis_int_rate must compareTo 9999.99 as 0 "
                        + "(value-equal regardless of scale); 9999.99 is the "
                        + "max positive value of PIC S9(04)V99 per AAP §0.6.1")
                .isZero();

        // Scale assertion — separate from value to ensure NUMERIC(6,2)
        // returns scale=2 (not 1, 0, or unbounded). A scale change would
        // silently break COBOL fixed-point parity per AAP §0.6.1.
        assertThat(reloadedRate.scale())
                .as("Reloaded dis_int_rate must have scale = 2 (preserving "
                        + "the NUMERIC(6,2) decimal-place invariant per COBOL "
                        + "PIC S9(04)V99); a different scale indicates the "
                        + "column was promoted to NUMERIC (unbounded) or "
                        + "DOUBLE PRECISION, silently breaking AAP §0.6.1")
                .isEqualTo(2);
    }

    // =========================================================================
    // Test 6 — VARCHAR(10) supports trimmed group-ID values
    // =========================================================================

    /**
     * Validates that the V007 {@code dis_acct_group_id VARCHAR(10)}
     * column (NOT {@code CHAR(10)}) stores a trimmed 'DEFAULT' literal
     * (7 chars, not 10) without applying trailing-space padding.
     *
     * <p>The choice of {@code VARCHAR(10)} over {@code CHAR(10)} in V007
     * is INTENTIONAL: the COBOL fixture
     * {@code app/data/ASCII/discgrp.txt} pads 'DEFAULT' and 'ZEROAPR'
     * with 3 trailing spaces to fill the 10-char {@code PIC X(10)}
     * field, but V012 stores the TRIMMED 7-char values for query
     * ergonomics. Java services in
     * {@code InterestCalculationService} pass the trimmed literal
     * 'DEFAULT' directly to {@code findById()} without trailing-space
     * normalization; this only works if the column type is
     * {@code VARCHAR(10)} (which stores exactly what is supplied) and
     * NOT {@code CHAR(10)} (which would space-pad the trimmed value to
     * 10 chars on storage, causing a future equality mismatch).</p>
     *
     * <p>This test exercises the V006-style VARCHAR-not-CHAR contract by
     * persisting a custom row with a 7-char group ID
     * ({@code "DEFAULT"}) under a {@code "99"} type-code (NOT the
     * V012-seeded {@code "01"} type-code, to avoid colliding with the
     * V012 seed). It then reloads via composite key with the SAME
     * 7-char literal and asserts the value round-trips with
     * {@code .trim()} as a defensive check &mdash; if V007 was ever
     * changed to {@code CHAR(10)} on a misguided "fixed-width matches
     * COBOL better" assumption, the round-trip value would include
     * 3 trailing spaces and the {@code trim().equals("DEFAULT")} would
     * still pass while the unprotected
     * {@code getDisAcctGroupId().equals("DEFAULT")} would FAIL. The
     * {@code .trim()} therefore catches this regression class
     * deterministically.</p>
     *
     * <p>The custom composite key {@code ("DEFAULT", "99", 9997)}
     * intentionally REUSES the 'DEFAULT' group ID but with a non-V012
     * {@code (type_cd, cat_cd)} pair so the row is unique and is
     * rolled back after the test &mdash; not colliding with the 17
     * V012-seeded 'DEFAULT' rows or with subsequent tests.</p>
     */
    @Test
    void varCharGroupIdSupportsTrimmedValues() {
        // Entity blueprint specifies VARCHAR(10) so 'DEFAULT' (7 chars) is not space-padded.
        // Custom composite key reuses 'DEFAULT' group ID with a NON-V012
        // (type, cat) pair to avoid collision with the 17 seeded 'DEFAULT' rows.
        DisclosureGroup d =
                buildDisclosureGroup("DEFAULT", "99", 9997, new BigDecimal("15.00"));

        repository.save(d);
        entityManager.flush();
        entityManager.clear();

        Optional<DisclosureGroup> result =
                repository.findById(new DisclosureGroupId("DEFAULT", "99", 9997));

        assertThat(result)
                .as("Custom row with trimmed 'DEFAULT' (7 chars) group ID must "
                        + "be findable by composite key after save + flush + "
                        + "clear; VARCHAR(10) (not CHAR(10)) preserves the "
                        + "trimmed value verbatim per V007")
                .isPresent();

        // .trim() defends against a future regression to CHAR(10); if the
        // column is correctly VARCHAR(10), .trim() is a no-op and the test
        // still passes. If the column is regressed to CHAR(10), the stored
        // value would be 'DEFAULT   ' (padded) and .trim() reveals the bug
        // because the assertion compares the trimmed form to 'DEFAULT' (7
        // chars). The unprotected getDisAcctGroupId().equals("DEFAULT")
        // would FAIL under CHAR(10), masking the regression with a
        // confusing error message; .trim() makes the regression detectable
        // through this targeted assertion.
        assertThat(result.get().getId().getDisAcctGroupId().trim())
                .as("V007 dis_acct_group_id column is VARCHAR(10) (not "
                        + "CHAR(10)) so a 7-char 'DEFAULT' literal round-trips "
                        + "WITHOUT space-padding; .trim() defends against a "
                        + "future regression to CHAR(10) per AAP §0.6.2")
                .isEqualTo("DEFAULT");
    }



    // =========================================================================
    // Test 7 — @EmbeddedId composite-key equals/hashCode contract
    // =========================================================================

    /**
     * Validates that the {@link DisclosureGroupId} composite-key class
     * obeys the value-based identity contract required by JPA &sect;2.4
     * (Jakarta Persistence specification): two {@code DisclosureGroupId}
     * instances with identical field values must be
     * {@link Object#equals(Object) equal} AND have identical
     * {@link Object#hashCode() hashCode}; an instance with any differing
     * sub-field must be non-equal.
     *
     * <p>This contract is what allows the JPA persistence context to
     * use {@code DisclosureGroupId} as a {@link java.util.HashMap} key
     * in its identity-tracking machinery, and what allows
     * {@code @DataJpaTest} test code to construct fresh
     * {@code DisclosureGroupId} instances for each {@code findById}
     * call without worrying about reference equality. Without this
     * contract, JPA would create duplicate managed-entity references
     * for the same composite key &mdash; a class of subtle
     * inconsistency that breaks the "one entity per identity per
     * persistence context" invariant.</p>
     *
     * <p>The test builds three {@code DisclosureGroupId} instances:</p>
     * <ul>
     *   <li>{@code id1 = ("DEFAULT", "01", 1)}: the canonical reference;</li>
     *   <li>{@code id2 = ("DEFAULT", "01", 1)}: built independently from
     *       {@code id1} with identical field values &mdash; must be
     *       value-equal;</li>
     *   <li>{@code id3 = ("DEFAULT", "01", 2)}: identical to {@code id1}
     *       except the third sub-field (catCd) differs &mdash; must be
     *       NOT value-equal.</li>
     * </ul>
     *
     * <p>The {@code id2 != id1} reference check is implicit
     * (different constructor invocations produce different object
     * references); the test does NOT use {@code ==} for equality.</p>
     */
    @Test
    void compositeKeyId_equalsAndHashCode() {
        // Two independent instances with IDENTICAL field values; must be
        // value-equal and hash-equal per JPA §2.4 composite-key contract.
        DisclosureGroupId id1 = new DisclosureGroupId("DEFAULT", "01", 1);
        DisclosureGroupId id2 = new DisclosureGroupId("DEFAULT", "01", 1);

        // Third instance with the SAME group and type but DIFFERENT cat;
        // must NOT be value-equal. If composite-key equals() ignored the
        // catCd sub-field, the JPA persistence context would conflate
        // distinct (DEFAULT, '01', 1) and (DEFAULT, '01', 2) entries.
        DisclosureGroupId id3 = new DisclosureGroupId("DEFAULT", "01", 2);

        // Value equality — must hold for two independently-constructed
        // composite keys with identical field values per JPA §2.4.
        assertThat(id1)
                .as("Two DisclosureGroupId instances with identical "
                        + "(groupId, typeCd, catCd) field values must be "
                        + "value-equal per the JPA composite-key contract "
                        + "(JPA §2.4); failure indicates a missing or "
                        + "incorrect equals() implementation in "
                        + "DisclosureGroupId")
                .isEqualTo(id2);

        // Hash-code consistency — equals contract requires
        // a.equals(b) == true => a.hashCode() == b.hashCode().
        // This is what allows the JPA persistence context to use
        // composite-key instances as HashMap keys.
        assertThat(id1.hashCode())
                .as("Hashcode of two value-equal DisclosureGroupId "
                        + "instances must be identical; violation breaks the "
                        + "JPA identity-tracking HashMap and corrupts the "
                        + "persistence context per JPA §2.4")
                .isEqualTo(id2.hashCode());

        // Negative case — a differing catCd must make the composite key
        // non-equal. If this assertion fails, equals() is ignoring the
        // catCd sub-field, which would silently merge logically distinct
        // (DEFAULT, '01', 1) and (DEFAULT, '01', 2) rows in the
        // persistence context.
        assertThat(id1)
                .as("Two DisclosureGroupId instances differing only in "
                        + "catCd (1 vs 2) must NOT be value-equal; failure "
                        + "indicates equals() ignores the catCd sub-field, "
                        + "which would silently merge distinct rows in the "
                        + "JPA persistence context per JPA §2.4")
                .isNotEqualTo(id3);
    }

    // =========================================================================
    // Test 8 — findById miss returns Optional.empty()
    // =========================================================================

    /**
     * Validates the standard {@code Optional}-returning {@code findById}
     * contract: a composite primary key that does not exist in the
     * table returns {@code Optional.empty()} rather than throwing an
     * exception.
     *
     * <p>The composite key {@code ("UNKNOWN", "99", 9999)} is chosen to
     * be entirely outside the V012 seed ranges:</p>
     * <ul>
     *   <li>{@code "UNKNOWN"} is not one of {@code "A000000000"},
     *       {@code "DEFAULT"}, or {@code "ZEROAPR"};</li>
     *   <li>{@code "99"} is not one of {@code "01"}-{@code "07"};</li>
     *   <li>{@code 9999} is not one of {@code 1}-{@code 4}.</li>
     * </ul>
     * <p>so the {@code findById} call is guaranteed to miss.</p>
     *
     * <p>This contract is what allows the consumer service
     * {@code InterestCalculationService} to implement the DEFAULT-
     * fallback two-stage lookup pattern per AAP &sect;0.6.1:
     * <em>"an {@link Optional#isEmpty() empty Optional} from the first
     * {@link DisclosureGroupRepository#findById findById} call is the
     * EXPECTED, non-error signal that the service should retry with
     * the {@code "DEFAULT"} group ID"</em>. This mirrors the COBOL
     * file-status {@code 23} (NOTFND) branch in
     * {@code app/cbl/CBACT04C.cbl}:L436-L439, which is NOT an abend
     * condition but a routine control-flow signal per the
     * {@code IF DISCGRP-STATUS = '00' OR '23'} predicate. Any change in
     * this Spring Data JPA contract (e.g., a future version switching
     * to throw-on-miss behavior) would break the entire DEFAULT-
     * fallback logic in {@code InterestCalculationService}.</p>
     */
    @Test
    void findByCompositeId_emptyWhenNotFound() {
        // ("UNKNOWN", "99", 9999) is entirely outside V012 seed ranges:
        // - 'UNKNOWN' is not 'A000000000', 'DEFAULT', or 'ZEROAPR'
        // - '99' is not '01'-'07'
        // - 9999 is not 1-4
        // findById is guaranteed to miss.
        Optional<DisclosureGroup> result =
                repository.findById(new DisclosureGroupId("UNKNOWN", "99", 9999));

        assertThat(result)
                .as("findById on unknown composite key ('UNKNOWN', '99', 9999) "
                        + "must return Optional.empty() for a non-existent "
                        + "composite primary key per the standard Spring Data "
                        + "JPA contract; throw-on-miss would break the DEFAULT-"
                        + "fallback two-stage lookup pattern in "
                        + "InterestCalculationService per AAP §0.6.1 (mirroring "
                        + "COBOL file-status 23 NOTFND non-error control flow at "
                        + "app/cbl/CBACT04C.cbl:L436-L439)")
                .isEmpty();
    }

    // =========================================================================
    // Test 9 — deleteById removes a previously persisted custom row
    // =========================================================================

    /**
     * Validates that the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#deleteById(Object)}
     * method removes a previously persisted custom row, and that a
     * subsequent {@code findById} for the same composite key returns
     * {@code Optional.empty()}.
     *
     * <p>The test uses a custom composite key
     * {@code ("CUSTOM03", "99", 9996)} for the delete fixture.
     * <strong>It is FORBIDDEN to delete any V012-seeded row (the 51
     * canonical rows in groups {@code "A000000000"}, {@code "DEFAULT"},
     * {@code "ZEROAPR"}) in any test method</strong>: those rows are
     * the canonical reference data that the
     * {@code InterestCalculationServiceTest},
     * {@code StatementGenerationServiceTest}, and integration tests
     * depend on. Accidental deletion would corrupt downstream service
     * test fixtures.</p>
     *
     * <p>Although {@code @DataJpaTest} rolls back at the end of each
     * test, a delete-then-rollback sequence cannot un-delete a V012-
     * seeded row if the rollback fails or if the test is run with
     * {@code @Commit} for diagnostics. Confining all destructive
     * operations to custom (non-seeded) composite keys is therefore
     * defense-in-depth.</p>
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS WRITE} +
     * {@code DELETE DATASET('DISCGRP')} sequence.</p>
     */
    @Test
    void deleteById_removesByCompositeKey() {
        // Use a CUSTOM composite key (not V012-seeded) for the delete
        // fixture. NEVER delete V012-seeded rows (any of the 51 rows in
        // groups 'A000000000', 'DEFAULT', 'ZEROAPR').
        DisclosureGroup custom =
                buildDisclosureGroup("CUSTOM03", "99", 9996, new BigDecimal("12.50"));
        repository.save(custom);
        entityManager.flush();

        DisclosureGroupId key = new DisclosureGroupId("CUSTOM03", "99", 9996);

        // Confirm preconditions — the custom row exists immediately after
        // save + flush; if this fails, the delete-then-find assertion below
        // would be meaningless (we'd be testing whether deleteById removes
        // a row that was never persisted).
        assertThat(repository.findById(key))
                .as("Pre-condition: custom row ('CUSTOM03', '99', 9996) must "
                        + "be present immediately after save + flush, before "
                        + "delete is exercised")
                .isPresent();

        repository.deleteById(key);
        entityManager.flush();

        Optional<DisclosureGroup> afterDelete = repository.findById(key);

        assertThat(afterDelete)
                .as("After deleteById on composite key ('CUSTOM03', '99', "
                        + "9996), findById must return Optional.empty() — the "
                        + "row is removed from the disclosure_group table by "
                        + "composite key (replaces COBOL EXEC CICS DELETE "
                        + "DATASET('DISCGRP') RIDFLD(DIS-GROUP-KEY))")
                .isEmpty();
    }
}

