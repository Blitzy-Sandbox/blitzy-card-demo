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

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.repository.TransactionCategoryBalanceRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TransactionCategoryBalanceRepository} against a
 * real, Flyway-seeded PostgreSQL&nbsp;16 instance (Testcontainers, via
 * {@link AbstractIntegrationIT}). It is deliberately <em>not</em> backed by H2
 * or a mock: the whole point of this suite is to prove the repository behaves
 * correctly against the production database contract.
 *
 * <h2>What is under test</h2>
 * The {@code transaction_category_balance} table is the per
 * (account, transaction-type, category) running balance that the COBOL interest
 * program {@code CBACT04C} reads sequentially and rewrites
 * (paragraph {@code 1300-COMPUTE-INTEREST}), and that the posting engine
 * {@code CBTRN02C} reads/rewrites randomly. It is the relational replacement for
 * the VSAM {@code TCATBALF} KSDS defined by {@code TCATBALF.jcl}
 * ({@code KEYS(17 0)}, {@code RECORDSIZE(50 50)}), whose 17-byte key is the
 * COBOL {@code TRAN-CAT-KEY} group from copybook {@code CVTRA01Y} @ source commit
 * {@code 27d6c6f}:
 * <pre>
 *   TRANCAT-ACCT-ID PIC 9(11)  -&gt; acctId  (column acct_id, BIGINT)
 *   TRANCAT-TYPE-CD PIC X(02)  -&gt; typeCd  (column type_cd, CHAR(2))
 *   TRANCAT-CD      PIC 9(04)  -&gt; catCd   (column cat_cd,  INTEGER)
 * </pre>
 * That multi-field key is modeled in Java as the {@code @Embeddable}
 * {@link TransactionCategoryBalanceId} consumed through {@code @EmbeddedId} on
 * {@link TransactionCategoryBalance}. These tests therefore focus on the three
 * things a composite-key repository must get right:
 * <ol>
 *   <li><strong>Composite-key reads</strong> — {@code findById} resolves a row by
 *       the full three-part key, returns empty for an unknown key, and the
 *       Flyway seed count (50) is visible.</li>
 *   <li><strong>Composite-key identity</strong> — the embeddable's
 *       {@code equals}/{@code hashCode} span all three fields, which is what JPA
 *       (and any {@code Map}/{@code Set} of keys) relies on to distinguish rows.</li>
 *   <li><strong>Upsert / update</strong> — saving a fresh key inserts, while
 *       saving an existing key updates in place (mirroring the {@code CBACT04C}
 *       balance rewrite) without ever creating a duplicate row.</li>
 * </ol>
 *
 * <h2>Money precision</h2>
 * {@code TRAN-CAT-BAL PIC S9(09)V99} maps to {@code NUMERIC(11,2)} and is modeled
 * as {@link BigDecimal} (never {@code double}/{@code float}). Monetary
 * comparisons use {@code compareTo} semantics (AssertJ
 * {@code isEqualByComparingTo}) so a value is matched by magnitude rather than by
 * its {@code (unscaledValue, scale)} pair; the scale itself is asserted
 * separately where the {@code NUMERIC(11,2)} contract requires it.
 *
 * <h2>Data isolation</h2>
 * {@link AbstractIntegrationIT} shares a single PostgreSQL container across every
 * {@code *IT}. The mutating tests here are annotated {@link Transactional} so
 * Spring rolls their writes back, keeping the Flyway seed (50 rows) deterministic
 * for sibling tests. The read-only tests need no transaction. A genuine reload is
 * forced after each write by flushing and clearing the persistence context (via
 * the injected {@link EntityManager}) so {@code findById} re-reads from the
 * database rather than returning the first-level cache instance.
 */
@DisplayName("TransactionCategoryBalanceRepository IT — @EmbeddedId composite key on PostgreSQL 16 (TCATBALF / CVTRA01Y)")
class TransactionCategoryBalanceRepositoryIT extends AbstractIntegrationIT {

    /** Number of {@code transaction_category_balance} rows seeded by Flyway V3 from {@code tcatbal.txt}. */
    private static final long SEEDED_ROW_COUNT = 50L;

    /** Account id of the first seeded record ({@code 0000000001} in {@code tcatbal.txt}). */
    private static final long KNOWN_ACCT_ID = 1L;

    /** Transaction-type code shared by every seeded record ({@code TRANCAT-TYPE-CD = "01"}). */
    private static final String KNOWN_TYPE_CD = "01";

    /** Transaction-category code shared by every seeded record ({@code TRANCAT-CD = 0001}). */
    private static final int KNOWN_CAT_CD = 1;

    /**
     * An account id deliberately outside the seeded range {@code 1..50} (and within
     * the COBOL {@code PIC 9(11)} domain) used to assert "not found" behavior.
     */
    private static final long ABSENT_ACCT_ID = 99_999_999_999L;

    /** Money scale mandated by the {@code NUMERIC(11,2)} / {@code S9(09)V99} contract. */
    private static final int MONEY_SCALE = 2;

    @Autowired
    private TransactionCategoryBalanceRepository repository;

    /**
     * Standard JPA entity manager used purely to flush and clear the persistence
     * context inside the mutating tests, forcing {@code findById} to perform a real
     * database round-trip instead of returning the cached managed instance.
     */
    @PersistenceContext
    private EntityManager entityManager;

    // -------------------------------------------------------------------------------------
    // Phase 1 — Composite-key reads.
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("findById resolves a seeded row by its full three-part composite key")
    void findByIdResolvesSeededRowByCompositeKey() {
        TransactionCategoryBalanceId id =
                new TransactionCategoryBalanceId(KNOWN_ACCT_ID, KNOWN_TYPE_CD, KNOWN_CAT_CD);

        Optional<TransactionCategoryBalance> found = repository.findById(id);

        assertThat(found)
                .as("seeded composite key (acctId=%d, typeCd=%s, catCd=%d) must be present",
                        KNOWN_ACCT_ID, KNOWN_TYPE_CD, KNOWN_CAT_CD)
                .isPresent();

        TransactionCategoryBalance balance = found.orElseThrow();
        assertThat(balance.getId())
                .as("the materialized entity must echo the requested composite key")
                .isEqualTo(id);
        assertThat(balance.getTranCatBal())
                .as("seed balance for the first record is zero")
                .isEqualByComparingTo("0.00");
        assertThat(balance.getTranCatBal().scale())
                .as("NUMERIC(11,2) must surface as a scale-2 BigDecimal")
                .isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("findById returns empty for a composite key whose account is not seeded")
    void findByIdReturnsEmptyForUnknownCompositeKey() {
        TransactionCategoryBalanceId unknown =
                new TransactionCategoryBalanceId(ABSENT_ACCT_ID, KNOWN_TYPE_CD, KNOWN_CAT_CD);

        assertThat(repository.findById(unknown))
                .as("an account id outside the seeded 1..50 range must not resolve")
                .isEmpty();
    }

    @Test
    @DisplayName("count reflects the Flyway V3 seed of 50 category-balance rows")
    void countMatchesFlywaySeed() {
        assertThat(repository.count())
                .as("Flyway V3 seeds exactly %d rows from tcatbal.txt", SEEDED_ROW_COUNT)
                .isEqualTo(SEEDED_ROW_COUNT);
    }

    @Test
    @DisplayName("findAll returns every seeded category-balance row")
    void findAllReturnsEverySeededRow() {
        assertThat(repository.findAll())
                .as("findAll must return all %d seeded rows", SEEDED_ROW_COUNT)
                .hasSize((int) SEEDED_ROW_COUNT);
    }

    // -------------------------------------------------------------------------------------
    // Phase 2 — Composite-key identity (the @Embeddable equals/hashCode contract).
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("composite keys with identical fields are equal and share a hash code")
    void compositeKeysWithIdenticalFieldsAreEqual() {
        TransactionCategoryBalanceId a =
                new TransactionCategoryBalanceId(KNOWN_ACCT_ID, KNOWN_TYPE_CD, KNOWN_CAT_CD);
        TransactionCategoryBalanceId b =
                new TransactionCategoryBalanceId(KNOWN_ACCT_ID, KNOWN_TYPE_CD, KNOWN_CAT_CD);

        assertThat(a)
                .as("two keys with the same (acctId, typeCd, catCd) must be equal")
                .isEqualTo(b);
        assertThat(a)
                .as("equal composite keys must share a hash code (Map/Set + JPA identity)")
                .hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("composite keys differing in any single field are not equal")
    void compositeKeysDifferingInAnyFieldAreNotEqual() {
        TransactionCategoryBalanceId base =
                new TransactionCategoryBalanceId(KNOWN_ACCT_ID, KNOWN_TYPE_CD, KNOWN_CAT_CD);

        assertThat(base)
                .as("a different acctId must break equality")
                .isNotEqualTo(new TransactionCategoryBalanceId(2L, KNOWN_TYPE_CD, KNOWN_CAT_CD));
        assertThat(base)
                .as("a different typeCd must break equality")
                .isNotEqualTo(new TransactionCategoryBalanceId(KNOWN_ACCT_ID, "02", KNOWN_CAT_CD));
        assertThat(base)
                .as("a different catCd must break equality")
                .isNotEqualTo(new TransactionCategoryBalanceId(KNOWN_ACCT_ID, KNOWN_TYPE_CD, 2));
    }

    // -------------------------------------------------------------------------------------
    // Phase 3 — Upsert / update (the CBACT04C balance rewrite). Mutating tests roll back.
    // -------------------------------------------------------------------------------------

    @Test
    @Transactional
    @DisplayName("saving a fresh composite key inserts a row that reloads via findById")
    void saveNewCompositeKeyInsertsAndReloads() {
        TransactionCategoryBalanceId newKey =
                new TransactionCategoryBalanceId(90_000_000_001L, "99", 9999);
        BigDecimal openingBalance = new BigDecimal("250.75");

        assertThat(repository.findById(newKey))
                .as("precondition: the fresh composite key must not already exist")
                .isEmpty();

        repository.saveAndFlush(new TransactionCategoryBalance(newKey, openingBalance));
        // Detach everything so the subsequent findById is a genuine SELECT, not a cache hit.
        entityManager.clear();

        Optional<TransactionCategoryBalance> reloaded = repository.findById(newKey);
        assertThat(reloaded)
                .as("the newly inserted composite key must reload from the database")
                .isPresent();

        TransactionCategoryBalance loaded = reloaded.orElseThrow();
        assertThat(loaded.getId())
                .as("the reloaded row must carry the exact composite key that was saved")
                .isEqualTo(newKey);
        assertThat(loaded.getTranCatBal())
                .as("the reloaded balance must equal the value that was persisted")
                .isEqualByComparingTo(openingBalance);
        assertThat(loaded.getTranCatBal().scale())
                .as("NUMERIC(11,2) must surface as a scale-2 BigDecimal")
                .isEqualTo(MONEY_SCALE);
    }

    @Test
    @Transactional
    @DisplayName("updating an existing balance changes the value in place without inserting a row")
    void updateExistingBalanceDoesNotInsertRow() {
        long rowCountBefore = repository.count();

        TransactionCategoryBalanceId key =
                new TransactionCategoryBalanceId(KNOWN_ACCT_ID, KNOWN_TYPE_CD, KNOWN_CAT_CD);
        TransactionCategoryBalance existing = repository.findById(key).orElseThrow();
        BigDecimal originalBalance = existing.getTranCatBal();

        // Interest-like increment, mirroring CBACT04C 1300-COMPUTE-INTEREST:
        //   monthly interest = (balance * annual-rate) / 1200, rounded HALF_EVEN to cents.
        // Computed against a representative principal/rate so the magnitude is non-trivial and
        // the HALF_EVEN rounding of 10.41666... -> 10.42 is exercised explicitly.
        BigDecimal principal = new BigDecimal("1000.00");
        BigDecimal annualRate = new BigDecimal("12.50");
        BigDecimal interest = principal.multiply(annualRate)
                .divide(new BigDecimal("1200"), MONEY_SCALE, RoundingMode.HALF_EVEN);
        assertThat(interest)
                .as("(1000.00 * 12.50) / 1200 rounds HALF_EVEN to 10.42")
                .isEqualByComparingTo("10.42");

        BigDecimal updatedBalance = originalBalance.add(interest).setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        existing.setTranCatBal(updatedBalance);
        repository.saveAndFlush(existing);
        // Detach so the reload below is a real round-trip through the database.
        entityManager.clear();

        TransactionCategoryBalance reloaded = repository.findById(key).orElseThrow();
        assertThat(reloaded.getTranCatBal())
                .as("the reloaded balance must reflect the persisted interest-like update")
                .isEqualByComparingTo(updatedBalance);
        assertThat(reloaded.getTranCatBal())
                .as("the update must have increased the balance above its seeded value")
                .isGreaterThan(originalBalance);
        assertThat(reloaded.getTranCatBal().scale())
                .as("NUMERIC(11,2) must surface as a scale-2 BigDecimal")
                .isEqualTo(MONEY_SCALE);
        assertThat(repository.count())
                .as("updating an existing composite key must not add a row (update, not insert)")
                .isEqualTo(rowCountBefore);
    }
}
