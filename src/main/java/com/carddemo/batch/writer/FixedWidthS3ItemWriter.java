/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.batch.writer;

import io.awspring.cloud.s3.S3Template;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;

/**
 * Reusable Spring Batch {@link ItemStreamWriter} that persists pre-formatted,
 * fixed-width text lines to a single versioned Amazon S3 object.
 *
 * <p>One generic writer serves the three fixed-width batch outputs of the
 * CardDemo migration (source commit {@code 27d6c6f}); the {@code batch/job}
 * package supplies the bucket name, the run-unique object key, and the record
 * width for each {@code @Bean @StepScope} instance:</p>
 * <ul>
 *   <li><b>Transaction detail report</b> — {@code 133}-byte records to the
 *       output bucket, realizing {@code CBTRN03C}'s
 *       {@code FD-REPTFILE-REC PIC X(133)} write paragraph
 *       {@code 1111-WRITE-REPORT-REC}; the {@code TRANREPT(+1)} GDG generation
 *       becomes a versioned object per run.</li>
 *   <li><b>Statement text</b> — {@code 80}-byte records to the statement
 *       bucket, realizing {@code CBSTM03A}'s {@code FD-STMTFILE-REC PIC X(80)}
 *       stream from paragraph {@code 5000-CREATE-STATEMENT}.</li>
 *   <li><b>Statement HTML</b> — {@code 100}-byte records to the statement
 *       bucket, realizing {@code CBSTM03A}'s {@code FD-HTMLFILE-REC PIC X(100)}
 *       stream.</li>
 * </ul>
 *
 * <p>Rendering is performed upstream; this writer is type-{@code String} and
 * rendering-agnostic. It enforces the exact record width (space-pad or
 * truncate, mirroring a COBOL {@code MOVE ... TO PIC X(n)}), encodes with a
 * single-byte {@link Charset} so the character count equals the byte count,
 * accumulates the chunked output, and uploads exactly one object when the
 * stream is closed. An S3 failure propagates as an {@link ItemStreamException},
 * mirroring the COBOL abend on a non-{@code '00'} file status.</p>
 */
public class FixedWidthS3ItemWriter implements ItemStreamWriter<String> {

    /** Single-byte charset used when none is supplied; guarantees char-count == byte-count. */
    private static final Charset DEFAULT_CHARSET = StandardCharsets.US_ASCII;

    /** Record separator used when none is supplied. */
    private static final String DEFAULT_LINE_DELIMITER = "\n";

    private final S3Template s3Template;

    private final String bucketName;

    private final Supplier<String> keySupplier;

    private final int recordWidth;

    private final Charset charset;

    private final String lineDelimiter;

    /** Accumulates the fixed-width payload for the current stream; (re)allocated in {@link #open}. */
    private StringBuilder buffer;

    /** Number of records appended to {@link #buffer} for the current stream. */
    private long lineCount;

    /** Object key resolved from {@link #keySupplier} when the stream is opened. */
    private String resolvedObjectKey;

    /**
     * Canonical constructor.
     *
     * @param s3Template the S3 client used to upload the materialized object; must not be {@code null}
     * @param bucketName the destination bucket (injected from {@code AwsResourceProperties};
     *     never a literal); must not be blank
     * @param keySupplier supplies the run-unique object key, resolved at {@link #open};
     *     must not be {@code null}
     * @param recordWidth the exact fixed record width in characters (e.g. 133, 80, 100); must be positive
     * @param charset the single-byte charset used to encode the payload; must not be {@code null}
     * @param lineDelimiter the record separator (use {@code ""} for pure {@code RECFM=FB}
     *     concatenation); must not be {@code null}
     */
    public FixedWidthS3ItemWriter(S3Template s3Template, String bucketName, Supplier<String> keySupplier,
            int recordWidth, Charset charset, String lineDelimiter) {
        this.s3Template = Objects.requireNonNull(s3Template, "s3Template must not be null");
        this.bucketName = requireNonBlank(bucketName, "bucketName");
        this.keySupplier = Objects.requireNonNull(keySupplier, "keySupplier must not be null");
        if (recordWidth <= 0) {
            throw new IllegalArgumentException("recordWidth must be positive but was " + recordWidth);
        }
        this.recordWidth = recordWidth;
        this.charset = Objects.requireNonNull(charset, "charset must not be null");
        this.lineDelimiter = Objects.requireNonNull(lineDelimiter, "lineDelimiter must not be null");
    }

    /**
     * Convenience constructor applying the default single-byte charset
     * ({@link StandardCharsets#US_ASCII}) and newline record delimiter.
     *
     * @param s3Template the S3 client used to upload the materialized object; must not be {@code null}
     * @param bucketName the destination bucket; must not be blank
     * @param keySupplier supplies the run-unique object key, resolved at {@link #open};
     *     must not be {@code null}
     * @param recordWidth the exact fixed record width in characters; must be positive
     */
    public FixedWidthS3ItemWriter(S3Template s3Template, String bucketName, Supplier<String> keySupplier,
            int recordWidth) {
        this(s3Template, bucketName, keySupplier, recordWidth, DEFAULT_CHARSET, DEFAULT_LINE_DELIMITER);
    }

    /**
     * Convenience constructor for a precomputed static object key, applying the
     * default single-byte charset ({@link StandardCharsets#US_ASCII}) and
     * newline record delimiter.
     *
     * @param s3Template the S3 client used to upload the materialized object; must not be {@code null}
     * @param bucketName the destination bucket; must not be blank
     * @param objectKey the run-unique object key; must not be blank
     * @param recordWidth the exact fixed record width in characters; must be positive
     */
    public FixedWidthS3ItemWriter(S3Template s3Template, String bucketName, String objectKey, int recordWidth) {
        this(s3Template, bucketName, toSupplier(objectKey), recordWidth, DEFAULT_CHARSET, DEFAULT_LINE_DELIMITER);
    }

    /**
     * Resets the accumulator and line counter and resolves the run-unique
     * object key from the supplier.
     *
     * @param executionContext the step execution context (unused; state is held in memory)
     * @throws ItemStreamException if the supplier yields a blank object key
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        this.buffer = new StringBuilder();
        this.lineCount = 0L;
        String key = keySupplier.get();
        if (key == null || key.isBlank()) {
            throw new ItemStreamException(
                    "Resolved S3 object key must not be blank for bucket '" + bucketName + "'");
        }
        this.resolvedObjectKey = key;
    }

    /**
     * No-op: the object is materialized atomically by {@link #close}, so there
     * is no incremental progress to persist into the execution context.
     *
     * @param executionContext the step execution context (unused)
     * @throws ItemStreamException never thrown by this implementation
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // Intentionally empty.
    }

    /**
     * Encodes the accumulated payload and uploads exactly one S3 object. When
     * no record was written the upload is skipped (no empty object is created).
     * The buffer is always released.
     *
     * @throws ItemStreamException if the upload to S3 fails
     */
    @Override
    public void close() throws ItemStreamException {
        try {
            if (lineCount == 0L) {
                return;
            }
            byte[] payload = buffer.toString().getBytes(charset);
            s3Template.upload(bucketName, resolvedObjectKey, new ByteArrayInputStream(payload));
        } catch (RuntimeException ex) {
            throw new ItemStreamException("Failed to upload fixed-width object to S3 bucket '"
                    + bucketName + "' at key '" + resolvedObjectKey + "'", ex);
        } finally {
            this.buffer = null;
        }
    }

    /**
     * Appends each item to the accumulator after coercing it to exactly
     * {@link #recordWidth} characters (right-pad with spaces or truncate),
     * followed by the configured record delimiter.
     *
     * @param chunk the chunk of pre-formatted lines to write; never {@code null}
     * @throws Exception if appending a record fails
     */
    @Override
    public void write(Chunk<? extends String> chunk) throws Exception {
        for (String line : chunk.getItems()) {
            buffer.append(padRightTruncate(line, recordWidth)).append(lineDelimiter);
            lineCount++;
        }
    }

    /**
     * Coerces a value to exactly {@code width} characters: a {@code null}
     * becomes empty, an over-length value is truncated, and a shorter value is
     * left-justified and right-padded with spaces — the byte-for-byte behavior
     * of a COBOL {@code MOVE ... TO PIC X(width)}.
     *
     * @param value the value to coerce; may be {@code null}
     * @param width the exact target width; positive
     * @return a string of exactly {@code width} characters
     */
    private static String padRightTruncate(String value, int width) {
        String text = (value == null) ? "" : value;
        int length = text.length();
        if (length == width) {
            return text;
        }
        if (length > width) {
            return text.substring(0, width);
        }
        StringBuilder padded = new StringBuilder(width);
        padded.append(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Validates that a value is neither {@code null} nor blank.
     *
     * @param value the value to validate
     * @param name the parameter name used in the failure message
     * @return the validated value
     */
    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Wraps a validated static object key in a supplier.
     *
     * @param objectKey the object key; must not be blank
     * @return a supplier that always returns {@code objectKey}
     */
    private static Supplier<String> toSupplier(String objectKey) {
        String key = requireNonBlank(objectKey, "objectKey");
        return () -> key;
    }
}
