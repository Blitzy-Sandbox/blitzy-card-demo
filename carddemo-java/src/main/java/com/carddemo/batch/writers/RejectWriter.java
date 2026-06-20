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
 * Spring Batch {@code ItemStreamWriter} that emits byte-exact fixed-width reject records to
 * Amazon S3 (LocalStack in local/test profiles).
 *
 * <p>Translated (logic only, never source text; traceability via commit {@code 27d6c6f}) from the
 * COBOL daily-transaction posting program {@code app/cbl/CBTRN02C.cbl}, specifically paragraph
 * {@code 2500-WRITE-REJECT-REC}. In the original program each rejected daily transaction was
 * written to the {@code DALYREJS} sequential file as a 430-byte fixed-length record:
 * {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD}, where {@code REJECT-RECORD} is the original
 * 350-byte daily-transaction image ({@code REJECT-TRAN-DATA}) followed by an 80-byte validation
 * trailer ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)} plus
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}).</p>
 *
 * <p>The dataset itself originated as a generation data group (GDG); per the GDG&rarr;S3 mapping
 * established for this migration, each batch run writes a single S3 object under the key
 * {@code DALYREJS}. Because the output bucket is versioned, every run's {@code putObject} produces
 * a new object version, which is the relational/cloud equivalent of a new GDG generation. The
 * record layout ({@code RECFM=F, LRECL=430}) is preserved exactly so the S3 object is
 * byte-compatible with the mainframe reject dataset.</p>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #open(ExecutionContext)} &mdash; allocates a fresh in-memory buffer for the run.</li>
 *   <li>{@link #write(Chunk)} &mdash; appends one 430-byte record per rejected item, with no
 *       delimiters (fixed-length records are contiguous), and increments the rejection metric.</li>
 *   <li>{@link #close()} &mdash; flushes the accumulated buffer to S3 as one object; writes nothing
 *       when no rejects occurred (faithful to the COBOL behaviour of not creating an empty
 *       generation).</li>
 * </ul>
 *
 * <p>The writer assumes single-threaded chunk execution (faithful to the sequential COBOL batch);
 * the {@code buffer} field is per-step state created in {@code open()} and flushed in
 * {@code close()}.</p>
 */
@Component
public class RejectWriter implements ItemStreamWriter<RejectWriter.RejectedTransaction> {

    /** Length of the raw daily-transaction image ({@code REJECT-TRAN-DATA}, DALYTRAN = 350 bytes). */
    private static final int DATA_LENGTH = 350;

    /** Length of the numeric reject reason field ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)}). */
    private static final int REASON_LENGTH = 4;

    /** Length of the reason-description field ({@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}). */
    private static final int DESC_LENGTH = 76;

    /** Total fixed record length: 350 + 4 + 76 = 430 bytes ({@code RECFM=F, LRECL=430}). */
    private static final int RECORD_LENGTH = 430;

    /** S3 object key for the reject dataset (GDG base / DD name {@code DALYREJS}). */
    private static final String OBJECT_KEY = "DALYREJS";

    /** Synchronous S3 client (bean type provided by {@code com.carddemo.config.AwsConfig}). */
    private final S3Client s3Client;

    /** Micrometer registry used to record the per-reason rejection counter. */
    private final MeterRegistry meterRegistry;

    /** Target S3 bucket for batch output (rejection objects). */
    private final String outputBucket;

    /**
     * Per-run accumulation buffer. Created in {@link #open(ExecutionContext)} and released in
     * {@link #close()}; intentionally non-final mutable per-step state.
     */
    private ByteArrayOutputStream buffer;

    /**
     * Creates the reject writer with its collaborators injected by the Spring container.
     *
     * @param s3Client      the synchronous S3 client used to upload the reject object
     * @param meterRegistry the Micrometer registry used to increment the rejection counter
     * @param outputBucket  the S3 output bucket name, resolved from
     *                      {@code carddemo.aws.s3.bucket-output} (default {@code carddemo-batch-output});
     *                      endpoint, region, and credentials are never hardcoded here
     */
    public RejectWriter(
            S3Client s3Client,
            MeterRegistry meterRegistry,
            @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") String outputBucket) {
        this.s3Client = s3Client;
        this.meterRegistry = meterRegistry;
        this.outputBucket = outputBucket;
    }

    /**
     * Allocates a fresh buffer for the step execution so that exactly one S3 object is produced per
     * run.
     *
     * @param executionContext the Spring Batch execution context (not used; no state is restored)
     */
    @Override
    public void open(ExecutionContext executionContext) {
        this.buffer = new ByteArrayOutputStream();
    }

    /**
     * No-op: this writer persists no incremental state to the execution context (the whole run is
     * flushed once in {@link #close()}).
     *
     * @param executionContext the Spring Batch execution context (not used)
     */
    @Override
    public void update(ExecutionContext executionContext) {
        // no incremental state
    }

    /**
     * Appends one byte-exact 430-byte reject record per item to the run buffer and increments the
     * rejection counter, tagged by reject reason code.
     *
     * @param chunk the chunk of rejected transactions supplied by the step
     */
    @Override
    public void write(Chunk<? extends RejectedTransaction> chunk) {
        for (RejectedTransaction item : chunk) {
            byte[] recordBytes = buildRecord(item);
            // RECFM=F: fixed-length records are concatenated contiguously, with no delimiters.
            buffer.writeBytes(recordBytes);
            MetricsConfig.recordsRejected(meterRegistry, String.valueOf(item.reasonCode())).increment();
        }
    }

    /**
     * Flushes the accumulated reject records to S3 as a single object. When no rejects were
     * accumulated, nothing is written (faithful to the COBOL program not creating an empty
     * generation). The buffer is always released afterwards.
     *
     * @throws FileAccessException if the S3 upload fails
     */
    @Override
    public void close() {
        if (buffer == null || buffer.size() == 0) {
            return;
        }
        byte[] payload = buffer.toByteArray();
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(outputBucket)
                            .key(OBJECT_KEY)
                            .contentType("application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(payload));
        } catch (SdkException e) {
            throw new FileAccessException(
                    "Failed to write reject file to S3 " + outputBucket + "/" + OBJECT_KEY, e);
        } finally {
            this.buffer = null;
        }
    }

    /**
     * Builds the byte-exact 430-byte reject record for a single item.
     *
     * <p>Layout (total = {@value #DATA_LENGTH} + {@value #REASON_LENGTH} + {@value #DESC_LENGTH}
     * = {@value #RECORD_LENGTH} bytes):</p>
     * <ul>
     *   <li>offset 0, length 350 &mdash; raw daily-transaction image, left-justified, space-padded,
     *       truncated if longer ({@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}).</li>
     *   <li>offset 350, length 4 &mdash; reject reason as zero-padded numeric ({@code 9(04)}).</li>
     *   <li>offset 354, length 76 &mdash; reason description, left-justified, space-padded,
     *       truncated if longer ({@code X(76)}).</li>
     * </ul>
     *
     * <p>The {@code ISO_8859_1} charset is used throughout so every character maps to exactly one
     * byte, preserving fixed-width fidelity.</p>
     *
     * @param item the rejected transaction (raw record, reason code, reason description)
     * @return a byte array of exactly {@value #RECORD_LENGTH} bytes
     */
    private byte[] buildRecord(RejectedTransaction item) {
        byte[] out = new byte[RECORD_LENGTH];
        // Space-fill first so any short field is right-padded with blanks (display convention).
        Arrays.fill(out, (byte) ' ');

        // Field 1: raw daily-transaction data (offset 0, length 350), left-justified.
        byte[] data = item.dailyTransactionRecord() == null
                ? new byte[0]
                : item.dailyTransactionRecord().getBytes(StandardCharsets.ISO_8859_1);
        int dataLen = Math.min(data.length, DATA_LENGTH);
        System.arraycopy(data, 0, out, 0, dataLen);

        // Field 2: reason code (offset 350, length 4), numeric 9(4) zero-padded.
        String reason = String.format("%04d", item.reasonCode());
        if (reason.length() > REASON_LENGTH) {
            reason = reason.substring(reason.length() - REASON_LENGTH);
        }
        byte[] reasonBytes = reason.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(reasonBytes, 0, out, DATA_LENGTH, REASON_LENGTH);

        // Field 3: reason description (offset 354, length 76), left-justified.
        String desc = item.reasonDescription() == null ? "" : item.reasonDescription();
        byte[] descBytes = desc.getBytes(StandardCharsets.ISO_8859_1);
        int descLen = Math.min(descBytes.length, DESC_LENGTH);
        System.arraycopy(descBytes, 0, out, DATA_LENGTH + REASON_LENGTH, descLen);

        return out;
    }

    /**
     * Input contract for {@link RejectWriter}: a single rejected daily transaction.
     *
     * <p>Mirrors the COBOL reject record fields from {@code CBTRN02C}:</p>
     * <ul>
     *   <li>{@code dailyTransactionRecord} &mdash; the original raw 350-byte daily-transaction
     *       record exactly as read (COBOL {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}); the
     *       writer does not re-derive it from individual fields.</li>
     *   <li>{@code reasonCode} &mdash; the validation reject reason
     *       ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)}; values 100&ndash;103 and 109).</li>
     *   <li>{@code reasonDescription} &mdash; the reason text
     *       ({@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}).</li>
     * </ul>
     *
     * @param dailyTransactionRecord the raw 350-byte daily-transaction record
     * @param reasonCode             the numeric reject reason code
     * @param reasonDescription      the human-readable reject reason description
     */
    public record RejectedTransaction(String dailyTransactionRecord, int reasonCode, String reasonDescription) {
    }
}
