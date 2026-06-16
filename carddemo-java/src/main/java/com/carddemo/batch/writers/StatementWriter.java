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
 * Spring Batch {@link ItemStreamWriter} that persists generated customer statements to
 * Amazon S3 in two output variants &mdash; plain text and HTML.
 *
 * <p>Translated from the statement-generation main program {@code app/cbl/CBSTM03A.CBL}
 * (source commit {@code 27d6c6f}), which writes two sequential output files: {@code STMTFILE}
 * (the plain-text statement, fixed record {@code PIC X(80)}, LRECL&nbsp;=&nbsp;80) and
 * {@code HTMLFILE} (the HTML statement, fixed record {@code PIC X(100)}, LRECL&nbsp;=&nbsp;100).
 * The COBOL {@code OPEN OUTPUT STMT-FILE HTML-FILE} maps to {@link #open(ExecutionContext)},
 * each {@code WRITE FD-STMTFILE-REC}/{@code WRITE FD-HTMLFILE-REC} maps to
 * {@link #write(Chunk)}, and {@code CLOSE STMT-FILE HTML-FILE} maps to {@link #close()},
 * which flushes the accumulated content to S3. The transaction-altered reporting record
 * layout reference is {@code app/cpy/COSTM01.CPY}.</p>
 *
 * <p>The two variants are written to the bucket configured by
 * {@code carddemo.aws.s3.bucket-statements} (default {@code carddemo-statements}) as a single
 * object per run per variant: {@code STATEMNT.PS} ({@code text/plain}) and
 * {@code STATEMNT.HTML} ({@code text/html}). All {@code StatementDocument} items of a run are
 * concatenated into these two run-level objects. Bucket versioning makes each run's
 * {@code putObject} a new object version, providing the generation/retention semantics of the
 * original generation-data-group definitions in {@code app/jcl/DEFGDGB.jcl}.</p>
 *
 * <p>Upstream components own the line-width formatting: the text variant is rendered and padded
 * to the LRECL&nbsp;=&nbsp;80 contract and the HTML variant to the LRECL&nbsp;=&nbsp;100
 * contract before reaching this writer. This writer is a faithful byte sink: it appends and
 * persists content verbatim using {@link StandardCharsets#ISO_8859_1} (one byte per character)
 * and performs no trimming, re-wrapping, re-padding, or re-encoding, guaranteeing byte-exact
 * persistence of the upstream rendering contract.</p>
 *
 * <p>Execution is single-threaded and sequential, matching the COBOL processing model. The
 * per-run buffers are created in {@link #open(ExecutionContext)} and released in
 * {@link #close()}; a variant whose buffer is empty is not written.</p>
 */
@Component
public class StatementWriter implements ItemStreamWriter<StatementWriter.StatementDocument> {

    /**
     * Structured logger for statement-writer lifecycle and S3 emission diagnostics. Logs only
     * counts, byte sizes, and destination identifiers (bucket/key) &mdash; never statement
     * content, customer data, PII, or secrets.
     */
    private static final Logger log = LoggerFactory.getLogger(StatementWriter.class);

    /** S3 object key for the plain-text statement variant (COBOL {@code STMTFILE}). */
    private static final String TEXT_OBJECT_KEY = "STATEMNT.PS";

    /** S3 object key for the HTML statement variant (COBOL {@code HTMLFILE}). */
    private static final String HTML_OBJECT_KEY = "STATEMNT.HTML";

    /** Synchronous S3 client used to persist the run-level statement objects. */
    private final S3Client s3Client;

    /** Meter registry used to increment the processed-records counter per statement. */
    private final MeterRegistry meterRegistry;

    /** Target S3 bucket for both statement variants. */
    private final String statementsBucket;

    /** Per-run accumulator for the plain-text statement bytes; created in {@link #open}. */
    private ByteArrayOutputStream textBuffer;

    /** Per-run accumulator for the HTML statement bytes; created in {@link #open}. */
    private ByteArrayOutputStream htmlBuffer;

    /**
     * Creates the writer with its collaborators and target bucket.
     *
     * @param s3Client         the synchronous S3 client (provided by {@code AwsConfig})
     * @param meterRegistry    the Micrometer registry for processed-record metrics
     * @param statementsBucket the destination bucket, from
     *                         {@code carddemo.aws.s3.bucket-statements}
     *                         (default {@code carddemo-statements})
     */
    public StatementWriter(S3Client s3Client,
                           MeterRegistry meterRegistry,
                           @Value("${carddemo.aws.s3.bucket-statements:carddemo-statements}") String statementsBucket) {
        this.s3Client = s3Client;
        this.meterRegistry = meterRegistry;
        this.statementsBucket = statementsBucket;
    }

    /**
     * Initializes the per-run text and HTML accumulators, mirroring the COBOL
     * {@code OPEN OUTPUT STMT-FILE HTML-FILE}.
     *
     * @param executionContext the Spring Batch execution context (not used)
     */
    @Override
    public void open(ExecutionContext executionContext) {
        this.textBuffer = new ByteArrayOutputStream();
        this.htmlBuffer = new ByteArrayOutputStream();
        log.debug("Opened statement writer; buffering text/HTML statements for bucket {}",
                statementsBucket);
    }

    /**
     * No-op: no incremental state is persisted between chunks; the run-level buffers are
     * flushed once in {@link #close()}.
     *
     * @param executionContext the Spring Batch execution context (not used)
     */
    @Override
    public void update(ExecutionContext executionContext) {
        // Intentionally empty: the buffers are flushed once in close().
    }

    /**
     * Appends each statement's text and HTML content verbatim to the per-run buffers and
     * increments the processed-records counter once per statement. A {@code null} field is
     * treated as an empty string; content is never altered (no trimming, re-wrapping,
     * re-padding, or re-encoding).
     *
     * @param chunk a chunk of statements to append
     */
    @Override
    public void write(Chunk<? extends StatementDocument> chunk) {
        for (StatementDocument item : chunk) {
            String text = (item.textContent() == null) ? "" : item.textContent();
            String html = (item.htmlContent() == null) ? "" : item.htmlContent();
            textBuffer.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
            htmlBuffer.writeBytes(html.getBytes(StandardCharsets.ISO_8859_1));
            MetricsConfig.recordsProcessed(meterRegistry).increment();
        }
        log.debug("Appended {} statement document(s) in this chunk", chunk.size());
    }

    /**
     * Flushes the accumulated content to S3, mirroring the COBOL
     * {@code CLOSE STMT-FILE HTML-FILE}. Each non-empty variant is written to a single
     * run-level object; an empty variant is skipped. The buffers are released afterwards.
     */
    @Override
    public void close() {
        try {
            if (textBuffer != null && textBuffer.size() > 0) {
                byte[] payload = textBuffer.toByteArray();
                putObject(TEXT_OBJECT_KEY, payload, "text/plain");
                log.info("Wrote text statement object ({} bytes) to s3://{}/{}",
                        payload.length, statementsBucket, TEXT_OBJECT_KEY);
            } else {
                log.debug("No text statement content buffered; skipping s3://{}/{}",
                        statementsBucket, TEXT_OBJECT_KEY);
            }
            if (htmlBuffer != null && htmlBuffer.size() > 0) {
                byte[] payload = htmlBuffer.toByteArray();
                putObject(HTML_OBJECT_KEY, payload, "text/html");
                log.info("Wrote HTML statement object ({} bytes) to s3://{}/{}",
                        payload.length, statementsBucket, HTML_OBJECT_KEY);
            } else {
                log.debug("No HTML statement content buffered; skipping s3://{}/{}",
                        statementsBucket, HTML_OBJECT_KEY);
            }
        } finally {
            textBuffer = null;
            htmlBuffer = null;
        }
    }

    /**
     * Persists a single payload to S3 under the configured statements bucket, translating any
     * {@link SdkException} into a {@link FileAccessException} (the Java equivalent of the COBOL
     * abend path on a failed {@code WRITE}).
     *
     * @param key         the S3 object key
     * @param payload     the object bytes
     * @param contentType the MIME content type to record on the object
     * @throws FileAccessException if the S3 put fails
     */
    private void putObject(String key, byte[] payload, String contentType) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(statementsBucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(payload));
        } catch (SdkException e) {
            log.error("Failed to write statement object to s3://{}/{} ({} bytes)",
                    statementsBucket, key, payload.length, e);
            throw new FileAccessException(
                    "Failed to write statement object to S3 " + statementsBucket + "/" + key, e);
        }
    }

    /**
     * Input contract for a single rendered statement.
     *
     * @param textContent the complete plain-text statement, already rendered and padded to the
     *                    LRECL&nbsp;=&nbsp;80 contract upstream; may be {@code null} (treated as
     *                    empty)
     * @param htmlContent the same statement rendered as HTML, already formatted to the
     *                    LRECL&nbsp;=&nbsp;100 contract upstream; may be {@code null} (treated as
     *                    empty)
     */
    public record StatementDocument(String textContent, String htmlContent) {
    }
}
