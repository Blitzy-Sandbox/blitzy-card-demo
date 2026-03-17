/*
 * SqsIntegrationIT.java — SQS LocalStack Integration Test
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *
 * Testcontainers-backed integration test validating SQS FIFO queue operations
 * for the CardDemo report submission system. This test verifies the CICS TDQ
 * (Transient Data Queue) replacement pattern — COBOL program CORPT00C.cbl
 * writes up to 1000 JCL card images (80 bytes each) to TDQ named 'JOBS' for
 * JES batch submission; Java replaces this with a single SQS FIFO message
 * containing JSON report parameters to carddemo-report-jobs.fifo (AAP Decision
 * D-004: SQS FIFO for TDQ replacement with sequential ordering guarantee).
 *
 * COBOL TDQ → SQS Mapping (CORPT00C.cbl lines 515-535):
 *   EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD) → SQS sendMessage()
 *   TDQ 'JOBS' (sequential, extrapartition)              → FIFO queue carddemo-report-jobs.fifo
 *   JCL card images (80 bytes × up to 1000)               → JSON {reportType, startDate, endDate, submittedAt}
 *   messageGroupId("report-submissions")                  → matches ReportSubmissionService pattern
 *   messageDeduplicationId(UUID)                          → prevents duplicate delivery
 *
 * COBOL paragraph mapping exercised by these tests:
 *   SUBMIT-JOB-TO-INTRDR (lines 462-510) → testSendReportMessage, testFifoOrdering
 *   WIRTE-JOBSUB-TDQ     (lines 515-535) → testSendReportMessage, testReceiveReportMessage
 *   PROCESS-ENTER-KEY    (lines 208-456) → testReportSubmissionMessageSchema (Monthly/Yearly/Custom)
 *
 * Per AAP §0.7.7 (LocalStack Verification Rule): zero live AWS dependencies.
 * Tests create/destroy their own SQS resources following the strict lifecycle
 * pattern: @BeforeAll create → test execution → @AfterAll delete.
 *
 * Decision Log References:
 *   D-004: SQS FIFO for TDQ replacement with ordering guarantee
 *          (SQS FIFO has 300 msg/sec throughput limit — sufficient for this workload)
 */
package com.cardemo.integration.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for SQS FIFO queue operations against LocalStack.
 *
 * <p>Validates that the CardDemo application can create SQS FIFO queues,
 * send and receive report submission messages, delete messages (acknowledge),
 * verify FIFO ordering guarantees, and validate the report submission JSON
 * message schema used by {@code ReportSubmissionService}.
 *
 * <p>This test class follows the AAP §0.7.7 resource lifecycle pattern:
 * <ol>
 *   <li>{@code @BeforeAll}: Create SQS FIFO queue with FifoQueue=true and
 *       ContentBasedDeduplication=false (explicit deduplication IDs)</li>
 *   <li>Test execution: Exercise SQS operations against LocalStack</li>
 *   <li>{@code @AfterAll}: Purge and delete the FIFO queue</li>
 * </ol>
 *
 * <p><strong>FIFO Queue Semantics (Decision D-004):</strong>
 * The FIFO queue guarantees that messages within the same {@code messageGroupId}
 * are delivered in the exact order they were sent, matching the sequential read
 * semantics of the CICS TDQ 'JOBS' queue in CORPT00C.cbl. Each message requires
 * a unique {@code messageDeduplicationId} (UUID) to prevent duplicate delivery.
 *
 * @see com.cardemo.config.AwsConfig#sqsClient()
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SqsIntegrationIT {

    private static final Logger log = LoggerFactory.getLogger(SqsIntegrationIT.class);

    // -------------------------------------------------------------------------
    // Testcontainers LocalStack Container — SQS Service
    // -------------------------------------------------------------------------
    // Only SQS service is needed for this test. The container is managed by the
    // @Testcontainers JUnit 5 extension which handles automatic start before
    // tests and stop after all tests complete.
    // -------------------------------------------------------------------------

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices("sqs");

    // -------------------------------------------------------------------------
    // Dynamic Property Registration — Wire LocalStack Endpoints
    // -------------------------------------------------------------------------
    // Overrides the application-test.yml default LocalStack endpoints with the
    // actual Testcontainers-managed container endpoints (dynamically allocated
    // ports). All three AWS service endpoints (S3, SQS, SNS) are overridden
    // because AwsConfig creates @Bean instances for all three during Spring
    // context initialization, and all must point to a valid endpoint.
    // -------------------------------------------------------------------------

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.sqs.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.s3.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.sns.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.credentials.access-key",
                localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key",
                localstack::getSecretKey);
        registry.add("spring.cloud.aws.region.static",
                localstack::getRegion);
    }

    // -------------------------------------------------------------------------
    // Injected Beans — From Spring Application Context
    // -------------------------------------------------------------------------
    // SqsClient is provided by AwsConfig.sqsClient() @Bean factory method.
    // ObjectMapper is provided by Spring Boot Jackson auto-configuration.
    // -------------------------------------------------------------------------

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Constants — Queue Configuration
    // -------------------------------------------------------------------------
    // FIFO queue name matches the AAP specification and ReportSubmissionService.
    // The .fifo suffix is required by SQS for FIFO queue identification.
    // -------------------------------------------------------------------------

    /** SQS FIFO queue name for report job submissions — replaces CICS TDQ 'JOBS'. */
    private static final String REPORT_QUEUE_NAME = "carddemo-report-jobs.fifo";

    /**
     * Message group ID used by ReportSubmissionService for all report submissions.
     * All messages in the same group are delivered in strict FIFO order, matching
     * the sequential read semantics of the CICS TDQ 'JOBS' queue.
     */
    private static final String MESSAGE_GROUP_ID = "report-submissions";

    // -------------------------------------------------------------------------
    // State — Populated in @BeforeAll, consumed by tests and @AfterAll
    // -------------------------------------------------------------------------

    /** URL of the SQS FIFO queue created in @BeforeAll. */
    private static String queueUrl;

    // =========================================================================
    // Lifecycle Methods — Create/Destroy SQS FIFO Queue
    // =========================================================================

    /**
     * Creates the SQS FIFO queue in LocalStack before any test method executes.
     *
     * <p>The FIFO queue is created with the following attributes:
     * <ul>
     *   <li>{@code FifoQueue=true} — enables FIFO ordering semantics</li>
     *   <li>{@code ContentBasedDeduplication=false} — requires explicit
     *       {@code messageDeduplicationId} per message (matching
     *       ReportSubmissionService pattern which uses UUID)</li>
     * </ul>
     *
     * <p>Uses direct SDK client construction (not Spring-injected beans) because
     * the Spring application context is not yet available in {@code @BeforeAll}
     * static lifecycle methods.
     *
     * <p>Maps from CORPT00C.cbl SUBMIT-JOB-TO-INTRDR paragraph (lines 462-510)
     * which assumes the TDQ 'JOBS' queue is pre-defined in the CICS system
     * definition (CSD). In the Java migration, queue creation is explicit.
     */
    @BeforeAll
    static void createQueue() {
        log.info("Setting up SQS integration test resources against LocalStack");

        SqsClient setupClient = buildSqsClient();

        try {
            // Create FIFO queue with explicit deduplication ID requirement.
            // The .fifo suffix on the queue name is mandatory for SQS FIFO queues.
            // ContentBasedDeduplication=false means each sendMessage() call MUST
            // provide a messageDeduplicationId — we use UUID.randomUUID() to match
            // the ReportSubmissionService implementation pattern.
            CreateQueueResponse response = setupClient.createQueue(CreateQueueRequest.builder()
                    .queueName(REPORT_QUEUE_NAME)
                    .attributes(Map.of(
                            QueueAttributeName.FIFO_QUEUE, "true",
                            QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"))
                    .build());
            queueUrl = response.queueUrl();
            log.info("Created SQS FIFO queue: {} (URL: {})", REPORT_QUEUE_NAME, queueUrl);
        } finally {
            setupClient.close();
        }

        log.info("SQS integration test setup complete — FIFO queue created");
    }

    /**
     * Cleans up the SQS FIFO queue after all test methods complete.
     *
     * <p>Purges all remaining messages and then deletes the queue. Both
     * operations are wrapped in try-catch to ensure the cleanup client is
     * always closed, even if an operation fails.
     *
     * <p>This guarantees no leftover AWS resources in the LocalStack container
     * (AAP §0.7.7 — no pre-existing state dependency).
     */
    @AfterAll
    static void deleteQueue() {
        log.info("Cleaning up SQS integration test resources");

        SqsClient cleanupClient = buildSqsClient();

        try {
            // Purge all remaining messages before queue deletion
            if (queueUrl != null) {
                try {
                    cleanupClient.purgeQueue(b -> b.queueUrl(queueUrl));
                    log.info("Purged SQS FIFO queue: {}", queueUrl);
                } catch (Exception e) {
                    log.warn("Failed to purge SQS queue {}: {}", queueUrl, e.getMessage());
                }

                // Delete the queue
                try {
                    cleanupClient.deleteQueue(b -> b.queueUrl(queueUrl));
                    log.info("Deleted SQS FIFO queue: {}", queueUrl);
                } catch (Exception e) {
                    log.warn("Failed to delete SQS queue {}: {}", queueUrl, e.getMessage());
                }
            }
        } finally {
            cleanupClient.close();
        }

        log.info("SQS integration test cleanup complete — FIFO queue destroyed");
    }

    // =========================================================================
    // Test Methods — SQS FIFO Queue Integration Verification
    // =========================================================================

    /**
     * Verifies that the SQS FIFO queue was successfully created in LocalStack.
     *
     * <p>Uses the Spring-injected {@link SqsClient} bean from
     * {@link com.cardemo.config.AwsConfig#sqsClient()} to retrieve the queue URL
     * by name, confirming that the queue exists and is accessible.
     *
     * <p>Also verifies the queue URL contains the FIFO queue name, ensuring
     * the .fifo suffix is properly reflected in the queue URL.
     */
    @Test
    void testQueueCreation() {
        log.info("Verifying SQS FIFO queue creation: {}", REPORT_QUEUE_NAME);

        // Retrieve queue URL using Spring-injected client (validates AwsConfig.sqsClient() bean)
        GetQueueUrlResponse response = sqsClient.getQueueUrl(b -> b.queueName(REPORT_QUEUE_NAME));

        assertThat(response.queueUrl())
                .as("FIFO queue URL should not be null after creation")
                .isNotNull();
        assertThat(response.queueUrl())
                .as("FIFO queue URL should contain the queue name with .fifo suffix")
                .contains(REPORT_QUEUE_NAME);

        log.info("FIFO queue creation verified — URL: {}", response.queueUrl());
    }

    /**
     * Verifies that a report submission message can be sent to the SQS FIFO queue.
     *
     * <p>Sends a JSON message matching the {@code ReportSubmissionService} contract:
     * <ul>
     *   <li>{@code reportType} — "Monthly", "Yearly", or "Custom"
     *       (from CORPT00C.cbl PROCESS-ENTER-KEY EVALUATE: MONTHLYI/YEARLYI/CUSTOMI)</li>
     *   <li>{@code startDate} — ISO-8601 date (from PARM-START-DATE fields)</li>
     *   <li>{@code endDate} — ISO-8601 date (from PARM-END-DATE fields)</li>
     *   <li>{@code submittedAt} — submission timestamp</li>
     * </ul>
     *
     * <p>The message uses:
     * <ul>
     *   <li>{@code messageGroupId("report-submissions")} — matches
     *       ReportSubmissionService for FIFO ordering within the group</li>
     *   <li>{@code messageDeduplicationId(UUID)} — unique per message,
     *       preventing duplicate delivery (ContentBasedDeduplication=false)</li>
     * </ul>
     *
     * <p>This validates the CORPT00C.cbl TDQ WRITEQ JOBS replacement —
     * a single JSON message replaces up to 1000 JCL card images (80 bytes each).
     */
    @Test
    void testSendReportMessage() {
        log.info("Testing SQS FIFO message send — report submission");

        // JSON payload matching ReportSubmissionService contract
        // Maps from CORPT00C.cbl Monthly report (PROCESS-ENTER-KEY lines 213-238)
        String messageBody = """
                {"reportType":"Monthly","startDate":"2024-01-01","endDate":"2024-01-31","submittedAt":"2024-01-15T10:30:00Z"}""";

        SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody.trim())
                .messageGroupId(MESSAGE_GROUP_ID)
                .messageDeduplicationId(UUID.randomUUID().toString())
                .build());

        assertThat(response.messageId())
                .as("Sent message should have a non-null messageId from SQS")
                .isNotNull();
        assertThat(response.messageId())
                .as("Sent message ID should not be empty")
                .isNotEmpty();

        log.info("Report message sent successfully — messageId: {}", response.messageId());

        // Cleanup: receive and delete the message to prevent interference with other tests
        drainMessages(1);
    }

    /**
     * Verifies the full send-receive-delete cycle for SQS FIFO messages.
     *
     * <p>Exercises the complete message lifecycle matching the TDQ write-read
     * pattern from CORPT00C.cbl:
     * <ol>
     *   <li>Send a Yearly report submission message (PROCESS-ENTER-KEY lines 239-255)</li>
     *   <li>Receive the message with long polling (5-second wait)</li>
     *   <li>Verify message body contains expected content</li>
     *   <li>Delete the message (acknowledge receipt)</li>
     * </ol>
     *
     * <p>The delete operation maps from the CICS TDQ READQ TD pattern where
     * reading a message from a TDQ implicitly removes it (destructive read).
     * In SQS, messages must be explicitly deleted after processing.
     */
    @Test
    void testReceiveReportMessage() {
        log.info("Testing SQS FIFO message send-receive-delete cycle");

        // Send a Yearly report submission message
        // Maps from CORPT00C.cbl Yearly report (PROCESS-ENTER-KEY lines 239-255)
        String reportPayload = "{\"reportType\":\"Yearly\",\"startDate\":\"2024-01-01\",\"endDate\":\"2024-12-31\"}";
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(reportPayload)
                .messageGroupId(MESSAGE_GROUP_ID)
                .messageDeduplicationId(UUID.randomUUID().toString())
                .build());
        log.info("Sent Yearly report message to FIFO queue");

        // Receive with long polling — waitTimeSeconds=5 ensures the message is available
        ReceiveMessageResponse receiveResp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(5)
                .build());

        assertThat(receiveResp.messages())
                .as("Should receive exactly 1 message from the FIFO queue")
                .hasSize(1);
        assertThat(receiveResp.messages().get(0).body())
                .as("Received message body should contain 'Yearly' report type")
                .contains("Yearly");
        assertThat(receiveResp.messages().get(0).body())
                .as("Received message body should contain the start date")
                .contains("2024-01-01");
        assertThat(receiveResp.messages().get(0).body())
                .as("Received message body should contain the end date")
                .contains("2024-12-31");

        // Delete received message — acknowledge processing
        // In CICS TDQ, reading is destructive (READQ TD removes the record).
        // In SQS, explicit deletion is required after successful processing.
        sqsClient.deleteMessage(b -> b.queueUrl(queueUrl)
                .receiptHandle(receiveResp.messages().get(0).receiptHandle()));

        log.info("Send-receive-delete cycle verified — message processed and acknowledged");
    }

    /**
     * Verifies message deletion (acknowledgment) leaves the queue empty.
     *
     * <p>Tests the complete lifecycle: send → receive → delete → verify empty.
     * This validates that message acknowledgment works correctly for the SQS FIFO
     * queue, ensuring no ghost messages remain after processing.
     *
     * <p>Maps from the CICS TDQ destructive read semantics where once a message
     * is read from the TDQ, it is permanently removed. In SQS, the equivalent
     * operation requires explicit {@code deleteMessage()} after receiving.
     */
    @Test
    void testDeleteMessage() {
        log.info("Testing SQS FIFO message deletion (acknowledgment)");

        // Drain any leftover messages to start with a clean queue state
        drainAllMessages();

        // Send a test message
        String messageBody = "{\"reportType\":\"Monthly\",\"startDate\":\"2024-06-01\",\"endDate\":\"2024-06-30\"}";
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .messageGroupId(MESSAGE_GROUP_ID)
                .messageDeduplicationId(UUID.randomUUID().toString())
                .build());
        log.info("Sent test message for deletion verification");

        // Receive the message
        ReceiveMessageResponse receiveResp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(5)
                .build());

        assertThat(receiveResp.messages())
                .as("Should receive the sent message before deletion")
                .hasSize(1);

        // Delete the message
        sqsClient.deleteMessage(b -> b.queueUrl(queueUrl)
                .receiptHandle(receiveResp.messages().get(0).receiptHandle()));
        log.info("Message deleted — verifying queue is empty");

        // Verify queue is empty after deletion
        // Use short wait time (1 second) to avoid slow test execution
        ReceiveMessageResponse emptyResp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .build());

        assertThat(emptyResp.messages())
                .as("Queue should be empty after message deletion — no ghost messages")
                .isEmpty();

        log.info("Message deletion verified — queue is confirmed empty");
    }

    /**
     * Verifies FIFO ordering guarantee — messages in the same group arrive in order.
     *
     * <p>This is the critical test for Decision D-004 (SQS FIFO for TDQ replacement).
     * CICS TDQ 'JOBS' in CORPT00C.cbl provides sequential read semantics — messages
     * are read in the exact order they were written. SQS FIFO queues preserve this
     * ordering for messages within the same {@code messageGroupId}.
     *
     * <p>The test:
     * <ol>
     *   <li>Drains any existing messages to ensure clean state</li>
     *   <li>Sends 3 messages with sequential content in the SAME message group</li>
     *   <li>Receives all 3 messages via polling</li>
     *   <li>Verifies they arrive in the exact order sent (sequence 1, 2, 3)</li>
     * </ol>
     *
     * <p>Each message uses the SAME {@code messageGroupId} to ensure ordering
     * within the group, and each uses a UNIQUE {@code messageDeduplicationId}
     * (UUID) as required by FIFO semantics.
     *
     * <p>This maps from CORPT00C.cbl SUBMIT-JOB-TO-INTRDR (lines 462-510) which
     * writes JCL records sequentially to TDQ 'JOBS' using PERFORM VARYING from
     * WS-IDX=1 to 1000, preserving record order.
     */
    @Test
    void testFifoOrdering() {
        log.info("Testing SQS FIFO ordering guarantee — Decision D-004 validation");

        // Drain any existing messages to ensure clean test state
        drainAllMessages();

        // Send 3 messages with sequential content in the same message group
        // Maps from CORPT00C.cbl loop: PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 1000
        String[] payloads = {
                "{\"reportType\":\"Monthly\",\"sequence\":1}",
                "{\"reportType\":\"Yearly\",\"sequence\":2}",
                "{\"reportType\":\"Custom\",\"sequence\":3}"
        };

        for (String payload : payloads) {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(payload)
                    .messageGroupId(MESSAGE_GROUP_ID)
                    .messageDeduplicationId(UUID.randomUUID().toString())
                    .build());
        }
        log.info("Sent 3 sequential messages to FIFO queue in group '{}'", MESSAGE_GROUP_ID);

        // Receive all 3 messages — poll until we have all of them or exhaust retries.
        // SQS FIFO may not return all messages in a single receive call, so we
        // collect across multiple receive operations while preserving order.
        List<Message> allMessages = new ArrayList<>();
        int maxAttempts = 5;
        int attempt = 0;

        while (allMessages.size() < 3 && attempt < maxAttempts) {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(3)
                    .build());
            allMessages.addAll(resp.messages());
            attempt++;
            log.info("Receive attempt {}/{}: got {} messages (total: {})",
                    attempt, maxAttempts, resp.messages().size(), allMessages.size());
        }

        assertThat(allMessages)
                .as("Should receive all 3 messages from FIFO queue")
                .hasSize(3);

        // Verify FIFO ordering — messages must arrive in the exact order sent
        // This validates that SQS FIFO preserves sequential read semantics
        // matching CICS TDQ 'JOBS' queue behavior (Decision D-004)
        assertThat(allMessages.get(0).body())
                .as("First message should be sequence 1 (Monthly)")
                .contains("\"sequence\":1");
        assertThat(allMessages.get(1).body())
                .as("Second message should be sequence 2 (Yearly)")
                .contains("\"sequence\":2");
        assertThat(allMessages.get(2).body())
                .as("Third message should be sequence 3 (Custom)")
                .contains("\"sequence\":3");

        log.info("FIFO ordering verified — messages delivered in exact send order (1, 2, 3)");

        // Cleanup: delete all received messages to prevent interference
        for (Message msg : allMessages) {
            sqsClient.deleteMessage(b -> b.queueUrl(queueUrl)
                    .receiptHandle(msg.receiptHandle()));
        }
        log.info("Cleaned up {} ordered messages from FIFO queue", allMessages.size());
    }

    /**
     * Validates the exact JSON schema that {@code ReportSubmissionService} produces.
     *
     * <p>This test exercises Gate 5 — API/Interface contract verification for the
     * SQS message schema. The report submission message must contain exactly these
     * fields:
     * <ul>
     *   <li>{@code reportType} — "Custom" (from CORPT00C.cbl Custom report, line 433)</li>
     *   <li>{@code startDate} — ISO-8601 date (from PARM-START-DATE, lines 381-386)</li>
     *   <li>{@code endDate} — ISO-8601 date (from PARM-END-DATE, lines 384-386)</li>
     *   <li>{@code submittedAt} — submission timestamp (not in COBOL — Java enhancement)</li>
     * </ul>
     *
     * <p>Uses Jackson {@link ObjectMapper} for serialization (send) and
     * deserialization (receive) to verify round-trip JSON fidelity, matching
     * the real serialization path used by {@code ReportSubmissionService}.
     *
     * @throws Exception if JSON serialization or deserialization fails
     */
    @Test
    void testReportSubmissionMessageSchema() throws Exception {
        log.info("Testing report submission message schema — Gate 5 contract verification");

        // Construct payload using Map.of() matching ReportSubmissionService contract
        // Maps from CORPT00C.cbl Custom report (PROCESS-ENTER-KEY lines 256-436):
        //   WS-REPORT-NAME = 'Custom' (line 433)
        //   PARM-START-DATE = user-entered start date (lines 381-383)
        //   PARM-END-DATE   = user-entered end date (lines 384-386)
        Map<String, String> payload = Map.of(
                "reportType", "Custom",
                "startDate", "2024-03-01",
                "endDate", "2024-03-31",
                "submittedAt", "2024-03-15T14:30:00Z");

        // Serialize using ObjectMapper — same serialization path as ReportSubmissionService
        String messageBody = objectMapper.writeValueAsString(payload);
        log.info("Serialized report submission payload: {}", messageBody);

        // Send to FIFO queue
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .messageGroupId(MESSAGE_GROUP_ID)
                .messageDeduplicationId(UUID.randomUUID().toString())
                .build());

        // Receive and deserialize
        ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(5)
                .build());

        assertThat(response.messages())
                .as("Should receive the schema validation message")
                .isNotEmpty();

        // Deserialize received message back to Map for field-level verification
        Map<String, String> received = objectMapper.readValue(
                response.messages().get(0).body(),
                new TypeReference<Map<String, String>>() {});

        // Verify all required fields are present (Gate 5 contract check)
        assertThat(received)
                .as("Report submission message must contain all 4 contract fields")
                .containsKeys("reportType", "startDate", "endDate", "submittedAt");

        // Verify field values match exactly
        assertThat(received.get("reportType"))
                .as("reportType should be 'Custom' (CORPT00C.cbl line 433)")
                .isEqualTo("Custom");
        assertThat(received.get("startDate"))
                .as("startDate should be '2024-03-01' (ISO-8601 format)")
                .isEqualTo("2024-03-01");
        assertThat(received.get("endDate"))
                .as("endDate should be '2024-03-31' (ISO-8601 format)")
                .isEqualTo("2024-03-31");
        assertThat(received.get("submittedAt"))
                .as("submittedAt should be '2024-03-15T14:30:00Z' (ISO-8601 timestamp)")
                .isEqualTo("2024-03-15T14:30:00Z");

        log.info("Report submission message schema verified — all 4 fields match contract");

        // Cleanup: delete the received message
        sqsClient.deleteMessage(b -> b.queueUrl(queueUrl)
                .receiptHandle(response.messages().get(0).receiptHandle()));

        log.info("Gate 5 contract verification complete for SQS message schema");
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Builds an SQS client using LocalStack container credentials.
     *
     * <p>Used by {@code @BeforeAll} and {@code @AfterAll} static lifecycle
     * methods where the Spring application context (and thus the Spring-managed
     * {@link SqsClient} bean from {@link com.cardemo.config.AwsConfig}) is not
     * available.
     *
     * <p>Credentials are sourced directly from the Testcontainers
     * {@link LocalStackContainer} instance (test-only, not real AWS credentials).
     *
     * @return a new {@link SqsClient} connected to the LocalStack container
     */
    private static SqsClient buildSqsClient() {
        return SqsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                localstack.getAccessKey(),
                                localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .build();
    }

    /**
     * Drains a specified number of messages from the FIFO queue.
     *
     * <p>Used by individual tests to clean up messages they have sent,
     * preventing interference with subsequent tests. Messages are received
     * and immediately deleted (acknowledged).
     *
     * @param expectedCount the number of messages to drain
     */
    private void drainMessages(int expectedCount) {
        int drained = 0;
        int maxAttempts = 3;
        int attempt = 0;

        while (drained < expectedCount && attempt < maxAttempts) {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(2)
                    .build());

            for (Message msg : resp.messages()) {
                sqsClient.deleteMessage(b -> b.queueUrl(queueUrl)
                        .receiptHandle(msg.receiptHandle()));
                drained++;
            }
            attempt++;
        }

        if (drained > 0) {
            log.info("Drained {} messages from FIFO queue", drained);
        }
    }

    /**
     * Drains all remaining messages from the FIFO queue.
     *
     * <p>Used before tests that require a clean queue state (e.g.,
     * {@link #testFifoOrdering()} and {@link #testDeleteMessage()}).
     * Receives messages in a loop until the queue is empty, deleting
     * each message immediately.
     */
    private void drainAllMessages() {
        boolean drained = false;
        int totalDrained = 0;

        while (!drained) {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .build());

            if (resp.messages().isEmpty()) {
                drained = true;
            } else {
                for (Message msg : resp.messages()) {
                    sqsClient.deleteMessage(b -> b.queueUrl(queueUrl)
                            .receiptHandle(msg.receiptHandle()));
                    totalDrained++;
                }
            }
        }

        if (totalDrained > 0) {
            log.info("Drained {} total messages from FIFO queue before test", totalDrained);
        }
    }
}
