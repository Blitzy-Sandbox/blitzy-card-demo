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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.Set;

/**
 * AWS Secrets Manager rotation listener configuration per AAP &sect;0.6.4
 * (<em>"Wiring AWS Secrets Manager dynamic secret rotation without Spring
 * Boot restart"</em>) and AAP &sect;0.7.1 (<em>"All credentials fetched at
 * runtime from AWS Secrets Manager &mdash; never hardcoded or in
 * application.yml"</em>).
 *
 * <h2>Architectural role</h2>
 * <p><b>Replaces:</b> file-based plaintext credentials in the mainframe
 * {@code PARM} datasets and {@code USRSEC} VSAM cluster (where the original
 * COBOL programs read login credentials and connection settings) &mdash;
 * now AWS Secrets Manager with <em>automatic rotation</em>, plus the
 * mainframe pattern of recycling the entire CICS region to pick up new
 * credentials. The Java target instead refreshes the Spring application
 * context in-place &mdash; no ECS task restart, no dropped connections
 * beyond the HikariCP {@code maxLifetime} pool drain.</p>
 *
 * <h2>Complete rotation flow this class participates in</h2>
 * <pre>
 *   1. Secrets Manager rotation Lambda (Terraform-managed in
 *      infrastructure/terraform/secrets.tf) rotates the RDS master
 *      credentials, the JWT signing key, and the MSK SASL credentials
 *      per their configured schedules (e.g., 7 days for RDS,
 *      90 days for JWT signing keys).
 *
 *   2. On rotation completion, the Lambda publishes a notification to
 *      an SNS topic.
 *
 *   3. The Spring application's ECS task subscribes (via Terraform) an
 *      SQS queue to that SNS topic; the subscription includes a raw
 *      message delivery filter for rotation events only.
 *
 *   4. This config's {@link #pollRotationEvents()} method, annotated
 *      with {@link Scheduled @Scheduled}, polls the SQS queue every
 *      {@code carddemo.secrets.rotation-listener.poll-interval-ms}
 *      milliseconds (default 30 s).
 *
 *   5. On any received message, {@link ContextRefresher#refresh()} is
 *      invoked, which destroys and recreates all
 *      {@code @RefreshScope}-annotated beans (the HikariCP
 *      {@code DataSource} and {@code DataSourceProperties} from
 *      {@link JpaConfig}, the {@code JwtTokenProvider} from
 *      {@code security/}, and any future Kafka factory beans marked
 *      {@code @RefreshScope}). Newly-rotated secrets are then picked
 *      up on next bean access.
 *
 *   6. The SQS message is deleted from the queue ONLY after the
 *      refresh completes successfully; on refresh failure the message
 *      remains in-flight and SQS re-delivers it after the visibility
 *      timeout for retry on the next ECS task or the next poll cycle.
 * </pre>
 *
 * <h2>Profile gating</h2>
 * <p>The listener is gated by
 * {@code carddemo.secrets.rotation-listener.enabled=true} via
 * {@link ConditionalOnProperty} with {@code matchIfMissing=false}. When the
 * property is absent (default in {@code local} profile and any test
 * harness) the entire {@code @Configuration} class &mdash; including the
 * {@code @Scheduled} polling method &mdash; is excluded from the Spring
 * application context, so LocalStack-backed local development does not
 * spin up an SQS poller. Production overlays flip the flag to
 * {@code true} once the rotation Lambda + SNS topic + SQS queue are
 * deployed.</p>
 *
 * <h2>Failure semantics</h2>
 * <p>Any failure during a poll cycle (SQS receive failure, context
 * refresh failure, message delete failure) is logged at WARN or ERROR
 * level and swallowed &mdash; the {@code @Scheduled} method never
 * propagates an exception to the scheduler thread, so a transient AWS
 * failure cannot stop subsequent rotation events from being processed.
 * The next scheduled invocation retries on the configured fixed-delay
 * cadence.</p>
 *
 * <h2>Idempotency</h2>
 * <p>Multiple rotation events received in a single poll cycle (e.g., due
 * to SNS fan-out delivering duplicate notifications) trigger only one
 * {@link ContextRefresher#refresh()} batch &mdash; refreshing once is
 * sufficient because rotation events are notifications, not commands to
 * refresh per event. {@code ContextRefresher.refresh()} is itself
 * idempotent: refreshing twice in quick succession refreshes only changed
 * properties the second time (returning an empty set).</p>
 *
 * <h2>Note on Spring Cloud AWS auto-configuration</h2>
 * <p>Declarative Secrets Manager imports (via
 * {@code spring.config.import: aws-secretsmanager:...} in
 * {@code application-dev.yml} / {@code application-prod.yml}) are handled
 * by {@code spring-cloud-aws-starter-secrets-manager} auto-configuration
 * &mdash; no explicit beans are required here. This config only adds the
 * runtime rotation propagation that the auto-configuration does NOT
 * provide.</p>
 *
 * <h2>Operational constraints honored by this file</h2>
 * <ul>
 *   <li><b>No business logic</b> (AAP &sect;0.7.1, Minimal Change Clause)
 *       &mdash; this class is pure infrastructure wiring; no
 *       transactional, financial, or domain logic appears anywhere.</li>
 *   <li><b>AWS SDK v2 only</b> (AAP &sect;0.5.1) &mdash; no
 *       {@code com.amazonaws.*} imports; the SQS client is provided by
 *       {@link AwsSdkConfig} and uses
 *       {@code DefaultCredentialsProvider} (ECS task role) in
 *       {@code dev} / {@code prod} profiles.</li>
 *   <li><b>Jakarta EE only</b> &mdash; no {@code javax.*} imports.</li>
 *   <li><b>No hardcoded credentials</b> (AAP &sect;0.7.1) &mdash; the
 *       queue URL and tunable parameters are sourced from
 *       {@code application.yml} via {@link Value @Value} placeholders
 *       that themselves resolve from environment variables or AWS
 *       Systems Manager Parameter Store.</li>
 * </ul>
 *
 * <h2>Refreshed beans</h2>
 * <p>The set of beans recreated on each successful poll is determined by
 * Spring Cloud Context at runtime &mdash; every bean annotated with
 * {@code @RefreshScope} is destroyed and re-instantiated. The current
 * application includes:</p>
 * <ul>
 *   <li>{@code dataSourceProperties} ({@link JpaConfig}) &mdash; rotated
 *       RDS master username / password.</li>
 *   <li>{@code dataSource} ({@link JpaConfig}) &mdash; HikariCP pool
 *       rebuilt with the rotated credentials; existing connections drain
 *       per the {@code max-lifetime} setting.</li>
 *   <li>{@code jwtTokenProvider}
 *       ({@code com.awsm2.carddemo.security.JwtTokenProvider})
 *       &mdash; rotated JWT signing key picked up on next token issuance.</li>
 *   <li>Future {@code @RefreshScope} beans (e.g., Kafka producer /
 *       consumer factories) automatically participate.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.config.AwsSdkConfig
 * @see com.awsm2.carddemo.config.JpaConfig
 * @see com.awsm2.carddemo.security.JwtTokenProvider
 * @see <a href="https://docs.spring.io/spring-cloud/docs/current/reference/html/spring-cloud-commons/index.html#refresh-scope">
 *      Spring Cloud Commons &mdash; Refresh Scope</a>
 * @see <a href="https://docs.awspring.io/spring-cloud-aws/docs/3.2.1/reference/html/index.html#secrets-manager-integration">
 *      Spring Cloud AWS &mdash; Secrets Manager Integration</a>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "carddemo.secrets.rotation-listener.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class SecretsManagerConfig {

    /**
     * SLF4J logger used for structured JSON logging via Logback per AAP
     * &sect;0.6.6. Every poll cycle that receives messages or encounters
     * an error emits a log record so operators can correlate Secrets
     * Manager rotation events with the corresponding refresh activity in
     * CloudWatch Logs / OpenSearch.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SecretsManagerConfig.class);

    /**
     * SQS queue URL subscribed to the Secrets Manager rotation SNS topic.
     *
     * <p>Sourced from
     * {@code carddemo.secrets.rotation-listener.queue-url} in
     * {@code application.yml}, which itself defaults to the
     * {@code SECRETS_ROTATION_QUEUE_URL} environment variable supplied to
     * the ECS task definition by Terraform. An empty value indicates the
     * listener is enabled but unwired &mdash; the poll method short-
     * circuits and logs a warning so operators see the misconfiguration
     * in CloudWatch.</p>
     */
    @Value("${carddemo.secrets.rotation-listener.queue-url:}")
    private String rotationQueueUrl;

    /**
     * Maximum messages returned per SQS {@code ReceiveMessage} call (1-10
     * inclusive per the SQS API contract). Default 10 maximises batch
     * efficiency while staying within the SQS hard limit. Even when 10
     * messages are returned, {@link ContextRefresher#refresh()} is
     * invoked only once per cycle.
     */
    @Value("${carddemo.secrets.rotation-listener.max-messages:10}")
    private int maxMessagesPerPoll;

    /**
     * SQS long-poll wait time in seconds (0-20 inclusive per the SQS API
     * contract). Default 20 (the SQS maximum) minimises empty receives
     * and reduces API-call costs while still bounding worst-case
     * rotation propagation latency to
     * {@code wait-time-seconds + poll-interval-ms}. A value of 0 disables
     * long-polling (immediate return) and is suitable only for testing.
     */
    @Value("${carddemo.secrets.rotation-listener.wait-time-seconds:20}")
    private int longPollSeconds;

    /**
     * SQS visibility timeout in seconds applied to each polled message.
     * If processing (i.e., {@link ContextRefresher#refresh()} +
     * message deletion) fails before the timeout elapses, SQS re-
     * delivers the message for retry on a subsequent poll. Default 60 s
     * is generous &mdash; refresh is typically &lt;1 s, but a stalled
     * downstream (e.g., RDS slow to accept new connections) could push
     * latency higher in pathological cases.
     */
    @Value("${carddemo.secrets.rotation-listener.visibility-timeout-seconds:60}")
    private int visibilityTimeoutSeconds;

    /**
     * AWS SDK v2 SQS client used to poll for rotation notifications and
     * delete processed messages. Supplied by {@link AwsSdkConfig#sqsClient()}
     * &mdash; thread-safe singleton sharing the same credentials provider
     * (ECS task role in {@code dev}/{@code prod}, static for LocalStack
     * in {@code local}) and the same {@code ClientOverrideConfiguration}
     * (bounded timeouts + retry policy) as every other AWS SDK client in
     * this codebase.
     */
    private final SqsClient sqsClient;

    /**
     * Spring Cloud Context refresh trigger. Auto-provided by
     * {@code spring-cloud-starter-bootstrap} (declared in {@code pom.xml}
     * per AAP &sect;0.5.1). Calling {@link ContextRefresher#refresh()}
     * destroys every {@code @RefreshScope}-annotated bean and re-creates
     * it on next access; this is the canonical mechanism by which
     * rotated Secrets Manager values are propagated into the running
     * application context.
     */
    private final ContextRefresher contextRefresher;

    /**
     * Constructor injection of the AWS SDK v2 SQS client (from
     * {@link AwsSdkConfig#sqsClient()}) and the Spring Cloud Context
     * {@link ContextRefresher} (auto-provided by
     * {@code spring-cloud-starter-bootstrap}). Constructor injection is
     * preferred over field injection per AAP &sect;0.3.3 Design Pattern
     * Applications (<em>"Dependency injection for loose coupling
     * &mdash; constructor injection for all {@code @Service},
     * {@code @Repository}, {@code @Component}, adapter, and config
     * beans"</em>).
     *
     * <p><b>Replaces:</b> the COBOL pattern of opening a CICS file or
     * VSAM cluster at program start &mdash; here the AWS SDK client is
     * injected once and reused across every scheduled poll without
     * per-invocation setup cost.</p>
     *
     * @param sqsClient        the AWS SDK v2 SQS client (must not be
     *                         {@code null}; supplied by Spring DI)
     * @param contextRefresher the Spring Cloud Context refresh trigger
     *                         (must not be {@code null}; supplied by
     *                         Spring DI via
     *                         {@code spring-cloud-starter-bootstrap})
     */
    public SecretsManagerConfig(SqsClient sqsClient, ContextRefresher contextRefresher) {
        // Replaces: CICS file OPEN at program startup, but for the AWS
        // SDK + Spring Cloud Context dependencies that drive the
        // rotation propagation per AAP §0.6.4.
        this.sqsClient = sqsClient;
        this.contextRefresher = contextRefresher;
    }

    /**
     * Documentary bean exposing the names of {@code @RefreshScope}-
     * annotated beans that will be re-instantiated when this listener
     * invokes {@link ContextRefresher#refresh()}. The bean is intended
     * solely for diagnostic / observability use (e.g., a custom
     * Actuator info contributor that reports which beans are subject
     * to rotation-driven refresh) and carries NO business logic.
     *
     * <p>The list is maintained statically by deliberate cross-reference
     * with the source files that declare {@code @RefreshScope}; adding a
     * new {@code @RefreshScope} bean elsewhere in the codebase should be
     * accompanied by a corresponding addition here so operators can
     * observe the full refresh footprint at runtime. The runtime refresh
     * itself is driven by Spring Cloud Context, not by this list.</p>
     *
     * <p>Bean is qualified by an explicit name to avoid colliding with
     * any other {@code Set<String>} bean in the application context.</p>
     *
     * @return immutable set of {@code @RefreshScope} bean names known
     *         at compile time
     */
    @Bean("secretsRotationRefreshableBeans")
    public Set<String> secretsRotationRefreshableBeans() {
        // Replaces: nothing in the COBOL world — this is a Java-side
        // diagnostic affordance enabled by Spring's bean registry.
        return Set.of(
                "dataSourceProperties",  // com.awsm2.carddemo.config.JpaConfig
                "dataSource",            // com.awsm2.carddemo.config.JpaConfig
                "jwtTokenProvider"       // com.awsm2.carddemo.security.JwtTokenProvider
        );
    }

    /**
     * Polls the configured SQS queue for Secrets Manager rotation
     * notifications and, on receipt of any message, triggers a Spring
     * {@link ContextRefresher#refresh()} which destroys and recreates
     * all {@code @RefreshScope}-annotated beans with rotated
     * credentials.
     *
     * <p>The default polling interval is 30 seconds
     * ({@code carddemo.secrets.rotation-listener.poll-interval-ms=30000});
     * for faster rotation propagation, lower this value. Note that the
     * AWS SQS service supports long-polling up to 20 seconds via the
     * {@code WaitTimeSeconds} parameter (configured here by
     * {@link #longPollSeconds}); when long-polling is enabled the
     * effective worst-case rotation latency is
     * {@code wait-time-seconds + poll-interval-ms}.</p>
     *
     * <h4>Flow</h4>
     * <ol>
     *   <li>If the queue URL is blank (listener enabled but unwired),
     *       log a warning and return.</li>
     *   <li>Build and send a {@link ReceiveMessageRequest} with the
     *       configured batch size, long-poll window, and visibility
     *       timeout.</li>
     *   <li>If the response contains zero messages, return silently
     *       (the normal idle-cycle case).</li>
     *   <li>Otherwise, invoke {@link ContextRefresher#refresh()} once
     *       and log the set of changed property keys for observability.</li>
     *   <li>Iterate the received messages and delete each one from the
     *       queue via {@link SqsClient#deleteMessage(DeleteMessageRequest)}.
     *       Individual delete failures are logged but do not block the
     *       loop &mdash; SQS will redeliver the offending message after
     *       its visibility timeout, and the redelivery will trigger
     *       another (idempotent) refresh on a subsequent poll cycle.</li>
     * </ol>
     *
     * <h4>Error handling</h4>
     * <p>Any {@link RuntimeException} (including
     * {@code software.amazon.awssdk.core.exception.SdkException} family
     * for transient AWS failures) thrown during polling, refresh, or
     * deletion is caught and logged but does NOT propagate. The
     * {@code @Scheduled} infrastructure considers an exception
     * propagating from a scheduled method as a soft failure that pauses
     * subsequent invocations until the application context is
     * refreshed; this is unacceptable for an infrastructure listener
     * that must run for the lifetime of the application. By swallowing
     * exceptions explicitly here we ensure the next scheduled
     * invocation will always run.</p>
     *
     * <h4>Refresh failure semantics</h4>
     * <p>If {@link ContextRefresher#refresh()} throws, the rotation
     * messages are deliberately NOT deleted from the queue &mdash; the
     * SQS visibility timeout (default 60 s) ensures the messages re-
     * deliver to a healthy ECS task or to the same task on its next
     * poll cycle. This guarantees that a transient bean-init failure
     * does not silently consume the rotation event.</p>
     *
     * <p><b>Replaces:</b> the mainframe operations-team procedure of
     * manually issuing {@code TSO ALTER USER PASSWORD} followed by a
     * RACF refresh and CICS region recycle on every credential
     * rotation (AAP &sect;0.6.4). The Java target propagates rotation
     * with zero operator touch.</p>
     */
    @Scheduled(fixedDelayString = "${carddemo.secrets.rotation-listener.poll-interval-ms:30000}",
               initialDelayString = "${carddemo.secrets.rotation-listener.poll-interval-ms:30000}")
    public void pollRotationEvents() {
        // Replaces: TSO ALTER USER PASSWORD + manual RACF refresh + CICS
        // region recycle (per AAP §0.6.4 — Secrets Manager dynamic
        // rotation without Spring Boot restart).
        if (rotationQueueUrl == null || rotationQueueUrl.isBlank()) {
            // Configuration is incomplete (listener enabled but queue
            // URL unset). Log a single warning per cycle so the
            // misconfiguration surfaces in CloudWatch but the scheduler
            // thread continues running for any future re-configuration.
            LOG.warn("Secrets rotation listener enabled but queue-url is blank; skipping poll cycle");
            return;
        }

        try {
            // ----------------------------------------------------------
            // Step 1 — receive messages from the SQS queue.
            // ----------------------------------------------------------
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(rotationQueueUrl)
                    .maxNumberOfMessages(maxMessagesPerPoll)
                    .waitTimeSeconds(longPollSeconds)
                    .visibilityTimeout(visibilityTimeoutSeconds)
                    .build();

            ReceiveMessageResponse response = sqsClient.receiveMessage(receiveRequest);
            if (response.messages() == null || response.messages().isEmpty()) {
                // Normal idle cycle — no rotation events outstanding.
                // Intentionally silent: logging a record per empty poll
                // would flood CloudWatch on the 30 s cadence.
                return;
            }

            LOG.info("Received {} Secrets Manager rotation notification(s); "
                    + "triggering ContextRefresher.refresh()",
                    response.messages().size());

            // ----------------------------------------------------------
            // Step 2 — invoke ContextRefresher exactly once. Even if
            // SNS fan-out delivered N copies of the same rotation, the
            // context need only be refreshed once. Any RuntimeException
            // is caught locally so we can SKIP message deletion and
            // allow SQS redelivery to retry the refresh.
            // ----------------------------------------------------------
            try {
                Set<String> refreshedKeys = contextRefresher.refresh();
                int refreshedSize = (refreshedKeys == null) ? 0 : refreshedKeys.size();
                LOG.info("ContextRefresher.refresh() completed; {} property key(s) changed: {}",
                        refreshedSize, refreshedKeys);
            } catch (RuntimeException refreshException) {
                LOG.error("ContextRefresher.refresh() failed; rotation message(s) will NOT be "
                        + "deleted and SQS visibility timeout ({}s) will trigger redelivery for retry",
                        visibilityTimeoutSeconds, refreshException);
                // Skip message deletion: returning the messages to SQS
                // via the visibility-timeout mechanism is the correct
                // recovery path.
                return;
            }

            // ----------------------------------------------------------
            // Step 3 — delete each processed message individually. A
            // failure here does NOT require undoing the refresh
            // (refresh is idempotent and safe to repeat). The
            // redelivered message will trigger another refresh on a
            // subsequent poll cycle, which is acceptable behavior.
            // ----------------------------------------------------------
            for (Message message : response.messages()) {
                try {
                    DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                            .queueUrl(rotationQueueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build();
                    sqsClient.deleteMessage(deleteRequest);
                } catch (RuntimeException deleteException) {
                    LOG.warn("Failed to delete rotation message messageId={}; "
                            + "SQS will redeliver after visibility timeout ({}s)",
                            message.messageId(), visibilityTimeoutSeconds, deleteException);
                    // Continue with remaining messages — partial delete
                    // success is better than aborting the whole loop.
                }
            }
        } catch (RuntimeException pollException) {
            // Top-level catch for the SQS ReceiveMessage call and any
            // unexpected runtime error. Logged at ERROR but swallowed
            // so the scheduler thread keeps running; next fixedDelay
            // invocation retries on its own schedule.
            LOG.error("SQS rotation poll failed; will retry on next scheduled invocation",
                    pollException);
        }
    }
}
