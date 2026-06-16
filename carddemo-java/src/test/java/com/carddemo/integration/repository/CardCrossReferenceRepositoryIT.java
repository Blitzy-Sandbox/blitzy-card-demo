package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.repository.CardCrossReferenceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link CardCrossReferenceRepository} against a real PostgreSQL 16
 * Testcontainer. Verifies keyed reads on the 16-digit card number and the CXACAIX non-unique
 * alternate index (findByXrefAcctId returning a List), preserving the card-account-customer linkage.
 */
class CardCrossReferenceRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Test
    void seedDataLoadsAllCrossReferences() {
        assertThat(cardCrossReferenceRepository.count()).isEqualTo(50L);
        assertThat(cardCrossReferenceRepository.findAll()).hasSize(50);
    }

    @Test
    void findByIdReturnsSeededCrossReference() {
        List<CardCrossReference> all = cardCrossReferenceRepository.findAll();
        assertThat(all).isNotEmpty();
        CardCrossReference sample = all.get(0);
        String cardNum = sample.getXrefCardNum();

        Optional<CardCrossReference> found = cardCrossReferenceRepository.findById(cardNum);

        assertThat(found).isPresent();
        assertThat(found.get().getXrefCardNum()).isEqualTo(cardNum);
        assertThat(found.get().getXrefAcctId()).isNotNull();
        assertThat(found.get().getXrefCustId()).isNotNull();
    }

    @Test
    void findByXrefAcctIdReturnsMatchingReferences() {
        List<CardCrossReference> all = cardCrossReferenceRepository.findAll();
        Long acctId = all.get(0).getXrefAcctId();
        long expected = all.stream().filter(x -> acctId.equals(x.getXrefAcctId())).count();

        List<CardCrossReference> byAccount = cardCrossReferenceRepository.findByXrefAcctId(acctId);

        assertThat(byAccount).hasSize((int) expected);
        assertThat(byAccount).allMatch(x -> acctId.equals(x.getXrefAcctId()));
    }

    @Test
    void findByUnknownAccountReturnsEmptyList() {
        long absentAcctId = cardCrossReferenceRepository.findAll().stream()
                .mapToLong(CardCrossReference::getXrefAcctId)
                .max()
                .orElse(0L) + 1L;

        assertThat(cardCrossReferenceRepository.findByXrefAcctId(absentAcctId)).isEmpty();
    }

    @Test
    void everyCrossReferenceLinksCardAccountAndCustomer() {
        for (CardCrossReference xref : cardCrossReferenceRepository.findAll()) {
            assertThat(xref.getXrefCardNum()).isNotNull();
            assertThat(xref.getXrefAcctId()).isNotNull();
            assertThat(xref.getXrefCustId()).isNotNull();
        }
    }
}
