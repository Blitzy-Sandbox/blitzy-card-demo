/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.batch.writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.carddemo.batch.writer.RejectTransactionWriter;
import com.carddemo.batch.writer.RejectTransactionWriter.RejectedTransaction;
import com.carddemo.config.AwsConfig;
import com.carddemo.enums.RejectReasonCode;

import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Byte-exactness unit tests for {@link RejectTransactionWriter}. They assert the
 * immutable 430-byte reject-record contract (F-018): 350-byte transaction image,
 * 4-byte zero-padded reason code, 76-byte left-justified space-padded description,
 * newline framing, the per-reason metric increment, and the never-overwrite key.
 */
@DisplayName("RejectTransactionWriter — 430-byte reject-record contract (F-018)")
class RejectTransactionWriterTest {

    private static final String BUCKET = "test-output-bucket";
    private static final int RECORD_WIDTH = 430;
    private static final int FRAMED_WIDTH = RECORD_WIDTH + 1; // 430 + LF

    private AwsConfig.AwsResourceProperties properties;
    private Map<RejectReasonCode, Counter> counters;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new AwsConfig.AwsResourceProperties();
        properties.getS3().setOutputBucket(BUCKET);

        // Mirror MetricsConfig: one counter per reason, the NONE sentinel excluded.
        registry = new SimpleMeterRegistry();
        counters = new EnumMap<>(RejectReasonCode.class);
        for (RejectReasonCode reason : RejectReasonCode.values()) {
            if (reason == RejectReasonCode.NONE) {
                continue;
            }
            counters.put(reason, Counter.builder("carddemo.batch.records.rejected")
                    .tag("reason", String.valueOf(reason.getCode()))
                    .register(registry));
        }
    }

    private RejectTransactionWriter newWriter(S3Template s3Template, Long jobExecutionId) {
        return new RejectTransactionWriter(s3Template, properties, counters, jobExecutionId);
    }

    private static byte[] runAndCaptureBytes(RejectTransactionWriter writer,
                                             S3Template s3Template,
                                             List<RejectedTransaction> items) throws Exception {
        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(items));
        writer.close();
        ArgumentCaptor<InputStream> streamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(s3Template).upload(eq(BUCKET), anyString(), streamCaptor.capture());
        try {
            return streamCaptor.getValue().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String spacePad(String value, int width) {
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("known 350-byte image + OVER_CREDIT_LIMIT (102) → exact 430-byte record, code at 351-354, desc at 355-430")
    void producesExact430ByteRecord() throws Exception {
        S3Template s3Template = mock(S3Template.class);
        String image = "X".repeat(350);

        byte[] bytes = runAndCaptureBytes(newWriter(s3Template, 123L),
                s3Template,
                List.of(new RejectedTransaction(image, RejectReasonCode.OVER_CREDIT_LIMIT)));

        assertThat(bytes).hasSize(FRAMED_WIDTH);
        String record = new String(bytes, 0, RECORD_WIDTH, StandardCharsets.US_ASCII);
        assertThat(record.substring(0, 350)).isEqualTo(image);
        assertThat(record.substring(350, 354)).isEqualTo("0102");
        assertThat(record.substring(354, 430)).isEqualTo(spacePad("OVERLIMIT TRANSACTION", 76));
        assertThat(bytes[RECORD_WIDTH]).isEqualTo((byte) '\n');

        assertThat(counters.get(RejectReasonCode.OVER_CREDIT_LIMIT).count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("short image + NONE (0) → image space-padded to 350, code 0000, 76-space description, counter untouched")
    void padsShortImageAndHandlesNoneReason() throws Exception {
        S3Template s3Template = mock(S3Template.class);

        byte[] bytes = runAndCaptureBytes(newWriter(s3Template, 1L),
                s3Template,
                List.of(new RejectedTransaction("ABC", RejectReasonCode.NONE)));

        assertThat(bytes).hasSize(FRAMED_WIDTH);
        String record = new String(bytes, 0, RECORD_WIDTH, StandardCharsets.US_ASCII);
        assertThat(record.substring(0, 3)).isEqualTo("ABC");
        assertThat(record.substring(3, 350)).isEqualTo(" ".repeat(347));
        assertThat(record.substring(350, 354)).isEqualTo("0000");
        assertThat(record.substring(354, 430)).isEqualTo(" ".repeat(76));
    }

    @Test
    @DisplayName("over-length image (400) truncated to exactly 350 bytes")
    void truncatesOverLengthImage() throws Exception {
        S3Template s3Template = mock(S3Template.class);
        String image = "Y".repeat(400);

        byte[] bytes = runAndCaptureBytes(newWriter(s3Template, 7L),
                s3Template,
                List.of(new RejectedTransaction(image, RejectReasonCode.CARD_NOT_FOUND)));

        assertThat(bytes).hasSize(FRAMED_WIDTH);
        String record = new String(bytes, 0, RECORD_WIDTH, StandardCharsets.US_ASCII);
        assertThat(record.substring(0, 350)).isEqualTo("Y".repeat(350));
        assertThat(record.substring(350, 354)).isEqualTo("0100");
        assertThat(record.substring(354, 430)).isEqualTo(spacePad("INVALID CARD NUMBER FOUND", 76));
    }

    @Test
    @DisplayName("null image treated as empty → 350 spaces; trailer still exact")
    void nullImageTreatedAsEmpty() throws Exception {
        S3Template s3Template = mock(S3Template.class);

        byte[] bytes = runAndCaptureBytes(newWriter(s3Template, 9L),
                s3Template,
                List.of(new RejectedTransaction(null, RejectReasonCode.ACCOUNT_EXPIRED)));

        String record = new String(bytes, 0, RECORD_WIDTH, StandardCharsets.US_ASCII);
        assertThat(record.substring(0, 350)).isEqualTo(" ".repeat(350));
        assertThat(record.substring(350, 354)).isEqualTo("0103");
        assertThat(record.substring(354, 430))
                .isEqualTo(spacePad("TRANSACTION RECEIVED AFTER ACCT EXPIRATION", 76));
    }

    @Test
    @DisplayName("distinct codes 101 and 109 sharing description text are never collapsed")
    void distinctCodesWithSharedDescriptionArePreserved() throws Exception {
        S3Template s3Template = mock(S3Template.class);
        String image = "Z".repeat(350);

        byte[] bytes = runAndCaptureBytes(newWriter(s3Template, 11L),
                s3Template,
                List.of(new RejectedTransaction(image, RejectReasonCode.ACCOUNT_NOT_FOUND),
                        new RejectedTransaction(image, RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE)));

        assertThat(bytes).hasSize(2 * FRAMED_WIDTH);
        String first = new String(bytes, 0, RECORD_WIDTH, StandardCharsets.US_ASCII);
        String second = new String(bytes, FRAMED_WIDTH, RECORD_WIDTH, StandardCharsets.US_ASCII);
        assertThat(first.substring(350, 354)).isEqualTo("0101");
        assertThat(second.substring(350, 354)).isEqualTo("0109");
        assertThat(first.substring(354, 430)).isEqualTo(spacePad("ACCOUNT RECORD NOT FOUND", 76));
        assertThat(second.substring(354, 430)).isEqualTo(spacePad("ACCOUNT RECORD NOT FOUND", 76));
        assertThat(bytes[RECORD_WIDTH]).isEqualTo((byte) '\n');
        assertThat(bytes[2 * FRAMED_WIDTH - 1]).isEqualTo((byte) '\n');
    }

    @Test
    @DisplayName("multiple rejects accumulate as a single object; counters increment once per reject")
    void multipleRejectsAccumulateAndCount() throws Exception {
        S3Template s3Template = mock(S3Template.class);
        String image = "A".repeat(350);

        byte[] bytes = runAndCaptureBytes(newWriter(s3Template, 5L),
                s3Template,
                List.of(new RejectedTransaction(image, RejectReasonCode.OVER_CREDIT_LIMIT),
                        new RejectedTransaction(image, RejectReasonCode.OVER_CREDIT_LIMIT),
                        new RejectedTransaction(image, RejectReasonCode.CARD_NOT_FOUND)));

        assertThat(bytes).hasSize(3 * FRAMED_WIDTH);
        assertThat(counters.get(RejectReasonCode.OVER_CREDIT_LIMIT).count()).isEqualTo(2.0d);
        assertThat(counters.get(RejectReasonCode.CARD_NOT_FOUND).count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("zero rejects → no S3 upload (no empty GDG generation)")
    void skipsUploadWhenNoRejects() throws Exception {
        S3Template s3Template = mock(S3Template.class);
        RejectTransactionWriter writer = newWriter(s3Template, 42L);

        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(List.of()));
        writer.close();

        verify(s3Template, never()).upload(anyString(), anyString(), any(InputStream.class));
    }

    @Test
    @DisplayName("distinct runs produce distinct object keys (GDG +1 never overwrites)")
    void distinctRunsProduceDistinctKeys() throws Exception {
        String image = "B".repeat(350);

        S3Template first = mock(S3Template.class);
        RejectTransactionWriter writer1 = newWriter(first, 1L);
        writer1.open(new ExecutionContext());
        writer1.write(new Chunk<>(List.of(new RejectedTransaction(image, RejectReasonCode.CARD_NOT_FOUND))));
        writer1.close();

        S3Template second = mock(S3Template.class);
        RejectTransactionWriter writer2 = newWriter(second, 2L);
        writer2.open(new ExecutionContext());
        writer2.write(new Chunk<>(List.of(new RejectedTransaction(image, RejectReasonCode.CARD_NOT_FOUND))));
        writer2.close();

        ArgumentCaptor<String> key1 = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key2 = ArgumentCaptor.forClass(String.class);
        verify(first).upload(eq(BUCKET), key1.capture(), any(InputStream.class));
        verify(second).upload(eq(BUCKET), key2.capture(), any(InputStream.class));

        assertThat(key1.getValue()).startsWith("rejects/dalyrejs/");
        assertThat(key2.getValue()).startsWith("rejects/dalyrejs/");
        assertThat(key1.getValue()).isNotEqualTo(key2.getValue());
    }
}
