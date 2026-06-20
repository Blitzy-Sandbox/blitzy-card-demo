package com.carddemo.batch.readers;

import com.carddemo.model.entity.Account;
import com.carddemo.repository.AccountRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch reader for the account master, replacing COBOL batch reader {@code CBACT01C}
 * (source commit {@code 27d6c6f}; REFERENCE ONLY, COBOL is not copied). Streams {@link Account}
 * rows in ascending {@code ACCT-ID} order via a sorted, paged {@link RepositoryItemReader} scan
 * over {@link AccountRepository}, reproducing the sequential primary-key read of the VSAM
 * {@code ACCTFILE} KSDS ({@code ORGANIZATION INDEXED}, {@code ACCESS MODE SEQUENTIAL}).
 *
 * <p>The framework opens the scan, fetches one page at a time, and closes it; exhaustion of the
 * scan yields {@code null} (the COBOL {@code FILE STATUS '10'} end-of-file), which signals Spring
 * Batch to stop the step. A hard data-access failure propagates as Spring Data's own
 * {@code DataAccessException} and fails the step (the abend equivalent). The reader is step-scoped
 * so each step execution begins with fresh paging state.
 */
@Component
@StepScope
public class AccountReader extends RepositoryItemReader<Account> {

    /** Account master repository (re-platforms the VSAM ACCTDAT KSDS) that supplies the paged scan. */
    private final AccountRepository accountRepository;

    /**
     * Creates a step-scoped reader bound to the account master repository. The paged, sorted scan
     * is configured in {@link #afterPropertiesSet()} (invoked by the Spring {@code InitializingBean}
     * lifecycle before the first read), not the constructor, so no partially-constructed instance
     * escapes through an overridable setter.
     *
     * @param accountRepository the Spring Data repository over the account master
     */
    public AccountReader(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Configures the ascending {@code acctId} sort (mirroring the VSAM KSDS primary-key order), the
     * {@code findAll(Pageable)} repository method, the fetch page size, and the save-state name,
     * then delegates to the superclass, which validates the configuration and prepares the paged
     * scan. The page size affects fetch granularity only; the set and order of emitted records are
     * unchanged for any page size.
     *
     * @throws Exception if superclass initialization or configuration validation fails
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
