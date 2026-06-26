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
package com.carddemo.integration;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.repository.DisclosureGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link DisclosureGroupRepository} against a <strong>real
 * PostgreSQL&nbsp;16</strong> container (Testcontainers), with the schema and the
 * 51-row seed applied by the Flyway {@code V1}/{@code V2}/{@code V3} migrations —
 * no H2, no mocks. It extends {@link AbstractIntegrationIT}, so it reuses the
 * suite's shared singleton PostgreSQL container, the {@code test} Spring profile,
 * and {@code ddl-auto=validate} (the context starts only when every {@code @Entity}
 * mapping — including the fixed-width {@code CHAR} composite-key bindings — agrees
 * with the migrated schema).
 *
 * <h2>What is under test</h2>
 * The disclosure-group reference table is translated (never copied) from COBOL
 * copybook {@code app/cpy/CVTRA02Y.cpy} ({@code DIS-GROUP-RECORD}, RECLN&nbsp;50)
 * at source commit SHA {@code 27d6c6f}. Its three-part business key
 * ({@code DIS-ACCT-GROUP-ID PIC X(10)} + {@code DIS-TRAN-TYPE-CD PIC X(02)} +
 * {@code DIS-TRAN-CAT-CD PIC 9(04)}) is modelled by the {@code @EmbeddedId}
 * {@link DisclosureGroupId}; the {@code DIS-INT-RATE PIC S9(04)V99} rate maps to a
 * {@link BigDecimal} backed by a {@code NUMERIC(6,2)} column. That rate feeds the
 * batch interest formula {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} in program
 * {@code app/cbl/CBACT04C.cbl} (VSAM {@code DISCGRP} KSDS provisioned by
 * {@code app/jcl/DISCGRP.jcl}).
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li><strong>Composite-key reads</strong> — keyed {@code findById} for a known
 *       seeded combination, a miss for a non-existent composite, and the seeded
 *       cardinality ({@code count}/{@code findAll}).</li>
 *   <li><strong>CHAR(10) fidelity</strong> — the legacy {@code PIC X(10)} group id
 *       is stored blank-padded in the {@code CHAR(10)} column and round-trips
 *       through the repository.</li>
 *   <li><strong>DEFAULT-group fallback</strong> — the {@code 'DEFAULT'} account
 *       group that {@code CBACT04C} paragraph {@code 1200-A-GET-DEFAULT-INT-RATE}
 *       re-reads when an account's specific group has no rate is present and
 *       resolvable (this test models the read the interest processor relies on;
 *       the fallback orchestration itself lives in the processor, not the
 *       repository).</li>
 *   <li><strong>Composite-key equality</strong> — {@link DisclosureGroupId}
 *       {@code equals}/{@code hashCode} over all three key fields.</li>
 *   <li><strong>Persist / update</strong> — {@code save} then keyed reload, an
 *       in-place rate update (no extra row), and exact {@code NUMERIC(6,2)} scale
 *       on read-back.</li>
 * </ul>
 *
 * <p>Monetary rates are always compared with {@code compareTo} semantics
 * (AssertJ's {@code isEqualByComparingTo}) so scale never confuses value equality,
 * and the explicit {@code NUMERIC(6,2)} scale is asserted separately. Mutating
 * tests are {@code @Transactional} so Spring rolls their writes back, keeping the
 * shared 51-row seed deterministic for the read assertions regardless of test
 * order.</p>
 */
@DisplayName("DisclosureGroupRepository IT — composite key, CHAR(10), DEFAULT fallback & NUMERIC(6,2) rate (PostgreSQL 16 + Flyway)")
class DisclosureGroupRepositoryIT extends AbstractIntegrationIT {

    /**
     * First seeded account group from {@code discgrp.txt}. Already exactly ten
     * characters, so it occupies the {@code CHAR(10)} column with no padding.
     */
    private static final String GROUP_A = "A000000000";

    /**
     * The {@code 'DEFAULT'} account group, written byte-for-byte as it is stored:
     * the seed inserts the 7-character literal {@code 'DEFAULT'} into a
     * {@code CHAR(10)} column, so PostgreSQL {@code bpchar} blank-pads it to
     * {@code "DEFAULT   "} (DEFAULT + three trailing spaces). Querying with this
     * exact 10-character representation matches the stored row under any
     * comparison semantics — mirroring how COBOL {@code CBACT04C} moves the
     * literal {@code 'DEFAULT'} into the {@code PIC X(10)} key field
     * {@code FD-DIS-ACCT-GROUP-ID} (which space-pads identically) before the
     * fallback re-read.
     */
    private static final String DEFAULT_GROUP = "DEFAULT   ";

    /** Total disclosure-group rows seeded by Flyway {@code V3} from {@code discgrp.txt}. */
    private static final long SEEDED_ROW_COUNT = 51L;

    /** Repository under test, wired against the real Testcontainers PostgreSQL. */
    @Autowired
    private DisclosureGroupRepository repository;

    /**
     * Shared, transaction-bound persistence context used only by the mutating
     * tests to {@code flush()} pending writes and {@code clear()} the first-level
     * cache, forcing a genuine {@code SELECT} on reload (so a {@code NUMERIC(6,2)}
     * round-trip and an update-vs-insert are proven against the database, not the
     * in-memory entity).
     */
    @PersistenceContext
    private EntityManager entityManager;

    // ---------------------------------------------------------------------------------------
    // Phase 1 — composite-key reads
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("findById resolves a known seeded composite key and reads the rate at NUMERIC(6,2) scale")
    void findById_knownSeededCompositeKey_isPresentWithScaleTwoRate() {
        DisclosureGroupId key = new DisclosureGroupId(GROUP_A, "01", 1);

        DisclosureGroup group = repository.findById(key).orElseThrow();

        // The composite key round-trips intact (all three parts).
        assertThat(group.getId()).isEqualTo(key);
        // ('A000000000','01',0001) seeds DIS-INT-RATE 00150{ -> 15.00 (zoned '{' = +0).
        assertThat(group.getDisIntRate()).isEqualByComparingTo("15.00");
        // DIS-INT-RATE PIC S9(04)V99 -> NUMERIC(6,2): always read back at scale 2.
        assertThat(group.getDisIntRate().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("findById returns empty for a non-existent composite key (all three key parts are significant)")
    void findById_nonExistentCompositeKey_isEmpty() {
        // Real account group, but a transaction type/category that is not seeded for it.
        assertThat(repository.findById(new DisclosureGroupId(GROUP_A, "99", 9999))).isEmpty();
        // A wholly unknown account group.
        assertThat(repository.findById(new DisclosureGroupId("ZZZZZZZZZZ", "ZZ", 9999))).isEmpty();
    }

    @Test
    @DisplayName("count() and findAll() report the 51 disclosure-group rows seeded by Flyway V3")
    void countAndFindAll_reportFiftyOneSeededRows() {
        assertThat(repository.count()).isEqualTo(SEEDED_ROW_COUNT);
        assertThat(repository.findAll()).hasSize((int) SEEDED_ROW_COUNT);
    }

    // ---------------------------------------------------------------------------------------
    // CHAR(10) group-id fidelity
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("acct_group_id is a fixed-width CHAR(10) column; the legacy PIC X(10) id round-trips with space-insensitive matching")
    void charTenAccountGroupId_isFixedWidthAndRoundTrips() {
        // The column is declared CHAR(10), mirroring DIS-ACCT-GROUP-ID PIC X(10). information_schema
        // is the authoritative, driver-independent proof of the fixed width and the character type.
        // (A value-length probe is unreliable here: PostgreSQL strips trailing blanks when a bpchar
        // value is cast to text or measured with length(), so the declared width is the right check.)
        Integer declaredWidth = jdbcTemplate.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = 'disclosure_group' AND column_name = 'acct_group_id'",
                Integer.class);
        assertThat(declaredWidth).as("acct_group_id must be declared CHAR(10)").isEqualTo(10);

        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'disclosure_group' AND column_name = 'acct_group_id'",
                String.class);
        assertThat(dataType).as("acct_group_id must be a fixed-width character type").isEqualTo("character");

        // CHAR(10) comparison is trailing-space insensitive. COBOL CBACT04C moves the 7-character
        // literal 'DEFAULT' into the PIC X(10) key field before the fallback re-read; here both the
        // blank-padded ("DEFAULT   ") and the bare ("DEFAULT") forms resolve the very same row.
        DisclosureGroup viaPadded =
                repository.findById(new DisclosureGroupId(DEFAULT_GROUP, "01", 1)).orElseThrow();
        DisclosureGroup viaBare =
                repository.findById(new DisclosureGroupId("DEFAULT", "01", 1)).orElseThrow();
        assertThat(viaBare.getDisIntRate()).isEqualByComparingTo(viaPadded.getDisIntRate());
        assertThat(viaPadded.getId().getAcctGroupId()).startsWith("DEFAULT");
        assertThat(viaPadded.getId().getAcctGroupId().trim()).isEqualTo("DEFAULT");
        assertThat(viaPadded.getId().getTranTypeCd()).isEqualTo("01");
        assertThat(viaPadded.getId().getTranCatCd()).isEqualTo(1);

        // A naturally ten-character id is returned verbatim at its full CHAR(10) width.
        DisclosureGroup a =
                repository.findById(new DisclosureGroupId(GROUP_A, "01", 1)).orElseThrow();
        assertThat(a.getId().getAcctGroupId()).isEqualTo(GROUP_A).hasSize(10);
    }

    // ---------------------------------------------------------------------------------------
    // Phase 2 — DEFAULT-group fallback (the read CBACT04C / InterestProcessor relies on)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("DEFAULT account group is present and resolvable — the interest-rate fallback for groups without a specific rate")
    void defaultGroup_fallbackRow_isPresentForInterestProcessor() {
        // CBACT04C 1200-A-GET-DEFAULT-INT-RATE re-reads DISCGRP under the 'DEFAULT' group when
        // an account's own group has no matching row. The seed provides ('DEFAULT','01',0001).
        DisclosureGroup fallback =
                repository.findById(new DisclosureGroupId(DEFAULT_GROUP, "01", 1)).orElseThrow();
        assertThat(fallback.getDisIntRate()).isEqualByComparingTo("15.00");
        assertThat(fallback.getDisIntRate().scale()).isEqualTo(2);
        assertThat(fallback.getId().getAcctGroupId().trim()).isEqualTo("DEFAULT");

        // Fallback semantics: an account-group with no seeded rate is absent, while DEFAULT
        // supplies a rate for the same (transaction type, category) — exactly the two-step
        // lookup the interest processor performs.
        assertThat(repository.findById(new DisclosureGroupId("B000000000", "01", 1)))
                .as("an unseeded specific group must be absent, triggering the DEFAULT fallback")
                .isEmpty();
        assertThat(repository.findById(new DisclosureGroupId(DEFAULT_GROUP, "01", 1)))
                .as("DEFAULT must supply the rate for the same (type, category)")
                .isPresent();
    }

    // ---------------------------------------------------------------------------------------
    // Phase 3 — composite-key equality, persist/update, and NUMERIC(6,2) precision
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("DisclosureGroupId equals/hashCode honour all three key fields (acctGroupId, tranTypeCd, tranCatCd)")
    void disclosureGroupId_honoursEqualsAndHashCodeOverAllThreeFields() {
        DisclosureGroupId base = new DisclosureGroupId(GROUP_A, "01", 1);
        DisclosureGroupId same = new DisclosureGroupId(GROUP_A, "01", 1);
        DisclosureGroupId diffGroup = new DisclosureGroupId("B000000000", "01", 1);
        DisclosureGroupId diffType = new DisclosureGroupId(GROUP_A, "02", 1);
        DisclosureGroupId diffCat = new DisclosureGroupId(GROUP_A, "01", 2);

        // Reflexive, and equal (with a consistent hash code) when every field matches.
        assertThat(base).isEqualTo(base);
        assertThat(base).isEqualTo(same).hasSameHashCodeAs(same);

        // Differing in any single key field breaks equality.
        assertThat(base).isNotEqualTo(diffGroup);
        assertThat(base).isNotEqualTo(diffType);
        assertThat(base).isNotEqualTo(diffCat);

        // Robust against null and a foreign type.
        assertThat(base.equals(null)).isFalse();
        assertThat(base.equals("A000000000/01/1")).isFalse();

        // The entity's identity is its composite key (equals/hashCode delegate to the id).
        assertThat(new DisclosureGroup(same, new BigDecimal("15.00")))
                .isEqualTo(new DisclosureGroup(base, new BigDecimal("99.99")));
    }

    @Test
    @Transactional
    @DisplayName("save persists a new row reachable by its composite key; an in-place rate update changes the value, not the row count")
    void save_reloadByCompositeKey_updateRate_leavesCountUnchanged() {
        long baseline = repository.count();
        DisclosureGroupId id = new DisclosureGroupId("ITGRP00001", "99", 9999);

        // Insert a brand-new disclosure group, flush, then drop the first-level cache so the
        // reload is a real SELECT keyed by the composite id.
        repository.saveAndFlush(new DisclosureGroup(id, new BigDecimal("3.33")));
        entityManager.clear();

        DisclosureGroup inserted = repository.findById(id).orElseThrow();
        assertThat(inserted.getDisIntRate()).isEqualByComparingTo("3.33");
        assertThat(inserted.getDisIntRate().scale()).isEqualTo(2);
        long afterInsert = repository.count();
        assertThat(afterInsert).isEqualTo(baseline + 1);

        // Update the rate on the existing row (VSAM REWRITE -> save of an existing key).
        inserted.setDisIntRate(new BigDecimal("4.44"));
        repository.saveAndFlush(inserted);
        entityManager.clear();

        DisclosureGroup updated = repository.findById(id).orElseThrow();
        assertThat(updated.getDisIntRate()).isEqualByComparingTo("4.44");
        // An update must not insert a second row for the same composite key.
        assertThat(repository.count()).isEqualTo(afterInsert);
    }

    @Test
    @Transactional
    @DisplayName("dis_int_rate is persisted as NUMERIC(6,2): values read back at scale 2 regardless of the supplied scale")
    void disIntRate_isPersistedAtNumericSixTwoScale() {
        // The AAP example rate 01.25 (already scale 2) must round-trip unchanged at scale 2.
        DisclosureGroupId scaleTwoId = new DisclosureGroupId("PRECGRP125", "99", 1);
        repository.saveAndFlush(new DisclosureGroup(scaleTwoId, new BigDecimal("1.25")));

        // A rate supplied at scale 1 must be normalised by the NUMERIC(6,2) column to scale 2.
        DisclosureGroupId scaleOneId = new DisclosureGroupId("PRECGRP150", "99", 2);
        repository.saveAndFlush(new DisclosureGroup(scaleOneId, new BigDecimal("7.5")));

        // Defeat the first-level cache so both reads come from the database with the column's scale.
        entityManager.clear();

        DisclosureGroup scaleTwo = repository.findById(scaleTwoId).orElseThrow();
        assertThat(scaleTwo.getDisIntRate()).isEqualByComparingTo("1.25");
        assertThat(scaleTwo.getDisIntRate().scale()).isEqualTo(2);

        DisclosureGroup scaleOne = repository.findById(scaleOneId).orElseThrow();
        assertThat(scaleOne.getDisIntRate()).isEqualByComparingTo("7.5");
        // 7.5 supplied -> stored and read back as 7.50 (scale 2), proving NUMERIC(6,2) enforcement.
        assertThat(scaleOne.getDisIntRate().scale()).isEqualTo(2);
    }
}
