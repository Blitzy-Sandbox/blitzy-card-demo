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

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.processor.PostingResult;
import com.carddemo.batch.processor.TransactionPostingProcessor;
import com.carddemo.batch.reader.DailyTransactionItemReader;
import com.carddemo.batch.writer.PostingResultWriter;
import com.carddemo.config.BatchConfig;
import com.carddemo.entity.DailyTransaction;

/**
 * Spring Batch configuration for the daily-transaction posting job, the Java
 * realization of the COBOL batch program {@code CBTRN02C} driven by JCL job
 * {@code POSTTRAN} (members {@code app/cbl/CBTRN02C.cbl} and
 * {@code app/jcl/POSTTRAN.jcl} at source commit {@code 27d6c6f}). This is the
 * primary batch pipeline and the Gate&nbsp;1 / Gate&nbsp;4 byte-equivalence
 * target of the migration.
 *
 * <p>The legacy program is a single, self-contained sequential pass: it opens
 * the daily-transaction input ({@code DALYTRAN}) and, for every record, validates
 * it and then either posts it to the transaction master ({@code TRANFILE}) and
 * updates the affected category balance ({@code TCATBALF}) and account
 * ({@code ACCTFILE}), or writes a 430-byte reject record to the rejects dataset
 * ({@code DALYREJS}). The {@code POSTTRAN} job runs this as one step
 * ({@code STEP15 EXEC PGM=CBTRN02C}) with no PARM and no condition code.</p>
 *
 * <p>The {@code PERFORM UNTIL END-OF-FILE} read&rarr;validate&rarr;(post|reject)
 * loop maps to a single chunk-oriented step typed
 * {@code <}{@link DailyTransaction}{@code , }{@link PostingResult}{@code >}:</p>
 * <ul>
 *   <li>{@link DailyTransactionItemReader} performs the sequential staging read
 *       of the {@code daily_transaction} table (the {@code DALYTRAN} PS input),
 *       ascending by {@code tranId}.</li>
 *   <li>{@link TransactionPostingProcessor} runs the ordered validation cascade
 *       and, for a valid record, performs the atomic category-balance and account
 *       updates and builds the transaction to persist. It emits a
 *       {@link PostingResult} for every input record (never {@code null}): a
 *       {@link PostingResult.Posted} carrying the posted transaction, or a
 *       {@link PostingResult.Rejected} carrying the 350-byte record image and the
 *       reject reason.</li>
 *   <li>{@link PostingResultWriter} routes each result to its durable sink:
 *       posted transactions to the PostgreSQL transaction master and rejected
 *       records to the byte-exact 430-byte {@code DALYREJS}-equivalent S3 object.
 *       Being an {@code ItemStream}, it is registered as a stream automatically
 *       by the step builder so its per-run open/flush lifecycle executes.</li>
 * </ul>
 *
 * <p>The chunk commit interval is bound from {@code carddemo.batch.chunk-size}
 * through {@link BatchConfig.BatchTuningProperties}, and the supplied
 * {@link PlatformTransactionManager} establishes the per-chunk commit boundary
 * (rollback on any exception), reproducing the CICS {@code SYNCPOINT} / implicit
 * batch-commit unit of work so the category-balance update, account update and
 * transaction insert of a chunk commit atomically.</p>
 *
 * <p>The COBOL tail logic {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE} is
 * reproduced as a typed step exit status rather than a numeric return code: when
 * the step completes with at least one rejected record it exits with
 * {@link #EXIT_CODE_COMPLETED_WITH_REJECTS}, which the pipeline orchestrator's
 * {@code JobExecutionDecider} maps to the tolerated {@code RC=4} path; otherwise
 * the step retains {@link ExitStatus#COMPLETED}. A single-step job propagates the
 * step exit status to the job exit status.</p>
 *
 * <p>The job is wired with the fluent {@link JobBuilder} / {@link StepBuilder}
 * API (Spring Batch&nbsp;5); {@code JobRepository}, {@code JobLauncher} and the
 * batch {@code PlatformTransactionManager} are auto-configured by Spring Boot
 * (no {@code @EnableBatchProcessing}). The job is never auto-run on startup
 * ({@code spring.batch.job.enabled=false}); it is launched explicitly.</p>
 */
@Configuration
public class PostTransactionJobConfig {

    /** Canonical name of the daily-transaction posting job. */
    static final String JOB_NAME = "postTransactionJob";

    /** Canonical name of the single posting step. */
    static final String STEP_NAME = "postTransactionStep";

    /**
     * Step/job exit code surfaced when the run completed but rejected one or more
     * daily transactions. Reproduces the COBOL {@code MOVE 4 TO RETURN-CODE} of
     * {@code CBTRN02C} as a typed exit status for the pipeline orchestrator's
     * decider.
     */
    static final String EXIT_CODE_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DailyTransactionItemReader dailyTransactionItemReader;
    private final TransactionPostingProcessor transactionPostingProcessor;
    private final PostingResultWriter postingResultWriter;
    private final BatchConfig.BatchTuningProperties batchTuningProperties;

    /**
     * Creates the posting-job configuration with all collaborators injected by the
     * container.
     *
     * @param jobRepository               the Spring Batch job repository backing
     *                                    the job and step metadata
     * @param transactionManager          the platform transaction manager
     *                                    providing the per-chunk commit boundary
     *                                    (rollback on any exception)
     * @param dailyTransactionItemReader  the sequential staging reader over the
     *                                    {@code daily_transaction} table (ascending
     *                                    {@code tranId}), realizing the
     *                                    {@code DALYTRAN} input scan
     * @param transactionPostingProcessor the validate-and-post processor emitting a
     *                                    {@link PostingResult} for every record
     * @param postingResultWriter         the composite writer that fans posted
     *                                    transactions and rejected records to their
     *                                    durable sinks
     * @param batchTuningProperties       the externalized batch tuning supplying
     *                                    the chunk commit interval
     */
    public PostTransactionJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DailyTransactionItemReader dailyTransactionItemReader,
            TransactionPostingProcessor transactionPostingProcessor,
            PostingResultWriter postingResultWriter,
            BatchConfig.BatchTuningProperties batchTuningProperties) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dailyTransactionItemReader = dailyTransactionItemReader;
        this.transactionPostingProcessor = transactionPostingProcessor;
        this.postingResultWriter = postingResultWriter;
        this.batchTuningProperties = batchTuningProperties;
    }

    /**
     * The single chunk-oriented posting step that reads, validates/posts and
     * fans out each daily transaction.
     *
     * <p>The commit interval is taken from
     * {@link BatchConfig.BatchTuningProperties#getChunkSize()} and the injected
     * {@link PlatformTransactionManager} provides the chunk commit boundary,
     * rolling back the whole chunk on any exception. A
     * {@link RejectCountingStepListener} counts the rejected results as each chunk
     * is written and, when the step completes with rejects, surfaces the
     * {@link #EXIT_CODE_COMPLETED_WITH_REJECTS} exit status.</p>
     *
     * @return the configured posting step
     */
    @Bean
    public Step postTransactionStep() {
        RejectCountingStepListener rejectCountingListener = new RejectCountingStepListener();
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, PostingResult>chunk(batchTuningProperties.getChunkSize(), transactionManager)
                .reader(dailyTransactionItemReader)
                .processor(transactionPostingProcessor)
                .writer(postingResultWriter)
                .listener((ItemWriteListener<PostingResult>) rejectCountingListener)
                .listener((StepExecutionListener) rejectCountingListener)
                .build();
    }

    /**
     * The daily-transaction posting job, composed of the single posting step.
     * Referenced by name ({@value #JOB_NAME}) by the pipeline orchestrator.
     *
     * @param postTransactionStep the posting step
     * @return the configured job
     */
    @Bean
    public Job postTransactionJob(Step postTransactionStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(postTransactionStep)
                .build();
    }

    /**
     * Step listener that reproduces the COBOL reject-count return-code behavior of
     * {@code CBTRN02C} ({@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}).
     *
     * <p>It tallies the {@link PostingResult.Rejected} results of each successfully
     * written chunk and, when the step finishes in {@link BatchStatus#COMPLETED}
     * with a non-zero reject tally, replaces the step exit status with
     * {@link #EXIT_CODE_COMPLETED_WITH_REJECTS}. The tally is counted at
     * {@code afterWrite} so a chunk that rolls back before its write completes is
     * not counted, and it is reset at {@code beforeStep} so a re-execution starts
     * from zero. When the step did not complete (for example a write error caused
     * a failure) the exit status is left unchanged so the failure is not masked.</p>
     */
    static final class RejectCountingStepListener
            implements StepExecutionListener, ItemWriteListener<PostingResult> {

        private final AtomicLong rejectCount = new AtomicLong();

        /**
         * Resets the reject tally at the start of the step so a re-execution does
         * not accumulate counts from a prior run.
         *
         * @param stepExecution the current step execution
         */
        @Override
        public void beforeStep(StepExecution stepExecution) {
            rejectCount.set(0L);
        }

        /**
         * Adds the number of {@link PostingResult.Rejected} results in the chunk to
         * the running reject tally, invoked only after the chunk is durably written.
         *
         * @param items the chunk of posting results just written
         */
        @Override
        public void afterWrite(Chunk<? extends PostingResult> items) {
            long rejectedInChunk = items.getItems().stream()
                    .filter(result -> result instanceof PostingResult.Rejected)
                    .count();
            if (rejectedInChunk > 0L) {
                rejectCount.addAndGet(rejectedInChunk);
            }
        }

        /**
         * Surfaces the reject-aware exit status: when the step completed and at
         * least one record was rejected, the exit status becomes
         * {@link #EXIT_CODE_COMPLETED_WITH_REJECTS}; otherwise the existing exit
         * status is preserved.
         *
         * @param stepExecution the completed step execution
         * @return the reject-aware exit status, or {@code null} to leave the
         *         step's exit status unchanged
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            if (stepExecution.getStatus() == BatchStatus.COMPLETED && rejectCount.get() > 0L) {
                return new ExitStatus(EXIT_CODE_COMPLETED_WITH_REJECTS);
            }
            return null;
        }
    }
}
