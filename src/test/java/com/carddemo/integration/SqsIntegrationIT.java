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
package com.carddemo.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.carddemo.dto.ReportDto;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.ReportService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LocalStack integration test for the <strong>F-011 report-submission bridge</strong>
 * (Validation Gate&nbsp;5 &mdash; real interface contract, exercised locally, no live AWS).
 *
 * <p>In the legacy program {@code app/cbl/CORPT00C.cbl} (CICS transaction {@code CR00}), a
 * confirmed report submission writes a JCL stream to the CICS extra-partition Transient Data
 * Queue {@code JOBS} ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}, paragraph
 * {@code WIRTE-JOBSUB-TDQ}, lines&nbsp;517-523 at SHA {@code 27d6c6f}) so that an internal
 * reader triggers the transaction-report batch job. The Java target replaces this mainframe
 * mechanism with {@link ReportService#submitReport(ReportDto.SubmitRequest)}, which publishes
 * exactly one typed request message to the SQS FIFO queue
 * {@code carddemo-report-jobs.fifo}, preserving the observable asynchronous
 * submit-then-process semantics.</p>
 *
 * <p>This suite verifies that bridge end-to-end against a <em>real</em> SQS service supplied by
 * the shared Testcontainers LocalStack wired in {@link AbstractIntegrationIT}: it drives the
 * production {@link ReportService} bean (so the actual publish path is exercised) and reads the
 * queue back with the AWS SDK&nbsp;v2 {@link SqsClient} created by the base class. The queue
 * name is never hardcoded &mdash; it is resolved from the config-bound
 * {@code AwsConfig.AwsResourceProperties} through {@link #reportQueueName()} /
 * {@link #reportQueueUrl()}.</p>
 *
 * <p><strong>Isolation.</strong> Each test idempotently (re)provisions the canonical AWS
 * resources and then drains the FIFO queue with a receive-and-delete loop both before and after
 * it runs, so received-message assertions are deterministic and the tests are independently
 * re-runnable. Deleting received messages (rather than relying on {@code PurgeQueue}, which has
 * a 60-second cooldown) both empties the queue and releases the FIFO message-group lock
 * immediately.</p>
 */
@DisplayName("SQS FIFO report-submission bridge IT — CORPT00C TDQ → carddemo-report-jobs.fifo (F-011, Gate 5)")
public class SqsIntegrationIT extends AbstractIntegrationIT {

    /**
     * The FIFO ordering group id the production publish path stamps on every report message
     * (mirrors {@code ReportService.REPORT_JOBS_MESSAGE_GROUP_ID}); asserted, never used as a
     * source of truth for sending through the service.
     */
    private static final String EXPECTED_MESSAGE_GROUP_ID = "report-jobs";

    /** The report name the service resolves for a monthly submission. */
    private static final String MONTHLY_REPORT_NAME = "Monthly";

    /** The byte-exact "no report type" message preserved from {@code CORPT00C} (line 438). */
    private static final String SELECT_REPORT_TYPE_MESSAGE = "Select a report type to print report...";

    /** Maximum messages requested per receive call (SQS hard limit is 10). */
    private static final int MAX_RECEIVE_BATCH = 10;

    /** Short long-poll wait per receive; LocalStack is local so a brief poll avoids flakiness. */
    private static final int RECEIVE_WAIT_SECONDS = 1;

    /** Consecutive empty receives that confirm the queue is fully drained. */
    private static final int EMPTY_POLLS_TO_STOP = 2;

    /** Upper bound on receive iterations so a drain can never loop unbounded. */
    private static final int MAX_DRAIN_POLLS = 12;

    /** The production report-submission service; drives the real SQS publish path. */
    @Autowired
    private ReportService reportService;

    /**
     * Ensures the canonical FIFO queue exists (idempotent) and starts every test from an empty
     * queue so message-count assertions are exact.
     */
    @BeforeEach
    void provisionAndDrain() {
        provisionCanonicalAwsResources();
        drainReportQueue();
    }

    /**
     * Leaves the FIFO queue empty for the next test; the shared containers are never torn down.
     */
    @AfterEach
    void drainAfter() {
        drainReportQueue();
    }

    @Test
    @DisplayName("canonical report queue exists and is a FIFO queue (init-aws.sh provisioning parity)")
    void reportQueueExistsAndIsFifo() {
        assertThat(reportQueueName()).endsWith(".fifo");

        String queueUrl = reportQueueUrl();
        assertThat(queueUrl).endsWith(reportQueueName());

        try (SqsClient sqs = newSqsClient()) {
            String fifoAttribute = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(queueUrl)
                            .attributeNames(QueueAttributeName.FIFO_QUEUE)
                            .build())
                    .attributes()
                    .get(QueueAttributeName.FIFO_QUEUE);
            assertThat(fifoAttribute).isEqualTo("true");
        }
    }

    @Test
    @DisplayName("submitReport publishes exactly one FIFO message whose payload reflects the report criteria (F-011)")
    void submitReportPublishesOneMessageToFifoQueue() {
        ReportDto.SubmitRequest request = monthlyConfirmedRequest();

        ReportService.SubmitResult result = reportService.submitReport(request);
        assertThat(result.submitted()).isTrue();
        assertThat(result.reportName()).isEqualTo(MONTHLY_REPORT_NAME);

        List<Message> messages = receiveAll();
        assertThat(messages).hasSize(1);

        String body = messages.get(0).body();
        assertThat(body)
                .contains(result.reportName())
                .contains(result.startDate())
                .contains(result.endDate());
    }

    @Test
    @DisplayName("published message carries MessageGroupId 'report-jobs' and a deduplication id (FIFO ordering)")
    void publishedMessageHasReportJobsMessageGroupId() {
        reportService.submitReport(monthlyConfirmedRequest());

        List<Message> messages = receiveAll();
        assertThat(messages).hasSize(1);

        Map<MessageSystemAttributeName, String> systemAttributes = messages.get(0).attributes();
        assertThat(systemAttributes)
                .containsEntry(MessageSystemAttributeName.MESSAGE_GROUP_ID, EXPECTED_MESSAGE_GROUP_ID);
        assertThat(systemAttributes.get(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID))
                .isNotBlank();
    }

    @Test
    @DisplayName("no report type selected → ValidationException (byte-exact CORPT00C text) and nothing is enqueued")
    void noReportTypeSelectedThrowsValidationAndPublishesNothing() {
        // confirm = "Y" but every report-type flag blank: the EVALUATE WHEN OTHER branch rejects
        // before the confirmation gate or any publish can run.
        ReportDto.SubmitRequest request = new ReportDto.SubmitRequest(
                null, null, null, null, null, null, null, null, null, "Y");

        assertThatThrownBy(() -> reportService.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(SELECT_REPORT_TYPE_MESSAGE);

        assertThat(receiveAll()).isEmpty();
    }

    @Test
    @DisplayName("raw FIFO send/receive round-trips exact payloads in order (Gate 5 real contract)")
    void rawFifoSendReceiveRoundTripPreservesPayloadAndOrdering() {
        String queueUrl = reportQueueUrl();
        String messageGroupId = "raw-roundtrip-" + UUID.randomUUID();
        String firstPayload = "raw-fifo-payload-1-" + UUID.randomUUID();
        String secondPayload = "raw-fifo-payload-2-" + UUID.randomUUID();

        try (SqsClient sqs = newSqsClient()) {
            sqs.sendMessage(fifoSend(queueUrl, firstPayload, messageGroupId, UUID.randomUUID().toString()));
            sqs.sendMessage(fifoSend(queueUrl, secondPayload, messageGroupId, UUID.randomUUID().toString()));
        }

        List<Message> messages = receiveAll();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).body()).isEqualTo(firstPayload);
        assertThat(messages.get(1).body()).isEqualTo(secondPayload);
        assertThat(messages).allSatisfy(message ->
                assertThat(message.attributes())
                        .containsEntry(MessageSystemAttributeName.MESSAGE_GROUP_ID, messageGroupId));
    }

    @Test
    @DisplayName("identical MessageDeduplicationId is de-duplicated within the FIFO window (one message survives)")
    void duplicateDeduplicationIdIsDeduplicated() {
        String queueUrl = reportQueueUrl();
        String messageGroupId = "dedup-group-" + UUID.randomUUID();
        String deduplicationId = UUID.randomUUID().toString();
        String payload = "dedup-payload-" + UUID.randomUUID();

        try (SqsClient sqs = newSqsClient()) {
            sqs.sendMessage(fifoSend(queueUrl, payload, messageGroupId, deduplicationId));
            // Same deduplication id within the 5-minute window: the second send is discarded.
            sqs.sendMessage(fifoSend(queueUrl, payload, messageGroupId, deduplicationId));
        }

        List<Message> messages = receiveAll();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).body()).isEqualTo(payload);
    }

    /**
     * Builds a valid monthly, confirmed submission: only the {@code monthly} flag is set and
     * {@code confirm} is {@code "Y"}, so the service computes the current-month range and
     * publishes a single message.
     *
     * @return a monthly, confirmed {@link ReportDto.SubmitRequest}
     */
    private static ReportDto.SubmitRequest monthlyConfirmedRequest() {
        return new ReportDto.SubmitRequest(
                "Y", null, null, null, null, null, null, null, null, "Y");
    }

    /**
     * Builds a FIFO {@link SendMessageRequest} for the raw-contract tests.
     *
     * @param queueUrl        the absolute FIFO queue URL
     * @param body            the message body
     * @param messageGroupId  the FIFO ordering group id
     * @param deduplicationId the FIFO deduplication id
     * @return the assembled send request
     */
    private static SendMessageRequest fifoSend(String queueUrl, String body,
                                               String messageGroupId, String deduplicationId) {
        return SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .messageGroupId(messageGroupId)
                .messageDeduplicationId(deduplicationId)
                .build();
    }

    /**
     * Receives and deletes every visible message on the canonical report FIFO queue, returning
     * them in receive order (which, for a single message group, equals FIFO send order).
     *
     * <p>The loop polls with a short long-poll wait and stops once it has seen
     * {@link #EMPTY_POLLS_TO_STOP} consecutive empty receives (or hits {@link #MAX_DRAIN_POLLS}),
     * so it both drains the queue deterministically and tolerates the brief delay before a
     * just-published message becomes visible. Deleting each received message releases the FIFO
     * group lock so subsequent messages in the same group can be received.</p>
     *
     * @return the drained messages, in receive order; never {@code null}
     */
    private List<Message> receiveAll() {
        String queueUrl = reportQueueUrl();
        List<Message> collected = new ArrayList<>();
        try (SqsClient sqs = newSqsClient()) {
            int emptyPolls = 0;
            for (int poll = 0; poll < MAX_DRAIN_POLLS && emptyPolls < EMPTY_POLLS_TO_STOP; poll++) {
                ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(MAX_RECEIVE_BATCH)
                        .waitTimeSeconds(RECEIVE_WAIT_SECONDS)
                        .messageSystemAttributeNames(
                                MessageSystemAttributeName.MESSAGE_GROUP_ID,
                                MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID)
                        .build());

                List<Message> batch = response.messages();
                if (batch.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (Message message : batch) {
                    collected.add(message);
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                }
            }
        }
        return collected;
    }

    /** Drains and discards every message on the report queue (test isolation helper). */
    private void drainReportQueue() {
        receiveAll();
    }
}
