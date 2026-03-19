/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * CardDemo Application — Interest Calculation Job (Pipeline Stage 2)
 * Migrated from INTCALC.jcl + CBACT04C.cbl (commit 27d6c6f).
 *
 * Original JCL (INTCALC.jcl, 45 lines):
 *   //INTCALC  JOB MSGLEVEL=(1,0),CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID
 *   //STEP15   EXEC PGM=CBACT04C,PARM='2022071800'
 *   DD allocations:
 *     TCATBALF  → TransactionCategoryBalanceRepository (TCATBALF.VSAM.KSDS)
 *     XREFFILE  → CardCrossReferenceRepository (CARDXREF.VSAM.KSDS)
 *     XREFFIL1  → CardCrossReferenceRepository (CARDAIX.AIX.PATH, alt index)
 *     ACCTFILE  → AccountRepository (ACCTDATA.VSAM.KSDS)
 *     DISCGRP   → DisclosureGroupRepository (DISCGRP.VSAM.KSDS)
 *     TRANSACT  → S3 output (SYSTRAN(+1), GDG generation, LRECL=350)
 *
 * CBACT04C.cbl (653 lines) paragraph mapping:
 *   PROCEDURE DIVISION USING EXTERNAL-PARMS  → Job parameter "parmDate" (10 chars)
 *   0000-TCATBALF-OPEN + 1000-TCATBALF-GET-NEXT → interestTcatbalReader
 *   Account break detection                     → InterestCalculationProcessor state
 *   1050-UPDATE-ACCOUNT                         → InterestCalculationProcessor
 *   1100-GET-ACCT-DATA                          → Processor → AccountRepository
 *   1110-GET-XREF-DATA                          → Processor → CardCrossReferenceRepository
 *   1200-GET-INTEREST-RATE + DEFAULT fallback   → Processor → DisclosureGroupRepository
 *   1300-COMPUTE-INTEREST  (bal × rate) / 1200  → Processor BigDecimal computation
 *   1300-B-WRITE-TX                             → Processor returns Transaction
 *   1400-COMPUTE-FEES (stub)                    → Not implemented (COBOL stub)
 */
package com.cardemo.batch.jobs;

// Internal imports — strictly from depends_on_files
import com.cardemo.batch.processors.InterestCalculationProcessor;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;

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

// Spring Batch Infrastructure — item reading and writing
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;

// Spring Framework — configuration, bean wiring, value injection
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring Data — sorting abstraction for reader configuration
import org.springframework.data.domain.Sort;

// Spring Transaction — transaction manager for chunk commit boundaries
import org.springframework.transaction.PlatformTransactionManager;

// AWS SDK v2 — S3 client for GDG replacement output
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// SLF4J — structured logging with correlation IDs per AAP §0.7.1
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Java Standard Library
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spring Batch {@link Configuration} class defining the Interest Calculation
 * Job — Stage 2 of the CardDemo 5-stage nightly batch pipeline.
 *
 * <p>This job reads all {@link TransactionCategoryBalance} records from
 * PostgreSQL (replacing COBOL sequential TCATBALF.VSAM.KSDS file), processes
 * each record through {@link InterestCalculationProcessor} which computes
 * interest using the formula {@code (balance × rate) / 1200} with
 * {@code BigDecimal} precision and {@code RoundingMode.HALF_EVEN} (banker's
 * rounding), and writes the resulting system-generated interest
 * {@link Transaction} records to both PostgreSQL and S3 backup.</p>
 *
 * <h3>COBOL-to-Java Paragraph Mapping</h3>
 * <table>
 *   <caption>CBACT04C.cbl → InterestCalculationJob mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Java Component</th></tr>
 *   <tr><td>PROCEDURE DIVISION USING EXTERNAL-PARMS</td>
 *       <td>Job parameter {@code parmDate} via {@code JobParameters}</td></tr>
 *   <tr><td>0000-TCATBALF-OPEN + 1000-TCATBALF-GET-NEXT</td>
 *       <td>{@link #interestTcatbalReader} (RepositoryItemReader)</td></tr>
 *   <tr><td>Account break detection</td>
 *       <td>{@link InterestCalculationProcessor} internal state</td></tr>
 *   <tr><td>1050-UPDATE-ACCOUNT</td>
 *       <td>{@link InterestCalculationProcessor} account update</td></tr>
 *   <tr><td>1100-GET-ACCT-DATA</td>
 *       <td>Processor → AccountRepository.findById()</td></tr>
 *   <tr><td>1110-GET-XREF-DATA</td>
 *       <td>Processor → CardCrossReferenceRepository</td></tr>
 *   <tr><td>1200-GET-INTEREST-RATE + DEFAULT fallback</td>
 *       <td>Processor → DisclosureGroupRepository</td></tr>
 *   <tr><td>1300-COMPUTE-INTEREST</td>
 *       <td>Processor: {@code (bal × rate) / 1200} with BigDecimal</td></tr>
 *   <tr><td>1300-B-WRITE-TX</td>
 *       <td>Processor returns {@link Transaction}</td></tr>
 *   <tr><td>1400-COMPUTE-FEES (stub)</td>
 *       <td>Not implemented (COBOL stub: "To be implemented")</td></tr>
 * </table>
 *
 * <h3>JCL PARM Parameter Mapping</h3>
 * <p>The COBOL LINKAGE SECTION defines {@code PARM-DATE PIC X(10)} which
 * receives the JCL PARM value {@code '2022071800'} (format YYYYMMDDSS).
 * In the Java implementation, this maps to the Spring Batch
 * {@code JobParameters.getString("parmDate")} which the
 * {@link InterestCalculationProcessor} reads in its {@code @BeforeStep}
 * callback. The date prefix is used for generating transaction IDs in
 * format {@code {parmDate}-{5-digit-suffix}}.</p>
 *
 * <h3>S3 Backup (GDG Replacement)</h3>
 * <p>System-generated interest transactions are backed up to S3 at key
 * {@code batch-output/system-transactions/SYSTRAN-{timestamp}.txt},
 * replacing the COBOL JCL DD statement
 * {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} GDG generation output.</p>
 *
 * @see InterestCalculationProcessor
 * @see TransactionCategoryBalance
 * @see Transaction
 * @see TransactionCategoryBalanceRepository
 * @see TransactionRepository
 * @see BatchPipelineOrchestrator
 */
@Configuration("interestCalculationJobConfig")
public class InterestCalculationJob {

    private static final Logger log = LoggerFactory.getLogger(InterestCalculationJob.class);

    /**
     * Timestamp formatter for S3 backup file naming. Uses compact format
     * {@code yyyyMMddHHmmss} to generate unique object keys per write
     * operation, replacing the GDG generation numbering scheme.
     */
    private static final DateTimeFormatter S3_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Configurable chunk size for the interest calculation step.
     * Controls the commit interval — how many {@link TransactionCategoryBalance}
     * records are read, processed, and written per database transaction.
     *
     * <p>Property: {@code carddemo.batch.chunk-size.interest} (default: 50).
     * The COBOL program processes records one at a time with implicit VSAM I-O
     * commits per record; Spring Batch batches multiple records per commit for
     * better throughput while maintaining the same overall correctness.</p>
     */
    @Value("${carddemo.batch.chunk-size.interest:50}")
    private int chunkSize;

    /**
     * S3 bucket name for batch output files. Replaces the COBOL GDG base
     * definition for system transaction output. Defaults to
     * {@code carddemo-batch-output}; configurable per environment.
     */
    @Value("${carddemo.s3.output-bucket:carddemo-batch-output}")
    private String outputBucket;

    /**
     * Accumulates formatted interest transaction records across all chunks for
     * a single S3 write in the afterStep listener. Replaces the per-chunk
     * S3 upload that caused data loss when chunk 2 overwrote chunk 1 under
     * the same timestamp key. Thread-safe for single-threaded chunk processing.
     */
    private final List<String> s3BackupAccumulator = new ArrayList<>(256);

    // -----------------------------------------------------------------------
    // ItemReader Bean — replaces CBACT04C 1000-TCATBALF-GET-NEXT sequential
    // read of TCATBALF.VSAM.KSDS records
    // -----------------------------------------------------------------------

    /**
     * Defines the TCATBAL reader for the interest calculation step.
     *
     * <p>Reads all {@link TransactionCategoryBalance} records from PostgreSQL
     * via Spring Data JPA pagination, sorted by account ID ascending. The
     * account-ordered sort is <strong>critical</strong> for the
     * {@link InterestCalculationProcessor}'s account break detection logic,
     * which mirrors the COBOL sequential file processing of TCATBALF.VSAM.KSDS
     * (paragraph {@code 1000-TCATBALF-GET-NEXT}).</p>
     *
     * <p>Maps to CBACT04C.cbl paragraphs:
     * <ul>
     *   <li>{@code 0000-TCATBALF-OPEN} — JPA session auto-managed</li>
     *   <li>{@code 1000-TCATBALF-GET-NEXT} — {@code findAll(Pageable)}</li>
     * </ul>
     *
     * @param repository the TransactionCategoryBalance repository for
     *                   paginated data access
     * @return configured {@link RepositoryItemReader} sorted by
     *         {@code id.acctId} ascending with page size 100
     */
    @Bean("interestTcatbalReader")
    public RepositoryItemReader<TransactionCategoryBalance> interestTcatbalReader(
            TransactionCategoryBalanceRepository repository) {

        log.info("Configuring TCATBAL reader: sorted by id.acctId ASC, pageSize=100");

        return new RepositoryItemReaderBuilder<TransactionCategoryBalance>()
                .name("interestTcatbalReader")
                .repository(repository)
                .methodName("findAll")
                .arguments(Collections.emptyList())
                .sorts(Map.of("id.acctId", Sort.Direction.ASC))
                .pageSize(100)
                .build();
    }

    // -----------------------------------------------------------------------
    // ItemWriter Bean — replaces CBACT04C 1300-B-WRITE-TX and JCL SYSTRAN DD
    // -----------------------------------------------------------------------

    /**
     * Defines the interest transaction writer that persists system-generated
     * interest transactions to both PostgreSQL and S3.
     *
     * <p><strong>PostgreSQL persistence</strong> (primary): Uses
     * {@link TransactionRepository#saveAll(Iterable)} to batch-persist
     * interest transactions, replacing COBOL
     * {@code WRITE FD-TRANSACT-REC FROM TRAN-RECORD} in paragraph
     * {@code 1300-B-WRITE-TX}.</p>
     *
     * <p><strong>S3 backup</strong> (secondary): Writes a pipe-delimited
     * backup file to S3 at key
     * {@code batch-output/system-transactions/SYSTRAN-{timestamp}.txt},
     * replacing the GDG output
     * {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1),LRECL=350} from the JCL.</p>
     *
     * <p>The {@link InterestCalculationProcessor} handles the account
     * update logic internally (adding total interest to
     * {@code ACCT-CURR-BAL} and resetting cycle fields), so the writer
     * only needs to persist the generated Transaction entities.</p>
     *
     * @param transactionRepository repository for persisting Transaction
     *                              entities to PostgreSQL
     * @param s3Client              AWS S3 client for backup file upload
     *                              (provided by AwsConfig, LocalStack-aware)
     * @return configured {@link ItemWriter} for Transaction entities
     */
    @Bean("interestTransactionWriter")
    public ItemWriter<Transaction> interestTransactionWriter(
            TransactionRepository transactionRepository,
            S3Client s3Client) {

        return (Chunk<? extends Transaction> chunk) -> {
            List<? extends Transaction> items = chunk.getItems();

            if (items.isEmpty()) {
                log.debug("No interest transactions to write in this chunk");
                return;
            }

            // Step 1: Persist to PostgreSQL
            // Replaces WRITE FD-TRANSACT-REC in paragraph 1300-B-WRITE-TX
            transactionRepository.saveAll(items);
            log.debug("Persisted {} interest transactions to PostgreSQL", items.size());

            // Step 2: Accumulate formatted records for S3 backup.
            // The actual S3 upload happens ONCE in the afterStep listener to
            // prevent chunk-boundary overwrites (Bug fix: previously each chunk
            // wrote to the same S3 key, causing chunk N+1 to overwrite chunk N).
            for (Transaction txn : items) {
                s3BackupAccumulator.add(formatTransactionRecord(txn));
            }
            log.debug("Accumulated {} records for S3 backup (total: {})",
                    items.size(), s3BackupAccumulator.size());
        };
    }

    // -----------------------------------------------------------------------
    // Job Bean Definition — maps to INTCALC.jcl job card
    // -----------------------------------------------------------------------

    /**
     * Defines the Interest Calculation Job — the Spring Batch {@link Job}
     * that orchestrates the single-step interest calculation pipeline.
     *
     * <p>Maps to the INTCALC.jcl job card:
     * {@code //INTCALC JOB MSGLEVEL=(1,0),CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID}
     * </p>
     *
     * <p>Uses {@link RunIdIncrementer} to ensure each job execution gets a
     * unique instance ID, enabling re-execution of the same job with new
     * parameters (equivalent to COBOL re-submitting the same JCL for a new
     * nightly run). The job accepts a {@code parmDate} parameter (10-char
     * string in YYYYMMDDSS format) via {@code JobParameters}, which the
     * {@link InterestCalculationProcessor} reads in its {@code @BeforeStep}
     * callback for transaction ID generation.</p>
     *
     * @param jobRepository Spring Batch job metadata repository for
     *                      persisting job execution state
     * @param step          the interest calculation step bean, resolved via
     *                      {@code @Qualifier("interestCalculationStep")}
     * @return the configured Spring Batch {@link Job} instance
     */
    @Bean("interestCalculationJob")
    public Job interestCalculationJob(
            JobRepository jobRepository,
            @Qualifier("interestCalculationStep") Step step) {

        log.info("Configuring interest calculation job (INTCALC equivalent)");

        return new JobBuilder("interestCalculationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(step)
                .build();
    }

    // -----------------------------------------------------------------------
    // Step Bean Definition — maps to INTCALC.jcl STEP15 EXEC PGM=CBACT04C
    // -----------------------------------------------------------------------

    /**
     * Defines the Interest Calculation Step — the chunk-oriented step that
     * reads TCATBAL records, computes interest, and writes system transactions.
     *
     * <p>Maps to the INTCALC.jcl step:
     * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}</p>
     *
     * <p>The step uses a chunk-oriented processing model:
     * <ol>
     *   <li><strong>Read</strong>: {@link RepositoryItemReader} reads
     *       {@link TransactionCategoryBalance} records sorted by account ID
     *       ascending (replacing TCATBALF sequential VSAM read)</li>
     *   <li><strong>Process</strong>: {@link InterestCalculationProcessor}
     *       performs account break detection, interest rate lookup with
     *       DEFAULT group fallback, computes
     *       {@code (balance × rate) / 1200} with {@code BigDecimal} and
     *       {@code RoundingMode.HALF_EVEN}, generates system
     *       {@link Transaction} entities, and updates account balances</li>
     *   <li><strong>Write</strong>: {@link ItemWriter} persists generated
     *       interest transactions to PostgreSQL and S3 backup</li>
     * </ol>
     *
     * <p>The processor is {@code @StepScope} and reads the {@code parmDate}
     * from {@code JobParameters} in its {@code @BeforeStep} callback. The
     * {@code setParmDate(String)} method is also available for programmatic
     * configuration if needed.</p>
     *
     * @param jobRepository       Spring Batch job metadata repository
     * @param transactionManager  platform transaction manager defining chunk
     *                            commit boundaries — each chunk is atomic,
     *                            matching COBOL SYNCPOINT semantics
     * @param reader              TCATBAL repository item reader
     * @param processor           interest calculation processor implementing
     *                            CBACT04C business logic
     * @param writer              interest transaction writer for DB + S3
     * @return the configured Spring Batch {@link Step} instance
     */
    @Bean("interestCalculationStep")
    public Step interestCalculationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("interestTcatbalReader")
                    RepositoryItemReader<TransactionCategoryBalance> reader,
            InterestCalculationProcessor processor,
            @Qualifier("interestTransactionWriter") ItemWriter<Transaction> writer,
            S3Client s3Client) {

        log.info("Configuring interest calculation step: chunkSize={}", chunkSize);

        return new StepBuilder("interestCalculationStep", jobRepository)
                .<TransactionCategoryBalance, Transaction>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener(interestS3BackupListener(s3Client))
                .build();
    }

    /**
     * Step listener that writes accumulated interest transaction records to S3
     * as a single file AFTER all chunks have been processed.
     *
     * <p>This replaces the per-chunk S3 upload strategy that caused data loss:
     * when the step had multiple chunks (e.g., 50 records chunk 1 + 1 record
     * chunk 2), each chunk wrote to the same timestamp-based S3 key, causing
     * the final chunk to overwrite all previous chunks. The listener pattern
     * ensures all records are accumulated in memory during chunk processing
     * and then written as a single complete file.</p>
     *
     * <p>Replaces GDG output {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} from
     * INTCALC.jcl with a timestamped S3 object.</p>
     *
     * @param s3Client AWS S3 client for file upload
     * @return StepExecutionListener that finalizes S3 backup after step completion
     */
    private StepExecutionListener interestS3BackupListener(S3Client s3Client) {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                // Clear accumulator at start of step to ensure clean state
                s3BackupAccumulator.clear();
                log.info("S3 backup accumulator cleared for interest calculation step");
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                if (s3BackupAccumulator.isEmpty()) {
                    log.info("No interest transactions to back up to S3");
                    return null; // preserve original exit status
                }

                // Write all accumulated records to S3 as a single file
                writeBackupToS3(s3Client, s3BackupAccumulator);
                log.info("S3 backup complete: {} interest transaction records written",
                        s3BackupAccumulator.size());

                // Clear after successful write to free memory
                s3BackupAccumulator.clear();
                return null; // preserve original exit status
            }
        };
    }

    // -----------------------------------------------------------------------
    // Private Helper — S3 backup for system-generated interest transactions
    // -----------------------------------------------------------------------

    /**
     * Writes pre-formatted pipe-delimited interest transaction records to S3
     * as a single file.
     *
     * <p>Replaces the GDG output:
     * {@code //TRANSACT DD DSN=AWS.M2.CARDDEMO.SYSTRAN(+1),LRECL=350}
     * from INTCALC.jcl. Uses versioned S3 object keys with timestamps
     * instead of GDG generation numbers.</p>
     *
     * <p>Called ONCE from the afterStep listener with ALL accumulated records,
     * ensuring a single complete file is written regardless of how many
     * chunks the step processes.</p>
     *
     * @param s3Client        the S3 client for file upload
     * @param formattedRecords pre-formatted pipe-delimited records to write
     */
    private void writeBackupToS3(S3Client s3Client, List<String> formattedRecords) {

        String timestamp = LocalDateTime.now().format(S3_TIMESTAMP_FORMAT);
        String key = "batch-output/system-transactions/SYSTRAN-" + timestamp + ".txt";

        StringBuilder content = new StringBuilder(formattedRecords.size() * 256);
        for (String record : formattedRecords) {
            content.append(record).append('\n');
        }

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(outputBucket)
                        .key(key)
                        .build(),
                RequestBody.fromString(content.toString(), StandardCharsets.UTF_8));

        log.info("Wrote {} interest transactions to S3: {}/{}",
                formattedRecords.size(), outputBucket, key);
    }

    /**
     * Formats a single {@link Transaction} as a pipe-delimited record for
     * S3 backup output.
     *
     * <p>Field order matches the TRAN-RECORD layout from CVTRA05Y.cpy:
     * TRAN-ID | TRAN-TYPE-CD | TRAN-CAT-CD | TRAN-SOURCE | TRAN-DESC |
     * TRAN-AMT | TRAN-MERCHANT-ID | TRAN-MERCHANT-NAME | TRAN-MERCHANT-CITY |
     * TRAN-MERCHANT-ZIP | TRAN-CARD-NUM | TRAN-ORIG-TS | TRAN-PROC-TS</p>
     *
     * <p><strong>Note:</strong> All monetary amounts use
     * {@code BigDecimal.toPlainString()} — zero floating-point usage per
     * AAP §0.8.2 decimal precision rules.</p>
     *
     * @param txn the Transaction entity to format
     * @return pipe-delimited string representation of the transaction
     */
    private static String formatTransactionRecord(Transaction txn) {
        return String.join("|",
                nullSafe(txn.getTranId()),
                nullSafe(txn.getTranTypeCd()),
                txn.getTranCatCd() != null ? txn.getTranCatCd().toString() : "0",
                nullSafe(txn.getTranSource()),
                nullSafe(txn.getTranDesc()),
                txn.getTranAmt() != null ? txn.getTranAmt().toPlainString() : "0.00",
                nullSafe(txn.getTranMerchantId()),
                nullSafe(txn.getTranMerchantName()),
                nullSafe(txn.getTranMerchantCity()),
                nullSafe(txn.getTranMerchantZip()),
                nullSafe(txn.getTranCardNum()),
                txn.getTranOrigTs() != null ? txn.getTranOrigTs().toString() : "",
                txn.getTranProcTs() != null ? txn.getTranProcTs().toString() : "");
    }

    /**
     * Returns the input string if non-null, or an empty string if null.
     * Utility method for safe pipe-delimited record formatting.
     *
     * @param value the string value to check
     * @return the input value or empty string if null
     */
    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
