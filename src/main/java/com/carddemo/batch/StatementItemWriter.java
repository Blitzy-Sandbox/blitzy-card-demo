package com.carddemo.batch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.carddemo.exception.FileProcessingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.awspring.cloud.s3.S3Template;

/**
 * Streaming {@link ItemStreamWriter} for the CardDemo statement-generation stage — writer
 * <strong>#2 of 3</strong> in the batch pipeline, wired into {@code StatementJob}.
 *
 * <p><strong>COBOL lineage (reference-only, source SHA {@code 27d6c6f}).</strong> This writer is the
 * Java realization of the dual-output file emission performed by the legacy batch program
 * {@code CBSTM03A.CBL}, launched by {@code CREASTMT.JCL} step {@code STEP040}. {@code CBSTM03A}
 * iterates the card cross-reference file and, for every card, appends one fully-assembled statement
 * to <em>two</em> parallel sequential output files declared in its {@code FILE-CONTROL}:</p>
 * <pre>
 *   SELECT STMT-FILE ASSIGN TO STMTFILE.        FD STMT-FILE.  01 FD-STMTFILE-REC PIC X(80).
 *   SELECT HTML-FILE ASSIGN TO HTMLFILE.        FD HTML-FILE.  01 FD-HTMLFILE-REC PIC X(100).
 * </pre>
 * <p>The COBOL program {@code OPEN OUTPUT STMT-FILE HTML-FILE} once, issues
 * {@code WRITE FD-STMTFILE-REC FROM ST-LINEn} / {@code WRITE FD-HTMLFILE-REC FROM HTML-...} per card
 * (concatenating every card's statement into the two aggregate files), then
 * {@code CLOSE STMT-FILE HTML-FILE} once. This {@code ItemStream} writer mirrors that lifecycle
 * exactly:</p>
 * <ul>
 *   <li>{@link #open(ExecutionContext)} &harr; COBOL {@code OPEN OUTPUT} — initializes the two
 *       in-memory record buffers.</li>
 *   <li>{@link #write(Chunk)} &harr; COBOL {@code WRITE ... FROM} — appends each
 *       {@link StatementDocument}'s already-rendered {@linkplain StatementDocument#textLines() text}
 *       and {@linkplain StatementDocument#htmlLines() HTML} lines to the buffers, in card order.</li>
 *   <li>{@link #close()} &harr; COBOL {@code CLOSE} — ships both aggregate buffers to S3 once, at the
 *       end of the step.</li>
 * </ul>
 *
 * <p><strong>Fixed record widths (byte-parity, AAP Gate&nbsp;1/5).</strong> {@code CREASTMT.JCL}
 * allocates {@code STMTFILE} with {@code DCB=(LRECL=80,...,RECFM=FB)} and {@code HTMLFILE} with
 * {@code DCB=(LRECL=100,...,RECFM=FB)}. To reproduce those fixed-block datasets byte-for-byte, every
 * text record is padded or truncated to exactly {@value #TEXT_RECORD_LENGTH} characters and every
 * HTML record to exactly {@value #HTML_RECORD_LENGTH} characters by {@link #padOrTrim(String, int)}.
 * Because the migrated statement content is single-byte ASCII (the migration is driven by the ASCII
 * fixtures under {@code app/data/ASCII}, AAP &sect;0.2.1), the buffers are serialized with
 * {@link StandardCharsets#US_ASCII US-ASCII} so that one character equals exactly one byte and the
 * {@code LRECL} byte contract holds. Records are separated by a single {@code '\n'} (LF, chosen for
 * reproducible output independent of the host platform's line separator).</p>
 *
 * <p><strong>GDG generations &rarr; versioned S3 objects (AAP &sect;0.7.7, &sect;0.8.5).</strong> On
 * {@link #close()} both buffers are uploaded to the {@code carddemo-batch-statements} bucket at the
 * stable keys {@code statements.ps} (text) and {@code statements.html} (HTML). S3 bucket versioning
 * supplies the generation-data-group semantics: writing a new version supersedes the prior one,
 * reproducing {@code CREASTMT.JCL} {@code STEP030}'s {@code IEFBR14 DISP=(MOD,DELETE,DELETE)}
 * "delete the previous {@code .PS}/{@code .HTML} then recreate" behaviour without an explicit delete.
 * The {@link S3Template} is the auto-configured Spring Cloud AWS framework bean, injected directly:
 * this writer deliberately does <em>not</em> depend on {@code config/AwsConfig} (which declares no
 * beans anyway), avoiding a {@code batch &rarr; config} dependency cycle. All AWS traffic is directed
 * at LocalStack via {@code spring.cloud.aws.*} properties; there is zero live-AWS dependency.</p>
 *
 * <p><strong>I/O only — no business logic (AAP &sect;0.4.3).</strong> The statement <em>content</em>
 * (both the text and HTML rendering, including every monetary value already formatted from a
 * {@link java.math.BigDecimal} of scale&nbsp;2) is produced upstream by {@code StatementProcessor}
 * via the injected {@code StatementFileService}. This writer performs no repository or service access
 * and no arithmetic; it never introduces {@code float}/{@code double}. Its sole responsibility is to
 * format the pre-rendered lines to fixed width and ship them to S3.</p>
 *
 * <p><strong>Ordering and thread-safety.</strong> The two buffers accumulate lines in the exact order
 * items are received, which is the card order established by {@code StatementCardXrefItemReader}; this
 * preserves the sequential emission order of {@code CBSTM03A}. The writer holds mutable per-step state
 * and is therefore <em>not</em> thread-safe: it is intended for a single-threaded, ordered step (as
 * the legacy program is inherently sequential). The recommended {@code @StepScope} guarantees a fresh
 * instance — and therefore fresh buffers — per step execution, so no state leaks across runs.</p>
 *
 * <p><strong>Faults.</strong> Any S3 or I/O failure during upload is wrapped in a
 * {@link FileProcessingException} (an unchecked, fatal batch failure that maps the legacy abend
 * paragraphs) and logged via SLF4J; the JVM is never terminated with {@code System.exit}. End-of-file
 * is not applicable to a writer.</p>
 *
 * @see StatementDocument
 * @see FileProcessingException
 */
@Component
@StepScope
public class StatementItemWriter implements ItemStreamWriter<StatementDocument>, StepExecutionListener {

    /**
     * Fixed record length of the plain-text statement file, in bytes/characters.
     *
     * <p>Mirrors COBOL {@code 01 FD-STMTFILE-REC PIC X(80)} and {@code CREASTMT.JCL} {@code STMTFILE
     * DD DCB=(LRECL=80,...,RECFM=FB)}.</p>
     */
    static final int TEXT_RECORD_LENGTH = 80;

    /**
     * Fixed record length of the HTML statement file, in bytes/characters.
     *
     * <p>Mirrors COBOL {@code 01 FD-HTMLFILE-REC PIC X(100)} and {@code CREASTMT.JCL} {@code HTMLFILE
     * DD DCB=(LRECL=100,...,RECFM=FB)}.</p>
     */
    static final int HTML_RECORD_LENGTH = 100;

    /**
     * Record delimiter written between fixed-width records when the aggregate buffers are serialized
     * for upload. A single line feed ({@code U+000A}) is used unconditionally so the generated object
     * bytes are reproducible and independent of the host platform's line separator.
     */
    private static final char RECORD_DELIMITER = '\n';

    /** SLF4J logger; structured JSON with the MDC {@code correlationId} is applied by logback. */
    private static final Logger log = LoggerFactory.getLogger(StatementItemWriter.class);

    /** Auto-configured Spring Cloud AWS S3 operations facade (LocalStack-backed). */
    private final S3Template s3Template;

    /** Destination bucket for both generated statement objects (GDG &rarr; versioned S3). */
    private final String statementsBucket;

    /** Stable object key for the plain-text ({@code LRECL=80}) statement file. */
    private final String textObjectKey;

    /** Stable object key for the HTML ({@code LRECL=100}) statement file. */
    private final String htmlObjectKey;

    /**
     * Aggregate buffer of fixed-width text records, one element per {@code LRECL=80} line, in card
     * order. Field-initialized (never {@code null}); reset by {@link #open(ExecutionContext)}.
     */
    private final List<String> textBuffer = new ArrayList<>();

    /**
     * Aggregate buffer of fixed-width HTML records, one element per {@code LRECL=100} line, in card
     * order. Field-initialized (never {@code null}); reset by {@link #open(ExecutionContext)}.
     */
    private final List<String> htmlBuffer = new ArrayList<>();

    /**
     * Idempotency guard for {@link #close()}: once both objects have been uploaded this is set so a
     * repeated {@code close()} is a safe no-op and cannot re-upload (empty) content.
     */
    private boolean closed;

    /**
     * Creates the writer with the S3 operations facade and the externalized destination coordinates.
     *
     * <p>Constructor injection only. The bucket and object keys are resolved from the
     * {@code carddemo.aws.s3.*} / {@code carddemo.batch.statement.*} properties with defaults that
     * match the AAP (&sect;0.7.7): bucket {@code carddemo-batch-statements}, text key
     * {@code statements.ps}, HTML key {@code statements.html}. Supplying them via {@link Value}
     * (rather than injecting {@code AwsConfig}) keeps the coordinates configurable per Spring profile
     * while avoiding a {@code batch &rarr; config} dependency cycle.</p>
     *
     * @param s3Template       the auto-configured Spring Cloud AWS {@link S3Template}; must not be
     *                         {@code null}
     * @param statementsBucket the destination S3 bucket for the generated statement objects; must not
     *                         be blank
     * @param textObjectKey    the S3 object key for the plain-text statement file; must not be blank
     * @param htmlObjectKey    the S3 object key for the HTML statement file; must not be blank
     */
    public StatementItemWriter(
            S3Template s3Template,
            @Value("${carddemo.aws.s3.statements-bucket:carddemo-batch-statements}") String statementsBucket,
            @Value("${carddemo.batch.statement.text-object-key:statements.ps}") String textObjectKey,
            @Value("${carddemo.batch.statement.html-object-key:statements.html}") String htmlObjectKey) {
        this.s3Template = Objects.requireNonNull(s3Template, "s3Template must not be null");
        this.statementsBucket = requireText(statementsBucket, "statementsBucket");
        this.textObjectKey = requireText(textObjectKey, "textObjectKey");
        this.htmlObjectKey = requireText(htmlObjectKey, "htmlObjectKey");
    }

    /**
     * Initializes the two aggregate buffers at the start of the step, mirroring COBOL
     * {@code OPEN OUTPUT STMT-FILE HTML-FILE}.
     *
     * <p>Both buffers are cleared and the {@link #closed} guard is reset so the instance is ready to
     * accumulate a fresh run's statements. The operation is idempotent and null-safe: re-invocation
     * simply yields empty buffers again. The supplied {@link ExecutionContext} is intentionally not
     * read — this writer keeps no restartable per-chunk cursor (see {@link #update(ExecutionContext)}).
     *
     * @param executionContext the step execution context (unused; the writer holds no restart state)
     */
    @Override
    public void open(ExecutionContext executionContext) {
        textBuffer.clear();
        htmlBuffer.clear();
        closed = false;
        log.debug("Opened statement writer; buffering to s3://{}/{} (text) and s3://{}/{} (html)",
                statementsBucket, textObjectKey, statementsBucket, htmlObjectKey);
    }

    /**
     * Appends every statement in the chunk to the aggregate buffers, mirroring the per-card
     * {@code WRITE FD-STMTFILE-REC FROM ...} / {@code WRITE FD-HTMLFILE-REC FROM ...} of
     * {@code CBSTM03A}.
     *
     * <p>For each {@link StatementDocument} the pre-rendered {@linkplain StatementDocument#textLines()
     * text lines} are padded/truncated to {@value #TEXT_RECORD_LENGTH} characters and appended to the
     * text buffer, and the {@linkplain StatementDocument#htmlLines() HTML lines} to
     * {@value #HTML_RECORD_LENGTH} characters and appended to the HTML buffer. Card order (and,
     * within a card, line order) is preserved. No content is uploaded here; the aggregate files are
     * shipped once at {@link #close()}. This method performs no I/O and therefore throws no checked
     * exception.</p>
     *
     * @param chunk the chunk of assembled statements to append; a {@code null} chunk is treated as an
     *              empty write. The Spring Batch contract never supplies {@code null} elements.
     */
    @Override
    public void write(Chunk<? extends StatementDocument> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        for (StatementDocument document : chunk) {
            if (document == null) {
                continue;
            }
            for (String textLine : document.textLines()) {
                textBuffer.add(padOrTrim(textLine, TEXT_RECORD_LENGTH));
            }
            for (String htmlLine : document.htmlLines()) {
                htmlBuffer.add(padOrTrim(htmlLine, HTML_RECORD_LENGTH));
            }
        }
    }

    /**
     * No-op stream checkpoint.
     *
     * <p>This writer intentionally persists no restartable state: both output files are aggregated in
     * memory and uploaded once at {@link #close()}, so there is no meaningful per-chunk cursor to
     * store. This matches the JCL rerun semantics of {@code CREASTMT.JCL}, where a rerun deletes the
     * prior {@code .PS}/{@code .HTML} outputs ({@code STEP030}) and regenerates them wholesale
     * ({@code STEP040}); the S3 upload is likewise all-or-nothing and versioned. The
     * {@link ExecutionContext} is therefore left untouched.</p>
     *
     * @param executionContext the step execution context (intentionally not modified)
     */
    @Override
    public void update(ExecutionContext executionContext) {
        // Intentionally empty: no restartable cursor is maintained (upload-once-at-step-end).
    }

    /**
     * Serializes both aggregate buffers and <strong>uploads them to S3</strong>, mirroring COBOL
     * {@code CLOSE STMT-FILE HTML-FILE}. Performed here — not in {@link #close()} — so that an upload
     * failure fails the step (QA finding <strong>F4</strong>, decision {@code D-027}).
     *
     * <p><strong>Why the upload lives here.</strong> {@code AbstractStep} persists the step and job
     * batch/exit status <em>after</em> {@code afterStep()} returns but <em>before</em> {@code close()}
     * runs, and it swallows any exception thrown from either callback. Uploading in {@code close()}
     * therefore could not influence the recorded outcome: a failed statement upload was previously only
     * logged as "Exception while closing step execution resources" while the step/job was already
     * recorded {@code COMPLETED} — the statements were silently lost. Uploading here and marking the
     * step {@code FAILED} on failure makes that failure durable.</p>
     *
     * <p>On the normal path both objects are always written — even when a buffer is empty (zero
     * statements) — so the step deterministically (re)creates both datasets, exactly as
     * {@code CBSTM03A} always produces both files. If the step already failed during item processing
     * (this callback also runs on the failure path), the upload is skipped and the {@code FAILED}
     * status is preserved. The {@link #closed} guard keeps the finalization single-shot.</p>
     *
     * @param stepExecution the completed (or failing) step execution; never {@code null}
     * @return {@code null} on success, or {@link ExitStatus#FAILED} when a statement upload fails
     */
    @Override
    public ExitStatus afterStep(final StepExecution stepExecution) {
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            log.warn("Statement step status is {} at afterStep; skipping statement upload",
                    stepExecution.getStatus());
            return null;
        }
        if (closed) {
            return null;
        }
        int textRecords = textBuffer.size();
        int htmlRecords = htmlBuffer.size();
        byte[] textBytes = serialize(textBuffer);
        byte[] htmlBytes = serialize(htmlBuffer);
        // Mark finalized BEFORE the uploads so this cannot re-enter and duplicate a successful upload.
        closed = true;
        try {
            upload(textObjectKey, textBytes, "text");
            upload(htmlObjectKey, htmlBytes, "html");
        } catch (final RuntimeException e) {
            log.error("Statement upload failed; failing the step", e);
            stepExecution.addFailureException(e);
            stepExecution.setStatus(BatchStatus.FAILED);
            return ExitStatus.FAILED;
        }
        log.info("Uploaded statements to s3://{}: {} ({} records, {} bytes), {} ({} records, {} bytes)",
                statementsBucket,
                textObjectKey, textRecords, textBytes.length,
                htmlObjectKey, htmlRecords, htmlBytes.length);
        textBuffer.clear();
        htmlBuffer.clear();
        return null;
    }

    /**
     * Releases the aggregate buffers. <strong>Cleanup only</strong> (QA finding <strong>F4</strong>,
     * decision {@code D-027}): the serialize + S3 upload were moved to
     * {@link #afterStep(StepExecution)} because {@code AbstractStep} swallows any exception thrown from
     * {@code close()} and has already persisted the step/job status by the time {@code close()} runs, so
     * an upload here could never fail the step. This method performs no upload; it simply frees the
     * buffers (they are already empty on the success path, and this releases them on a failed step).
     */
    @Override
    public void close() {
        textBuffer.clear();
        htmlBuffer.clear();
    }

    /**
     * Serializes an aggregate buffer of fixed-width records into the bytes of a fixed-block dataset.
     *
     * <p>Each record is emitted verbatim (it has already been normalized to its fixed width by
     * {@link #padOrTrim(String, int)}) followed by a single {@link #RECORD_DELIMITER}. The result is
     * encoded as {@link StandardCharsets#US_ASCII US-ASCII} so that, for the single-byte statement
     * content, each record occupies exactly its {@code LRECL} in bytes.</p>
     *
     * @param records the fixed-width records to serialize, in order; never {@code null}
     * @return the encoded object bytes; empty when {@code records} is empty
     */
    private static byte[] serialize(List<String> records) {
        StringBuilder sb = new StringBuilder(records.size() * (TEXT_RECORD_LENGTH + 1));
        for (String record : records) {
            sb.append(record).append(RECORD_DELIMITER);
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Uploads one statement object to the configured bucket via {@link S3Template}.
     *
     * <p>The bytes are streamed from a {@link ByteArrayInputStream}; any S3/SDK
     * {@link RuntimeException} or {@link IOException} is wrapped in a fatal
     * {@link FileProcessingException} so the batch step fails cleanly with structured context. The
     * JVM is never terminated.</p>
     *
     * @param objectKey   the destination object key
     * @param content     the object bytes to upload
     * @param formatLabel a short label ({@code "text"}/{@code "html"}) used in log and error messages
     * @throws FileProcessingException if the upload fails
     */
    private void upload(String objectKey, byte[] content, String formatLabel) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            s3Template.upload(statementsBucket, objectKey, in);
        } catch (RuntimeException | IOException e) {
            throw new FileProcessingException(
                    "Failed to upload " + formatLabel + " statement object to s3://"
                            + statementsBucket + "/" + objectKey, e);
        }
    }

    /**
     * Pads with trailing spaces, or truncates, {@code value} to exactly {@code width} characters,
     * reproducing the fixed-width record behaviour of a COBOL {@code PIC X(width)} field.
     *
     * <p>A {@code null} value is treated as an all-spaces record. Values shorter than {@code width}
     * are right-padded with ASCII spaces (COBOL left-justifies alphanumeric moves into a
     * {@code PIC X} field); values longer than {@code width} are truncated to the first {@code width}
     * characters (COBOL truncates on the right). The returned string always has length exactly
     * {@code width}.</p>
     *
     * @param value the source line; may be {@code null}
     * @param width the target fixed record width; must be non-negative
     * @return a string of length exactly {@code width}
     */
    private static String padOrTrim(String value, int width) {
        String source = (value == null) ? "" : value;
        int length = source.length();
        if (length == width) {
            return source;
        }
        if (length > width) {
            return source.substring(0, width);
        }
        return source + " ".repeat(width - length);
    }

    /**
     * Validates that a configured coordinate is present and non-blank.
     *
     * @param value the value to validate
     * @param name  the property name, used in the failure message
     * @return {@code value} unchanged when valid
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        return value;
    }

    /**
     * Returns an unmodifiable snapshot of the accumulated fixed-width text records, in card order.
     *
     * <p>Package-private inspection accessor supporting unit tests that assert every buffered text
     * record is exactly {@value #TEXT_RECORD_LENGTH} characters wide and correctly ordered. It is not
     * part of the writer's public contract.</p>
     *
     * @return an unmodifiable view of the current text buffer; never {@code null}
     */
    List<String> getTextBuffer() {
        return Collections.unmodifiableList(textBuffer);
    }

    /**
     * Returns an unmodifiable snapshot of the accumulated fixed-width HTML records, in card order.
     *
     * <p>Package-private inspection accessor supporting unit tests that assert every buffered HTML
     * record is exactly {@value #HTML_RECORD_LENGTH} characters wide and correctly ordered. It is not
     * part of the writer's public contract.</p>
     *
     * @return an unmodifiable view of the current HTML buffer; never {@code null}
     */
    List<String> getHtmlBuffer() {
        return Collections.unmodifiableList(htmlBuffer);
    }
}
