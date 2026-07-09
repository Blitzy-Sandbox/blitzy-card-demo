package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Spring Data JPA slice test for {@link CardRepository}, executed against a
 * <strong>real PostgreSQL&nbsp;16</strong> instance provisioned by Testcontainers
 * through {@link AbstractRepositoryTest} (never an embedded/H2 substitute and
 * never a mock).
 *
 * <p>This test is the migrated, executable proof of the legacy {@code CARDDATA}
 * access paths defined by copybook {@code app/cpy/CVACT02Y.cpy}
 * ({@code CARD-RECORD}, 150-byte record) captured at source commit SHA
 * {@code 27d6c6f}. It verifies three behaviours that the COBOL online card
 * transactions relied on:</p>
 *
 * <ol>
 *   <li><b>Alternate-index paging by account id.</b> The VSAM alternate index
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} ({@code NONUNIQUEKEY} on
 *       {@code CARD-ACCT-ID}) is migrated to the derived query
 *       {@link CardRepository#findByCardAcctId(Long, Pageable)}. The Card List
 *       transaction (CCLI / {@code COCRDLIC}) renders exactly <em>seven</em> rows
 *       per screen ({@code WS-MAX-SCREEN-LINES VALUE 7}); this test drives the
 *       repository with {@code PageRequest.of(page, 7, Sort.by("cardNum"))} and
 *       asserts the seven-row window is honoured (AAP&nbsp;&sect;0.5.1).</li>
 *   <li><b>Round-trip persistence.</b> A {@code save} followed by
 *       {@code findById} returns every mapped field unchanged, proving the
 *       {@code CVACT02Y} field-by-field mapping and the
 *       {@code ddl-auto=validate} schema contract hold end-to-end.</li>
 *   <li><b>Optimistic locking.</b> The Card Update transaction (CCUP /
 *       {@code COCRDUPC}) performs a read-then-rewrite; the {@code @Version}
 *       column on {@link Card} reproduces the legacy "record changed, please
 *       retry" semantics, surfacing a concurrent modification as an
 *       {@link ObjectOptimisticLockingFailureException} (409-style conflict)
 *       (AAP&nbsp;&sect;0.8.4).</li>
 * </ol>
 *
 * <h2>Foreign-key ordering (critical)</h2>
 * <p>{@code V1__schema.sql} declares
 * {@code CONSTRAINT fk_card_account FOREIGN KEY (card_acct_id) REFERENCES
 * account (acct_id)}. Every persisted {@link Card} must therefore reference an
 * already-committed {@link Account}. Each test that inserts cards first inserts
 * its parent account (via {@link #accountRepository}) and flushes it, then
 * inserts the cards. Skipping this order raises a
 * {@code DataIntegrityViolationException}.</p>
 *
 * <h2>Isolation and seed data</h2>
 * <p>{@link AbstractRepositoryTest} wraps each test in a transaction that is
 * rolled back on completion, and the committed Flyway seed ({@code V3}) is
 * shared and read-only from a test's perspective. To avoid polluting counts
 * with seed rows (the seed gives each real account exactly one card), this test
 * uses <em>test-owned</em> account ids ({@code >= 900000000}) and 16-character
 * card numbers beginning with {@code 9000...} that do not exist in the seed.
 * Consequently every assertion is scoped to a test-owned {@code cardAcctId};
 * global counts are never asserted.</p>
 */
class CardRepositoryTest extends AbstractRepositoryTest {

    /**
     * The account id that owns the ten cards inserted by the pagination test.
     * Test-owned (&ge; {@code 900000000}) so it never collides with the Flyway
     * seed, whose accounts use small sequential ids.
     */
    private static final long PAGINATION_ACCT_ID = 900_000_100L;

    /**
     * Page size reproducing the CCLI / {@code COCRDLIC} card-list screen, which
     * shows seven rows at a time ({@code WS-MAX-SCREEN-LINES VALUE 7}).
     */
    private static final int CCLI_PAGE_SIZE = 7;

    /** Number of cards inserted for the pagination test (spans two pages of 7). */
    private static final int PAGINATION_CARD_COUNT = 10;

    /** An account id that owns no cards, used to prove an empty page is returned. */
    private static final long UNOWNED_ACCT_ID = 999_999_999L;

    /** Repository under test — the migrated {@code CARDDATA} access port. */
    @Autowired
    private CardRepository cardRepository;

    /**
     * Used to insert the parent {@link Account} required by the
     * {@code fk_card_account} foreign key before any {@link Card} is persisted.
     */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * JPA test helper for low-level persistence-context control (persist, flush,
     * clear, detach) that the optimistic-locking scenarios depend on.
     */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Verifies the account-id alternate-index query pages results seven rows at
     * a time, reproducing the CCLI / {@code COCRDLIC} screen window.
     *
     * <p>Ten cards are created under a single test-owned account. Page&nbsp;0
     * must contain the first seven rows (and report a total of ten across two
     * pages); page&nbsp;1 must contain the remaining three. Every returned card
     * must belong to the queried account, and an unrelated account id must yield
     * an empty page.</p>
     */
    @Test
    void findByCardAcctId_pagesSevenRowsPerPage() {
        // FK ordering: the parent account must exist and be flushed first.
        accountRepository.saveAndFlush(newAccount(PAGINATION_ACCT_ID));

        // Ten cards, all owned by PAGINATION_ACCT_ID, with distinct 16-char keys.
        List<Card> cards = new ArrayList<>();
        for (int i = 1; i <= PAGINATION_CARD_COUNT; i++) {
            cards.add(newCard(String.format("90000000%08d", i), PAGINATION_ACCT_ID));
        }
        cardRepository.saveAllAndFlush(cards);
        entityManager.clear();

        // Page 0 — the first seven-row screen window.
        Pageable firstPage = PageRequest.of(0, CCLI_PAGE_SIZE, Sort.by("cardNum"));
        Page<Card> p0 = cardRepository.findByCardAcctId(PAGINATION_ACCT_ID, firstPage);
        assertThat(p0.getContent()).hasSize(CCLI_PAGE_SIZE);
        assertThat(p0.getTotalElements()).isEqualTo((long) PAGINATION_CARD_COUNT);
        assertThat(p0.getTotalPages()).isEqualTo(2);
        assertThat(p0.isFirst()).isTrue();
        assertThat(p0.getContent())
                .allSatisfy(card -> assertThat(card.getCardAcctId()).isEqualTo(PAGINATION_ACCT_ID));

        // Page 1 — the remaining three cards; this is the last page.
        Pageable secondPage = PageRequest.of(1, CCLI_PAGE_SIZE, Sort.by("cardNum"));
        Page<Card> p1 = cardRepository.findByCardAcctId(PAGINATION_ACCT_ID, secondPage);
        assertThat(p1.getContent()).hasSize(PAGINATION_CARD_COUNT - CCLI_PAGE_SIZE);
        assertThat(p1.isLast()).isTrue();
        assertThat(p1.getContent())
                .allSatisfy(card -> assertThat(card.getCardAcctId()).isEqualTo(PAGINATION_ACCT_ID));

        // Query correctness: an account that owns no cards returns an empty page.
        Page<Card> emptyPage = cardRepository.findByCardAcctId(UNOWNED_ACCT_ID, firstPage);
        assertThat(emptyPage.getContent()).isEmpty();
        assertThat(emptyPage.getTotalElements()).isZero();
    }

    /**
     * Verifies a {@code save}/{@code findById} round-trip preserves every mapped
     * field, proving the {@code CVACT02Y} mapping and the Flyway schema contract.
     */
    @Test
    void saveAndFindById_roundTrips() {
        accountRepository.saveAndFlush(newAccount(900_000_101L));

        String cardNum = "9000000000009999";
        Card toSave = newCard(cardNum, 900_000_101L);
        toSave.setCardEmbossedName("ROUND TRIP HOLDER");
        toSave.setCardCvvCd(123);
        toSave.setCardExpirationDate(LocalDate.of(2028, 12, 31));
        toSave.setCardActiveStatus("Y");

        cardRepository.saveAndFlush(toSave);
        entityManager.clear();

        Card found = cardRepository.findById(cardNum).orElseThrow();
        assertThat(found.getCardNum()).isEqualTo(cardNum);
        assertThat(found.getCardAcctId()).isEqualTo(900_000_101L);
        assertThat(found.getCardCvvCd()).isEqualTo(123);
        assertThat(found.getCardEmbossedName()).isEqualTo("ROUND TRIP HOLDER");
        assertThat(found.getCardExpirationDate()).isEqualTo(LocalDate.of(2028, 12, 31));
        assertThat(found.getCardActiveStatus()).isEqualTo("Y");
        // A freshly inserted row starts at optimistic-lock version 0.
        assertThat(found.getVersion()).isEqualTo(0L);
    }

    /**
     * Optimistic-locking (positive path): the {@code @Version} counter starts at
     * {@code 0} on insert and increments to {@code 1} after a normal update.
     */
    @Test
    void save_incrementsVersion() {
        accountRepository.saveAndFlush(newAccount(900_000_102L));

        Card persisted = entityManager.persistFlushFind(newCard("9000000000008888", 900_000_102L));
        assertThat(persisted.getVersion()).isEqualTo(0L);

        persisted.setCardActiveStatus("N");
        Card saved = cardRepository.saveAndFlush(persisted);
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    /**
     * Optimistic-locking (conflict path): a stale copy that is rewritten after a
     * concurrent update must fail with
     * {@link ObjectOptimisticLockingFailureException}, mirroring the COCRDUPC /
     * CCUP "record changed" outcome (mapped to a 409-style conflict).
     *
     * <p>The two-copy detach pattern loads the same row twice: {@code stale} is
     * detached at version&nbsp;0, {@code fresh} is updated (bumping the row to
     * version&nbsp;1), and the subsequent rewrite of {@code stale} then detects
     * the version mismatch.</p>
     */
    @Test
    void staleUpdate_throwsOptimisticLockException() {
        accountRepository.saveAndFlush(newAccount(900_000_103L));

        String num = "9000000000007777";
        entityManager.persistAndFlush(newCard(num, 900_000_103L));
        entityManager.clear();

        // First copy — detached while still at version 0.
        Card stale = cardRepository.findById(num).orElseThrow();
        entityManager.detach(stale);

        // Second copy — updated and flushed, advancing the row to version 1.
        Card fresh = cardRepository.findById(num).orElseThrow();
        fresh.setCardEmbossedName("UPDATED NAME");
        cardRepository.saveAndFlush(fresh);
        entityManager.flush();
        entityManager.clear();

        // Rewriting the stale (version 0) copy must be rejected.
        stale.setCardEmbossedName("STALE NAME");
        assertThatThrownBy(() -> cardRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /**
     * Builds a minimal but valid parent {@link Account} for the
     * {@code fk_card_account} foreign key. Only {@code acctId} is
     * schema-mandatory ({@code NOT NULL}); an active-status flag is set for
     * realism. The {@code version} attribute is intentionally left {@code null}
     * so Spring Data treats the entity as new and issues an {@code INSERT}.
     *
     * @param acctId the test-owned account id to assign (never {@code null})
     * @return a transient {@link Account} ready to persist
     */
    private Account newAccount(Long acctId) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctActiveStatus("Y");
        return account;
    }

    /**
     * Builds a fully-populated, transient {@link Card} owned by the given
     * account. The {@code version} attribute is left {@code null} so the entity
     * is treated as new (persist path) rather than merged.
     *
     * @param cardNum the 16-character natural key (primary key); never
     *                {@code null}
     * @param acctId  the owning account id, matching an existing
     *                {@code account.acct_id} (FK); never {@code null}
     * @return a transient {@link Card} ready to persist
     */
    private Card newCard(String cardNum, Long acctId) {
        Card card = new Card();
        card.setCardNum(cardNum);
        card.setCardAcctId(acctId);
        card.setCardCvvCd(123);
        card.setCardEmbossedName("CARDHOLDER " + cardNum);
        card.setCardExpirationDate(LocalDate.of(2028, 12, 31));
        card.setCardActiveStatus("Y");
        return card;
    }
}
