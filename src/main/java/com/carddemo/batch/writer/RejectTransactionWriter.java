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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.carddemo.config.AwsConfig;
import com.carddemo.enums.RejectReasonCode;

import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.Counter;

/**
 * Spring Batch rejects writer for the daily-transaction posting step (CBTRN02C):
 * assembles the byte-exact 430-byte reject record per rejected transaction and
 * uploads the run's rejects as a single versioned object to the batch output bucket.
 */
@Component
@StepScope
public class RejectTransactionWriter implements ItemStreamWriter<RejectTransactionWriter.RejectedTransaction> {

    /**
     * A rejected daily transaction: its exact 350-byte CVTRA06Y record image and the validation-failure reason.
     */
    public record RejectedTransaction(String originalRecordImage, RejectReasonCode reason) {
    }

    private static final Logger log = LoggerFactory.getLogger(RejectTransactionWriter.class);

    /** REJECT-TRAN-DATA width: CVTRA06Y DALYTRAN-RECORD, PIC X(350). */
    private static final int TRAN_DATA_WIDTH = 350;

    /** WS-VALIDATION-FAIL-REASON width: PIC 9(04), zero-padded numeric reason code. */
    private static final int REASON_CODE_WIDTH = 4;

    /** WS-VALIDATION-FAIL-REASON-DESC width: PIC X(76), left-justified, space-padded. */
    private static final int REASON_DESC_WIDTH = 76;

    /** VALIDATION-TRAILER width: PIC X(80) = REASON_CODE_WIDTH + REASON_DESC_WIDTH. */
    private static final int TRAILER_WIDTH = REASON_CODE_WIDTH + REASON_DESC_WIDTH;

    /** REJECT-RECORD width: PIC X(430) = TRAN_DATA_WIDTH + TRAILER_WIDTH (F-018, immutable). */
    private static final int RECORD_WIDTH = TRAN_DATA_WIDTH + TRAILER_WIDTH;

    /** Single space, used for COBOL alphanumeric MOVE space-fill. */
    private static final char PAD_CHAR = ' ';

    /**
     * Record framing: newline-delimited fixed-width records, matching the AWS CardDemo
     * ASCII baseline (app/data/ASCII/*: LF-delimited, no CR). Switch here if the baseline changes.
     */
    private static final String LINE_DELIMITER = "\n";

    /** Object-key prefix mapping the DALYREJS GDG dataset to the output bucket. */
    private static final String KEY_PREFIX = "rejects/dalyrejs/";

    private static final DateTimeFormatter KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final S3Template s3Template;
    private final AwsConfig.AwsResourceProperties awsResourceProperties;
    private final Map<RejectReasonCode, Counter> rejectedCounters;
    private final Long jobExecutionId;

    private ByteArrayOutputStream buffer;
    private String objectKey;
    private long recordCount;

    /**
     * Constructs the writer with its auto-configured collaborators. The reject-reason
     * counters are injected as the single {@code Map<RejectReasonCode, Counter>} bean
     * registered by {@code MetricsConfig} (metric {@code carddemo.batch.records.rejected},
     * one tagged counter per reason).
     *
     * @param s3Template            Spring Cloud AWS S3 template (LocalStack-backed in local/test)
     * @param awsResourceProperties typed AWS resource names; supplies the output bucket
     * @param rejectedCounters      per-reason rejected-record counters from {@code MetricsConfig}
     * @param jobExecutionId        step-scoped job execution id used to disambiguate the run key
     */
    public RejectTransactionWriter(S3Template s3Template,
                                   AwsConfig.AwsResourceProperties awsResourceProperties,
                                   Map<RejectReasonCode, Counter> rejectedCounters,
                                   @Value("#{stepExecution.jobExecution.id}") Long jobExecutionId) {
        this.s3Template = s3Template;
        this.awsResourceProperties = awsResourceProperties;
        this.rejectedCounters = rejectedCounters;
        this.jobExecutionId = jobExecutionId;
    }

    /**
     * Initializes fresh per-run accumulation state and computes the versioned object key.
     * The key combines a millisecond UTC timestamp with the job execution id so each run
     * produces a new object that never overwrites a prior generation (GDG {@code (+1)}).
     *
     * @param executionContext the step execution context (unused; state is in-memory)
     * @throws ItemStreamException never thrown here; declared by the contract
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        this.buffer = new ByteArrayOutputStream();
        this.recordCount = 0L;
        String runDiscriminator = (jobExecutionId != null) ? String.format("%010d", jobExecutionId) : "na";
        this.objectKey = KEY_PREFIX + KEY_TIMESTAMP.format(Instant.now()) + "-" + runDiscriminator;
    }

    /**
     * No-op: all output is flushed as a single object in {@link #close()}.
     *
     * @param executionContext the step execution context
     * @throws ItemStreamException never thrown here; declared by the contract
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // Intentionally empty: the run's rejects are uploaded once, at close().
    }

    /**
     * Uploads the accumulated reject records as one object to the output bucket and releases
     * the buffer. Upload is skipped when the run produced no rejects (no GDG generation would
     * be created). Any S3 failure is rethrown as {@link ItemStreamException} so the step fails,
     * preserving the COBOL ABEND-on-write-error behavior of {@code 2500-WRITE-REJECT-REC}.
     *
     * @throws ItemStreamException if the S3 upload fails
     */
    @Override
    public void close() throws ItemStreamException {
        if (buffer == null) {
            return;
        }
        try {
            if (recordCount == 0L) {
                log.debug("No rejected transactions this run; skipping rejects upload for key {}", objectKey);
                return;
            }
            byte[] payload = buffer.toByteArray();
            String bucket = awsResourceProperties.getS3().getOutputBucket();
            try {
                s3Template.upload(bucket, objectKey, new ByteArrayInputStream(payload));
            } catch (RuntimeException e) {
                throw new ItemStreamException(
                        "Failed to upload rejected-transactions object to S3 (bucket=" + bucket
                                + ", key=" + objectKey + ")", e);
            }
            log.info("Uploaded {} rejected transaction(s) ({} bytes) to s3://{}/{}",
                    recordCount, payload.length, bucket, objectKey);
        } finally {
            this.buffer = null;
        }
    }

    /**
     * Builds the byte-exact 430-byte reject record for each item and appends it to the run
     * buffer, incrementing the per-reason rejected-record counter. Mirrors COBOL
     * {@code 2500-WRITE-REJECT-REC} (MOVE the 350-byte image, MOVE the 80-byte trailer, WRITE)
     * and the {@code ADD 1 TO WS-REJECT-COUNT} performed once per reject.
     *
     * @param chunk the chunk of rejected transactions to write
     * @throws Exception if a record cannot be assembled to the exact 430-byte width
     */
    @Override
    public void write(Chunk<? extends RejectedTransaction> chunk) throws Exception {
        for (RejectedTransaction item : chunk.getItems()) {
            String image350 = padRightTruncate(item.originalRecordImage(), TRAN_DATA_WIDTH);
            String code4 = padRightTruncate(item.reason().getFormattedCode(), REASON_CODE_WIDTH);
            String desc76 = padRightTruncate(item.reason().getDescription(), REASON_DESC_WIDTH);

            String trailer80 = code4 + desc76;
            if (trailer80.length() != TRAILER_WIDTH) {
                throw new IllegalStateException(
                        "Reject trailer width violation: expected " + TRAILER_WIDTH
                                + " but was " + trailer80.length());
            }

            String record430 = image350 + trailer80;
            if (record430.length() != RECORD_WIDTH) {
                throw new IllegalStateException(
                        "Reject record width violation: expected " + RECORD_WIDTH
                                + " but was " + record430.length());
            }

            buffer.writeBytes(record430.getBytes(StandardCharsets.US_ASCII));
            buffer.writeBytes(LINE_DELIMITER.getBytes(StandardCharsets.US_ASCII));
            recordCount++;

            Counter counter = rejectedCounters.get(item.reason());
            if (counter != null) {
                counter.increment();
            }
        }
    }

    /**
     * Renders a value to an exact fixed width, mirroring a COBOL alphanumeric {@code MOVE}
     * to {@code PIC X(width)}: left-justified, right space-padded, and right-truncated. A
     * {@code null} value is treated as an empty string.
     *
     * @param value the source value (may be {@code null})
     * @param width the exact target width
     * @return a string of exactly {@code width} characters
     */
    private static String padRightTruncate(String value, int width) {
        String safe = (value == null) ? "" : value;
        if (safe.length() >= width) {
            return safe.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(safe);
        while (sb.length() < width) {
            sb.append(PAD_CHAR);
        }
        return sb.toString();
    }
}
