package com.carddemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import com.carddemo.model.dto.ReportRequest;
import com.carddemo.model.dto.ReportResponse;
import com.carddemo.service.report.ReportSubmissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.awspring.cloud.sqs.operations.SqsTemplate;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Integration test for the report-submission SQS FIFO bridge against LocalStack.
 * Exercises com.carddemo.service.report.ReportSubmissionService, the Java re-implementation of the
 * CICS TDQ WRITEQ report submission in app/cbl/CORPT00C.cbl (commit 27d6c6f, REFERENCE ONLY):
 * a report request becomes one FIFO publish carrying the byte-stable {reportType,startDate,endDate}
 * message contract (Gate 5 SQS message-schema verification).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsIntegrationIT extends AbstractAwsLocalStackIT {

    private static final String SCHEMA_QUEUE = "carddemo-report-jobs-it.fifo";
    private static final String MESSAGE_GROUP_ID = "carddemo-reports";

    @Autowired
    private ReportSubmissionService reportSubmissionService;

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Autowired
    private ObjectMapper objectMapper;

    private String schemaQueueUrl;

    @BeforeAll
    void createSchemaQueue() {
        Map<QueueAttributeName, String> attributes = new HashMap<>();
        attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        attributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true");
        schemaQueueUrl = await(sqsAsyncClient.createQueue(CreateQueueRequest.builder()
                .queueName(SCHEMA_QUEUE)
                .attributes(attributes)
                .build())).queueUrl();
    }

    @AfterAll
    void deleteSchemaQueue() {
        if (schemaQueueUrl != null) {
            await(sqsAsyncClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(schemaQueueUrl).build()));
        }
    }

    @Test
    void monthlyReportSubmissionPublishesToFifoQueue() throws Exception {
        // Prove the service's TDQ-replacement path lands a message on the REAL configured FIFO
        // queue (carddemo-report-jobs.fifo), not merely that submitReport returns a DTO.
        String reportQueueUrl = reportQueueUrl();
        drain(reportQueueUrl);

        LocalDate today = LocalDate.now();
        String expectedStartDate = today.withDayOfMonth(1).toString();
        String expectedEndDate = today.with(TemporalAdjusters.lastDayOfMonth()).toString();

        Map<String, Object> fields = new HashMap<>();
        fields.put("monthly", "Y");
        fields.put("confirm", "Y");
        ReportRequest request = objectMapper.convertValue(fields, ReportRequest.class);

        ReportResponse response = reportSubmissionService.submitReport(request);

        assertThat(response).isNotNull();
        assertThat(response.errorMessage()).isNull();
        assertThat(response.confirmationMessage()).isNotBlank();
        assertThat(response.confirmationMessage()).contains("Monthly");

        Message received = receiveOne(reportQueueUrl);
        assertThat(received)
                .as("ReportSubmissionService must publish to the configured report FIFO queue %s",
                        REPORT_QUEUE)
                .isNotNull();

        JsonNode payload = objectMapper.readTree(received.body());
        assertThat(payload.path("reportType").asText()).isEqualTo("Monthly");
        assertThat(payload.path("startDate").asText()).isEqualTo(expectedStartDate);
        assertThat(payload.path("endDate").asText()).isEqualTo(expectedEndDate);
        assertThat(received.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID))
                .as("report messages must use the constant FIFO message group")
                .isEqualTo(MESSAGE_GROUP_ID);

        // Exactly one message must have been published for one submission.
        deleteMessage(reportQueueUrl, received);
        assertThat(receive(reportQueueUrl, 1))
                .as("a single report submission must publish exactly one message")
                .isNull();
    }

    @Test
    void reportJobMessageHonoursByteStableSchema() throws Exception {
        ReportSubmissionService.ReportJobMessage message =
                new ReportSubmissionService.ReportJobMessage("Monthly", "2022-07-01", "2022-07-31");

        sqsTemplate.send(options -> options
                .queue(SCHEMA_QUEUE)
                .payload(message)
                .messageGroupId(MESSAGE_GROUP_ID)
                .messageDeduplicationId(UUID.randomUUID().toString()));

        Message received = receiveOne(schemaQueueUrl);
        assertThat(received).isNotNull();

        JsonNode payload = objectMapper.readTree(received.body());
        assertThat(payload.has("reportType")).isTrue();
        assertThat(payload.has("startDate")).isTrue();
        assertThat(payload.has("endDate")).isTrue();
        assertThat(payload.get("reportType").asText()).isEqualTo("Monthly");
        assertThat(payload.get("startDate").asText()).isEqualTo("2022-07-01");
        assertThat(payload.get("endDate").asText()).isEqualTo("2022-07-31");
    }

    @Test
    void reportQueueHasRedrivePolicyTargetingDeadLetterQueue() throws Exception {
        // Proves QA Issue 2 is resolved: the real report FIFO queue carries a RedrivePolicy so that
        // a poison message (or a repeatedly-failing launch) is isolated to the companion DLQ after
        // MAX_RECEIVE_COUNT receives, instead of looping indefinitely or being silently dropped.
        String redrivePolicy = await(sqsAsyncClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(reportQueueUrl())
                .attributeNames(QueueAttributeName.REDRIVE_POLICY)
                .build()))
                .attributes()
                .get(QueueAttributeName.REDRIVE_POLICY);

        assertThat(redrivePolicy)
                .as("the report FIFO queue %s must carry a RedrivePolicy (D-029)", REPORT_QUEUE)
                .isNotNull();

        JsonNode policy = objectMapper.readTree(redrivePolicy);
        assertThat(policy.path("maxReceiveCount").asInt())
                .as("RedrivePolicy maxReceiveCount")
                .isEqualTo(MAX_RECEIVE_COUNT);
        assertThat(policy.path("deadLetterTargetArn").asText())
                .as("RedrivePolicy must target the companion DLQ %s", REPORT_DLQ)
                .endsWith(":" + REPORT_DLQ);
    }

    private Message receiveOne(String queueUrl) {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            Message message = receive(queueUrl, 5);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    /**
     * Performs a single SQS receive of at most one message, requesting the FIFO
     * {@link MessageSystemAttributeName#MESSAGE_GROUP_ID} system attribute so the message-group
     * contract can be asserted. Returns {@code null} when no message arrives within
     * {@code waitTimeSeconds} (zero requests an immediate short poll).
     */
    private Message receive(String queueUrl, int waitTimeSeconds) {
        ReceiveMessageResponse response = await(sqsAsyncClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(waitTimeSeconds)
                .messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID)
                .build()));
        return response.messages().isEmpty() ? null : response.messages().get(0);
    }

    /** Resolves the URL of the real configured report FIFO queue ({@link #REPORT_QUEUE}). */
    private String reportQueueUrl() {
        return await(sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName(REPORT_QUEUE)
                .build())).queueUrl();
    }

    /** Removes any messages already on the queue so a later assertion can prove exactly-one. */
    private void drain(String queueUrl) {
        Message message;
        while ((message = receive(queueUrl, 0)) != null) {
            deleteMessage(queueUrl, message);
        }
    }

    /** Deletes a received message by its receipt handle. */
    private void deleteMessage(String queueUrl, Message message) {
        await(sqsAsyncClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build()));
    }
}
