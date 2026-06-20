package com.carddemo.batch.writers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Spring Batch {@link ItemStreamWriter} that persists generated customer statements to AWS S3
 * (LocalStack-compatible) as two run-level objects: a plain-text variant and an HTML variant.
 *
 * <p>Translated (reference only; COBOL source is not copied &mdash; traceability via commit
 * {@code 27d6c6f}) from {@code app/cbl/CBSTM03A.CBL}, which writes two sequential output files:
 * {@code STMTFILE} ({@code FD-STMTFILE-REC PIC X(80)}, plain-text statement) and {@code HTMLFILE}
 * ({@code FD-HTMLFILE-REC PIC X(100)}, HTML statement). The statement record layout reference is
 * {@code app/cpy/COSTM01.CPY}; the generation-data-group to S3 mapping reference is
 * {@code app/jcl/DEFGDGB.jcl}.</p>
 *
 * <h2>Upstream rendering contract (preserved byte-for-byte)</h2>
 * <p>Each {@link StatementDocument} arrives already rendered and padded by the upstream
 * processor/job to the original mainframe fixed-width line contracts: the plain-text variant to
 * <strong>LRECL=80</strong> (80-column lines, from {@code FD-STMTFILE-REC PIC X(80)}) and the HTML
 * variant to <strong>LRECL=100</strong> (100-column lines, from {@code FD-HTMLFILE-REC PIC X(100)}).
 * This writer is a faithful byte sink: it persists both variants verbatim, performing no trimming,
 * re-wrapping, re-padding, or re-encoding. All content is encoded with
 * {@link StandardCharsets#ISO_8859_1}, a single-byte-per-character charset that preserves the
 * fixed-width fidelity exactly.</p>
 *
 * <h2>Output objects</h2>
 * <p>All statements produced during a single batch run are concatenated into exactly two objects
 * written to the {@code carddemo-statements} bucket (overridable via the
 * {@code carddemo.aws.s3.bucket-statements} property): {@value #TEXT_OBJECT_KEY}
 * ({@code text/plain}) and {@value #HTML_OBJECT_KEY} ({@code text/html}). Because the target bucket
 * is versioned, each run's {@code putObject} produces a new object version, providing the
 * generation/retention behaviour formerly given by the mainframe generation data groups.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Single-threaded, sequential chunk execution is assumed (COBOL parity). The per-run buffers are
 * allocated in {@link #open(ExecutionContext)}, appended to in {@link #write(Chunk)}, and flushed to
 * S3 once in {@link #close()} &mdash; producing one {@value #TEXT_OBJECT_KEY} and one
 * {@value #HTML_OBJECT_KEY} object per run.</p>
 */
@Component
public class StatementWriter implements ItemStreamWriter<StatementWriter.StatementDocument> {

    /**
     * Structured logger for writer lifecycle and S3 outcomes (Observability rule, AAP 0.7.1). Emits
     * only non-sensitive context — the bucket/key, object byte sizes, and statement counts — and
     * NEVER the statement body (customer financial content).
     */
    private static final Logger log = LoggerFactory.getLogger(StatementWriter.class);

    /** S3 object key for the accumulated plain-text statement output (from COBOL {@code STMTFILE}). */
    private static final String TEXT_OBJECT_KEY = "STATEMNT.PS";

    /** S3 object key for the accumulated HTML statement output (from COBOL {@code HTMLFILE}). */
    private static final String HTML_OBJECT_KEY = "STATEMNT.HTML";

    /** Content type recorded on the plain-text statement object. */
    private static final String TEXT_CONTENT_TYPE = "text/plain";

    /** Content type recorded on the HTML statement object. */
    private static final String HTML_CONTENT_TYPE = "text/html";

    /** Synchronous S3 client used to persist the run-level statement objects. */
    private final S3Client s3Client;

    /** Micrometer registry used to increment the per-statement processed counter. */
    private final MeterRegistry meterRegistry;

    /** Destination bucket for both statement objects; defaults to {@code carddemo-statements}. */
    private final String statementsBucket;

    /** Accumulates the plain-text statement bytes for the current run; allocated in {@link #open}. */
    private ByteArrayOutputStream textBuffer;

    /** Accumulates the HTML statement bytes for the current run; allocated in {@link #open}. */
    private ByteArrayOutputStream htmlBuffer;

    /** Count of statements appended this run, logged as a lifecycle summary in {@link #close()}. */
    private long statementsProcessed;

    /**
     * Creates the writer with its collaborators injected by Spring.
     *
     * @param s3Client         the synchronous S3 client bean (see {@code com.carddemo.config.AwsConfig})
     * @param meterRegistry    the auto-configured Micrometer registry for the processed-records metric
     * @param statementsBucket the destination S3 bucket name, resolved from the
     *                         {@code carddemo.aws.s3.bucket-statements} property (default
     *                         {@code carddemo-statements})
     */
    public StatementWriter(final S3Client s3Client,
                           final MeterRegistry meterRegistry,
                           @Value("${carddemo.aws.s3.bucket-statements:carddemo-statements}") final String statementsBucket) {
        this.s3Client = s3Client;
        this.meterRegistry = meterRegistry;
        this.statementsBucket = statementsBucket;
    }

    /**
     * Allocates fresh per-run accumulation buffers at the start of the step.
     *
     * @param executionContext the step execution context (unused; no incremental state is restored)
     */
    @Override
    public void open(final ExecutionContext executionContext) {
        this.textBuffer = new ByteArrayOutputStream();
        this.htmlBuffer = new ByteArrayOutputStream();
        this.statementsProcessed = 0L;
        log.debug("Statement writer opened; buffering statements for run: bucket={}", statementsBucket);
    }

    /**
     * No incremental state is persisted between chunks: the complete output is flushed once, in
     * {@link #close()}. This method is intentionally a no-op.
     *
     * @param executionContext the step execution context (unused)
     */
    @Override
    public void update(final ExecutionContext executionContext) {
        // No incremental state is persisted across chunks; flushing occurs in close().
    }

    /**
     * Appends each statement's text and HTML content to the per-run buffers verbatim and records one
     * processed-record metric per statement. A {@code null} value for either variant is treated as an
     * empty string. No content transformation is performed: the bytes are persisted exactly as
     * supplied by the upstream renderer (byte-exact persistence; the upstream owns the LRECL=80/100
     * line-width formatting).
     *
     * @param chunk the chunk of statements to append to the run-level buffers
     */
    @Override
    public void write(final Chunk<? extends StatementDocument> chunk) {
        for (final StatementDocument item : chunk) {
            final String text = (item.textContent() == null) ? "" : item.textContent();
            final String html = (item.htmlContent() == null) ? "" : item.htmlContent();
            this.textBuffer.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
            this.htmlBuffer.writeBytes(html.getBytes(StandardCharsets.ISO_8859_1));
            MetricsConfig.recordsProcessed(this.meterRegistry).increment();
            this.statementsProcessed++;
        }
    }

    /**
     * Flushes the accumulated buffers to S3 at the end of the step. The plain-text object is written
     * only when text bytes were accumulated, and likewise for the HTML object; an empty buffer yields
     * no object for that variant. Both buffers are released in a {@code finally} block regardless of
     * outcome so the writer holds no run state after the step ends.
     */
    @Override
    public void close() {
        try {
            if (this.textBuffer != null && this.textBuffer.size() > 0) {
                putObject(TEXT_OBJECT_KEY, this.textBuffer.toByteArray(), TEXT_CONTENT_TYPE);
            }
            if (this.htmlBuffer != null && this.htmlBuffer.size() > 0) {
                putObject(HTML_OBJECT_KEY, this.htmlBuffer.toByteArray(), HTML_CONTENT_TYPE);
            }
            log.info("Statement writer flush complete: bucket={}, statements={}",
                    statementsBucket, statementsProcessed);
        } finally {
            this.textBuffer = null;
            this.htmlBuffer = null;
        }
    }

    /**
     * Persists a single payload to the configured statements bucket under the given key, mapping any
     * AWS SDK failure to a {@link FileAccessException} &mdash; the idiomatic Java replacement for the
     * COBOL fatal I/O abend path.
     *
     * @param key         the S3 object key under which to store the payload
     * @param payload     the bytes to store
     * @param contentType the MIME content type to record on the stored object
     * @throws FileAccessException if the S3 {@code putObject} call fails
     */
    private void putObject(final String key, final byte[] payload, final String contentType) {
        try {
            this.s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(this.statementsBucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(payload));
            // Object size only (never the statement body, which is customer financial content).
            log.info("Wrote statement object to S3: bucket={}, key={}, bytes={}",
                    this.statementsBucket, key, payload.length);
        } catch (final SdkException e) {
            // Server-side diagnostic with bucket/key (operational context) and cause; this detail is
            // intentionally sanitized out of any API client response (see GlobalExceptionHandler).
            log.error("Failed to write statement object to S3: bucket={}, key={}, cause={}",
                    this.statementsBucket, key, e.getMessage());
            throw new FileAccessException(
                    "Failed to write statement object to S3 " + this.statementsBucket + "/" + key, e);
        }
    }

    /**
     * Immutable input contract for {@link StatementWriter}: one rendered customer statement in both
     * output variants. {@code textContent} is the complete plain-text statement already padded to the
     * 80-column line contract, and {@code htmlContent} is the same statement rendered as HTML and
     * formatted to the 100-column line contract. Either component may be {@code null}, which the
     * writer treats as empty content for that variant.
     *
     * @param textContent the plain-text statement (LRECL=80 upstream contract), or {@code null}
     * @param htmlContent the HTML statement (LRECL=100 upstream contract), or {@code null}
     */
    public record StatementDocument(String textContent, String htmlContent) {
    }
}
