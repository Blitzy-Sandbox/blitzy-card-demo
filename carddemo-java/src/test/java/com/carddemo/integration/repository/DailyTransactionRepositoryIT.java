package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.DailyTransaction;
import com.carddemo.repository.DailyTransactionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Integration tests for {@link DailyTransactionRepository} against a real PostgreSQL 16
 * Testcontainer. Verifies the daily-transaction staging store re-platforms to PostgreSQL with
 * keyed reads and the save / saveAll / findAll operations used by the POSTTRAN posting pipeline.
 */
class DailyTransactionRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void seedDataLoadsAllDailyTransactions() {
        assertThat(dailyTransactionRepository.count()).isEqualTo(300L);
        assertThat(dailyTransactionRepository.findAll()).hasSize(300);
    }

    @Test
    void findByIdReturnsSeededDailyTransaction() {
        List<DailyTransaction> all = dailyTransactionRepository.findAll();
        assertThat(all).isNotEmpty();
        DailyTransaction sample = all.get(0);
        String id = sample.getDalytranId();

        Optional<DailyTransaction> found = dailyTransactionRepository.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().getDalytranId()).isEqualTo(id);
    }

    @Test
    void saveStagesNewDailyTransaction() {
        List<DailyTransaction> seed = dailyTransactionRepository.findAll();
        DailyTransaction staged = seed.get(0);
        String newId = "Z" + staged.getDalytranId().substring(1);

        entityManager.detach(staged);
        staged.setDalytranId(newId);
        dailyTransactionRepository.saveAndFlush(staged);

        assertThat(dailyTransactionRepository.findById(newId)).isPresent();
        assertThat(dailyTransactionRepository.count()).isEqualTo(301L);
    }

    @Test
    void saveAllStagesMultipleDailyTransactions() {
        List<DailyTransaction> seed = dailyTransactionRepository.findAll();
        DailyTransaction first = seed.get(0);
        DailyTransaction second = seed.get(1);
        String firstId = "Y" + first.getDalytranId().substring(1);
        String secondId = "Z" + second.getDalytranId().substring(1);

        entityManager.detach(first);
        entityManager.detach(second);
        first.setDalytranId(firstId);
        second.setDalytranId(secondId);
        dailyTransactionRepository.saveAll(List.of(first, second));
        dailyTransactionRepository.flush();

        assertThat(dailyTransactionRepository.count()).isEqualTo(302L);
        assertThat(dailyTransactionRepository.findById(firstId)).isPresent();
        assertThat(dailyTransactionRepository.findById(secondId)).isPresent();
    }
}
