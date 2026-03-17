package com.cardemo.batch.writers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Spring Batch {@link ItemWriter} that uploads dual-format customer statements
 * to AWS S3 in both plain text and HTML formats.
 *
 * <p>Replaces the output portion of COBOL program {@code CBSTM03A.CBL}, specifically
 * the sequential file write operations:
 * <ul>
 *   <li>{@code WRITE FD-STMTFILE-REC} — text format, RECFM=FB, LRECL=80
 *       (paragraphs 5000-CREATE-STATEMENT, 6000-WRITE-TRANS)</li>
 *   <li>{@code WRITE FD-HTMLFILE-REC} — HTML format, RECFM=FB, LRECL=100
 *       (paragraphs 5100-WRITE-HTML-HEADER, 5200-WRITE-HTML-NMADBS, 6000-WRITE-TRANS)</li>
 * </ul>
 *
 * <p>The upstream {@code StatementProcessor} is responsible for generating the
 * pre-formatted text and HTML content (mirroring CBSTM03A statement line construction).
 * This writer is responsible solely for uploading that content to S3, replacing the
 * COBOL sequential output files defined in {@code CREASTMT.JCL} DD statements:
 * <ul>
 *   <li>{@code STMTFILE DD DSN=AWS.M2.CARDDEMO.STATEMNT.PS} → S3 {@code text/} prefix</li>
 *   <li>{@code HTMLFILE DD DSN=AWS.M2.CARDDEMO.STATEMNT.HTML} → S3 {@code html/} prefix</li>
 * </ul>
 *
 * <p>S3 Object Key Structure:
 * <ul>
 *   <li>Text: {@code {textPrefix}{cardNumber}-{yyyyMMddHHmmss}.txt}</li>
 *   <li>HTML: {@code {htmlPrefix}{cardNumber}-{yyyyMMddHHmmss}.html}</li>
 * </ul>
 *
 * <p>All S3 interactions are testable against LocalStack with zero live AWS dependencies
 * per AAP §0.7.7 (LocalStack Verification).
 *
 * @see StatementOutput
 */
@Component
public class StatementWriter implements ItemWriter<StatementWriter.StatementOutput> {

    private static final Logger log = LoggerFactory.getLogger(StatementWriter.class);

    /**
     * DateTimeFormatter for generating unique S3 object key timestamps.
     * Format: {@code yyyyMMddHHmmss} — replaces COBOL {@code FUNCTION CURRENT-DATE}
     * used in CBSTM03A for GDG dataset naming conventions.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** MIME content type for plain text statement uploads. */
    private static final String CONTENT_TYPE_TEXT = "text/plain";

    /** MIME content type for HTML statement uploads. */
    private static final String CONTENT_TYPE_HTML = "text/html";

    private final S3Client s3Client;
    private final String statementBucket;
    private final String textPrefix;
    private final String htmlPrefix;

    /**
     * Counter tracking the number of statements written during this batch run.
     * Used for batch summary reporting at the end of a job execution.
     * Reset via {@link #resetStatementCount()} before each new batch run.
     */
    private long statementCount;

    /**
     * Constructs a {@code StatementWriter} with the required S3 client and
     * configurable S3 bucket/prefix settings.
     *
     * <p>All configuration values are externalized via {@code application.yml}
     * Spring property injection, ensuring zero hardcoded AWS resource names
     * per AAP §0.8.1. Profile-specific configuration enables transparent switching
     * between LocalStack (local/test) and real AWS (production) environments.
     *
     * @param s3Client        AWS S3 client bean (provided by AwsConfig)
     * @param statementBucket S3 bucket name for statement storage
     *                        (default: {@code carddemo-statements})
     * @param textPrefix      S3 key prefix for text-format statements
     *                        (default: {@code text/})
     * @param htmlPrefix      S3 key prefix for HTML-format statements
     *                        (default: {@code html/})
     */
    public StatementWriter(
            S3Client s3Client,
            @Value("${carddemo.s3.statement-bucket:carddemo-statements}") String statementBucket,
            @Value("${carddemo.s3.statement-text-prefix:text/}") String textPrefix,
            @Value("${carddemo.s3.statement-html-prefix:html/}") String htmlPrefix) {
        this.s3Client = s3Client;
        this.statementBucket = statementBucket;
        this.textPrefix = textPrefix;
        this.htmlPrefix = htmlPrefix;
        this.statementCount = 0;
    }

    /**
     * Writes a chunk of statement outputs to S3 in both text and HTML formats.
     *
     * <p>For each {@link StatementOutput} in the chunk:
     * <ol>
     *   <li>Uploads the text statement to S3 at
     *       {@code {textPrefix}{cardNumber}-{timestamp}.txt}</li>
     *   <li>Uploads the HTML statement to S3 at
     *       {@code {htmlPrefix}{cardNumber}-{timestamp}.html}</li>
     *   <li>Increments the statement count for batch summary reporting</li>
     * </ol>
     *
     * <p>Both uploads must succeed for each statement. If the text upload succeeds
     * but the HTML upload fails, the error propagates to Spring Batch for
     * retry/skip handling. This mirrors the COBOL ABEND pattern in paragraph
     * {@code 9999-ABEND-PROGRAM} where a file write failure causes program
     * termination via {@code CALL 'CEE3ABD'}.
     *
     * @param chunk the chunk of pre-formatted statement outputs to write
     * @throws Exception if any S3 upload operation fails, propagated to Spring Batch
     *                   for step-level error handling (retry, skip, or fail)
     */
    @Override
    public void write(Chunk<? extends StatementOutput> chunk) throws Exception {
        if (chunk.size() == 0) {
            log.warn("Received empty chunk — no statements to write");
            return;
        }

        for (StatementOutput statement : chunk.getItems()) {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String cardNumber = statement.cardNumber();

            /* Upload text statement to S3 — replaces WRITE FD-STMTFILE-REC (LRECL=80) */
            String textKey = generateS3Key(textPrefix, cardNumber, "txt", timestamp);
            uploadToS3(textKey, statement.textContent(), CONTENT_TYPE_TEXT, cardNumber);

            /* Upload HTML statement to S3 — replaces WRITE FD-HTMLFILE-REC (LRECL=100) */
            String htmlKey = generateS3Key(htmlPrefix, cardNumber, "html", timestamp);
            uploadToS3(htmlKey, statement.htmlContent(), CONTENT_TYPE_HTML, cardNumber);

            statementCount++;
            log.info("Wrote statement for card {} to S3 (text + HTML), total statements: {}",
                    cardNumber, statementCount);
        }

        log.info("Processed chunk of {} statement(s), cumulative total: {}",
                chunk.size(), statementCount);
    }

    /**
     * Uploads content to S3 with the specified key and content type.
     *
     * <p>Uses UTF-8 encoding for all uploads, replacing the mainframe EBCDIC
     * character set used in COBOL {@code STMT-FILE} (PIC X(80)) and
     * {@code HTML-FILE} (PIC X(100)) outputs.
     *
     * <p>On failure, logs the error at ERROR level and re-throws the exception
     * to allow Spring Batch to handle it at the step level (retry/skip/fail).
     *
     * @param key         the S3 object key
     * @param content     the statement content to upload
     * @param contentType the MIME content type ({@code text/plain} or {@code text/html})
     * @param cardNumber  the card number (for error logging context)
     * @throws RuntimeException if the S3 putObject operation fails
     */
    private void uploadToS3(String key, String content, String contentType, String cardNumber) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(statementBucket)
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(request, RequestBody.fromString(content, StandardCharsets.UTF_8));
            log.info("Uploaded S3 object: s3://{}/{}", statementBucket, key);
        } catch (RuntimeException e) {
            log.error("Failed to upload statement for card {} to S3 key {}: {}",
                    cardNumber, key, e.getMessage());
            throw new RuntimeException(
                    "S3 upload failed for card " + cardNumber + " at key " + key, e);
        }
    }

    /**
     * Generates a unique S3 object key for a statement file.
     *
     * <p>Key format: {@code {prefix}{cardNumber}-{yyyyMMddHHmmss}.{extension}}
     *
     * <p>Replaces the COBOL PS dataset naming convention
     * ({@code AWS.M2.CARDDEMO.STATEMNT.PS} / {@code AWS.M2.CARDDEMO.STATEMNT.HTML})
     * with S3 key-based organization that supports per-card, per-run uniqueness.
     *
     * @param prefix     the S3 key prefix (e.g., {@code "text/"} or {@code "html/"})
     * @param cardNumber the 16-digit card number identifying the statement owner
     * @param extension  the file extension ({@code "txt"} or {@code "html"})
     * @param timestamp  the pre-formatted timestamp string ({@code yyyyMMddHHmmss})
     * @return the complete S3 object key
     */
    private String generateS3Key(String prefix, String cardNumber,
                                 String extension, String timestamp) {
        return prefix + cardNumber + "-" + timestamp + "." + extension;
    }

    /**
     * Returns the total number of statements written during the current batch run.
     * Used for batch job summary reporting, monitoring, and observability metrics.
     *
     * @return the count of statements successfully written to S3
     */
    public long getStatementCount() {
        return statementCount;
    }

    /**
     * Resets the statement count to zero for a new batch run.
     * Should be called at the beginning of each new batch execution
     * (e.g., in a {@code StepExecutionListener.beforeStep()}) to ensure
     * accurate per-run statistics.
     */
    public void resetStatementCount() {
        this.statementCount = 0;
        log.info("Statement count reset to 0");
    }

    /**
     * Immutable data transfer record encapsulating a single customer statement
     * in both text and HTML formats, ready for S3 upload.
     *
     * <p>Produced by the upstream {@code StatementProcessor} and consumed by
     * {@link StatementWriter} for dual-format S3 upload. The processor is
     * responsible for all statement formatting logic (mapping from COBOL
     * CBSTM03A paragraphs 5000-CREATE-STATEMENT, 5100-WRITE-HTML-HEADER,
     * 5200-WRITE-HTML-NMADBS, 6000-WRITE-TRANS, and 4000-TRNXFILE-GET).
     *
     * <p>The text content preserves the COBOL 80-character line format from
     * {@code FD STMT-FILE} (RECFM=FB, LRECL=80), with lines delimited by
     * newline characters. The HTML content is a complete HTML document replacing
     * {@code FD HTML-FILE} (RECFM=FB, LRECL=100) — the 100-byte LRECL was a
     * mainframe line buffer constraint that does not apply in the S3 context.
     *
     * @param cardNumber  the 16-digit card number identifying the statement owner;
     *                    must not be null or blank
     * @param textContent the pre-formatted plain text statement with 80-char lines
     *                    delimited by newlines; must not be null
     * @param htmlContent the pre-formatted complete HTML statement document;
     *                    must not be null
     */
    public record StatementOutput(String cardNumber, String textContent, String htmlContent) {

        /**
         * Compact constructor with input validation.
         * Ensures all required fields are present and valid before
         * the record instance is created.
         *
         * @throws IllegalArgumentException if any required field is null or
         *                                  if cardNumber is blank
         */
        public StatementOutput {
            if (cardNumber == null || cardNumber.isBlank()) {
                throw new IllegalArgumentException("Card number must not be null or blank");
            }
            if (textContent == null) {
                throw new IllegalArgumentException("Text content must not be null");
            }
            if (htmlContent == null) {
                throw new IllegalArgumentException("HTML content must not be null");
            }
        }
    }
}
