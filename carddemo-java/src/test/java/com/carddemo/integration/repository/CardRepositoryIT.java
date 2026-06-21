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
 * (findByCardAcctId returning a List and a Page), the COCRDLIC 7-rows/page browse math, and JPA
 * {@code @Version} optimistic locking (the COBOL COCRDUPC before/after-image concurrency guard).
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

    @Test
    void concurrentUpdateRaisesOptimisticLockingFailure() {
        String cardNum = cardRepository.findAll().get(0).getCardNum();
        entityManager.clear();

        // Load a stale snapshot, then detach it so its (now older) @Version is preserved.
        Card stale = cardRepository.findById(cardNum).orElseThrow();
        entityManager.detach(stale);

        // A concurrent writer mutates and commits the fresh copy first, bumping the row version.
        Card fresh = cardRepository.findById(cardNum).orElseThrow();
        fresh.setCardEmbossedName("FRESH EMBOSSED NAME");
        cardRepository.saveAndFlush(fresh);
        entityManager.detach(fresh);

        // Saving the stale copy must fail the @Version check (COCRDUPC before/after-image guard).
        stale.setCardEmbossedName("STALE EMBOSSED NAME");

        assertThrows(
                OptimisticLockingFailureException.class,
                () -> cardRepository.saveAndFlush(stale));
    }
}
