package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.writers.StatementWriter;
import com.carddemo.exception.FileAccessException;
import com.carddemo.observability.MetricsConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
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
 * Unit tests for {@link StatementWriter}, the Spring Batch
 * {@link org.springframework.batch.item.ItemStreamWriter} that persists generated customer
 * statements to Amazon S3 in two variants (plain text and HTML).
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * {@code app/cbl/CBSTM03A.CBL} prints account statements in two formats &mdash; plain text and
 * HTML &mdash; writing {@code STMTFILE} (text, {@code FD-STMTFILE-REC PIC X(80)}) and
 * {@code HTMLFILE} (HTML, {@code FD-HTMLFILE-REC PIC X(100)}); the file-access sub-service is
 * {@code app/cbl/CBSTM03B.CBL} and the reporting record layout is
 * {@code app/cpy/COSTM01.CPY}. These tests pin the migrated behavior to the production
 * contract: two run-level S3 objects with fixed keys ({@code STATEMNT.PS}, {@code STATEMNT.HTML})
 * and content types ({@code text/plain}, {@code text/html}), byte-exact
 * {@link StandardCharsets#ISO_8859_1} content, a per-statement
 * {@code carddemo.batch.records.processed} increment, the empty-variant skip guard, and
 * {@link FileAccessException} wrapping of any {@link SdkException} (AAP sections 0.7.7 and
 * 0.8.1).</p>
 *
 * <p>This is a pure-JVM unit test: the {@link S3Client} is mocked with Mockito and a real
 * {@link SimpleMeterRegistry} records the metric. No Spring context, Testcontainers, or
 * LocalStack is involved.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementWriter - dual text/HTML statement persistence to S3")
class StatementWriterTest {

    /** Destination bucket supplied to the writer under test. */
    private static final String BUCKET = "carddemo-statements";

    /** S3 object key for the plain-text statement variant (COBOL {@code STMTFILE}). */
    private static final String TEXT_KEY = "STATEMNT.PS";

    /** S3 object key for the HTML statement variant (COBOL {@code HTMLFILE}). */
    private static final String HTML_KEY = "STATEMNT.HTML";

    @Mock
    private S3Client s3Client;

    private SimpleMeterRegistry registry;
    private StatementWriter writer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        writer = new StatementWriter(s3Client, registry, BUCKET);
    }

    /**
     * Reads the full payload of the captured {@link RequestBody} at the given index.
     *
     * @param bodies the captured request bodies, paired by index with the captured requests
     * @param idx    the index to read
     * @return the body bytes
     * @throws IOException if the request-body stream cannot be read
     */
    private static byte[] bytesFor(List<RequestBody> bodies, int idx) throws IOException {
        return bodies.get(idx).contentStreamProvider().newStream().readAllBytes();
    }

    /**
     * Returns the capture index of the {@link PutObjectRequest} whose object key equals the
     * supplied key. Because {@link StatementWriter#close()} may issue two {@code putObject}
     * calls, requests are matched by key rather than by call order; request {@code [i]} pairs
     * with body {@code [i]} in capture order.
     *
     * @param requests the captured put requests
     * @param key      the object key to locate
     * @return the index of the matching request
     */
    private static int indexOfKey(List<PutObjectRequest> requests, String key) {
        for (int i = 0; i < requests.size(); i++) {
            if (key.equals(requests.get(i).key())) {
                return i;
            }
        }
        throw new AssertionError("No captured PutObjectRequest carried object key: " + key);
    }

    @Test
    @DisplayName("writes the text and HTML variants as two distinct S3 objects with correct keys, "
            + "content types, bucket, and verbatim ISO-8859-1 content")
    void writesTextAndHtml_asTwoObjects() throws Exception {
        String text = "STATEMENT LINE 1\nLINE 2";
        String html = "<html><body><p>STATEMENT LINE 1</p></body></html>";

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementWriter.StatementDocument(text, html)));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(2)).putObject(reqCaptor.capture(), bodyCaptor.capture());

        List<PutObjectRequest> reqs = reqCaptor.getAllValues();
        List<RequestBody> bodies = bodyCaptor.getAllValues();

        int textIdx = indexOfKey(reqs, TEXT_KEY);
        PutObjectRequest textReq = reqs.get(textIdx);
        assertThat(textReq.bucket()).isEqualTo(BUCKET);
        assertThat(textReq.contentType()).isEqualTo("text/plain");
        assertThat(bytesFor(bodies, textIdx)).isEqualTo(text.getBytes(StandardCharsets.ISO_8859_1));

        int htmlIdx = indexOfKey(reqs, HTML_KEY);
        PutObjectRequest htmlReq = reqs.get(htmlIdx);
        assertThat(htmlReq.bucket()).isEqualTo(BUCKET);
        assertThat(htmlReq.contentType()).isEqualTo("text/html");
        assertThat(bytesFor(bodies, htmlIdx)).isEqualTo(html.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("HTML content is persisted verbatim, including <p>...</p> tags (no transformation)")
    void htmlContent_preservedVerbatim_includingParagraphTags() throws Exception {
        String html = "<html><body><p>Account 0000000001</p></body></html>";

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementWriter.StatementDocument("TEXT", html)));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(2)).putObject(reqCaptor.capture(), bodyCaptor.capture());

        List<PutObjectRequest> reqs = reqCaptor.getAllValues();
        List<RequestBody> bodies = bodyCaptor.getAllValues();

        String captured = new String(bytesFor(bodies, indexOfKey(reqs, HTML_KEY)),
                StandardCharsets.ISO_8859_1);
        assertThat(captured).isEqualTo(html);
        assertThat(captured).contains("<p>").contains("</p>");
    }

    @Test
    @DisplayName("increments carddemo.batch.records.processed exactly once for a single statement")
    void incrementsProcessed_oncePerDocument() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementWriter.StatementDocument("TEXT", "<html></html>")));

        assertThat(registry.get(MetricsConfig.BATCH_RECORDS_PROCESSED).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("increments carddemo.batch.records.processed once per statement in a multi-item chunk")
    void incrementsProcessed_perDocumentInChunk() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(
                new StatementWriter.StatementDocument("T1", "<html>1</html>"),
                new StatementWriter.StatementDocument("T2", "<html>2</html>")));

        assertThat(registry.get(MetricsConfig.BATCH_RECORDS_PROCESSED).counter().count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("an empty text variant is not written; only the non-empty HTML object is put")
    void emptyTextVariant_notWritten_onlyHtmlPut() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementWriter.StatementDocument("", "<html><p>X</p></html>")));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(1)).putObject(reqCaptor.capture(), any(RequestBody.class));

        assertThat(reqCaptor.getValue().key()).isEqualTo(HTML_KEY);
    }

    @Test
    @DisplayName("both variants empty (empty string and null) result in no S3 writes")
    void bothEmpty_noS3Writes() {
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(
                new StatementWriter.StatementDocument("", ""),
                new StatementWriter.StatementDocument(null, null)));
        writer.close();

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("an SdkException on close is wrapped in a FileAccessException")
    void s3Failure_onClose_throwsFileAccessException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.builder().message("simulated").build());

        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new StatementWriter.StatementDocument("STATEMENT", "<html><p>S</p></html>")));

        assertThatThrownBy(() -> writer.close())
                .isInstanceOf(FileAccessException.class);
    }
}
