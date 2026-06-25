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

import com.carddemo.entity.DailyTransaction;
import com.carddemo.repository.DailyTransactionRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemReader} that performs the sequential staging read of
 * unposted daily-transaction records, translating the {@code DALYTRAN-FILE}
 * input scan of the COBOL batch driver {@code CBTRN01C} (record layout copybook
 * {@code CVTRA06Y}, RECLN 350) at source commit {@code 27d6c6f}.
 *
 * <p>The legacy {@code DALYTRAN-FILE} is declared {@code ORGANIZATION IS
 * SEQUENTIAL, ACCESS MODE IS SEQUENTIAL}, opened {@code OPEN INPUT}, and consumed
 * record-by-record by paragraph {@code 1000-DALYTRAN-GET-NEXT}. That flat input
 * is realized in the migration as the PostgreSQL {@code daily_transaction}
 * staging table, surfaced through {@link DailyTransactionRepository}. This reader
 * emits each staging {@link DailyTransaction} into the chunk-oriented posting
 * step in a deterministic order — ascending by {@code tranId}
 * ({@code DALYTRAN-ID PIC X(16)}) — and {@link #read()} returns {@code null} once
 * the input is exhausted, mirroring the COBOL end-of-file condition
 * ({@code FILE STATUS '10'}). An unexpected data-access error propagates and
 * fails the step, mirroring the COBOL abend path.</p>
 *
 * <p>The reader's responsibility is limited to the sequential staging read. The
 * card-to-account validation lookups that {@code CBTRN01C} performs against the
 * cross-reference and account files ({@code 2000-LOOKUP-XREF} and
 * {@code 3000-READ-ACCOUNT}) are not part of this contract; they belong to the
 * posting processor (the migration of {@code CBTRN02C}). No record is validated,
 * enriched, posted, or rejected here.</p>
 *
 * <p>The bean is {@link StepScope step-scoped}: each step execution obtains its
 * own paging cursor, so the reader is restartable and holds no shared mutable
 * state across job executions.</p>
 */
@Component
@StepScope
public class DailyTransactionItemReader extends RepositoryItemReader<DailyTransaction> {

    /** Entity property backing {@code DALYTRAN-ID}; the deterministic sort key. */
    private static final String SORT_PROPERTY = "tranId";

    /** Inherited paging finder used to scan the {@code daily_transaction} table. */
    private static final String REPOSITORY_METHOD = "findAll";

    /** Stable reader name keying restart state in the step execution context. */
    private static final String READER_NAME = "dailyTransactionItemReader";

    private final DailyTransactionRepository dailyTransactionRepository;

    private final int chunkSize;

    /**
     * Creates the reader with its backing staging repository and page size.
     *
     * @param dailyTransactionRepository the staging repository scanned in
     *     ascending {@code tranId} order; must not be {@code null}
     * @param chunkSize the paging size, bound from
     *     {@code carddemo.batch.chunk-size} (default {@code 100}); must be greater
     *     than zero
     */
    public DailyTransactionItemReader(DailyTransactionRepository dailyTransactionRepository,
            @Value("${carddemo.batch.chunk-size:100}") int chunkSize) {
        this.dailyTransactionRepository = dailyTransactionRepository;
        this.chunkSize = chunkSize;
    }

    /**
     * Configures and validates the underlying {@link RepositoryItemReader} prior
     * to use: the staging repository is scanned via its inherited {@code findAll}
     * paging finder, ordered ascending by {@code tranId}, with a page size equal
     * to the configured chunk size. Invoked by the container after construction.
     *
     * @throws Exception if the resulting reader configuration is incomplete or
     *     invalid (for example a non-positive page size)
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        setName(READER_NAME);
        setRepository(dailyTransactionRepository);
        setMethodName(REPOSITORY_METHOD);
        setSort(Map.of(SORT_PROPERTY, Sort.Direction.ASC));
        setPageSize(chunkSize);
        super.afterPropertiesSet();
    }
}
