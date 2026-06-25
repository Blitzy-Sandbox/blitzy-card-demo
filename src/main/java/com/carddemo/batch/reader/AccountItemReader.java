/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.batch.reader;

import java.util.Map;

import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Chunk-oriented {@link ItemStreamReader} that streams the account master in
 * ascending primary-key order, supplying {@link Account} entities to a
 * Spring Batch step.
 *
 * <p>This is the migration target of the COBOL batch program {@code CBACT01C}
 * (account master sequential reader) at source commit {@code 27d6c6f}: the
 * legacy VSAM KSDS {@code ACCTFILE} ({@code ORGANIZATION INDEXED},
 * {@code ACCESS SEQUENTIAL}, {@code RECORD KEY FD-ACCT-ID}) is replaced by the
 * PostgreSQL {@code accounts} table accessed through {@link AccountRepository}.
 * Records are therefore read in ascending {@code acctId} order, reproducing the
 * key-sequential order the legacy program relied upon. The COBOL
 * {@code DISPLAY} side-effect is not reproduced here; this reader only emits
 * records and leaves all output to downstream processors and writers.</p>
 *
 * <p>Reading is paged and delegated to a {@link RepositoryItemReader}, so the
 * reader is restartable: end of data is signalled by {@link #read()} returning
 * {@code null}, and the read cursor is persisted to, and restored from, the
 * step {@link ExecutionContext}. Any unexpected data-access failure surfaced by
 * {@link #read()} propagates to the step and fails it, mirroring the abend the
 * COBOL program raised on an unexpected file status.</p>
 *
 * <p>The bean is {@link StepScope step-scoped}, so each step execution receives
 * its own instance and read cursor; there is no shared mutable state across
 * runs. The page size is bound to the {@code carddemo.batch.chunk-size}
 * property.</p>
 */
@Component
@StepScope
public class AccountItemReader implements ItemStreamReader<Account> {

    /** Repository finder method invoked by the delegate to fetch each page. */
    private static final String READ_METHOD_NAME = "findAll";

    /** {@link Account} property used as the ascending sort key. */
    private static final String SORT_PROPERTY = "acctId";

    /** Page size applied when the configured chunk size is not positive. */
    private static final int DEFAULT_PAGE_SIZE = 100;

    /** Reader name used to key the restart state in the step execution context. */
    private static final String READER_NAME = "accountItemReader";

    private final RepositoryItemReader<Account> delegate;

    /**
     * Creates a fully configured, restartable account master reader.
     *
     * @param accountRepository the Spring Data JPA repository for the
     *                          {@code accounts} table; its inherited
     *                          {@code findAll(Pageable)} method is used to read
     *                          accounts one page at a time
     * @param chunkSize         the configured batch chunk size bound from the
     *                          {@code carddemo.batch.chunk-size} property; when
     *                          not positive, {@value #DEFAULT_PAGE_SIZE} is used
     *                          as the page size
     * @throws IllegalStateException if the underlying paged reader cannot be
     *                               initialized with the supplied configuration
     */
    public AccountItemReader(AccountRepository accountRepository,
                             @Value("${carddemo.batch.chunk-size:100}") int chunkSize) {
        int pageSize = chunkSize > 0 ? chunkSize : DEFAULT_PAGE_SIZE;
        RepositoryItemReader<Account> repositoryItemReader = new RepositoryItemReader<>();
        repositoryItemReader.setRepository(accountRepository);
        repositoryItemReader.setMethodName(READ_METHOD_NAME);
        repositoryItemReader.setSort(Map.of(SORT_PROPERTY, Sort.Direction.ASC));
        repositoryItemReader.setPageSize(pageSize);
        repositoryItemReader.setName(READER_NAME);
        try {
            repositoryItemReader.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to initialize the account master item reader", ex);
        }
        this.delegate = repositoryItemReader;
    }

    /**
     * Returns the next account in ascending {@code acctId} order, or
     * {@code null} once every account has been read.
     *
     * @return the next {@link Account}, or {@code null} at end of data
     * @throws Exception if an unexpected data-access error occurs, which fails
     *                   the enclosing step
     */
    @Override
    public Account read() throws Exception {
        return delegate.read();
    }

    /**
     * Opens the reader and restores any previously persisted read position.
     *
     * @param executionContext the step execution context
     * @throws ItemStreamException if the reader cannot be opened
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        delegate.open(executionContext);
    }

    /**
     * Persists the current read position so the step can be restarted.
     *
     * @param executionContext the step execution context
     * @throws ItemStreamException if the read position cannot be persisted
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        delegate.update(executionContext);
    }

    /**
     * Closes the reader and releases any resources it holds.
     *
     * @throws ItemStreamException if the reader cannot be closed cleanly
     */
    @Override
    public void close() throws ItemStreamException {
        delegate.close();
    }
}
