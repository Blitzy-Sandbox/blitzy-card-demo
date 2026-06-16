package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.writers.StatementWriter;
import com.carddemo.batch.writers.StatementWriter.StatementDocument;
import com.carddemo.exception.FileAccessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
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
 * Unit tests for {@link StatementWriter}.
 *
 * <p>Traceability (REFERENCE-ONLY; COBOL not copied; source commit {@code 27d6c6f}): the writer
 * re-platforms {@code CBSTM03A}'s dual {@code STMTFILE}/{@code HTMLFILE} output, persisting the
 * plain-text ({@code STATEMNT.PS}, {@code text/plain}) and HTML ({@code STATEMNT.HTML},
 * {@code text/html}) statement variants to S3. These tests use a mocked {@link S3Client} and a
 * real {@link SimpleMeterRegistry} to assert both-variant emission, the per-variant empty-skip,
 * the processed-records metric, and the S3-failure mapping to {@link FileAccessException}.</p>
 */
@DisplayName("StatementWriter - dual text/HTML statement S3 sink")
class StatementWriterTest {

    private static final String BUCKET = "carddemo-statements";

    private S3Client s3Client;
    private SimpleMeterRegistry registry;
    private StatementWriter writer;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        registry = new SimpleMeterRegistry();
        writer = new StatementWriter(s3Client, registry, BUCKET);
    }

    @Test
    @DisplayName("Writes both text and HTML objects and counts each statement processed")
    void writesBothVariants() {
        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(
                new StatementDocument("TEXT-A", "<p>A</p>"),
                new StatementDocument("TEXT-B", "<p>B</p>")));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(reqCaptor.capture(), any(RequestBody.class));

        List<PutObjectRequest> requests = reqCaptor.getAllValues();
        assertThat(requests.get(0).bucket()).isEqualTo(BUCKET);
        assertThat(requests.get(0).key()).isEqualTo("STATEMNT.PS");
        assertThat(requests.get(0).contentType()).isEqualTo("text/plain");
        assertThat(requests.get(1).key()).isEqualTo("STATEMNT.HTML");
        assertThat(requests.get(1).contentType()).isEqualTo("text/html");

        assertThat(registry.get("carddemo.batch.records.processed").counter().count()).isEqualTo(2.0);
    }

    @Nested
    @DisplayName("Empty variants are skipped")
    class EmptyVariants {

        @Test
        @DisplayName("Empty HTML content skips the HTML object")
        void textOnly() {
            writer.open(new ExecutionContext());
            writer.write(new Chunk<>(new StatementDocument("ONLY-TEXT", "")));
            writer.close();

            ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client, times(1)).putObject(reqCaptor.capture(), any(RequestBody.class));
            assertThat(reqCaptor.getValue().key()).isEqualTo("STATEMNT.PS");
        }

        @Test
        @DisplayName("Empty text content skips the text object")
        void htmlOnly() {
            writer.open(new ExecutionContext());
            writer.write(new Chunk<>(new StatementDocument("", "<p>ONLY-HTML</p>")));
            writer.close();

            ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client, times(1)).putObject(reqCaptor.capture(), any(RequestBody.class));
            assertThat(reqCaptor.getValue().key()).isEqualTo("STATEMNT.HTML");
        }

        @Test
        @DisplayName("Null content is treated as empty and writes nothing")
        void nullContent() {
            writer.open(new ExecutionContext());
            writer.write(new Chunk<>(new StatementDocument(null, null)));
            writer.close();

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            assertThat(registry.get("carddemo.batch.records.processed").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("close without open (null buffers) writes nothing")
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
        writer.write(new Chunk<>(new StatementDocument("HELLO", null)));

        assertThatThrownBy(() -> writer.close())
                .isInstanceOf(FileAccessException.class)
                .hasMessage("Failed to write statement object to S3 carddemo-statements/STATEMNT.PS");
    }
}
