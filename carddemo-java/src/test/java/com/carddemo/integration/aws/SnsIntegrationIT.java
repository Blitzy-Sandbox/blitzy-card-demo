package com.carddemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Integration test for the SNS notification fan-out (com.carddemo.config.AwsConfig SnsClient)
 * against LocalStack: the CardDemo notifications topic fans a published message out to a subscribed
 * SQS queue. Cross-cutting cloud integration with no COBOL equivalent (commit 27d6c6f, REFERENCE ONLY).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnsIntegrationIT extends AbstractAwsLocalStackIT {

    private static final String SUBSCRIBER_QUEUE = "carddemo-notifications-sub-it";

    @Autowired
    private SnsClient snsClient;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    private String topicArn;
    private String subscriberQueueUrl;
    private String subscriptionArn;

    @BeforeAll
    void subscribeQueueToTopic() {
        topicArn = snsClient.createTopic(CreateTopicRequest.builder()
                .name(NOTIFICATIONS_TOPIC)
                .build()).topicArn();

        subscriberQueueUrl = await(sqsAsyncClient.createQueue(CreateQueueRequest.builder()
                .queueName(SUBSCRIBER_QUEUE)
                .build())).queueUrl();

        Map<QueueAttributeName, String> queueAttributes = await(sqsAsyncClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(subscriberQueueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())).attributes();
        String queueArn = queueAttributes.get(QueueAttributeName.QUEUE_ARN);

        Map<String, String> subscribeAttributes = new HashMap<>();
        subscribeAttributes.put("RawMessageDelivery", "true");
        subscriptionArn = snsClient.subscribe(SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol("sqs")
                .endpoint(queueArn)
                .attributes(subscribeAttributes)
                .returnSubscriptionArn(true)
                .build()).subscriptionArn();
    }

    @AfterAll
    void teardownSubscription() {
        if (subscriptionArn != null) {
            snsClient.unsubscribe(UnsubscribeRequest.builder().subscriptionArn(subscriptionArn).build());
        }
        if (subscriberQueueUrl != null) {
            await(sqsAsyncClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(subscriberQueueUrl).build()));
        }
    }

    @Test
    void publishedNotificationFansOutToSubscribedQueue() {
        String payload = "CardDemo account ACCT00000000011 posted; notification fan-out check";

        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .subject("CardDemo Notification")
                .message(payload)
                .build());

        Message received = pollForMessage(subscriberQueueUrl, Duration.ofSeconds(30));
        // The SNS->SQS fan-out is a required, concrete verification: a missing message must FAIL
        // this test, never soft-skip it. (Previously this aborted via Assumptions, which reported
        // the required AWS interaction as skipped.)
        assertThat(received)
                .as("SNS->SQS fan-out must deliver the published notification to the subscribed queue within 30s")
                .isNotNull();
        assertThat(received.body()).isEqualTo(payload);
    }

    private Message pollForMessage(String queueUrl, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ReceiveMessageResponse response = await(sqsAsyncClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(2)
                    .build()));
            if (!response.messages().isEmpty()) {
                return response.messages().get(0);
            }
        }
        return null;
    }
}
