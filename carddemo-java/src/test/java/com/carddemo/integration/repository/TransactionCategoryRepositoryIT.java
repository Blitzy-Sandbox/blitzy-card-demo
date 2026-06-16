package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.key.TransactionCategoryId;
import com.carddemo.repository.TransactionCategoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link TransactionCategoryRepository} against a real PostgreSQL 16
 * Testcontainer. Verifies the VSAM TRANCATG reference lookup re-platforms to PostgreSQL with a
 * 2-part composite key (type code + category code) and the full seeded reference set intact.
 */
class TransactionCategoryRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    @Test
    void seedDataLoadsAllTransactionCategories() {
        assertThat(transactionCategoryRepository.count()).isEqualTo(18L);
        assertThat(transactionCategoryRepository.findAll()).hasSize(18);
    }

    @Test
    void findByCompositeKeyReturnsSeededCategory() {
        List<TransactionCategory> all = transactionCategoryRepository.findAll();
        assertThat(all).isNotEmpty();
        TransactionCategory sample = all.get(0);
        TransactionCategoryId key =
                new TransactionCategoryId(sample.getId().getTypeCode(), sample.getId().getCategoryCode());

        Optional<TransactionCategory> found = transactionCategoryRepository.findById(key);

        assertThat(found).isPresent();
        assertThat(found.get().getId().getTypeCode()).isEqualTo(sample.getId().getTypeCode());
        assertThat(found.get().getId().getCategoryCode()).isEqualTo(sample.getId().getCategoryCode());
        assertThat(found.get().getTranCatTypeDesc()).isNotNull();
    }

    @Test
    void findByUnknownCompositeKeyReturnsEmpty() {
        assertThat(transactionCategoryRepository.findById(new TransactionCategoryId("ZZ", 9999))).isEmpty();
    }

    @Test
    void everyCategoryHasPopulatedTwoPartKey() {
        for (TransactionCategory category : transactionCategoryRepository.findAll()) {
            assertThat(category.getId()).isNotNull();
            assertThat(category.getId().getTypeCode()).isNotNull();
            assertThat(category.getId().getCategoryCode()).isNotNull();
        }
    }
}
