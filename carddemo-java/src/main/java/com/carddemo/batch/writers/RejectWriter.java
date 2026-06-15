package com.carddemo.batch.writers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.carddemo.exception.FileAccessException;
import com.carddemo.observability.MetricsConfig;

/**
 * Spring Batch {@link ItemStreamWriter} that emits byte-exact 430-byte fixed-width reject
 * records to AWS S3 — the Java replacement for the daily-transaction reject file produced
 * by COBOL program {@code CBTRN02C}, paragraph {@code 2500-WRITE-REJECT-REC} (source commit
 * {@code 27d6c6f}).
 *
 * <p>In the mainframe pipeline, the daily-posting program writes each rejected transaction
 * to the {@code DALYREJS} sequential file ({@code RECFM=F LRECL=430}, backed by a
 * generation-data group defined in {@code DEFGDGB.jcl}). Every record is the original
 * 350-byte {@code DALYTRAN-RECORD} (copybook {@code CVTRA06Y}) followed by an 80-byte
 * validation trailer carrying the numeric reject reason and its text description. This
 * writer reproduces that layout exactly:</p>
 *
 * <pre>
 *   offset  length  field                                 PIC      encoding
 *   ------  ------  ------------------------------------  -------  ----------------------------
 *        0     350  raw daily-transaction record          X(350)   left-justified, space-padded
 *      350       4  validation reject reason              9(04)    zero-padded numeric
 *      354      76  validation reject reason description   X(76)    left-justified, space-padded
 *   --------------------------------------------------------------------------------------------
 *      total = 350 + 4 + 76 = 430 bytes (LRECL=430)
 * </pre>
 *
 * <p>Records are accumulated for the whole step in an in-memory buffer and flushed as a single
 * S3 object on {@link #close()}, so one batch run yields exactly one object (one GDG
 * generation; S3 bucket versioning supplies the generation history). When no transaction is
 * rejected, no object is written — faithful to the COBOL program, which does not create an
 * empty generation. All bytes use {@link StandardCharsets#ISO_8859_1} (one byte per character)
 * to guarantee fixed-width fidelity; a multi-byte charset would corrupt the byte offsets.</p>
 *
 * <p>The writer assumes single-threaded chunk processing, matching the sequential COBOL batch;
 * its buffer is per-step state created in {@link #open(ExecutionContext)} and released in
 * {@link #close()}.</p>
 */
@Component
public class RejectWriter implements ItemStreamWriter<RejectWriter.RejectedTransaction> {

    /** Length of the raw daily-transaction data segment (COBOL {@code REJECT-TRAN-DATA PIC X(350)}). */
    private static final int DATA_LENGTH = 350;

    /** Length of the reject-reason segment (COBOL {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}). */
    private static final int REASON_LENGTH = 4;

    /** Length of the reason-description segment (COBOL {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}). */
    private static final int DESC_LENGTH = 76;

    /** Total fixed record length: 350 + 4 + 76 = 430 bytes ({@code LRECL=430}). */
    private static final int RECORD_LENGTH = DATA_LENGTH + REASON_LENGTH + DESC_LENGTH;

    /** S3 object key for the reject file, derived from the {@code DALYREJS} GDG base / DD name (D-003). */
    private static final String OBJECT_KEY = "DALYREJS";

    /** Content type used for the binary, fixed-width reject object. */
    private static final String CONTENT_TYPE = "application/octet-stream";

    private final S3Client s3Client;
    private final MeterRegistry meterRegistry;
    private final String outputBucket;

    /** Per-step accumulation buffer; created in {@link #open} and released in {@link #close}. */
    private ByteArrayOutputStream buffer;

    /**
     * Creates the reject writer.
     *
     * @param s3Client      the synchronous S3 client; endpoint, region, and credentials are
     *                      resolved from configuration (for example LocalStack), never here
     * @param meterRegistry the Micrometer registry used to record the rejection metric
     * @param outputBucket  the destination S3 bucket name, defaulting to {@code carddemo-batch-output}
     */
    public RejectWriter(S3Client s3Client,
                        MeterRegistry meterRegistry,
                        @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") String outputBucket) {
        this.s3Client = s3Client;
        this.meterRegistry = meterRegistry;
        this.outputBucket = outputBucket;
    }

    /**
     * Initializes a fresh accumulation buffer at the start of the step so that each run produces
     * exactly one S3 object.
     *
     * @param executionContext the step execution context (unused; no state is restored)
     */
    @Override
    public void open(ExecutionContext executionContext) {
        this.buffer = new ByteArrayOutputStream();
    }

    /**
     * No-op: this writer persists no incremental state to the execution context.
     *
     * @param executionContext the step execution context (unused)
     */
    @Override
    public void update(ExecutionContext executionContext) {
        // no incremental state
    }

    /**
     * Appends each rejected transaction to the in-memory buffer as a 430-byte fixed-width record
     * (no delimiters, matching {@code RECFM=F}) and increments the rejection metric tagged by the
     * reject reason code.
     *
     * @param chunk the chunk of rejected transactions to write
     */
    @Override
    public void write(Chunk<? extends RejectedTransaction> chunk) {
        for (RejectedTransaction item : chunk) {
            buffer.writeBytes(buildRecord(item));
            MetricsConfig.recordsRejected(meterRegistry, String.valueOf(item.reasonCode())).increment();
        }
    }

    /**
     * Flushes the accumulated reject records to a single S3 object. When nothing was rejected, no
     * object is written. The buffer is always released afterwards.
     *
     * @throws FileAccessException if the S3 put fails
     */
    @Override
    public void close() {
        try {
            if (buffer == null || buffer.size() == 0) {
                return;
            }
            byte[] payload = buffer.toByteArray();
            try {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(outputBucket)
                                .key(OBJECT_KEY)
                                .contentType(CONTENT_TYPE)
                                .build(),
                        RequestBody.fromBytes(payload));
            } catch (SdkException e) {
                throw new FileAccessException(
                        "Failed to write reject file to S3 " + outputBucket + "/" + OBJECT_KEY, e);
            }
        } finally {
            this.buffer = null;
        }
    }

    /**
     * Builds one byte-exact 430-byte reject record: a 350-byte raw daily-transaction segment, a
     * 4-byte zero-padded numeric reject reason, and a 76-byte left-justified description. The
     * record is space-filled first, so any short field is right-padded with spaces and any
     * over-long field is truncated to its fixed width (350 + 4 + 76 = 430).
     *
     * @param item the rejected transaction
     * @return a new array of exactly 430 bytes
     */
    private byte[] buildRecord(RejectedTransaction item) {
        byte[] out = new byte[RECORD_LENGTH];
        Arrays.fill(out, (byte) ' ');

        byte[] data = item.dailyTransactionRecord() == null
                ? new byte[0]
                : item.dailyTransactionRecord().getBytes(StandardCharsets.ISO_8859_1);
        int dataLen = Math.min(data.length, DATA_LENGTH);
        System.arraycopy(data, 0, out, 0, dataLen);

        String reason = String.format("%04d", item.reasonCode());
        if (reason.length() > REASON_LENGTH) {
            reason = reason.substring(reason.length() - REASON_LENGTH);
        }
        byte[] reasonBytes = reason.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(reasonBytes, 0, out, DATA_LENGTH, REASON_LENGTH);

        String desc = item.reasonDescription() == null ? "" : item.reasonDescription();
        byte[] descBytes = desc.getBytes(StandardCharsets.ISO_8859_1);
        int descLen = Math.min(descBytes.length, DESC_LENGTH);
        System.arraycopy(descBytes, 0, out, DATA_LENGTH + REASON_LENGTH, descLen);

        return out;
    }

    /**
     * Input contract for {@link RejectWriter}: a single rejected daily transaction.
     *
     * @param dailyTransactionRecord the original raw 350-byte daily-transaction record, exactly as
     *                               read upstream (COBOL {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA})
     * @param reasonCode             the validation reject reason code (COBOL
     *                               {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}; values 100-103 and 109)
     * @param reasonDescription      the reject reason description text (COBOL
     *                               {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)})
     */
    public record RejectedTransaction(String dailyTransactionRecord, int reasonCode, String reasonDescription) {
    }
}
