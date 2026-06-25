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
package com.carddemo.batch.job;

import com.carddemo.batch.processor.TransactionCombineComparator;
import com.carddemo.config.BatchConfig;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the combine-transactions job, the Java
 * realization of the legacy JCL job {@code COMBTRAN} (member
 * {@code app/jcl/COMBTRAN.jcl} at source commit {@code 27d6c6f}). The legacy job
 * has no COBOL program: it is a pure DFSORT + IDCAMS REPRO pipeline that merges
 * the prior transaction set with the system-generated interest transactions,
 * orders the union by transaction id, and loads the result into the transaction
 * master.
 *
 * <p>The two legacy steps map to a single chunk-oriented Spring Batch step:</p>
 * <ul>
 *   <li>{@code STEP05R EXEC PGM=SORT} &mdash; the concatenation of
 *       {@code TRANSACT.BKUP(0)} and {@code SYSTRAN(0)} sorted by
 *       {@code SORT FIELDS=(TRAN-ID,A)} ({@code SYMNAMES TRAN-ID,1,16,CH}) is
 *       realized by reading the combined transaction set and ordering it with
 *       the injected {@link TransactionCombineComparator}. That comparator
 *       reproduces DFSORT's single ascending key on {@code TRAN-ID} with a
 *       stable order that retains duplicate keys. The reader returns the items
 *       already sorted so the master load order matches the DFSORT output.</li>
 *   <li>{@code STEP10 EXEC PGM=IDCAMS} &mdash; {@code REPRO} of the combined,
 *       sorted dataset into {@code TRANSACT.VSAM.KSDS} is realized by persisting
 *       the ordered items through {@link TransactionRepository} (the chunk
 *       writer issues {@code saveAll}, preserving the supplied order).</li>
 * </ul>
 *
 * <p>The combined transaction set is sourced from the {@code transactions}
 * master via {@link TransactionRepository#findAll()}; the source-strategy choice
 * is documented in {@code DECISION_LOG.md}. The chunk commit interval is bound
 * from {@code carddemo.batch.chunk-size} through
 * {@link BatchConfig.BatchTuningProperties}. The job is wired with the fluent
 * {@link JobBuilder} / {@link StepBuilder} API and is never auto-run on startup
 * ({@code spring.batch.job.enabled=false}); it is triggered explicitly by the
 * pipeline orchestrator.</p>
 */
@Configuration
public class CombineTransactionsJobConfig {

    /** Canonical name of the combine-transactions job. */
    static final String JOB_NAME = "combineTransactionsJob";

    /** Canonical name of the single combine step. */
    static final String STEP_NAME = "combineTransactionsStep";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionCombineComparator transactionCombineComparator;
    private final TransactionRepository transactionRepository;
    private final BatchConfig.BatchTuningProperties batchTuningProperties;

    /**
     * Creates the combine-transactions job configuration with all collaborators
     * injected by the container.
     *
     * @param jobRepository                the Spring Batch job repository backing
     *                                     the job and step metadata
     * @param transactionManager           the platform transaction manager that
     *                                     provides the per-chunk commit boundary
     *                                     (rollback on any exception)
     * @param transactionCombineComparator the ascending, stable,
     *                                     duplicate-retaining {@code TRAN-ID}
     *                                     comparator that reproduces the DFSORT
     *                                     {@code SORT FIELDS=(TRAN-ID,A)} ordering
     * @param transactionRepository        the repository over the
     *                                     {@code transactions} master used to read
     *                                     the combined set and load the sorted
     *                                     result ({@code saveAll})
     * @param batchTuningProperties        the externalized batch tuning supplying
     *                                     the chunk commit interval
     */
    public CombineTransactionsJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionCombineComparator transactionCombineComparator,
            TransactionRepository transactionRepository,
            BatchConfig.BatchTuningProperties batchTuningProperties) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionCombineComparator = transactionCombineComparator;
        this.transactionRepository = transactionRepository;
        this.batchTuningProperties = batchTuningProperties;
    }

    /**
     * Reader that gathers the combined transaction set and returns it ordered by
     * the injected {@link TransactionCombineComparator}.
     *
     * <p>The full combined set is read once via
     * {@link TransactionRepository#findAll()} and sorted in memory with the
     * comparator (ascending {@code TRAN-ID}, stable, duplicate keys retained),
     * reproducing the DFSORT materialize-then-sort behavior of {@code STEP05R}.
     * Because the gather and sort run when the reader is created and the reader
     * holds the resulting cursor, the bean is {@link StepScope step-scoped} so
     * each step execution acquires a freshly gathered and sorted snapshot.</p>
     *
     * @return a reader yielding the combined transactions in ascending
     *         {@code TRAN-ID} order, one item at a time
     */
    @Bean
    @StepScope
    public ItemReader<Transaction> combineTransactionsReader() {
        List<Transaction> combined = new ArrayList<>(transactionRepository.findAll());
        combined.sort(transactionCombineComparator);
        return new ListItemReader<>(combined);
    }

    /**
     * Writer that loads the sorted transactions into the {@code transactions}
     * master, realizing the {@code STEP10} IDCAMS {@code REPRO}.
     *
     * <p>No method name is configured, so {@link RepositoryItemWriter} persists
     * each chunk with {@link TransactionRepository#saveAll(Iterable)}, which
     * preserves the iteration order supplied by the reader; the writer performs
     * no reordering.</p>
     *
     * @return a repository-backed writer that saves each chunk via {@code saveAll}
     */
    @Bean
    public RepositoryItemWriter<Transaction> combineTransactionsWriter() {
        RepositoryItemWriter<Transaction> writer = new RepositoryItemWriter<>();
        writer.setRepository(transactionRepository);
        return writer;
    }

    /**
     * The single chunk-oriented step that reads the pre-sorted combined set and
     * loads it into the transaction master.
     *
     * <p>The chunk commit interval is taken from
     * {@link BatchConfig.BatchTuningProperties#getChunkSize()} and the supplied
     * {@link PlatformTransactionManager} provides the commit boundary, rolling
     * back the chunk on any exception.</p>
     *
     * @param combineTransactionsReader the step-scoped, pre-sorted reader
     * @param combineTransactionsWriter the {@code saveAll}-backed master loader
     * @return the configured combine step
     */
    @Bean
    public Step combineTransactionsStep(
            ItemReader<Transaction> combineTransactionsReader,
            RepositoryItemWriter<Transaction> combineTransactionsWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Transaction, Transaction>chunk(batchTuningProperties.getChunkSize(), transactionManager)
                .reader(combineTransactionsReader)
                .writer(combineTransactionsWriter)
                .build();
    }

    /**
     * The combine-transactions job, composed of the single combine step.
     *
     * @param combineTransactionsStep the combine step
     * @return the configured job
     */
    @Bean
    public Job combineTransactionsJob(Step combineTransactionsStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(combineTransactionsStep)
                .build();
    }
}
