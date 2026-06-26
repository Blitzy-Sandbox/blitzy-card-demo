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

import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategoryId;
import com.carddemo.repository.TransactionCategoryRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TransactionCategoryRepository} exercised against a
 * <strong>real PostgreSQL&nbsp;16 Testcontainer</strong> (no H2, no mocks, no
 * hardcoded ports), with the {@code transaction_category} reference table seeded
 * by the production Flyway migrations applied inside the container
 * ({@code V1__create_schema.sql} &rarr; {@code V2__create_indexes.sql} &rarr;
 * {@code V3__seed_data.sql}). All real-infrastructure wiring is inherited from
 * {@link AbstractIntegrationIT}.
 *
 * <h2>What is under test</h2>
 * The {@code transaction_category} table is the relational successor of the
 * legacy VSAM KSDS {@code TRANCATG} (JCL {@code TRANCATG.jcl}, KEYS(6&nbsp;0),
 * RECORDSIZE(60&nbsp;60)), whose record layout is COBOL copybook
 * {@code CVTRA04Y} ({@code TRAN-CAT-RECORD}, RECLN&nbsp;60) at source commit
 * {@code 27d6c6f}:
 * <pre>
 *   05 TRAN-CAT-KEY.
 *      10 TRAN-TYPE-CD       PIC X(02)   -&gt; tran_type_cd CHAR(2)
 *      10 TRAN-CAT-CD        PIC 9(04)   -&gt; tran_cat_cd  INTEGER
 *   05 TRAN-CAT-TYPE-DESC    PIC X(50)   -&gt; tran_cat_type_desc VARCHAR(50)
 *   05 FILLER                PIC X(04)   -&gt; (reserved padding, not persisted)
 * </pre>
 *
 * <p>This reference table is consumed at runtime by
 * {@code TransactionReportProcessor} (the Java translation of batch program
 * {@code CBTRN03C}, paragraph {@code 1500-C-LOOKUP-TRANCATG}): for every report
 * line it builds the composite key
 * {@code new TransactionCategoryId(typeCode, categoryCode)}, performs a keyed
 * {@code findById}, and uses {@link TransactionCategory#getTranCatTypeDesc()} to
 * enrich the line with the human-readable category description. These tests
 * therefore verify the exact composite-key lookup path that the report pipeline
 * depends on, plus the full reference-set integrity and the
 * {@link TransactionCategoryId} value-equality contract that makes that lookup
 * deterministic.
 *
 * <h2>Data isolation</h2>
 * Every test here is read-only against the V3-seeded reference rows, so no
 * {@code @Transactional} boundary or explicit cleanup is required and the shared
 * container data stays deterministic for sibling ITs.
 */
@DisplayName("TransactionCategoryRepository — composite-key reference lookups (real PostgreSQL 16)")
class TransactionCategoryRepositoryIT extends AbstractIntegrationIT {

    /** Number of reference rows seeded by Flyway V3 from {@code trancatg.txt}. */
    private static final long EXPECTED_ROW_COUNT = 18L;

    /**
     * The full, authoritative {@code (tran_type_cd, tran_cat_cd) -> description}
     * reference set as seeded by {@code V3__seed_data.sql} (decoded from the
     * {@code app/data/ASCII/trancatg.txt} fixture, descriptions trimmed of the
     * COBOL {@code PIC X(50)} padding). This is the exact data
     * {@code TransactionReportProcessor} resolves when enriching report lines,
     * so it doubles as the expected lookup table for the enrichment test.
     * {@link Map#ofEntries(Map.Entry[])} additionally guards that all 18
     * composite keys are unique.
     */
    private static final Map<TransactionCategoryId, String> EXPECTED_CATEGORIES = Map.ofEntries(
            Map.entry(new TransactionCategoryId("01", 1), "Regular Sales Draft"),
            Map.entry(new TransactionCategoryId("01", 2), "Regular Cash Advance"),
            Map.entry(new TransactionCategoryId("01", 3), "Convenience Check Debit"),
            Map.entry(new TransactionCategoryId("01", 4), "ATM Cash Advance"),
            Map.entry(new TransactionCategoryId("01", 5), "Interest Amount"),
            Map.entry(new TransactionCategoryId("02", 1), "Cash payment"),
            Map.entry(new TransactionCategoryId("02", 2), "Electronic payment"),
            Map.entry(new TransactionCategoryId("02", 3), "Check payment"),
            Map.entry(new TransactionCategoryId("03", 1), "Credit to Account"),
            Map.entry(new TransactionCategoryId("03", 2), "Credit to Purchase balance"),
            Map.entry(new TransactionCategoryId("03", 3), "Credit to Cash balance"),
            Map.entry(new TransactionCategoryId("04", 1), "Zero dollar authorization"),
            Map.entry(new TransactionCategoryId("04", 2), "Online purchase authorization"),
            Map.entry(new TransactionCategoryId("04", 3), "Travel booking authorization"),
            Map.entry(new TransactionCategoryId("05", 1), "Refund credit"),
            Map.entry(new TransactionCategoryId("06", 1), "Fraud reversal"),
            Map.entry(new TransactionCategoryId("06", 2), "Non-fraud reversal"),
            Map.entry(new TransactionCategoryId("07", 1), "Sales draft credit adjustment"));

    /** Repository under test — the composite-keyed JPA successor of VSAM {@code TRANCATG}. */
    @Autowired
    private TransactionCategoryRepository repository;

    // -------------------------------------------------------------------------------------
    // Phase 1 — Composite-key lookups (the report-enrichment access path)
    // -------------------------------------------------------------------------------------

    /**
     * The canonical enrichment lookup: resolving the first reference key
     * ({@code TRAN-TYPE-CD = "01"}, {@code TRAN-CAT-CD = 1}) must return the
     * seeded {@code "Regular Sales Draft"} description. This is precisely the
     * {@code new TransactionCategoryId(typeCode, catCd)} &rarr; {@code findById}
     * &rarr; {@code getTranCatTypeDesc()} sequence that
     * {@code TransactionReportProcessor} performs for every report detail line.
     */
    @Test
    @DisplayName("findById: seeded composite key (01,1) resolves to 'Regular Sales Draft'")
    void findById_withSeededCompositeKey_returnsRegularSalesDraftDescription() {
        TransactionCategoryId key = new TransactionCategoryId("01", 1);

        Optional<TransactionCategory> found = repository.findById(key);

        assertThat(found)
                .as("composite key %s must be present in the V3-seeded reference table", key)
                .isPresent();

        TransactionCategory category = found.orElseThrow();
        assertThat(category.getId())
                .as("round-tripped composite key must equal the lookup key")
                .isEqualTo(key);
        assertThat(category.getTranCatTypeDesc())
                .as("the enrichment description the report processor surfaces")
                .isEqualTo("Regular Sales Draft");
    }

    /**
     * A composite key that was never seeded must yield {@link Optional#empty()};
     * the report processor relies on this to detect and reject unknown
     * type/category combinations rather than receiving stale data.
     */
    @Test
    @DisplayName("findById: a non-existent composite key returns empty")
    void findById_withNonExistentCompositeKey_returnsEmpty() {
        TransactionCategoryId absentKey = new TransactionCategoryId("99", 9999);

        Optional<TransactionCategory> found = repository.findById(absentKey);

        assertThat(found)
                .as("composite key %s is not seeded and must not resolve", absentKey)
                .isEmpty();
    }

    /**
     * Exercises the complete enrichment table: every one of the 18 seeded
     * composite keys must resolve, via the same {@code findById} path the report
     * processor uses, to its exact (trimmed) description. This is the
     * byte-fidelity guarantee for category-description enrichment across the
     * whole reference set, not just the first row.
     */
    @Test
    @DisplayName("findById: every seeded composite key resolves to its exact description")
    void findById_resolvesEverySeededEnrichmentDescription() {
        EXPECTED_CATEGORIES.forEach((key, expectedDescription) ->
                assertThat(repository.findById(key))
                        .as("enrichment lookup for composite key %s", key)
                        .hasValueSatisfying(category ->
                                assertThat(category.getTranCatTypeDesc())
                                        .isEqualTo(expectedDescription)));
    }

    // -------------------------------------------------------------------------------------
    // Phase 2 — Full reference set + composite-key equality contract
    // -------------------------------------------------------------------------------------

    /**
     * The reference table must contain exactly the 18 rows seeded from
     * {@code trancatg.txt}; an unexpected count would mean a seed regression or
     * data leakage from another test.
     */
    @Test
    @DisplayName("count: the reference table holds exactly 18 seeded rows")
    void count_returnsExactlyEighteenSeededRows() {
        assertThat(repository.count())
                .as("transaction_category must hold exactly the 18 trancatg.txt rows")
                .isEqualTo(EXPECTED_ROW_COUNT);
    }

    /**
     * A full-table load (the access pattern used to cache enrichment lookups)
     * must return all 18 rows, each with a populated composite key and a
     * non-blank category description.
     */
    @Test
    @DisplayName("findAll: returns all 18 rows, each with a key and a non-blank description")
    void findAll_returnsAllEighteenRowsWithNonBlankDescriptions() {
        List<TransactionCategory> all = repository.findAll();

        assertThat(all)
                .as("full reference set must contain all 18 seeded categories")
                .hasSize((int) EXPECTED_ROW_COUNT)
                .allSatisfy(category -> {
                    assertThat(category.getId())
                            .as("every reference row must carry a composite key")
                            .isNotNull();
                    assertThat(category.getId().getTranTypeCd())
                            .as("every row must carry a transaction-type code")
                            .isNotBlank();
                    assertThat(category.getId().getTranCatCd())
                            .as("every row must carry a transaction-category code")
                            .isNotNull();
                    assertThat(category.getTranCatTypeDesc())
                            .as("every row must carry a non-blank category description")
                            .isNotBlank();
                });
    }

    /**
     * {@link TransactionCategoryId} is a JPA {@code @EmbeddedId}: correct
     * value-based {@code equals}/{@code hashCode} over <em>both</em> components
     * is what makes {@code findById} and map-based caching deterministic. Two
     * keys with identical {@code tranTypeCd} and {@code tranCatCd} must be equal,
     * mutually equal (symmetry), reflexively equal, and share a hash code.
     */
    @Test
    @DisplayName("TransactionCategoryId: equal and same hashCode when both components match")
    void transactionCategoryId_equalsAndHashCode_equalWhenBothComponentsMatch() {
        TransactionCategoryId a = new TransactionCategoryId("01", 1);
        TransactionCategoryId b = new TransactionCategoryId("01", 1);

        assertThat(a).isEqualTo(a);                 // reflexive
        assertThat(a).isEqualTo(b);                 // equal by value
        assertThat(b).isEqualTo(a);                 // symmetric
        assertThat(a).hasSameHashCodeAs(b);         // consistent hashCode
    }

    /**
     * Conversely, the composite key must distinguish records whenever
     * <em>either</em> component differs, and must never equal {@code null} or an
     * unrelated type. This guards against a degenerate key that would collapse
     * distinct categories during enrichment.
     */
    @Test
    @DisplayName("TransactionCategoryId: unequal when either component differs (and vs null/other type)")
    void transactionCategoryId_unequalWhenAnyComponentDiffers() {
        TransactionCategoryId base = new TransactionCategoryId("01", 1);

        assertThat(base)
                .as("differing transaction-type code must not be equal")
                .isNotEqualTo(new TransactionCategoryId("02", 1));
        assertThat(base)
                .as("differing transaction-category code must not be equal")
                .isNotEqualTo(new TransactionCategoryId("01", 2));
        assertThat(base)
                .as("both components differing must not be equal")
                .isNotEqualTo(new TransactionCategoryId("02", 2));

        // Explicit equals-contract branches: null and an unrelated type.
        assertThat(base.equals(null))
                .as("a composite key is never equal to null")
                .isFalse();
        assertThat(base.equals("01"))
                .as("a composite key is never equal to a different type")
                .isFalse();
    }
}
