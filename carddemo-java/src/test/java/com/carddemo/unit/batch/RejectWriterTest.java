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

import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.batch.writers.RejectWriter.RejectedTransaction;
import com.carddemo.exception.FileAccessException;
import com.carddemo.observability.MetricsConfig;

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

/**
 * Pure-JVM unit tests for {@link RejectWriter}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the COBOL
 * batch posting program {@code app/cbl/CBTRN02C.cbl}, paragraph {@code 2500-WRITE-REJECT-REC},
 * writes each rejected daily transaction to the {@code DALYREJS} sequential dataset as a 430-byte
 * fixed-length record ({@code RECFM=F, LRECL=430}). The record layout is
 * {@code REJECT-TRAN-DATA PIC X(350)} + {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} +
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} = 430 bytes, with the raw daily-transaction
 * image ({@code CVTRA06Y}, also 350 bytes) copied verbatim into the first field.</p>
 *
 * <p>These tests prove the binding external-interface contract of the migrated writer without any
 * Spring context, Testcontainers, or LocalStack: the {@code S3Client} is a Mockito mock and the
 * {@link SimpleMeterRegistry} is a real in-memory registry. Everything is exercised through the
 * public {@code open -> write -> close} {@code ItemStreamWriter} lifecycle (the byte-building logic
 * is private and is never invoked reflectively); the emitted S3 {@link PutObjectRequest} and
 * {@link RequestBody} are captured and asserted byte-for-byte.</p>
 *
 * <p>Verified behaviours (AAP sections 0.7.7 / 0.8.1):</p>
 * <ul>
 *   <li>exact 430-byte fixed layout (350 data + 4 reason + 76 description);</li>
 *   <li>4-digit zero-padded numeric reason code (for example {@code 0100}, {@code 0109});</li>
 *   <li>76-character, space-padded reason description;</li>
 *   <li>contiguous concatenation of multiple fixed-length records (no delimiters);</li>
 *   <li>the reason-tagged {@code carddemo.batch.records.rejected} counter (Observability rule);</li>
 *   <li>the empty-buffer guard (no S3 object is written when nothing was rejected);</li>
 *   <li>{@code SdkException} wrapping into {@link FileAccessException} (FILE STATUS abend path).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RejectWriter - 430-byte fixed-width reject record (CBTRN02C 2500-WRITE-REJECT-REC)")
class RejectWriterTest {

    /** Default output bucket from {@code carddemo.aws.s3.bucket-output}; passed explicitly here. */
    private static final String BUCKET = "carddemo-batch-output";

    /** Total fixed record length asserted throughout: 350 + 4 + 76. */
    private static final int RECORD_LENGTH = 430;

    /** Mocked synchronous S3 client; the writer's only external collaborator. */
    @Mock
    private S3Client s3Client;

    /** Real in-memory Micrometer registry so counter assertions exercise production wiring. */
    private SimpleMeterRegistry registry;

    /** System under test, reconstructed before each test with a fresh registry. */
    private RejectWriter writer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        writer = new RejectWriter(s3Client, registry, BUCKET);
    }

    /**
     * Builds an exactly-350-character raw daily-transaction image shaped after {@code CVTRA06Y}
     * (DALYTRAN-RECORD, RECLN=350). The exact content is irrelevant to the layout assertions; only
     * the 350-byte width matters, so the value is normalised to exactly 350 ISO-8859-1 characters
     * (each maps to a single byte).
     *
     * @return a 350-character daily-transaction record string
     */
    private static String data350() {
        // DALYTRAN-ID(16) + TYPE-CD(2) + CAT-CD(4) + SOURCE(10) + start of DESC(100); the
        // remaining bytes are space-padded to the full 350-byte record width below.
        String sample = "0000000000000001" + "PR" + "0001" + "POS       " + "GROCERY PURCHASE";
        StringBuilder sb = new StringBuilder(sample);
        while (sb.length() < 350) {
            sb.append(' ');
        }
        sb.setLength(350);
        return sb.toString();
    }

    /**
     * Reads the captured S3 upload body into a byte array.
     *
     * @param bodyCaptor the captor that recorded the {@link RequestBody} passed to {@code putObject}
     * @return the full uploaded payload as bytes
     * @throws IOException if the request body stream cannot be read
     */
    private static byte[] capturedBytes(ArgumentCaptor<RequestBody> bodyCaptor) throws IOException {
        try (InputStream in = bodyCaptor.getValue().contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("write+close emits a 430-byte record: 350 data, zero-padded code, padded description")
    void writesReject_produces430ByteRecord_zeroPaddedCode_paddedDescription() throws IOException {
        assertThat(data350().length()).isEqualTo(350);

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND")));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

        byte[] bytes = capturedBytes(bodyCaptor);
        assertThat(bytes).hasSize(RECORD_LENGTH);

        String rec = new String(bytes, StandardCharsets.ISO_8859_1);
        // Field 1 (offset 0, length 350): raw daily-transaction image, preserved verbatim.
        assertThat(rec.substring(0, 350)).isEqualTo(data350());
        // Field 2 (offset 350, length 4): numeric reason 9(04), zero-padded.
        assertThat(rec.substring(350, 354)).isEqualTo("0100");
        // Field 3 (offset 354, length 76): reason description X(76), left-justified, space-padded.
        assertThat(rec.substring(354, 430)).hasSize(76);
        assertThat(rec.substring(354, 430).strip()).isEqualTo("INVALID CARD NUMBER FOUND");

        // S3 destination contract: configured bucket, fixed object key, binary content type.
        assertThat(reqCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(reqCaptor.getValue().key()).isEqualTo("DALYREJS");
        assertThat(reqCaptor.getValue().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("reason code 109 is zero-padded to '0109'")
    void reasonCode109_zeroPaddedTo0109() throws IOException {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new RejectedTransaction(data350(), 109, "ACCOUNT RECORD NOT FOUND")));
        writer.close();

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

        String rec = new String(capturedBytes(bodyCaptor), StandardCharsets.ISO_8859_1);
        assertThat(rec.substring(350, 354)).isEqualTo("0109");
    }

    @Test
    @DisplayName("multiple rejects concatenate as contiguous 430-byte records")
    void multipleRejects_concatenateInBuffer() throws IOException {
        RejectedTransaction first = new RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND");
        RejectedTransaction second = new RejectedTransaction(data350(), 101, "ACCOUNT RECORD NOT FOUND");

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(first, second));
        writer.close();

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

        byte[] bytes = capturedBytes(bodyCaptor);
        // Two fixed-length 430-byte records, contiguous with no delimiter.
        assertThat(bytes).hasSize(2 * RECORD_LENGTH);

        String rec = new String(bytes, StandardCharsets.ISO_8859_1);
        // First record reason at offset 350; second record reason at 430 + 350 = 780.
        assertThat(rec.substring(350, 354)).isEqualTo("0100");
        assertThat(rec.substring(780, 784)).isEqualTo("0101");
    }

    @Test
    @DisplayName("rejection counter is tagged by reason code")
    void incrementsRejectedCounter_taggedByReasonCode() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new RejectedTransaction(data350(), 102, "OVERLIMIT TRANSACTION")));

        double count = registry.get(MetricsConfig.BATCH_RECORDS_REJECTED)
                .tags(MetricsConfig.TAG_REASON, "102")
                .counter()
                .count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("rejection counter increments once per item")
    void rejectedCounter_incrementsPerItem() {
        RejectedTransaction one = new RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND");
        RejectedTransaction two = new RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND");

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(one, two));

        double count = registry.get(MetricsConfig.BATCH_RECORDS_REJECTED)
                .tags(MetricsConfig.TAG_REASON, "100")
                .counter()
                .count();
        assertThat(count).isEqualTo(2.0);
    }

    @Test
    @DisplayName("no rejects -> no S3 object is written (empty-buffer guard)")
    void emptyBuffer_noS3Write() {
        writer.open(new ExecutionContext());
        writer.close();

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("S3 failure on close is wrapped in FileAccessException")
    void s3Failure_onClose_throwsFileAccessException() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new RejectedTransaction(data350(), 100, "INVALID CARD NUMBER FOUND")));

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.builder().message("simulated S3 failure").build());

        assertThatThrownBy(() -> writer.close()).isInstanceOf(FileAccessException.class);
    }
}
