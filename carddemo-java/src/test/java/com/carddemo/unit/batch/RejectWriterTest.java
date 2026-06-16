package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.batch.writers.RejectWriter.RejectedTransaction;
import com.carddemo.exception.FileAccessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Unit tests for {@link RejectWriter}.
 *
 * <p>Traceability (REFERENCE-ONLY; COBOL not copied; source commit {@code 27d6c6f}): the writer
 * re-platforms {@code CBTRN02C} paragraph {@code 2500-WRITE-REJECT-REC}, emitting the 430-byte
 * {@code DALYREJS} reject record (350-byte raw transaction + 4-byte numeric reason + 76-byte
 * description) to S3. These tests use a mocked {@link S3Client} and a real
 * {@link SimpleMeterRegistry} to assert the byte-exact fixed-width layout, the rejection metric,
 * the empty-run "no object" behaviour, and the S3-failure mapping to {@link FileAccessException}.</p>
 */
@DisplayName("RejectWriter - 430-byte DALYREJS reject record S3 sink")
class RejectWriterTest {

    private static final String BUCKET = "carddemo-batch-output";

    private S3Client s3Client;
    private SimpleMeterRegistry registry;
    private RejectWriter writer;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        registry = new SimpleMeterRegistry();
        writer = new RejectWriter(s3Client, registry, BUCKET);
    }

    @Nested
    @DisplayName("Writing and flushing reject records")
    class Writing {

        @Test
        @DisplayName("Flushes a single 430-byte object with the DALYREJS layout and counts the rejection")
        void writesLayout() throws Exception {
            writer.open(new ExecutionContext());
            writer.write(new Chunk<>(new RejectedTransaction("DATA0", 100, "Account not found")));
            writer.close();

            ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

            PutObjectRequest request = reqCaptor.getValue();
            assertThat(request.bucket()).isEqualTo(BUCKET);
            assertThat(request.key()).isEqualTo("DALYREJS");
            assertThat(request.contentType()).isEqualTo("application/octet-stream");

            byte[] bytes = bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes();
            String record = new String(bytes, StandardCharsets.ISO_8859_1);
            assertThat(bytes).hasSize(430);
            assertThat(record).startsWith("DATA0");
            assertThat(record.substring(350, 354)).isEqualTo("0100");
            assertThat(record.substring(354)).startsWith("Account not found");

            assertThat(registry.get("carddemo.batch.records.rejected").tag("reason", "100").counter().count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("buildRecord handles null data, over-long reason truncation, and null description")
        void buildRecordEdgeCases() throws Exception {
            writer.open(new ExecutionContext());
            writer.write(new Chunk<>(
                    new RejectedTransaction("DATA0", 100, "desc zero"),
                    new RejectedTransaction(null, 12345, "x"),
                    new RejectedTransaction("D2", 7, null)));
            writer.close();

            ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] bytes = bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes();
            String all = new String(bytes, StandardCharsets.ISO_8859_1);

            assertThat(bytes).hasSize(1290);
            assertThat(all.substring(350, 354)).isEqualTo("0100");
            // Record 1 (offset 430): null data -> 350 spaces; reason 12345 truncated to last 4 -> "2345".
            assertThat(all.substring(430, 780)).isBlank();
            assertThat(all.substring(780, 784)).isEqualTo("2345");
            // Record 2 (offset 860): reason 7 zero-padded -> "0007"; null description -> 76 spaces.
            assertThat(all.substring(1210, 1214)).isEqualTo("0007");
            assertThat(all.substring(1214, 1290)).isBlank();
        }
    }

    @Nested
    @DisplayName("No object is written when nothing is rejected")
    class NoObject {

        @Test
        @DisplayName("close after an empty run writes no object")
        void emptyRunWritesNothing() {
            writer.open(new ExecutionContext());
            writer.close();

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("close without open (null buffer) writes no object")
        void closeWithoutOpen() {
            writer.close();

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }
    }

    @Test
    @DisplayName("An S3 put failure surfaces as FileAccessException naming the bucket/key")
    void s3FailureMapsToFileAccess() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.create("simulated S3 failure", null));

        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(new RejectedTransaction("DATA0", 100, "Account not found")));

        assertThatThrownBy(() -> writer.close())
                .isInstanceOf(FileAccessException.class)
                .hasMessage("Failed to write reject file to S3 carddemo-batch-output/DALYREJS");
    }
}
