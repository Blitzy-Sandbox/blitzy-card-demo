package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.exception.FileAccessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Unit tests for {@link RejectWriter} (COBOL {@code CBTRN02C} {@code 2500-WRITE-REJECT-REC} &rarr; S3,
 * source commit {@code 27d6c6f}). Verifies byte-exact 430-byte record layout, the single per-run S3
 * object, the per-reason rejection metric, the empty-run no-op, S3 failure mapping to
 * {@link FileAccessException}, and the structured SLF4J logging added for the Observability rule (R1)
 * — including the guarantee that rejected record content (e.g. a card number) is never logged.
 */
class RejectWriterTest {

    private static final int RECORD_LENGTH = 430;
    private static final String BUCKET = "carddemo-batch-output";
    private static final String KEY = "DALYREJS";
    /** A fake card-number-like token embedded in raw record content; must never appear in logs. */
    private static final String FAKE_PAN = "4111111111111111";

    private S3Client s3Client;
    private SimpleMeterRegistry registry;
    private RejectWriter writer;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        registry = new SimpleMeterRegistry();
        writer = new RejectWriter(s3Client, registry, BUCKET);
        logbackLogger = (Logger) LoggerFactory.getLogger(RejectWriter.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    private static RejectWriter.RejectedTransaction reject(String raw, int code, String desc) {
        return new RejectWriter.RejectedTransaction(raw, code, desc);
    }

    @Test
    @DisplayName("Two rejects produce one 860-byte S3 object with byte-exact 430-byte records and per-reason metrics")
    void writesByteExactRecordsAndMetrics() throws Exception {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(
                reject("DATA-A " + FAKE_PAN, 101, "Account not found"),
                reject("DATA-B", 102, "Card not active")));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(1)).putObject(reqCaptor.capture(), bodyCaptor.capture());

        assertThat(reqCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(reqCaptor.getValue().key()).isEqualTo(KEY);

        byte[] payload = bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes();
        assertThat(payload).hasSize(2 * RECORD_LENGTH);

        String rec0 = new String(payload, 0, RECORD_LENGTH, StandardCharsets.ISO_8859_1);
        // Field 1 (offset 0..349) carries the raw data left-justified.
        assertThat(rec0).startsWith("DATA-A ");
        // Field 2 (offset 350..353) is the zero-padded reason code 9(04).
        assertThat(rec0.substring(350, 354)).isEqualTo("0101");
        // Field 3 (offset 354..429) is the left-justified reason description.
        assertThat(rec0.substring(354).trim()).isEqualTo("Account not found");

        assertThat(registry.counter("carddemo.batch.records.rejected", "reason", "101").count()).isEqualTo(1.0d);
        assertThat(registry.counter("carddemo.batch.records.rejected", "reason", "102").count()).isEqualTo(1.0d);

        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("Wrote reject file to S3")
                        && e.getFormattedMessage().contains("records=2"));
        // No rejected record content (card number) may ever be logged.
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(FAKE_PAN));
    }

    @Test
    @DisplayName("An empty run writes no S3 object and logs the no-op")
    void emptyRunWritesNothing() {
        writer.open(new ExecutionContext());
        writer.close();

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("No rejected transactions"));
    }

    @Test
    @DisplayName("An S3 failure is mapped to FileAccessException and logged as ERROR")
    void s3FailureMapsToFileAccessException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.builder().message("connection reset").build());

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(reject("DATA-A", 101, "Account not found")));

        assertThatThrownBy(() -> writer.close())
                .isInstanceOf(FileAccessException.class)
                .hasMessageContaining(KEY);

        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("Failed to write reject file to S3"));
        // After a failed close the buffer is released, so a subsequent close is a quiet no-op.
        writer.close();
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("update() persists no incremental state")
    void updateIsNoOp() {
        writer.open(new ExecutionContext());
        writer.update(new ExecutionContext());
        writer.close();
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
