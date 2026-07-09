package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;

import com.carddemo.entity.CardXref;
import com.carddemo.exception.FileProcessingException;

import io.awspring.cloud.s3.S3Template;

/**
 * Pure, fast unit test for {@link StatementItemWriter}, the aggregate S3 writer
 * of the statement-generation job migrated from the COBOL batch program
 * {@code CBSTM03A}/{@code CBSTM03B} ({@code app/cbl/CBSTM03A.cbl}, frozen
 * reference SHA {@code 27d6c6f} &mdash; read-only, not copied into this
 * repository), whose {@code OPEN/WRITE/CLOSE STMT-FILE HTML-FILE} sequence maps
 * to the Spring Batch {@link org.springframework.batch.item.ItemStreamWriter}
 * lifecycle over a LocalStack-backed S3 bucket (versioned-object mapping of the
 * legacy GDG datasets).
 *
 * <p>The single {@link S3Template} collaborator is a Mockito mock, so the suite
 * uploads nothing to real AWS and needs no Spring context, database,
 * Testcontainers, or Docker. Coverage spans the constructor coordinate guards,
 * the fixed-width {@code PIC X(80)}/{@code PIC X(100)} pad-or-truncate behaviour,
 * the {@code null}/empty-chunk no-ops, the always-both-objects upload with buffer
 * release, the idempotent second {@code close()}, and the upload-failure path
 * that wraps any S3/SDK error in a fatal {@link FileProcessingException}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementItemWriter — COBOL CBSTM03A statement S3 writer (SHA 27d6c6f)")
class StatementItemWriterTest {

    private static final String BUCKET = "carddemo-batch-statements";
    private static final String TEXT_KEY = "statements.ps";
    private static final String HTML_KEY = "statements.html";

    @Mock
    private S3Template s3Template;

    private StatementItemWriter writer;

    @BeforeEach
    void setUp() {
        writer = new StatementItemWriter(s3Template, BUCKET, TEXT_KEY, HTML_KEY);
    }

    private static StatementDocument doc(List<String> textLines, List<String> htmlLines) {
        CardXref x = new CardXref();
        x.setXrefCardNum("4111111111111111");
        x.setXrefAcctId(1L);
        x.setXrefCustId(1L);
        return new StatementDocument(x, null, null, null, null, textLines, htmlLines);
    }

    @Nested
    @DisplayName("Constructor coordinate guards")
    class Guards {

        @Test
        @DisplayName("null S3Template → NullPointerException")
        void nullTemplate() {
            assertThatThrownBy(() -> new StatementItemWriter(null, BUCKET, TEXT_KEY, HTML_KEY))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank bucket → IllegalArgumentException")
        void blankBucket() {
            assertThatThrownBy(() -> new StatementItemWriter(s3Template, "  ", TEXT_KEY, HTML_KEY))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("blank text object key → IllegalArgumentException")
        void blankTextKey() {
            assertThatThrownBy(() -> new StatementItemWriter(s3Template, BUCKET, "", HTML_KEY))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("blank html object key → IllegalArgumentException")
        void blankHtmlKey() {
            assertThatThrownBy(() -> new StatementItemWriter(s3Template, BUCKET, TEXT_KEY, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("write() — fixed-width buffering")
    class Writing {

        @Test
        @DisplayName("pads short lines and truncates long lines to the fixed record width")
        void padsAndTruncates() {
            writer.open(new ExecutionContext());
            String longLine = "X".repeat(90); // longer than the 80-char text record
            writer.write(new Chunk<>(List.of(doc(List.of("SHORT", longLine), List.of("<html>")))));

            List<String> text = writer.getTextBuffer();
            assertThat(text).hasSize(2);
            assertThat(text.get(0)).hasSize(StatementItemWriter.TEXT_RECORD_LENGTH)
                    .startsWith("SHORT").isEqualTo("SHORT" + " ".repeat(75));
            assertThat(text.get(1)).hasSize(StatementItemWriter.TEXT_RECORD_LENGTH)
                    .isEqualTo("X".repeat(80));

            List<String> html = writer.getHtmlBuffer();
            assertThat(html).hasSize(1);
            assertThat(html.get(0)).hasSize(StatementItemWriter.HTML_RECORD_LENGTH)
                    .startsWith("<html>");
        }

        @Test
        @DisplayName("null chunk is a no-op")
        void nullChunk() {
            writer.open(new ExecutionContext());
            writer.write(null);
            assertThat(writer.getTextBuffer()).isEmpty();
            assertThat(writer.getHtmlBuffer()).isEmpty();
        }

        @Test
        @DisplayName("empty chunk is a no-op")
        void emptyChunk() {
            writer.open(new ExecutionContext());
            writer.write(new Chunk<>());
            assertThat(writer.getTextBuffer()).isEmpty();
        }

        @Test
        @DisplayName("open() clears any previously buffered content")
        void openClearsBuffers() {
            writer.open(new ExecutionContext());
            writer.write(new Chunk<>(List.of(doc(List.of("A"), List.of("B")))));
            assertThat(writer.getTextBuffer()).isNotEmpty();
            writer.open(new ExecutionContext());
            assertThat(writer.getTextBuffer()).isEmpty();
            assertThat(writer.getHtmlBuffer()).isEmpty();
        }
    }

    @Nested
    @DisplayName("close() — S3 upload lifecycle")
    class Closing {

        @Test
        @DisplayName("uploads both objects, then releases the buffers")
        void uploadsBothObjects() {
            writer.open(new ExecutionContext());
            writer.write(new Chunk<>(List.of(doc(List.of("LINE"), List.of("<p>")))));
            writer.close();

            verify(s3Template).upload(eq(BUCKET), eq(TEXT_KEY), any(InputStream.class));
            verify(s3Template).upload(eq(BUCKET), eq(HTML_KEY), any(InputStream.class));
            // Buffers released after a successful upload.
            assertThat(writer.getTextBuffer()).isEmpty();
            assertThat(writer.getHtmlBuffer()).isEmpty();
        }

        @Test
        @DisplayName("second close() is a no-op (idempotent) — objects uploaded once")
        void idempotentClose() {
            writer.open(new ExecutionContext());
            writer.close();
            writer.close();

            // Two uploads total (one text + one html), not four.
            verify(s3Template, times(2)).upload(anyString(), anyString(), any(InputStream.class));
        }

        @Test
        @DisplayName("upload failure is wrapped in FileProcessingException")
        void uploadFailureWrapped() {
            doThrow(new RuntimeException("boom"))
                    .when(s3Template).upload(anyString(), anyString(), any(InputStream.class));
            writer.open(new ExecutionContext());

            assertThatThrownBy(() -> writer.close())
                    .isInstanceOf(FileProcessingException.class);
        }
    }
}
