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

import com.carddemo.entity.Card;
import com.carddemo.repository.CardRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration test for {@link CardRepository} exercised against a <em>real</em>
 * PostgreSQL&nbsp;16 instance (Flyway-seeded) provisioned by Testcontainers via the
 * shared {@link AbstractIntegrationIT} base. The suite uses no H2 and no mock: it
 * exercises VSAM-fidelity behaviour against the live schema and the
 * {@code idx_cards_acct_id} secondary index, with Hibernate running at
 * {@code ddl-auto=validate} and every derived query parsed and executed by the
 * database.
 *
 * <h2>Legacy mapping verified here</h2>
 * The {@code cards} table replaces the VSAM {@code CARDDAT} KSDS and its alternate
 * index {@code CARDAIX}, defined in {@code app/jcl/CARDFILE.jcl} as
 * {@code KEYS(11 16) NONUNIQUEKEY} over {@code CARD-ACCT-ID}. The COBOL access
 * mechanics (programs {@code COCRDLIC} card-list and {@code COCRDUPC} update, copybook
 * {@code app/cpy/CVACT02Y.cpy} at commit {@code 27d6c6f}) map to repository operations
 * as follows, and each is asserted below:
 * <ul>
 *   <li>Keyed {@code READ} by {@code CARD-NUM} &rarr; {@link CardRepository#findById}.</li>
 *   <li>{@code CARDAIX} non-unique browse by {@code CARD-ACCT-ID} &rarr;
 *       {@link CardRepository#findByCardAcctId(Long)} (a {@code List}, because one
 *       account may own several cards).</li>
 *   <li>{@code STARTBR}/{@code READNEXT} paged browse (program {@code COCRDLIC},
 *       {@code WS-MAX-SCREEN-LINES VALUE 7}, PF7/PF8) &rarr;
 *       {@link CardRepository#findByCardAcctId(Long, org.springframework.data.domain.Pageable)}
 *       at the {@code CardListService} page size of {@value #PAGE_SIZE}.</li>
 *   <li>{@code REWRITE} guarded by paragraph {@code 9300-CHECK-CHANGE-IN-REC}
 *       (re-read-and-compare) &rarr; JPA {@code @Version} optimistic locking, surfaced
 *       as {@link ObjectOptimisticLockingFailureException}.</li>
 * </ul>
 *
 * <h2>Data isolation</h2>
 * Flyway&nbsp;V3 seeds exactly {@value #EXPECTED_SEED_COUNT} cards (one per account).
 * Mutating tests therefore either run {@code @Transactional} (Spring rolls the test
 * transaction back) or clean up the synthetic rows they create, so the shared seed
 * stays deterministic for every other {@code *IT} in the suite. Synthetic card numbers
 * use a {@code "999…"} prefix that no seeded card uses, avoiding primary-key
 * collisions; synthetic account identifiers are well above the seeded {@code 1..50}
 * range and are safe because {@code cards.card_acct_id} carries no foreign key.
 */
@DisplayName("CardRepository IT — PostgreSQL 16 + Flyway (CARDDAT KSDS / CARDAIX fidelity)")
class CardRepositoryIT extends AbstractIntegrationIT {

    /** Page size of the legacy card-list browse ({@code COCRDLIC}/{@code CardListService}). */
    private static final int PAGE_SIZE = 7;

    /** Total number of cards seeded by Flyway V3 (from {@code app/data/ASCII/carddata.txt}). */
    private static final long EXPECTED_SEED_COUNT = 50L;

    // --- Authoritative seeded values (Flyway V3) -----------------------------------------

    /** First seeded card; {@code CARD-NUM} primary key. */
    private static final String SEEDED_CARD_NUM = "0500024453765740";
    /** Owning {@code CARD-ACCT-ID} of {@link #SEEDED_CARD_NUM}. */
    private static final long SEEDED_CARD_ACCT_ID = 50L;
    /** {@code CARD-CVV-CD} of {@link #SEEDED_CARD_NUM}. */
    private static final int SEEDED_CARD_CVV = 747;
    /** {@code CARD-EMBOSSED-NAME} of {@link #SEEDED_CARD_NUM}. */
    private static final String SEEDED_CARD_EMBOSSED = "Aniya Von";
    /** {@code CARD-EXPIRAION-DATE} of {@link #SEEDED_CARD_NUM} (legacy spelling preserved). */
    private static final String SEEDED_CARD_EXPIRY = "2023-03-09";
    /** {@code CARD-ACTIVE-STATUS} of {@link #SEEDED_CARD_NUM}. */
    private static final String SEEDED_CARD_STATUS = "Y";

    /** A second seeded card (account {@code 1}) used by the version-increment update test. */
    private static final String SEEDED_CARD_NUM_FOR_UPDATE = "9680294154603697";

    // --- Negative / synthetic test data --------------------------------------------------

    /** A well-formed 16-character card number that is guaranteed not to be seeded. */
    private static final String ABSENT_CARD_NUM = "9999999999999999";
    /** An account identifier guaranteed to own no seeded cards (outside {@code 1..50}). */
    private static final long ABSENT_ACCT_ID = 99_999_999_999L;

    /** Synthetic account used by the true-pagination test; owns {@link #PAGINATION_CARD_COUNT} cards. */
    private static final long PAGINATION_ACCT_ID = 9_000_000_001L;
    /** Number of synthetic cards created for the pagination test (>{@value #PAGE_SIZE}). */
    private static final int PAGINATION_CARD_COUNT = 10;

    /** Synthetic card number persisted by the save-new-card test. */
    private static final String NEW_CARD_NUM = "9991000000000001";
    /** Synthetic owning account for the save-new-card test. */
    private static final long NEW_CARD_ACCT_ID = 9_000_000_002L;

    /** Synthetic card number used by the optimistic-lock test. */
    private static final String OPT_LOCK_CARD_NUM = "9992000000000001";
    /** Synthetic owning account for the optimistic-lock test. */
    private static final long OPT_LOCK_ACCT_ID = 9_000_000_003L;

    /** Repository under test (the only collaborator this IT touches directly). */
    @Autowired
    private CardRepository cardRepository;

    // =====================================================================================
    // Phase 1 — Primary-key finders (CARDDAT keyed READ by CARD-NUM)
    // =====================================================================================

    @Test
    @DisplayName("findById(CARD-NUM): a known seeded 16-char card is present with its mapped fields")
    void findByIdForKnownSeededCardReturnsMappedFields() {
        Optional<Card> found = cardRepository.findById(SEEDED_CARD_NUM);

        assertThat(found)
                .as("seeded card %s must be retrievable by its CARD-NUM primary key", SEEDED_CARD_NUM)
                .isPresent();

        Card card = found.orElseThrow();
        // CHAR(16) is space-padded by PostgreSQL; compare on the trimmed key to stay
        // robust to fixed-width padding while still proving the exact card was returned.
        assertThat(card.getCardNum()).isNotNull();
        assertThat(card.getCardNum().trim()).isEqualTo(SEEDED_CARD_NUM);
        assertThat(card.getCardAcctId()).isEqualTo(SEEDED_CARD_ACCT_ID);
        assertThat(card.getCvvCd()).isEqualTo(SEEDED_CARD_CVV);
        assertThat(card.getEmbossedName()).isEqualTo(SEEDED_CARD_EMBOSSED);
        assertThat(card.getExpirationDate()).isEqualTo(SEEDED_CARD_EXPIRY);
        assertThat(card.getActiveStatus()).isNotNull();
        assertThat(card.getActiveStatus().trim()).isEqualTo(SEEDED_CARD_STATUS);
        // Seeded rows default version to 0 (column DEFAULT 0); @Version is never null.
        assertThat(card.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("findById(CARD-NUM): a non-existent 16-char card number yields an empty Optional")
    void findByIdForNonExistentCardReturnsEmpty() {
        Optional<Card> found = cardRepository.findById(ABSENT_CARD_NUM);

        assertThat(found)
                .as("card number %s is not seeded and must not be found", ABSENT_CARD_NUM)
                .isEmpty();
    }

    @Test
    @DisplayName("count()/findAll(): the seeded card population is exactly 50 rows")
    void seededCardPopulationIsFifty() {
        assertThat(cardRepository.count())
                .as("Flyway V3 seeds %d cards from carddata.txt", EXPECTED_SEED_COUNT)
                .isEqualTo(EXPECTED_SEED_COUNT);

        List<Card> all = cardRepository.findAll();
        assertThat(all).hasSize((int) EXPECTED_SEED_COUNT);
        // Cross-check against the JDBC row count exposed by the base class.
        assertThat(countRows("cards")).isEqualTo(EXPECTED_SEED_COUNT);
    }

    // =====================================================================================
    // Phase 2 — CARDAIX account->cards finders (alternate-index replacement)
    // =====================================================================================

    @Test
    @DisplayName("findByCardAcctId(Long): an account that owns a card returns a non-empty, matching List")
    void findByCardAcctIdForOwningAccountReturnsMatchingCards() {
        List<Card> cards = cardRepository.findByCardAcctId(SEEDED_CARD_ACCT_ID);

        assertThat(cards)
                .as("CARDAIX browse for account %d must return its card(s)", SEEDED_CARD_ACCT_ID)
                .isNotEmpty();
        // Every row the alternate index returns must belong to the requested account.
        assertThat(cards).allMatch(card -> SEEDED_CARD_ACCT_ID == card.getCardAcctId());
        // The known seeded card for this account must be among the results.
        assertThat(cards).anyMatch(card -> SEEDED_CARD_NUM.equals(card.getCardNum().trim()));
    }

    @Test
    @DisplayName("findByCardAcctId(Long): an account that owns no cards returns an empty List (never null)")
    void findByCardAcctIdForAccountWithoutCardsReturnsEmptyList() {
        List<Card> cards = cardRepository.findByCardAcctId(ABSENT_ACCT_ID);

        assertThat(cards)
                .as("an unknown account must yield an empty list, mirroring an exhausted CARDAIX browse")
                .isNotNull()
                .isEmpty();
    }

    @Test
    @DisplayName("findByCardAcctId(Long, Pageable): a single-card account yields one full-size page")
    void findByCardAcctIdPagedForSeededAccountIsSinglePage() {
        // Each seeded account owns exactly one card, so a size-7 page is a single page.
        Page<Card> page = cardRepository.findByCardAcctId(
                SEEDED_CARD_ACCT_ID, PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum")));

        assertThat(page.getSize())
                .as("the requested page size must equal the COCRDLIC screen size")
                .isEqualTo(PAGE_SIZE);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getNumberOfElements()).isEqualTo(1);
        assertThat(page.getTotalElements()).isEqualTo(1L);
        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(page.isFirst()).isTrue();
        assertThat(page.isLast()).isTrue();
        assertThat(page.getContent())
                .allMatch(card -> SEEDED_CARD_ACCT_ID == card.getCardAcctId());
    }

    @Test
    @Transactional
    @DisplayName("findByCardAcctId(Long, Pageable): >7 cards for one account paginate at size 7 (PF7/PF8)")
    void findByCardAcctIdPagedBeyondPageSizePaginatesAtSeven() {
        // Build PAGINATION_CARD_COUNT (> PAGE_SIZE) synthetic cards for a single account so a
        // size-7 browse must span two pages, exercising the COCRDLIC PF8 page-forward path.
        // This test is @Transactional, so the synthetic rows are rolled back afterwards.
        List<Card> synthetic = new ArrayList<>();
        for (int i = 0; i < PAGINATION_CARD_COUNT; i++) {
            String cardNum = String.format("99900000000000%02d", i);
            synthetic.add(new Card(cardNum, PAGINATION_ACCT_ID, 100 + i,
                    "Pagination IT " + i, "2030-01-01", "Y", null));
        }
        cardRepository.saveAllAndFlush(synthetic);

        Sort byCardNum = Sort.by("cardNum");

        Page<Card> firstPage = cardRepository.findByCardAcctId(
                PAGINATION_ACCT_ID, PageRequest.of(0, PAGE_SIZE, byCardNum));
        assertThat(firstPage.getTotalElements()).isEqualTo(PAGINATION_CARD_COUNT);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getSize()).isEqualTo(PAGE_SIZE);
        assertThat(firstPage.getContent()).hasSize(PAGE_SIZE);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.isLast()).isFalse();
        assertThat(firstPage.getContent()).allMatch(card -> PAGINATION_ACCT_ID == card.getCardAcctId());
        // Sorted by CARD-NUM, the first page must start at the lowest synthetic key.
        assertThat(firstPage.getContent().get(0).getCardNum().trim()).isEqualTo("9990000000000000");

        Page<Card> secondPage = cardRepository.findByCardAcctId(
                PAGINATION_ACCT_ID, PageRequest.of(1, PAGE_SIZE, byCardNum));
        assertThat(secondPage.getContent()).hasSize(PAGINATION_CARD_COUNT - PAGE_SIZE);
        assertThat(secondPage.isFirst()).isFalse();
        assertThat(secondPage.isLast()).isTrue();
        assertThat(secondPage.getContent()).allMatch(card -> PAGINATION_ACCT_ID == card.getCardAcctId());

        // The two pages partition the result set (Card equality is by CARD-NUM).
        assertThat(firstPage.getContent()).doesNotContainAnyElementsOf(secondPage.getContent());
    }

    // =====================================================================================
    // Phase 3 — Persist / update + optimistic locking (CARDDAT WRITE/REWRITE + 9300 guard)
    // =====================================================================================

    @Test
    @Transactional
    @DisplayName("save(new Card): a fresh card is persisted, reloadable, and starts at version 0")
    void saveNewCardPersistsAndIsReloadable() {
        Card fresh = new Card(NEW_CARD_NUM, NEW_CARD_ACCT_ID, 321,
                "New Card IT", "2029-12-31", "Y", null);

        Card saved = cardRepository.saveAndFlush(fresh);
        assertThat(saved.getVersion())
                .as("a newly inserted @Version row initialises to 0")
                .isEqualTo(0L);

        Optional<Card> reloaded = cardRepository.findById(NEW_CARD_NUM);
        assertThat(reloaded)
                .as("the freshly written card must be retrievable by its new CARD-NUM")
                .isPresent();
        Card card = reloaded.orElseThrow();
        assertThat(card.getCardAcctId()).isEqualTo(NEW_CARD_ACCT_ID);
        assertThat(card.getCvvCd()).isEqualTo(321);
        assertThat(card.getEmbossedName()).isEqualTo("New Card IT");
        assertThat(card.getExpirationDate()).isEqualTo("2029-12-31");
    }

    @Test
    @Transactional
    @DisplayName("save(existing Card): updating a field advances the optimistic-lock @Version by one")
    void saveExistingCardIncrementsVersion() {
        Card card = cardRepository.findById(SEEDED_CARD_NUM_FOR_UPDATE).orElseThrow();
        Long originalVersion = card.getVersion();
        assertThat(originalVersion).as("seeded @Version must be populated").isNotNull();

        card.setEmbossedName("Updated Embossed Name IT");
        Card saved = cardRepository.saveAndFlush(card);

        assertThat(saved.getVersion())
                .as("a REWRITE/save must advance @Version (cf. COCRDUPC 9300-CHECK-CHANGE-IN-REC)")
                .isEqualTo(originalVersion + 1);
        assertThat(saved.getEmbossedName()).isEqualTo("Updated Embossed Name IT");
    }

    @Test
    @DisplayName("save(stale Card): a concurrent update makes the stale save throw the optimistic-lock failure")
    void staleSaveThrowsOptimisticLockingFailure() {
        // Seed a synthetic card to mutate concurrently. This test is NOT @Transactional:
        // each findById runs in its own transaction so the two reads return two distinct
        // detached instances, modelling the cross-user conflict. A single shared persistence
        // context would return one managed instance for both reads.
        cardRepository.saveAndFlush(new Card(OPT_LOCK_CARD_NUM, OPT_LOCK_ACCT_ID, 222,
                "Opt Lock IT", "2031-06-30", "Y", null));
        try {
            Card readA = cardRepository.findById(OPT_LOCK_CARD_NUM).orElseThrow();
            Card readB = cardRepository.findById(OPT_LOCK_CARD_NUM).orElseThrow();

            // First writer wins: A commits and the row's @Version advances.
            readA.setEmbossedName("Winner A");
            cardRepository.saveAndFlush(readA);

            // Second writer still holds the pre-update @Version and must be rejected,
            // reproducing the COBOL "Record changed by some one else" guard.
            readB.setEmbossedName("Stale B");
            assertThatExceptionOfType(ObjectOptimisticLockingFailureException.class)
                    .as("a stale REWRITE must fail the @Version optimistic-lock check")
                    .isThrownBy(() -> cardRepository.saveAndFlush(readB));
        } finally {
            // Explicit cleanup (this test is not transactional) keeps the seed deterministic.
            cardRepository.deleteById(OPT_LOCK_CARD_NUM);
        }
    }
}
