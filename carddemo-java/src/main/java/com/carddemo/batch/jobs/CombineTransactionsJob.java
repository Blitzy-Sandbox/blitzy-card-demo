package com.carddemo.batch.jobs;

import com.carddemo.batch.processors.CombineTransactionsProcessor;
import com.carddemo.model.entity.Transaction;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Spring Batch configuration that re-hosts the mainframe JCL job {@code COMBTRAN.jcl}
 * (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; the COBOL/JCL is not copied).
 *
 * <p>The original two-step job stream is reproduced as a two-step Spring Batch {@link Job}:</p>
 * <ol>
 *   <li><strong>{@code STEP05R EXEC PGM=SORT}</strong> &mdash; concatenates the master
 *       transaction backup ({@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)}) <em>followed by</em> the
 *       interest transactions staged by the interest-calculation job
 *       ({@code AWS.M2.CARDDEMO.SYSTRAN(0)}), orders the merged stream ascending by the 16-byte
 *       transaction id ({@code SORT FIELDS=(1,16,CH,A)}), and writes the sorted result to a new
 *       generation ({@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)}).</li>
 *   <li><strong>{@code STEP10 EXEC PGM=IDCAMS}</strong> &mdash; {@code REPRO}s the combined,
 *       sorted stream into the transaction master KSDS.</li>
 * </ol>
 *
 * <h2>DFSORT + IDCAMS REPRO &rarr; Comparator + bulk JPA insert</h2>
 * <p>The {@code DFSORT} step is replaced by an in-memory Java sort using the canonical comparator
 * {@link CombineTransactionsProcessor#BY_TRAN_ID} (ascending {@code tranId}, identical to
 * {@code SORT FIELDS=(1,16,CH,A)}); the {@code IDCAMS REPRO} step is replaced by a bulk JPA
 * upsert into the {@code transaction} table via {@link TransactionRepository#saveAll}.</p>
 *
 * <h2>GDG generations &rarr; S3 versioned objects</h2>
 * <p>Each GDG {@code (0)}/{@code (+1)} generation maps to an S3 object read and written through
 * the {@link S3Client} bean supplied by {@code com.carddemo.config.AwsConfig}. The endpoint and
 * credentials are resolved from {@code spring.cloud.aws.*} (LocalStack for the {@code local} and
 * {@code test} profiles); the bucket and object keys are resolved from configuration and are
 * <strong>never</strong> hardcoded.</p>
 *
 * <h2>Idempotent &quot;rebuild the master&quot; load (double-load guard)</h2>
 * <p>The {@code TRANSACT.BKUP} input already contains the daily-posted transactions that the
 * daily-posting job persisted to the {@code transaction} table, so a blind insert would collide
 * on the {@code tranId} primary key. The COBOL intent &mdash; rebuild the master from the combined
 * stream &mdash; is reproduced idempotently with {@link TransactionRepository#saveAll}, which
 * performs a JPA merge for every entity whose {@code @Id} is set: existing rows are overwritten
 * and absent rows are inserted. Re-running the load therefore never throws a duplicate-key error,
 * and the post-condition mirrors the mainframe (the table equals daily posts plus interest, keyed
 * by {@code tranId}).</p>
 *
 * <h2>Wiring</h2>
 * <p>This is a {@code @Configuration} only; the job is <strong>not</strong> auto-run
 * ({@code spring.batch.job.enabled=false}) and is launched by the batch pipeline orchestrator.
 * The {@link JobRepository} and {@link PlatformTransactionManager} are the beans auto-configured
 * by Spring Boot (see {@code com.carddemo.config.BatchConfig}, which intentionally does not declare
 * them). The two steps are wired predecessor-success
 * ({@code start(combineSortStep).next(combineLoadStep)}), so the load step runs only after the
 * sort step completes successfully.</p>
 *
 * <p>All decimal amounts are handled with {@link BigDecimal} (scale 2, {@link RoundingMode#HALF_EVEN}
 * where rounding is required); {@code float}/{@code double} are never used for monetary values.</p>
 */
@Configuration(value = "combineTransactionsJobConfig", proxyBeanMethods = false)
public final class CombineTransactionsJob {

    /** Logger for combine-stage progress and S3 access diagnostics. */
    private static final Logger LOGGER = LoggerFactory.getLogger(CombineTransactionsJob.class);

    /** Canonical Spring Batch job name (also the {@code COMBTRAN.jcl} job identity). */
    public static final String JOB_NAME = "combineTransactionsJob";

    /** Bean name and step name of the sort step ({@code STEP05R}, the DFSORT replacement). */
    public static final String SORT_STEP_NAME = "combineSortStep";

    /** Bean name and step name of the load step ({@code STEP10}, the IDCAMS REPRO replacement). */
    public static final String LOAD_STEP_NAME = "combineLoadStep";

    /** Number of records persisted per {@code saveAll} call during the bulk upsert load. */
    static final int BATCH_INSERT_SIZE = 200;

    /** Fixed record length of the {@code CVTRA05Y} transaction layout (RECLN = 350). */
    static final int RECORD_LENGTH = 350;

    /**
     * Single-byte charset used for all record I/O. ISO-8859-1 maps every byte to exactly one
     * character, preserving the byte-exact fixed-width offsets of the 350-byte layout.
     */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /** MIME content type recorded on the combined S3 object. */
    private static final String COMBINED_CONTENT_TYPE = "text/plain";

    // --- CVTRA05Y field boundaries (0-based, end-exclusive) mirroring the 350-byte layout -------
    private static final int TRAN_ID_BEGIN = 0;
    private static final int TRAN_ID_END = 16;
    private static final int TRAN_TYPE_CD_END = 18;
    private static final int TRAN_CAT_CD_END = 22;
    private static final int TRAN_SOURCE_END = 32;
    private static final int TRAN_DESC_END = 132;
    private static final int TRAN_AMT_END = 143;
    private static final int TRAN_MERCHANT_ID_END = 152;
    private static final int TRAN_MERCHANT_NAME_END = 202;
    private static final int TRAN_MERCHANT_CITY_END = 252;
    private static final int TRAN_MERCHANT_ZIP_END = 262;
    private static final int TRAN_CARD_NUM_END = 278;
    private static final int TRAN_ORIG_TS_END = 304;
    private static final int TRAN_PROC_TS_END = 330;

    // --- CVTRA05Y field widths used when re-encoding a record ----------------------------------
    private static final int WIDTH_TRAN_ID = 16;
    private static final int WIDTH_TRAN_TYPE_CD = 2;
    private static final int WIDTH_TRAN_CAT_CD = 4;
    private static final int WIDTH_TRAN_SOURCE = 10;
    private static final int WIDTH_TRAN_DESC = 100;
    private static final int WIDTH_TRAN_AMT = 11;
    private static final int WIDTH_TRAN_MERCHANT_ID = 9;
    private static final int WIDTH_TRAN_MERCHANT_NAME = 50;
    private static final int WIDTH_TRAN_MERCHANT_CITY = 50;
    private static final int WIDTH_TRAN_MERCHANT_ZIP = 10;
    private static final int WIDTH_TRAN_CARD_NUM = 16;
    private static final int WIDTH_TRAN_ORIG_TS = 26;
    private static final int WIDTH_TRAN_PROC_TS = 26;
    private static final int WIDTH_FILLER = 20;

    /** Monetary scale of the {@code TRAN-AMT PIC S9(09)V99} field. */
    private static final int AMOUNT_SCALE = 2;

    /** Synchronous S3 client (see {@code com.carddemo.config.AwsConfig}) for all GDG-mapped objects. */
    private final S3Client s3Client;

    /** Repository used for the idempotent bulk upsert that replaces {@code IDCAMS REPRO}. */
    private final TransactionRepository transactionRepository;

    /** Micrometer registry used to record combined-stage throughput. */
    private final MeterRegistry meterRegistry;

    /** S3 bucket holding the combine-stage staging/output objects (BKUP, SYSTRAN, COMBINED). */
    private final String stagingBucket;

    /** Object key of the master-transaction backup input ({@code TRANSACT.BKUP(0)}). */
    private final String bkupKey;

    /** Object key of the interest-transaction staging input ({@code SYSTRAN(0)}). */
    private final String systranKey;

    /** Object key of the sorted combined output ({@code TRANSACT.COMBINED(+1)}). */
    private final String combinedKey;

    /**
     * Creates the combine-job configuration with its collaborators and externalized S3 coordinates.
     *
     * @param s3Client              synchronous S3 client bean (from {@code AwsConfig}); never {@code null}
     * @param transactionRepository transaction repository for the bulk upsert load; never {@code null}
     * @param meterRegistry         Micrometer registry for throughput metrics; never {@code null}
     * @param stagingBucket         S3 bucket for combine staging objects, resolved from
     *                              {@code carddemo.aws.s3.bucket-output} (default
     *                              {@code carddemo-batch-output})
     * @param bkupKey               S3 key of the master-backup input, resolved from
     *                              {@code carddemo.batch.combine.bkup-key} (default {@code TRANSACT.BKUP})
     * @param systranKey            S3 key of the interest-staging input, resolved from
     *                              {@code carddemo.batch.combine.systran-key} (default {@code SYSTRAN})
     * @param combinedKey           S3 key of the combined output, resolved from
     *                              {@code carddemo.batch.combine.combined-key} (default
     *                              {@code TRANSACT.COMBINED})
     */
    public CombineTransactionsJob(
            final S3Client s3Client,
            final TransactionRepository transactionRepository,
            final MeterRegistry meterRegistry,
            @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") final String stagingBucket,
            @Value("${carddemo.batch.combine.bkup-key:TRANSACT.BKUP}") final String bkupKey,
            @Value("${carddemo.batch.combine.systran-key:SYSTRAN}") final String systranKey,
            @Value("${carddemo.batch.combine.combined-key:TRANSACT.COMBINED}") final String combinedKey) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.stagingBucket = stagingBucket;
        this.bkupKey = bkupKey;
        this.systranKey = systranKey;
        this.combinedKey = combinedKey;
    }

    /**
     * Defines the combine job: the sort step followed by the load step, with the load step running
     * only on successful completion of the sort step (predecessor-success ordering, §0.8.5).
     *
     * @param jobRepository    the auto-configured Spring Batch job repository
     * @param combineSortStep  the sort step bean (DFSORT replacement)
     * @param combineLoadStep  the load step bean (IDCAMS REPRO replacement)
     * @return the two-step combine job
     */
    @Bean
    public Job combineTransactionsJob(
            final JobRepository jobRepository,
            @Qualifier(SORT_STEP_NAME) final Step combineSortStep,
            @Qualifier(LOAD_STEP_NAME) final Step combineLoadStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(combineSortStep)
                .next(combineLoadStep)
                .build();
    }

    /**
     * Sort step ({@code STEP05R}): downloads the BKUP and SYSTRAN S3 objects, parses each 350-byte
     * {@code CVTRA05Y} record, concatenates them in JCL order (BKUP then SYSTRAN), sorts ascending
     * by {@code tranId} via {@link CombineTransactionsProcessor#BY_TRAN_ID}, and writes the sorted
     * combined stream to the COMBINED S3 object.
     *
     * @param jobRepository      the auto-configured Spring Batch job repository
     * @param transactionManager the auto-configured platform transaction manager
     * @return the configured sort step
     */
    @Bean(SORT_STEP_NAME)
    public Step combineSortStep(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager) {
        return new StepBuilder(SORT_STEP_NAME, jobRepository)
                .tasklet(buildSortTasklet(), transactionManager)
                .build();
    }

    /**
     * Load step ({@code STEP10}): downloads the COMBINED S3 object and bulk-upserts every record
     * into the {@code transaction} table in batches, idempotently (merge by {@code tranId}).
     *
     * @param jobRepository      the auto-configured Spring Batch job repository
     * @param transactionManager the auto-configured platform transaction manager
     * @return the configured load step
     */
    @Bean(LOAD_STEP_NAME)
    public Step combineLoadStep(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager) {
        return new StepBuilder(LOAD_STEP_NAME, jobRepository)
                .tasklet(buildLoadTasklet(), transactionManager)
                .build();
    }

    /**
     * Builds the sort tasklet. Defined as a plain factory method (not a bean) so it is safe under
     * {@code proxyBeanMethods = false}: the returned {@link Tasklet} is a fresh, un-proxied object
     * consumed once by {@link #combineSortStep}.
     *
     * @return the sort tasklet implementing the DFSORT replacement
     */
    Tasklet buildSortTasklet() {
        return (contribution, chunkContext) -> {
            final List<Transaction> combined = new ArrayList<>();
            combined.addAll(parseRecords(downloadObject(this.bkupKey)));
            final int bkupCount = combined.size();
            combined.addAll(parseRecords(downloadObject(this.systranKey)));
            LOGGER.info("Combine sort: read {} BKUP + {} SYSTRAN = {} record(s) for sort",
                    bkupCount, combined.size() - bkupCount, combined.size());

            // DFSORT SORT FIELDS=(1,16,CH,A) -> ascending by tranId (D-005).
            combined.sort(CombineTransactionsProcessor.BY_TRAN_ID);

            uploadCombined(combined);
            MetricsConfig.recordsProcessed(this.meterRegistry).increment(combined.size());
            contribution.incrementWriteCount(combined.size());
            LOGGER.info("Combine sort: wrote {} sorted record(s) to s3://{}/{}",
                    combined.size(), this.stagingBucket, this.combinedKey);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Builds the load tasklet. Defined as a plain factory method (not a bean) so it is safe under
     * {@code proxyBeanMethods = false}: the returned {@link Tasklet} is a fresh, un-proxied object
     * consumed once by {@link #combineLoadStep}.
     *
     * @return the load tasklet implementing the idempotent IDCAMS REPRO replacement
     */
    Tasklet buildLoadTasklet() {
        return (contribution, chunkContext) -> {
            final List<Transaction> combined = parseRecords(downloadObject(this.combinedKey));
            int written = 0;
            for (int start = 0; start < combined.size(); start += BATCH_INSERT_SIZE) {
                final int end = Math.min(start + BATCH_INSERT_SIZE, combined.size());
                // saveAll performs a JPA merge per entity (the @Id is always set), so an existing
                // tranId is overwritten rather than colliding -> idempotent "rebuild the master".
                this.transactionRepository.saveAll(combined.subList(start, end));
                written += (end - start);
            }
            contribution.incrementWriteCount(written);
            LOGGER.info("Combine load: upserted {} record(s) into the transaction master", written);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Downloads an S3 object's bytes from the configured staging bucket. A missing object
     * ({@link NoSuchKeyException}) is treated as an empty input &mdash; the cloud-native equivalent
     * of an absent/empty GDG generation &mdash; so the combine still produces output from whatever
     * inputs exist. Any other SDK failure aborts the step.
     *
     * @param key the S3 object key to download
     * @return the object bytes, or an empty array when the object does not exist
     */
    private byte[] downloadObject(final String key) {
        try {
            final ResponseBytes<GetObjectResponse> response = this.s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(this.stagingBucket).key(key).build());
            return response.asByteArray();
        } catch (final NoSuchKeyException notFound) {
            LOGGER.warn("S3 object s3://{}/{} not found; treating as empty combine input",
                    this.stagingBucket, key);
            return new byte[0];
        } catch (final SdkException sdkFailure) {
            throw new IllegalStateException(
                    "Failed to read S3 object s3://" + this.stagingBucket + "/" + key, sdkFailure);
        }
    }

    /**
     * Serializes the sorted transactions to the 350-byte {@code CVTRA05Y} layout (one newline-
     * terminated record each, ISO-8859-1) and uploads them to the COMBINED S3 object, mirroring
     * {@code SORTOUT -> COMBINED(+1)}.
     *
     * @param sorted the transactions already ordered ascending by {@code tranId}
     */
    private void uploadCombined(final List<Transaction> sorted) {
        final StringBuilder builder = new StringBuilder(sorted.size() * (RECORD_LENGTH + 1));
        for (final Transaction transaction : sorted) {
            builder.append(encodeRecord(transaction)).append('\n');
        }
        final byte[] payload = builder.toString().getBytes(RECORD_CHARSET);
        try {
            this.s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(this.stagingBucket)
                            .key(this.combinedKey)
                            .contentType(COMBINED_CONTENT_TYPE)
                            .build(),
                    RequestBody.fromBytes(payload));
        } catch (final SdkException sdkFailure) {
            throw new IllegalStateException(
                    "Failed to write combined S3 object s3://" + this.stagingBucket + "/" + this.combinedKey,
                    sdkFailure);
        }
    }

    /**
     * Parses a buffer of concatenated/line-delimited 350-byte {@code CVTRA05Y} records into
     * {@link Transaction} instances. The buffer is decoded as ISO-8859-1 and split either on line
     * terminators (when present, e.g. the ASCII fixtures or the COMBINED object this job writes) or
     * into fixed {@value #RECORD_LENGTH}-byte slices (raw mainframe RECFM=F concatenation),
     * accommodating either producer format.
     *
     * @param data the raw object bytes (possibly empty)
     * @return the parsed transactions in file order (never {@code null})
     */
    static List<Transaction> parseRecords(final byte[] data) {
        final List<Transaction> transactions = new ArrayList<>();
        if (data == null || data.length == 0) {
            return transactions;
        }
        final String content = new String(data, RECORD_CHARSET);
        if (content.indexOf('\n') >= 0 || content.indexOf('\r') >= 0) {
            for (final String line : content.split("\\R", -1)) {
                if (!line.isEmpty()) {
                    transactions.add(parseRecord(line));
                }
            }
        } else {
            for (int offset = 0; offset + RECORD_LENGTH <= content.length(); offset += RECORD_LENGTH) {
                transactions.add(parseRecord(content.substring(offset, offset + RECORD_LENGTH)));
            }
        }
        return transactions;
    }

    /**
     * Parses a single fixed-width {@code CVTRA05Y} record into a {@link Transaction}. The raw record
     * is normalized to exactly {@value #RECORD_LENGTH} characters (right-padded with spaces when
     * short, truncated when long) so field extraction is always in-bounds. Alphanumeric fields are
     * trimmed (matching the rest of the codebase, which keeps the assigned {@code tranId} identical
     * to the rows persisted by the daily-posting job so the idempotent merge matches); numeric
     * fields are decoded to their declared types and the amount is decoded from its zoned-decimal
     * trailing-sign overpunch into a scale-{@value #AMOUNT_SCALE} {@link BigDecimal}.
     *
     * @param raw the raw record text
     * @return the mapped transaction
     */
    static Transaction parseRecord(final String raw) {
        final String record = normalizeLength(raw);
        final Transaction transaction = new Transaction();
        transaction.setTranId(record.substring(TRAN_ID_BEGIN, TRAN_ID_END).trim());
        transaction.setTranTypeCd(record.substring(TRAN_ID_END, TRAN_TYPE_CD_END).trim());
        transaction.setTranCatCd(parseIntField(record.substring(TRAN_TYPE_CD_END, TRAN_CAT_CD_END)));
        transaction.setTranSource(record.substring(TRAN_CAT_CD_END, TRAN_SOURCE_END).trim());
        transaction.setTranDesc(record.substring(TRAN_SOURCE_END, TRAN_DESC_END).trim());
        transaction.setTranAmt(decodeSignedAmount(record.substring(TRAN_DESC_END, TRAN_AMT_END)));
        transaction.setTranMerchantId(parseLongField(record.substring(TRAN_AMT_END, TRAN_MERCHANT_ID_END)));
        transaction.setTranMerchantName(
                record.substring(TRAN_MERCHANT_ID_END, TRAN_MERCHANT_NAME_END).trim());
        transaction.setTranMerchantCity(
                record.substring(TRAN_MERCHANT_NAME_END, TRAN_MERCHANT_CITY_END).trim());
        transaction.setTranMerchantZip(
                record.substring(TRAN_MERCHANT_CITY_END, TRAN_MERCHANT_ZIP_END).trim());
        transaction.setTranCardNum(record.substring(TRAN_MERCHANT_ZIP_END, TRAN_CARD_NUM_END).trim());
        transaction.setTranOrigTs(record.substring(TRAN_CARD_NUM_END, TRAN_ORIG_TS_END).trim());
        transaction.setTranProcTs(record.substring(TRAN_ORIG_TS_END, TRAN_PROC_TS_END).trim());
        return transaction;
    }

    /**
     * Serializes a {@link Transaction} back to a {@value #RECORD_LENGTH}-character {@code CVTRA05Y}
     * record: alphanumeric fields are left-justified and space-padded, numeric fields are
     * right-justified and zero-padded, the amount is re-encoded as an 11-character zoned-decimal
     * trailing-sign overpunch, and the trailing 20-byte filler is spaces. This preserves the
     * external fixed-width record contract exactly (§0.8.1).
     *
     * @param transaction the transaction to serialize
     * @return the 350-character record
     */
    static String encodeRecord(final Transaction transaction) {
        final StringBuilder record = new StringBuilder(RECORD_LENGTH);
        record.append(fixedAlpha(transaction.getTranId(), WIDTH_TRAN_ID));
        record.append(fixedAlpha(transaction.getTranTypeCd(), WIDTH_TRAN_TYPE_CD));
        record.append(fixedNumeric(transaction.getTranCatCd(), WIDTH_TRAN_CAT_CD));
        record.append(fixedAlpha(transaction.getTranSource(), WIDTH_TRAN_SOURCE));
        record.append(fixedAlpha(transaction.getTranDesc(), WIDTH_TRAN_DESC));
        record.append(encodeSignedAmount(transaction.getTranAmt(), WIDTH_TRAN_AMT));
        record.append(fixedNumeric(transaction.getTranMerchantId(), WIDTH_TRAN_MERCHANT_ID));
        record.append(fixedAlpha(transaction.getTranMerchantName(), WIDTH_TRAN_MERCHANT_NAME));
        record.append(fixedAlpha(transaction.getTranMerchantCity(), WIDTH_TRAN_MERCHANT_CITY));
        record.append(fixedAlpha(transaction.getTranMerchantZip(), WIDTH_TRAN_MERCHANT_ZIP));
        record.append(fixedAlpha(transaction.getTranCardNum(), WIDTH_TRAN_CARD_NUM));
        record.append(fixedAlpha(transaction.getTranOrigTs(), WIDTH_TRAN_ORIG_TS));
        record.append(fixedAlpha(transaction.getTranProcTs(), WIDTH_TRAN_PROC_TS));
        record.append(" ".repeat(WIDTH_FILLER));
        return record.toString();
    }

    /**
     * Normalizes a raw record to exactly {@value #RECORD_LENGTH} characters by right-padding with
     * spaces (short records) or truncating (long records).
     *
     * @param raw the raw record text (may be shorter or longer than the fixed length)
     * @return a record of exactly {@value #RECORD_LENGTH} characters
     */
    private static String normalizeLength(final String raw) {
        final String value = (raw == null) ? "" : raw;
        if (value.length() == RECORD_LENGTH) {
            return value;
        }
        if (value.length() > RECORD_LENGTH) {
            return value.substring(0, RECORD_LENGTH);
        }
        return value + " ".repeat(RECORD_LENGTH - value.length());
    }

    /**
     * Decodes an 11-character zoned-decimal {@code S9(09)V99} amount with a trailing-sign overpunch
     * on the final byte into a scale-{@value #AMOUNT_SCALE} {@link BigDecimal} (§0.8.2; never
     * {@code float}/{@code double}).
     *
     * <p>Final-byte overpunch decode: {@code '{'} = digit 0 positive; {@code 'A'..'I'} = digits 1..9
     * positive; {@code '}'} = digit 0 negative; {@code 'J'..'R'} = digits 1..9 negative;
     * {@code '0'..'9'} = that digit positive (no overpunch). A blank field decodes to {@code 0.00}.
     *
     * @param field the raw 11-character amount token
     * @return the decoded scale-{@value #AMOUNT_SCALE} amount
     */
    static BigDecimal decodeSignedAmount(final String field) {
        final String value = (field == null) ? "" : field.trim();
        if (value.isEmpty()) {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
        }
        final char sign = value.charAt(value.length() - 1);
        final String head = value.substring(0, value.length() - 1);
        final int lastDigit;
        final boolean negative;
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
        final BigDecimal magnitude = new BigDecimal(head + lastDigit).movePointLeft(AMOUNT_SCALE);
        return negative ? magnitude.negate() : magnitude;
    }

    /**
     * Encodes a {@link BigDecimal} amount as a fixed-width zoned-decimal {@code S9(09)V99} token
     * with a trailing-sign overpunch on the final byte, the inverse of {@link #decodeSignedAmount}.
     * A {@code null} amount encodes as zero. The magnitude is taken to scale
     * {@value #AMOUNT_SCALE} ({@link RoundingMode#HALF_EVEN}) and zero-padded to {@code width}
     * digits before the final digit's overpunch is applied.
     *
     * @param amount the amount to encode (may be {@code null} or negative)
     * @param width  the total field width (11 for {@code S9(09)V99})
     * @return the encoded fixed-width amount token
     */
    static String encodeSignedAmount(final BigDecimal amount, final int width) {
        final BigDecimal scaled =
                (amount == null ? BigDecimal.ZERO : amount).setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        final boolean negative = scaled.signum() < 0;
        final String digits = leftPadZero(scaled.abs().unscaledValue().toString(), width);
        if (digits.length() > width) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " exceeds the " + width + "-digit S9(09)V99 capacity");
        }
        final String head = digits.substring(0, width - 1);
        final int lastDigit = digits.charAt(width - 1) - '0';
        final char overpunch;
        if (negative) {
            overpunch = (lastDigit == 0) ? '}' : (char) ('J' + lastDigit - 1);
        } else {
            overpunch = (lastDigit == 0) ? '{' : (char) ('A' + lastDigit - 1);
        }
        return head + overpunch;
    }

    /**
     * Parses a numeric {@code PIC 9} token to an {@link Integer}, treating a blank token as zero.
     *
     * @param token the raw numeric token
     * @return the parsed value (never {@code null})
     */
    private static Integer parseIntField(final String token) {
        final String trimmed = (token == null) ? "" : token.trim();
        return trimmed.isEmpty() ? Integer.valueOf(0) : Integer.valueOf(trimmed);
    }

    /**
     * Parses a numeric {@code PIC 9} token to a {@link Long}, treating a blank token as zero.
     *
     * @param token the raw numeric token
     * @return the parsed value (never {@code null})
     */
    private static Long parseLongField(final String token) {
        final String trimmed = (token == null) ? "" : token.trim();
        return trimmed.isEmpty() ? Long.valueOf(0L) : Long.valueOf(trimmed);
    }

    /**
     * Left-justifies and space-pads an alphanumeric value to a fixed width, truncating an
     * over-length value to the field width.
     *
     * @param value the value to format (may be {@code null}, treated as empty)
     * @param width the fixed field width
     * @return the formatted, width-exact field
     */
    private static String fixedAlpha(final String value, final int width) {
        final String text = (value == null) ? "" : value;
        if (text.length() == width) {
            return text;
        }
        if (text.length() > width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    /**
     * Right-justifies and zero-pads a numeric value to a fixed width. A {@code null} value formats
     * as zeros; an over-length value keeps its low-order {@code width} digits, mirroring the COBOL
     * high-order truncation of a {@code MOVE} into a smaller {@code PIC 9} field.
     *
     * @param value the numeric value to format (may be {@code null})
     * @param width the fixed field width
     * @return the formatted, width-exact field
     */
    private static String fixedNumeric(final Number value, final int width) {
        final long magnitude = (value == null) ? 0L : Math.abs(value.longValue());
        final String digits = leftPadZero(Long.toString(magnitude), width);
        return (digits.length() > width) ? digits.substring(digits.length() - width) : digits;
    }

    /**
     * Left-pads a digit string with {@code '0'} up to the given width; strings already at or beyond
     * the width are returned unchanged.
     *
     * @param digits the digit string
     * @param width  the desired minimum width
     * @return the zero-padded string
     */
    private static String leftPadZero(final String digits, final int width) {
        if (digits.length() >= width) {
            return digits;
        }
        return "0".repeat(width - digits.length()) + digits;
    }
}
