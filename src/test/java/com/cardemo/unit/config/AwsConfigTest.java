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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.config.AwsConfig;
import com.cardemo.observability.CorrelationIdFilter;
import io.awspring.cloud.sns.core.TopicArnResolver;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.support.converter.MessagingMessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.support.MessageBuilder;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import java.lang.reflect.Method;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
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
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

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
            // would be easy to justify on reachability grounds. It is refused
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

    /**
     * The one payload contract both ends of the queue share.
     *
     * <p>Finding C-01, severity Blocker. The library's default converter writes a payload type header on
     * send and resolves it with {@code Class.forName} on receive, which broke this application's own contract
     * - a typed publish against a listener that binds text - and handed a caller control over which class was
     * loaded. The converter declared by {@code AwsConfig} neither writes nor reads that header, and because
     * the same instance is injected into the publisher and into the listener container the two cannot drift.
     */
    @Nested
    @DisplayName("raw-JSON queue payload contract")
    class RawJsonPayloadContract {

        /** The converter under test, built exactly as the container builds it. */
        private final MessagingMessageConverter<Message> converter =
                newConfig(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, "test", "test", PHYSICAL_QUEUE)
                        .sqsMessagingMessageConverter(new NoObjectMapperProvider());

        @Test
        @DisplayName("an outbound message carries no payload type header for a caller to choose")
        void anOutboundMessageCarriesNoPayloadTypeHeader() {
            final Message converted = converter.fromMessagingMessage(
                    MessageBuilder.withPayload(new SubmissionShape("Monthly", "2022-07-01")).build());

            assertThat(converted.messageAttributes())
                    .as("the type header is what made the producer's message unreadable by a listener that "
                            + "binds text, and what let a publisher name the class this application loads")
                    .doesNotContainKey("JavaType");
            assertThat(converted.body())
                    .as("the body is still the typed JSON the batch tier consumes")
                    .contains("Monthly");
        }

        @Test
        @DisplayName("an inbound payload type header is ignored, so no caller-selected class is resolved")
        void anInboundPayloadTypeHeaderIsIgnored() {
            final Message hostile = Message.builder()
                    .messageId("11111111-2222-3333-4444-555555555555")
                    .receiptHandle("receipt")
                    .body("{\"reportName\":\"Monthly\",\"startDate\":\"2022-07-01\"}")
                    .messageAttributes(java.util.Map.of("JavaType", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue("java.net.URLClassLoader")
                            .build()))
                    .build();

            final org.springframework.messaging.Message<?> bound = converter.toMessagingMessage(hostile);

            assertThat(bound.getPayload())
                    .as("the body reaches the listener as text; the class a publisher named is never loaded")
                    .isInstanceOf(String.class);
        }

        /** A payload shape standing in for the published record, so this test needs no production type. */
        private record SubmissionShape(String reportName, String startDate) {
        }

        /** An {@link ObjectProvider} that supplies no object mapper, which the bean must tolerate. */
        private static final class NoObjectMapperProvider
                implements ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper> {

            @Override
            public com.fasterxml.jackson.databind.ObjectMapper getObject() {
                throw new UnsupportedOperationException("no object mapper is contributed by this test");
            }

            @Override
            public com.fasterxml.jackson.databind.ObjectMapper getObject(final Object... args) {
                throw new UnsupportedOperationException("no object mapper is contributed by this test");
            }

            @Override
            public com.fasterxml.jackson.databind.ObjectMapper getIfAvailable() {
                return null;
            }

            @Override
            public com.fasterxml.jackson.databind.ObjectMapper getIfUnique() {
                return null;
            }
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
        @DisplayName("no ApplicationRunner is declared here, so nothing in this class touches the network at startup")
        void noApplicationRunnerIsDeclared() {
            // Finding CFG-001, severity High. No cardDemoFifoQueueContractVerifier may be declared here - an
            // ApplicationRunner that resolved the queue and read its attributes straight after context refresh
            // would put startup network traffic in a configuration class. This one assertion is
            // stated as the property that actually matters: the configuration class performs NO startup network
            // traffic at all, so a bean here can never be broken by an emulator being down and no eager call
            // can precede the endpoint allow-list. The FIFO attributes are provisioned by
            // localstack-init/init-aws.sh, which converges an existing queue, and re-read on every readiness
            // probe by HealthIndicators, which is asserted in HealthIndicatorsTest - two mechanisms that can
            // act on a divergence rather than merely report one, and that keep reporting it after startup.
            //
            // Declared-method reflection rather than a text search, so that a runner added under any bean name
            // or through any annotation fails this immediately.
            assertThat(Stream.of(AwsConfig.class.getDeclaredMethods())
                    .filter(method -> ApplicationRunner.class.isAssignableFrom(method.getReturnType())
                            || CommandLineRunner.class.isAssignableFrom(method.getReturnType()))
                    .map(Method::getName))
                    .as("the AAP's contract for this class allows no runner and no Java-side provisioning; "
                            + "a startup check belongs in provisioning or in readiness, both of which own it")
                    .isEmpty();
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

    /**
     * Service-specific resource-name validation, mirroring {@code localstack-init/init-aws.sh} check for
     * check.
     *
     * <p><strong>The finding these cover.</strong> The constructor previously proved only that each name was
     * non-blank. A name such as {@code invalid/name}, {@code Carddemo_Batch_Input} or {@code ab} therefore
     * bound cleanly, every client was built, the context refreshed and readiness answered
     * {@code 200 UP} - and the failure arrived at the first object write or report submission as an SDK
     * rejection reported as a storage or messaging fault rather than as the configuration fault it was.
     * The provisioning script refused the same values with exit code 4, so the two guards disagreed and the
     * pair was worse than either alone.
     *
     * <p>Each case below is an <em>independently rejected shape</em>, not a variation on one. A single
     * "obviously bad name" case would pass against a guard that only checked, say, the leading character.
     */
    @Nested
    @DisplayName("resource-name shape validation")
    class ResourceNameShapeValidation {

        /** Builds the configuration with one bucket name replaced, every other value well formed. */
        private AwsConfig withInputBucket(final String inputBucket) {
            return new AwsConfig("us-east-1", inputBucket, "carddemo-batch-output",
                    "carddemo-statements", PHYSICAL_QUEUE, LOGICAL_QUEUE, "carddemo-notifications",
                    QueueNotFoundStrategy.FAIL, LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    NO_GLOBAL_ENDPOINT, "test", "test");
        }

        /** Builds the configuration with the logical and physical queue names derived from one logical name. */
        private AwsConfig withLogicalQueue(final String logicalQueue) {
            return new AwsConfig("us-east-1", "carddemo-batch-input", "carddemo-batch-output",
                    "carddemo-statements", logicalQueue + ".fifo", logicalQueue, "carddemo-notifications",
                    QueueNotFoundStrategy.FAIL, LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    NO_GLOBAL_ENDPOINT, "test", "test");
        }

        /** Builds the configuration with one topic name replaced, every other value well formed. */
        private AwsConfig withTopic(final String topic) {
            return new AwsConfig("us-east-1", "carddemo-batch-input", "carddemo-batch-output",
                    "carddemo-statements", PHYSICAL_QUEUE, LOGICAL_QUEUE, topic,
                    QueueNotFoundStrategy.FAIL, LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    NO_GLOBAL_ENDPOINT, "test", "test");
        }

        /** Builds the configuration with one region replaced, every other value well formed. */
        private AwsConfig withRegion(final String region) {
            return new AwsConfig(region, "carddemo-batch-input", "carddemo-batch-output",
                    "carddemo-statements", PHYSICAL_QUEUE, LOGICAL_QUEUE, "carddemo-notifications",
                    QueueNotFoundStrategy.FAIL, LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    NO_GLOBAL_ENDPOINT, "test", "test");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "invalid/name",
            "Carddemo-Batch-Input",
            "carddemo_batch_input",
            "-carddemo-batch-input",
            "carddemo-batch-input-",
            ".carddemo-batch-input",
            "carddemo batch input",
            "carddemo-batch-input:1",
            "ab",
        })
        @DisplayName("refuses every bucket name the provisioner refuses, naming the property not the value")
        void refusesInvalidBucketNames(final String bucketName) {
            assertThatThrownBy(() -> withInputBucket(bucketName))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.s3.batch-input-bucket");
        }

        @Test
        @DisplayName("refuses a bucket name over the 63-character AWS ceiling without quoting it")
        void refusesAnOverlongBucketName() {
            final String overlong = "a".repeat(64);

            assertThatThrownBy(() -> withInputBucket(overlong))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.s3.batch-input-bucket")
                    .hasMessageContaining("3-63")
                    // The length branch reports the measured length and deliberately does NOT echo the
                    // value: its length is what is wrong, not its content.
                    .hasMessageNotContaining(overlong);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "carddemo-batch-input",
            "carddemo.batch.input",
            "abc",
            "a1b",
            "1carddemo-input2",
        })
        @DisplayName("accepts every bucket name the provisioner accepts")
        void acceptsValidBucketNames(final String bucketName) {
            assertThat(withInputBucket(bucketName)).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "carddemo.report.jobs",
            "carddemo report jobs",
            "carddemo/report-jobs",
            "carddemo:report-jobs",
        })
        @DisplayName("refuses a queue logical name outside the character class SQS accepts")
        void refusesInvalidQueueNames(final String logicalQueue) {
            assertThatThrownBy(() -> withLogicalQueue(logicalQueue))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sqs.report-queue-logical-name");
        }

        @Test
        @DisplayName("accepts the underscore and mixed case the queue character class permits")
        void acceptsPermittedQueueNameCharacters() {
            assertThat(withLogicalQueue("CardDemo_report-JOBS_1")).isNotNull();
        }

        @Test
        @DisplayName("refuses a logical queue name whose DERIVED dead-letter companion breaches the ceiling")
        void refusesALogicalQueueNameThatBreaksTheDeadLetterCeiling() {
            // 72 characters: inside the 75 the main queue's own ceiling allows once '.fifo' is appended,
            // and outside the 71 the derived '-dlq.fifo' companion allows. Exactly the gap in which the
            // provisioner creates the report queue and then fails to create its quarantine target,
            // leaving a working queue with nowhere to quarantine an unprocessable submission.
            final String tooLongForItsDlq = "q".repeat(72);

            assertThatThrownBy(() -> withLogicalQueue(tooLongForItsDlq))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sqs.report-queue-logical-name")
                    .hasMessageContaining("-dlq.fifo")
                    .hasMessageContaining("at most 71");
        }

        @Test
        @DisplayName("accepts a logical queue name at exactly the 71-character dead-letter ceiling")
        void acceptsALogicalQueueNameAtTheDeadLetterCeiling() {
            assertThat(withLogicalQueue("q".repeat(71))).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "carddemo.notifications",
            "carddemo notifications",
            "arn:aws:sns:us-east-1:000000000000:carddemo-notifications",
        })
        @DisplayName("refuses a topic name outside the character class SNS accepts, including an ARN")
        void refusesInvalidTopicNames(final String topic) {
            assertThatThrownBy(() -> withTopic(topic))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sns.notification-topic");
        }

        @Test
        @DisplayName("accepts a topic name at the 256-character SNS ceiling and refuses one beyond it")
        void enforcesTheTopicNameCeiling() {
            assertThat(withTopic("t".repeat(256))).isNotNull();

            assertThatThrownBy(() -> withTopic("t".repeat(257)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1-256");
        }

        @ParameterizedTest
        @ValueSource(strings = {"US-EAST-1", "us_east_1", "us east 1", "u"})
        @DisplayName("refuses a region code outside the shape every AWS region code has")
        void refusesInvalidRegions(final String region) {
            assertThatThrownBy(() -> withRegion(region))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.region.static");
        }

        @Test
        @DisplayName("accepts the region codes the profiles actually use")
        void acceptsValidRegions() {
            assertThat(withRegion("us-east-1")).isNotNull();
            assertThat(withRegion("eu-west-2")).isNotNull();
        }

        @Test
        @DisplayName("the name guard runs BEFORE the distinctness guard, so the more specific fault is "
                + "reported")
        void theShapeGuardPrecedesTheDistinctnessGuard() {
            // Two invalid names that are also identical. Reporting 'not distinct' here would send a reader
            // to rename one of them when in fact neither is a legal bucket name at all.
            assertThatThrownBy(() -> new AwsConfig("us-east-1", "Shared/Bucket", "Shared/Bucket",
                    "carddemo-statements", PHYSICAL_QUEUE, LOGICAL_QUEUE, "carddemo-notifications",
                    QueueNotFoundStrategy.FAIL, LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    NO_GLOBAL_ENDPOINT, "test", "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a valid name")
                    .hasMessageNotContaining("three distinct buckets");
        }

        @Test
        @DisplayName("the two derived queue ceilings are the 75 and 71 the documentation states")
        void theDerivedQueueCeilingsMatchTheDocumentedFigures() throws Exception {
            // Both constants are DERIVED - 80 less the FIFO suffix, and 80 less the derived dead-letter
            // suffix - so they cannot drift from the strings they depend on. That is also why their Javadoc
            // states the figures as prose rather than through {@value}: a length taken from a string is a
            // method call, not a compile-time constant, and doclint renders only the latter. This assertion
            // is what stops the PROSE drifting instead, which is the failure mode the derivation created.
            final java.lang.reflect.Field mainCeiling =
                    AwsConfig.class.getDeclaredField("QUEUE_LOGICAL_NAME_MAX_LENGTH");
            mainCeiling.setAccessible(true);
            final java.lang.reflect.Field dlqCeiling =
                    AwsConfig.class.getDeclaredField("QUEUE_LOGICAL_NAME_DLQ_SAFE_MAX_LENGTH");
            dlqCeiling.setAccessible(true);

            assertThat(mainCeiling.getInt(null))
                    .as("80 characters AWS allows a queue name, less the five of '.fifo' the physical name "
                            + "must carry. localstack-init/init-aws.sh validates the logical name against "
                            + "the same 1-75 range")
                    .isEqualTo(75);
            assertThat(dlqCeiling.getInt(null))
                    .as("80 less the nine of the derived '-dlq.fifo' companion. The provisioner states the "
                            + "same arithmetic and refuses a report-queue name above 71")
                    .isEqualTo(71);
        }

        @Test
        @DisplayName("the endpoint allow-list still runs first, so a live endpoint is refused even when a "
                + "name is also invalid")
        void theEndpointGuardStillRunsFirst() {
            assertThatThrownBy(() -> new AwsConfig("us-east-1", "Invalid/Bucket", "carddemo-batch-output",
                    "carddemo-statements", PHYSICAL_QUEUE, LOGICAL_QUEUE, "carddemo-notifications",
                    QueueNotFoundStrategy.FAIL, "https://s3.amazonaws.com", LOCAL_ENDPOINT,
                    LOCAL_ENDPOINT, NO_GLOBAL_ENDPOINT, "test", "test"))
                    .isInstanceOf(IllegalStateException.class)
                    // The no-live-AWS guarantee is the strongest constraint in this class and must not be
                    // displaced by a name check added later.
                    .hasMessageNotContaining("is not a valid name");
        }
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
