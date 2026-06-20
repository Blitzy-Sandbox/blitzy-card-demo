package com.carddemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;

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
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
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
        Map<String, Object> fields = new HashMap<>();
        fields.put("monthly", "Y");
        fields.put("confirm", "Y");
        ReportRequest request = objectMapper.convertValue(fields, ReportRequest.class);

        ReportResponse response = reportSubmissionService.submitReport(request);

        assertThat(response).isNotNull();
        assertThat(response.errorMessage()).isNull();
        assertThat(response.confirmationMessage()).isNotBlank();
        assertThat(response.confirmationMessage()).contains("Monthly");

        // Gate 5 contract verification: consume from the ACTUAL configured report queue
        // (carddemo-report-jobs.fifo) that the real service path published to, and assert the
        // byte-stable {reportType,startDate,endDate} JSON message schema. This exercises the end
        // -to-end service publish, not a direct send (the direct schema send is kept separately
        // below as a supplementary contract test against an isolated queue).
        String reportQueueUrl = await(sqsAsyncClient.getQueueUrl(
                GetQueueUrlRequest.builder().queueName(REPORT_QUEUE).build())).queueUrl();
        Message received = receiveOne(reportQueueUrl);
        assertThat(received).isNotNull();

        JsonNode payload = objectMapper.readTree(received.body());
        assertThat(payload.has("reportType")).isTrue();
        assertThat(payload.has("startDate")).isTrue();
        assertThat(payload.has("endDate")).isTrue();
        assertThat(payload.get("reportType").asText()).isEqualTo("Monthly");
        assertThat(payload.get("startDate").asText()).isNotBlank();
        assertThat(payload.get("endDate").asText()).isNotBlank();
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

    private Message receiveOne(String queueUrl) {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            ReceiveMessageResponse response = await(sqsAsyncClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(5)
                    .build()));
            if (!response.messages().isEmpty()) {
                return response.messages().get(0);
            }
        }
        return null;
    }
}
