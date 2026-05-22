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
package com.awsm2.carddemo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configures the AWS Secrets Manager dynamic-rotation listener required
 * by AAP &sect;0.6.4 ("AWS Secrets Manager Dynamic Rotation Without
 * Restart").
 *
 * <p>The complete rotation flow this class participates in:</p>
 * <pre>
 *   Secrets Manager rotation Lambda
 *       → publishes to SNS topic
 *       → SNS topic fans out to SQS queue
 *       → @Scheduled SQS poller (this class) receives the message
 *       → ContextRefresher.refresh()  ← re-reads aws-secretsmanager: imports
 *       → @RefreshScope beans drained and re-instantiated on next use:
 *            * HikariCP DataSource (JpaConfig)
 *            * JwtTokenProvider (security/JwtTokenProvider)
 *            * Kafka producer/consumer factories (KafkaConfig)
 * </pre>
 *
 * <p>Per AAP guidance and PCI-DSS rotation requirements, the listener:</p>
 * <ul>
 *   <li>Is <strong>OFF by default</strong> &mdash; gated by
 *       {@link ConditionalOnProperty} with {@code matchIfMissing=false}
 *       so local and test profiles do not spin up an SQS poller.</li>
 *   <li>Polls one SQS queue ({@code carddemo.aws.secrets.rotation-listener.queue-url})
 *       with long-polling enabled, then collapses all received messages
 *       into a <strong>single</strong> {@link ContextRefresher#refresh()}
 *       call. This avoids storming the context with N refreshes when a
 *       fan-out delivered N copies of the same rotation event.</li>
 *   <li>Deletes each message only AFTER the refresh returns successfully.
 *       Any failure (refresh throws, network error, etc.) skips the
 *       delete so SQS re-delivers after the visibility-timeout window
 *       and the next poll cycle retries.</li>
 *   <li>Catches and logs every exception type so the {@code @Scheduled}
 *       method never propagates &mdash; a transient AWS failure must
 *       NEVER stop the scheduler thread.</li>
 * </ul>
 *
 * <h2>Replaces</h2>
 * <p>Replaces: the COBOL/mainframe pattern of restarting the CICS region
 * to pick up new credentials. The Java target instead refreshes the
 * Spring application context in-place &mdash; no ECS task restart, no
 * dropped connections beyond the HikariCP {@code maxLifetime} pool drain.</p>
 *
 * <h2>Failure semantics</h2>
 * <p>If {@code carddemo.aws.secrets.rotation-listener.enabled=true} but
 * the queue URL is blank or the SQS client cannot reach the queue, this
 * class will log warnings on every poll cycle. The application
 * continues to run with the existing (pre-rotation) credentials &mdash;
 * a degraded but not broken state. Operators are expected to wire an
 * SNS / CloudWatch alarm on missed rotation events.</p>
 *
 * @see com.awsm2.carddemo.adapter.SecretsManagerService
 * @see com.awsm2.carddemo.config.JpaConfig
 * @see com.awsm2.carddemo.security.JwtTokenProvider
 */
@Configuration
@ConditionalOnProperty(
        name = "carddemo.aws.secrets.rotation-listener.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class SecretsManagerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsManagerConfig.class);

    /**
     * Bean that owns the polling loop. Defined as a separate bean to keep
     * the {@code @Configuration} cleanly focused on bean wiring while
     * isolating the polling state on a distinct lifecycle.
     *
     * @param sqsClient         SQS client (from {@link AwsSdkConfig#sqsClient()})
     * @param contextRefresher  Spring Cloud context refresher (auto-configured
     *                          when {@code spring-cloud-starter-bootstrap}
     *                          is on the classpath)
     * @param queueUrl          SQS queue URL to poll
     * @param maxMessages       SQS {@code MaxNumberOfMessages} (1-10)
     * @param waitSeconds       SQS {@code WaitTimeSeconds} (0-20)
     * @param visibilitySeconds SQS {@code VisibilityTimeout} (seconds)
     * @return the configured rotation listener
     */
    @Bean
    public SecretsRotationListener secretsRotationListener(
            SqsClient sqsClient,
            ContextRefresher contextRefresher,
            @Value("${carddemo.aws.secrets.rotation-listener.queue-url:}") String queueUrl,
            @Value("${carddemo.aws.secrets.rotation-listener.max-messages:10}") int maxMessages,
            @Value("${carddemo.aws.secrets.rotation-listener.wait-time-seconds:20}") int waitSeconds,
            @Value("${carddemo.aws.secrets.rotation-listener.visibility-timeout-seconds:60}") int visibilitySeconds) {
        return new SecretsRotationListener(sqsClient, contextRefresher, queueUrl,
                maxMessages, waitSeconds, visibilitySeconds);
    }

    /**
     * Scheduled SQS poller that drives the Spring Cloud context refresh.
     *
     * <p>This class is intentionally <em>not</em> a Spring stereotype
     * (e.g., {@code @Component}) &mdash; it is instantiated as a bean by
     * the surrounding {@code @Configuration} so the
     * {@code @ConditionalOnProperty} on the config still gates its
     * existence. The {@link Scheduled} method here is processed by
     * Spring's scheduling infrastructure regardless of where the bean
     * came from, provided {@code @EnableScheduling} is active on the
     * application context (see {@code CardDemoApplication}).</p>
     */
    public static class SecretsRotationListener {

        private final SqsClient sqsClient;
        private final ContextRefresher contextRefresher;
        private final String queueUrl;
        private final int maxMessages;
        private final int waitSeconds;
        private final int visibilitySeconds;

        SecretsRotationListener(SqsClient sqsClient,
                                ContextRefresher contextRefresher,
                                String queueUrl,
                                int maxMessages,
                                int waitSeconds,
                                int visibilitySeconds) {
            // Replaces: CICS NEWCOPY + region recycle that the mainframe
            // operations team had to perform manually for every credential
            // rotation. The Java target rotates without any operator touch.
            this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient must not be null");
            this.contextRefresher = Objects.requireNonNull(contextRefresher,
                    "contextRefresher must not be null");
            this.queueUrl = queueUrl;
            this.maxMessages = clamp(maxMessages, 1, 10);
            this.waitSeconds = clamp(waitSeconds, 0, 20);
            this.visibilitySeconds = clamp(visibilitySeconds, 1, 43200);
        }

        /**
         * Polls SQS, refreshes the Spring context on receipt of any
         * messages, and deletes those messages only after the refresh
         * succeeds. Scheduled at {@code carddemo.aws.secrets.rotation-listener.poll-interval-ms}
         * with a 30 s default.
         */
        @Scheduled(fixedDelayString = "${carddemo.aws.secrets.rotation-listener.poll-interval-ms:30000}",
                   initialDelayString = "${carddemo.aws.secrets.rotation-listener.poll-interval-ms:30000}")
        public void pollOnce() {
            if (queueUrl == null || queueUrl.isBlank()) {
                // Configuration is incomplete — log periodically rather
                // than silently no-op'ing so the misconfiguration is
                // visible in CloudWatch.
                LOG.warn("Secrets rotation listener enabled but queue-url is blank; skipping poll");
                return;
            }
            try {
                ReceiveMessageRequest req = ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(maxMessages)
                        .waitTimeSeconds(waitSeconds)
                        .visibilityTimeout(visibilitySeconds)
                        .build();
                ReceiveMessageResponse resp = sqsClient.receiveMessage(req);
                List<Message> messages = resp.messages();
                if (messages == null || messages.isEmpty()) {
                    // No rotation events this cycle — completely normal.
                    return;
                }
                LOG.info("Secrets rotation listener received {} message(s); triggering ContextRefresher",
                        messages.size());

                // Single refresh per poll cycle: even if SNS fan-out
                // delivered N copies of the same rotation, the context
                // need only be refreshed once.
                Set<String> changedKeys = contextRefresher.refresh();
                LOG.info("ContextRefresher.refresh() returned {} changed property keys",
                        (changedKeys == null) ? 0 : changedKeys.size());

                // Delete each message ONLY after the refresh succeeds.
                // If refresh() threw, control would not reach this loop
                // and the messages remain in-flight; they redeliver
                // after the visibility timeout for retry.
                for (Message m : messages) {
                    try {
                        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .receiptHandle(m.receiptHandle())
                                .build());
                    } catch (SqsException sqsEx) {
                        // Delete failure does not require us to undo the
                        // refresh (refresh is idempotent and safe to repeat).
                        // The redelivered message will trigger another
                        // refresh next cycle, which is acceptable.
                        LOG.warn("Failed to delete rotation message messageId={} cause={}",
                                m.messageId(), sqsEx.getMessage());
                    }
                }
            } catch (SqsException sqsEx) {
                // Transient AWS failures (throttling, networking) are
                // logged and skipped. Messages remain in-flight or
                // unreceived; next poll cycle retries.
                LOG.warn("Secrets rotation poll failed (SQS) errorCode={} cause={}",
                        sqsEx.awsErrorDetails() == null
                                ? "n/a" : sqsEx.awsErrorDetails().errorCode(),
                        sqsEx.getMessage());
            } catch (RuntimeException e) {
                // ContextRefresher.refresh() can throw RuntimeException
                // on bean re-init failures. Per the AAP, those failures
                // must NEVER kill the scheduler; we log and continue.
                LOG.error("ContextRefresher.refresh() failed during secrets rotation poll cause={}",
                        e.getMessage(), e);
            }
        }

        private static int clamp(int value, int min, int max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        }
    }
}
