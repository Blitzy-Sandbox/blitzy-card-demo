package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.writers.StatementWriter;
import com.carddemo.batch.writers.StatementWriter.StatementDocument;
import com.carddemo.exception.FileAccessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * Unit tests for {@link StatementWriter}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the writer
 * migrates the dual sequential output of {@code app/cbl/CBSTM03A.CBL} &mdash; {@code STMTFILE}
 * ({@code FD-STMTFILE-REC PIC X(80)}, the plain-text statement) and {@code HTMLFILE}
 * ({@code FD-HTMLFILE-REC PIC X(100)}, the HTML statement), each emitted verbatim via
 * {@code WRITE ... FROM} &mdash; to two versioned S3 objects, {@code STATEMNT.PS} and
 * {@code STATEMNT.HTML}. The statement record layout reference is {@code app/cpy/COSTM01.CPY};
 * the data-source side is {@code app/cbl/CBSTM03B.CBL}.</p>
 *
 * <p>These are pure-JVM tests: the {@link S3Client} is Mockito-mocked and a real
 * {@link SimpleMeterRegistry} is used, with no Spring context, Testcontainers, or LocalStack. They
 * prove the two-object output with exact keys and content types, verbatim
 * {@link StandardCharsets#ISO_8859_1} content, the per-document
 * {@code carddemo.batch.records.processed} metric increment, the empty-variant guard, and the
 * {@code SdkException} &rarr; {@link FileAccessException} wrapping (AAP 0.7.7 / 0.8.1).</p>
 *
 * <p>Because {@link StatementWriter#close()} may issue two {@code putObject} calls in a single run,
 * the assertions capture all invocations and match each by {@link PutObjectRequest#key()} rather
 * than relying on the put ordering.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementWriter - CBSTM03A two-object (STATEMNT.PS + STATEMNT.HTML) S3 sink")
class StatementWriterTest {

    /** Expected S3 object key for the plain-text statement variant (COBOL {@code STMTFILE}). */
    private static final String TEXT_KEY = "STATEMNT.PS";

    /** Expected S3 object key for the HTML statement variant (COBOL {@code HTMLFILE}). */
    private static final String HTML_KEY = "STATEMNT.HTML";

    /** Expected MIME content type recorded on the plain-text object. */
    private static final String TEXT_CONTENT_TYPE = "text/plain";

    /** Expected MIME content type recorded on the HTML object. */
    private static final String HTML_CONTENT_TYPE = "text/html";

    /**
     * Canonical processed-records metric name. This is the value of the public production constant
     * {@code MetricsConfig.BATCH_RECORDS_PROCESSED}; the literal is asserted here to bind the metric
     * name as part of the Observability contract.
     */
    private static final String PROCESSED_METRIC = "carddemo.batch.records.processed";

    /** Destination bucket supplied to the writer under test. */
    private static final String BUCKET = "carddemo-statements";

    @Mock
    private S3Client s3Client;

    private SimpleMeterRegistry registry;

    private StatementWriter writer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        writer = new StatementWriter(s3Client, registry, BUCKET);
    }

    // ----- Two objects with correct keys / content types / verbatim content -----

    @Test
    @DisplayName("write+close emits two objects: STATEMNT.PS (text/plain) and STATEMNT.HTML (text/html)")
    void writesTextAndHtml_asTwoObjects() throws IOException {
        final String text = "STATEMENT LINE 1\nLINE 2";
        final String html = "<html><body><p>STATEMENT LINE 1</p></body></html>";

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementDocument(text, html)));
        writer.close();

        final ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        final ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(2)).putObject(reqCaptor.capture(), bodyCaptor.capture());

        final List<PutObjectRequest> reqs = reqCaptor.getAllValues();
        final List<RequestBody> bodies = bodyCaptor.getAllValues();

        // Plain-text object (COBOL STMTFILE -> STATEMNT.PS).
        final int textIdx = indexOfKey(reqs, TEXT_KEY);
        final PutObjectRequest textReq = reqs.get(textIdx);
        assertThat(textReq.bucket()).isEqualTo(BUCKET);
        assertThat(textReq.contentType()).isEqualTo(TEXT_CONTENT_TYPE);
        assertThat(bytesFor(bodies, textIdx)).isEqualTo(text.getBytes(StandardCharsets.ISO_8859_1));

        // HTML object (COBOL HTMLFILE -> STATEMNT.HTML).
        final int htmlIdx = indexOfKey(reqs, HTML_KEY);
        final PutObjectRequest htmlReq = reqs.get(htmlIdx);
        assertThat(htmlReq.bucket()).isEqualTo(BUCKET);
        assertThat(htmlReq.contentType()).isEqualTo(HTML_CONTENT_TYPE);
        assertThat(bytesFor(bodies, htmlIdx)).isEqualTo(html.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("HTML content is persisted verbatim, including <p>/</p> tags (no transformation)")
    void htmlContent_preservedVerbatim_includingParagraphTags() throws IOException {
        final String text = "PLAIN TEXT STATEMENT";
        final String html = "<html><body><p>Account 00000000010</p></body></html>";

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementDocument(text, html)));
        writer.close();

        final ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        final ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(2)).putObject(reqCaptor.capture(), bodyCaptor.capture());

        final List<PutObjectRequest> reqs = reqCaptor.getAllValues();
        final List<RequestBody> bodies = bodyCaptor.getAllValues();

        final int htmlIdx = indexOfKey(reqs, HTML_KEY);
        final String captured = new String(bytesFor(bodies, htmlIdx), StandardCharsets.ISO_8859_1);
        assertThat(captured).isEqualTo(html);
        assertThat(captured).contains("<p>").contains("</p>");
    }

    // ----- Per-document processed metric -----

    @Test
    @DisplayName("records carddemo.batch.records.processed once for a single-document chunk")
    void incrementsProcessed_oncePerDocument() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementDocument("T1", "<p>H1</p>")));

        assertThat(registry.get(PROCESSED_METRIC).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("records carddemo.batch.records.processed once per document in a multi-document chunk")
    void incrementsProcessed_perDocumentInChunk() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(
                new StatementDocument("T1", "<p>H1</p>"),
                new StatementDocument("T2", "<p>H2</p>")));

        assertThat(registry.get(PROCESSED_METRIC).counter().count()).isEqualTo(2.0);
    }

    // ----- Empty-variant guard and error wrapping -----

    @Test
    @DisplayName("empty text variant is not written; only the HTML object is put")
    void emptyTextVariant_notWritten_onlyHtmlPut() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementDocument("", "<html><p>X</p></html>")));
        writer.close();

        final ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(1)).putObject(reqCaptor.capture(), any(RequestBody.class));

        final PutObjectRequest only = reqCaptor.getValue();
        assertThat(only.key()).isEqualTo(HTML_KEY);
        assertThat(only.contentType()).isEqualTo(HTML_CONTENT_TYPE);
    }

    @Test
    @DisplayName("both variants empty (blank and null) produce no S3 writes")
    void bothEmpty_noS3Writes() {
        writer.open(new ExecutionContext());
        // Blank strings and nulls are both treated as empty content for their variant.
        writer.write(Chunk.of(
                new StatementDocument("", ""),
                new StatementDocument(null, null)));
        writer.close();

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("S3 SdkException during close is wrapped as FileAccessException")
    void s3Failure_onClose_throwsFileAccessException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.builder().message("simulated").build());

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementDocument("STATEMENT", "<html><p>X</p></html>")));

        assertThatThrownBy(() -> writer.close())
                .isInstanceOf(FileAccessException.class)
                .hasCauseInstanceOf(SdkException.class);
    }

    // ----- Helpers -----

    /**
     * Reads the raw bytes carried by the captured {@link RequestBody} at the given capture index.
     * request[i] pairs with body[i] in Mockito capture order, so a single index addresses both.
     *
     * @param bodies the captured request bodies, in capture order
     * @param idx    the capture index to read
     * @return the full byte payload carried by the body at {@code idx}
     * @throws IOException if the body stream cannot be read
     */
    private static byte[] bytesFor(final List<RequestBody> bodies, final int idx) throws IOException {
        try (InputStream in = bodies.get(idx).contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * Returns the capture index of the {@link PutObjectRequest} whose key equals {@code key}, so the
     * paired request and body can be addressed without depending on the order in which
     * {@link StatementWriter#close()} issues its puts.
     *
     * @param reqs the captured put requests, in capture order
     * @param key  the S3 object key to locate
     * @return the index of the matching request
     * @throws AssertionError if no captured request carries {@code key}
     */
    private static int indexOfKey(final List<PutObjectRequest> reqs, final String key) {
        for (int i = 0; i < reqs.size(); i++) {
            if (key.equals(reqs.get(i).key())) {
                return i;
            }
        }
        throw new AssertionError("No captured PutObjectRequest for key: " + key);
    }
}
