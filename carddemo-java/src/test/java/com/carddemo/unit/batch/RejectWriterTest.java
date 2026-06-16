package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.exception.FileAccessException;
import com.carddemo.observability.MetricsConfig;

/**
 * Pure-JVM unit tests for {@link RejectWriter}, the Java replacement for the
 * COBOL daily-transaction reject-file writer in {@code app/cbl/CBTRN02C.cbl},
 * paragraph {@code 2500-WRITE-REJECT-REC} (REFERENCE-ONLY; COBOL not copied;
 * source commit {@code 27d6c6f}).
 *
 * <p>These tests exercise the writer strictly through its public
 * {@link RejectWriter#open(ExecutionContext) open} &rarr;
 * {@link RejectWriter#write(Chunk) write} &rarr; {@link RejectWriter#close() close}
 * lifecycle and capture the resulting S3 {@link PutObjectRequest} / {@link RequestBody}
 * with Mockito argument captors; the private record-builder is never invoked
 * reflectively. The {@link S3Client} is mocked and a real
 * {@link SimpleMeterRegistry} verifies the rejection metric. No Spring context,
 * Testcontainers, or LocalStack is started.</p>
 *
 * <p>They prove the binding parity rules for the reject record (AAP &sect;0.7.7 /
 * &sect;0.8.1): the byte-exact {@code 430}-byte fixed layout
 * ({@code 350} data + {@code 4} reason + {@code 76} description), the four-digit
 * zero-padded reason code, the {@code 76}-character space-padded description, the
 * reason-tagged {@code carddemo.batch.records.rejected} metric, the
 * empty-buffer&rarr;no-write guard, and the {@code SdkException}&rarr;
 * {@link FileAccessException} wrapping of an S3 failure.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RejectWriter - byte-exact 430-byte S3 reject record (CBTRN02C 2500-WRITE-REJECT-REC)")
class RejectWriterTest {

    /** Default reject-output bucket (matches the {@code @Value} default in the production class). */
    private static final String BUCKET = "carddemo-batch-output";

    /** Fixed S3 object key for the reject file (COBOL {@code DALYREJS} GDG base / DD name). */
    private static final String OBJECT_KEY = "DALYREJS";

    /** Content type used for the binary fixed-width reject object. */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /** Length of the raw daily-transaction data segment (COBOL {@code DALYTRAN-RECORD PIC X(350)}). */
    private static final int DATA_LENGTH = 350;

    /** Total fixed record length: 350 data + 4 reason + 76 description = 430 bytes ({@code LRECL=430}). */
    private static final int RECORD_LENGTH = 430;

    @Mock
    private S3Client s3Client;

    private SimpleMeterRegistry registry;
    private RejectWriter writer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        writer = new RejectWriter(s3Client, registry, BUCKET);
    }

    @Test
    @DisplayName("write+close emits one 430-byte record: data | 0100 | space-padded description")
    void writesReject_produces430ByteRecord_zeroPaddedCode_paddedDescription() throws Exception {
        // Sanity-guard the test fixture: the data segment must be exactly 350 characters.
        assertThat(data350().length()).isEqualTo(DATA_LENGTH);

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new RejectWriter.RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND")));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

        byte[] bytes = capturedBytes(bodyCaptor);
        assertThat(bytes).hasSize(RECORD_LENGTH);

        String rec = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(rec.substring(0, 350)).isEqualTo(data350());                 // raw daily-transaction data
        assertThat(rec.substring(350, 354)).isEqualTo("0100");                  // 4-digit zero-padded reason code
        assertThat(rec.substring(354, 430)).hasSize(76);                        // description field is fixed width 76
        assertThat(rec.substring(354, 430).strip()).isEqualTo("INVALID CARD NUMBER FOUND");

        PutObjectRequest req = reqCaptor.getValue();
        assertThat(req.bucket()).isEqualTo(BUCKET);
        assertThat(req.key()).isEqualTo(OBJECT_KEY);
        assertThat(req.contentType()).isEqualTo(CONTENT_TYPE);
    }

    @Test
    @DisplayName("reason code 109 is zero-padded to '0109' at offset 350..354")
    void reasonCode109_zeroPaddedTo0109() throws Exception {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new RejectWriter.RejectedTransaction(data350(), 109, "ACCOUNT RECORD NOT FOUND")));
        writer.close();

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

        String rec = new String(capturedBytes(bodyCaptor), StandardCharsets.ISO_8859_1);
        assertThat(rec.substring(350, 354)).isEqualTo("0109");
    }

    @Test
    @DisplayName("multiple rejects concatenate as back-to-back 430-byte records in one object")
    void multipleRejects_concatenateInBuffer() throws Exception {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(
                new RejectWriter.RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND"),
                new RejectWriter.RejectedTransaction(data350(), 101, "ACCOUNT RECORD NOT FOUND")));
        writer.close();

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

        byte[] bytes = capturedBytes(bodyCaptor);
        assertThat(bytes).hasSize(2 * RECORD_LENGTH);

        String rec = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(rec.substring(350, 354)).isEqualTo("0100");                                       // first record reason
        assertThat(rec.substring(RECORD_LENGTH + 350, RECORD_LENGTH + 354)).isEqualTo("0101");       // second record reason @ 780..784
    }

    @Test
    @DisplayName("write increments carddemo.batch.records.rejected tagged reason=<code>")
    void incrementsRejectedCounter_taggedByReasonCode() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new RejectWriter.RejectedTransaction(data350(), 102, "OVERLIMIT TRANSACTION")));

        assertThat(registry.get(MetricsConfig.BATCH_RECORDS_REJECTED)
                .tags(MetricsConfig.TAG_REASON, "102")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("rejected counter increments once per item for the same reason code")
    void rejectedCounter_incrementsPerItem() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(
                new RejectWriter.RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND"),
                new RejectWriter.RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND")));

        assertThat(registry.get(MetricsConfig.BATCH_RECORDS_REJECTED)
                .tags(MetricsConfig.TAG_REASON, "100")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("empty buffer (no rejects) writes no S3 object - faithful to the COBOL no-empty-generation behaviour")
    void emptyBuffer_noS3Write() {
        writer.open(new ExecutionContext());
        writer.close();

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("S3 SdkException on close is wrapped as FileAccessException (FILE STATUS -> exception)")
    void s3Failure_onClose_throwsFileAccessException() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new RejectWriter.RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND")));

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.builder().message("simulated S3 failure").build());

        assertThatThrownBy(() -> writer.close())
                .isInstanceOf(FileAccessException.class)
                .hasCauseInstanceOf(SdkException.class);
    }

    /**
     * Produces an exactly-{@value #DATA_LENGTH}-character daily-transaction record. The writer copies
     * this segment verbatim into bytes {@code 0..349}, so a deterministic, exact-length filler is
     * sufficient to prove the byte-exact layout without coupling the test to copybook field offsets.
     *
     * @return a string of exactly 350 characters
     */
    private static String data350() {
        return "D".repeat(DATA_LENGTH);
    }

    /**
     * Reads the bytes that were captured as the S3 object payload.
     *
     * @param bodyCaptor the captor that captured the {@link RequestBody} passed to
     *                   {@link S3Client#putObject(PutObjectRequest, RequestBody)}
     * @return the full payload byte array
     * @throws IOException if the captured content stream cannot be read
     */
    private static byte[] capturedBytes(ArgumentCaptor<RequestBody> bodyCaptor) throws IOException {
        try (InputStream in = bodyCaptor.getValue().contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }
}
