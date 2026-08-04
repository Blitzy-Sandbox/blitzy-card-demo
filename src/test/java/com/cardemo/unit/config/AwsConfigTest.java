/*
 * ****************************************************************************
 * Program     : AwsConfigTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies the executable half of the no-live-AWS guarantee in
 *               AwsConfig: the fail-closed endpoint allow-list, the mandatory
 *               static credentials, the required .fifo queue contract and its
 *               attribute verification, the strict read-only topic resolver that
 *               never creates a topic, and the outbound correlation header.
 * Source      : app/csd/CARDDEMO.CSD:L499-505 (DEFINE TDQUEUE(JOBS) TYPE(EXTRA)
 *                 RECORDSIZE(80) RECORDFORMAT(FIXED) DISPOSITION(MOD))
 *               + app/jcl/DEFGDGB.jcl + app/jcl/DALYREJS.jcl + app/jcl/REPTFILE.jcl
 *                 (the seven GDG bases the three buckets replace)
 *               + app/cbl/CORPT00C.cbl:L517-523 (EXEC CICS WRITEQ TD)
 *               @ 7756d89
 * ****************************************************************************
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
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.config.AwsConfig;
import com.cardemo.observability.CorrelationIdFilter;
import io.awspring.cloud.sns.core.TopicArnResolver;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationRunner;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.ListTopicsRequest;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Unit tests for {@code com.cardemo.config.AwsConfig}.
 *
 * <p>The subject of every test below is one of the guarantees the migration plan states as absolute: all cloud
 * interaction targets the local emulator, zero live credentials exist, no code path reaches a live endpoint, and
 * nothing is ever provisioned from Java. Those are not properties that can be read off a configuration file -
 * an <em>absent</em> endpoint override is precisely what makes the software development kit resolve the real
 * regional endpoint - so they are asserted here against the code that enforces them.
 *
 * <p>No Spring context is started and no network call is made. The constructor is exercised directly, and the
 * two collaborators that do perform calls are driven through mocks, which is also what lets the most important
 * assertion in this file be made at all: that resolving an absent topic <strong>never</strong> reaches
 * {@code CreateTopic}.
 */
@DisplayName("AwsConfig - the executable no-live-AWS guarantee")
class AwsConfigTest {

    /** A valid emulator endpoint, used wherever the endpoint is not the subject of the test. */
    private static final String LOCAL_ENDPOINT = "http://localhost:4566";

    /** The logical queue name the base profile declares. */
    private static final String LOGICAL_QUEUE = "carddemo-report-jobs";

    /** The physical queue name, which must be the logical name plus the FIFO suffix. */
    private static final String PHYSICAL_QUEUE = LOGICAL_QUEUE + ".fifo";

    /** Clears any diagnostic context a test established, so no entry leaks onto the next test. */
    @AfterEach
    void clearDiagnosticContext() {
        MDC.clear();
    }

    /**
     * Builds an instance with every value valid except those a test overrides.
     *
     * @param s3Endpoint  the object-storage endpoint
     * @param sqsEndpoint the queue endpoint
     * @param snsEndpoint the notification endpoint
     * @param accessKey   the access key identifier
     * @param secretKey   the secret access key
     * @param queueName   the physical queue name
     * @return the constructed configuration
     */
    private static AwsConfig newConfig(final String s3Endpoint, final String sqsEndpoint,
            final String snsEndpoint, final String accessKey, final String secretKey, final String queueName) {

        return new AwsConfig("us-east-1", "carddemo-batch-input", "carddemo-batch-output",
                "carddemo-statements", queueName, LOGICAL_QUEUE, "carddemo-notifications",
                QueueNotFoundStrategy.FAIL, s3Endpoint, sqsEndpoint, snsEndpoint, NO_GLOBAL_ENDPOINT,
                accessKey, secretKey);
    }

    /**
     * The value every profile gives {@code spring.cloud.aws.endpoint}: none. The three per-service keys are
     * used instead, and the global key is bound only so that setting it cannot slip an unapproved address in
     * through a property nothing else reads.
     */
    private static final String NO_GLOBAL_ENDPOINT = "";

    /**
     * A static, non-live credential provider, which is what the customizers assert before any client is
     * built. The pair is the emulator's own {@code test}/{@code test}; no live credential exists anywhere in
     * this repository.
     *
     * @return the provider the library would resolve from the base profile
     */
    private static AwsCredentialsProvider staticCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }

    /** Builds an instance whose only variable is the object-storage endpoint. */
    private static AwsConfig withS3Endpoint(final String s3Endpoint) {
        return newConfig(s3Endpoint, LOCAL_ENDPOINT, LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE);
    }

    /** The endpoint allow-list: the control that makes the no-live-AWS guarantee executable. */
    @Nested
    @DisplayName("endpoint allow-list")
    class EndpointAllowList {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://localhost:4566",
            "https://localhost:4566",
            "http://127.0.0.1:4566",
            "http://[::1]:4566",
            "http://localstack:4566",
            "http://carddemo-localstack:4566",
            "http://localhost.localstack.cloud:4566",
        })
        @DisplayName("accepts the two loopback literals and the emulator's own documented host names")
        void acceptsEmulatorEndpoints(final String endpoint) {
            assertThat(withS3Endpoint(endpoint)).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://s3.us-east-1.amazonaws.com",
            "https://sqs.eu-west-1.amazonaws.com",
            "https://s3.amazonaws.com",
            "http://169.254.169.254",
            "http://attacker.example.com:4566",
            "http://localhost.localstack.cloud.example.com:4566",
            "http://notlocalhost:4566",
            // A sub-domain of the emulator's loopback DNS name. It resolves to loopback, so admitting it
            // would have been easy to justify on reachability grounds, and this list once did. It is refused
            // because localstack-init/init-aws.sh refuses it and records why: the bare host is the only
            // endpoint this project documents, and the virtual-hosted form was probed and found to pass
            // readiness and then fail provisioning at the queue stage, a virtual-hosted object-storage name
            // not being a general service edge. Bucket-style request addressing, the one virtual-host case
            // that matters, is derived by the SDK from the bucket name and never configured as an endpoint.
            "http://s3.localhost.localstack.cloud:4566",
            "http://carddemo-batch-output.s3.localhost.localstack.cloud:4566",
            // A DNS name that merely begins with the loopback literal, which a prefix test would admit.
            "http://127.0.0.1.attacker.example:4566",
            // A loopback address that is not 127.0.0.1. It was accepted here, by a pattern matching the whole
            // of 127.0.0.0/8 on the reasoning that Testcontainers might publish onto any of that range. It does
            // not: the harness registers whatever LOCALSTACK.getEndpoint() reports, which is localhost, and this
            // literal appeared nowhere else in the repository. Accepting it made the Java allowlist a strict
            // superset of the one localstack-init/init-aws.sh applies - the drift this class's parity test now
            // forbids - so the range pattern is gone and the two sides name the same five hosts.
            "http://127.10.20.30:4566",
        })
        @DisplayName("refuses every host outside the allow-list, including the metadata address")
        void refusesLiveEndpoints(final String endpoint) {
            assertThatThrownBy(() -> withS3Endpoint(endpoint))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint")
                    .hasMessageContaining("does not name one of the approved local emulator hosts");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ftp://localhost:4566", "file:///tmp/bucket", "tcp://localhost:4566"})
        @DisplayName("refuses any scheme other than http and https")
        void refusesForeignSchemes(final String endpoint) {
            assertThatThrownBy(() -> withS3Endpoint(endpoint))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a lowercase http or https URL");
        }

        @Test
        @DisplayName("refuses a value that names no host")
        void refusesHostlessValue() {
            assertThatThrownBy(() -> withS3Endpoint("http:///bucket"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("names no host");
        }

        @Test
        @DisplayName("refuses a syntactically invalid address and keeps its cause")
        void refusesMalformedValue() {
            assertThatThrownBy(() -> withS3Endpoint("http://local^host:4566"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a parsable URL")
                    .hasCauseInstanceOf(java.net.URISyntaxException.class);
        }

        @Test
        @DisplayName("refuses whitespace before parsing, so no such value can reach the URL parser")
        void refusesWhitespaceBearingValue() {
            // Screened on characters first, deliberately: a control character or a newline in a value that
            // reads as local can make it resolve elsewhere and can split a log line, and neither defect is
            // something a URL parser is obliged to notice. There is therefore no cause to preserve here.
            assertThatThrownBy(() -> withS3Endpoint("http://loc alhost:4566"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carries a control character, whitespace or a backslash")
                    .hasNoCause();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("refuses a blank endpoint, which resolves successfully and beats a profile default")
        void refusesBlankEndpoint(final String endpoint) {
            assertThatThrownBy(() -> withS3Endpoint(endpoint))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint");
        }

        @Test
        @DisplayName("validates all three service endpoints, not only the first")
        void validatesEveryServiceEndpoint() {
            assertThatThrownBy(() -> newConfig(LOCAL_ENDPOINT, "https://sqs.us-east-1.amazonaws.com",
                    LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.sqs.endpoint");

            assertThatThrownBy(() -> newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    "https://sns.us-east-1.amazonaws.com", "test", "test", PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.sns.endpoint");
        }

        @Test
        @DisplayName("never repeats the rejected value in the failure message")
        void withholdsTheRejectedValue() {
            assertThatThrownBy(() -> withS3Endpoint("https://s3.eu-central-1.amazonaws.com"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("amazonaws.com");
        }
    }

    /** The static-credential requirement, which is what keeps the default provider chain unreachable. */
    @Nested
    @DisplayName("mandatory static credentials")
    class StaticCredentials {

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank access key aborts startup and is never echoed")
        void blankAccessKeyAborts(final String accessKey) {
            assertThatThrownBy(() -> newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    accessKey, "test", PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.credentials.access-key");
        }

        @Test
        @DisplayName("a blank secret key aborts startup and is never echoed")
        void blankSecretKeyAborts() {
            assertThatThrownBy(() -> newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    "test", "  ", PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.credentials.secret-key")
                    .hasMessageNotContaining("test");
        }
    }

    /** The queue contract: a message group is honoured only by a first-in-first-out queue. */
    @Nested
    @DisplayName("FIFO queue contract")
    class FifoQueueContract {

        @Test
        @DisplayName("an unsuffixed physical name aborts startup instead of warning")
        void unsuffixedNameAborts() {
            assertThatThrownBy(() -> newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    "test", "test", LOGICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sqs.report-queue")
                    .hasMessageContaining(".fifo");
        }

        @Test
        @DisplayName("a physical name that is not the logical name plus the suffix aborts startup")
        void driftedNameAborts() {
            assertThatThrownBy(() -> newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    "test", "test", "some-other-queue.fifo"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sqs.report-queue-logical-name");
        }

        @Test
        @DisplayName("the startup verifier accepts a FIFO queue with content-based deduplication off")
        void verifierAcceptsCompliantQueue() throws Exception {
            SqsAsyncClient client = stubbedQueue(Map.of(
                    QueueAttributeName.FIFO_QUEUE.toString(), "true",
                    QueueAttributeName.CONTENT_BASED_DEDUPLICATION.toString(), "false"));

            ApplicationRunner verifier = compliantConfig().cardDemoFifoQueueContractVerifier(client);

            verifier.run(null);

            verify(client).getQueueAttributes(any(GetQueueAttributesRequest.class));
        }

        @Test
        @DisplayName("the startup verifier accepts a FIFO queue that omits the deduplication attribute")
        void verifierAcceptsOmittedDeduplicationAttribute() throws Exception {
            // Omission is how the service says false, so it must satisfy the requirement rather than fail it.
            SqsAsyncClient client = stubbedQueue(Map.of(
                    QueueAttributeName.FIFO_QUEUE.toString(), "true"));

            ApplicationRunner verifier = compliantConfig().cardDemoFifoQueueContractVerifier(client);

            verifier.run(null);

            verify(client).getQueueAttributes(any(GetQueueAttributesRequest.class));
        }

        @Test
        @DisplayName("the startup verifier refuses a standard queue created under a .fifo name")
        void verifierRefusesStandardQueue() {
            SqsAsyncClient client = stubbedQueue(Map.of(
                    QueueAttributeName.FIFO_QUEUE.toString(), "false",
                    QueueAttributeName.CONTENT_BASED_DEDUPLICATION.toString(), "false"));

            ApplicationRunner verifier = compliantConfig().cardDemoFifoQueueContractVerifier(client);

            assertThatThrownBy(() -> verifier.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FifoQueue")
                    .hasMessageContaining(PHYSICAL_QUEUE)
                    .hasMessageContaining("localstack-init/init-aws.sh");
        }

        @Test
        @DisplayName("the startup verifier warns about content-based deduplication but still starts")
        void verifierWarnsAboutContentBasedDeduplicationWithoutRefusingToStart() throws Exception {
            // Finding H-08, severity High. The original defect was the reverse of this: the verifier REQUIRED
            // the attribute to be true and refused a queue that omitted it, while content-based deduplication
            // hashes the message body - and a report body is just the report name plus two dates, so two
            // legitimate submissions of the same period collapsed inside the five-minute window where
            // DISPOSITION(MOD) at app/csd/CARDDEMO.CSD:503 appended both.
            //
            // The fix is on the send path, not here: ReportSubmissionService mints an explicit
            // MessageDeduplicationId per submission, and an explicit identifier takes precedence over the body
            // hash, so both submissions arrive even while this attribute is enabled. Startup therefore must NOT
            // refuse - an intermediate revision made it refuse, and that took every ApplicationContext down over
            // a mutable attribute this process does not own, including tiers that never publish a report.
            // FifoQueue is asserted instead because it is immutable from creation; this one is provisioned by
            // localstack-init/init-aws.sh and re-read continuously by HealthIndicators.
            SqsAsyncClient client = stubbedQueue(Map.of(
                    QueueAttributeName.FIFO_QUEUE.toString(), "true",
                    QueueAttributeName.CONTENT_BASED_DEDUPLICATION.toString(), "true"));

            Logger verifierLogger = (Logger) LoggerFactory.getLogger(AwsConfig.class);
            ListAppender<ILoggingEvent> captured = new ListAppender<>();
            captured.start();
            verifierLogger.addAppender(captured);
            try {
                ApplicationRunner verifier = compliantConfig().cardDemoFifoQueueContractVerifier(client);

                assertThatNoException()
                        .as("an enabled content hash must not abort startup: the explicit deduplication "
                                + "identifier on every send already guarantees the append parity")
                        .isThrownBy(() -> verifier.run(null));
            } finally {
                verifierLogger.detachAppender(captured);
                captured.stop();
            }

            assertThat(captured.list)
                    .as("the drift is still reported, and at WARN so it is not lost among the informational "
                            + "startup lines")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains(QueueAttributeName.CONTENT_BASED_DEDUPLICATION.toString())
                                .contains("localstack-init/init-aws.sh");
                    });
        }

        @Test
        @DisplayName("the startup verifier reports a failed call without disclosing the queue address")
        void verifierReportsFailureSafely() {
            SqsAsyncClient client = mock(SqsAsyncClient.class);
            when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(
                            "https://sqs.us-east-1.amazonaws.com/123456789012/carddemo-report-jobs.fifo")));

            ApplicationRunner verifier = compliantConfig().cardDemoFifoQueueContractVerifier(client);

            assertThatThrownBy(() -> verifier.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("java.lang.IllegalStateException")
                    .hasMessageNotContaining("123456789012");
        }

        /** Builds a fully valid configuration. */
        private AwsConfig compliantConfig() {
            return newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE);
        }

        /** Stubs a queue client that resolves the queue and answers with the given attributes. */
        private static SqsAsyncClient stubbedQueue(final Map<String, String> attributes) {
            SqsAsyncClient client = mock(SqsAsyncClient.class);
            when(client.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(CompletableFuture.completedFuture(
                    GetQueueUrlResponse.builder()
                            .queueUrl("http://localhost:4566/000000000000/" + PHYSICAL_QUEUE)
                            .build()));
            when(client.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(GetQueueAttributesResponse.builder()
                            .attributesWithStrings(attributes)
                            .build()));
            return client;
        }
    }

    /** The strict topic resolver: the control that stops a named send from provisioning a topic. */
    @Nested
    @DisplayName("strict topic resolver")
    class StrictTopicResolver {

        /** A provisioned topic identifier, with the emulator's own account placeholder. */
        private static final String PROVISIONED =
                "arn:aws:sns:us-east-1:000000000000:carddemo-notifications";

        @Test
        @DisplayName("resolves a provisioned bare name without ever creating a topic")
        void resolvesProvisionedName() {
            SnsClient client = mock(SnsClient.class);
            when(client.listTopics(any(ListTopicsRequest.class))).thenReturn(ListTopicsResponse.builder()
                    .topics(Topic.builder().topicArn(PROVISIONED).build())
                    .build());

            Arn resolved = resolver(client).resolveTopicArn("carddemo-notifications");

            assertThat(resolved.toString()).isEqualTo(PROVISIONED);
            verify(client, never()).createTopic(any(CreateTopicRequest.class));
        }

        @Test
        @DisplayName("refuses an unprovisioned name and still never creates a topic")
        void refusesUnprovisionedName() {
            SnsClient client = mock(SnsClient.class);
            when(client.listTopics(any(ListTopicsRequest.class))).thenReturn(ListTopicsResponse.builder()
                    .topics(Topic.builder().topicArn(PROVISIONED).build())
                    .build());

            TopicArnResolver resolver = resolver(client);

            assertThatThrownBy(() -> resolver.resolveTopicArn("carddemo-notifcations"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not provisioned")
                    .hasMessageContaining("carddemo.aws.sns.notification-topic");
            verify(client, never()).createTopic(any(CreateTopicRequest.class));
        }

        @Test
        @DisplayName("accepts an already-resolved identifier without listing anything")
        void acceptsResolvedIdentifier() {
            SnsClient client = mock(SnsClient.class);

            assertThat(resolver(client).resolveTopicArn(PROVISIONED).toString()).isEqualTo(PROVISIONED);

            verify(client, never()).listTopics(any(ListTopicsRequest.class));
            verify(client, never()).createTopic(any(CreateTopicRequest.class));
        }

        @Test
        @DisplayName("walks paginated results and stops when the token runs out")
        void walksPagination() {
            SnsClient client = mock(SnsClient.class);
            when(client.listTopics(any(ListTopicsRequest.class)))
                    .thenReturn(ListTopicsResponse.builder()
                            .topics(Topic.builder().topicArn(
                                    "arn:aws:sns:us-east-1:000000000000:something-else").build())
                            .nextToken("page-2")
                            .build())
                    .thenReturn(ListTopicsResponse.builder()
                            .topics(Topic.builder().topicArn(PROVISIONED).build())
                            .build());

            assertThat(resolver(client).resolveTopicArn("carddemo-notifications").toString())
                    .isEqualTo(PROVISIONED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("refuses a blank destination")
        void refusesBlankDestination(final String destination) {
            TopicArnResolver resolver = resolver(mock(SnsClient.class));

            assertThatThrownBy(() -> resolver.resolveTopicArn(destination))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("created from Java");
        }

        @Test
        @DisplayName("refuses a null destination")
        void refusesNullDestination() {
            TopicArnResolver resolver = resolver(mock(SnsClient.class));

            assertThatThrownBy(() -> resolver.resolveTopicArn(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** Builds the resolver under test. */
        private TopicArnResolver resolver(final SnsClient client) {
            return newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE)
                    .provisionedTopicArnResolver(client);
        }
    }

    /** The outbound correlation header: the process-boundary half of the thread of identity. */
    @Nested
    @DisplayName("outbound correlation propagation")
    class OutboundCorrelation {

        @Test
        @DisplayName("copies a well-formed correlation identifier onto the outbound request")
        void addsHeaderWhenPresent() {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "abc-123_XYZ");

            SdkHttpRequest modified = interceptOnce();

            assertThat(modified.firstMatchingHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .contains("abc-123_XYZ");
        }

        @Test
        @DisplayName("adds no header when no correlation identifier is in scope")
        void addsNoHeaderWhenAbsent() {
            SdkHttpRequest modified = interceptOnce();

            assertThat(modified.firstMatchingHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEmpty();
        }

        @Test
        @DisplayName("adds no header for a value carrying a line terminator, so no header can be injected")
        void refusesHeaderInjection() {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "abc\r\nX-Injected: yes");

            SdkHttpRequest modified = interceptOnce();

            assertThat(modified.firstMatchingHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEmpty();
        }

        /**
         * Runs whichever interceptor the customizers registered over one request and returns the result.
         *
         * <p>The interceptor is reached through the same beans the library would use, so this asserts the
         * wiring as well as the behaviour: a configuration that failed to register it would leave the list
         * empty and fail here.
         *
         * <p><strong>Two customizer beans of the same type, not one.</strong> The credential assertion and
         * the deadline policy live on {@code cardDemoS3ClientCustomizer}; the correlation interceptor lives
         * on {@code correlationIdS3ClientCustomizer}. The library collects every bean of the customizer type
         * and applies all of them, so both are applied here. Applying only the first would assert that the
         * interceptor is registered somewhere it deliberately is not, and each customizer reaches the
         * existing configuration through {@code toBuilder()}, so neither can erase the other and the order
         * between them does not matter.
         *
         * @return the possibly-modified request
         */
        private SdkHttpRequest interceptOnce() {
            SdkHttpRequest request = SdkHttpRequest.builder()
                    .method(SdkHttpMethod.PUT)
                    .uri(URI.create(LOCAL_ENDPOINT + "/carddemo-batch-output/key"))
                    .build();

            var builder = software.amazon.awssdk.services.s3.S3Client.builder();
            AwsConfig config =
                    newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE);
            config.cardDemoS3ClientCustomizer(staticCredentials()).customize(builder);
            config.correlationIdS3ClientCustomizer().customize(builder);

            var interceptors = builder.overrideConfiguration().executionInterceptors();
            assertThat(interceptors).isNotEmpty();

            SdkHttpRequest current = request;
            for (var interceptor : interceptors) {
                current = interceptor.modifyHttpRequest(new StubContext(current), new ExecutionAttributes());
            }
            return current;
        }
    }

    /** The deadline and retry policy, which every writer relies on because none can override it per request. */
    @Nested
    @DisplayName("bounded deadline and retry policy")
    class BoundedPolicy {

        @Test
        @DisplayName("applies a whole-call deadline, a per-attempt deadline and a bounded retry strategy")
        void appliesExplicitDeadlines() {
            var builder = software.amazon.awssdk.services.s3.S3Client.builder();

            newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE)
                    .cardDemoS3ClientCustomizer(staticCredentials())
                    .customize(builder);

            var configuration = builder.overrideConfiguration();
            assertThat(configuration.apiCallTimeout()).isPresent();
            assertThat(configuration.apiCallAttemptTimeout()).isPresent();
            assertThat(configuration.apiCallAttemptTimeout().orElseThrow())
                    .isLessThan(configuration.apiCallTimeout().orElseThrow());
            // retryStrategy(RetryMode) records the MODE, and the SDK resolves the bounded strategy from it
            // when the client is built - so retryMode() is what carries the choice, and retryStrategy() is
            // empty until then. Verified against the compiled builder rather than assumed.
            assertThat(configuration.retryMode()).contains(software.amazon.awssdk.core.retry.RetryMode.STANDARD);
        }

        @Test
        @DisplayName("applies the same policy to the queue and notification clients")
        void appliesToEveryClient() {
            AwsConfig config =
                    newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE);

            // Both customizer beans per service, as the library applies them - see interceptOnce() for why
            // the interceptor is not on the same bean as the deadlines.
            var sqsBuilder = software.amazon.awssdk.services.sqs.SqsAsyncClient.builder();
            config.cardDemoSqsAsyncClientCustomizer(staticCredentials()).customize(sqsBuilder);
            config.correlationIdSqsAsyncClientCustomizer().customize(sqsBuilder);
            var snsBuilder = software.amazon.awssdk.services.sns.SnsClient.builder();
            config.cardDemoSnsClientCustomizer(staticCredentials()).customize(snsBuilder);
            config.correlationIdSnsClientCustomizer().customize(snsBuilder);

            assertThat(Stream.of(sqsBuilder.overrideConfiguration(), snsBuilder.overrideConfiguration()))
                    .allSatisfy(configuration -> {
                        assertThat(configuration.apiCallTimeout()).isPresent();
                        assertThat(configuration.apiCallAttemptTimeout()).isPresent();
                        assertThat(configuration.executionInterceptors()).isNotEmpty();
                    });
        }
    }

    /**
     * The recovery interceptor is registered on the queue client and on that client only.
     *
     * <p>It exists because {@code SqsTemplate} chains a send behind an asynchronous queue resolution and, on a
     * cold cache, issues that send from an SDK completion thread with no diagnostic context - a shape no other
     * client has. Registering it on the object-storage or notification client would put operation-aware code on
     * a path that does not need it, so the scoping is asserted rather than assumed: two interceptor types on the
     * queue builder, one on each of the other two.
     */
    @Nested
    @DisplayName("the queue-send correlation recovery interceptor is registered on the queue client only")
    class QueueSendRecoveryRegistration {

        /**
         * Collects the interceptor types a customizer pair installs on one builder.
         *
         * @param builder    the client builder under test
         * @param customized the customization to apply, as the library would
         * @return the simple names of the interceptor types registered, in registration order
         */
        private List<String> interceptorTypesOn(final software.amazon.awssdk.core.client.builder
                .SdkClientBuilder<?, ?> builder, final Runnable customized) {
            customized.run();
            return builder.overrideConfiguration().executionInterceptors().stream()
                    .map(interceptor -> interceptor.getClass().getSimpleName())
                    .toList();
        }

        @Test
        @DisplayName("the queue client gets both interceptors and the other two get only the uniform one")
        void theQueueClientGetsBothInterceptors() {
            final AwsConfig config =
                    newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE);

            var sqsBuilder = software.amazon.awssdk.services.sqs.SqsAsyncClient.builder();
            var s3Builder = software.amazon.awssdk.services.s3.S3Client.builder();
            var snsBuilder = software.amazon.awssdk.services.sns.SnsClient.builder();

            assertThat(interceptorTypesOn(sqsBuilder,
                    () -> config.correlationIdSqsAsyncClientCustomizer().customize(sqsBuilder)))
                    .as("the uniform interceptor first, so a value from the diagnostic context wins, then the "
                            + "recovery interceptor for the cold-cache send the first one cannot reach")
                    .containsExactly("CorrelationIdExecutionInterceptor",
                            "SqsSendCorrelationRecoveryInterceptor");

            assertThat(interceptorTypesOn(s3Builder,
                    () -> config.correlationIdS3ClientCustomizer().customize(s3Builder)))
                    .as("an object write is issued from the thread that asked for it, so there is nothing to "
                            + "recover and no reason to inspect a modelled request here")
                    .containsExactly("CorrelationIdExecutionInterceptor");

            assertThat(interceptorTypesOn(snsBuilder,
                    () -> config.correlationIdSnsClientCustomizer().customize(snsBuilder)))
                    .containsExactly("CorrelationIdExecutionInterceptor");
        }

        @Test
        @DisplayName("registering the pair preserves the deadlines the other customizer applied")
        void registeringThePairPreservesTheDeadlines() {
            final AwsConfig config =
                    newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE);

            var sqsBuilder = software.amazon.awssdk.services.sqs.SqsAsyncClient.builder();
            config.cardDemoSqsAsyncClientCustomizer(staticCredentials()).customize(sqsBuilder);
            config.correlationIdSqsAsyncClientCustomizer().customize(sqsBuilder);

            var configuration = sqsBuilder.overrideConfiguration();
            assertThat(configuration.executionInterceptors())
                    .as("adding two interceptors through the Consumer overload would have started from a "
                            + "fresh configuration and silently discarded the deadlines below")
                    .hasSize(2);
            assertThat(configuration.apiCallTimeout()).isPresent();
            assertThat(configuration.apiCallAttemptTimeout()).isPresent();
        }
    }

    /** The bucket-distinctness assertion, which stops a job from overwriting its own input. */
    @Test
    @DisplayName("refuses two roles pointing at one bucket")
    void refusesSharedBucket() {
        assertThatThrownBy(() -> new AwsConfig("us-east-1", "shared", "shared", "carddemo-statements",
                PHYSICAL_QUEUE, LOGICAL_QUEUE, "carddemo-notifications", QueueNotFoundStrategy.FAIL,
                LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, NO_GLOBAL_ENDPOINT, "test", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("three distinct buckets");
    }

    /** The missing-queue strategy guard, which keeps an absent queue from being created on first send. */
    @Test
    @DisplayName("refuses an unresolved missing-queue strategy")
    void refusesUnresolvedStrategy() {
        assertThatThrownBy(() -> new AwsConfig("us-east-1", "carddemo-batch-input", "carddemo-batch-output",
                "carddemo-statements", PHYSICAL_QUEUE, LOGICAL_QUEUE, "carddemo-notifications", null,
                LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, NO_GLOBAL_ENDPOINT, "test", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.cloud.aws.sqs.queue-not-found-strategy");
    }

    /**
     * The minimum interception context: a request and nothing else.
     *
     * <p>Only {@code httpRequest()} is consulted by the interceptor under test, so the remaining accessors are
     * left to the interface's own defaults rather than stubbed with values that would imply they are read.
     *
     * @param httpRequest the request being intercepted
     */
    private record StubContext(SdkHttpRequest httpRequest) implements Context.ModifyHttpRequest {

        @Override
        public software.amazon.awssdk.core.SdkRequest request() {
            throw new UnsupportedOperationException("the correlation interceptor never reads the SDK request");
        }

        @Override
        public java.util.Optional<software.amazon.awssdk.core.async.AsyncRequestBody> asyncRequestBody() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<software.amazon.awssdk.core.sync.RequestBody> requestBody() {
            return java.util.Optional.empty();
        }
    }
}
