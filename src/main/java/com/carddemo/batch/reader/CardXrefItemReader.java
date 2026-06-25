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

import com.carddemo.entity.CardXref;
import com.carddemo.repository.CardXrefRepository;

import java.util.Map;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link org.springframework.batch.item.ItemReader} that streams
 * {@link CardXref} cross-reference records to a chunk-oriented step.
 *
 * <p>Replaces the sequential master read of the legacy COBOL batch program
 * {@code CBACT03C} (source commit {@code 27d6c6f}, driven by
 * {@code app/jcl/READXREF.jcl} against
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}). The VSAM KSDS is keyed on the
 * 16-character card number and read in ascending key sequence; this reader
 * reproduces that ordering by paging the {@code card_xref} table through
 * {@link CardXrefRepository} sorted ascending on {@code xrefCardNum}.
 *
 * <p>Each invocation of {@link #read()} returns the next {@link CardXref} and
 * {@code null} once every row has been emitted, matching the COBOL end-of-file
 * ({@code FILE STATUS '10'}) signal; any unexpected data-access failure propagates
 * as an exception and fails the step, mirroring the legacy abend path. The reader
 * is read-only and emits records exclusively &mdash; downstream side effects belong
 * to the step's processor and writer.
 *
 * <p>The reader is {@link StepScope step-scoped}: every step execution receives a
 * fresh paging cursor, so the reader is restartable and holds no shared mutable
 * state across executions. The page size is bound from the
 * {@code carddemo.batch.chunk-size} property and defaults to {@code 100}.
 */
@Component
@StepScope
public class CardXrefItemReader extends RepositoryItemReader<CardXref> {

    /**
     * {@link org.springframework.batch.item.ItemStream} name used to namespace the
     * reader's restart state within the step {@code ExecutionContext}.
     */
    private static final String READER_NAME = "cardXrefItemReader";

    /**
     * Repository method paged by the reader. {@code findAll(Pageable)} is inherited
     * from {@link org.springframework.data.repository.PagingAndSortingRepository}.
     */
    private static final String FIND_ALL_METHOD = "findAll";

    /**
     * {@link CardXref} property that establishes the key-sequenced read order,
     * mirroring the VSAM {@code RECORD KEY} defined on the card number.
     */
    private static final String SORT_PROPERTY = "xrefCardNum";

    /**
     * Repository that supplies the cross-reference rows backing the
     * {@code card_xref} table.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Page (chunk) size applied to the underlying paged read.
     */
    private final int chunkSize;

    /**
     * Creates the reader with its backing repository and page size.
     *
     * @param cardXrefRepository the cross-reference repository supplying records
     * @param chunkSize          the page size, bound from
     *                           {@code carddemo.batch.chunk-size} (default
     *                           {@code 100})
     */
    public CardXrefItemReader(CardXrefRepository cardXrefRepository,
                              @Value("${carddemo.batch.chunk-size:100}") int chunkSize) {
        this.cardXrefRepository = cardXrefRepository;
        this.chunkSize = chunkSize;
    }

    /**
     * Initializes the inherited {@link RepositoryItemReader} configuration &mdash;
     * repository, {@code findAll} paging method, ascending {@code xrefCardNum} sort,
     * page size, and reader name &mdash; then delegates to
     * {@link RepositoryItemReader#afterPropertiesSet()} for validation.
     *
     * @throws Exception if the resulting reader configuration is invalid, for
     *                   example a non-positive page size
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        setRepository(cardXrefRepository);
        setMethodName(FIND_ALL_METHOD);
        setSort(Map.of(SORT_PROPERTY, Sort.Direction.ASC));
        setPageSize(chunkSize);
        setName(READER_NAME);
        super.afterPropertiesSet();
    }
}
