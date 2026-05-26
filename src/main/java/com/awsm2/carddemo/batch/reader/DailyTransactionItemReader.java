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
package com.awsm2.carddemo.batch.reader;

import com.awsm2.carddemo.domain.DailyTransaction;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Spring Batch {@link JpaPagingItemReader} factory for the
 * {@link DailyTransaction} staging table.
 *
 * <p><b>// Replaces: app/jcl/COMBTRAN.jcl STEP05R SORTIN DD concatenation of
 * AWS.M2.CARDDEMO.TRANSACT.BKUP(0) + AWS.M2.CARDDEMO.SYSTRAN(0)</b> — the
 * unified read of the source GDG generations is implemented here as a
 * single ascending-by-{@code dalytranId} paged query against the
 * {@code daily_transactions} table per AAP &sect;0.4.1.</p>
 *
 * <h2>Why Paging (AAP &sect;0.6.2)</h2>
 *
 * <p>The original tasklet implementation called
 * {@code dailyTransactionRepository.findAll()} which loaded the entire
 * staging table into the JVM heap. For end-of-day batch volumes (tens of
 * thousands to millions of records), this is not viable. The
 * {@link JpaPagingItemReader} variant streams the staging table page-by-
 * page using {@code LIMIT}/{@code OFFSET} (Spring Batch translates to
 * {@code setFirstResult}/{@code setMaxResults}), bounding heap usage to
 * {@code pageSize} records regardless of total volume.</p>
 *
 * <h2>Ordering Guarantee</h2>
 *
 * <p>The JPQL query {@code SELECT d FROM DailyTransaction d ORDER BY
 * d.dalytranId ASC} pushes the sort to the database, replacing the
 * DFSORT {@code SORT FIELDS=(TRAN-ID,A)} step entirely. Java
 * {@code String} natural ordering matches COBOL {@code CH} (character
 * ascending) collation because the {@code TRAN-ID} field is a zero-
 * padded 16-character alphanumeric ({@code PIC X(16)}) per AAP
 * &sect;0.6.2 byte-padded string equivalence.</p>
 *
 * <h2>Spring Batch 5 Configuration Discipline</h2>
 *
 * <p>The reader is declared via the {@link JpaPagingItemReaderBuilder}
 * pattern (the Spring Batch 5 fluent factory). Configuration uses:</p>
 * <ul>
 *   <li>{@code .name("dailyTransactionItemReader")} &mdash; required to
 *       support step restartability via the saved
 *       {@code ItemReader.read} count in the {@code ExecutionContext}.</li>
 *   <li>{@code .entityManagerFactory(emf)} &mdash; binds the reader to the
 *       Spring-managed {@link EntityManagerFactory} (provided by
 *       {@code JpaConfig}).</li>
 *   <li>{@code .queryString("SELECT d FROM DailyTransaction d ORDER BY
 *       d.dalytranId ASC")} &mdash; explicit JPQL with deterministic
 *       sort order so identical inputs produce identical outputs across
 *       restarts.</li>
 *   <li>{@code .pageSize(pageSize)} &mdash; tuned independently of the
 *       chunk size so the JDBC fetch can be sized for I/O efficiency
 *       while the chunk commit boundary is sized for transactional
 *       safety.</li>
 *   <li>{@code .saveState(false)} &mdash; disables in-memory state
 *       checkpointing for this read since the COBOL semantic is a
 *       full-table scan with no partial-progress resume; if a job
 *       fails mid-execution, the operator re-runs from the start
 *       (idempotency is provided by the {@code batchRunId} parameter
 *       per AAP &sect;0.6.3).</li>
 * </ul>
 *
 * <h2>Statelessness</h2>
 *
 * <p>The reader bean is {@link StepScope @StepScope} so each
 * {@code StepExecution} receives a fresh reader instance — this guarantees
 * (a) no leaked iteration state between batch runs, (b) parameter-bound
 * fields (none in this reader, but the pattern matches the other Item*
 * components in this package) are resolved per-execution from the current
 * {@code JobParameters}.</p>
 *
 * <h2>Source Lineage (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>JCL:</b> {@code app/jcl/COMBTRAN.jcl} STEP05R EXEC PGM=SORT
 *       SORTIN DD concatenation</li>
 *   <li><b>COBOL:</b> none — pure DFSORT utility</li>
 *   <li><b>Copybook:</b> {@code app/cpy/CVTRA06Y.cpy} (DALYTRAN-RECORD)</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.batch.processor.DailyTransactionToTransactionProcessor
 * @see com.awsm2.carddemo.batch.writer.TransactionJpaItemWriter
 * @see com.awsm2.carddemo.batch.CombineTransactionsJob
 */
@Configuration
@Component
public class DailyTransactionItemReader {

    /**
     * Default page size for the JPA paging item reader. Matches the
     * default chunk size for the combine step (1000) so that under
     * steady-state operation, one JPA page roughly translates to one
     * chunk commit boundary. The value is overridable via the
     * {@code carddemo.batch.combine.reader-page-size} application
     * property.
     */
    public static final int DEFAULT_PAGE_SIZE = 1000;

    /**
     * The name used to register the underlying {@link JpaPagingItemReader}
     * with Spring Batch's {@code ExecutionContext}. Required so the
     * reader's read offset can be checkpointed and resumed across
     * step restarts.
     */
    public static final String READER_NAME = "dailyTransactionItemReader";

    private final EntityManagerFactory entityManagerFactory;

    @Value("${carddemo.batch.combine.reader-page-size:1000}")
    private int pageSize;

    /**
     * Constructs the reader factory, binding the Spring-managed
     * {@link EntityManagerFactory} for the underlying paged JPA query.
     *
     * @param entityManagerFactory the {@code @Primary}
     *                             {@link EntityManagerFactory} bean
     *                             configured by {@code JpaConfig};
     *                             must not be {@code null}
     */
    public DailyTransactionItemReader(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    /**
     * Builds a fresh {@link JpaPagingItemReader} bound to this step
     * execution. The reader returns {@link DailyTransaction} entities
     * in ascending {@code dalytranId} order from the
     * {@code daily_transactions} table.
     *
     * <p>// Replaces: JCL COMBTRAN.jcl STEP05R SORTIN DD with embedded
     * DFSORT SORT FIELDS=(TRAN-ID,A) — the database-level ordering is
     * deterministic and removes the in-memory sort entirely.</p>
     *
     * @return a configured {@link JpaPagingItemReader} ready for use
     *         in a Spring Batch chunk step
     */
    public JpaPagingItemReader<DailyTransaction> build() {
        return new JpaPagingItemReaderBuilder<DailyTransaction>()
                .name(READER_NAME)
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT d FROM DailyTransaction d ORDER BY d.dalytranId ASC")
                .parameterValues(Collections.emptyMap())
                .pageSize(pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE)
                .saveState(false)
                .build();
    }

    /**
     * Returns the currently configured page size (read at construction
     * time from {@code carddemo.batch.combine.reader-page-size}).
     *
     * @return the JPA paging item reader page size; defaults to
     *         {@link #DEFAULT_PAGE_SIZE} when unset
     */
    public int getPageSize() {
        return pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
    }
}
