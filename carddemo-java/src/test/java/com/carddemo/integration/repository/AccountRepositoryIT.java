package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.model.entity.Account;
import com.carddemo.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Integration tests for {@link AccountRepository} against a real PostgreSQL 16 Testcontainer.
 * Verifies the VSAM ACCTDAT KSDS re-platforms to PostgreSQL with keyed reads, exact BigDecimal
 * scale-2 balance precision, and JPA {@code @Version} optimistic locking (the COBOL COACTUPC
 * before/after-image concurrency guard).
 */
class AccountRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void seedDataLoadsAllAccounts() {
        assertThat(accountRepository.count()).isEqualTo(50L);
        assertThat(accountRepository.findAll()).hasSize(50);
    }

    @Test
    void findByIdReturnsSeededAccount() {
        List<Account> all = accountRepository.findAll();
        assertThat(all).isNotEmpty();
        Account sample = all.get(0);
        Long acctId = sample.getAcctId();

        Optional<Account> found = accountRepository.findById(acctId);

        assertThat(found).isPresent();
        assertThat(found.get().getAcctId()).isEqualTo(acctId);
        assertThat(found.get().getAcctCurrBal()).isNotNull();
    }

    @Test
    void seededAccountUsesScaleTwoBalanceAndStartsAtVersionZero() {
        Account sample = accountRepository.findAll().get(0);

        assertThat(sample.getAcctCurrBal()).isNotNull();
        assertThat(sample.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(sample.getVersion()).isEqualTo(0L);
    }

    @Test
    void saveUpdatesAccountBalance() {
        Account account = accountRepository.findAll().get(0);
        Long acctId = account.getAcctId();
        BigDecimal newBalance = new BigDecimal("4321.99");

        account.setAcctCurrBal(newBalance);
        accountRepository.saveAndFlush(account);
        entityManager.clear();

        Account reloaded = accountRepository.findById(acctId).orElseThrow();
        assertThat(reloaded.getAcctCurrBal()).isEqualByComparingTo(newBalance);
    }

    @Test
    void concurrentUpdateRaisesOptimisticLockingFailure() {
        Long acctId = accountRepository.findAll().get(0).getAcctId();
        entityManager.clear();

        Account stale = accountRepository.findById(acctId).orElseThrow();
        entityManager.detach(stale);

        Account fresh = accountRepository.findById(acctId).orElseThrow();
        fresh.setAcctCurrBal(fresh.getAcctCurrBal().add(new BigDecimal("10.00")));
        accountRepository.saveAndFlush(fresh);
        entityManager.detach(fresh);

        stale.setAcctCurrBal(stale.getAcctCurrBal().add(new BigDecimal("20.00")));

        assertThrows(
                OptimisticLockingFailureException.class,
                () -> accountRepository.saveAndFlush(stale));
    }
}
