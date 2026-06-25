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
package com.carddemo.batch.writer;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;

import io.micrometer.core.instrument.Counter;

/**
 * Spring Batch {@link ItemWriter} that persists successfully-posted
 * {@link Transaction} entities to the PostgreSQL {@code transactions} table via
 * {@link TransactionRepository}, replacing the COBOL write of a posted
 * transaction record to the VSAM transaction master in the daily-posting engine
 * ({@code CBTRN02C}, copybook {@code CVTRA05Y}).
 *
 * <p>The writer is a thin, single-responsibility persistence sink: the posting
 * and validation logic lives upstream in the step's {@code ItemProcessor}, and
 * this component only saves the chunk and records the processed-records metric.
 * It runs inside the Spring Batch step's chunk transaction and therefore carries
 * no transaction annotation of its own; if persistence fails, the exception is
 * allowed to propagate so the step rolls back the chunk and fails.
 *
 * <p>This writer is the authoritative emitter of the
 * {@code carddemo.batch.records.processed} metric; the processed counter is
 * incremented only after the chunk is durably saved, so a rolled-back chunk is
 * never counted. The component is stateless and is safe to use as a singleton.
 */
@Component
public class PostedTransactionWriter implements ItemWriter<Transaction> {

    private final TransactionRepository transactionRepository;
    private final Counter processedCounter;

    /**
     * Creates the writer with its collaborators.
     *
     * @param transactionRepository the repository used to persist posted
     *                              transactions ({@code saveAll} per chunk)
     * @param processedCounter      the {@code carddemo.batch.records.processed}
     *                              counter registered by
     *                              {@code MetricsConfig#batchRecordsProcessedCounter}
     */
    public PostedTransactionWriter(
            TransactionRepository transactionRepository,
            @Qualifier("batchRecordsProcessedCounter") Counter processedCounter) {
        this.transactionRepository = transactionRepository;
        this.processedCounter = processedCounter;
    }

    /**
     * Persists the chunk of posted transactions and records the processed-records
     * metric. An empty chunk is a no-op. The processed counter is incremented by
     * the chunk size only after {@code saveAll} returns successfully so that a
     * chunk rolled back by a persistence failure is not counted as processed; any
     * persistence exception is propagated to fail the step.
     *
     * @param chunk the chunk of posted transactions to persist
     * @throws Exception if the chunk cannot be persisted
     */
    @Override
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        if (chunk.isEmpty()) {
            return;
        }
        transactionRepository.saveAll(chunk.getItems());
        processedCounter.increment(chunk.size());
    }
}
