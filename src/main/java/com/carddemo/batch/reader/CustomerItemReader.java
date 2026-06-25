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

import com.carddemo.entity.Customer;
import com.carddemo.repository.CustomerRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link org.springframework.batch.item.ItemReader ItemReader} that
 * streams the customer master in primary-key order. It is the modern translation of
 * the legacy COBOL batch program {@code CBCUS01C} (source commit {@code 27d6c6f};
 * standalone read job {@code app/jcl/READCUST.jcl}, {@code EXEC PGM=CBCUS01C}).
 *
 * <p>{@code CBCUS01C} opens the customer master VSAM KSDS
 * ({@code ORGANIZATION INDEXED, ACCESS MODE SEQUENTIAL}, {@code RECORD KEY FD-CUST-ID})
 * and reads every {@code CUSTOMER-RECORD} in ascending {@code CUST-ID} order until
 * end-of-file. That KSDS is now the PostgreSQL {@code customers} table, so this reader
 * pages over the inherited {@code findAll(Pageable)} of {@link CustomerRepository},
 * sorted ascending by {@code custId}, reproducing the original key-sequential delivery.</p>
 *
 * <p>Behavioral contract:</p>
 * <ul>
 *   <li>{@link #read()} returns the next {@link Customer} in ascending {@code custId}
 *       order and yields {@code null} once the data is exhausted (the COBOL
 *       {@code FILE STATUS '10'} end-of-file condition).</li>
 *   <li>A data-access failure propagates as an exception and fails the enclosing step,
 *       mirroring the COBOL non-{@code '00'}/{@code '10'} status path that abends
 *       {@code CBCUS01C}.</li>
 *   <li>The reader is read-only: it emits records only. Any reporting or persistence
 *       side effect is owned by the downstream processor or writer.</li>
 *   <li>The reader is {@link org.springframework.batch.core.configuration.annotation.StepScope
 *       step-scoped}: every step execution receives a fresh paging cursor and holds no
 *       shared mutable state across executions. The read position is saved to and restored
 *       from the step {@link org.springframework.batch.item.ExecutionContext}
 *       (save-state enabled), so the enclosing step is restartable.</li>
 * </ul>
 */
@Component
@StepScope
public class CustomerItemReader extends RepositoryItemReader<Customer> {

    /** Entity property backing {@code CUST-ID}; the deterministic sort key. */
    private static final String SORT_PROPERTY = "custId";

    /** Inherited paging finder used to scan the {@code customers} table. */
    private static final String REPOSITORY_METHOD = "findAll";

    /** Stable reader name keying restart state in the step execution context. */
    private static final String READER_NAME = "customerItemReader";

    private final CustomerRepository customerRepository;

    private final int chunkSize;

    /**
     * Constructs the customer master reader, capturing the backing repository and page
     * size. The inherited {@link RepositoryItemReader} configuration is applied in
     * {@link #afterPropertiesSet()} so that no overridable method is invoked from the
     * constructor.
     *
     * @param customerRepository Spring Data repository over the {@code customers} table;
     *                           its inherited {@code findAll(Pageable)} provides paged,
     *                           sorted access. Must not be {@code null}.
     * @param chunkSize          page size bound from the {@code carddemo.batch.chunk-size}
     *                           property (defaults to {@code 100} when unset). Must be
     *                           greater than zero.
     */
    public CustomerItemReader(
            CustomerRepository customerRepository,
            @Value("${carddemo.batch.chunk-size:100}") int chunkSize) {
        this.customerRepository = customerRepository;
        this.chunkSize = chunkSize;
    }

    /**
     * Configures and validates the underlying {@link RepositoryItemReader} prior to use:
     * the {@code customers} table is scanned via its inherited {@code findAll} paging
     * finder, ordered ascending by {@code custId}, with a page size equal to the
     * configured chunk size and save-state enabled for step restartability. Invoked by
     * the container after construction.
     *
     * @throws Exception if the resulting reader configuration is incomplete or invalid
     *                   (for example a non-positive page size)
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        setRepository(customerRepository);
        setMethodName(REPOSITORY_METHOD);
        setSort(Map.of(SORT_PROPERTY, Sort.Direction.ASC));
        setPageSize(chunkSize);
        setName(READER_NAME);
        setSaveState(true);
        super.afterPropertiesSet();
    }
}
