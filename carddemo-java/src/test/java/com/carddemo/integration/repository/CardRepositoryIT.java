package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Integration tests for {@link CardRepository} against a real PostgreSQL 16 Testcontainer.
 * Verifies keyed reads on the 16-digit card number, the CARDAIX non-unique alternate index
 * (findByCardAcctId returning a List and a Page), the COCRDLIC 7-rows/page browse math, and the
 * {@code @Version} optimistic-lock behavior that re-platforms the COCRDUPC ({@code CardUpdateService})
 * before/after record-image concurrency check to JPA optimistic locking at the database level.
 */
class CardRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void seedDataLoadsAllCards() {
        assertThat(cardRepository.count()).isEqualTo(50L);
        assertThat(cardRepository.findAll()).hasSize(50);
    }

    @Test
    void findByIdReturnsSeededCard() {
        List<Card> all = cardRepository.findAll();
        assertThat(all).isNotEmpty();
        Card sample = all.get(0);
        String cardNum = sample.getCardNum();

        Optional<Card> found = cardRepository.findById(cardNum);

        assertThat(found).isPresent();
        assertThat(found.get().getCardNum()).isEqualTo(cardNum);
        assertThat(found.get().getCardAcctId()).isNotNull();
    }

    @Test
    void findByCardAcctIdReturnsAllCardsForAccount() {
        List<Card> all = cardRepository.findAll();
        Long acctId = all.get(0).getCardAcctId();
        long expected = all.stream().filter(c -> acctId.equals(c.getCardAcctId())).count();

        List<Card> byAccount = cardRepository.findByCardAcctId(acctId);

        assertThat(byAccount).hasSize((int) expected);
        assertThat(byAccount).allMatch(c -> acctId.equals(c.getCardAcctId()));
    }

    @Test
    void findByCardAcctIdPagedMatchesListVariant() {
        List<Card> all = cardRepository.findAll();
        Long acctId = all.get(0).getCardAcctId();
        long expected = all.stream().filter(c -> acctId.equals(c.getCardAcctId())).count();

        Page<Card> page = cardRepository.findByCardAcctId(acctId, PageRequest.of(0, 7));

        assertThat(page.getTotalElements()).isEqualTo(expected);
        assertThat(page.getContent()).allMatch(c -> acctId.equals(c.getCardAcctId()));
    }

    @Test
    void findAllBrowsesSevenRowsPerPage() {
        Page<Card> firstPage = cardRepository.findAll(PageRequest.of(0, 7));

        assertThat(firstPage.getTotalElements()).isEqualTo(50L);
        assertThat(firstPage.getTotalPages()).isEqualTo(8);
        assertThat(firstPage.getContent()).hasSize(7);
    }

    @Test
    void findByUnknownAccountReturnsEmptyList() {
        long absentAcctId = cardRepository.findAll().stream()
                .mapToLong(Card::getCardAcctId)
                .max()
                .orElse(0L) + 1L;

        assertThat(cardRepository.findByCardAcctId(absentAcctId)).isEmpty();
    }

    /**
     * Database-level optimistic-lock evidence for {@code Card.version} (COCRDUPC parity). Two
     * persistence contexts load the same card; the first update succeeds and bumps the version
     * column, so the second (stale) update must fail with an optimistic-locking exception rather
     * than silently overwrite the committed change &mdash; the JPA equivalent of the COCRDUPC
     * before/after record-image comparison. Mirrors
     * {@code AccountRepositoryIT.concurrentUpdateRaisesOptimisticLockingFailure}.
     */
    @Test
    void concurrentUpdateRaisesOptimisticLockingFailure() {
        String cardNum = cardRepository.findAll().get(0).getCardNum();
        entityManager.clear();

        // First context: load, detach, and keep a stale copy holding the original version token.
        Card stale = cardRepository.findById(cardNum).orElseThrow();
        entityManager.detach(stale);

        // Second context: load the same card, apply and flush an update (version increments in DB).
        Card fresh = cardRepository.findById(cardNum).orElseThrow();
        int freshBase = (fresh.getCardCvvCd() == null) ? 0 : fresh.getCardCvvCd();
        fresh.setCardCvvCd((freshBase + 1) % 1000);
        cardRepository.saveAndFlush(fresh);
        entityManager.detach(fresh);

        // The stale copy still carries the pre-update version, so persisting it must conflict.
        int staleBase = (stale.getCardCvvCd() == null) ? 0 : stale.getCardCvvCd();
        stale.setCardCvvCd((staleBase + 2) % 1000);

        assertThrows(
                OptimisticLockingFailureException.class,
                () -> cardRepository.saveAndFlush(stale));
    }
}
