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
import com.carddemo.batch.writers.StatementWriter;
import com.carddemo.exception.FileAccessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
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
 * Unit tests for {@link StatementWriter} (COBOL {@code CBSTM03A} {@code STMTFILE}/{@code HTMLFILE}
 * &rarr; S3, source commit {@code 27d6c6f}). Verifies the two run-level objects with the correct
 * keys/content types, verbatim byte persistence, the processed metric, the empty/partial buffer
 * behaviour, S3 failure mapping to {@link FileAccessException}, and the structured SLF4J logging
 * added for the Observability rule (R1) — including that statement bodies are never logged.
 */
class StatementWriterTest {

    private static final String BUCKET = "carddemo-statements";
    private static final String TEXT_KEY = "STATEMNT.PS";
    private static final String HTML_KEY = "STATEMNT.HTML";
    private static final String SECRET_BODY = "BALANCE 1234.56 SENSITIVE-STATEMENT-BODY";

    private S3Client s3Client;
    private SimpleMeterRegistry registry;
    private StatementWriter writer;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        registry = new SimpleMeterRegistry();
        writer = new StatementWriter(s3Client, registry, BUCKET);
        logbackLogger = (Logger) LoggerFactory.getLogger(StatementWriter.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("Text and HTML statements are written as two keyed objects with correct content types")
    void writesTextAndHtmlObjects() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementWriter.StatementDocument(SECRET_BODY, "<html>" + SECRET_BODY + "</html>")));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(reqCaptor.capture(), any(RequestBody.class));

        List<PutObjectRequest> reqs = reqCaptor.getAllValues();
        assertThat(reqs).anyMatch(r -> r.key().equals(TEXT_KEY) && "text/plain".equals(r.contentType()));
        assertThat(reqs).anyMatch(r -> r.key().equals(HTML_KEY) && "text/html".equals(r.contentType()));
        assertThat(reqs).allMatch(r -> r.bucket().equals(BUCKET));

        assertThat(registry.counter("carddemo.batch.records.processed").count()).isEqualTo(1.0d);

        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("Wrote statement object to S3"));
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("flush complete")
                        && e.getFormattedMessage().contains("statements=1"));
        // The statement body must never be logged.
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("SENSITIVE-STATEMENT-BODY"));
    }

    @Test
    @DisplayName("A null variant is treated as empty so only the populated variant is written")
    void nullVariantTreatedAsEmpty() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementWriter.StatementDocument("text only", null)));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(1)).putObject(reqCaptor.capture(), any(RequestBody.class));
        assertThat(reqCaptor.getValue().key()).isEqualTo(TEXT_KEY);
    }

    @Test
    @DisplayName("An empty run writes no objects but still logs the flush summary")
    void emptyRunWritesNothing() {
        writer.open(new ExecutionContext());
        writer.close();

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("flush complete")
                        && e.getFormattedMessage().contains("statements=0"));
    }

    @Test
    @DisplayName("An S3 failure is mapped to FileAccessException and logged as ERROR")
    void s3FailureMapsToFileAccessException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.builder().message("timeout").build());

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementWriter.StatementDocument("text", "<html/>")));

        assertThatThrownBy(() -> writer.close())
                .isInstanceOf(FileAccessException.class)
                .hasMessageContaining(TEXT_KEY);

        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("Failed to write statement object to S3"));
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
