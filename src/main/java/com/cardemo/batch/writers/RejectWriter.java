package com.cardemo.batch.writers;

import com.cardemo.model.entity.DailyTransaction;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring Batch {@link ItemWriter} that writes rejected daily transactions to AWS S3,
 * replacing COBOL paragraph {@code 2500-WRITE-REJECT-REC} in {@code CBTRN02C.cbl}
 * (lines 446–465).
 *
 * <p>Each rejected record is formatted as a 430-byte fixed-length record preserving
 * the original COBOL record layout from {@code POSTTRAN.jcl} (DCB RECFM=F, LRECL=430):
 * <ul>
 *   <li>350 bytes — Transaction data (mirrors COBOL {@code REJECT-TRAN-DATA PIC X(350)}
 *       from CVTRA06Y.cpy DALYTRAN-RECORD layout)</li>
 *   <li>80 bytes — Validation trailer ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)} +
 *       {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)})</li>
 * </ul>
 *
 * <p>Output is written to S3 with key pattern {@code rejections/DALYREJS-{timestamp}.txt},
 * replacing the COBOL GDG generation {@code DALYREJS(+1)} from {@code POSTTRAN.jcl}
 * (lines 34–38) and the GDG base defined in {@code DALYREJS.jcl} with LIMIT(5).
 *
 * <h3>Reject Codes (from CBTRN02C.cbl validation paragraphs)</h3>
 * <ul>
 *   <li><strong>100</strong>: INVALID CARD NUMBER FOUND (1500-A-LOOKUP-XREF, line 386)</li>
 *   <li><strong>101</strong>: ACCOUNT RECORD NOT FOUND (1500-B-LOOKUP-ACCT, line 398)</li>
 *   <li><strong>102</strong>: OVERLIMIT TRANSACTION (1500-B-LOOKUP-ACCT, line 411)</li>
 *   <li><strong>103</strong>: TRANSACTION RECEIVED AFTER ACCT EXPIRATION (line 418)</li>
 * </ul>
 *
 * <h3>COBOL Source Reference</h3>
 * <pre>
 * 2500-WRITE-REJECT-REC.
 *     MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
 *     MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
 *     MOVE 8 TO APPL-RESULT
 *     WRITE FD-REJS-RECORD FROM REJECT-RECORD
 *     IF DALYREJS-STATUS = '00'
 *         MOVE 0 TO APPL-RESULT
 *     ELSE
 *         MOVE 12 TO APPL-RESULT
 *     END-IF
 *     IF APPL-AOK
 *         CONTINUE
 *     ELSE
 *         DISPLAY 'ERROR WRITING TO REJECTS FILE'
 *         PERFORM 9910-DISPLAY-IO-STATUS
 *         PERFORM 9999-ABEND-PROGRAM
 *     END-IF
 * </pre>
 *
 * <p>All S3 interactions are testable against LocalStack with zero live AWS
 * dependencies, per AAP §0.7.7.</p>
 *
 * @see com.cardemo.model.entity.DailyTransaction
 */
@Component
public class RejectWriter implements ItemWriter<DailyTransaction> {

    private static final Logger log = LoggerFactory.getLogger(RejectWriter.class);

    // -------------------------------------------------------------------------
    // COBOL Record Layout Constants (CBTRN02C.cbl lines 176–182, POSTTRAN.jcl
    // DCB=(RECFM=F,LRECL=430,BLKSIZE=0))
    // -------------------------------------------------------------------------

    /** Length of transaction data portion: REJECT-TRAN-DATA PIC X(350). */
    private static final int TRANSACTION_DATA_LENGTH = 350;

    /** Length of validation trailer: VALIDATION-TRAILER PIC X(80). */
    private static final int VALIDATION_TRAILER_LENGTH = 80;

    /** Total rejection record length: 350 + 80 = 430 bytes (JCL DCB LRECL=430). */
    static final int TOTAL_RECORD_LENGTH = TRANSACTION_DATA_LENGTH + VALIDATION_TRAILER_LENGTH;

    // -------------------------------------------------------------------------
    // COBOL Field Widths from CVTRA06Y.cpy (DALYTRAN-RECORD, 350 bytes total)
    // -------------------------------------------------------------------------

    /** DALYTRAN-ID PIC X(16). */
    private static final int LEN_TRAN_ID = 16;

    /** DALYTRAN-TYPE-CD PIC X(02). */
    private static final int LEN_TYPE_CD = 2;

    /** DALYTRAN-CAT-CD PIC 9(04). */
    private static final int LEN_CAT_CD = 4;

    /** DALYTRAN-SOURCE PIC X(10). */
    private static final int LEN_SOURCE = 10;

    /** DALYTRAN-DESC PIC X(100). */
    private static final int LEN_DESC = 100;

    /** DALYTRAN-AMT PIC S9(09)V99 — 11 bytes in DISPLAY mode. */
    private static final int LEN_AMT = 11;

    /** DALYTRAN-MERCHANT-ID PIC 9(09). */
    private static final int LEN_MERCHANT_ID = 9;

    /** DALYTRAN-MERCHANT-NAME PIC X(50). */
    private static final int LEN_MERCHANT_NAME = 50;

    /** DALYTRAN-MERCHANT-CITY PIC X(50). */
    private static final int LEN_MERCHANT_CITY = 50;

    /** DALYTRAN-MERCHANT-ZIP PIC X(10). */
    private static final int LEN_MERCHANT_ZIP = 10;

    /** DALYTRAN-CARD-NUM PIC X(16). */
    private static final int LEN_CARD_NUM = 16;

    /** DALYTRAN-ORIG-TS PIC X(26). */
    private static final int LEN_ORIG_TS = 26;

    /** DALYTRAN-PROC-TS PIC X(26). */
    private static final int LEN_PROC_TS = 26;

    /** FILLER PIC X(20) — unused record padding. */
    private static final int LEN_FILLER = 20;

    // -------------------------------------------------------------------------
    // Validation Trailer Field Widths (CBTRN02C.cbl lines 180–182)
    // -------------------------------------------------------------------------

    /** WS-VALIDATION-FAIL-REASON PIC 9(04). */
    private static final int LEN_REJECT_CODE = 4;

    /** WS-VALIDATION-FAIL-REASON-DESC PIC X(76). */
    private static final int LEN_REJECT_DESC = 76;

    // -------------------------------------------------------------------------
    // Formatting Constants
    // -------------------------------------------------------------------------

    /**
     * DateTimeFormatter for S3 key timestamps — replaces COBOL GDG generation
     * numbering DALYREJS(+1) from POSTTRAN.jcl with unique timestamped S3 keys.
     */
    private static final DateTimeFormatter S3_KEY_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * DateTimeFormatter for COBOL DB2-FORMAT-TS PIC X(26).
     * Format: "yyyy-MM-dd-HH.mm.ss.000000" (26 characters total).
     */
    private static final String COBOL_TS_FORMAT = "%04d-%02d-%02d-%02d.%02d.%02d.%06d";

    /** Default reject code when no rejection info is registered for a transaction. */
    private static final int DEFAULT_REJECT_CODE = 0;

    /** Default reject description when no rejection info is registered. */
    private static final String DEFAULT_REJECT_DESC = "UNSPECIFIED REJECTION REASON";

    // -------------------------------------------------------------------------
    // Inner Types
    // -------------------------------------------------------------------------

    /**
     * Immutable record holding rejection metadata for a daily transaction.
     * Maps COBOL {@code WS-VALIDATION-TRAILER} structure (lines 180–182).
     *
     * @param rejectCode        4-digit numeric reject code (100, 101, 102, 103)
     * @param rejectDescription rejection reason description (max 76 chars)
     */
    public record RejectionInfo(int rejectCode, String rejectDescription) { }

    // -------------------------------------------------------------------------
    // Dependencies and Configuration
    // -------------------------------------------------------------------------

    /** AWS S3 client for writing rejection files — provided by AwsConfig bean. */
    private final S3Client s3Client;

    /** S3 bucket name for rejection output (configurable via application.yml). */
    private final String outputBucket;

    /** S3 key prefix for rejection files (configurable via application.yml). */
    private final String rejectPrefix;

    /**
     * Rejection metadata registry, keyed by transaction ID (DALYTRAN-ID).
     * Populated by the upstream {@code TransactionPostingProcessor} before each
     * chunk is written. Thread-safe for potential multi-threaded step execution.
     */
    private final Map<String, RejectionInfo> rejectionRegistry = new ConcurrentHashMap<>();

    /**
     * Rejection counter mirroring COBOL {@code WS-REJECT-COUNT PIC 9(09) VALUE 0}
     * (CBTRN02C.cbl line 186). Tracks total rejected transactions across all chunks
     * in a single batch run. Designed for single-threaded step execution matching
     * COBOL sequential processing semantics.
     */
    private long rejectCount;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs a new {@code RejectWriter} with injected AWS S3 client and
     * externalized bucket configuration.
     *
     * <p>Bucket and prefix are configurable via {@code application.yml} properties,
     * ensuring zero hardcoded AWS resource names per AAP §0.8.1. Default values
     * match the LocalStack provisioning in {@code localstack-init/init-aws.sh}.</p>
     *
     * @param s3Client     AWS S3 client bean (provided by AwsConfig)
     * @param outputBucket S3 bucket name for rejection output (default: carddemo-batch-output)
     * @param rejectPrefix S3 key prefix for rejection files (default: rejections/)
     */
    public RejectWriter(
            S3Client s3Client,
            @Value("${carddemo.s3.output-bucket:carddemo-batch-output}") String outputBucket,
            @Value("${carddemo.s3.reject-prefix:rejections/}") String rejectPrefix) {
        this.s3Client = s3Client;
        this.outputBucket = outputBucket;
        this.rejectPrefix = rejectPrefix;
        this.rejectCount = 0;
    }

    // -------------------------------------------------------------------------
    // ItemWriter Contract — write()
    // -------------------------------------------------------------------------

    /**
     * Writes a chunk of rejected {@link DailyTransaction} records to AWS S3 as a
     * fixed-length 430-byte record file. This method is the Java equivalent of
     * COBOL paragraph {@code 2500-WRITE-REJECT-REC} in {@code CBTRN02C.cbl}
     * (lines 446–465).
     *
     * <p>Processing flow (mirrors COBOL control flow):
     * <ol>
     *   <li>For each transaction in the chunk, look up rejection metadata from
     *       the registry (set by upstream processor)</li>
     *   <li>Format each as a 430-byte record: 350 bytes transaction data
     *       ({@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}) +
     *       80 bytes validation trailer
     *       ({@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER})</li>
     *   <li>Concatenate all records and upload as single S3 object
     *       ({@code WRITE FD-REJS-RECORD FROM REJECT-RECORD})</li>
     *   <li>Increment the rejection counter
     *       ({@code ADD 1 TO WS-REJECT-COUNT} in main loop, line 214)</li>
     * </ol>
     *
     * <p>If the S3 write fails, an exception is thrown — this maps the COBOL
     * ABEND behavior where {@code DALYREJS-STATUS != '00'} triggers
     * {@code PERFORM 9999-ABEND-PROGRAM}.</p>
     *
     * @param chunk the chunk of rejected daily transactions to write
     * @throws Exception if S3 upload fails (equivalent to COBOL ABEND)
     */
    @Override
    public void write(Chunk<? extends DailyTransaction> chunk) throws Exception {
        if (chunk.isEmpty()) {
            return;
        }

        int chunkSize = chunk.size();
        StringBuilder recordBuffer = new StringBuilder(chunkSize * (TOTAL_RECORD_LENGTH + 1));

        for (DailyTransaction txn : chunk.getItems()) {
            RejectionInfo rejInfo = rejectionRegistry.getOrDefault(
                    txn.getDalytranId(),
                    new RejectionInfo(DEFAULT_REJECT_CODE, DEFAULT_REJECT_DESC));

            String record = formatRejectionRecord(txn, rejInfo.rejectCode(), rejInfo.rejectDescription());
            recordBuffer.append(record);
            recordBuffer.append('\n');

            log.info("Rejection record formatted for transaction: {}", txn.toString());
        }

        String s3Key = generateS3Key();
        uploadToS3(s3Key, recordBuffer.toString());

        rejectCount += chunkSize;

        // Clean up registered rejections for processed items to prevent memory leaks
        for (DailyTransaction txn : chunk.getItems()) {
            rejectionRegistry.remove(txn.getDalytranId());
        }

        log.info("Wrote {} rejection records to S3: {}/{}", chunkSize, outputBucket, s3Key);
    }

    // -------------------------------------------------------------------------
    // Rejection Registration (for upstream processor integration)
    // -------------------------------------------------------------------------

    /**
     * Registers rejection metadata for a daily transaction. Called by the upstream
     * processor ({@code TransactionPostingProcessor}) when a transaction fails
     * the 4-stage validation cascade (CBTRN02C.cbl paragraphs 1500-A through 1500-B).
     *
     * <p>This method must be called before the rejected transaction is passed to
     * the {@link #write(Chunk)} method so that the correct reject code and
     * description are included in the 80-byte validation trailer.</p>
     *
     * @param dalytranId        transaction ID (16-char primary key from DALYTRAN-ID)
     * @param rejectCode        4-digit reject code: 100=invalid card, 101=account not found,
     *                          102=overlimit, 103=expired card
     * @param rejectDescription rejection reason description (max 76 chars, right-padded)
     */
    public void registerRejection(String dalytranId, int rejectCode, String rejectDescription) {
        rejectionRegistry.put(dalytranId, new RejectionInfo(rejectCode, rejectDescription));
    }

    // -------------------------------------------------------------------------
    // Public Accessors — Counter Management
    // -------------------------------------------------------------------------

    /**
     * Returns the total count of rejected transactions written across all chunks
     * in the current batch run. Mirrors COBOL {@code WS-REJECT-COUNT PIC 9(09)}
     * (CBTRN02C.cbl line 186), which is displayed at job completion:
     * {@code DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT} (line 228).
     *
     * @return total rejection count since last reset
     */
    public long getRejectCount() {
        return rejectCount;
    }

    /**
     * Resets the rejection counter to zero and clears the rejection registry.
     * Called at the start of each batch run. Mirrors COBOL initialization:
     * {@code 05 WS-REJECT-COUNT PIC 9(09) VALUE 0} (line 186).
     *
     * <p>Also used by batch job configuration to prepare the writer for a new run,
     * ensuring no stale rejection data from a previous execution.</p>
     */
    public void resetRejectCount() {
        rejectCount = 0;
        rejectionRegistry.clear();
    }

    // -------------------------------------------------------------------------
    // Private Helpers — Record Formatting
    // -------------------------------------------------------------------------

    /**
     * Formats a single 430-byte rejection record from a {@link DailyTransaction}
     * and its validation failure metadata.
     *
     * <p>COBOL record layout (CBTRN02C.cbl lines 176–178):
     * <pre>
     * 01 REJECT-RECORD.
     *    05 REJECT-TRAN-DATA          PIC X(350).
     *    05 VALIDATION-TRAILER        PIC X(80).
     * </pre>
     *
     * @param txn        the rejected daily transaction entity
     * @param rejectCode 4-digit numeric reject code (100, 101, 102, 103)
     * @param rejectDesc rejection reason description (max 76 chars)
     * @return 430-character fixed-length rejection record
     */
    private String formatRejectionRecord(DailyTransaction txn, int rejectCode, String rejectDesc) {
        String transactionData = formatTransactionData(txn);
        String validationTrailer = formatValidationTrailer(rejectCode, rejectDesc);
        return transactionData + validationTrailer;
    }

    /**
     * Formats the 350-byte transaction data portion of the rejection record.
     * Maps each field from the {@link DailyTransaction} entity to its fixed-width
     * ASCII representation, preserving the COBOL DALYTRAN-RECORD layout (CVTRA06Y.cpy).
     *
     * <p>Field layout (16+2+4+10+100+11+9+50+50+10+16+26+26+20 = 350):
     * <pre>
     * DALYTRAN-ID           PIC X(16)     positions   1– 16
     * DALYTRAN-TYPE-CD      PIC X(02)     positions  17– 18
     * DALYTRAN-CAT-CD       PIC 9(04)     positions  19– 22
     * DALYTRAN-SOURCE       PIC X(10)     positions  23– 32
     * DALYTRAN-DESC         PIC X(100)    positions  33–132
     * DALYTRAN-AMT          PIC S9(09)V99 positions 133–143
     * DALYTRAN-MERCHANT-ID  PIC 9(09)     positions 144–152
     * DALYTRAN-MERCHANT-NAME PIC X(50)    positions 153–202
     * DALYTRAN-MERCHANT-CITY PIC X(50)    positions 203–252
     * DALYTRAN-MERCHANT-ZIP PIC X(10)     positions 253–262
     * DALYTRAN-CARD-NUM     PIC X(16)     positions 263–278
     * DALYTRAN-ORIG-TS      PIC X(26)     positions 279–304
     * DALYTRAN-PROC-TS      PIC X(26)     positions 305–330
     * FILLER                PIC X(20)     positions 331–350
     * </pre>
     *
     * @param txn the daily transaction entity
     * @return 350-character fixed-width transaction data string
     */
    private String formatTransactionData(DailyTransaction txn) {
        StringBuilder sb = new StringBuilder(TRANSACTION_DATA_LENGTH);

        sb.append(padRight(nullSafe(txn.getDalytranId()), LEN_TRAN_ID));
        sb.append(padRight(nullSafe(txn.getDalytranTypeCd()), LEN_TYPE_CD));
        sb.append(formatCategoryCode(txn.getDalytranCatCd()));
        sb.append(padRight(nullSafe(txn.getDalytranSource()), LEN_SOURCE));
        sb.append(padRight(nullSafe(txn.getDalytranDesc()), LEN_DESC));
        sb.append(formatAmount(txn.getDalytranAmt()));
        sb.append(padRight(nullSafe(txn.getDalytranMerchantId()), LEN_MERCHANT_ID));
        sb.append(padRight(nullSafe(txn.getDalytranMerchantName()), LEN_MERCHANT_NAME));
        sb.append(padRight(nullSafe(txn.getDalytranMerchantCity()), LEN_MERCHANT_CITY));
        sb.append(padRight(nullSafe(txn.getDalytranMerchantZip()), LEN_MERCHANT_ZIP));
        sb.append(padRight(nullSafe(txn.getDalytranCardNum()), LEN_CARD_NUM));
        sb.append(formatTimestamp(txn.getDalytranOrigTs()));
        sb.append(formatTimestamp(txn.getDalytranProcTs()));
        sb.append(padRight("", LEN_FILLER));

        return sb.toString();
    }

    /**
     * Formats the 80-byte validation trailer containing the reject code and description.
     *
     * <p>COBOL layout (CBTRN02C.cbl lines 180–182):
     * <pre>
     * 01 WS-VALIDATION-TRAILER.
     *    05 WS-VALIDATION-FAIL-REASON      PIC 9(04).
     *    05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
     * </pre>
     *
     * @param rejectCode 4-digit reject code (zero-padded, e.g., "0100")
     * @param rejectDesc rejection description (right-padded to 76 chars)
     * @return 80-character validation trailer string
     */
    private String formatValidationTrailer(int rejectCode, String rejectDesc) {
        String code = String.format("%0" + LEN_REJECT_CODE + "d", rejectCode);
        String desc = padRight(nullSafe(rejectDesc), LEN_REJECT_DESC);
        return code + desc;
    }

    /**
     * Formats a {@link BigDecimal} amount as an 11-character string matching
     * COBOL {@code DALYTRAN-AMT PIC S9(09)V99} in DISPLAY representation.
     *
     * <p>The COBOL PIC S9(09)V99 stores 11 bytes: 9 integer digits + 2 implied
     * decimal digits, with the sign embedded in the zone portion of the last digit.
     * For the ASCII migration, this is represented as:</p>
     * <ul>
     *   <li>Non-negative: 11 zero-padded digits (e.g., {@code "00001234567"} for 12345.67)</li>
     *   <li>Negative: leading {@code -} + 10 zero-padded digits
     *       (e.g., {@code "-0001234567"} for -12345.67)</li>
     * </ul>
     *
     * <p><strong>CRITICAL</strong>: Uses {@link BigDecimal} exclusively — zero
     * {@code float}/{@code double} usage per AAP §0.8.2.</p>
     *
     * @param amount the transaction amount (BigDecimal, precision 11, scale 2)
     * @return 11-character fixed-width amount string
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0".repeat(LEN_AMT);
        }
        long cents = amount.movePointRight(2).longValue();
        if (cents >= 0) {
            return String.format("%011d", cents);
        } else {
            return String.format("-%010d", Math.abs(cents));
        }
    }

    /**
     * Formats a category code as a 4-digit zero-padded string matching
     * COBOL {@code DALYTRAN-CAT-CD PIC 9(04)}.
     *
     * @param catCd the category code (may be null)
     * @return 4-character zero-padded string (e.g., "0100")
     */
    private String formatCategoryCode(Short catCd) {
        if (catCd == null) {
            return "0".repeat(LEN_CAT_CD);
        }
        return String.format("%0" + LEN_CAT_CD + "d", catCd.intValue());
    }

    /**
     * Formats a {@link LocalDateTime} as a 26-character COBOL-compatible timestamp
     * string matching the DB2-FORMAT-TS layout: {@code "yyyy-MM-dd-HH.mm.ss.SSSSSS"}.
     *
     * <p>COBOL layout (CBTRN02C.cbl lines 160–174):
     * <pre>
     * 01 FILLER REDEFINES DB2-FORMAT-TS.
     *     06 DB2-YYYY    PIC X(004).   yyyy
     *     06 DB2-STREEP-1 PIC X.       -
     *     06 DB2-MM      PIC X(002).   MM
     *     06 DB2-STREEP-2 PIC X.       -
     *     06 DB2-DD      PIC X(002).   dd
     *     06 DB2-STREEP-3 PIC X.       -
     *     06 DB2-HH      PIC X(002).   HH
     *     06 DB2-DOT-1   PIC X.        .
     *     06 DB2-MIN     PIC X(002).   mm
     *     06 DB2-DOT-2   PIC X.        .
     *     06 DB2-SS      PIC X(002).   ss
     *     06 DB2-DOT-3   PIC X.        .
     *     06 DB2-MIL     PIC 9(002).   SS
     *     06 DB2-REST    PIC X(04).    ssss
     * Total: 4+1+2+1+2+1+2+1+2+1+2+1+2+4 = 26
     * </pre>
     *
     * @param ts the timestamp (may be null — produces 26 spaces)
     * @return 26-character COBOL-format timestamp string
     */
    private String formatTimestamp(LocalDateTime ts) {
        if (ts == null) {
            return " ".repeat(LEN_ORIG_TS);
        }
        String formatted = String.format(COBOL_TS_FORMAT,
                ts.getYear(), ts.getMonthValue(), ts.getDayOfMonth(),
                ts.getHour(), ts.getMinute(), ts.getSecond(),
                ts.getNano() / 1000);
        return padRight(formatted, LEN_ORIG_TS);
    }

    // -------------------------------------------------------------------------
    // Private Helpers — S3 Operations
    // -------------------------------------------------------------------------

    /**
     * Generates a unique S3 key for the rejection file, replacing COBOL GDG
     * generation numbering {@code DALYREJS(+1)} from {@code POSTTRAN.jcl} (line 38)
     * with timestamped keys providing equivalent generation semantics.
     *
     * <p>Key format: {@code {prefix}DALYREJS-{yyyyMMddHHmmss}.txt}</p>
     *
     * @return S3 object key for the rejection file
     */
    private String generateS3Key() {
        String timestamp = LocalDateTime.now().format(S3_KEY_TIMESTAMP_FORMATTER);
        return rejectPrefix + "DALYREJS-" + timestamp + ".txt";
    }

    /**
     * Uploads the formatted rejection records to AWS S3 using {@link S3Client#putObject}.
     *
     * <p>Maps COBOL {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} (line 451).
     * If the write fails, throws an exception mapping the COBOL ABEND behavior:
     * {@code DISPLAY 'ERROR WRITING TO REJECTS FILE'} followed by
     * {@code PERFORM 9999-ABEND-PROGRAM} (lines 460–463).</p>
     *
     * @param s3Key   the S3 object key
     * @param content the formatted rejection records content
     * @throws Exception if S3 upload fails (maps to COBOL ABEND on file write error)
     */
    private void uploadToS3(String s3Key, String content) throws Exception {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(outputBucket)
                    .key(s3Key)
                    .contentType("text/plain")
                    .build();

            RequestBody body = RequestBody.fromString(content, StandardCharsets.UTF_8);
            s3Client.putObject(request, body);
        } catch (S3Exception e) {
            log.error("ERROR WRITING TO REJECTS FILE: S3 upload failed for key {}/{}: {}",
                    outputBucket, s3Key, e.getMessage());
            throw new Exception("Failed to write rejection records to S3: " + s3Key, e);
        }
    }

    // -------------------------------------------------------------------------
    // Private Helpers — String Utilities
    // -------------------------------------------------------------------------

    /**
     * Right-pads or truncates a string to the specified exact length using spaces.
     * Matches COBOL alphanumeric field behavior where PIC X fields are space-padded
     * on the right.
     *
     * @param value  the input string
     * @param length the exact target field length
     * @return fixed-length string (exactly {@code length} characters)
     */
    private static String padRight(String value, int length) {
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        return value + " ".repeat(length - value.length());
    }

    /**
     * Returns the input string or empty string if null. Prevents
     * {@link NullPointerException} during fixed-width field formatting.
     *
     * @param value the input string (may be null)
     * @return the input string, or empty string if null
     */
    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
