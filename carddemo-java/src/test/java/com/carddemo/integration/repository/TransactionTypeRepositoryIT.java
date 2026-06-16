package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.TransactionType;
import com.carddemo.repository.TransactionTypeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link TransactionTypeRepository} against a real PostgreSQL 16
 * Testcontainer. Verifies the VSAM TRANTYPE reference lookup re-platforms to PostgreSQL with
 * keyed reads and the full seeded reference set intact.
 */
class TransactionTypeRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    @Test
    void seedDataLoadsAllTransactionTypes() {
        assertThat(transactionTypeRepository.count()).isEqualTo(7L);
        assertThat(transactionTypeRepository.findAll()).hasSize(7);
    }

    @Test
    void findByIdReturnsSeededTransactionType() {
        List<TransactionType> all = transactionTypeRepository.findAll();
        assertThat(all).isNotEmpty();
        TransactionType sample = all.get(0);
        String typeCode = sample.getTranType();

        Optional<TransactionType> found = transactionTypeRepository.findById(typeCode);

        assertThat(found).isPresent();
        assertThat(found.get().getTranType()).isEqualTo(typeCode);
        assertThat(found.get().getTranTypeDesc()).isNotNull();
    }

    @Test
    void findByIdReturnsEmptyForUnknownType() {
        assertThat(transactionTypeRepository.findById("ZZ")).isEmpty();
    }

    @Test
    void everyTransactionTypeHasCodeAndDescription() {
        for (TransactionType type : transactionTypeRepository.findAll()) {
            assertThat(type.getTranType()).isNotNull();
            assertThat(type.getTranType().trim()).isNotEmpty();
            assertThat(type.getTranTypeDesc()).isNotNull();
        }
    }
}
