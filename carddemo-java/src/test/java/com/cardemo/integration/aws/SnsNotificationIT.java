/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  SNS Notification Fan-Out Integration Test
 *  (net-new cloud notification capability  ->  SNS topic -> SQS subscriber)
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2 / §0.7.7)
 *  Net-new greenfield infrastructure test with NO COBOL source equivalent. SNS
 *  is ENTIRELY net-new in the Java target: the frozen AWS CardDemo COBOL/CICS
 *  estate had no notification/alert-publishing facility whatsoever (source
 *  inspection confirms the only "Amazon" strings in app/cbl are copyright
 *  headers — there is no WRITEQ-to-notification, no MQ, and no SNS analogue).
 *  This test therefore validates the Java target's SNS infrastructure CONTRACT
 *  purely against the AAP/blueprint specification rather than against any legacy
 *  COBOL behaviour.
 *
 *  SPEC-DRIVEN STRATEGY (AAP §0.7.7; docs/technical-specifications.md L1000):
 *      SNS — Alert/notification publishing — LocalStack test strategy:
 *      "Tests create topic, subscribe SQS endpoint, publish, verify fan-out."
 *  The topic under test is carddemo-notifications, the topic-name contract bound
 *  by com.cardemo.config.AwsConfig.AwsResourceProperties (carddemo.aws.sns.
 *  notifications-topic). This IT realizes the documented strategy literally:
 *  it creates the topic, subscribes a TEST-ONLY SQS endpoint, publishes a
 *  notification, and verifies the message fanned out to the subscriber.
 *
 *  The COBOL/JCL sources are read-only reference and are NEVER copied into this
 *  repository; traceability to the frozen legacy baseline is by commit SHA
 *  27d6c6f only. Base package is com.cardemo (decision D-006 — deliberately NOT
 *  com.carddemo), matching <groupId>com.cardemo</groupId> in carddemo-java/pom.xml.
 * ============================================================================
 */
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
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
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

/**
 * End-to-end LocalStack integration test for the CardDemo <strong>SNS notification fan-out</strong>
 * capability &mdash; a <em>net-new</em> cloud feature of the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x
 * target with <strong>no COBOL analogue</strong> in the frozen mainframe estate (AAP §0.7.7).
 *
 * <h2>What this test proves</h2>
 * <p>It exercises the authoritative AAP §0.7.7 SNS verification strategy
 * (docs/technical-specifications.md&nbsp;L1000 &mdash; <em>"Tests create topic, subscribe SQS endpoint,
 * publish, verify fan-out"</em>) against a real (LocalStack) SNS topic and a real (LocalStack) SQS
 * subscriber queue:</p>
 * <ol>
 *   <li>a topic named {@code carddemo-notifications} is created (the name resolved from
 *       {@link AbstractLocalStackIntegrationTest#awsResourceProperties awsResourceProperties}, never a
 *       magic string);</li>
 *   <li>a TEST-ONLY plain (non-FIFO) SQS queue is subscribed to that topic with
 *       <strong>raw message delivery</strong> enabled; and</li>
 *   <li>a single notification published to the topic <strong>fans out</strong> to the subscribed queue,
 *       arriving with a body equal to the published text (raw delivery).</li>
 * </ol>
 *
 * <h2>Why SNS is net-new (no COBOL source)</h2>
 * <p>Unlike the SQS bridge ({@code ReportSubmissionSqsIT}, which translates the single CICS
 * {@code WRITEQ TD QUEUE('JOBS')} online&rarr;batch coupling) and the S3 staging layer
 * ({@code S3StagingIT}, which replaces VSAM/GDG datasets), SNS has <strong>no</strong> 1:1 mainframe
 * counterpart: the legacy CardDemo system published no notifications or alerts. This IT consequently
 * binds to the target's SNS <em>infrastructure contract</em> (the {@code carddemo-notifications} topic
 * declared by {@link com.cardemo.config.AwsConfig.AwsResourceProperties}) rather than to any translated
 * COBOL paragraph &mdash; it is a pure technology-addition test, documented as such per the Minimal
 * Change Clause (AAP §0.7.1).</p>
 *
 * <h2>Self-managed resource lifecycle (AAP §0.7.7)</h2>
 * <p>The topic, the subscriber queue, and the subscription are all created in
 * {@link #createTopicAndSubscribeQueue()} ({@code @BeforeAll}) and torn down in
 * {@link #unsubscribeAndDeleteResources()} ({@code @AfterAll}); no pre-existing state is assumed and
 * reruns are idempotent. The shared LocalStack container is owned by the base class and is intentionally
 * left running (reaped by the Testcontainers <em>Ryuk</em> sidecar at JVM exit), never stopped here.</p>
 *
 * <h2>ZERO live AWS / zero live credentials (AAP §0.7.2 / §0.7.7)</h2>
 * <p>Every AWS interaction targets the inherited LocalStack <strong>community</strong> Testcontainer.
 * Verification uses the inherited synchronous {@link AbstractLocalStackIntegrationTest#newSnsClient()}
 * and {@link AbstractLocalStackIntegrationTest#newSqsClient()} factories, which use LocalStack's dummy
 * {@code test}/{@code test} keys. No real AWS endpoints or secrets are ever involved.</p>
 *
 * <h2>Contract fidelity (no hardcoded resource names)</h2>
 * <p>The topic name is resolved from {@code awsResourceProperties} (bound from
 * {@code carddemo.aws.sns.notifications-topic}) and asserted to equal the contract value
 * {@code carddemo-notifications}, so the test and the production beans resolve the same topic. The
 * subscriber queue {@code carddemo-notifications-it-subscriber} is a deliberately distinct
 * <strong>test-only</strong> resource (not a production queue) used solely to observe fan-out.</p>
 *
 * <h2>Determinism</h2>
 * <p>SNS&rarr;SQS delivery is eventually consistent, so the fan-out assertion is wrapped in
 * <strong>Awaitility</strong> with SQS long polling; messages are accumulated across polls so a
 * received (and therefore temporarily invisible) message is never lost between iterations.</p>
 *
 * <h2>Execution</h2>
 * <p>The mandatory {@code IT} suffix and {@code com.cardemo.integration} package route this class to the
 * {@code maven-failsafe-plugin} (run via {@code mvn verify -Pintegration}); the
 * {@code maven-surefire-plugin} explicitly excludes it. Lifecycle methods are non-static, relying on the
 * {@code @TestInstance(PER_CLASS)} the base class declares (inherited by this subclass), which also lets
 * {@code @BeforeAll} read the autowired {@code awsResourceProperties}. This subclass deliberately adds
 * <strong>no</strong> Spring context annotations ({@code @SpringBootTest} / {@code @ActiveProfiles} /
 * {@code @DynamicPropertySource} / {@code @TestPropertySource}) so the resolved configuration stays
 * identical to its siblings and the cached {@code ApplicationContext} is shared across the AWS IT suite.</p>
 *
 * @see com.cardemo.config.AwsConfig.AwsResourceProperties
 * @see AbstractLocalStackIntegrationTest
 */
@DisplayName("SNS notification fan-out (net-new; topic -> SQS subscriber) integration")
public class SnsNotificationIT extends AbstractLocalStackIntegrationTest {

    /**
     * The contract topic name. Asserted against the value resolved from {@code awsResourceProperties}
     * so a drifting configuration fails the test loudly rather than silently exercising the wrong topic.
     */
    private static final String EXPECTED_TOPIC_NAME = "carddemo-notifications";

    /**
     * A TEST-ONLY plain (non-FIFO) SQS queue used purely to observe SNS fan-out. It is deliberately NOT
     * a production resource (the production AWS resources are the three S3 buckets, the
     * {@code carddemo-report-jobs.fifo} queue, and the {@code carddemo-notifications} topic); this queue
     * exists only for the lifetime of this test class and is deleted in {@code @AfterAll}.
     */
    private static final String SUBSCRIBER_QUEUE_NAME = "carddemo-notifications-it-subscriber";

    /**
     * A deterministic notification payload. The 11-digit account token mirrors the COBOL {@code ACCT-ID}
     * key width (VSAM {@code ACCTDAT} key length 11); the exact text is fixed so the fan-out body
     * assertion is fully deterministic and never flakes.
     */
    private static final String NOTIFICATION_BODY = "CardDemo alert: account 00000000011 over limit";

    /**
     * Independent, synchronous SNS verification/publish client (created from the inherited
     * {@link AbstractLocalStackIntegrationTest#newSnsClient()} factory). Created in {@code @BeforeAll},
     * closed in {@code @AfterAll}.
     */
    private SnsClient sns;

    /**
     * Independent, synchronous SQS client (created from the inherited
     * {@link AbstractLocalStackIntegrationTest#newSqsClient()} factory) used to create the subscriber
     * queue and to read the fanned-out message. Created in {@code @BeforeAll}, closed in
     * {@code @AfterAll}.
     */
    private SqsClient sqs;

    /** ARN of the {@code carddemo-notifications} topic created in {@code @BeforeAll}. */
    private String topicArn;

    /** URL of the test-only subscriber queue created in {@code @BeforeAll}. */
    private String queueUrl;

    /** ARN of the test-only subscriber queue (needed as the SNS subscription endpoint). */
    private String queueArn;

    /** ARN of the SNS&rarr;SQS subscription created in {@code @BeforeAll} (unsubscribed in teardown). */
    private String subscriptionArn;

    // -------------------------------------------------------------------------------------------------
    // Lifecycle — self-managed topic + subscriber queue + subscription
    // (AAP §0.7.7: tests create and destroy their own resources).
    // -------------------------------------------------------------------------------------------------

    /**
     * Provisions the SNS topic, a test-only SQS subscriber queue, and the SNS&rarr;SQS subscription on
     * the shared LocalStack container before any test runs. Non-static (the base declares
     * {@code @TestInstance(PER_CLASS)}) so it can read the autowired
     * {@link AbstractLocalStackIntegrationTest#awsResourceProperties}.
     *
     * <p>Steps (the literal AAP §0.7.7 SNS strategy &mdash; "create topic, subscribe SQS endpoint"):</p>
     * <ol>
     *   <li>resolve the topic name from configuration (never hardcoded) and assert it equals the
     *       contract value, locking the resource-name contract;</li>
     *   <li>create the topic (idempotent: {@code createTopic} with the same name returns the existing
     *       ARN);</li>
     *   <li>create a plain (non-FIFO) subscriber queue and read its ARN;</li>
     *   <li>attach an SQS access policy permitting the topic to {@code sqs:SendMessage} to the queue
     *       (LocalStack is permissive, but the policy is set for correctness/realism); and</li>
     *   <li>subscribe the queue to the topic with {@code RawMessageDelivery=true} so the delivered SQS
     *       body equals the published message verbatim.</li>
     * </ol>
     */
    @BeforeAll
    void createTopicAndSubscribeQueue() {
        sns = newSnsClient();
        sqs = newSqsClient();

        // Resolve the topic name from the SAME bean the production beans use — contract fidelity.
        String topicName = awsResourceProperties.getSns().getNotificationsTopic();
        assertThat(topicName)
                .as("SNS notifications topic name must come from awsResourceProperties "
                        + "(carddemo.aws.sns.notifications-topic), not a hardcoded literal")
                .isEqualTo(EXPECTED_TOPIC_NAME);

        // (1) Create the topic. This realizes the net-new SNS notification capability (no COBOL source).
        topicArn = sns.createTopic(CreateTopicRequest.builder()
                        .name(topicName)
                        .build())
                .topicArn();

        // (2) Create the TEST-ONLY plain (non-FIFO) subscriber queue and read its ARN.
        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName(SUBSCRIBER_QUEUE_NAME)
                        .build())
                .queueUrl();
        queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);

        // (3) Allow SNS -> SQS delivery via an SQS access policy (correctness/realism; LocalStack is
        //     permissive, so this is belt-and-suspenders, but it mirrors a real AWS setup exactly).
        sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributes(Map.of(QueueAttributeName.POLICY, snsToSqsSendPolicy(queueArn, topicArn)))
                .build());

        // (4) Subscribe the queue to the topic with raw message delivery so the SQS body equals the
        //     published message (otherwise SNS wraps the payload in a JSON envelope). returnSubscriptionArn
        //     forces the full ARN back even for the sqs protocol so we can cleanly unsubscribe later.
        subscriptionArn = sns.subscribe(SubscribeRequest.builder()
                        .topicArn(topicArn)
                        .protocol("sqs")
                        .endpoint(queueArn)
                        .attributes(Map.of("RawMessageDelivery", "true"))
                        .returnSubscriptionArn(true)
                        .build())
                .subscriptionArn();
    }

    // -------------------------------------------------------------------------------------------------
    // Test — publish to the topic and verify fan-out to the subscribed queue.
    // -------------------------------------------------------------------------------------------------

    /**
     * Verifies the AAP §0.7.7 fan-out contract: a single notification published to the
     * {@code carddemo-notifications} topic is delivered (fanned out) to the subscribed SQS queue, with a
     * body equal to the published text (raw message delivery).
     */
    @Test
    @DisplayName("publish to carddemo-notifications fans out to the subscribed SQS queue (raw delivery)")
    void publishFansOutToSubscribedQueue() {
        // ---- Act: publish a deterministic notification to the topic. ----
        // SnsClient.publish(...) is synchronous, so the publish has been accepted by SNS once it returns;
        // delivery to the SQS subscriber is then eventually consistent (polled for below).
        sns.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message(NOTIFICATION_BODY)
                .build());

        // ---- Assert: the message fanned out to the subscribed queue. ----
        // SNS->SQS delivery is eventually consistent, so long-poll within an Awaitility window and
        // accumulate across polls (a received message becomes invisible for the visibility timeout, so
        // accumulating avoids losing it between iterations).
        List<Message> received = new ArrayList<>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    received.addAll(receiveMessages(10, 10));
                    assertThat(received)
                            .as("SNS notification fanned out to subscriber queue %s", SUBSCRIBER_QUEUE_NAME)
                            .isNotEmpty();
                });

        // Raw delivery => the SQS body equals the published message verbatim. Assert by list-containment
        // so the test is robust to any incidental extra message ordering.
        assertThat(received)
                .as("the SNS-published notification body fanned out (raw delivery) to the SQS subscriber")
                .extracting(Message::body)
                .contains(NOTIFICATION_BODY);
    }

    // -------------------------------------------------------------------------------------------------
    // Suite teardown.
    // -------------------------------------------------------------------------------------------------

    /**
     * Tears down ONLY this test's own resources &mdash; unsubscribe, delete topic, delete the subscriber
     * queue &mdash; and closes the verification clients (self-managed lifecycle, AAP §0.7.7). Each step
     * is null-guarded so a partially-completed {@code @BeforeAll} still cleans up what it created, and the
     * clients are always closed. The shared LocalStack container is owned by the base class and is
     * intentionally NOT stopped here.
     */
    @AfterAll
    void unsubscribeAndDeleteResources() {
        try {
            if (sns != null && subscriptionArn != null) {
                sns.unsubscribe(UnsubscribeRequest.builder().subscriptionArn(subscriptionArn).build());
            }
            if (sns != null && topicArn != null) {
                sns.deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build());
            }
            if (sqs != null && queueUrl != null) {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            }
        } finally {
            // Always release the SDK clients (AutoCloseable), even if a delete above failed.
            if (sns != null) {
                sns.close();
            }
            if (sqs != null) {
                sqs.close();
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Helpers.
    // -------------------------------------------------------------------------------------------------

    /**
     * Receives up to {@code maxMessages} from the subscriber queue using SQS long polling.
     *
     * @param maxMessages the maximum number of messages to fetch in one call (1&ndash;10)
     * @param waitSeconds the long-poll wait time in seconds (0&ndash;20)
     * @return the received messages (possibly empty); never {@code null}
     */
    private List<Message> receiveMessages(int maxMessages, int waitSeconds) {
        ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitSeconds)
                .build());
        return response.messages();
    }

    /**
     * Builds an SQS access-policy JSON document that permits the given SNS topic to deliver messages to
     * the given SQS queue. This is the standard SNS&rarr;SQS subscription policy: it allows the
     * {@code sns.amazonaws.com} service principal to {@code sqs:SendMessage} to the queue, scoped (via the
     * {@code ArnEquals} condition on {@code aws:SourceArn}) to this one topic so no other source can
     * publish to the queue. LocalStack does not strictly enforce it, but setting it keeps the test
     * faithful to a real AWS deployment.
     *
     * @param sqsQueueArn the ARN of the destination SQS queue
     * @param snsTopicArn the ARN of the source SNS topic permitted to send
     * @return a valid IAM policy JSON string
     */
    private static String snsToSqsSendPolicy(String sqsQueueArn, String snsTopicArn) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Sid": "AllowCardDemoNotificationsTopicToSendToSubscriberQueue",
                      "Effect": "Allow",
                      "Principal": { "Service": "sns.amazonaws.com" },
                      "Action": "sqs:SendMessage",
                      "Resource": "%s",
                      "Condition": { "ArnEquals": { "aws:SourceArn": "%s" } }
                    }
                  ]
                }""".formatted(sqsQueueArn, snsTopicArn);
    }
}
