package com.carddemo.batch.readers;

import com.carddemo.model.entity.Account;
import com.carddemo.repository.AccountRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch reader for the account master, replacing COBOL batch reader CBACT01C
 * (source commit 27d6c6f). Streams {@link Account} rows in ascending ACCT-ID order via a
 * sorted, paged {@link RepositoryItemReader} scan over {@link AccountRepository}, reproducing
 * the VSAM KSDS sequential primary-key read (ORGANIZATION INDEXED, ACCESS MODE SEQUENTIAL,
 * RECORD KEY ACCT-ID).
 *
 * <p>Wired into a Spring Batch step by the batch job layer; it defines no job or step itself.
 * End-of-file maps to the reader's native {@code null} return (the framework stops the step),
 * and a hard data-access failure surfaces as Spring Data's {@code DataAccessException} which
 * fails the step (the abend equivalent).</p>
 *
 * <p>The inherited reader is configured in {@link #afterPropertiesSet()} rather than in the
 * constructor so that no overridable method is invoked during construction; Spring then validates
 * the configuration through the superclass call.</p>
 */
@Component
@StepScope
public class AccountReader extends RepositoryItemReader<Account> {

    /** Repository whose {@code findAll(Pageable)} backs the paged sequential scan. */
    private final AccountRepository accountRepository;

    /**
     * Stores the repository for deferred configuration. Configuration is performed in
     * {@link #afterPropertiesSet()} (not here) so the constructor invokes no overridable method.
     *
     * @param accountRepository the account repository, injected by Spring
     */
    public AccountReader(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Configures the inherited {@link RepositoryItemReader} once the bean is fully constructed:
     * the {@link AccountRepository} as the backing repository, {@code findAll(Pageable)} as the
     * paging method, an ascending sort on {@code acctId} (the deterministic VSAM KSDS key order),
     * a page size of 100 (a performance setting that does not affect the set or order of emitted
     * records), and the reader name used as the {@code ExecutionContext} key prefix for restart
     * state. The final {@code super} call runs the framework validation of these properties.
     *
     * @throws Exception if the superclass configuration validation fails
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        setRepository(accountRepository);
        setMethodName("findAll");
        setSort(Map.of("acctId", Sort.Direction.ASC));
        setPageSize(100);
        setName("accountReader");
        super.afterPropertiesSet();
    }
}
