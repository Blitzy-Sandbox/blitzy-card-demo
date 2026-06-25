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

import com.carddemo.entity.Card;
import com.carddemo.repository.CardRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link org.springframework.batch.item.ItemReader} for the card
 * master, translating the sequential read loop of COBOL program
 * {@code app/cbl/CBACT02C.cbl} (standalone job {@code app/jcl/READCARD.jcl},
 * {@code EXEC PGM=CBACT02C}) at source commit {@code 27d6c6f}.
 *
 * <p>The legacy program opens the card master VSAM KSDS
 * ({@code ORGANIZATION INDEXED, ACCESS MODE SEQUENTIAL, RECORD KEY FD-CARD-NUM}),
 * reads each {@code CARD-RECORD} in ascending card-number order until end of
 * file, then closes. The VSAM KSDS is now the PostgreSQL {@code cards} table, so
 * this reader streams {@link Card} entities from {@link CardRepository} in
 * ascending {@code cardNum} order — preserving the key-sequential read order — and
 * returns {@code null} once the last record has been emitted (mirroring the COBOL
 * {@code FILE STATUS '10'} end-of-file). A data-access failure propagates as an
 * exception, failing the step in the same way the COBOL
 * {@code 9999-ABEND-PROGRAM} terminated the job.</p>
 *
 * <p>Records are paged through the repository's {@code findAll(Pageable)} method
 * with a page size bound from {@code carddemo.batch.chunk-size}. The reader is
 * step-scoped and delegates the {@link org.springframework.batch.item.ItemStream}
 * lifecycle to a {@link RepositoryItemReader}, so it is restartable and holds no
 * shared mutable cursor. It is read-only and emits records only; any downstream
 * print or persistence side effect is owned by the step's processor and writer.</p>
 */
@Component
@StepScope
public class CardItemReader implements ItemStreamReader<Card>, InitializingBean {

    /**
     * Default chunk/page size applied when {@code carddemo.batch.chunk-size} is
     * not configured.
     */
    private static final int DEFAULT_CHUNK_SIZE = 100;

    /**
     * Ascending sort on the {@code cardNum} primary key, reproducing the VSAM
     * key-sequential read order of {@code CBACT02C}.
     */
    private static final Map<String, Sort.Direction> SORT_BY_CARD_NUM_ASC =
            Map.of("cardNum", Sort.Direction.ASC);

    /** Restart-state key prefix in the step {@link ExecutionContext}. */
    private static final String READER_NAME = "cardItemReader";

    private final RepositoryItemReader<Card> delegate;

    /**
     * Builds the card master reader.
     *
     * @param cardRepository the repository backing the {@code cards} table
     *                       (replaces the VSAM {@code CARDDAT} KSDS); must not be
     *                       {@code null}
     * @param chunkSize      the page size, bound from
     *                       {@code carddemo.batch.chunk-size} (defaults to
     *                       {@value #DEFAULT_CHUNK_SIZE})
     */
    public CardItemReader(
            CardRepository cardRepository,
            @Value("${carddemo.batch.chunk-size:" + DEFAULT_CHUNK_SIZE + "}") int chunkSize) {
        RepositoryItemReader<Card> reader = new RepositoryItemReader<>();
        reader.setRepository(cardRepository);
        reader.setMethodName("findAll");
        reader.setSort(SORT_BY_CARD_NUM_ASC);
        reader.setPageSize(chunkSize);
        reader.setName(READER_NAME);
        reader.setSaveState(true);
        this.delegate = reader;
    }

    /**
     * Validates the underlying reader configuration, failing fast when the
     * repository, sort, method name, or a positive page size is missing.
     *
     * @throws Exception if the delegate configuration is invalid
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        delegate.afterPropertiesSet();
    }

    /**
     * Returns the next {@link Card} in ascending {@code cardNum} order, or
     * {@code null} when no further records remain (end of file).
     *
     * @return the next card, or {@code null} at end of data
     * @throws Exception if the underlying data access fails
     */
    @Override
    public Card read() throws Exception {
        return delegate.read();
    }

    /**
     * Opens the reader, restoring any saved restart position from the execution
     * context.
     *
     * @param executionContext the step execution context
     * @throws ItemStreamException if the stream cannot be opened
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        delegate.open(executionContext);
    }

    /**
     * Stores the current read position in the execution context to support
     * restart.
     *
     * @param executionContext the step execution context
     * @throws ItemStreamException if the state cannot be stored
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        delegate.update(executionContext);
    }

    /**
     * Closes the reader and releases its paging state.
     *
     * @throws ItemStreamException if the stream cannot be closed
     */
    @Override
    public void close() throws ItemStreamException {
        delegate.close();
    }
}
