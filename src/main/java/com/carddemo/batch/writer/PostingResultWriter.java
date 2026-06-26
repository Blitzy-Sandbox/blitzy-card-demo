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

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.stereotype.Component;

import com.carddemo.batch.processor.PostingResult;
import com.carddemo.entity.Transaction;

/**
 * Composite Spring Batch writer for the daily-transaction posting step
 * ({@code CBTRN02C} / {@code POSTTRAN}) that fans every {@link PostingResult} out
 * to the correct durable sink, preserving the COBOL behavior in which a single
 * sequential pass writes posted transactions to the transaction master
 * <em>and</em> writes rejected records to the {@code DALYREJS} reject file.
 *
 * <p>The upstream {@code TransactionPostingProcessor} now emits a
 * {@link PostingResult} for every input record (never {@code null}), so no
 * rejected record is filtered out of the step. This writer partitions each chunk
 * by the sealed result type and delegates:</p>
 * <ul>
 *   <li>{@link PostingResult.Posted} &rarr; {@link PostedTransactionWriter},
 *       which persists the {@link Transaction} to the PostgreSQL transaction
 *       master and owns the {@code carddemo.batch.records.processed} metric.</li>
 *   <li>{@link PostingResult.Rejected} &rarr; {@link RejectTransactionWriter},
 *       which assembles the byte-exact 430-byte reject record from the 350-byte
 *       {@code CVTRA06Y} image plus the {@link com.carddemo.enums.RejectReasonCode}
 *       trailer, uploads it to the S3 reject object, and owns the
 *       {@code carddemo.batch.records.rejected} metric.</li>
 * </ul>
 *
 * <p>Posted transactions are persisted first (inside the step's chunk
 * transaction) and rejected records are buffered second; this ordering keeps the
 * two sinks consistent on rollback, because a persistence failure on the posted
 * arm propagates before any reject bytes are buffered, so a retried chunk does
 * not double-buffer rejects.</p>
 *
 * <p>This component is {@link StepScope step-scoped} because its
 * {@link RejectTransactionWriter} delegate is step-scoped (it accumulates the
 * run's rejects in memory and uploads them once at {@link #close()}). The
 * composite implements {@link ItemStreamWriter} and forwards the
 * {@code open}/{@code update}/{@code close} lifecycle to that delegate so the
 * reject object is created and flushed exactly once per run; the
 * {@link PostedTransactionWriter} carries no stream state and needs no lifecycle
 * forwarding.</p>
 */
@Component
@StepScope
public class PostingResultWriter implements ItemStreamWriter<PostingResult> {

    private final PostedTransactionWriter postedTransactionWriter;
    private final RejectTransactionWriter rejectTransactionWriter;

    /**
     * Creates the composite writer with its two delegate sinks.
     *
     * @param postedTransactionWriter the sink that persists posted transactions
     *                                 and owns the processed-records metric; must
     *                                 not be {@code null}
     * @param rejectTransactionWriter the step-scoped sink that emits the 430-byte
     *                                 reject records to S3 and owns the
     *                                 rejected-records metric; must not be
     *                                 {@code null}
     */
    public PostingResultWriter(PostedTransactionWriter postedTransactionWriter,
                               RejectTransactionWriter rejectTransactionWriter) {
        this.postedTransactionWriter = postedTransactionWriter;
        this.rejectTransactionWriter = rejectTransactionWriter;
    }

    /**
     * Partitions the chunk by sealed result type and delegates each partition to
     * its sink. Posted transactions are persisted first, then rejected records are
     * buffered; either delegate's exception is propagated to fail and roll back
     * the chunk. Empty partitions are skipped.
     *
     * @param chunk the chunk of posting results to route; never {@code null}
     * @throws Exception if either delegate fails to write its partition
     */
    @Override
    public void write(Chunk<? extends PostingResult> chunk) throws Exception {
        Chunk<Transaction> posted = new Chunk<>();
        Chunk<RejectTransactionWriter.RejectedTransaction> rejected = new Chunk<>();

        for (PostingResult result : chunk.getItems()) {
            switch (result) {
                case PostingResult.Posted p -> posted.add(p.transaction());
                case PostingResult.Rejected r -> rejected.add(
                        new RejectTransactionWriter.RejectedTransaction(
                                r.originalRecordImage(), r.reason()));
            }
        }

        if (!posted.isEmpty()) {
            postedTransactionWriter.write(posted);
        }
        if (!rejected.isEmpty()) {
            rejectTransactionWriter.write(rejected);
        }
    }

    /**
     * Opens the reject delegate's stream so its per-run buffer and versioned
     * object key are initialized. The posted delegate holds no stream state.
     *
     * @param executionContext the step execution context
     * @throws ItemStreamException if the delegate fails to open
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        rejectTransactionWriter.open(executionContext);
    }

    /**
     * Forwards the periodic stream update to the reject delegate.
     *
     * @param executionContext the step execution context
     * @throws ItemStreamException if the delegate fails to update
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        rejectTransactionWriter.update(executionContext);
    }

    /**
     * Closes the reject delegate's stream, flushing the run's accumulated rejects
     * to S3 as a single object.
     *
     * @throws ItemStreamException if the delegate fails to upload or close
     */
    @Override
    public void close() throws ItemStreamException {
        rejectTransactionWriter.close();
    }
}
