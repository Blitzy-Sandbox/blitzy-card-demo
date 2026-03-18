/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * CardDemo Application — Combine Transactions Job (Pipeline Stage 3)
 * Migrated from COMBTRAN.jcl — a pure JCL utility job (NO COBOL program).
 * COBOL source reference: app/jcl/COMBTRAN.jcl (commit 27d6c6f) — 53 lines.
 *
 * Original JCL performs two steps:
 *   STEP05R (DFSORT): Concatenates TRANSACT.BKUP(0) + SYSTRAN(0), sorts by TRAN-ID ascending.
 *                      SORT FIELDS=(TRAN-ID,A) with SYMNAMES TRAN-ID,1,16,CH.
 *   STEP10  (IDCAMS REPRO): Loads sorted TRANSACT.COMBINED(+1) into TRANSACT.VSAM.KSDS.
 *
 * Java equivalent: Read all transactions from PostgreSQL (both Stage 1 posted and Stage 2
 * interest-generated are already in the same table), sort by transaction ID ascending
 * (lexicographic, matching COBOL CH type), create a sorted backup to S3 (replacing the
 * TRANSACT.COMBINED GDG generation), and verify dataset consistency.
 */
package com.cardemo.batch.jobs;

// Internal imports — strictly from depends_on_files
import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

// Spring Batch Core — job/step definition, tasklet execution model
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;

// Spring Batch Infrastructure — step completion status
import org.springframework.batch.repeat.RepeatStatus;

// Spring Framework — configuration, bean wiring, value injection
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

// AWS SDK v2 — S3 client for backup file output (replacing GDG generation)
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// SLF4J — structured logging with correlation IDs per AAP §0.7.1
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Java Standard Library
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch {@code @Configuration} defining the combine transactions job —
 * <strong>Stage 3</strong> of the 5-stage batch pipeline.
 *
 * <h2>Pipeline Position</h2>
 * <pre>
 * Stage 1: POSTTRAN  (DailyTransactionPostingJob)  — validate and post daily transactions
 * Stage 2: INTCALC   (InterestCalculationJob)       — calculate and post interest transactions
 * Stage 3: COMBTRAN  (CombineTransactionsJob) ← THIS — sort and backup combined transactions
 * Stage 4a: CREASTMT (StatementGenerationJob)       — generate customer statements
 * Stage 4b: TRANREPT (TransactionReportJob)         — generate transaction reports
 * </pre>
 *
 * <h2>Migration Strategy</h2>
 * <p>This is the <strong>ONLY</strong> batch job with no corresponding COBOL program.
 * The original {@code COMBTRAN.jcl} is a pure utility job using:
 * <ul>
 *   <li><strong>DFSORT</strong> (STEP05R): Merges two input datasets and sorts by TRAN-ID
 *       ascending (position 1, length 16, type CH/character)</li>
 *   <li><strong>IDCAMS REPRO</strong> (STEP10): Bulk loads sorted output into VSAM KSDS</li>
 * </ul>
 *
 * <p>In the Java/JPA world, both Stage 1 (posting) and Stage 2 (interest) write to the
 * <em>same</em> PostgreSQL {@code transactions} table. Therefore, COMBTRAN's Java purpose is:
 * <ol>
 *   <li>Read all transactions via {@link TransactionRepository#findAll()}</li>
 *   <li>Sort by transaction ID ascending using {@link TransactionCombineProcessor#TRAN_ID_COMPARATOR}</li>
 *   <li>Create a sorted backup to S3 (replacing {@code TRANSACT.COMBINED(+1)} GDG generation)</li>
 *   <li>Verify combined dataset consistency for downstream stages</li>
 * </ol>
 *
 * <p>Uses <strong>Tasklet</strong> pattern (not chunk) because the entire operation is a
 * single atomic unit — read all, sort, write backup.
 *
 * @see TransactionCombineProcessor#TRAN_ID_COMPARATOR
 * @see TransactionRepository
 * @see Transaction
 */
@Configuration("combineTransactionsJobConfig")
public class CombineTransactionsJob {

    private static final Logger log = LoggerFactory.getLogger(CombineTransactionsJob.class);

    /**
     * Fixed-width record length matching COBOL LRECL=350 from COMBTRAN.jcl
     * {@code SORTOUT DD DCB=(RECFM=FB,LRECL=350,BLKSIZE=0)}.
     * Corresponds to the TRAN-RECORD layout defined in CVTRA05Y.cpy.
     */
    private static final int RECORD_LENGTH = 350;

    /**
     * S3 object key prefix for combined transaction backups.
     * Replaces the GDG base {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED}.
     */
    private static final String S3_KEY_PREFIX = "combined-transactions/TRANSACT-COMBINED-";

    /**
     * Timestamp format for S3 backup file naming — replaces GDG generation numbering
     * ({@code TRANSACT.COMBINED(+1)}) with a timestamp-based key suffix.
     */
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Timestamp format matching the COBOL PIC X(26) convention for
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} fields.
     * COBOL format: {@code YYYY-MM-DD-HH.MM.SS.NNNNNN}
     */
    private static final DateTimeFormatter COBOL_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /**
     * ASCII overpunch characters for negative signed numeric fields.
     * Maps digit values 0-9 to their negative overpunch representations,
     * preserving COBOL DISPLAY format signed field semantics in ASCII output.
     * Index maps to the digit value: 0→'}', 1→'J', 2→'K', ... 9→'R'.
     */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    private final TransactionRepository transactionRepository;
    private final S3Client s3Client;

    /**
     * S3 bucket name for batch output, externalized via Spring property injection.
     * Default: {@code carddemo-batch-output}. Overridden via
     * {@code carddemo.s3.output-bucket} property for environment-specific configuration.
     * No hardcoded credentials per AAP §0.8.1.
     */
    @Value("${carddemo.s3.output-bucket:carddemo-batch-output}")
    private String outputBucket;

    /**
     * Constructs the combine transactions job configuration with required dependencies.
     *
     * @param transactionRepository JPA repository for reading all posted and
     *                              system-generated transactions (replaces VSAM KSDS access)
     * @param s3Client              AWS S3 client for writing the combined backup file
     *                              (provided by AwsConfig with LocalStack-aware configuration)
     */
    public CombineTransactionsJob(TransactionRepository transactionRepository,
                                   S3Client s3Client) {
        this.transactionRepository = transactionRepository;
        this.s3Client = s3Client;
    }

    // -----------------------------------------------------------------------
    // Job Bean Definition
    // -----------------------------------------------------------------------

    /**
     * Defines the combine transactions job — Stage 3 of the 5-stage batch pipeline.
     *
     * <p>Maps {@code COMBTRAN.jcl} which executes DFSORT (STEP05R) followed by
     * IDCAMS REPRO (STEP10). The job uses {@link RunIdIncrementer} to enable
     * multiple executions with unique run identifiers (equivalent to JCL
     * {@code NOTIFY=&SYSUID} and JES job numbering).
     *
     * @param jobRepository  Spring Batch job metadata repository
     * @param combineStep    the single combine-and-sort step (Tasklet-based)
     * @return the configured Spring Batch {@link Job} instance
     */
    @Bean("combineTransactionsJob")
    public Job combineTransactionsJob(JobRepository jobRepository,
                                      @Qualifier("combineTransactionsStep") Step combineStep) {
        log.debug("Configuring combineTransactionsJob — Pipeline Stage 3 (COMBTRAN)");
        return new JobBuilder("combineTransactionsJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(combineStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Step Bean Definition — Tasklet-Based (NOT Chunk-Based)
    // -----------------------------------------------------------------------

    /**
     * Defines the single Tasklet-based step that combines and sorts all transactions.
     *
     * <p><strong>Why Tasklet (not Chunk)?</strong> The original COMBTRAN.jcl processes
     * all records as a single unit — DFSORT reads all input, sorts in memory, and writes
     * all output. There is no item-by-item transformation. The Tasklet pattern matches
     * this single-unit-of-work semantic exactly.
     *
     * <p>The step executes within a {@link PlatformTransactionManager}-managed transaction
     * boundary, ensuring atomicity of any metadata updates during the combine operation
     * (equivalent to IDCAMS REPRO's all-or-nothing semantics).
     *
     * @param jobRepository      Spring Batch job metadata repository
     * @param transactionManager platform transaction manager wrapping the Tasklet execution
     * @return the configured Spring Batch {@link Step} instance
     */
    @Bean("combineTransactionsStep")
    public Step combineTransactionsStep(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager) {
        return new StepBuilder("combineTransactionsStep", jobRepository)
                .tasklet((StepContribution contribution, ChunkContext chunkContext) -> {

                    // -----------------------------------------------------------
                    // STEP 1 — SORT equivalent (COMBTRAN.jcl STEP05R):
                    //
                    // Original JCL concatenates two SORTIN datasets:
                    //   DD DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)  (posted transactions)
                    //   DD DSN=AWS.M2.CARDDEMO.SYSTRAN(0)         (interest transactions)
                    // Then sorts using: SORT FIELDS=(TRAN-ID,A)
                    //   SYMNAMES: TRAN-ID,1,16,CH (position 1, length 16, character, ascending)
                    //
                    // In Java/JPA, both Stage 1 (posting) and Stage 2 (interest)
                    // wrote to the SAME Transaction table in PostgreSQL.
                    // We read all with findAll() — equivalent to concatenating both datasets.
                    // -----------------------------------------------------------

                    List<Transaction> allTransactions =
                            new ArrayList<>(transactionRepository.findAll());
                    log.info("COMBTRAN Step 1 (SORT): Read {} transactions for combining and sorting",
                            allTransactions.size());

                    // Sort by transaction ID ascending — SORT FIELDS=(TRAN-ID,A)
                    // Uses TransactionCombineProcessor.TRAN_ID_COMPARATOR which implements
                    // Comparator.comparing(Transaction::getTranId) — lexicographic ascending
                    // matching COBOL CH (character) type sort semantics
                    allTransactions.sort(TransactionCombineProcessor.TRAN_ID_COMPARATOR);
                    log.info("COMBTRAN Step 1 (SORT): Sorted {} transactions by TRAN-ID ascending",
                            allTransactions.size());

                    // Debug-level sort verification for observability
                    if (!allTransactions.isEmpty()) {
                        log.debug("COMBTRAN Step 1 (SORT): Sort verification — first ID: {}, last ID: {}",
                                allTransactions.get(0).getTranId(),
                                allTransactions.get(allTransactions.size() - 1).getTranId());
                    } else {
                        log.debug("COMBTRAN Step 1 (SORT): No transactions to sort — empty dataset");
                    }

                    // -----------------------------------------------------------
                    // STEP 2 — REPRO equivalent (COMBTRAN.jcl STEP10):
                    //
                    // Original JCL: REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)
                    // Copies sorted TRANSACT.COMBINED into TRANSACT.VSAM.KSDS.
                    //
                    // In Java/JPA, transactions are already in PostgreSQL from
                    // Stage 1 and Stage 2. This step creates a sorted backup
                    // to S3 (replacing TRANSACT.COMBINED(+1) GDG generation)
                    // and verifies dataset consistency for downstream stages.
                    // -----------------------------------------------------------

                    writeCombinedBackupToS3(allTransactions);

                    log.info("COMBTRAN Step 2 (REPRO): Combined dataset verified and backed up — "
                            + "{} total transactions", allTransactions.size());

                    return RepeatStatus.FINISHED;

                }, transactionManager)
                .build();
    }

    // -----------------------------------------------------------------------
    // S3 Backup Writer — Replaces TRANSACT.COMBINED GDG Generation
    // -----------------------------------------------------------------------

    /**
     * Writes all combined sorted transactions to S3 as a fixed-width 350-byte record file.
     *
     * <p>Replaces the {@code TRANSACT.COMBINED(+1)} GDG generation output from COMBTRAN.jcl
     * STEP10. The S3 object key uses a timestamp suffix instead of GDG generation numbering:
     * {@code combined-transactions/TRANSACT-COMBINED-{yyyyMMddHHmmss}.txt}
     *
     * <p>Each transaction is serialized as a fixed-width 350-byte record matching the
     * COBOL TRAN-RECORD layout (CVTRA05Y.cpy) with RECFM=FB, LRECL=350.
     *
     * @param transactions the sorted list of all transactions to back up
     */
    private void writeCombinedBackupToS3(List<Transaction> transactions) {
        // Generate S3 object key with timestamp (replaces GDG generation numbering)
        String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP_FORMATTER);
        String s3Key = S3_KEY_PREFIX + timestamp + ".txt";

        log.debug("COMBTRAN S3 backup: Serializing {} transactions to fixed-width format (LRECL={})",
                transactions.size(), RECORD_LENGTH);

        // Serialize all transactions to fixed-width 350-byte records
        // Each record matches the COBOL TRAN-RECORD layout from CVTRA05Y.cpy
        StringBuilder content = new StringBuilder(transactions.size() * (RECORD_LENGTH + 1));
        for (Transaction txn : transactions) {
            content.append(serializeToFixedWidth(txn));
            content.append('\n');
        }

        // Convert to bytes using UTF-8 encoding (ASCII-compatible for fixed-width records)
        byte[] contentBytes = content.toString().getBytes(StandardCharsets.UTF_8);

        // Upload to S3 bucket
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(outputBucket)
                .key(s3Key)
                .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

        log.info("COMBTRAN S3 backup: Wrote {} transactions ({} bytes) to s3://{}/{}",
                transactions.size(), contentBytes.length, outputBucket, s3Key);
    }

    // -----------------------------------------------------------------------
    // Fixed-Width Record Serialization — TRAN-RECORD Layout (CVTRA05Y.cpy)
    // -----------------------------------------------------------------------

    /**
     * Serializes a {@link Transaction} to a fixed-width 350-byte record string matching
     * the COBOL TRAN-RECORD layout from CVTRA05Y.cpy.
     *
     * <p>Field layout (total 350 bytes):
     * <pre>
     * Offset  Length  COBOL PIC         Java Getter           Format
     * ------  ------  ----------------  --------------------  ----------------------
     *   0       16    TRAN-ID X(16)     getTranId()           Right-padded spaces
     *  16        2    TRAN-TYPE-CD X(2) getTranTypeCd()       Right-padded spaces
     *  18        4    TRAN-CAT-CD 9(4)  getTranCatCd()        Left-padded zeros
     *  22       10    TRAN-SOURCE X(10) getTranSource()       Right-padded spaces
     *  32      100    TRAN-DESC X(100)  getTranDesc()         Right-padded spaces
     * 132       11    TRAN-AMT S9(9)V99 getTranAmt()          Signed overpunch
     * 143        9    MERCHANT-ID 9(9)  getTranMerchantId()   Left-padded zeros
     * 152       50    MERCHANT-NAME     getTranMerchantName() Right-padded spaces
     * 202       50    MERCHANT-CITY     getTranMerchantCity() Right-padded spaces
     * 252       10    MERCHANT-ZIP      getTranMerchantZip()  Right-padded spaces
     * 262       16    TRAN-CARD-NUM     getTranCardNum()      Right-padded spaces
     * 278       26    TRAN-ORIG-TS      getTranOrigTs()       COBOL timestamp fmt
     * 304       26    TRAN-PROC-TS      getTranProcTs()       COBOL timestamp fmt
     * 330       20    FILLER X(20)      (none)                Spaces
     * </pre>
     *
     * @param txn the transaction entity to serialize
     * @return a 350-character fixed-width string representation
     */
    private String serializeToFixedWidth(Transaction txn) {
        StringBuilder sb = new StringBuilder(RECORD_LENGTH);

        // TRAN-ID PIC X(16) — alphanumeric, right-padded with spaces
        sb.append(padRight(nullSafe(txn.getTranId()), 16));

        // TRAN-TYPE-CD PIC X(02) — alphanumeric, right-padded with spaces
        sb.append(padRight(nullSafe(txn.getTranTypeCd()), 2));

        // TRAN-CAT-CD PIC 9(04) — numeric display, left-padded with zeros
        sb.append(padLeftZero(
                txn.getTranCatCd() != null ? String.valueOf(txn.getTranCatCd()) : "0", 4));

        // TRAN-SOURCE PIC X(10) — alphanumeric, right-padded with spaces
        sb.append(padRight(nullSafe(txn.getTranSource()), 10));

        // TRAN-DESC PIC X(100) — alphanumeric, right-padded with spaces
        sb.append(padRight(nullSafe(txn.getTranDesc()), 100));

        // TRAN-AMT PIC S9(09)V99 — 11 display positions, signed with overpunch
        sb.append(formatSignedDecimal(txn.getTranAmt(), 9, 2));

        // TRAN-MERCHANT-ID PIC 9(09) — numeric display, left-padded with zeros
        sb.append(padLeftZero(nullSafe(txn.getTranMerchantId()), 9));

        // TRAN-MERCHANT-NAME PIC X(50) — alphanumeric, right-padded with spaces
        sb.append(padRight(nullSafe(txn.getTranMerchantName()), 50));

        // TRAN-MERCHANT-CITY PIC X(50) — alphanumeric, right-padded with spaces
        sb.append(padRight(nullSafe(txn.getTranMerchantCity()), 50));

        // TRAN-MERCHANT-ZIP PIC X(10) — alphanumeric, right-padded with spaces
        sb.append(padRight(nullSafe(txn.getTranMerchantZip()), 10));

        // TRAN-CARD-NUM PIC X(16) — alphanumeric, right-padded with spaces
        sb.append(padRight(nullSafe(txn.getTranCardNum()), 16));

        // TRAN-ORIG-TS PIC X(26) — COBOL timestamp format YYYY-MM-DD-HH.MM.SS.NNNNNN
        sb.append(padRight(formatTimestamp(txn.getTranOrigTs()), 26));

        // TRAN-PROC-TS PIC X(26) — COBOL timestamp format YYYY-MM-DD-HH.MM.SS.NNNNNN
        sb.append(padRight(formatTimestamp(txn.getTranProcTs()), 26));

        // FILLER PIC X(20) — padding spaces (no data mapped)
        sb.append(padRight("", 20));

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Formatting Utility Methods
    // -----------------------------------------------------------------------

    /**
     * Formats a {@link BigDecimal} value as a COBOL signed numeric display string.
     *
     * <p>Implements the COBOL PIC S9(integerDigits)V9(decimalDigits) DISPLAY format
     * where the sign is encoded using ASCII overpunch on the last digit:
     * <ul>
     *   <li>Positive: digits as-is (0-9)</li>
     *   <li>Negative: last digit replaced with overpunch character
     *       (0→'}', 1→'J', 2→'K', 3→'L', 4→'M', 5→'N', 6→'O', 7→'P', 8→'Q', 9→'R')</li>
     * </ul>
     *
     * <p>CRITICAL: Uses {@link BigDecimal} arithmetic exclusively — zero floating-point
     * substitution per AAP §0.8.2.
     *
     * @param value         the decimal value to format (may be null)
     * @param integerDigits number of integer digit positions (e.g., 9 for S9(09))
     * @param decimalDigits number of decimal digit positions (e.g., 2 for V99)
     * @return a fixed-width string of exactly (integerDigits + decimalDigits) characters
     */
    private String formatSignedDecimal(BigDecimal value, int integerDigits, int decimalDigits) {
        int totalDigits = integerDigits + decimalDigits;

        if (value == null) {
            return "0".repeat(totalDigits);
        }

        // Scale the value to remove implied decimal (multiply by 10^decimalDigits)
        // Using BigDecimal arithmetic to preserve precision — no float/double
        long unscaled = value.movePointRight(decimalDigits).longValue();
        boolean negative = unscaled < 0;
        long absValue = Math.abs(unscaled);

        // Format as zero-padded digit string of exact length
        String digits = String.format("%0" + totalDigits + "d", absValue);

        // Ensure exact length (truncate overflow from left if value exceeds capacity)
        if (digits.length() > totalDigits) {
            digits = digits.substring(digits.length() - totalDigits);
        }

        // Apply negative overpunch on last digit if value is negative
        if (negative) {
            char lastDigit = digits.charAt(digits.length() - 1);
            int digitValue = lastDigit - '0';
            char overpunch = NEGATIVE_OVERPUNCH[digitValue];
            return digits.substring(0, digits.length() - 1) + overpunch;
        }

        return digits;
    }

    /**
     * Formats a {@link LocalDateTime} to the COBOL PIC X(26) timestamp convention:
     * {@code YYYY-MM-DD-HH.MM.SS.NNNNNN}.
     *
     * @param timestamp the timestamp to format (may be null)
     * @return formatted timestamp string, or empty string if null
     */
    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "";
        }
        return timestamp.format(COBOL_TIMESTAMP_FORMATTER);
    }

    /**
     * Returns the input string if non-null, or an empty string if null.
     * Prevents {@link NullPointerException} in fixed-width field formatting.
     *
     * @param value the string value to check
     * @return the input value or empty string
     */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    /**
     * Right-pads a string with spaces to the specified length.
     * If the input exceeds the target length, it is truncated.
     * Matches COBOL PIC X(n) alphanumeric field formatting.
     *
     * @param value  the string value to pad
     * @param length the target field length
     * @return a string of exactly {@code length} characters
     */
    private String padRight(String value, int length) {
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        return value + " ".repeat(length - value.length());
    }

    /**
     * Left-pads a string with zeros to the specified length.
     * If the input exceeds the target length, the rightmost digits are preserved.
     * Matches COBOL PIC 9(n) numeric display field formatting.
     *
     * @param value  the numeric string value to pad
     * @param length the target field length
     * @return a string of exactly {@code length} characters, left-padded with '0'
     */
    private String padLeftZero(String value, int length) {
        if (value.length() >= length) {
            return value.substring(value.length() - length);
        }
        return "0".repeat(length - value.length()) + value;
    }
}
