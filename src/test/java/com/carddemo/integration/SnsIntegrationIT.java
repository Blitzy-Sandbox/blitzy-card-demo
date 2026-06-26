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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalStack integration test for the AWS <strong>SNS notification bridge</strong>
 * (Validation Gate&nbsp;5 — real-contract verification, no self-certification).
 *
 * <p>SNS has no direct COBOL ancestor: the legacy report-submission program
 * {@code CORPT00C} (transaction {@code CR00}) assembled an 80-column JCL stream and
 * wrote it to the CICS Transient Data Queue {@code JOBS} to trigger an asynchronous
 * batch report. In the modernized architecture that submit-then-process bridge is the
 * SQS FIFO queue {@code carddemo-report-jobs.fifo}, while SNS provides the complementary
 * <em>notification fan-out</em> fabric (the canonical topic, by default
 * {@code carddemo-notifications}). This IT proves the SNS contract works end-to-end
 * against a real LocalStack endpoint with <strong>zero live-AWS dependencies</strong>:
 * the topic is listable, a publish returns a message id, and a published message fans
 * out to a subscribed SQS queue (raw delivery) with its body and message attributes
 * intact.</p>
 *
 * <h2>Infrastructure and wiring</h2>
 * The class extends {@link AbstractIntegrationIT}, inheriting the singleton
 * Testcontainers LocalStack endpoint and the credential-free AWS&nbsp;SDK&nbsp;v2 client
 * factories ({@link #newSnsClient()}, {@link #newSqsClient()}). The canonical topic name
 * is <strong>never hardcoded</strong>: it is read from configuration through the
 * inherited {@link #snsTopicName()} helper, which resolves
 * {@code awsResourceProperties.getSns().getTopic()} (bound from {@code carddemo.aws.sns.topic}
 * in {@code application.yml} / {@code application-test.yml}). Asserting against
 * {@code ":" + snsTopicName()} keeps the test correct regardless of the configured value.
 *
 * <h2>Self-provisioning and cleanup (LocalStack Verification rule)</h2>
 * {@link #provisionCanonicalAwsResources()} is invoked from {@link #setUp()} so the
 * canonical topic exists before every test (the test-side equivalent of
 * {@code localstack-init/init-aws.sh}, which is not executed inside the Testcontainers
 * container). Each test that creates its own SQS queue and SNS→SQS subscription tracks
 * those resources and removes them in {@link #tearDown()}; the shared canonical topic is
 * deliberately left in place (the base class owns its lifecycle). The tests are therefore
 * independently re-runnable and leak no resources.
 */
@DisplayName("SNS notification bridge IT — LocalStack publish + SNS→SQS fan-out (Gate 5)")
public class SnsIntegrationIT extends AbstractIntegrationIT {

    /** Total budget for long-polling an SQS queue before giving up (milliseconds). */
    private static final long RECEIVE_TIMEOUT_MS = 20_000L;

    /** Per-request SQS long-poll wait (seconds); bounded by the SQS maximum of 20. */
    private static final int POLL_WAIT_SECONDS = 5;

    /** Prefix for the unique, test-scoped SQS queues created by the fan-out tests. */
    private static final String TEST_QUEUE_PREFIX = "sns-it-fanout-";

    /**
     * SNS subscription ARNs created by individual tests, removed in {@link #tearDown()}.
     * A fresh instance is used per test method (JUnit's default per-method lifecycle), so
     * the list only ever holds the current test's subscriptions.
     */
    private final List<String> createdSubscriptionArns = new ArrayList<>();

    /** Absolute URLs of the test-scoped SQS queues, removed in {@link #tearDown()}. */
    private final List<String> createdQueueUrls = new ArrayList<>();

    /**
     * Provisions the canonical AWS resources (idempotently) before each test so the SNS
     * topic the production code path uses is present and listable. This mirrors the
     * provisioning that {@code init-aws.sh} performs for the Docker Compose stack.
     */
    @BeforeEach
    void setUp() {
        provisionCanonicalAwsResources();
    }

    /**
     * Best-effort teardown of every resource a test created: unsubscribe each SNS→SQS
     * subscription, then delete each test-scoped SQS queue. Failures during cleanup are
     * swallowed (the resource may already be gone) so a teardown problem never masks the
     * test result; the tracking lists are always cleared. The canonical topic is never
     * deleted.
     */
    @AfterEach
    void tearDown() {
        if (createdSubscriptionArns.isEmpty() && createdQueueUrls.isEmpty()) {
            return;
        }
        try (SnsClient sns = newSnsClient();
             SqsClient sqs = newSqsClient()) {
            for (String subscriptionArn : createdSubscriptionArns) {
                try {
                    sns.unsubscribe(UnsubscribeRequest.builder()
                            .subscriptionArn(subscriptionArn)
                            .build());
                } catch (SdkException alreadyGone) {
                    // Idempotent teardown: the subscription was already removed.
                }
            }
            for (String queueUrl : createdQueueUrls) {
                try {
                    sqs.deleteQueue(DeleteQueueRequest.builder()
                            .queueUrl(queueUrl)
                            .build());
                } catch (SdkException alreadyGone) {
                    // Idempotent teardown: the queue was already removed.
                }
            }
        } finally {
            createdSubscriptionArns.clear();
            createdQueueUrls.clear();
        }
    }

    /**
     * The canonical notification topic provisioned by the base class is discoverable
     * through the real {@code ListTopics} contract, with an ARN that ends in the
     * config-bound topic name (parity with {@code init-aws.sh}).
     */
    @Test
    @DisplayName("canonical notification topic is provisioned and listable via ListTopics")
    void reportNotificationTopicExists() {
        String topicName = snsTopicName();
        try (SnsClient sns = newSnsClient()) {
            boolean present = sns.listTopics().topics().stream()
                    .anyMatch(topic -> topic.topicArn().endsWith(":" + topicName));
            assertThat(present)
                    .as("SNS topic ARN ending in ':%s' should be present", topicName)
                    .isTrue();
        }
    }

    /**
     * Publishing a subject + body to the canonical topic succeeds against LocalStack and
     * returns a non-blank SNS message id.
     */
    @Test
    @DisplayName("publish to the canonical topic returns a message id")
    void publishToTopicSucceeds() {
        try (SnsClient sns = newSnsClient()) {
            String topicArn = resolveCanonicalTopicArn(sns);
            String messageId = sns.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .subject("CardDemo report notification")
                    .message("Report job submitted: " + UUID.randomUUID())
                    .build())
                    .messageId();
            assertThat(messageId)
                    .as("SNS publish must return a non-blank message id")
                    .isNotBlank();
        }
    }

    /**
     * End-to-end fan-out: a message published to the canonical topic is delivered to a
     * freshly subscribed SQS queue (raw delivery) and arrives with the exact published
     * body — proving the full SNS→SQS notification path locally.
     */
    @Test
    @DisplayName("SNS→SQS fan-out delivers the published body to a subscribed queue")
    void snsToSqsFanoutDeliversMessage() {
        String expectedBody = "sns-fanout-" + UUID.randomUUID();
        try (SnsClient sns = newSnsClient();
             SqsClient sqs = newSqsClient()) {
            String topicArn = resolveCanonicalTopicArn(sns);
            String queueUrl = subscribeFreshQueueToTopic(sns, sqs, topicArn);

            sns.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(expectedBody)
                    .build());

            Message received = receiveOneMessage(sqs, queueUrl);
            assertThat(received)
                    .as("SNS→SQS fan-out should deliver exactly one message")
                    .isNotNull();
            assertThat(received.body())
                    .as("the delivered body should equal the published body (raw delivery)")
                    .isEqualTo(expectedBody);
        }
    }

    /**
     * A message attribute set on the SNS publish survives the fan-out and is delivered
     * intact as an SQS message attribute (raw delivery preserves attributes), confirming
     * the attribute contract end-to-end.
     */
    @Test
    @DisplayName("SNS message attributes are preserved through the SQS fan-out")
    void publishWithMessageAttributesPreserved() {
        String expectedBody = "sns-attr-" + UUID.randomUUID();
        String attributeName = "reportType";
        String attributeValue = "TRANSACTION-DETAIL";
        try (SnsClient sns = newSnsClient();
             SqsClient sqs = newSqsClient()) {
            String topicArn = resolveCanonicalTopicArn(sns);
            String queueUrl = subscribeFreshQueueToTopic(sns, sqs, topicArn);

            sns.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(expectedBody)
                    .messageAttributes(Map.of(attributeName, MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(attributeValue)
                            .build()))
                    .build());

            Message received = receiveOneMessage(sqs, queueUrl);
            assertThat(received)
                    .as("a message with attributes should be delivered")
                    .isNotNull();
            assertThat(received.body()).isEqualTo(expectedBody);
            assertThat(received.messageAttributes())
                    .as("the SNS message attribute should be delivered to SQS")
                    .containsKey(attributeName);
            assertThat(received.messageAttributes().get(attributeName).stringValue())
                    .isEqualTo(attributeValue);
        }
    }

    /**
     * Resolves the ARN of the canonical notification topic. {@code CreateTopic} is
     * idempotent in SNS — it returns the existing topic's ARN when the name already
     * exists — so this both guarantees the topic is present and yields its ARN.
     *
     * @param sns the SNS client to use
     * @return the canonical topic ARN
     */
    private String resolveCanonicalTopicArn(SnsClient sns) {
        return sns.createTopic(CreateTopicRequest.builder()
                .name(snsTopicName())
                .build())
                .topicArn();
    }

    /**
     * Creates a unique, test-scoped standard SQS queue, authorizes the topic to deliver to
     * it, and subscribes it to the canonical topic with raw message delivery enabled. The
     * created queue URL and subscription ARN are tracked for teardown.
     *
     * <p>A standard (non-FIFO) queue is used because the canonical topic is a standard SNS
     * topic; SNS can only fan out to a FIFO queue from a FIFO topic. Raw message delivery
     * is enabled so the SQS body is exactly the published payload (no SNS JSON envelope),
     * which keeps the body and attribute assertions precise.</p>
     *
     * @param sns      the SNS client to use
     * @param sqs      the SQS client to use
     * @param topicArn the canonical topic ARN to subscribe to
     * @return the absolute URL of the newly created, subscribed queue
     */
    private String subscribeFreshQueueToTopic(SnsClient sns, SqsClient sqs, String topicArn) {
        String queueName = TEST_QUEUE_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .build())
                .queueUrl();
        createdQueueUrls.add(queueUrl);

        String queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build())
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);

        // Authorize SNS to deliver to the queue (the real AWS contract; LocalStack enforces it).
        sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributes(Map.of(QueueAttributeName.POLICY, sqsAllowSnsPolicy(queueArn, topicArn)))
                .build());

        String subscriptionArn = sns.subscribe(SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol("sqs")
                .endpoint(queueArn)
                .attributes(Map.of("RawMessageDelivery", "true"))
                .returnSubscriptionArn(true)
                .build())
                .subscriptionArn();
        createdSubscriptionArns.add(subscriptionArn);

        return queueUrl;
    }

    /**
     * Long-polls the given queue until a message arrives or the {@link #RECEIVE_TIMEOUT_MS}
     * budget is exhausted. Message attributes are requested so attribute-preservation
     * assertions can inspect them.
     *
     * @param sqs      the SQS client to use
     * @param queueUrl the absolute queue URL to poll
     * @return the first received message, or {@code null} if none arrived within the budget
     */
    private Message receiveOneMessage(SqsClient sqs, String queueUrl) {
        long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            var response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(POLL_WAIT_SECONDS)
                    .messageAttributeNames("All")
                    .build());
            if (response.hasMessages() && !response.messages().isEmpty()) {
                return response.messages().get(0);
            }
        }
        return null;
    }

    /**
     * Builds an SQS access policy that allows the Amazon SNS service principal to send
     * messages to the given queue, scoped to the given topic ARN. The values are
     * LocalStack-generated ARNs (trusted, not user input), and the document is JSON — not
     * SQL — so it carries no injection or unsafe-code concern.
     *
     * @param queueArn the ARN of the queue that grants access
     * @param topicArn the ARN of the topic permitted to publish to the queue
     * @return the access-policy document as a JSON string
     */
    private static String sqsAllowSnsPolicy(String queueArn, String topicArn) {
        return "{"
                + "\"Version\":\"2012-10-17\","
                + "\"Statement\":[{"
                + "\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"sns.amazonaws.com\"},"
                + "\"Action\":\"sqs:SendMessage\","
                + "\"Resource\":\"" + queueArn + "\","
                + "\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"" + topicArn + "\"}}"
                + "}]"
                + "}";
    }
}
