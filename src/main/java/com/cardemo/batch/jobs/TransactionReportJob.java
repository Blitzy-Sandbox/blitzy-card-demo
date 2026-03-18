/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * CardDemo Application — Transaction Report Job (Pipeline Stage 4b)
 * Migrated from TRANREPT.jcl + CBTRN03C.cbl (commit 27d6c6f).
 * COBOL source reference: app/jcl/TRANREPT.jcl (85 lines) + app/cbl/CBTRN03C.cbl (649 lines).
 *
 * Original JCL performs three logical steps:
 *   STEP05R (REPROC):  Unloads TRANSACT VSAM to GDG backup (TRANSACT.BKUP(+1)).
 *   STEP05R (DFSORT):  Sorts by TRAN-CARD-NUM ascending with date range INCLUDE COND
 *                       (TRAN-PROC-DT GE start AND LE end).
 *   STEP10R (CBTRN03C): Generates 133-char LRECL transaction report with enrichment
 *                       lookups (XREF, TRANTYPE, TRANCATG) and multi-level totals
 *                       (page/account/grand).
 *
 * Java equivalent:
 *   Step 1 (transactionBackupStep):  Tasklet that exports all Transaction records to S3
 *           as a CSV backup — replacing GDG TRANSACT.BKUP(+1).
 *   Step 2 (transactionReportStep):  Chunk-based step with RepositoryItemReader (date
 *           filter + card number sort via JPA query), TransactionReportProcessor (enrichment
 *           + multi-level totals), and S3 ItemWriter (133-char report lines).
 *
 * This stage runs IN PARALLEL with Stage 4a (StatementGenerationJob) after Stage 3
 * completes, orchestrated by BatchPipelineOrchestrator via FlowBuilder.split().
 */
package com.cardemo.batch.jobs;

// Internal imports — strictly from depends_on_files
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

// Spring Batch Core — job/step definition, step scope, tasklet model
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;

// Spring Batch Infrastructure — chunk-based reader/writer types, repeat status
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;

// Spring Framework — configuration, bean wiring, value injection
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

// Spring Data — sort specification for RepositoryItemReader
import org.springframework.data.domain.Sort;

// AWS SDK v2 — S3 client for backup and report output (replacing GDG + REPTFILE)
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// SLF4J — structured logging with correlation IDs per AAP §0.7.1
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Java Standard Library
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Batch {@code @Configuration} defining the transaction report job —
 * <strong>Stage 4b</strong> of the 5-stage batch pipeline.
 *
 * <h2>Pipeline Position</h2>
 * <pre>
 * Stage 1: POSTTRAN  (DailyTransactionPostingJob)  — validate and post daily transactions
 * Stage 2: INTCALC   (InterestCalculationJob)       — calculate and post interest transactions
 * Stage 3: COMBTRAN  (CombineTransactionsJob)       — sort and backup combined transactions
 * Stage 4a: CREASTMT (StatementGenerationJob)       — generate customer statements  (parallel)
 * Stage 4b: TRANREPT (TransactionReportJob) ← THIS  — generate transaction reports  (parallel)
 * </pre>
 *
 * <h2>Migration Strategy</h2>
 * <p>The original TRANREPT.jcl has three logical steps:
 * <ol>
 *   <li><strong>REPROC backup</strong> → Java Tasklet uploading all transactions to S3</li>
 *   <li><strong>DFSORT date filter + card sort</strong> → JPA query with date range
 *       and ORDER BY</li>
 *   <li><strong>CBTRN03C report generation</strong> → {@link TransactionReportProcessor} for
 *       enrichment lookups, multi-level totals, and 133-char report line formatting</li>
 * </ol>
 *
 * <p>Date parameters ({@code startDate}, {@code endDate}) are injected from Spring Batch
 * {@code JobParameters} — replacing the COBOL DATEPARM in-stream data
 * (paragraph {@code 0500-DATE-READ} in CBTRN03C.cbl, lines 162–170).
 *
 * @see TransactionReportProcessor
 * @see TransactionRepository#findByTranOrigTsBetween(LocalDateTime, LocalDateTime)
 * @see Transaction
 */
@Configuration("transactionReportJobConfig")
public class TransactionReportJob {

    private static final Logger log = LoggerFactory.getLogger(TransactionReportJob.class);

    /**
     * Default page size for the report — matches COBOL {@code WS-PAGE-SIZE PIC 9(03)
     * VALUE 020} from CBTRN03C.cbl (line 135). This constant is used for documentation
     * purposes; the actual page break logic lives inside {@link TransactionReportProcessor}.
     */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Report line record length — matches COBOL {@code FD-REPTFILE-REC} LRECL=133
     * from CBTRN03C.cbl (line 46). This is the fixed width of each output report line.
     */
    private static final int REPORT_LRECL = 133;

    /**
     * S3 key prefix for transaction backups — replaces GDG base
     * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}.
     */
    private static final String BACKUP_KEY_PREFIX = "transact-backup/";

    /**
     * S3 key prefix for generated reports — replaces
     * {@code AWS.M2.CARDDEMO.REPORT.PS} (REPTFILE DD).
     */
    private static final String REPORT_KEY_PREFIX = "reports/";

    /**
     * Timestamp format for S3 object key naming — replaces GDG generation numbering.
     */
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Page size for the {@link RepositoryItemReader} — number of records fetched per
     * database query page. Provides efficient memory usage for large datasets.
     */
    private static final int READER_PAGE_SIZE = 100;

    /**
     * S3 output bucket name — configurable via application properties.
     * Default value {@code carddemo-batch-output} replaces the mainframe
     * dataset qualifiers (e.g., {@code AWS.M2.CARDDEMO.*}).
     */
    @Value("${carddemo.s3.output-bucket:carddemo-batch-output}")
    private String outputBucket;

    /**
     * Chunk size for the report generation step — configurable via application properties.
     * Default value of 50 balances commit frequency with throughput. Each chunk corresponds
     * to a database transaction boundary.
     */
    @Value("${carddemo.batch.chunk-size.report:50}")
    private int chunkSize;

    // -----------------------------------------------------------------------
    // Job Bean Definition — TRANREPT.jcl overall job
    // -----------------------------------------------------------------------

    /**
     * Defines the transaction report job (Pipeline Stage 4b).
     *
     * <p>Mirrors the 3-step JCL flow reduced to 2 Java steps (DFSORT is folded
     * into the reader's JPA query):
     * <ol>
     *   <li>{@code transactionBackupStep} — REPROC backup (S3 upload)</li>
     *   <li>{@code transactionReportStep} — DFSORT + CBTRN03C combined (query + report)</li>
     * </ol>
     *
     * @param jobRepository Spring Batch metadata repository
     * @param backupStep    the transaction backup step (REPROC equivalent)
     * @param reportStep    the report generation step (CBTRN03C equivalent)
     * @return a fully configured Spring Batch {@link Job}
     */
    @Bean("transactionReportJob")
    public Job transactionReportJob(
            JobRepository jobRepository,
            @Qualifier("transactionBackupStep") Step backupStep,
            @Qualifier("transactionReportStep") Step reportStep) {

        log.info("Configuring transactionReportJob (TRANREPT equivalent) — "
                + "Pipeline Stage 4b with 2 steps: backup -> report");

        return new JobBuilder("transactionReportJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(backupStep)
                .next(reportStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Step 1: Backup (REPROC equivalent — TRANREPT.jcl STEP05R)
    // -----------------------------------------------------------------------

    /**
     * Defines the transaction backup step — a {@code Tasklet}-based step that exports
     * all {@link Transaction} records to S3 as a CSV backup file.
     *
     * <p>Replaces the REPROC cataloged procedure in TRANREPT.jcl STEP05R which
     * unloads TRANSACT.VSAM.KSDS to GDG {@code TRANSACT.BKUP(+1)}. The S3 versioned
     * object key includes a timestamp to replace GDG generation numbering.
     *
     * <p>Backup format: CSV with pipe delimiter, one record per line. Fields are ordered
     * to match the original COBOL TRAN-RECORD layout from CVTRA05Y.cpy.
     *
     * @param jobRepository         Spring Batch metadata repository
     * @param transactionManager    transaction manager for step boundaries
     * @param transactionRepository JPA repository for reading all transactions
     * @param s3Client              AWS S3 client for backup upload
     * @return a fully configured backup {@link Step}
     */
    @Bean("transactionBackupStep")
    public Step transactionBackupStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionRepository transactionRepository,
            S3Client s3Client) {

        return new StepBuilder("transactionBackupStep", jobRepository)
                .tasklet((StepContribution contribution, ChunkContext chunkContext) -> {
                    log.info("TRANREPT Step 1 (REPROC equivalent): Starting transaction "
                            + "backup to S3");

                    // Read all transaction records — equivalent to REPROC INFILE(INVSAMFL)
                    // reading AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
                    List<Transaction> allTransactions = transactionRepository.findAll();
                    int recordCount = allTransactions.size();

                    if (recordCount == 0) {
                        log.warn("TRANREPT backup: No transactions found — backup will "
                                + "be empty");
                    }

                    // Serialize to CSV format (pipe-delimited, matching COBOL record layout)
                    StringBuilder csvContent = new StringBuilder(recordCount * 200);
                    csvContent.append("TRAN_ID|TRAN_TYPE_CD|TRAN_CAT_CD|TRAN_SOURCE|"
                            + "TRAN_DESC|TRAN_AMT|TRAN_MERCHANT_ID|TRAN_MERCHANT_NAME|"
                            + "TRAN_MERCHANT_CITY|TRAN_MERCHANT_ZIP|TRAN_CARD_NUM|"
                            + "TRAN_ORIG_TS|TRAN_PROC_TS\n");

                    for (Transaction txn : allTransactions) {
                        csvContent.append(nullSafe(txn.getTranId())).append('|')
                                .append(nullSafe(txn.getTranTypeCd())).append('|')
                                .append(txn.getTranCatCd() != null
                                        ? txn.getTranCatCd().toString() : "")
                                .append('|')
                                .append(nullSafe(txn.getTranSource())).append('|')
                                .append(nullSafe(txn.getTranDesc())).append('|')
                                .append(txn.getTranAmt() != null
                                        ? txn.getTranAmt().toPlainString() : "0.00")
                                .append('|')
                                .append(nullSafe(txn.getTranMerchantId())).append('|')
                                .append(nullSafe(txn.getTranMerchantName())).append('|')
                                .append(nullSafe(txn.getTranMerchantCity())).append('|')
                                .append(nullSafe(txn.getTranMerchantZip())).append('|')
                                .append(nullSafe(txn.getTranCardNum())).append('|')
                                .append(txn.getTranOrigTs() != null
                                        ? txn.getTranOrigTs().toString() : "")
                                .append('|')
                                .append(txn.getTranProcTs() != null
                                        ? txn.getTranProcTs().toString() : "")
                                .append('\n');
                    }

                    // Upload to S3 — replacing GDG TRANSACT.BKUP(+1)
                    String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
                    String s3Key = BACKUP_KEY_PREFIX + timestamp
                            + "/transactions.dat";

                    PutObjectRequest putRequest = PutObjectRequest.builder()
                            .bucket(outputBucket)
                            .key(s3Key)
                            .build();
                    s3Client.putObject(putRequest,
                            RequestBody.fromString(csvContent.toString()));

                    log.info("TRANREPT Step 1 (REPROC) complete: exported {} transaction "
                                    + "records to s3://{}/{}",
                            recordCount, outputBucket, s3Key);

                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    // -----------------------------------------------------------------------
    // Step 2: Report Generation (combined DFSORT + CBTRN03C)
    // -----------------------------------------------------------------------

    /**
     * Defines the transaction report generation step — a chunk-based step combining
     * the DFSORT date filter / card sort with the CBTRN03C report program.
     *
     * <p>The reader applies the date filter and card-number sort at the JPA query level,
     * replacing both the DFSORT {@code INCLUDE COND} and {@code SORT FIELDS} directives.
     * The processor performs enrichment lookups (XREF, TRANTYPE, TRANCATG) and
     * multi-level total accumulation. The writer collects processed transactions and
     * uploads the final 133-character LRECL report to S3.
     *
     * <p>A {@link StepExecutionListener} logs the report summary after step completion,
     * including total transactions processed, pages generated, and grand total amount.
     *
     * @param jobRepository      Spring Batch metadata repository
     * @param transactionManager transaction manager for chunk boundaries
     * @param reader             the date-filtered, card-sorted transaction reader
     * @param processor          the report enrichment and formatting processor
     * @param writer             the S3 report writer
     * @return a fully configured report {@link Step}
     */
    @Bean("transactionReportStep")
    public Step transactionReportStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("reportTransactionReader")
            RepositoryItemReader<Transaction> reader,
            @Qualifier("transactionReportProcessor")
            ItemProcessor<Transaction, Transaction> processor,
            @Qualifier("transactionReportWriter")
            ItemWriter<Transaction> writer) {

        log.info("Configuring transactionReportStep: chunkSize={}, readerPageSize={}, "
                + "reportPageSize={}", chunkSize, READER_PAGE_SIZE, DEFAULT_PAGE_SIZE);

        return new StepBuilder("transactionReportStep", jobRepository)
                .<Transaction, Transaction>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener(createReportStepListener(processor))
                .build();
    }

    // -----------------------------------------------------------------------
    // Reader Bean — RepositoryItemReader with date filter + card sort
    // -----------------------------------------------------------------------

    /**
     * Configures the {@link RepositoryItemReader} that combines the DFSORT date filter
     * and card number sort into a single JPA query.
     *
     * <p>Replaces:
     * <ul>
     *   <li>DFSORT {@code INCLUDE COND=(TRAN-PROC-DT,GE,startDate,AND,
     *       TRAN-PROC-DT,LE,endDate)} — date range filtering via
     *       {@link TransactionRepository#findByTranOrigTsBetween}</li>
     *   <li>DFSORT {@code SORT FIELDS=(TRAN-CARD-NUM,A)} — ascending card number sort
     *       via Spring Data sort specification</li>
     * </ul>
     *
     * <p>{@code @StepScope} enables late-binding of {@code startDate} and {@code endDate}
     * from {@code JobParameters}, replacing the COBOL DATEPARM in-stream data.
     *
     * <p>This method also configures the {@link TransactionReportProcessor} with the
     * date range by calling {@code setStartDate} and {@code setEndDate} — mapping the
     * COBOL paragraph {@code 0500-DATE-READ} that reads the DATEPARM file before
     * processing begins (CBTRN03C.cbl, lines 162–170).
     *
     * @param startDate             start date (YYYY-MM-DD) from JobParameters (inclusive)
     * @param endDate               end date (YYYY-MM-DD) from JobParameters (inclusive)
     * @param transactionRepository JPA repository for transaction queries
     * @return a configured {@link RepositoryItemReader} with date filtering and card sort
     */
    @Bean("reportTransactionReader")
    @StepScope
    public RepositoryItemReader<Transaction> reportTransactionReader(
            @Value("#{jobParameters['startDate']}") String startDate,
            @Value("#{jobParameters['endDate']}") String endDate,
            TransactionRepository transactionRepository) {

        // Parse YYYY-MM-DD strings from JobParameters — maps COBOL WS-START-DATE
        // and WS-END-DATE PIC X(10) read from DATEPARM file
        // (paragraph 0500-DATE-READ in CBTRN03C.cbl)
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        // Convert to LocalDateTime boundaries for the JPA query:
        //   startDate → start of day (00:00:00)
        //   endDate   → end of day (23:59:59)
        LocalDateTime startDateTime = start.atStartOfDay();
        LocalDateTime endDateTime = end.atTime(23, 59, 59);

        log.info("TRANREPT reader: date range {} to {} (inclusive), sorted by "
                        + "tranCardNum ASC, tranId ASC",
                startDate, endDate);

        // Build sort specification matching DFSORT:
        //   Primary:   SORT FIELDS=(TRAN-CARD-NUM,A) → tranCardNum ascending
        //   Secondary: tranId ascending for stable ordering within same card
        Map<String, Sort.Direction> sortMap = new LinkedHashMap<>();
        sortMap.put("tranCardNum", Sort.Direction.ASC);
        sortMap.put("tranId", Sort.Direction.ASC);

        return new RepositoryItemReaderBuilder<Transaction>()
                .name("reportTransactionReader")
                .repository(transactionRepository)
                .methodName("findByTranOrigTsBetween")
                .arguments(List.of(startDateTime, endDateTime))
                .pageSize(READER_PAGE_SIZE)
                .sorts(sortMap)
                .build();
    }

    // -----------------------------------------------------------------------
    // Writer Bean — S3 Report Output (replacing REPTFILE DD)
    // -----------------------------------------------------------------------

    /**
     * Defines the report writer that collects processed transactions and uploads
     * a formatted report to S3. The report uses 133-character fixed-width lines
     * matching the COBOL {@code FD-REPTFILE-REC} LRECL=133 specification.
     *
     * <p>The writer accumulates report lines across all chunks in a buffer and
     * uploads the complete report to S3 after each chunk (overwriting the same
     * key). The final S3 upload contains the complete report.
     *
     * <p>The S3 object key follows the pattern
     * {@code reports/{timestamp}/transaction-report.txt}, replacing the mainframe
     * {@code REPTFILE DD DSN=AWS.M2.CARDDEMO.REPORT.PS}.
     *
     * <p>This method also initialises the {@link TransactionReportProcessor} date range
     * by calling {@code setStartDate}/{@code setEndDate} — mapping the COBOL paragraph
     * {@code 0500-DATE-READ} (CBTRN03C.cbl, lines 162–170).
     *
     * @param s3Client  AWS S3 client for report upload
     * @param processor the report processor (for date configuration and summary stats)
     * @param startDate start date (YYYY-MM-DD) from JobParameters
     * @param endDate   end date (YYYY-MM-DD) from JobParameters
     * @return a configured {@link ItemWriter} for report output
     */
    @Bean("transactionReportWriter")
    @StepScope
    public ItemWriter<Transaction> transactionReportWriter(
            S3Client s3Client,
            @Qualifier("transactionReportProcessor")
            TransactionReportProcessor processor,
            @Value("#{jobParameters['startDate']}") String startDate,
            @Value("#{jobParameters['endDate']}") String endDate) {

        // Configure processor date range — maps COBOL paragraph 0500-DATE-READ
        // which reads start/end dates from the DATEPARM in-stream data before
        // any transaction processing begins
        if (startDate != null) {
            processor.setStartDate(LocalDate.parse(startDate));
        }
        if (endDate != null) {
            processor.setEndDate(LocalDate.parse(endDate));
        }

        log.info("TRANREPT writer configured: date range {} to {}, output LRECL={}",
                startDate, endDate, REPORT_LRECL);

        // Buffer for accumulating 133-char report lines across all chunks.
        // The buffer grows as chunks are processed; after each chunk the
        // complete accumulated content is uploaded to S3 (overwriting the
        // previous version). The final upload contains the complete report.
        final StringBuilder reportBuffer = new StringBuilder(64 * 1024);

        // Compute S3 key once at bean creation — all chunks write to the same key.
        // The timestamp replaces GDG generation numbering in the original JCL.
        final String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        final String s3Key = REPORT_KEY_PREFIX + timestamp
                + "/transaction-report.txt";

        return items -> {
            // Format each processed transaction as a 133-char fixed-width line
            // matching FD-REPTFILE-REC LRECL=133 from CBTRN03C.cbl
            for (Transaction txn : items) {
                reportBuffer.append(formatReportLine(txn)).append('\n');
            }

            // Upload accumulated report content to S3 (overwrites same key each chunk)
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(outputBucket)
                    .key(s3Key)
                    .build();
            s3Client.putObject(putRequest,
                    RequestBody.fromString(reportBuffer.toString()));

            BigDecimal grandTotal = processor.getGrandTotal();
            int pageNum = processor.getPageNum();

            log.debug("TRANREPT report chunk written: {} items in chunk, "
                            + "buffer size {} bytes, {} pages so far, "
                            + "running grand total: {} -> s3://{}/{}",
                    items.size(), reportBuffer.length(),
                    pageNum, grandTotal, outputBucket, s3Key);
        };
    }

    // -----------------------------------------------------------------------
    // Step Execution Listener — Report Summary (COBOL end-of-file processing)
    // -----------------------------------------------------------------------

    /**
     * Creates a {@link StepExecutionListener} that logs the report summary after
     * step completion. This replaces the COBOL end-of-file processing in CBTRN03C.cbl
     * that writes the grand total line and displays summary messages.
     *
     * <p>Summary includes: total transactions processed, transactions in report,
     * pages generated, and grand total amount from the processor.
     *
     * @param processor the chunk processor (may be a TransactionReportProcessor
     *                  for detailed statistics)
     * @return a step execution listener for report summary logging
     */
    private StepExecutionListener createReportStepListener(
            ItemProcessor<Transaction, Transaction> processor) {

        return new StepExecutionListener() {

            @Override
            public void beforeStep(StepExecution stepExecution) {
                log.info("TRANREPT Step 2 (CBTRN03C equivalent): Starting report "
                        + "generation with chunkSize={}, pageSize={}",
                        chunkSize, DEFAULT_PAGE_SIZE);
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                long readCount = stepExecution.getReadCount();
                long writeCount = stepExecution.getWriteCount();
                long filterCount = stepExecution.getFilterCount();

                // Attempt to retrieve detailed stats from TransactionReportProcessor.
                // The processor may be proxied by Spring's @StepScope mechanism;
                // pattern matching instanceof handles both direct and CGLIB proxies.
                if (processor instanceof TransactionReportProcessor reportProcessor) {
                    BigDecimal grandTotal = reportProcessor.getGrandTotal();
                    int pageNum = reportProcessor.getPageNum();

                    log.info("TRANREPT complete: {} transactions reported across "
                                    + "{} pages, grand total: {} "
                                    + "(read={}, written={}, filtered={})",
                            writeCount, pageNum, grandTotal,
                            readCount, writeCount, filterCount);
                } else {
                    log.info("TRANREPT complete: {} transactions processed "
                                    + "(read={}, written={}, filtered={})",
                            writeCount, readCount, writeCount, filterCount);
                }
                return null;
            }
        };
    }

    // -----------------------------------------------------------------------
    // Private Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Formats a single transaction into a 133-character fixed-width report line,
     * matching the COBOL {@code TRANSACTION-DETAIL-REPORT} format from CBTRN03C.cbl
     * paragraph {@code 1100-WRITE-TRANSACTION-REPORT} (lines 380–430).
     *
     * <p>Field layout (total 133 characters):
     * <pre>
     * Col  1-16  : TRAN-ID          (16 chars, left-justified)
     * Col 17     : Separator space   (1 char)
     * Col 18-33  : TRAN-CARD-NUM    (16 chars, left-justified)
     * Col 34     : Separator space   (1 char)
     * Col 35-36  : TRAN-TYPE-CD     (2 chars, left-justified)
     * Col 37     : Separator space   (1 char)
     * Col 38-47  : TRAN-SOURCE      (10 chars, left-justified)
     * Col 48     : Separator space   (1 char)
     * Col 49-98  : TRAN-DESC        (50 chars, left-justified)
     * Col 99     : Separator space   (1 char)
     * Col100-119 : TRAN-ORIG-TS     (20 chars, ISO datetime, left-justified)
     * Col120     : Separator space   (1 char)
     * Col121-133 : TRAN-AMT         (13 chars, right-justified)
     * </pre>
     *
     * @param txn the transaction to format
     * @return a 133-character padded report line
     */
    private static String formatReportLine(Transaction txn) {
        StringBuilder line = new StringBuilder(REPORT_LRECL);

        // TRAN-ID: 16 chars left-justified (COBOL PIC X(16), position 1-16)
        line.append(padRight(nullSafe(txn.getTranId()), 16));
        line.append(' ');

        // TRAN-CARD-NUM: 16 chars left-justified (COBOL PIC X(16), position 263-278)
        line.append(padRight(nullSafe(txn.getTranCardNum()), 16));
        line.append(' ');

        // TRAN-TYPE-CD: 2 chars left-justified (COBOL PIC X(02))
        line.append(padRight(nullSafe(txn.getTranTypeCd()), 2));
        line.append(' ');

        // TRAN-SOURCE: 10 chars left-justified (COBOL PIC X(10))
        line.append(padRight(nullSafe(txn.getTranSource()), 10));
        line.append(' ');

        // TRAN-DESC: 50 chars left-justified (truncated if longer)
        line.append(padRight(nullSafe(txn.getTranDesc()), 50));
        line.append(' ');

        // TRAN-ORIG-TS: 20 chars left-justified (ISO datetime format)
        String origTs = txn.getTranOrigTs() != null
                ? txn.getTranOrigTs().toString() : "";
        line.append(padRight(origTs, 20));
        line.append(' ');

        // TRAN-AMT: 13 chars right-justified (PIC S9(09)V99 COMP-3 display)
        String amtStr = txn.getTranAmt() != null
                ? txn.getTranAmt().toPlainString() : "0.00";
        line.append(padLeft(amtStr, 13));

        // Ensure exact LRECL=133 — truncate or pad as needed
        String result = line.toString();
        if (result.length() > REPORT_LRECL) {
            return result.substring(0, REPORT_LRECL);
        }
        if (result.length() < REPORT_LRECL) {
            return padRight(result, REPORT_LRECL);
        }
        return result;
    }

    /**
     * Left-pads a string with spaces to the specified width.
     * Used for right-justified numeric fields (e.g., TRAN-AMT).
     *
     * @param value the string to pad
     * @param width the target width
     * @return the padded string, truncated if longer than width
     */
    private static String padLeft(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return " ".repeat(width - value.length()) + value;
    }

    /**
     * Right-pads a string with spaces to the specified width.
     * Used for left-justified character fields.
     *
     * @param value the string to pad
     * @param width the target width
     * @return the padded string, truncated if longer than width
     */
    private static String padRight(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Returns the input string or an empty string if null.
     * Prevents {@link NullPointerException} during report line formatting
     * and CSV serialization.
     *
     * @param value the string to null-check
     * @return the original string, or empty string if null
     */
    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
