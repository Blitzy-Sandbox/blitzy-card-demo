/*
 * SnsIntegrationIT.java — SNS LocalStack Integration Test
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *
 * Testcontainers-backed integration test validating SNS topic operations for
 * alert/notification publishing in the CardDemo application. SNS topics provide
 * batch job completion notifications — when the 5-stage batch pipeline
 * (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT) completes, an SNS
 * notification alerts subscribing systems.
 *
 * JCL notification patterns replaced by SNS:
 *   POSTTRAN.jcl  line 2: NOTIFY=&SYSUID — JES notification to submitter
 *   CREASTMT.JCL  line 2: NOTIFY=&SYSUID — Statement generation completion
 *   TRANREPT.jcl  line 2: NOTIFY=&SYSUID — Transaction report completion
 *
 * This test verifies:
 *   1. Topic creation — SNS topic can be created for batch notifications
 *   2. SQS subscription — SQS queues can subscribe to SNS topics for fan-out
 *   3. Batch completion publish — Structured batch job completion notifications
 *   4. Fan-out delivery — Messages published to SNS are delivered to SQS subscribers
 *   5. Attribute filtering — Message attributes enable selective notification routing
 *
 * Per AAP §0.7.7 (LocalStack Verification Rule): zero live AWS dependencies.
 * The test creates/destroys its own SNS+SQS resources following the strict
 * lifecycle pattern: @BeforeAll create → test execution → @AfterAll delete.
 *
 * Decision Log References:
 *   D-004: SQS FIFO for TDQ replacement with ordering guarantee
 */
package com.cardemo.integration.aws;

import java.util.List;
import java.util.Map;

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
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicResponse;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicResponse;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for SNS topic operations against LocalStack.
 *
 * <p>Validates that the CardDemo application can create SNS topics, subscribe
 * SQS queues for fan-out delivery, publish structured batch job completion
 * notifications, and verify end-to-end message delivery through the SNS→SQS
 * pipeline.
 *
 * <p>This test class follows the AAP §0.7.7 resource lifecycle pattern:
 * <ol>
 *   <li>{@code @BeforeAll}: Create SNS topic, SQS subscriber queue, and subscription</li>
 *   <li>Test execution: Exercise SNS operations against LocalStack</li>
 *   <li>{@code @AfterAll}: Unsubscribe, delete topic, and delete subscriber queue</li>
 * </ol>
 *
 * <p>The test uses Testcontainers LocalStack with BOTH SNS and SQS services
 * enabled, because SNS fan-out verification requires an SQS subscriber endpoint.
 *
 * @see com.cardemo.config.AwsConfig#snsClient()
 * @see com.cardemo.config.AwsConfig#sqsClient()
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SnsIntegrationIT {

    private static final Logger log = LoggerFactory.getLogger(SnsIntegrationIT.class);

    // -------------------------------------------------------------------------
    // Testcontainers LocalStack Container — SNS + SQS Services
    // -------------------------------------------------------------------------
    // SNS fan-out testing requires SQS subscriber — both services must be active.
    // The container is managed by the @Testcontainers JUnit 5 extension which
    // handles automatic start before tests and stop after all tests complete.
    // -------------------------------------------------------------------------

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices("sns", "sqs");

    // -------------------------------------------------------------------------
    // Dynamic Property Registration — Wire LocalStack Endpoints
    // -------------------------------------------------------------------------
    // Overrides the application-test.yml default LocalStack endpoints with the
    // actual Testcontainers-managed container endpoints (dynamically allocated ports).
    // -------------------------------------------------------------------------

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.sns.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.sqs.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.s3.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.credentials.access-key",
                localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key",
                localstack::getSecretKey);
        registry.add("spring.cloud.aws.region.static",
                localstack::getRegion);
    }

    // -------------------------------------------------------------------------
    // Injected AWS Clients — From AwsConfig @Bean factory methods
    // -------------------------------------------------------------------------

    @Autowired
    private SnsClient snsClient;

    @Autowired
    private SqsClient sqsClient;

    // -------------------------------------------------------------------------
    // Constants — Topic and Queue Names
    // -------------------------------------------------------------------------

    /** SNS topic name for batch pipeline completion notifications. */
    private static final String BATCH_NOTIFICATIONS_TOPIC = "carddemo-batch-notifications";

    /** Test-only SQS queue for verifying SNS fan-out delivery. */
    private static final String SUBSCRIBER_QUEUE_NAME = "carddemo-batch-notification-subscriber";

    // -------------------------------------------------------------------------
    // State — Populated in @BeforeAll, consumed by tests and @AfterAll
    // -------------------------------------------------------------------------

    /** ARN of the SNS topic created in @BeforeAll. */
    private static String topicArn;

    /** URL of the SQS subscriber queue created in @BeforeAll. */
    private static String subscriberQueueUrl;

    /** ARN of the SQS subscriber queue (needed for SNS subscription endpoint). */
    private static String subscriberQueueArn;

    /** ARN of the SNS-to-SQS subscription created in @BeforeAll. */
    private static String subscriptionArn;

    // =========================================================================
    // Lifecycle Methods — Create/Destroy SNS + SQS Resources
    // =========================================================================

    /**
     * Creates all AWS resources required for SNS integration testing.
     *
     * <p>Executes before any test method in this class. Creates:
     * <ol>
     *   <li>SNS topic for batch notifications</li>
     *   <li>SQS subscriber queue (standard, not FIFO — for notification receipt)</li>
     *   <li>SNS-to-SQS subscription</li>
     *   <li>SQS queue policy allowing SNS cross-service delivery</li>
     * </ol>
     *
     * <p>Uses direct SDK client construction (not Spring-injected beans) because
     * the Spring application context is not yet available in {@code @BeforeAll}
     * static lifecycle methods.
     */
    @BeforeAll
    static void setupAwsResources() {
        log.info("Setting up SNS integration test resources against LocalStack");

        SnsClient snsSetupClient = buildSnsClient();
        SqsClient sqsSetupClient = buildSqsClient();

        try {
            // Step 1: Create SNS topic for batch pipeline notifications
            // Maps from JCL NOTIFY=&SYSUID (POSTTRAN.jcl, CREASTMT.JCL, TRANREPT.jcl)
            CreateTopicResponse topicResponse = snsSetupClient.createTopic(
                    b -> b.name(BATCH_NOTIFICATIONS_TOPIC));
            topicArn = topicResponse.topicArn();
            log.info("Created SNS topic: {} (ARN: {})", BATCH_NOTIFICATIONS_TOPIC, topicArn);

            // Step 2: Create SQS subscriber queue (standard queue for notification receipt)
            CreateQueueResponse queueResponse = sqsSetupClient.createQueue(
                    b -> b.queueName(SUBSCRIBER_QUEUE_NAME));
            subscriberQueueUrl = queueResponse.queueUrl();
            log.info("Created SQS subscriber queue: {} (URL: {})", SUBSCRIBER_QUEUE_NAME, subscriberQueueUrl);

            // Step 3: Get SQS queue ARN (needed for SNS subscription endpoint)
            GetQueueAttributesResponse attrs = sqsSetupClient.getQueueAttributes(
                    b -> b.queueUrl(subscriberQueueUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN));
            subscriberQueueArn = attrs.attributes().get(QueueAttributeName.QUEUE_ARN);
            log.info("Subscriber queue ARN: {}", subscriberQueueArn);

            // Step 4: Subscribe SQS queue to SNS topic for fan-out delivery
            SubscribeResponse subResponse = snsSetupClient.subscribe(
                    b -> b.topicArn(topicArn)
                            .protocol("sqs")
                            .endpoint(subscriberQueueArn));
            subscriptionArn = subResponse.subscriptionArn();
            log.info("Subscribed SQS to SNS topic — subscription ARN: {}", subscriptionArn);

            // Step 5: Set SQS queue policy to allow SNS to deliver messages
            // This IAM-style policy is required for cross-service delivery between
            // SNS and SQS, even in LocalStack. Without it, SNS cannot push messages
            // into the SQS subscriber queue.
            String policy = String.format("""
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": {"Service": "sns.amazonaws.com"},
                        "Action": "sqs:SendMessage",
                        "Resource": "%s",
                        "Condition": {"ArnEquals": {"aws:SourceArn": "%s"}}
                      }]
                    }""", subscriberQueueArn, topicArn);
            sqsSetupClient.setQueueAttributes(
                    b -> b.queueUrl(subscriberQueueUrl)
                            .attributes(Map.of(QueueAttributeName.POLICY, policy)));
            log.info("SQS queue policy set — SNS can deliver messages to subscriber queue");

        } finally {
            // Close setup-only clients (Spring-managed clients are used in tests)
            snsSetupClient.close();
            sqsSetupClient.close();
        }

        log.info("SNS integration test setup complete — topic, queue, subscription, and policy configured");
    }

    /**
     * Cleans up all AWS resources created during test setup.
     *
     * <p>Executes after all test methods in this class complete. Destroys:
     * <ol>
     *   <li>SNS-to-SQS subscription (unsubscribe)</li>
     *   <li>SNS topic (delete)</li>
     *   <li>SQS subscriber queue (purge then delete)</li>
     * </ol>
     *
     * <p>All cleanup operations are wrapped in individual try-catch blocks to
     * ensure that a failure in one cleanup step does not prevent subsequent
     * cleanup steps from executing. This guarantees no leftover AWS resources
     * in the LocalStack container (AAP §0.7.7).
     */
    @AfterAll
    static void cleanupAwsResources() {
        log.info("Cleaning up SNS integration test resources");

        SnsClient snsCleanupClient = buildSnsClient();
        SqsClient sqsCleanupClient = buildSqsClient();

        try {
            // Unsubscribe SQS from SNS topic
            if (subscriptionArn != null) {
                try {
                    snsCleanupClient.unsubscribe(b -> b.subscriptionArn(subscriptionArn));
                    log.info("Unsubscribed: {}", subscriptionArn);
                } catch (Exception e) {
                    log.warn("Failed to unsubscribe {}: {}", subscriptionArn, e.getMessage());
                }
            }

            // Delete SNS topic
            if (topicArn != null) {
                try {
                    snsCleanupClient.deleteTopic(b -> b.topicArn(topicArn));
                    log.info("Deleted SNS topic: {}", topicArn);
                } catch (Exception e) {
                    log.warn("Failed to delete SNS topic {}: {}", topicArn, e.getMessage());
                }
            }

            // Purge and delete SQS subscriber queue
            if (subscriberQueueUrl != null) {
                try {
                    sqsCleanupClient.purgeQueue(b -> b.queueUrl(subscriberQueueUrl));
                    log.info("Purged SQS queue: {}", subscriberQueueUrl);
                } catch (Exception e) {
                    log.warn("Failed to purge SQS queue {}: {}", subscriberQueueUrl, e.getMessage());
                }
                try {
                    sqsCleanupClient.deleteQueue(b -> b.queueUrl(subscriberQueueUrl));
                    log.info("Deleted SQS queue: {}", subscriberQueueUrl);
                } catch (Exception e) {
                    log.warn("Failed to delete SQS queue {}: {}", subscriberQueueUrl, e.getMessage());
                }
            }
        } finally {
            snsCleanupClient.close();
            sqsCleanupClient.close();
        }

        log.info("SNS integration test cleanup complete — all resources destroyed");
    }

    // =========================================================================
    // Test Methods — SNS Integration Verification
    // =========================================================================

    /**
     * Verifies that the SNS topic was successfully created in LocalStack.
     *
     * <p>Lists all topics via the Spring-injected {@link SnsClient} bean from
     * {@link com.cardemo.config.AwsConfig#snsClient()} and asserts that the
     * batch notifications topic exists.
     */
    @Test
    void testTopicCreation() {
        log.info("Verifying SNS topic creation: {}", BATCH_NOTIFICATIONS_TOPIC);

        ListTopicsResponse response = snsClient.listTopics();
        List<String> topicArns = response.topics().stream()
                .map(Topic::topicArn)
                .toList();

        assertThat(topicArns)
                .as("SNS topics should include the batch notifications topic")
                .anyMatch(arn -> arn.contains(BATCH_NOTIFICATIONS_TOPIC));

        log.info("Topic creation verified — {} topics found, batch notifications topic present", topicArns.size());
    }

    /**
     * Verifies that the SQS queue subscription to the SNS topic was correctly established.
     *
     * <p>Lists subscriptions for the batch notifications topic and asserts:
     * <ul>
     *   <li>Exactly one subscription exists</li>
     *   <li>Protocol is "sqs" (SQS fan-out delivery)</li>
     *   <li>Endpoint matches the subscriber queue ARN</li>
     * </ul>
     */
    @Test
    void testSqsSubscription() {
        log.info("Verifying SQS subscription to SNS topic: {}", topicArn);

        ListSubscriptionsByTopicResponse response = snsClient.listSubscriptionsByTopic(
                b -> b.topicArn(topicArn));

        assertThat(response.subscriptions())
                .as("Exactly one SQS subscription should exist for the topic")
                .hasSize(1);
        assertThat(response.subscriptions().get(0).protocol())
                .as("Subscription protocol should be 'sqs'")
                .isEqualTo("sqs");
        assertThat(response.subscriptions().get(0).endpoint())
                .as("Subscription endpoint should be the subscriber queue ARN")
                .isEqualTo(subscriberQueueArn);

        log.info("SQS subscription verified — protocol: sqs, endpoint: {}", subscriberQueueArn);
    }

    /**
     * Verifies that a batch job completion notification can be published to SNS.
     *
     * <p>Publishes a structured JSON notification simulating a
     * {@code DailyTransactionPostingJob} completion event. This maps from the
     * JCL {@code NOTIFY=&SYSUID} pattern in POSTTRAN.jcl (line 2) where JES
     * notified the job submitter upon completion.
     *
     * <p>In the Java migration, SNS provides structured notification publishing
     * with rich metadata (job name, status, record counts, timestamps) rather
     * than the simple JES completion message.
     */
    @Test
    void testPublishBatchCompletionNotification() {
        log.info("Publishing batch job completion notification to SNS topic");

        String message = """
                {"event":"BATCH_JOB_COMPLETED","jobName":"DailyTransactionPostingJob",\
                "status":"COMPLETED","recordsProcessed":20,"recordsRejected":2,\
                "startTime":"2024-01-15T10:00:00Z","endTime":"2024-01-15T10:05:30Z"}""";

        PublishResponse response = snsClient.publish(
                b -> b.topicArn(topicArn)
                        .subject("CardDemo Batch Job Completed")
                        .message(message));

        assertThat(response.messageId())
                .as("Published message should have a non-null messageId")
                .isNotNull();
        assertThat(response.messageId())
                .as("Published message ID should not be empty")
                .isNotEmpty();

        log.info("Batch completion notification published — messageId: {}", response.messageId());
    }

    /**
     * Verifies end-to-end SNS fan-out delivery to an SQS subscriber.
     *
     * <p>Publishes a pipeline completion notification to the SNS topic and then
     * verifies that the message is delivered to the subscribed SQS queue. This
     * validates the complete fan-out path: SNS publish → SQS delivery.
     *
     * <p><strong>CRITICAL:</strong> SNS wraps messages in a JSON envelope with
     * {@code Type}, {@code MessageId}, {@code TopicArn}, and {@code Message}
     * fields. The test verifies the inner message content within this envelope.
     *
     * <p>This maps from the JCL batch pipeline completion flow where all 5 stages
     * (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT) complete and notify
     * the submitter via JES {@code NOTIFY=&SYSUID}.
     *
     * @throws InterruptedException if the wait for fan-out delivery is interrupted
     */
    @Test
    void testFanOutToSqsSubscriber() throws InterruptedException {
        log.info("Testing SNS → SQS fan-out delivery");

        // Drain any messages left over from prior tests to ensure isolation.
        // We receive repeatedly until the queue is empty, rather than using
        // purgeQueue which has an eventual-consistency delay on AWS.
        boolean drained = false;
        while (!drained) {
            ReceiveMessageResponse drain = sqsClient.receiveMessage(
                    b -> b.queueUrl(subscriberQueueUrl)
                            .maxNumberOfMessages(10)
                            .waitTimeSeconds(1));
            if (drain.messages().isEmpty()) {
                drained = true;
            } else {
                drain.messages().forEach(msg ->
                        sqsClient.deleteMessage(b -> b.queueUrl(subscriberQueueUrl)
                                .receiptHandle(msg.receiptHandle())));
                log.info("Drained {} leftover messages from subscriber queue", drain.messages().size());
            }
        }

        // Publish pipeline completion notification to SNS
        String notification = "{\"event\":\"PIPELINE_COMPLETED\",\"pipeline\":\"5-stage-batch\"}";
        snsClient.publish(b -> b.topicArn(topicArn).message(notification));
        log.info("Published pipeline completion notification to SNS topic");

        // Wait briefly for asynchronous SNS → SQS fan-out delivery
        Thread.sleep(2000);

        // Receive message from SQS subscriber queue
        ReceiveMessageResponse receiveResp = sqsClient.receiveMessage(
                b -> b.queueUrl(subscriberQueueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(10));

        assertThat(receiveResp.messages())
                .as("SQS subscriber should receive at least one message from SNS fan-out")
                .isNotEmpty();

        // SNS wraps the original message in a JSON envelope containing:
        // { "Type": "Notification", "MessageId": "...", "TopicArn": "...",
        //   "Subject": "...", "Message": "<original-message>", ... }
        // The original message content appears within the "Message" field of the envelope.
        // Search all received messages for the expected content (in case of ordering variance).
        boolean foundPipelineCompleted = receiveResp.messages().stream()
                .anyMatch(msg -> msg.body().contains("PIPELINE_COMPLETED"));
        assertThat(foundPipelineCompleted)
                .as("At least one SQS message should contain the PIPELINE_COMPLETED event")
                .isTrue();

        boolean foundStageBatch = receiveResp.messages().stream()
                .anyMatch(msg -> msg.body().contains("5-stage-batch"));
        assertThat(foundStageBatch)
                .as("At least one SQS message should contain the 5-stage-batch pipeline identifier")
                .isTrue();

        log.info("Fan-out delivery verified — message received in SQS subscriber queue");

        // Cleanup: delete received messages to prevent interference with other tests
        receiveResp.messages().forEach(msg ->
                sqsClient.deleteMessage(b -> b.queueUrl(subscriberQueueUrl)
                        .receiptHandle(msg.receiptHandle())));

        log.info("Cleaned up {} received messages from subscriber queue", receiveResp.messages().size());
    }

    /**
     * Verifies that SNS messages can be published with message attributes.
     *
     * <p>Publishes a batch job failure notification with {@code severity} and
     * {@code jobName} message attributes. These attributes enable subscriber
     * filter policies — monitoring systems can subscribe to only ERROR-severity
     * notifications, for example.
     *
     * <p>This extends the JCL {@code NOTIFY=&SYSUID} pattern with rich metadata
     * that was not available in the z/OS JES notification mechanism.
     */
    @Test
    void testPublishWithAttributes() {
        log.info("Publishing SNS message with attributes for filtering");

        PublishResponse response = snsClient.publish(
                b -> b.topicArn(topicArn)
                        .message("{\"event\":\"JOB_FAILED\",\"jobName\":\"InterestCalculationJob\"}")
                        .subject("CardDemo Batch Job Failed")
                        .messageAttributes(Map.of(
                                "severity", MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue("ERROR")
                                        .build(),
                                "jobName", MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue("InterestCalculationJob")
                                        .build())));

        assertThat(response.messageId())
                .as("Published message with attributes should have a non-null messageId")
                .isNotNull();
        assertThat(response.messageId())
                .as("Published message ID with attributes should not be empty")
                .isNotEmpty();

        log.info("Message with attributes published — messageId: {}, attributes: [severity=ERROR, jobName=InterestCalculationJob]",
                response.messageId());
    }

    // =========================================================================
    // Helper Methods — Static Client Construction for Lifecycle Methods
    // =========================================================================

    /**
     * Builds an SNS client using LocalStack container credentials.
     *
     * <p>Used by {@code @BeforeAll} and {@code @AfterAll} static lifecycle
     * methods where the Spring application context (and thus the Spring-managed
     * {@link SnsClient} bean from {@link com.cardemo.config.AwsConfig}) is not
     * available.
     *
     * @return a new {@link SnsClient} connected to the LocalStack container
     */
    private static SnsClient buildSnsClient() {
        return SnsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                localstack.getAccessKey(),
                                localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .build();
    }

    /**
     * Builds an SQS client using LocalStack container credentials.
     *
     * <p>Used by {@code @BeforeAll} and {@code @AfterAll} static lifecycle
     * methods where the Spring application context (and thus the Spring-managed
     * {@link SqsClient} bean from {@link com.cardemo.config.AwsConfig}) is not
     * available.
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
}
