package com.carddemo.batch.jobs;

import com.carddemo.batch.processors.CombineTransactionsProcessor;
import com.carddemo.model.entity.Transaction;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.TransactionRepository;

import io.micrometer.core.instrument.MeterRegistry;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Spring Batch re-host of the mainframe combine job {@code app/jcl/COMBTRAN.jcl}
 * (source commit {@code 27d6c6f}; REFERENCE ONLY — no COBOL/JCL is copied).
 *
 * <p>The original JCL ran two sequential programs:</p>
 * <ol>
 *   <li>{@code STEP05R EXEC PGM=SORT} concatenated two generation-data-group (GDG)
 *       generation-0 datasets — {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} (the backed-up
 *       master transactions) followed by {@code AWS.M2.CARDDEMO.SYSTRAN(0)} (the interest
 *       transactions) — and sorted the combined stream ascending by the 16-byte
 *       {@code TRAN-ID} key ({@code SYMNAMES TRAN-ID,1,16,CH}; {@code SORT FIELDS=(TRAN-ID,A)}),
 *       writing the result to {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)}.</li>
 *   <li>{@code STEP10 EXEC PGM=IDCAMS} issued a {@code REPRO} that loaded the combined,
 *       sorted file into the master transaction KSDS (no {@code REPLACE} keyword).</li>
 * </ol>
 *
 * <p><b>Decision D-005</b> (AAP §0.8.5): the {@code DFSORT} sort is replaced by an in-memory
 * Java sort using the canonical {@link CombineTransactionsProcessor#BY_TRAN_ID} comparator
 * (ascending {@code tranId}, matching {@code SORT FIELDS=(1,16,CH,A)}), and the
 * {@code IDCAMS REPRO} is replaced by a bulk JPA insert via {@link TransactionRepository}.
 * <b>Decision D-003</b>: the GDG generations re-platform to S3 versioned objects, read and
 * written through the {@link S3Client} bean and exercised against LocalStack only; the bucket
 * and object keys resolve from configuration and are never hardcoded.</p>
 *
 * <p>The job exposes a two-step {@link Job} bean that runs the sort step and then, only on its
 * success, the load step (predecessor-success ordering, AAP §0.8.5). Jobs are not auto-run
 * ({@code spring.batch.job.enabled=false}); a pipeline orchestrator launches this job.</p>
 *
 * <p><b>Idempotent load (double-load guard).</b> {@code TRANSACT.BKUP} already contains the
 * daily-posted transactions that the posting job persisted to the {@code transaction} table, so
 * a blind insert would collide on the {@code tranId} primary key. The load step therefore relies
 * on {@link TransactionRepository#saveAll(Iterable)} performing a JPA merge for entities whose
 * {@code @Id} is set (always true here), turning the reload into an upsert that overwrites rather
 * than collides. The net post-condition mirrors the COBOL "rebuild the master" intent: the table
 * contains the daily posts plus the interest transactions, keyed by {@code tranId}. The rationale
 * for this idempotency choice is recorded in {@code DECISION_LOG.md}.</p>
 */
@Configuration(proxyBeanMethods = false)
public class CombineTransactionsJob {

    /** Canonical job name; referenced by the pipeline orchestrator to launch this job. */
    public static final String JOB_NAME = "combineTransactionsJob";

    /** Name of step 1 — the {@code DFSORT} replacement (read, parse, sort, stage COMBINED). */
    public static final String SORT_STEP_NAME = "combineSortStep";

    /** Name of step 2 — the {@code IDCAMS REPRO} replacement (load combined stream to the master). */
    public static final String LOAD_STEP_NAME = "combineLoadStep";

    /**
     * Number of records persisted per {@code saveAll} call in the load step. Bounds the size of
     * each repository call so a large combined stream is loaded in manageable batches.
     */
    public static final int BULK_INSERT_BATCH_SIZE = 500;

    /** Fixed record length of the {@code CVTRA05Y} transaction layout, in bytes. */
    static final int RECORD_LENGTH = 350;

    // ---------------------------------------------------------------------------------------------
    // CVTRA05Y fixed-width field boundaries (0-based, end-exclusive), mirroring the 350-byte
    // transaction record layout (identical to the daily-transaction CVTRA06Y layout). The 16-byte
    // TRAN-ID at positions 1-16 is the SORT key (SYMNAMES TRAN-ID,1,16,CH).
    // ---------------------------------------------------------------------------------------------
    private static final int TRAN_ID_BEGIN = 0;
    private static final int TRAN_ID_END = 16;
    private static final int TYPE_CD_BEGIN = 16;
    private static final int TYPE_CD_END = 18;
    private static final int CAT_CD_BEGIN = 18;
    private static final int CAT_CD_END = 22;
    private static final int SOURCE_BEGIN = 22;
    private static final int SOURCE_END = 32;
    private static final int DESC_BEGIN = 32;
    private static final int DESC_END = 132;
    private static final int AMT_BEGIN = 132;
    private static final int AMT_END = 143;
    private static final int MERCHANT_ID_BEGIN = 143;
    private static final int MERCHANT_ID_END = 152;
    private static final int MERCHANT_NAME_BEGIN = 152;
    private static final int MERCHANT_NAME_END = 202;
    private static final int MERCHANT_CITY_BEGIN = 202;
    private static final int MERCHANT_CITY_END = 252;
    private static final int MERCHANT_ZIP_BEGIN = 252;
    private static final int MERCHANT_ZIP_END = 262;
    private static final int CARD_NUM_BEGIN = 262;
    private static final int CARD_NUM_END = 278;
    private static final int ORIG_TS_BEGIN = 278;
    private static final int ORIG_TS_END = 304;
    private static final int PROC_TS_BEGIN = 304;
    private static final int PROC_TS_END = 330;

    /** Implied decimal positions of the {@code TRAN-AMT} field ({@code PIC S9(09)V99}). */
    private static final int AMOUNT_SCALE = 2;

    /** Content type recorded on the binary, fixed-width COMBINED S3 object. */
    private static final String CONTENT_TYPE = "application/octet-stream";

    private static final Logger LOGGER = LoggerFactory.getLogger(CombineTransactionsJob.class);

    private final S3Client s3Client;
    private final TransactionRepository transactionRepository;
    private final MeterRegistry meterRegistry;

    /** Bucket holding the BKUP, SYSTRAN, and COMBINED objects (all map to {@code bucket-output}). */
    private final String bucket;

    /** Object key of the backed-up master transactions input ({@code TRANSACT.BKUP} GDG base). */
    private final String bkupKey;

    /** Object key of the interest-transaction input ({@code SYSTRAN} GDG base). */
    private final String systranKey;

    /** Object key of the sorted COMBINED output ({@code TRANSACT.COMBINED} GDG base). */
    private final String combinedKey;

    /**
     * Creates the combine job configuration. The S3 endpoint, region, and credentials are resolved
     * by {@link com.carddemo.config.AwsConfig} from configuration (LocalStack for local/test), never
     * here; this constructor only binds the resource names so they stay externalized.
     *
     * @param s3Client              the synchronous S3 client bean
     * @param transactionRepository the transaction repository used for the bulk merge load
     * @param meterRegistry         the Micrometer registry used to count combined records processed
     * @param bucket                the S3 bucket holding the input and output objects
     * @param bkupKey               the {@code TRANSACT.BKUP} object key
     * @param systranKey            the {@code SYSTRAN} object key
     * @param combinedKey           the {@code TRANSACT.COMBINED} object key
     */
    public CombineTransactionsJob(
            S3Client s3Client,
            TransactionRepository transactionRepository,
            MeterRegistry meterRegistry,
            @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") String bucket,
            @Value("${carddemo.batch.combine.bkup-key:TRANSACT.BKUP}") String bkupKey,
            @Value("${carddemo.batch.combine.systran-key:SYSTRAN}") String systranKey,
            @Value("${carddemo.batch.combine.combined-key:TRANSACT.COMBINED}") String combinedKey) {
        this.s3Client = s3Client;
        this.transactionRepository = transactionRepository;
        this.meterRegistry = meterRegistry;
        this.bucket = bucket;
        this.bkupKey = bkupKey;
        this.systranKey = systranKey;
        this.combinedKey = combinedKey;
    }

    /**
     * Step 1 — the {@code DFSORT} replacement. Reads the BKUP then SYSTRAN objects (preserving the
     * JCL concatenation order), parses each 350-byte {@code CVTRA05Y} record, sorts the combined
     * stream ascending by {@code tranId} using {@link CombineTransactionsProcessor#BY_TRAN_ID}, and
     * stages the sorted stream as the COMBINED S3 object. Runs inside a step transaction managed by
     * the supplied transaction manager (no database writes occur in this step).
     *
     * @param jobRepository      the Spring Batch job repository (Boot-auto-configured)
     * @param transactionManager the platform transaction manager (Boot-auto-configured)
     * @return the configured sort step
     */
    @Bean
    public Step combineSortStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder(SORT_STEP_NAME, jobRepository)
                .tasklet(this::executeSortStep, transactionManager)
                .build();
    }

    /**
     * Step 2 — the {@code IDCAMS REPRO} replacement. Reads the sorted COMBINED S3 object and loads
     * every record into the {@code transaction} table via batched {@code saveAll} merges (the
     * idempotent double-load guard). Runs inside a step transaction so the load is atomic.
     *
     * @param jobRepository      the Spring Batch job repository (Boot-auto-configured)
     * @param transactionManager the platform transaction manager (Boot-auto-configured)
     * @return the configured load step
     */
    @Bean
    public Step combineLoadStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder(LOAD_STEP_NAME, jobRepository)
                .tasklet(this::executeLoadStep, transactionManager)
                .build();
    }

    /**
     * The two-step combine job. The load step runs only after the sort step succeeds, reproducing
     * the JCL's sequential, predecessor-success step ordering (AAP §0.8.5).
     *
     * @param jobRepository   the Spring Batch job repository (Boot-auto-configured)
     * @param combineSortStep the sort step bean (injected by name)
     * @param combineLoadStep the load step bean (injected by name)
     * @return the configured combine job, registered under {@link #JOB_NAME}
     */
    @Bean
    public Job combineTransactionsJob(
            JobRepository jobRepository, Step combineSortStep, Step combineLoadStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(combineSortStep)
                .next(combineLoadStep)
                .build();
    }

    /**
     * Executes the sort step: read BKUP then SYSTRAN, parse and pair each record with its raw
     * 350-byte image, sort ascending by {@code tranId}, and write the sorted raw records to the
     * COMBINED object (byte-faithful — the amount and every other field are preserved verbatim,
     * never re-encoded, so the COMBINED object honours the {@code CVTRA05Y} contract).
     *
     * @param contribution the step contribution used to record the write count
     * @param chunkContext the chunk context (unused; the whole stream is processed in one pass)
     * @return {@link RepeatStatus#FINISHED} — the tasklet completes in a single execution
     */
    private RepeatStatus executeSortStep(StepContribution contribution, ChunkContext chunkContext) {
        List<String> records = new ArrayList<>();
        records.addAll(extractRecords(readObject(bkupKey)));
        records.addAll(extractRecords(readObject(systranKey)));

        List<CombinedRecord> combined = new ArrayList<>(records.size());
        for (String record : records) {
            combined.add(new CombinedRecord(parseTransaction(record), record));
        }

        // DFSORT SORT FIELDS=(1,16,CH,A) -> ascending by the 16-char TRAN-ID (D-005).
        combined.sort(Comparator.comparing(CombinedRecord::transaction, CombineTransactionsProcessor.BY_TRAN_ID));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(combined.size() * RECORD_LENGTH);
        for (CombinedRecord item : combined) {
            buffer.writeBytes(item.raw().getBytes(StandardCharsets.ISO_8859_1));
        }
        writeObject(combinedKey, buffer.toByteArray());

        contribution.incrementWriteCount(combined.size());
        LOGGER.info("Combine sort step staged {} sorted transaction records to s3://{}/{}",
                combined.size(), bucket, combinedKey);
        return RepeatStatus.FINISHED;
    }

    /**
     * Executes the load step: read the sorted COMBINED object and bulk-merge every record into the
     * {@code transaction} table in batches of {@link #BULK_INSERT_BATCH_SIZE}. {@code saveAll}
     * merges entities whose {@code @Id} is set, so re-loading a record that already exists (for
     * example a daily-posted transaction carried in BKUP) updates it rather than colliding on the
     * primary key — the idempotent reproduction of {@code IDCAMS REPRO}.
     *
     * @param contribution the step contribution used to record the write count
     * @param chunkContext the chunk context (unused; the whole stream is loaded in one pass)
     * @return {@link RepeatStatus#FINISHED} — the tasklet completes in a single execution
     */
    private RepeatStatus executeLoadStep(StepContribution contribution, ChunkContext chunkContext) {
        List<String> records = extractRecords(readObject(combinedKey));

        int loaded = 0;
        List<Transaction> batch = new ArrayList<>(BULK_INSERT_BATCH_SIZE);
        for (String record : records) {
            batch.add(parseTransaction(record));
            if (batch.size() >= BULK_INSERT_BATCH_SIZE) {
                loaded += flushBatch(batch);
            }
        }
        loaded += flushBatch(batch);

        contribution.incrementWriteCount(loaded);
        LOGGER.info("Combine load step merged {} transaction records into the master table", loaded);
        return RepeatStatus.FINISHED;
    }

    /**
     * Persists one batch of transactions via an idempotent {@code saveAll} merge, records the
     * processed-records metric, and clears the batch for reuse. A no-op for an empty batch.
     *
     * @param batch the transactions to persist; cleared on return
     * @return the number of records persisted by this call
     */
    private int flushBatch(List<Transaction> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        transactionRepository.saveAll(batch);
        int count = batch.size();
        MetricsConfig.recordsProcessed(meterRegistry).increment(count);
        batch.clear();
        return count;
    }

    /**
     * Reads an S3 object from the configured bucket and returns its raw bytes. A missing object is
     * a hard error (the JCL referenced each input GDG generation with {@code DISP=SHR}, so it must
     * exist); any SDK failure is surfaced with the failing bucket and key for diagnostics.
     *
     * @param key the object key to read
     * @return the object's bytes (empty when the object is empty)
     * @throws IllegalStateException if the object is absent or the read fails
     */
    private byte[] readObject(String key) {
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return response.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new IllegalStateException(
                    "Required combine input object not found: s3://" + bucket + "/" + key, e);
        } catch (SdkException e) {
            throw new IllegalStateException(
                    "Failed to read combine object: s3://" + bucket + "/" + key, e);
        }
    }

    /**
     * Writes the sorted COMBINED payload to the configured bucket as a single binary object,
     * reproducing {@code SORTOUT -> COMBINED(+1)}. The object is written even when empty, mirroring
     * the JCL's unconditional creation of a new generation.
     *
     * @param key     the destination object key
     * @param payload the object bytes
     * @throws IllegalStateException if the write fails
     */
    private void writeObject(String key, byte[] payload) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(CONTENT_TYPE)
                            .build(),
                    RequestBody.fromBytes(payload));
        } catch (SdkException e) {
            throw new IllegalStateException(
                    "Failed to write combined object: s3://" + bucket + "/" + key, e);
        }
    }

    /**
     * Splits a raw object payload into fixed 350-byte {@code CVTRA05Y} records. The decoder is
     * tolerant of the two physical shapes a batch artifact can take: a delimiter-less binary stream
     * (an exact multiple of the record length — the canonical mainframe/GDG form), or a
     * newline-delimited text file. ISO-8859-1 is used so one byte maps to one character, preserving
     * the fixed-width offsets.
     *
     * @param raw the object bytes
     * @return the list of 350-character records (empty when there is no data)
     */
    private static List<String> extractRecords(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        String content = new String(raw, StandardCharsets.ISO_8859_1);
        int length = content.length();
        List<String> records = new ArrayList<>();

        if (length % RECORD_LENGTH == 0) {
            // Exact multiple -> delimiter-less fixed-width (binary GDG / COMBINED). A genuine
            // newline-delimited file is effectively never an exact multiple of the record length,
            // so this interpretation is safe and also tolerates a 0x0A byte inside a fixed field.
            for (int offset = 0; offset < length; offset += RECORD_LENGTH) {
                records.add(content.substring(offset, offset + RECORD_LENGTH));
            }
            return records;
        }

        if (content.indexOf('\n') >= 0) {
            // Newline-delimited text: each non-blank line is one record, normalized to 350 chars.
            for (String line : content.split("\n", -1)) {
                String record = stripTrailingCarriageReturn(line);
                if (!record.isBlank()) {
                    records.add(normalizeLength(record));
                }
            }
            return records;
        }

        // Delimiter-less but not a clean multiple: take the full records and tolerate only a
        // trailing run of blanks (padding); anything else is a corrupt partial record.
        int fullRecords = length / RECORD_LENGTH;
        for (int i = 0; i < fullRecords; i++) {
            records.add(content.substring(i * RECORD_LENGTH, (i + 1) * RECORD_LENGTH));
        }
        String tail = content.substring(fullRecords * RECORD_LENGTH);
        if (!tail.isBlank()) {
            throw new IllegalArgumentException(
                    "Combine input contains a trailing partial record of " + tail.length()
                            + " bytes (expected a multiple of " + RECORD_LENGTH + ")");
        }
        return records;
    }

    /**
     * Removes a single trailing carriage return so that {@code \r\n}-delimited records normalize to
     * the same value as {@code \n}-delimited records.
     *
     * @param line the raw line
     * @return the line without a trailing {@code \r}
     */
    private static String stripTrailingCarriageReturn(String line) {
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    /**
     * Pads (with spaces, the COBOL alphanumeric fill) or truncates a record to exactly
     * {@link #RECORD_LENGTH} characters so positional field extraction is always in range.
     *
     * @param record the record to normalize
     * @return a record of exactly {@link #RECORD_LENGTH} characters
     */
    private static String normalizeLength(String record) {
        if (record.length() == RECORD_LENGTH) {
            return record;
        }
        if (record.length() > RECORD_LENGTH) {
            return record.substring(0, RECORD_LENGTH);
        }
        return record + " ".repeat(RECORD_LENGTH - record.length());
    }

    /**
     * Parses one 350-byte {@code CVTRA05Y} record into a {@link Transaction}. Alphanumeric fields
     * are trimmed; the category code and merchant id are decoded as numerics; and the amount is
     * decoded from its zoned-decimal trailing-sign overpunch into a {@link BigDecimal} of
     * {@link #AMOUNT_SCALE} (no floating-point substitution — AAP §0.8.2). The trailing filler is
     * ignored.
     *
     * @param record the 350-character record
     * @return the parsed transaction
     * @throws IllegalArgumentException if the record cannot be parsed
     */
    private static Transaction parseTransaction(String record) {
        String row = normalizeLength(record);
        String tranId = row.substring(TRAN_ID_BEGIN, TRAN_ID_END).trim();
        try {
            Transaction transaction = new Transaction();
            transaction.setTranId(tranId);
            transaction.setTranTypeCd(row.substring(TYPE_CD_BEGIN, TYPE_CD_END).trim());
            transaction.setTranCatCd(parseNumericInt(row.substring(CAT_CD_BEGIN, CAT_CD_END)));
            transaction.setTranSource(row.substring(SOURCE_BEGIN, SOURCE_END).trim());
            transaction.setTranDesc(row.substring(DESC_BEGIN, DESC_END).trim());
            transaction.setTranAmt(decodeSignedAmount(row.substring(AMT_BEGIN, AMT_END)));
            transaction.setTranMerchantId(parseNumericLong(row.substring(MERCHANT_ID_BEGIN, MERCHANT_ID_END)));
            transaction.setTranMerchantName(row.substring(MERCHANT_NAME_BEGIN, MERCHANT_NAME_END).trim());
            transaction.setTranMerchantCity(row.substring(MERCHANT_CITY_BEGIN, MERCHANT_CITY_END).trim());
            transaction.setTranMerchantZip(row.substring(MERCHANT_ZIP_BEGIN, MERCHANT_ZIP_END).trim());
            transaction.setTranCardNum(row.substring(CARD_NUM_BEGIN, CARD_NUM_END).trim());
            transaction.setTranOrigTs(row.substring(ORIG_TS_BEGIN, ORIG_TS_END).trim());
            transaction.setTranProcTs(row.substring(PROC_TS_BEGIN, PROC_TS_END).trim());
            return transaction;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Failed to parse 350-byte CVTRA05Y transaction record (TRAN-ID='" + tranId + "')", e);
        }
    }

    /**
     * Parses a fixed-width numeric field into an {@link Integer}, treating an all-blank field as
     * zero (COBOL numeric fields default to zeros).
     *
     * @param field the raw field text
     * @return the parsed integer value
     */
    private static Integer parseNumericInt(String field) {
        String value = field.trim();
        return value.isEmpty() ? Integer.valueOf(0) : Integer.valueOf(value);
    }

    /**
     * Parses a fixed-width numeric field into a {@link Long}, treating an all-blank field as zero.
     *
     * @param field the raw field text
     * @return the parsed long value
     */
    private static Long parseNumericLong(String field) {
        String value = field.trim();
        return value.isEmpty() ? Long.valueOf(0L) : Long.valueOf(value);
    }

    /**
     * Decodes an 11-character zoned-decimal amount with a trailing-sign overpunch on the final byte
     * (nine integer digits plus two implied decimals) into a {@link BigDecimal} of
     * {@link #AMOUNT_SCALE}. A blank field yields {@code 0.00}. This mirrors the daily-transaction
     * reader's decoder exactly so the amount round-trips with byte fidelity.
     *
     * @param field the raw 11-character amount field
     * @return the decoded signed amount, scale {@link #AMOUNT_SCALE}
     * @throws IllegalArgumentException if the overpunch sign byte is invalid
     */
    private static BigDecimal decodeSignedAmount(String field) {
        String value = field == null ? "" : field.trim();
        if (value.isEmpty()) {
            return BigDecimal.ZERO.movePointLeft(AMOUNT_SCALE);
        }
        char sign = value.charAt(value.length() - 1);
        String head = value.substring(0, value.length() - 1);
        int lastDigit;
        boolean negative;
        switch (sign) {
            case '{' -> {
                lastDigit = 0;
                negative = false;
            }
            case 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I' -> {
                lastDigit = sign - 'A' + 1;
                negative = false;
            }
            case '}' -> {
                lastDigit = 0;
                negative = true;
            }
            case 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R' -> {
                lastDigit = sign - 'J' + 1;
                negative = true;
            }
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                lastDigit = sign - '0';
                negative = false;
            }
            default -> throw new IllegalArgumentException(
                    "Invalid overpunch sign '" + sign + "' in amount '" + field + "'");
        }
        BigDecimal magnitude = new BigDecimal(head + lastDigit).movePointLeft(AMOUNT_SCALE);
        return negative ? magnitude.negate() : magnitude;
    }

    /**
     * Pairing of a parsed {@link Transaction} (used for the {@code tranId} sort key) with its raw
     * 350-character record image (written verbatim to the COMBINED object, preserving every field).
     *
     * @param transaction the parsed transaction supplying the sort key
     * @param raw         the original 350-character record image
     */
    private record CombinedRecord(Transaction transaction, String raw) {
    }
}
