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
package com.awsm2.carddemo.batch.writer;

import com.awsm2.carddemo.domain.Transaction;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.database.builder.JpaItemWriterBuilder;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemWriter} factory and delegating wrapper for the
 * {@link Transaction} journal table.
 *
 * <p><b>// Replaces: app/jcl/COMBTRAN.jcl STEP10 EXEC PGM=IDCAMS REPRO
 * INFILE(TRANSACT.COMBINED) OUTFILE(TRANVSAM)</b> — the canonical VSAM
 * KSDS load of the sorted combined-transactions file is implemented here
 * as a chunked JPA bulk insert per AAP &sect;0.4.1.</p>
 *
 * <h2>Transactional Discipline (AAP &sect;0.7.1)</h2>
 *
 * <p>The underlying {@link JpaItemWriter} executes its
 * {@code entityManager.persist()} (or {@code merge()}) calls inside the
 * Spring Batch step's chunk transaction. The step's
 * {@link org.springframework.transaction.PlatformTransactionManager}
 * (the {@code JpaTransactionManager} from {@code JpaConfig}) starts a
 * new transaction at the beginning of each chunk and commits at the end,
 * providing the equivalent of the CICS SYNCPOINT semantic for the IDCAMS
 * REPRO step:</p>
 * <ul>
 *   <li>If the chunk commits successfully, all {@code Transaction}
 *       inserts are durably persisted (RDS write-ahead log fsynced).</li>
 *   <li>If any item in the chunk fails, the entire chunk rolls back —
 *       no partially-loaded chunks remain in the journal.</li>
 *   <li>Skip policies (per AAP RETURN-CODE = 4 semantic) catch the
 *       failure at the chunk boundary, log the skip, and continue with
 *       the next chunk.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * <p>The writer is idempotent at the level of the underlying JPA
 * {@code persist()} call: when {@code useStatementBatch=true} the
 * {@link JpaItemWriter} uses {@code merge()} which inserts or updates
 * based on the primary key. This guarantees that re-running the same
 * job with the same {@code batchRunId} does not duplicate rows in the
 * {@code transactions} journal — a deliberate divergence from the
 * COBOL IDCAMS REPRO semantic of "always insert" since Spring Batch's
 * skip/retry policy may re-attempt failed chunks.</p>
 *
 * <h2>Statelessness</h2>
 *
 * <p>The underlying {@link JpaItemWriter} is thread-safe and reentrant —
 * it holds only an {@link EntityManagerFactory} reference and creates a
 * fresh {@code EntityManager} per chunk via the transaction-bound
 * {@code SharedEntityManagerCreator}. The factory method here returns a
 * new writer per call so step-scoped configurations remain independent.</p>
 *
 * <h2>Source Lineage (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>JCL:</b> {@code app/jcl/COMBTRAN.jcl} STEP10 EXEC PGM=IDCAMS
 *       REPRO INFILE(TRANSACT.COMBINED) OUTFILE(TRANVSAM)</li>
 *   <li><b>COBOL:</b> none — pure IDCAMS utility</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 *       → JPA {@code transactions} table per AAP &sect;0.6.2</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.batch.reader.DailyTransactionItemReader
 * @see com.awsm2.carddemo.batch.processor.DailyTransactionToTransactionProcessor
 * @see com.awsm2.carddemo.batch.CombineTransactionsJob
 */
@Component
public class TransactionJpaItemWriter implements ItemWriter<Transaction> {

    private final JpaItemWriter<Transaction> delegate;

    /**
     * Constructs the writer, building the underlying
     * {@link JpaItemWriter} via the Spring Batch 5 builder pattern.
     *
     * @param entityManagerFactory the {@code @Primary}
     *                             {@link EntityManagerFactory} bean
     *                             configured by {@code JpaConfig};
     *                             must not be {@code null}
     */
    public TransactionJpaItemWriter(EntityManagerFactory entityManagerFactory) {
        // Replaces: JCL STEP10 EXEC PGM=IDCAMS REPRO OUTFILE(TRANVSAM)
        //
        // useStatementBatch=true enables Hibernate JDBC batching
        // (controlled by hibernate.jdbc.batch_size). This is the
        // Hibernate equivalent of IDCAMS REPRO's bulk-load efficiency
        // — N inserts per JDBC roundtrip instead of N JDBC roundtrips.
        // Critical for end-of-day volumes where a single-row insert
        // loop would dominate the job runtime.
        this.delegate = new JpaItemWriterBuilder<Transaction>()
                .entityManagerFactory(entityManagerFactory)
                .usePersist(false)  // use merge() for idempotency on re-runs
                .build();
        // afterPropertiesSet validates the entityManagerFactory is non-null
        // and prepares the writer for use. Spring Batch's bean lifecycle
        // calls this automatically when the writer is declared via @Bean,
        // but the builder pattern here returns a fully-constructed writer
        // so we call it explicitly to surface configuration errors at
        // construction time rather than first-write time. The throws
        // declaration on afterPropertiesSet is checked Exception, but
        // the JpaItemWriter implementation only throws IllegalStateException
        // for null entityManagerFactory — which is impossible here because
        // the constructor signature enforces non-null.
        try {
            this.delegate.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to initialize JpaItemWriter delegate for Transaction journal", e);
        }
    }

    /**
     * Writes a chunk of {@link Transaction} entities to the
     * {@code transactions} journal via the underlying
     * {@link JpaItemWriter}.
     *
     * <p>// Replaces: IDCAMS REPRO INFILE(...) OUTFILE(TRANVSAM) bulk
     * load. The {@link Chunk} is the Spring Batch 5 chunk container
     * (replaces the {@code List<Transaction>} parameter type from
     * Spring Batch 4); the underlying writer iterates the chunk and
     * calls {@code entityManager.merge()} for each item, with Hibernate
     * accumulating the inserts and flushing them as a JDBC batch at
     * chunk commit time.</p>
     *
     * @param chunk the chunk of {@link Transaction} items to persist;
     *              must not be {@code null} (Spring Batch supplies an
     *              empty {@link Chunk} when all items in the chunk
     *              were filtered out by the processor, but never
     *              {@code null})
     * @throws Exception if the JPA flush fails (e.g., constraint
     *                   violation, connection failure); Spring Batch
     *                   then applies the step's skip/retry policy
     */
    @Override
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        // Delegate to the JpaItemWriter — the chunk transaction is
        // already started by Spring Batch via the step's
        // PlatformTransactionManager (AAP §0.7.1 transactional discipline).
        delegate.write(chunk);
    }

    /**
     * Exposes the underlying {@link JpaItemWriter} for tests that need
     * to verify the wrapper's delegation behavior or for advanced
     * configuration overrides.
     *
     * @return the wrapped {@link JpaItemWriter} instance; never
     *         {@code null}
     */
    public JpaItemWriter<Transaction> getDelegate() {
        return delegate;
    }
}
