/*
 * ******************************************************************
 * Program     : HealthIndicatorsTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test
 * Function    : Pins the readiness probes that replace the file-availability jobs -
 *               an unconfigured, malformed, missing or unreachable dependency must
 *               report DOWN rather than UP, and the reason must be actionable.
 * Source      : app/jcl/OPENFIL.jcl, app/jcl/CLOSEFIL.jcl (the operator jobs that
 *               made datasets available to the online region) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (DEFINE TDQUEUE(JOBS), now an SQS FIFO queue)
 * ******************************************************************
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
 * ******************************************************************
 */
package com.cardemo.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.observability.HealthIndicators;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.core.exception.SdkClientException;
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
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Unit test for {@link HealthIndicators}, the readiness probes that replace {@code OPENFIL} and
 * {@code CLOSEFIL}.
 *
 * <h2>What it does</h2>
 *
 * <p>On the mainframe an operator ran a job to make the datasets available to the online region, and the
 * region simply failed if they were not. There is no equivalent step in a container, so readiness has to be
 * <em>asserted</em> instead of assumed - and a readiness probe that reports UP when its dependency is missing
 * is worse than no probe at all, because an orchestrator will route traffic to it.
 *
 * <p>This class therefore concentrates on the DOWN paths, which are the ones a passing deployment never
 * exercises. Four distinct failure classes are pinned per dependency and each carries its own machine-readable
 * reason: <strong>not-configured</strong> when the property is empty, <strong>invalid-name</strong> when the
 * value carries a character that would let a resource name smuggle in a host or a path,
 * <strong>missing</strong> when the dependency genuinely is not there, and <strong>unreachable</strong> or
 * <strong>timeout</strong> when it cannot be contacted. The distinction matters operationally: the first two
 * are fixed by editing configuration and the last two by fixing infrastructure, and a probe that collapsed
 * them into a single "DOWN" would not say which.
 *
 * <p>The empty-property case deserves particular note, because the constructor defaults every bucket and queue
 * property to the empty string. That makes a <em>missing</em> configuration key indistinguishable from a
 * present-but-empty one at construction time, so the probe - not the constructor - is where it must be caught.
 * A probe that treated an empty name as "nothing to check" would report UP for a completely unconfigured
 * application.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run alone with {@code ./mvnw -B -ntp -o test -Dtest=HealthIndicatorsTest -Djacoco.skip=true}, or with the
 * unit tier via {@code ./mvnw -B -ntp test}. The {@code -Dtest} separator is a comma, never a plus.
 *
 * <p>No container and no LocalStack instance is involved: the two AWS clients are Mockito doubles, which is
 * what lets the failure classes above be provoked deterministically. Whether a real bucket answers a real
 * {@code HeadBucket} is an integration concern and is not claimed here. The two indicator classes are private
 * nested types, so they are obtained through the {@code @Bean} factory methods exactly as the container does.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Five keys participate, all defaulting to the empty string:
 * {@code carddemo.aws.s3.batch-input-bucket}, {@code carddemo.aws.s3.batch-output-bucket},
 * {@code carddemo.aws.s3.statements-bucket}, {@code carddemo.aws.sqs.report-queue} and
 * {@code carddemo.aws.sqs.report-queue-logical-name}. The last is optional - when blank the physical queue
 * name is published instead - and that fallback is asserted.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A probe reporting UP in any test here is the serious failure: it means a missing or misconfigured
 *       dependency would be advertised as ready.</li>
 *   <li>A failure in the invalid-name tests means the character filter was relaxed. It exists so a resource
 *       name cannot carry {@code :}, {@code /} or {@code @} and thereby redirect a probe.</li>
 *   <li>A failure in the ordering test means the probe now contacts S3 before validating the configured
 *       names, which turns a configuration error into a network error in the reported reason.</li>
 *   </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HealthIndicators - the readiness probes replacing OPENFIL and CLOSEFIL")
class HealthIndicatorsTest {

    private static final String INPUT_BUCKET = "carddemo-batch-input";
    private static final String OUTPUT_BUCKET = "carddemo-batch-output";
    private static final String STATEMENTS_BUCKET = "carddemo-statements";
    private static final String QUEUE = "carddemo-report-jobs.fifo";

    @Mock private S3Client s3Client;
    @Mock private SqsAsyncClient sqsAsyncClient;

    /** Builds the configuration holder with the supplied bucket and queue names. */
    private HealthIndicators indicators(final String inputBucket, final String outputBucket,
                                        final String statementsBucket, final String queue,
                                        final String logicalQueueName) {
        return new HealthIndicators(this.s3Client, this.sqsAsyncClient, inputBucket, outputBucket,
                statementsBucket, queue, logicalQueueName);
    }

    /** The fully configured holder, which every happy-path test starts from. */
    private HealthIndicators configured() {
        return indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, "");
    }

    /**
     * Answers a fully reachable, correctly provisioned object store.
     *
     * <p>Existence is necessary but not sufficient, so this answers both calls the probe makes. The
     * batch-output bucket is the object-storage replacement for the generation data groups of
     * {@code app/jcl/DEFGDGB.jcl}, and it is versioning that supplies the generation semantics: without
     * it a second write to the same generation key overwrites the first instead of creating a version,
     * silently losing a generation with no error on any path. {@code HeadBucket} cannot see that, so the
     * probe verifies it separately and a happy path must answer it.
     */
    private void s3Answers() {
        when(this.s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());
        when(this.s3Client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder()
                        .status(BucketVersioningStatus.ENABLED).build());
    }

    /**
     * Answers a fully reachable, correctly provisioned report queue.
     *
     * <p>Resolving the URL is necessary but not sufficient. The queue replaces the {@code JOBS} transient
     * data queue, whose ordering the batch bridge depends on, so the probe also reads the two FIFO
     * attributes and requires both to be {@code true}. A happy path must answer that read as well, and
     * only those two attributes are requested: an unqualified request would return the queue ARN and
     * every policy document with it.
     */
    private void sqsAnswers() {
        when(this.sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder()
                        .queueUrl("http://localhost:4566/000000000000/" + QUEUE).build()));
        when(this.sqsAsyncClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true",
                                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                        .build()));
    }

    private static Object detail(final Health health, final String key) {
        return health.getDetails().get(key);
    }

    @Nested
    @DisplayName("the S3 readiness probe")
    class S3Probe {

        @Test
        @DisplayName("all three buckets present reports UP and records how many were probed")
        void allThreeBucketsPresentReportsUp() {
            s3Answers();

            final Health health = configured().s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(detail(health, HealthIndicators.DETAIL_COMPONENT))
                    .isEqualTo(HealthIndicators.S3_HEALTH_COMPONENT_NAME);
            assertThat(detail(health, HealthIndicators.DETAIL_BUCKETS_PROBED))
                    .as("all three configured buckets must be probed, not just the first")
                    .isEqualTo(3);
            assertThat(health.getDetails()).containsKey(HealthIndicators.DETAIL_ELAPSED_MILLIS);
        }

        @Test
        @DisplayName("an unconfigured bucket reports DOWN with not-configured, and never contacts S3")
        void anUnconfiguredBucketReportsDown() {
            final Health health = indicators("", OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, "")
                    .s3HealthIndicator().health();

            assertThat(health.getStatus())
                    .as("an unconfigured application must never advertise itself as ready")
                    .isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_NOT_CONFIGURED);
            assertThat(detail(health, HealthIndicators.DETAIL_PROPERTY))
                    .as("the reported property is what makes the failure actionable")
                    .isEqualTo("carddemo.aws.s3.batch-input-bucket");
            verify(s3Client, never()).headBucket(any(HeadBucketRequest.class));
        }

        @Test
        @DisplayName("a blank-only bucket name is treated as unconfigured, not as a name")
        void aBlankOnlyBucketNameIsTreatedAsUnconfigured() {
            final Health health = indicators("   ", OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, "")
                    .s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_NOT_CONFIGURED);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "bucket:with-colon",
            "bucket/with-slash",
            "bucket@with-at",
            "http://evil.example.com",
        })
        @DisplayName("a bucket name carrying a host or path character reports invalid-name")
        void aBucketNameCarryingAHostCharacterIsRefused(final String unsafe) {
            final Health health = indicators(unsafe, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, "")
                    .s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .as("a resource name must not be able to smuggle in a host or a path")
                    .isEqualTo(HealthIndicators.REASON_INVALID_NAME);
            verify(s3Client, never()).headBucket(any(HeadBucketRequest.class));
        }

        @Test
        @DisplayName("an absent bucket reports DOWN with missing, and names the bucket")
        void anAbsentBucketReportsMissing() {
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(NoSuchBucketException.builder().message("no such bucket").build());

            final Health health = configured().s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_MISSING);
            assertThat(detail(health, HealthIndicators.DETAIL_BUCKET)).isEqualTo(INPUT_BUCKET);
        }

        @Test
        @DisplayName("a transport failure reports DOWN with unreachable")
        void aTransportFailureReportsUnreachable() {
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(SdkClientException.create("connection refused"));

            final Health health = configured().s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .as("a transport fault is fixed by repairing infrastructure, not configuration, so it "
                            + "must be distinguishable from not-configured")
                    .isEqualTo(HealthIndicators.REASON_UNREACHABLE);
        }

        @Test
        @DisplayName("an unexpected runtime failure reports DOWN rather than escaping the probe")
        void anUnexpectedRuntimeFailureReportsDown() {
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(new IllegalStateException("the client was closed"));

            final Health health = configured().s3HealthIndicator().health();

            assertThat(health.getStatus())
                    .as("a probe that throws produces a 500 from the health endpoint instead of a DOWN body")
                    .isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_ERROR);
        }

        @Test
        @DisplayName("names are validated before S3 is contacted, so a config error is not reported as I/O")
        void namesAreValidatedBeforeS3IsContacted() {
            final Health health = indicators(INPUT_BUCKET, "bucket:with-colon", STATEMENTS_BUCKET, QUEUE, "")
                    .s3HealthIndicator().health();

            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_INVALID_NAME);
            assertThat(detail(health, HealthIndicators.DETAIL_BUCKETS_PROBED))
                    .as("the first bucket was probed, the second refused before any call")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the SQS readiness probe")
    class SqsProbe {

        @Test
        @DisplayName("a resolvable queue reports UP and publishes the physical name")
        void aResolvableQueueReportsUp() {
            sqsAnswers();

            final Health health = configured().sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(detail(health, HealthIndicators.DETAIL_COMPONENT))
                    .isEqualTo(HealthIndicators.SQS_HEALTH_COMPONENT_NAME);
            assertThat(detail(health, HealthIndicators.DETAIL_QUEUE)).isEqualTo(QUEUE);
        }

        @Test
        @DisplayName("a configured logical name is published in place of the physical one")
        void aConfiguredLogicalNameIsPublished() {
            sqsAnswers();

            final Health health =
                    indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, "JOBS")
                            .sqsHealthIndicator().health();

            assertThat(detail(health, HealthIndicators.DETAIL_QUEUE))
                    .as("the DEFINE TDQUEUE(JOBS) name is the one an operator recognises")
                    .isEqualTo("JOBS");
        }

        @Test
        @DisplayName("an unconfigured queue reports DOWN with not-configured, and never contacts SQS")
        void anUnconfiguredQueueReportsDown() {
            final Health health =
                    indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, "", "")
                            .sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_NOT_CONFIGURED);
            assertThat(detail(health, HealthIndicators.DETAIL_PROPERTY))
                    .isEqualTo("carddemo.aws.sqs.report-queue");
            verify(sqsAsyncClient, never()).getQueueUrl(any(GetQueueUrlRequest.class));
        }

        @Test
        @DisplayName("an unsafe queue name reports invalid-name")
        void anUnsafeQueueNameIsRefused() {
            final Health health =
                    indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, "queue@elsewhere", "")
                            .sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_INVALID_NAME);
        }

        @Test
        @DisplayName("an unsafe LOGICAL name is refused too, and names its own property")
        void anUnsafeLogicalNameIsRefused() {
            final Health health =
                    indicators(INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, "logical/name")
                            .sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_INVALID_NAME);
            assertThat(detail(health, HealthIndicators.DETAIL_PROPERTY))
                    .as("the logical name is published into the response, so it is validated as well")
                    .isEqualTo("carddemo.aws.sqs.report-queue-logical-name");
        }

        @Test
        @DisplayName("an absent queue reports DOWN with missing")
        void anAbsentQueueReportsMissing() {
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            QueueDoesNotExistException.builder().message("no such queue").build()));

            final Health health = configured().sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_MISSING);
        }

        @Test
        @DisplayName("a transport failure reports DOWN with unreachable")
        void aTransportFailureReportsUnreachable() {
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            SdkClientException.create("connection refused")));

            final Health health = configured().sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_UNREACHABLE);
        }

        @Test
        @DisplayName("a non-SDK completion failure reports DOWN with error")
        void aNonSdkCompletionFailureReportsError() {
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            new IllegalStateException("the client was closed")));

            final Health health = configured().sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_ERROR);
        }

        @Test
        @DisplayName("a synchronous runtime failure reports DOWN rather than escaping the probe")
        void aSynchronousRuntimeFailureReportsDown() {
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenThrow(new IllegalStateException("the client was closed"));

            final Health health = configured().sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_ERROR);
        }
    }

    @Nested
    @DisplayName("construction guards and bean identity")
    class ConstructionGuards {

        @Test
        @DisplayName("the holder refuses to be built without an S3 client")
        void theHolderRefusesToBeBuiltWithoutAnS3Client() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new HealthIndicators(null, sqsAsyncClient, INPUT_BUCKET,
                            OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, ""))
                    .withMessageContaining("s3Client");
        }

        @Test
        @DisplayName("the holder refuses to be built without an SQS client")
        void theHolderRefusesToBeBuiltWithoutAnSqsClient() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new HealthIndicators(s3Client, null, INPUT_BUCKET,
                            OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, ""))
                    .withMessageContaining("sqsAsyncClient");
        }

        @Test
        @DisplayName("a null property value is tolerated and normalised to unconfigured")
        void aNullPropertyValueIsNormalised() {
            final Health health = indicators(null, OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE, "")
                    .s3HealthIndicator().health();

            assertThat(health.getStatus())
                    .as("an absent key must produce a DOWN with a reason, never a NullPointerException "
                            + "escaping the health endpoint")
                    .isEqualTo(Status.DOWN);
            assertThat(detail(health, HealthIndicators.DETAIL_REASON))
                    .isEqualTo(HealthIndicators.REASON_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("each factory call yields a usable indicator")
        void eachFactoryCallYieldsAUsableIndicator() {
            final HealthIndicators holder = configured();
            final HealthIndicator s3 = holder.s3HealthIndicator();
            final HealthIndicator sqs = holder.sqsHealthIndicator();

            assertThat(s3).isNotNull();
            assertThat(sqs).isNotNull();
            assertThat(s3).isNotSameAs(sqs);
        }
    }
}
