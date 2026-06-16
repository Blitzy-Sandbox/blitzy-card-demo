package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link TransactionCategoryBalanceRepository} against a real PostgreSQL 16
 * Testcontainer. Verifies the VSAM TCATBAL store re-platforms to PostgreSQL with a 3-part composite
 * key (account id + type code + category code), keyed reads, a save round-trip, and scale-2 balances.
 */
class TransactionCategoryBalanceRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Test
    void seedDataLoadsAllBalances() {
        assertThat(transactionCategoryBalanceRepository.count()).isEqualTo(50L);
        assertThat(transactionCategoryBalanceRepository.findAll()).hasSize(50);
    }

    @Test
    void findByCompositeKeyReturnsSeededBalance() {
        List<TransactionCategoryBalance> all = transactionCategoryBalanceRepository.findAll();
        assertThat(all).isNotEmpty();
        TransactionCategoryBalanceId sampleKey = all.get(0).getId();
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                sampleKey.getAccountId(), sampleKey.getTypeCode(), sampleKey.getCategoryCode());

        Optional<TransactionCategoryBalance> found = transactionCategoryBalanceRepository.findById(key);

        assertThat(found).isPresent();
        assertThat(found.get().getId().getAccountId()).isEqualTo(sampleKey.getAccountId());
        assertThat(found.get().getId().getTypeCode()).isEqualTo(sampleKey.getTypeCode());
        assertThat(found.get().getId().getCategoryCode()).isEqualTo(sampleKey.getCategoryCode());
        assertThat(found.get().getTranCatBal()).isNotNull();
        assertThat(found.get().getTranCatBal().scale()).isEqualTo(2);
    }

    @Test
    void findByUnknownCompositeKeyReturnsEmpty() {
        TransactionCategoryBalanceId absent =
                new TransactionCategoryBalanceId(99_999_999_999L, "99", 9999);

        assertThat(transactionCategoryBalanceRepository.findById(absent)).isEmpty();
    }

    @Test
    void saveNewBalanceRoundTrips() {
        TransactionCategoryBalanceId key =
                new TransactionCategoryBalanceId(99_999_999_999L, "99", 9999);
        TransactionCategoryBalance balance = new TransactionCategoryBalance();
        balance.setId(key);
        balance.setTranCatBal(new BigDecimal("123.45"));

        transactionCategoryBalanceRepository.saveAndFlush(balance);

        Optional<TransactionCategoryBalance> found = transactionCategoryBalanceRepository.findById(key);
        assertThat(found).isPresent();
        assertThat(found.get().getTranCatBal()).isEqualByComparingTo(new BigDecimal("123.45"));
    }

    @Test
    void everyBalanceHasPopulatedThreePartKey() {
        for (TransactionCategoryBalance balance : transactionCategoryBalanceRepository.findAll()) {
            assertThat(balance.getId()).isNotNull();
            assertThat(balance.getId().getAccountId()).isNotNull();
            assertThat(balance.getId().getTypeCode()).isNotNull();
            assertThat(balance.getId().getCategoryCode()).isNotNull();
        }
    }
}
