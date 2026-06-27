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
package com.carddemo.integration;

import com.carddemo.dto.ReportDto;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.TransactionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end Testcontainers integration test for the <strong>F-011 report
 * bridge consumer</strong> ({@link com.carddemo.service.ReportJobConsumer}), the
 * Java realization of the CICS internal-reader trigger of {@code CORPT00C}
 * ({@code SUBMIT-JOB-TO-INTRDR} &rarr; TDQ {@code JOBS}) at source commit
 * {@code 27d6c6f}.
 *
 * <h2>What this verifies (the gap the QA report found)</h2>
 * The publish half of the bridge (submit &rarr; one FIFO message) was already
 * green, but the <em>consume-then-process</em> half was missing: the published
 * message was never consumed, no batch job ran, and no S3 report object was
 * produced. This suite drives the <strong>complete</strong> round trip against
 * real infrastructure and asserts the previously-absent outcome:
 * <ol>
 *   <li>{@code POST /api/reports/submit} (a confirmed custom-window request)
 *       returns {@code 202 Accepted} and publishes to the FIFO queue;</li>
 *   <li>{@link com.carddemo.service.ReportJobConsumer} receives the message and
 *       launches {@code transactionReportJob} with the message window mapped onto
 *       the required {@code reportStartDate}/{@code reportEndDate} parameters;</li>
 *   <li>the batch writer uploads <strong>exactly one</strong> 133-byte
 *       fixed-width report object to the S3 output bucket; and</li>
 *   <li>the FIFO message is consumed (the queue drains to zero), refuting the QA
 *       observation that the message remained queued ({@code
 *       ApproximateNumberOfMessages=1}) after the wait.</li>
 * </ol>
 *
 * <h2>Why the consumer is explicitly enabled here</h2>
 * The {@code test} profile disables the consumer by default
 * ({@code carddemo.report.consumer.enabled=false} in {@code application-test.yml})
 * so the publish-contract suites ({@code SqsIntegrationIT},
 * {@code OnlineTransactionE2EIT}) can receive the published message themselves to
 * assert the SQS contract without a live {@code @SqsListener} draining it. This
 * IT re-enables the consumer via {@link TestPropertySource} (which yields a
 * distinct application context with the listener active) and is annotated
 * {@link DirtiesContext} {@code AFTER_CLASS} so the listener container is torn
 * down when the class finishes and never competes with the other suites that
 * share the singleton LocalStack queue.
 *
 * <h2>Why an in-window transaction is seeded</h2>
 * {@code transactionReportJob}'s writer uploads an object only when the step
 * processes at least one row (an empty report yields no trailer and no object).
 * This IT therefore seeds one posted transaction inside the requested window so
 * the end-to-end success path deterministically produces the S3 object. The
 * card, type, and category are chosen to resolve against the Flyway V3 seed so
 * the processor's enrichment lookups succeed.
 *
 * <p>Real PostgreSQL and real LocalStack S3/SQS are provided by
 * {@link AbstractIntegrationIT}; nothing is mocked and there is no live AWS
 * dependency (Rule&nbsp;2). The suite owns its data lifecycle explicitly because
 * Spring Batch commits in its own transactions (so it cannot be
 * {@code @Transactional}).</p>
 */
@DisplayName("ReportJobConsumer IT — F-011 end-to-end bridge (submit → SQS FIFO → consume → batch → 133-byte S3 object)")
@TestPropertySource(properties = "carddemo.report.consumer.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ReportJobConsumerIT extends AbstractIntegrationIT {

    /** The posted-transaction master table the report job reads from (seeds empty). */
    private static final String TRANSACTIONS_TABLE = "transactions";

    /** Fixed report record width — {@code TRANREPT} {@code LRECL=133}, COBOL {@code FD-REPTFILE-REC PIC X(133)}. */
    private static final int RECORD_WIDTH = 133;

    /** A seeded {@code card_xref} card number that resolves to account id {@code 2}. */
    private static final String CARD_A = "0923877193247330";

    /** Default seeded transaction type ({@code "01"} → {@code transaction_type} "Purchase"). */
    private static final TransactionTypeCode DEFAULT_TYPE = TransactionTypeCode.PURCHASE;

    /** Default seeded category code ({@code {01,1}} → {@code transaction_category} "Regular Sales Draft"). */
    private static final int DEFAULT_CATEGORY = 1;

    /** A processing date inside the submitted custom window {@code [2022-01-01, 2022-07-06]}. */
    private static final String IN_WINDOW_DATE = "2022-02-01";

    /** Upper bound on the asynchronous round-trip wait (SQS delivery + batch run + S3 upload). */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(45);

    /** Poll cadence while awaiting the asynchronous outcome. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Resets to a deterministic state before each test: clears the {@code transactions} master
     * (this IT cannot be {@code @Transactional}), idempotently provisions the canonical AWS
     * resources (the output bucket must exist for the writer), empties that bucket, and purges the
     * FIFO queue so the round-trip assertions are exact.
     */
    @BeforeEach
    void resetState() {
        deleteFrom(TRANSACTIONS_TABLE);
        provisionCanonicalAwsResources();
        emptyBucket(outputBucket());
        purgeQueue(reportQueueUrl());
    }

    /**
     * Returns the shared {@code transactions} table, S3 output bucket, and FIFO queue to a clean
     * state for the other suites that share the singleton containers.
     */
    @AfterEach
    void cleanState() {
        deleteFrom(TRANSACTIONS_TABLE);
        emptyBucket(outputBucket());
        purgeQueue(reportQueueUrl());
    }

    /**
     * <strong>CRITICAL (Issue&nbsp;1).</strong> A confirmed report submission publishes a message
     * to the FIFO queue, the {@code @SqsListener} consumer launches {@code transactionReportJob},
     * and the batch writer uploads exactly one 133-byte fixed-width report object to S3 — the full
     * F-011 bridge the QA report found broken. The FIFO message is consumed (queue drains to zero).
     */
    @Test
    @DisplayName("CRITICAL: confirmed submit → consumer runs transactionReportJob → one 133-byte report object in S3; queue drains")
    void submitTriggersConsumerWhichRunsBatchAndWritesReportObject() {
        // Seed one in-window posted transaction so the report is non-empty (the writer uploads no
        // object when the step processes zero rows).
        persistInWindowTransaction();

        // Submit a confirmed CUSTOM-window report covering the seeded transaction (2022-01-01..2022-07-06).
        HttpHeaders headers = userAuthHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ReportDto.SubmitRequest request = new ReportDto.SubmitRequest(
                null, null, "Y", "01", "01", "2022", "07", "06", "2022", "Y");

        ResponseEntity<Void> response = restTemplate.postForEntity(
                url("/api/reports/submit"), new HttpEntity<>(request, headers), Void.class);
        assertThat(response.getStatusCode().value())
                .as("CORPT00C submit is accepted for asynchronous processing (202 Accepted)")
                .isEqualTo(202);

        // The consumer must receive the message, launch the batch job, and the writer must upload
        // exactly one report object — the end-to-end half the QA report found missing.
        await("report object produced by the SQS-triggered batch job")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(listOutputObjectKeys())
                        .as("exactly one transaction-report object is uploaded to the output bucket")
                        .hasSize(1));

        // The single object honours the CVTRA07Y 133-byte fixed-width contract.
        List<String> lines = readSingleReportObjectLines();
        assertThat(lines).as("the report object is non-empty").isNotEmpty();
        assertThat(lines)
                .as("every CVTRA07Y report line is exactly %d characters wide (FD-REPTFILE-REC PIC X(133))",
                        RECORD_WIDTH)
                .allSatisfy(line -> assertThat(line.length()).isEqualTo(RECORD_WIDTH));

        // And the FIFO message must have been consumed — refuting the QA evidence that it remained
        // queued (ApproximateNumberOfMessages=1, NotVisible=0) after the wait.
        await("report message consumed and acknowledged from the FIFO queue")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(approximateQueueDepth())
                        .as("the FIFO report queue drains to zero once the consumer acknowledges the message")
                        .isZero());
    }

    // =====================================================================================
    // Helpers — seed, S3 read-back, queue depth.
    // =====================================================================================

    /**
     * Persists one in-window posted {@link Transaction} resolving against the Flyway seed so the
     * report processor's enrichment lookups succeed and the job produces a detail line.
     */
    private void persistInWindowTransaction() {
        Transaction transaction = new Transaction(
                String.format("%016d", 1L),
                DEFAULT_TYPE.getCode(),
                DEFAULT_CATEGORY,
                "POS",
                "INTEGRATION TEST TRANSACTION",
                new BigDecimal("100.00"),
                1L,
                "TEST MERCHANT",
                "TEST CITY",
                "00000",
                CARD_A,
                ts(IN_WINDOW_DATE),
                ts(IN_WINDOW_DATE));
        transactionRepository.saveAndFlush(transaction);
    }

    /**
     * Builds a 26-character {@code TRAN-PROC-TS}/{@code TRAN-ORIG-TS} timestamp at midnight for the
     * given date in the legacy {@code YYYY-MM-DD HH:MM:SS.mmmmmm} format; only the leading ten
     * characters participate in the report window filter.
     *
     * @param date the {@code YYYY-MM-DD} date portion
     * @return the 26-character timestamp text
     */
    private static String ts(String date) {
        return date + " 00:00:00.000000";
    }

    /**
     * Lists the object keys currently in the report output bucket.
     *
     * @return the output-bucket object keys (empty when none yet)
     */
    private List<String> listOutputObjectKeys() {
        try (S3Client s3 = newS3Client()) {
            List<String> keys = new ArrayList<>();
            for (S3Object object : s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(outputBucket()).build()).contents()) {
                keys.add(object.key());
            }
            return keys;
        }
    }

    /**
     * Reads the single report object the job uploads to the output bucket and returns its
     * fixed-width lines (decoded US-ASCII, split on the writer's {@code "\n"} delimiter, trailing
     * empty element dropped). Asserts exactly one object exists.
     *
     * @return the report's fixed-width lines, in file order
     */
    private List<String> readSingleReportObjectLines() {
        final String bucket = outputBucket();
        final byte[] payload;
        try (S3Client s3 = newS3Client()) {
            final List<S3Object> objects = s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).build()).contents();
            assertThat(objects)
                    .as("exactly one transaction-report object is written to the '%s' output bucket", bucket)
                    .hasSize(1);
            final String key = objects.get(0).key();
            payload = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        }
        final List<String> lines = new ArrayList<>();
        for (String line : new String(payload, StandardCharsets.US_ASCII).split("\n", -1)) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Returns the approximate total depth of the FIFO report queue (visible plus in-flight),
     * mirroring the {@code ApproximateNumberOfMessages} + {@code ApproximateNumberOfMessagesNotVisible}
     * the QA report inspected.
     *
     * @return the approximate number of messages still on (or in flight from) the queue
     */
    private long approximateQueueDepth() {
        try (SqsClient sqs = newSqsClient()) {
            Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(
                            GetQueueAttributesRequest.builder()
                                    .queueUrl(reportQueueUrl())
                                    .attributeNames(
                                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                                    .build())
                    .attributes();
            long visible = Long.parseLong(attributes.getOrDefault(
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
            long notVisible = Long.parseLong(attributes.getOrDefault(
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0"));
            return visible + notVisible;
        }
    }
}
