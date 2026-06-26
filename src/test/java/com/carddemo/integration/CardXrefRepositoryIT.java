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

import com.carddemo.entity.CardXref;
import com.carddemo.repository.CardXrefRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link CardXrefRepository} against a <em>real</em> PostgreSQL&nbsp;16
 * Testcontainer (Flyway-seeded), exercised through the shared {@link AbstractIntegrationIT}
 * base. There is no H2, no mock and no hardcoded port: the datasource is injected at runtime
 * from the container, Flyway applies the {@code V1}/{@code V2}/{@code V3} migrations, and
 * Hibernate runs with {@code ddl-auto=validate}, so the context starts only when the
 * {@code CardXref} mapping agrees with the migrated {@code card_xref} schema and its
 * {@code idx_card_xref_acct_id} secondary index.
 *
 * <h2>What is under test (legacy lineage)</h2>
 * The repository replaces the VSAM {@code XREFFILE} KSDS and its account alternate index
 * (copybook {@code CVACT03Y} {@code CARD-XREF-RECORD}, record length&nbsp;50; sequential
 * reader program {@code CBACT03C}; provisioning JCL {@code app/jcl/XREFFILE.jcl}). Two access
 * paths are verified, mirroring the legacy VSAM contract:
 * <ul>
 *   <li><strong>Primary key</strong> &mdash; {@code XREFFILE.jcl} defines the base cluster as
 *       {@code KEYS(16 0)} on {@code XREF-CARD-NUM}, served here by the inherited
 *       {@link org.springframework.data.repository.CrudRepository#findById(Object) findById}.</li>
 *   <li><strong>Account alternate index (non-unique)</strong> &mdash; {@code XREFFILE.jcl}
 *       defines {@code KEYS(11,25) NONUNIQUEKEY} on {@code XREF-ACCT-ID}, served by
 *       {@link CardXrefRepository#findByXrefAcctId(Long)}. This is the exact first lookup the
 *       {@code AccountViewService} (acctId &rarr; xref &rarr; account &rarr; customer) and the
 *       {@code FileServiceReader} XREFFILE path perform.</li>
 * </ul>
 *
 * <h2>Seed expectations</h2>
 * Flyway {@code V3} seeds the {@code card_xref} table from {@code app/data/ASCII/cardxref.txt}
 * with exactly {@value #EXPECTED_SEED_ROWS} rows. The first seeded record links card number
 * {@code 0500024453765740} to customer {@code 50} / account {@code 50}; the asserted values
 * below are taken verbatim from that fixture. The fixture is 1:1 (each account maps to exactly
 * one card / one xref row), so the non-uniqueness path asserts a single-element list while
 * documenting that the alternate-index column itself is non-unique by design.
 *
 * <h2>Mutation isolation</h2>
 * The persist / delete tests are {@link Transactional}, so Spring rolls their writes back at
 * method end and the shared container's seeded {@code card_xref} table stays deterministic
 * (still {@value #EXPECTED_SEED_ROWS} rows) for every other test and test class that reuses the
 * singleton container. A {@link PersistenceContext}-injected {@link EntityManager} is used to
 * {@code flush()} then {@code clear()} the persistence context inside those transactions so a
 * subsequent read is a genuine round-trip to PostgreSQL rather than a first-level-cache hit.
 */
@DisplayName("CardXrefRepository IT — PostgreSQL 16 + Flyway (VSAM XREFFILE KSDS + non-unique acct AIX)")
class CardXrefRepositoryIT extends AbstractIntegrationIT {

    /** Number of {@code card_xref} rows Flyway V3 seeds from {@code cardxref.txt}. */
    private static final int EXPECTED_SEED_ROWS = 50;

    /** First seeded card number (primary key) from {@code cardxref.txt}. */
    private static final String SEEDED_CARD_NUM = "0500024453765740";

    /** Customer id linked to {@link #SEEDED_CARD_NUM} in the fixture. */
    private static final Long SEEDED_CUST_ID = 50L;

    /** Account id linked to {@link #SEEDED_CARD_NUM} in the fixture (also its acct-index key). */
    private static final Long SEEDED_ACCT_ID = 50L;

    /** A syntactically valid 16-character card number that is absent from the seed. */
    private static final String ABSENT_CARD_NUM = "9999999999999999";

    /** An account id (within {@code PIC 9(11)}) that no seeded xref row references. */
    private static final Long ABSENT_ACCT_ID = 99_999_999_999L;

    /** Fresh 16-character card number for the persist test (absent from the seed). */
    private static final String NEW_CARD_NUM = "9999000011112222";

    /** Fresh 16-character card number for the delete test (absent from the seed). */
    private static final String DELETE_CARD_NUM = "9999000033334444";

    /** Synthetic customer id ({@code PIC 9(09)}) for the persist / delete tests. */
    private static final Long NEW_CUST_ID = 999_999_999L;

    /** Synthetic account id ({@code PIC 9(11)}) for the persist test (absent from the seed). */
    private static final Long NEW_ACCT_ID = 9_999_999_999L;

    /** Distinct synthetic account id for the delete test (absent from the seed). */
    private static final Long DELETE_ACCT_ID = 9_999_999_998L;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Used only to force flush/clear round-trips inside the {@link Transactional} mutation tests. */
    @PersistenceContext
    private EntityManager entityManager;

    // -------------------------------------------------------------------------------------
    // Phase 1 — Primary-key (card-number) finder: the base VSAM KSDS read path.
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("findById: a seeded 16-char card number resolves to its linked customer and account")
    void findByIdReturnsSeededCrossReference() {
        Optional<CardXref> found = cardXrefRepository.findById(SEEDED_CARD_NUM);

        assertThat(found)
                .as("first seeded cardxref.txt record must be present via the card-number key")
                .isPresent();
        CardXref xref = found.orElseThrow();
        assertThat(xref.getXrefCardNum()).isEqualTo(SEEDED_CARD_NUM);
        assertThat(xref.getXrefCustId()).isEqualTo(SEEDED_CUST_ID);
        assertThat(xref.getXrefAcctId()).isEqualTo(SEEDED_ACCT_ID);
    }

    @Test
    @DisplayName("findById: a card number not present in the seed returns empty (no record)")
    void findByIdMissingCardReturnsEmpty() {
        assertThat(cardXrefRepository.findById(ABSENT_CARD_NUM))
                .as("an unseeded card number must not resolve to any xref row")
                .isEmpty();
    }

    @Test
    @DisplayName("count/findAll: the seeded card_xref table holds exactly 50 rows")
    void countAndFindAllMatchSeededRowCount() {
        assertThat(cardXrefRepository.count())
                .as("Flyway V3 seeds exactly %d rows from cardxref.txt", EXPECTED_SEED_ROWS)
                .isEqualTo(EXPECTED_SEED_ROWS);
        assertThat(cardXrefRepository.findAll())
                .as("findAll mirrors the sequential CBACT03C read over the whole KSDS")
                .hasSize(EXPECTED_SEED_ROWS);
    }

    // -------------------------------------------------------------------------------------
    // Phase 2 — Account alternate-index finder (KEYS(11,25) NONUNIQUEKEY): AccountViewService path.
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("findByXrefAcctId: a seeded account returns rows that all carry that account id")
    void findByXrefAcctIdReturnsRowsForSeededAccount() {
        List<CardXref> matches = cardXrefRepository.findByXrefAcctId(SEEDED_ACCT_ID);

        assertThat(matches)
                .as("the acct-index lookup AccountViewService performs first must find the account")
                .isNotEmpty();
        assertThat(matches)
                .allMatch(xref -> SEEDED_ACCT_ID.equals(xref.getXrefAcctId()),
                        "every returned row maps to the requested account id");
    }

    @Test
    @DisplayName("findByXrefAcctId: an account absent from the xref returns an empty list, never null")
    void findByXrefAcctIdMissingAccountReturnsEmptyList() {
        // The COBOL "did not find this account in account card xref file" path: an account with
        // no cross-reference must yield an empty result set, not a null and not an exception.
        assertThat(cardXrefRepository.findByXrefAcctId(ABSENT_ACCT_ID))
                .as("a non-referenced account must yield an empty (non-null) list")
                .isNotNull()
                .isEmpty();
    }

    @Test
    @DisplayName("findByXrefAcctId: the 1:1 seed maps each account to a single card (non-unique index, single match)")
    void findByXrefAcctIdSeedIsOneToOne() {
        // XREFFILE.jcl defines the account index as NONUNIQUEKEY, so an account *may* map to many
        // cards and the finder therefore returns a List. The shipped fixture (cardxref.txt) happens
        // to be strictly 1:1 — 50 distinct accounts across 50 rows — so for any seeded account the
        // list holds exactly one element. This documents that the data is 1:1 while the contract
        // (and idx_card_xref_acct_id) remains non-unique.
        List<CardXref> matches = cardXrefRepository.findByXrefAcctId(SEEDED_ACCT_ID);

        assertThat(matches)
                .as("seed data is 1:1, so a seeded account resolves to exactly one xref row")
                .hasSize(1);
        assertThat(matches.get(0).getXrefCardNum()).isEqualTo(SEEDED_CARD_NUM);
        assertThat(matches.get(0).getXrefCustId()).isEqualTo(SEEDED_CUST_ID);
    }

    // -------------------------------------------------------------------------------------
    // Phase 3 — Persistence (insert / delete). Transactional so writes roll back and the
    // shared seeded table stays at EXPECTED_SEED_ROWS for sibling tests.
    // -------------------------------------------------------------------------------------

    @Test
    @Transactional
    @DisplayName("save: a new cross-reference reloads by card number and appears under its account index")
    void saveNewCrossReferenceIsReloadableAndIndexed() {
        cardXrefRepository.save(new CardXref(NEW_CARD_NUM, NEW_CUST_ID, NEW_ACCT_ID));
        // Force the INSERT to the database, then detach so the reads below hit PostgreSQL, not the
        // first-level cache — proving a genuine persisted round-trip.
        entityManager.flush();
        entityManager.clear();

        Optional<CardXref> reloaded = cardXrefRepository.findById(NEW_CARD_NUM);
        assertThat(reloaded)
                .as("the freshly saved xref must be retrievable by its card-number key")
                .isPresent();
        CardXref xref = reloaded.orElseThrow();
        assertThat(xref.getXrefCustId()).isEqualTo(NEW_CUST_ID);
        assertThat(xref.getXrefAcctId()).isEqualTo(NEW_ACCT_ID);

        List<CardXref> byAccount = cardXrefRepository.findByXrefAcctId(NEW_ACCT_ID);
        assertThat(byAccount)
                .as("the new row must now be visible through the account alternate-index path")
                .hasSize(1);
        assertThat(byAccount.get(0).getXrefCardNum()).isEqualTo(NEW_CARD_NUM);
    }

    @Test
    @Transactional
    @DisplayName("delete: a created cross-reference is removed and findById returns empty")
    void deleteCreatedCrossReferenceRemovesIt() {
        CardXref created = cardXrefRepository.save(new CardXref(DELETE_CARD_NUM, NEW_CUST_ID, DELETE_ACCT_ID));
        entityManager.flush();
        assertThat(cardXrefRepository.findById(DELETE_CARD_NUM))
                .as("precondition: the row exists before deletion")
                .isPresent();

        cardXrefRepository.delete(created);
        entityManager.flush();
        entityManager.clear();

        // No @Version on CardXref, so there is no optimistic-lock scenario to assert here — only
        // that the delete is durable and the card-number key no longer resolves.
        assertThat(cardXrefRepository.findById(DELETE_CARD_NUM))
                .as("after deletion the card-number key must no longer resolve to a row")
                .isEmpty();
    }
}
