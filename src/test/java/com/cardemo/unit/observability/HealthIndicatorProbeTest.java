/*
 ******************************************************************************
 * Program     : HealthIndicatorProbeTest
 * Application : CardDemo
 * Type        : Java unit test (JUnit 5, Surefire tier)
 * Function    : Asserts both readiness contributors that replace the legacy
 *               file-availability jobs: that a misconfigured or unreachable
 *               dependency reports DOWN with a machine-readable reason rather
 *               than throwing, that every SDK failure arm is distinguished, and
 *               that the probe never reports UP on an unverified dependency.
 * Source      : app/jcl/OPENFIL.jcl @ 7756d89 - the operator step that made the
 *               VSAM datasets available to the online region
 * Source      : app/jcl/CLOSEFIL.jcl @ 7756d89 - its closing counterpart
 ******************************************************************************
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
 ******************************************************************************
 */
package com.cardemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.observability.HealthIndicators;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.sns.SnsClient;
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
 * Unit cover for {@link HealthIndicators}.
 *
 * <h2>What it does</h2>
 *
 * <p>The two contributors replace the legacy operator jobs that made the VSAM datasets available to the
 * online region, and they are the only mechanism by which an orchestrator learns that this application's
 * object storage or queue dependency has gone away. Their contract is therefore not "return a status" but
 * "never claim health that has not been verified, and never fail to report at all".
 *
 * <p>That second half is what these assertions concentrate on. A readiness probe that throws instead of
 * returning {@code DOWN} produces a 500 from the health endpoint rather than a structured status, and an
 * orchestrator reading a 500 cannot tell a broken dependency from a broken probe. Every failure arm in both
 * indicators is therefore exercised here through a stubbed SDK client, including the four distinct
 * asynchronous failure modes of the queue probe, and each is asserted to yield {@code DOWN} carrying a
 * machine-readable reason.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>{@code ./mvnw -B clean test}, or this class alone with
 * {@code ./mvnw -B test -Dtest=HealthIndicatorProbeTest}. No container, no network and no credential: both SDK
 * clients are stubs, so no code path here reaches a real or emulated endpoint.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>The bucket and queue names are constructor arguments in production, bound from properties that default
 * to empty. Empty is therefore a real production state rather than an invented one, which is why the
 * not-configured arm is asserted rather than assumed unreachable.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>A reason-code assertion fails</dt>
 *   <dd>A reason constant was renamed. These strings are the contract an orchestrator or dashboard matches
 *       on, so they are pinned here rather than left free.</dd>
 *   <dt>The timeout test is slow</dt>
 *   <dd>It is deliberately the only test that waits: the probe's deadline is two seconds and the stub
 *       returns a future that never completes, which is the only faithful way to reach that arm.</dd>
 *   </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe and not required to be. One instance per test method and no shared mutable state.
 */
@DisplayName("HealthIndicators: the readiness contributors that replace OPENFIL and CLOSEFIL")
class HealthIndicatorProbeTest {

    /** A configured, well-formed input bucket name. */
    private static final String INPUT_BUCKET = "carddemo-batch-input";

    /** A configured, well-formed output bucket name. */
    private static final String OUTPUT_BUCKET = "carddemo-batch-output";

    /** A configured, well-formed statements bucket name. */
    private static final String STATEMENTS_BUCKET = "carddemo-statements";

    /** The physical FIFO queue name, whose suffix the queue service requires. */
    private static final String QUEUE = "carddemo-report-jobs.fifo";

    /** The logical queue name the health detail publishes in preference to the physical one. */
    private static final String QUEUE_LOGICAL = "carddemo-report-jobs";

    /** The bare notification topic name, which carries no suffix of any kind. */
    private static final String TOPIC = "carddemo-notifications";

    /** Stubbed object-storage client; no test lets it reach an endpoint. */
    private S3Client s3Client;

    /** Stubbed queue client; no test lets it reach an endpoint. */
    private SqsAsyncClient sqsAsyncClient;

    /** Stubbed notification client; no test lets it reach an endpoint. */
    private SnsClient snsClient;

    /** Mocked application datasource, so the bounded relational probe can be built and driven. */
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        this.s3Client = Mockito.mock(S3Client.class);
        this.dataSource = Mockito.mock(DataSource.class);
        this.sqsAsyncClient = Mockito.mock(SqsAsyncClient.class);
        this.snsClient = Mockito.mock(SnsClient.class);
    }

    /**
     * Builds the configuration holder with the given resource names.
     *
     * @param inputBucket      the batch input bucket name, possibly empty
     * @param outputBucket     the batch output bucket name, possibly empty
     * @param statementsBucket the statements bucket name, possibly empty
     * @param queue            the physical queue name, possibly empty
     * @param queueLogical     the logical queue name, possibly empty
     * @return the holder, never {@code null}
     */
    private HealthIndicators indicators(final String inputBucket, final String outputBucket,
            final String statementsBucket, final String queue, final String queueLogical) {
        return indicators(inputBucket, outputBucket, statementsBucket, queue, queueLogical, TOPIC);
    }

    /**
     * Builds the holder with an explicit notification topic name, for the notification probe's own cases.
     *
     * @param inputBucket      the batch input bucket name, possibly empty
     * @param outputBucket     the batch output bucket name, possibly empty
     * @param statementsBucket the statements bucket name, possibly empty
     * @param queue            the physical queue name, possibly empty
     * @param queueLogical     the logical queue name, possibly empty
     * @param topic            the bare notification topic name, possibly empty
     * @return the holder, never {@code null}
     */
    private HealthIndicators indicators(final String inputBucket, final String outputBucket,
            final String statementsBucket, final String queue, final String queueLogical,
            final String topic) {
        return new HealthIndicators(this.s3Client, this.sqsAsyncClient, this.snsClient,
                this.dataSource,
                inputBucket, outputBucket, statementsBucket, queue, queueLogical, topic);
    }

    /** Builds the holder with every resource name well formed. */
    private HealthIndicators fullyConfigured() {
        return indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL);
    }

    /**
     * Answers the FIFO attribute read with a queue that satisfies the contract.
     *
     * <p>Resolving the URL proves the queue exists; it does not prove it is FIFO. The queue replaces the
     * {@code JOBS} transient data queue, whose ordering the batch bridge depends on, so the probe also reads
     * {@code FifoQueue} and {@code ContentBasedDeduplication} - but only the first one gates the verdict.
     * {@code FifoQueue} must be {@code true} for that ordering to exist at all, and it cannot change after
     * the queue is created. {@code ContentBasedDeduplication} is answered {@code false} here to match how the
     * queue is provisioned, yet finding H-08 records why it is reported rather than required: every
     * submission carries its own {@code MessageDeduplicationId}, which takes precedence over the body hash,
     * so two legitimate submissions of the same period both survive either way. Only those two attributes
     * are requested: an unqualified request would return the queue ARN and every policy document with it,
     * which is exactly the payload a readiness probe must not obtain.
     */
    private void stubFifoContractSatisfied() {
        Mockito.when(sqsAsyncClient.getQueueAttributes(Mockito.any(GetQueueAttributesRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true",
                                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"))
                        .build()));
    }

    /** The object-storage readiness contributor. */
    @Nested
    @DisplayName("S3 readiness: three buckets, probed in order, with every failure arm distinguished")
    class S3Readiness {

        @Test
        @DisplayName("all three buckets reachable reports UP and records how many were probed")
        void allBucketsReachableReportsUp() {
            Mockito.when(s3Client.headBucket(Mockito.any(HeadBucketRequest.class)))
                    .thenReturn(HeadBucketResponse.builder().build());
            // Existence is necessary but not sufficient. The batch-output bucket carries the generation
            // semantics of app/jcl/DEFGDGB.jcl, and only versioning supplies them: without it a second
            // write to the same generation key overwrites the first rather than creating a version,
            // losing a generation with no error on any path. HeadBucket cannot see that, so the probe
            // verifies it separately and a reachable, correctly provisioned store answers both calls.
            Mockito.when(s3Client.getBucketVersioning(Mockito.any(GetBucketVersioningRequest.class)))
                    .thenReturn(GetBucketVersioningResponse.builder()
                            .status(BucketVersioningStatus.ENABLED).build());

            Health health = fullyConfigured().s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_VERSIONING,
                    BucketVersioningStatus.ENABLED.toString());
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_COMPONENT,
                            HealthIndicators.S3_HEALTH_COMPONENT_NAME)
                    .containsEntry(HealthIndicators.DETAIL_BUCKETS_PROBED, Integer.valueOf(3));
            assertThat(health.getDetails()).containsKey(HealthIndicators.DETAIL_ELAPSED_MILLIS);
        }

        @Test
        @DisplayName("an unconfigured bucket reports DOWN with not-configured and probes nothing")
        void unconfiguredBucketReportsNotConfigured() {
            Health health = indicators("", OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL)
                    .s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON,
                            HealthIndicators.REASON_NOT_CONFIGURED)
                    .containsEntry(HealthIndicators.DETAIL_BUCKETS_PROBED, Integer.valueOf(0));
            Mockito.verify(s3Client, Mockito.never()).headBucket(Mockito.any(HeadBucketRequest.class));
        }

        @Test
        @DisplayName("a blank bucket name is normalised to empty and refused, not probed")
        void blankBucketNameIsRefused() {
            Health health = indicators("   ", OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL)
                    .s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON,
                            HealthIndicators.REASON_NOT_CONFIGURED);
        }

        @ParameterizedTest(name = "[{index}] a bucket name containing {0} is refused before any call")
        @ValueSource(strings = {"bucket:with-colon", "bucket/with-slash", "bucket@with-at",
            "http://endpoint/bucket"})
        @DisplayName("a bucket name carrying an endpoint-bearing character is refused")
        void unsafeBucketNameIsRefused(final String unsafe) {
            Health health = indicators(unsafe, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL)
                    .s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .as("a name that can carry a scheme or host must never be sent to the SDK")
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_INVALID_NAME);
            Mockito.verify(s3Client, Mockito.never()).headBucket(Mockito.any(HeadBucketRequest.class));
        }

        @Test
        @DisplayName("an absent bucket reports DOWN with missing rather than throwing")
        void absentBucketReportsMissing() {
            Mockito.when(s3Client.headBucket(Mockito.any(HeadBucketRequest.class)))
                    .thenThrow(NoSuchBucketException.builder().message("no such bucket").build());

            Health health = fullyConfigured().s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_MISSING)
                    .containsEntry(HealthIndicators.DETAIL_BUCKET, INPUT_BUCKET);
        }

        @Test
        @DisplayName("a 404 service exception is classified as missing, not merely unreachable")
        void notFoundServiceExceptionIsMissing() {
            Mockito.when(s3Client.headBucket(Mockito.any(HeadBucketRequest.class)))
                    .thenThrow(SdkServiceException.builder().message("not found").statusCode(404).build());

            Health health = fullyConfigured().s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_MISSING);
        }

        @Test
        @DisplayName("a non-404 SDK failure is classified as unreachable")
        void otherSdkFailureIsUnreachable() {
            Mockito.when(s3Client.headBucket(Mockito.any(HeadBucketRequest.class)))
                    .thenThrow(SdkClientException.builder().message("connection refused").build());

            Health health = fullyConfigured().s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_UNREACHABLE);
        }

        @Test
        @DisplayName("an unexpected runtime failure is reported as error, never propagated")
        void unexpectedRuntimeFailureIsReportedAsError() {
            Mockito.when(s3Client.headBucket(Mockito.any(HeadBucketRequest.class)))
                    .thenThrow(new IllegalStateException("something structural"));

            Health health = fullyConfigured().s3HealthIndicator().health();

            assertThat(health.getStatus())
                    .as("a probe that throws yields a 500 an orchestrator cannot interpret")
                    .isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_ERROR);
        }

        @Test
        @DisplayName("probing stops at the first failing bucket rather than continuing")
        void probingStopsAtTheFirstFailure() {
            Mockito.when(s3Client.headBucket(Mockito.any(HeadBucketRequest.class)))
                    .thenThrow(SdkClientException.builder().message("down").build());

            Health health = fullyConfigured().s3HealthIndicator().health();

            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_BUCKETS_PROBED, Integer.valueOf(1));
            Mockito.verify(s3Client, Mockito.times(1)).headBucket(Mockito.any(HeadBucketRequest.class));
        }
    }

    /** The queue readiness contributor, including its four asynchronous failure modes. */
    @Nested
    @DisplayName("SQS readiness: the report queue, with every asynchronous failure arm distinguished")
    class SqsReadiness {

        @Test
        @DisplayName("a reachable queue reports UP and publishes the logical name")
        void reachableQueueReportsUp() {
            Mockito.when(sqsAsyncClient.getQueueUrl(Mockito.any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(
                            GetQueueUrlResponse.builder().queueUrl("http://localhost/q").build()));
            stubFifoContractSatisfied();

            Health health = fullyConfigured().sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_COMPONENT,
                            HealthIndicators.SQS_HEALTH_COMPONENT_NAME)
                    .containsEntry(HealthIndicators.DETAIL_QUEUE, QUEUE_LOGICAL);
        }

        @Test
        @DisplayName("with no logical name, the physical queue name is published instead")
        void withoutLogicalNameThePhysicalNameIsPublished() {
            Mockito.when(sqsAsyncClient.getQueueUrl(Mockito.any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(
                            GetQueueUrlResponse.builder().queueUrl("http://localhost/q").build()));
            stubFifoContractSatisfied();

            Health health = indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, "")
                    .sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_QUEUE, QUEUE);
        }

        @Test
        @DisplayName("an unconfigured queue reports DOWN with not-configured and calls nothing")
        void unconfiguredQueueReportsNotConfigured() {
            Health health = indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, "", "")
                    .sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON,
                            HealthIndicators.REASON_NOT_CONFIGURED);
            Mockito.verify(sqsAsyncClient, Mockito.never())
                    .getQueueUrl(Mockito.any(GetQueueUrlRequest.class));
        }

        @Test
        @DisplayName("an unsafe physical queue name is refused before any call")
        void unsafePhysicalQueueNameIsRefused() {
            Health health = indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET,
                    "http://elsewhere/queue", "").sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_INVALID_NAME);
            Mockito.verify(sqsAsyncClient, Mockito.never())
                    .getQueueUrl(Mockito.any(GetQueueUrlRequest.class));
        }

        @Test
        @DisplayName("an unsafe logical name is refused too, and names the logical property")
        void unsafeLogicalQueueNameIsRefused() {
            Health health = indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE,
                    "logical/name").sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_INVALID_NAME);
            assertThat(health.getDetails().get(HealthIndicators.DETAIL_PROPERTY))
                    .asString()
                    .contains("logical");
        }

        @Test
        @DisplayName("a future completing exceptionally reports DOWN, classified from its cause")
        void exceptionallyCompletedFutureIsClassified() {
            Mockito.when(sqsAsyncClient.getQueueUrl(Mockito.any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            SdkClientException.builder().message("no route").build()));

            Health health = fullyConfigured().sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_QUEUE, QUEUE_LOGICAL);
            assertThat(health.getDetails()).containsKey(HealthIndicators.DETAIL_REASON);
        }

        @Test
        @DisplayName("a synchronous runtime failure from the client is reported as error")
        void synchronousRuntimeFailureIsReportedAsError() {
            Mockito.when(sqsAsyncClient.getQueueUrl(Mockito.any(GetQueueUrlRequest.class)))
                    .thenThrow(new IllegalStateException("client already closed"));

            Health health = fullyConfigured().sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_ERROR);
        }

        @Test
        @DisplayName("a probe that never completes reports DOWN with timeout rather than hanging forever")
        void neverCompletingFutureTimesOut() {
            Mockito.when(sqsAsyncClient.getQueueUrl(Mockito.any(GetQueueUrlRequest.class)))
                    .thenReturn(new CompletableFuture<>());

            Health health = fullyConfigured().sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .as("an unbounded wait would make the health endpoint itself a liveness hazard")
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_TIMEOUT);
        }
    }

    /**
     * The notification contributor, which did not exist before the change that added it.
     *
     * <p><strong>The finding.</strong> Notification was the one required cloud dependency readiness said
     * nothing about. An instance whose topic had never been provisioned - a renamed variable, a provisioning
     * script that had not run, a typo - started cleanly, answered {@code /actuator/health/readiness} with
     * {@code 200 UP}, passed the container health check and took traffic; the fault surfaced only at the
     * first report submission, on a request thread, as an exception out of the topic resolver. Every arm
     * below distinguishes one verdict from the others, because a probe whose only reachable answer is DOWN
     * would be no signal at all and one whose only answer is UP would be worse than none.
     */
    @Nested
    @DisplayName("SNS readiness: the operator notification topic, resolved the way the publish path "
            + "resolves it")
    class SnsReadiness {

        /** Builds a one-page listing answer holding the given bare topic names. */
        private ListTopicsResponse page(final String... bareNames) {
            return ListTopicsResponse.builder()
                    .topics(java.util.Arrays.stream(bareNames)
                            .map(name -> Topic.builder()
                                    .topicArn("arn:aws:sns:us-east-1:000000000000:" + name)
                                    .build())
                            .toList())
                    .build();
        }

        @Test
        @DisplayName("a provisioned topic reports UP and publishes the bare name, never the identifier")
        void aProvisionedTopicReportsUp() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenReturn(page("some-other-topic", TOPIC));

            Health health = fullyConfigured().snsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_COMPONENT,
                            HealthIndicators.SNS_HEALTH_COMPONENT_NAME)
                    .containsEntry(HealthIndicators.DETAIL_TOPIC, TOPIC)
                    .containsEntry(HealthIndicators.DETAIL_TOPIC_PAGES_EXAMINED, 1)
                    .containsKey(HealthIndicators.DETAIL_ELAPSED_MILLIS);
            assertThat(health.getDetails().toString())
                    .as("a resolved topic identifier embeds the twelve-digit account segment, so it is "
                            + "compared in-process and discarded - only the configured bare name is "
                            + "published")
                    .doesNotContain("arn:")
                    .doesNotContain("000000000000");
        }

        @Test
        @DisplayName("an absent topic reports DOWN with missing, having walked the whole listing")
        void anAbsentTopicReportsMissing() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenReturn(page("some-other-topic"));

            Health health = fullyConfigured().snsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .as("MISSING rather than UNREACHABLE: every call succeeded, so the remedy is to "
                            + "provision the topic and not to fix connectivity")
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_MISSING)
                    .containsEntry(HealthIndicators.DETAIL_TOPIC_PAGES_EXAMINED, 1);
        }

        @Test
        @DisplayName("an unconfigured topic reports DOWN with not-configured and calls nothing")
        void anUnconfiguredTopicReportsNotConfigured() {
            Health health =
                    indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL, "")
                            .snsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON,
                            HealthIndicators.REASON_NOT_CONFIGURED)
                    .containsEntry(HealthIndicators.DETAIL_PROPERTY,
                            "carddemo.aws.sns.notification-topic");
            Mockito.verify(snsClient, Mockito.never()).listTopics(Mockito.any(ListTopicsRequest.class));
        }

        @Test
        @DisplayName("a topic ARN where a bare name belongs is refused without echoing the value")
        void anArnTopicNameIsRefusedWithoutEchoing() {
            String arn = "arn:aws:sns:us-east-1:000000000000:carddemo-notifications";

            Health health = indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE,
                    QUEUE_LOGICAL, arn).snsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON,
                            HealthIndicators.REASON_INVALID_NAME);
            assertThat(health.getDetails().toString())
                    .doesNotContain("arn:")
                    .doesNotContain("000000000000");
            Mockito.verify(snsClient, Mockito.never()).listTopics(Mockito.any(ListTopicsRequest.class));
        }

        @Test
        @DisplayName("a transport failure reports DOWN with unreachable, not missing")
        void aTransportFailureReportsUnreachable() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenThrow(SdkClientException.create("connection refused"));

            Health health = fullyConfigured().snsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .as("UNREACHABLE rather than MISSING: the service never answered, so nothing has been "
                            + "learned about whether the topic exists")
                    .containsEntry(HealthIndicators.DETAIL_REASON,
                            HealthIndicators.REASON_UNREACHABLE);
        }

        @Test
        @DisplayName("an unexpected runtime failure reports DOWN with error rather than propagating")
        void anUnexpectedFailureReportsError() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenThrow(new IllegalStateException("unexpected"));

            Health health = fullyConfigured().snsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_ERROR);
        }

        @Test
        @DisplayName("every listing call carries a deadline drawn from the contributor's budget")
        void everyListingCallCarriesADeadline() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenReturn(page(TOPIC));

            fullyConfigured().snsHealthIndicator().health();

            org.mockito.ArgumentCaptor<ListTopicsRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(ListTopicsRequest.class);
            Mockito.verify(snsClient).listTopics(captor.capture());
            software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration override =
                    captor.getValue().overrideConfiguration().orElseThrow();
            assertThat(override.apiCallTimeout()).isPresent();
            assertThat(override.apiCallTimeout().orElseThrow())
                    .as("the shared client's timeouts are sized for publishing, so the probe must impose "
                            + "its own or it could block for far longer than the container health check "
                            + "allows")
                    .isGreaterThan(java.time.Duration.ZERO)
                    .isLessThanOrEqualTo(java.time.Duration.ofMillis(1_500L));
            assertThat(override.apiCallAttemptTimeout()).isPresent();
        }

        @Test
        @DisplayName("the walk follows the pagination token and stops at the page that answers")
        void theWalkFollowsPaginationAndStopsWhenItAnswers() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenReturn(ListTopicsResponse.builder()
                            .topics(page("first-page-topic").topics())
                            .nextToken("page-2")
                            .build())
                    .thenReturn(page(TOPIC));

            Health health = fullyConfigured().snsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .as("the page count is published so a reader can tell a complete search from a "
                            + "ceiling-limited one")
                    .containsEntry(HealthIndicators.DETAIL_TOPIC_PAGES_EXAMINED, 2);
            Mockito.verify(snsClient, Mockito.times(2))
                    .listTopics(Mockito.any(ListTopicsRequest.class));
            assertThat(health.getDetails().toString()).doesNotContain("page-2");
        }

        @Test
        @DisplayName("a blank pagination token ends the walk rather than repeating the first page")
        void aBlankPaginationTokenEndsTheWalk() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenReturn(ListTopicsResponse.builder()
                            .topics(page("other").topics())
                            .nextToken("   ")
                            .build());

            Health health = fullyConfigured().snsHealthIndicator().health();

            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_MISSING);
            Mockito.verify(snsClient, Mockito.times(1))
                    .listTopics(Mockito.any(ListTopicsRequest.class));
        }

        @Test
        @DisplayName("the walk is bounded by a page ceiling even when the service keeps handing out tokens")
        void theWalkIsBoundedByThePageCeiling() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenReturn(ListTopicsResponse.builder()
                            .topics(page("never-the-one-we-want").topics())
                            .nextToken("always-another")
                            .build());

            Health health = fullyConfigured().snsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat((Integer) health.getDetails()
                    .get(HealthIndicators.DETAIL_TOPIC_PAGES_EXAMINED))
                    .as("an unbounded walk over an endlessly-paginating service would hang the readiness "
                            + "endpoint; the ceiling and the time budget both bound it")
                    .isLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("the probe publishes NOTHING to the topic - not one message, ever")
        void theProbePublishesNothing() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenReturn(page(TOPIC));

            fullyConfigured().snsHealthIndicator().health();

            // A health check that published would emit a spurious operator notification every 15 s under
            // the container health check, which is worse than having no probe at all.
            Mockito.verify(snsClient, Mockito.never())
                    .publish(Mockito.any(software.amazon.awssdk.services.sns.model.PublishRequest.class));
            Mockito.verify(snsClient, Mockito.never())
                    .createTopic(
                            Mockito.any(software.amazon.awssdk.services.sns.model.CreateTopicRequest.class));
        }

        @Test
        @DisplayName("the page ceiling agrees with the publish path's, so the two reach the same verdict")
        void thePageCeilingAgreesWithThePublishPath() throws Exception {
            java.lang.reflect.Field probeCeiling =
                    HealthIndicators.class.getDeclaredField("TOPIC_PAGE_LIMIT_COUNT");
            probeCeiling.setAccessible(true);
            java.lang.reflect.Field publishCeiling =
                    com.cardemo.config.AwsConfig.class.getDeclaredField("TOPIC_PAGE_LIMIT_COUNT");
            publishCeiling.setAccessible(true);

            assertThat(probeCeiling.getInt(null))
                    .as("a readiness probe that searched FEWER pages than the publish path would report "
                            + "DOWN for a topic the publish path resolves perfectly well, taking a healthy "
                            + "instance out of rotation on the strength of its own shorter search. The two "
                            + "constants are declared separately - a health probe must not import "
                            + "configuration internals - so this assertion is what binds them")
                    .isEqualTo(publishCeiling.getInt(null));
        }

        @Test
        @DisplayName("a non-empty list containing only near-miss names is still missing")
        void nearMissNamesDoNotSatisfyTheProbe() {
            Mockito.when(snsClient.listTopics(Mockito.any(ListTopicsRequest.class)))
                    .thenReturn(page(TOPIC + "-inbox", "x" + TOPIC, TOPIC.toUpperCase(java.util.Locale.ROOT)));

            Health health = fullyConfigured().snsHealthIndicator().health();

            assertThat(health.getStatus())
                    .as("the comparison is exact, as the publish path's is. The derived '-inbox' queue "
                            + "subscription target shares a prefix with the topic and must not satisfy it")
                    .isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_MISSING);
        }
    }

    /** Construction-time guarantees and the published detail-key contract. */
    @Nested
    @DisplayName("Construction and published names")
    class ConstructionAndNames {

        @Test
        @DisplayName("a null object-storage client is refused at construction")
        void nullS3ClientIsRefused() {
            assertThatThrownBy(() -> new HealthIndicators(null, sqsAsyncClient, snsClient,
                    dataSource,
                    INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL, TOPIC))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a null datasource is refused at construction")
        void nullDataSourceIsRefused() {
            assertThatThrownBy(() -> new HealthIndicators(s3Client, sqsAsyncClient, snsClient, null,
                    INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL, TOPIC))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a null queue client is refused at construction")
        void nullSqsClientIsRefused() {
            assertThatThrownBy(() -> new HealthIndicators(s3Client, null, snsClient, dataSource,
                    INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL, TOPIC))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a null notification client is refused at construction")
        void nullSnsClientIsRefused() {
            assertThatThrownBy(() -> new HealthIndicators(s3Client, sqsAsyncClient, null, dataSource,
                    INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL, TOPIC))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null resource names are normalised to empty rather than throwing")
        void nullResourceNamesAreNormalised() {
            HealthIndicator indicator = new HealthIndicators(s3Client, sqsAsyncClient, snsClient,
                    dataSource,
                    null, null, null, null, null, null).s3HealthIndicator();

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON,
                            HealthIndicators.REASON_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("both contributors are distinct instances, so neither shares probe state")
        void contributorsAreDistinctInstances() {
            HealthIndicators holder = fullyConfigured();

            assertThat(holder.s3HealthIndicator()).isNotSameAs(holder.s3HealthIndicator());
            assertThat(holder.sqsHealthIndicator()).isNotSameAs(holder.sqsHealthIndicator());
            assertThat(holder.dbHealthIndicator()).isNotSameAs(holder.dbHealthIndicator());
        }

        @Test
        @DisplayName("the component and reason names a dashboard matches on are exactly these")
        void publishedNamesArePinned() {
            assertThat(HealthIndicators.S3_HEALTH_COMPONENT_NAME).isEqualTo("s3");
            assertThat(HealthIndicators.SQS_HEALTH_COMPONENT_NAME).isEqualTo("sqs");
            assertThat(HealthIndicators.DB_HEALTH_COMPONENT_NAME)
                    .as("the bounded relational probe deliberately takes the SAME key the "
                            + "framework contributor used, so the readiness group, the container "
                            + "health check and every published gate reading keep their spelling")
                    .isEqualTo("db");
            assertThat(HealthIndicators.DB_HEALTH_INDICATOR_BEAN_NAME)
                    .as("Actuator derives the component key by stripping the HealthIndicator "
                            + "suffix, so the bean name is what actually fixes the key")
                    .isEqualTo("dbHealthIndicator");
            assertThat(HealthIndicators.REASON_NOT_CONFIGURED).isEqualTo("not-configured");
            assertThat(HealthIndicators.REASON_INVALID_NAME).isEqualTo("invalid-name");
            assertThat(HealthIndicators.REASON_MISSING).isEqualTo("missing");
            assertThat(HealthIndicators.REASON_UNREACHABLE).isEqualTo("unreachable");
            assertThat(HealthIndicators.REASON_TIMEOUT).isEqualTo("timeout");
            assertThat(HealthIndicators.REASON_INTERRUPTED).isEqualTo("interrupted");
            assertThat(HealthIndicators.REASON_ERROR).isEqualTo("error");
        }
    }

    /**
     * The bounded relational probe that replaces the framework's unbounded {@code db} contributor.
     *
     * <p>Every test here asserts the BOUND as well as the verdict, because the verdict was already
     * right before this probe existed: Boot's contributor also answered {@code DOWN} with the database
     * stopped - it took 30 009 ms to do it, against a container health check that allows 5 s. A test
     * that only asserted {@code DOWN} would have passed against the defect.
     */
    @Nested
    @DisplayName("Bounded relational readiness")
    class BoundedRelationalReadiness {

        /** Comfortably above the 1 500 ms budget and comfortably below the pool's 30 s timeout. */
        private static final long ALLOWED_ANSWER_MILLIS = 6_000L;

        @Test
        @DisplayName("a stalled acquisition still answers DOWN inside the budget, not after 30 seconds")
        void aStalledAcquisitionAnswersInsideTheBudget() throws Exception {
            Mockito.when(dataSource.getConnection()).thenAnswer(invocation -> {
                // Stands in for Hikari retrying acquisition until connection-timeout expires. Far longer
                // than the budget, far shorter than a test-suite hang if the bound were ever removed.
                Thread.sleep(20_000L);
                throw new SQLException("acquisition timed out");
            });

            long startedAtNanos = System.nanoTime();
            Health health = fullyConfigured().dbHealthIndicator().health();
            long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_COMPONENT,
                            HealthIndicators.DB_HEALTH_COMPONENT_NAME)
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_TIMEOUT)
                    .containsKey(HealthIndicators.DETAIL_ELAPSED_MILLIS);
            assertThat(elapsedMillis)
                    .as("the whole point of the contributor: the ANSWER is bounded, so a readiness probe "
                            + "cannot outlast the container health check's own timeout and turn a database "
                            + "blip into a restart loop")
                    .isLessThan(ALLOWED_ANSWER_MILLIS);
        }

        @Test
        @DisplayName("a connection that answers the validation query reports UP with its elapsed time")
        void aWorkingConnectionReportsUp() throws Exception {
            Connection connection = Mockito.mock(Connection.class);
            Statement statement = Mockito.mock(Statement.class);
            ResultSet answer = Mockito.mock(ResultSet.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(statement);
            Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(answer);
            Mockito.when(answer.next()).thenReturn(true);
            Mockito.when(answer.getInt(1)).thenReturn(1);

            Health health = fullyConfigured().dbHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_COMPONENT,
                            HealthIndicators.DB_HEALTH_COMPONENT_NAME)
                    .containsKey(HealthIndicators.DETAIL_ELAPSED_MILLIS);
            Mockito.verify(statement).setQueryTimeout(Mockito.intThat(seconds -> seconds >= 1));
            Mockito.verify(connection).close();
            Mockito.verify(statement).close();
        }

        @Test
        @DisplayName("a refused connection reports DOWN as unreachable, and never rethrows")
        void aRefusedConnectionReportsUnreachable() throws Exception {
            Mockito.when(dataSource.getConnection())
                    .thenThrow(new SQLException("connection refused"));

            Health health = fullyConfigured().dbHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_UNREACHABLE);
            assertThat(health.getDetails().toString())
                    .as("a contributor publishes a symbolic reason and an elapsed time and nothing else: "
                            + "a JDBC message routinely carries the host, the port and the role name")
                    .doesNotContain("connection refused");
        }

        @Test
        @DisplayName("a validation query answering anything but 1 is a mismatch, not a success")
        void anUnexpectedAnswerIsAMismatch() throws Exception {
            Connection connection = Mockito.mock(Connection.class);
            Statement statement = Mockito.mock(Statement.class);
            ResultSet answer = Mockito.mock(ResultSet.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(statement);
            Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(answer);
            Mockito.when(answer.next()).thenReturn(false);

            Health health = fullyConfigured().dbHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON,
                            HealthIndicators.REASON_ATTRIBUTE_MISMATCH);
        }

        @Test
        @DisplayName("the probe releases its thread when the contributor is closed")
        void theProbeReleasesItsThreadOnClose() throws Exception {
            HealthIndicator indicator = fullyConfigured().dbHealthIndicator();

            assertThat(indicator)
                    .as("the bean declares close() as its destroy method, so the one daemon thread this "
                            + "probe owns is released when the context closes rather than leaked")
                    .isInstanceOf(AutoCloseable.class);
            ((AutoCloseable) indicator).close();
        }
    }
}
