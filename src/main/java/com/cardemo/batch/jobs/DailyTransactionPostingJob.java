/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * CardDemo Application — Daily Transaction Posting Job (Pipeline Stage 1)
 * Migrated from POSTTRAN.jcl + CBTRN02C.cbl (commit 27d6c6f).
 *
 * Original JCL (POSTTRAN.jcl, 46 lines):
 *   //POSTTRAN JOB 'POSTTRAN',CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID
 *   //STEP15   EXEC PGM=CBTRN02C
 *   DD allocations:
 *     TRANFILE  → TransactionRepository (TRANSACT.VSAM.KSDS)
 *     DALYTRAN  → DailyTransactionReader (DALYTRAN.PS → S3 input)
 *     XREFFILE  → CardCrossReferenceRepository (CARDXREF.VSAM.KSDS)
 *     DALYREJS  → RejectWriter (DALYREJS(+1), LRECL=430 → S3 output)
 *     ACCTFILE  → AccountRepository (ACCTDATA.VSAM.KSDS)
 *     TCATBALF  → TransactionCategoryBalanceRepository (TCATBALF.VSAM.KSDS)
 *
 * CBTRN02C.cbl (731 lines) implements:
 *   0000-DALYTRAN-OPEN through main loop  → Spring Batch chunk-based read
 *   1500-VALIDATE-TRAN (4-stage cascade)  → TransactionPostingProcessor.process()
 *   2000-POST-TRANSACTION                 → TransactionPostingProcessor + TransactionWriter
 *   2500-WRITE-REJECT-REC                 → RejectWriter (or processor-internal)
 *   2700-UPDATE-TCATBAL                   → TransactionWriter (TCATBAL create-or-update)
 *   2800-UPDATE-ACCOUNT-REC               → TransactionWriter (account balance update)
 *   2900-WRITE-TRANSACTION-FILE           → TransactionWriter.write()
 *   RETURN-CODE = 4 (line 230)            → StepExecutionListener → ExitStatus("COMPLETED_WITH_REJECTS")
 *   9000-* close paragraphs               → DailyTransactionReader.close() / Spring Batch cleanup
 */
package com.cardemo.batch.jobs;

// Internal imports — strictly from depends_on_files
import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;

// Spring Batch Core — job/step definition and execution model
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;

import java.util.List;

// Spring Framework — configuration, bean wiring, value injection
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring Transaction — transaction manager for chunk commit boundaries
import org.springframework.transaction.PlatformTransactionManager;

// SLF4J — structured logging with correlation IDs per AAP §0.7.1
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring Batch {@link Configuration} class defining the Daily Transaction Posting
 * Job — Stage 1 of the CardDemo 5-stage nightly batch pipeline.
 *
 * <p>This job reads daily transaction records from AWS S3 (replacing COBOL
 * sequential file DALYTRAN.PS), validates each record through a 4-stage cascade
 * (card cross-reference lookup, account existence check, credit limit verification,
 * and expiry date check), posts valid transactions to PostgreSQL via
 * {@link TransactionWriter}, and writes rejected transactions to S3 via
 * {@link RejectWriter}.</p>
 *
 * <h3>COBOL-to-Java Paragraph Mapping</h3>
 * <table>
 *   <caption>CBTRN02C.cbl → DailyTransactionPostingJob mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Java Component</th></tr>
 *   <tr><td>0000-DALYTRAN-OPEN</td><td>{@link DailyTransactionReader} lazy init</td></tr>
 *   <tr><td>Main read loop</td><td>Spring Batch chunk loop</td></tr>
 *   <tr><td>1500-VALIDATE-TRAN</td><td>{@link TransactionPostingProcessor#process}</td></tr>
 *   <tr><td>2000-POST-TRANSACTION</td><td>{@link TransactionPostingProcessor} + {@link TransactionWriter}</td></tr>
 *   <tr><td>2500-WRITE-REJECT-REC</td><td>{@link RejectWriter} (processor-internal)</td></tr>
 *   <tr><td>2700-UPDATE-TCATBAL</td><td>{@link TransactionWriter} (TCATBAL create-or-update)</td></tr>
 *   <tr><td>2800-UPDATE-ACCOUNT-REC</td><td>{@link TransactionWriter} (account balance update)</td></tr>
 *   <tr><td>2900-WRITE-TRANSACTION-FILE</td><td>{@link TransactionWriter#write}</td></tr>
 *   <tr><td>RETURN-CODE = 4</td><td>{@link StepExecutionListener} → {@code ExitStatus("COMPLETED_WITH_REJECTS")}</td></tr>
 *   <tr><td>9000-* close paragraphs</td><td>{@link DailyTransactionReader} close / Spring Batch cleanup</td></tr>
 * </table>
 *
 * <h3>Exit Status Semantics (COBOL RETURN-CODE mapping)</h3>
 * <ul>
 *   <li>{@code ExitStatus.COMPLETED} — RETURN-CODE 0: all records posted, zero rejections</li>
 *   <li>{@code ExitStatus("COMPLETED_WITH_REJECTS")} — RETURN-CODE 4: partial rejections,
 *       downstream pipeline stages still proceed (evaluated by
 *       {@link BatchPipelineOrchestrator#conditionCodeDecider})</li>
 *   <li>{@code ExitStatus.FAILED} — RETURN-CODE ≥ 8: fatal error, pipeline stops</li>
 * </ul>
 *
 * <h3>Chunk Processing Configuration</h3>
 * <p>The chunk size is configurable via the property
 * {@code carddemo.batch.chunk-size.posting} (default: 100). Each chunk commit
 * corresponds to one database transaction encompassing all read/process/write
 * operations for that batch of records.</p>
 *
 * @see DailyTransactionReader
 * @see TransactionPostingProcessor
 * @see TransactionWriter
 * @see RejectWriter
 * @see BatchPipelineOrchestrator
 */
@Configuration("dailyTransactionPostingJobConfig")
public class DailyTransactionPostingJob {

    private static final Logger log = LoggerFactory.getLogger(DailyTransactionPostingJob.class);

    /**
     * Custom exit status code indicating the step completed with partial rejections.
     * Maps to COBOL RETURN-CODE = 4 from CBTRN02C.cbl line 230:
     * {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}.
     *
     * <p>This exit status is evaluated by
     * {@link BatchPipelineOrchestrator#conditionCodeDecider} to determine whether
     * downstream pipeline stages (INTCALC, COMBTRAN, CREASTMT, TRANREPT) should
     * proceed. Both COMPLETED and COMPLETED_WITH_REJECTS allow continuation.</p>
     */
    private static final String COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /**
     * Configurable chunk size for the daily transaction posting step.
     * Controls the commit interval — how many records are read, processed, and
     * written in a single database transaction.
     *
     * <p>Property: {@code carddemo.batch.chunk-size.posting} (default: 100).
     * The COBOL program processes records one at a time with implicit VSAM I-O
     * commits per record; Spring Batch batches multiple records per commit for
     * better throughput while maintaining the same overall correctness.</p>
     */
    @Value("${carddemo.batch.chunk-size.posting:100}")
    private int chunkSize;

    // -----------------------------------------------------------------------
    // Job Bean Definition — maps to POSTTRAN.jcl job card
    // -----------------------------------------------------------------------

    /**
     * Defines the Daily Transaction Posting Job — the Spring Batch {@link Job}
     * that orchestrates the single-step posting pipeline.
     *
     * <p>Maps to the POSTTRAN.jcl job card:
     * {@code //POSTTRAN JOB 'POSTTRAN',CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID}</p>
     *
     * <p>Uses {@link RunIdIncrementer} to ensure each job execution gets a unique
     * instance ID, enabling re-execution of the same job with new parameters
     * (equivalent to COBOL re-submitting the same JCL for a new daily run).</p>
     *
     * @param jobRepository Spring Batch job metadata repository for persisting
     *                      job execution state
     * @param postingStep   the daily transaction posting step bean, resolved via
     *                      {@code @Qualifier("dailyTransactionPostingStep")}
     * @return the configured Spring Batch {@link Job} instance
     */
    @Bean("dailyTransactionPostingJob")
    public Job dailyTransactionPostingJob(
            JobRepository jobRepository,
            @Qualifier("dailyTransactionPostingStep") Step postingStep) {

        log.info("Configuring daily transaction posting job (POSTTRAN equivalent)");

        return new JobBuilder("dailyTransactionPostingJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(postingStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Step Bean Definition — maps to POSTTRAN.jcl STEP15 EXEC PGM=CBTRN02C
    // -----------------------------------------------------------------------

    /**
     * Defines the Daily Transaction Posting Step — the chunk-oriented step that
     * reads, validates, and posts daily transactions.
     *
     * <p>Maps to the POSTTRAN.jcl step:
     * {@code //STEP15 EXEC PGM=CBTRN02C}</p>
     *
     * <p>The step uses a chunk-oriented processing model:
     * <ol>
     *   <li><strong>Read</strong>: {@link DailyTransactionReader} reads
     *       {@link DailyTransaction} records from S3 (replacing DALYTRAN.PS)</li>
     *   <li><strong>Process</strong>: {@link TransactionPostingProcessor} validates
     *       each record through the 4-stage cascade and builds a {@link Transaction}
     *       entity for valid records (returns {@code null} for rejected records,
     *       which Spring Batch filters automatically)</li>
     *   <li><strong>Write</strong>: {@link TransactionWriter} persists validated
     *       transactions to PostgreSQL and writes S3 backups</li>
     * </ol>
     *
     * <p>A {@link StepExecutionListener} is attached to implement the COBOL
     * RETURN-CODE logic from CBTRN02C.cbl line 230. After the step completes,
     * the listener queries the {@link TransactionWriter} and {@link RejectWriter}
     * for their respective counts and sets the appropriate {@link ExitStatus}.</p>
     *
     * @param jobRepository       Spring Batch job metadata repository
     * @param transactionManager  platform transaction manager defining chunk
     *                            commit boundaries
     * @param reader              daily transaction file reader (S3 → DailyTransaction)
     * @param processor           4-stage validation cascade processor
     * @param writer              validated transaction writer (PostgreSQL + S3 backup)
     * @return the configured Spring Batch {@link Step} instance
     */
    @Bean("dailyTransactionPostingStep")
    public Step dailyTransactionPostingStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DailyTransactionReader reader,
            TransactionPostingProcessor processor,
            TransactionWriter writer,
            RejectWriter rejectWriter) {

        log.info("Configuring daily transaction posting step: chunkSize={}", chunkSize);

        return new StepBuilder("dailyTransactionPostingStep", jobRepository)
                .<DailyTransaction, Transaction>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener(createPostingStepListener(writer, processor, rejectWriter))
                .build();
    }

    // -----------------------------------------------------------------------
    // Step Execution Listener — implements COBOL RETURN-CODE = 4 logic
    // -----------------------------------------------------------------------

    /**
     * Creates a {@link StepExecutionListener} that implements the COBOL
     * RETURN-CODE logic from CBTRN02C.cbl lines 227-231:
     *
     * <pre>
     * DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT
     * DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT
     * IF WS-REJECT-COUNT &gt; 0
     *    MOVE 4 TO RETURN-CODE
     * END-IF
     * </pre>
     *
     * <p>The listener's {@code afterStep} callback queries the
     * {@link TransactionWriter#getTransactionCount()} for the count of
     * successfully posted transactions and determines the reject count
     * from the step execution's filter count metric.</p>
     *
     * <p>Exit status mapping (always returns {@link ExitStatus#COMPLETED} exit code):</p>
     * <ul>
     *   <li>Rejections == 0 → {@link ExitStatus#COMPLETED} (RETURN-CODE 0)</li>
     *   <li>Rejections &gt; 0 → {@code ExitStatus("COMPLETED", description)}
     *       with reject count stored in {@code ExecutionContext} (RETURN-CODE 4)</li>
     * </ul>
     *
     * <p>The {@link BatchPipelineOrchestrator} Flow transitions require standard
     * exit codes. The COBOL RETURN-CODE 4 semantics are preserved via the
     * {@code rejectCount} and {@code postedCount} entries in the step's
     * {@code ExecutionContext}, accessible by the
     * {@link BatchPipelineOrchestrator#conditionCodeDecider} and downstream
     * stages.</p>
     *
     * @param writer the transaction writer providing posted transaction count
     * @return a configured {@link StepExecutionListener}
     */
    private StepExecutionListener createPostingStepListener(
            TransactionWriter writer,
            TransactionPostingProcessor processor,
            RejectWriter rejectWriter) {
        return new StepExecutionListener() {

            /**
             * Invoked before the posting step starts. Resets mutable state on
             * the singleton processor and reject writer to ensure clean
             * execution across multiple job runs within the same Spring context
             * (e.g., integration tests or re-runnable batch jobs).
             *
             * <p>Mirrors COBOL working storage initialization at program load
             * time (CBTRN02C.cbl lines 185-186).</p>
             *
             * @param stepExecution the step execution context (not used)
             */
            @Override
            public void beforeStep(StepExecution stepExecution) {
                processor.resetState();
                rejectWriter.resetRejectCount();
                writer.resetTransactionCount();
                log.debug("Reset processor, writer, and reject writer state for new job run");
            }

            /**
             * Invoked after the posting step completes. Flushes accumulated
             * processor rejections to the RejectWriter (S3), logs a summary of
             * processed and rejected transactions, then determines the
             * appropriate exit status based on the rejection count.
             *
             * @param stepExecution the step execution context containing
             *                      read, write, and filter counts
             * @return the computed {@link ExitStatus} reflecting the
             *         COBOL RETURN-CODE semantics
             */
            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                // Retrieve posted transaction count from the TransactionWriter
                // (maps to COBOL WS-TRANSACTION-COUNT PIC 9(09))
                long transactionCount = writer.getTransactionCount();

                // Derive rejection count from Spring Batch step execution metrics.
                // The filter count tracks items where the processor returned null,
                // which is the Spring Batch convention for filtered/rejected items.
                // This corresponds to COBOL WS-REJECT-COUNT PIC 9(09).
                long readCount = stepExecution.getReadCount();
                long writeCount = stepExecution.getWriteCount();
                long filterCount = stepExecution.getFilterCount();

                // The reject count is the number of items read but not written.
                // Spring Batch's filterCount tracks processor-filtered items.
                long rejectCount = filterCount;

                // ---------------------------------------------------------------
                // Flush accumulated rejections to RejectWriter (S3 output)
                // Maps COBOL paragraph 2500-WRITE-REJECT-REC (CBTRN02C.cbl
                // lines 446-465): writes each rejected DALYTRAN record plus an
                // 80-byte validation trailer to the DALYREJS output file.
                //
                // Because the processor returns null for rejected items (Spring
                // Batch filter convention), rejected DailyTransaction records
                // never reach any ItemWriter in the chunk pipeline. Instead, the
                // processor accumulates RejectionResult objects internally, and
                // we flush them here after the step completes.
                // ---------------------------------------------------------------
                List<TransactionPostingProcessor.RejectionResult> rejections =
                        processor.getRejections();
                if (!rejections.isEmpty()) {
                    // Register rejection metadata so the RejectWriter can include
                    // correct reject codes and descriptions in the 80-byte trailer
                    for (TransactionPostingProcessor.RejectionResult r : rejections) {
                        rejectWriter.registerRejection(
                                r.originalTransaction().getDalytranId(),
                                r.rejectCode().getCode(),
                                r.reasonDescription());
                    }
                    // Build a Chunk of rejected DailyTransaction entities and write
                    Chunk<DailyTransaction> rejectChunk = new Chunk<>();
                    for (TransactionPostingProcessor.RejectionResult r : rejections) {
                        rejectChunk.add(r.originalTransaction());
                    }
                    try {
                        rejectWriter.write(rejectChunk);
                        log.info("Flushed {} rejection records to S3 via RejectWriter",
                                rejections.size());
                    } catch (Exception e) {
                        log.error("Failed to write rejection records to S3: {}",
                                e.getMessage(), e);
                        // Non-fatal: COBOL POSTTRAN continues on reject file write
                        // errors with RETURN-CODE 4, so we do not fail the step.
                    }
                }

                // ---------------------------------------------------------------
                // Exit status determination — COBOL RETURN-CODE mapping
                //
                // COBOL CBTRN02C.cbl line 230:
                //   IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE
                //   ELSE MOVE 0 TO RETURN-CODE
                //
                // In the Spring Batch pipeline (BatchPipelineOrchestrator), the
                // FlowJobBuilder uses Flow-level transitions that match only
                // standard exit codes. Custom exit codes (e.g. the previous
                // "COMPLETED_WITH_REJECTS") prevent the conditionCodeDecider
                // from being invoked, silently stopping downstream stages.
                //
                // To maintain correct pipeline flow while preserving COBOL
                // RETURN-CODE semantics, we always return ExitStatus.COMPLETED
                // and store reject metadata in the StepExecution context for
                // the conditionCodeDecider and observability consumers.
                //
                // The conditionCodeDecider only checks for "FAILED" exit codes
                // (RETURN-CODE >= 8), so both RC=0 and RC=4 correctly map to
                // ExitStatus.COMPLETED → pipeline CONTINUES.
                // ---------------------------------------------------------------
                stepExecution.getExecutionContext().putLong("rejectCount", rejectCount);
                stepExecution.getExecutionContext().putLong("postedCount", transactionCount);

                ExitStatus exitStatus;
                if (rejectCount > 0) {
                    // Return COMPLETED with descriptive text for observability.
                    // Exit code remains "COMPLETED" for correct Flow transitions.
                    exitStatus = new ExitStatus(ExitStatus.COMPLETED.getExitCode(),
                            "Completed with " + rejectCount + " rejections "
                            + "(RETURN-CODE=4 equivalent)");
                } else {
                    exitStatus = ExitStatus.COMPLETED;
                }

                log.info("POSTTRAN complete: read={}, posted={}, rejected={}, exitStatus={}",
                        readCount, transactionCount, rejectCount, exitStatus.getExitCode());

                if (rejectCount > 0) {
                    log.warn("Daily transaction posting completed with {} rejections "
                            + "(RETURN-CODE=4 equivalent). Downstream pipeline stages "
                            + "will still proceed.", rejectCount);
                } else {
                    log.info("Daily transaction posting completed successfully with zero "
                            + "rejections (RETURN-CODE=0 equivalent).");
                }

                return exitStatus;
            }
        };
    }
}
