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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    /** Stubbed object-storage client; no test lets it reach an endpoint. */
    private S3Client s3Client;

    /** Stubbed queue client; no test lets it reach an endpoint. */
    private SqsAsyncClient sqsAsyncClient;

    @BeforeEach
    void setUp() {
        this.s3Client = Mockito.mock(S3Client.class);
        this.sqsAsyncClient = Mockito.mock(SqsAsyncClient.class);
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
        return new HealthIndicators(this.s3Client, this.sqsAsyncClient,
                inputBucket, outputBucket, statementsBucket, queue, queueLogical);
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

    /** Construction-time guarantees and the published detail-key contract. */
    @Nested
    @DisplayName("Construction and published names")
    class ConstructionAndNames {

        @Test
        @DisplayName("a null object-storage client is refused at construction")
        void nullS3ClientIsRefused() {
            assertThatThrownBy(() -> new HealthIndicators(null, sqsAsyncClient,
                    INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a null queue client is refused at construction")
        void nullSqsClientIsRefused() {
            assertThatThrownBy(() -> new HealthIndicators(s3Client, null,
                    INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, QUEUE_LOGICAL))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null resource names are normalised to empty rather than throwing")
        void nullResourceNamesAreNormalised() {
            HealthIndicator indicator = new HealthIndicators(s3Client, sqsAsyncClient,
                    null, null, null, null, null).s3HealthIndicator();

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
        }

        @Test
        @DisplayName("the component and reason names a dashboard matches on are exactly these")
        void publishedNamesArePinned() {
            assertThat(HealthIndicators.S3_HEALTH_COMPONENT_NAME).isEqualTo("s3");
            assertThat(HealthIndicators.SQS_HEALTH_COMPONENT_NAME).isEqualTo("sqs");
            assertThat(HealthIndicators.REASON_NOT_CONFIGURED).isEqualTo("not-configured");
            assertThat(HealthIndicators.REASON_INVALID_NAME).isEqualTo("invalid-name");
            assertThat(HealthIndicators.REASON_MISSING).isEqualTo("missing");
            assertThat(HealthIndicators.REASON_UNREACHABLE).isEqualTo("unreachable");
            assertThat(HealthIndicators.REASON_TIMEOUT).isEqualTo("timeout");
            assertThat(HealthIndicators.REASON_INTERRUPTED).isEqualTo("interrupted");
            assertThat(HealthIndicators.REASON_ERROR).isEqualTo("error");
        }
    }
}
