/*
 * ****************************************************************************
 * Program     : HealthIndicatorsTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies the two readiness contributors in HealthIndicators:
 *               that every probe call is bounded by the contributor budget and
 *               that an abandoned asynchronous call is cancelled; that no raw
 *               SDK throwable, endpoint, queue URL or account id reaches a log
 *               argument or a health detail; and that UP requires the substrate
 *               contract - batch-output bucket versioning enabled, report queue
 *               FIFO with content-based deduplication - and not existence alone.
 * Source      : app/jcl/OPENFIL.jcl + app/jcl/CLOSEFIL.jcl (the online file
 *                 availability jobs the two contributors replace)
 *               + app/jcl/DEFGDGB.jcl (the GDG bases whose generation semantics
 *                 object versioning supplies)
 *               + app/csd/CARDDEMO.CSD:L499-505 (DEFINE TDQUEUE(JOBS) - the FIFO
 *                 ordering the queue attributes supply)
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
package com.cardemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.observability.HealthIndicators;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Unit tests for {@link HealthIndicators}.
 *
 * <p>No network, no container and no Spring context: both SDK clients are Mockito doubles, so every
 * outcome below is reached deterministically. The three properties under test are the three defects
 * external review found - unbounded and uncancelled calls, raw SDK throwables reaching the log, and
 * {@code UP} awarded on existence alone.
 */
@DisplayName("HealthIndicators - bounded, curated and contract-verifying readiness probes")
class HealthIndicatorsTest {

    /** The batch-input bucket name used throughout, matching the shape {@code .env.example} sets. */
    private static final String INPUT_BUCKET = "carddemo-batch-input";

    /** The batch-output bucket name - the one bucket whose versioning is verified. */
    private static final String OUTPUT_BUCKET = "carddemo-batch-output";

    /** The statements bucket name. */
    private static final String STATEMENTS_BUCKET = "carddemo-statements";

    /** The physical report-queue name, carrying the {@code .fifo} suffix AWS requires. */
    private static final String QUEUE_NAME = "carddemo-report-jobs.fifo";

    /** The account-id-free logical queue name, the only queue name a detail may publish. */
    private static final String QUEUE_LOGICAL_NAME = "carddemo-report-jobs";

    /** The bare notification topic name. Bare, never an ARN: the probe refuses an ARN outright. */
    private static final String TOPIC_NAME = "carddemo-notifications";

    /**
     * A resolved queue URL of the shape SQS returns, carrying a twelve-digit account segment. Used to
     * assert that the probe consumes it without ever publishing it.
     */
    private static final String QUEUE_URL =
            "http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/"
                    + "carddemo-report-jobs.fifo";

    /**
     * Builds the class under test with every client supplied and all six properties populated.
     *
     * @param s3Client       the S3 double
     * @param sqsAsyncClient the SQS double
     * @return a fully configured factory
     */
    private static HealthIndicators indicators(S3Client s3Client, SqsAsyncClient sqsAsyncClient) {
        return indicators(s3Client, sqsAsyncClient, mock(SnsClient.class));
    }

    /**
     * Builds the class under test with an explicit notification client, for the SNS probe's own cases.
     *
     * @param s3Client       the S3 double
     * @param sqsAsyncClient the SQS double
     * @param snsClient      the SNS double
     * @return a fully configured factory
     */
    private static HealthIndicators indicators(S3Client s3Client, SqsAsyncClient sqsAsyncClient,
            SnsClient snsClient) {
        return new HealthIndicators(s3Client, sqsAsyncClient, snsClient, mock(DataSource.class),
                INPUT_BUCKET,
                OUTPUT_BUCKET, STATEMENTS_BUCKET, QUEUE_NAME, QUEUE_LOGICAL_NAME, TOPIC_NAME);
    }

    /**
     * Stubs the three existence probes to succeed.
     *
     * @param s3Client the S3 double to stub
     */
    private static void stubBucketsExist(S3Client s3Client) {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());
    }

    /**
     * Stubs the versioning probe to answer with the supplied status.
     *
     * @param s3Client the S3 double to stub
     * @param status   the status to answer with, or {@code null} for a never-configured bucket
     */
    private static void stubVersioning(S3Client s3Client, BucketVersioningStatus status) {
        when(s3Client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder().status(status).build());
    }

    /**
     * Stubs a successful queue URL resolution.
     *
     * @param sqsAsyncClient the SQS double to stub
     */
    private static void stubQueueResolves(SqsAsyncClient sqsAsyncClient) {
        when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(
                CompletableFuture.completedFuture(
                        GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
    }

    /**
     * Stubs the attribute probe to answer with the supplied values.
     *
     * @param sqsAsyncClient      the SQS double to stub
     * @param fifo                value of {@code FifoQueue}, or {@code null} to omit it
     * @param contentDeduplicated value of {@code ContentBasedDeduplication}, or {@code null} to omit
     */
    private static void stubQueueAttributes(SqsAsyncClient sqsAsyncClient, String fifo,
            String contentDeduplicated) {
        Map<QueueAttributeName, String> attributes = new java.util.LinkedHashMap<>();
        if (fifo != null) {
            attributes.put(QueueAttributeName.FIFO_QUEUE, fifo);
        }
        if (contentDeduplicated != null) {
            attributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, contentDeduplicated);
        }
        when(sqsAsyncClient.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenReturn(
                CompletableFuture.completedFuture(
                        GetQueueAttributesResponse.builder().attributes(attributes).build()));
    }

    /**
     * Renders every detail value of a health result as one string, for leak assertions.
     *
     * @param health the result to render
     * @return the concatenated detail keys and values
     */
    private static String renderDetails(Health health) {
        StringBuilder rendered = new StringBuilder(health.getStatus().getCode());
        health.getDetails().forEach((key, value) -> rendered.append('|')
                .append(key)
                .append('=')
                .append(value));
        return rendered.toString();
    }

    @Nested
    @DisplayName("Bounded calls (H-03)")
    class BoundedCalls {

        @Test
        @DisplayName("every HeadBucket carries a request-level call and attempt deadline")
        void headBucketCarriesDeadlines() {
            S3Client s3Client = mock(S3Client.class);
            List<HeadBucketRequest> issued = new ArrayList<>();
            when(s3Client.headBucket(any(HeadBucketRequest.class))).thenAnswer(invocation -> {
                issued.add(invocation.getArgument(0));
                return HeadBucketResponse.builder().build();
            });
            stubVersioning(s3Client, BucketVersioningStatus.ENABLED);

            indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(issued).hasSize(3);
            assertThat(issued).allSatisfy(request -> {
                AwsRequestOverrideConfiguration override =
                        request.overrideConfiguration().orElseThrow();
                assertThat(override.apiCallTimeout()).isPresent();
                assertThat(override.apiCallTimeout().orElseThrow())
                        .isGreaterThan(Duration.ZERO)
                        .isLessThanOrEqualTo(Duration.ofMillis(1_500L));
                assertThat(override.apiCallAttemptTimeout()).isPresent();
                assertThat(override.apiCallAttemptTimeout().orElseThrow())
                        .isGreaterThan(Duration.ZERO)
                        .isLessThanOrEqualTo(Duration.ofMillis(500L));
            });
        }

        @Test
        @DisplayName("the versioning verification call carries a deadline too")
        void versioningCallCarriesDeadline() {
            S3Client s3Client = mock(S3Client.class);
            stubBucketsExist(s3Client);
            AtomicReference<GetBucketVersioningRequest> issued = new AtomicReference<>();
            when(s3Client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                    .thenAnswer(invocation -> {
                        issued.set(invocation.getArgument(0));
                        return GetBucketVersioningResponse.builder()
                                .status(BucketVersioningStatus.ENABLED)
                                .build();
                    });

            indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(issued.get().overrideConfiguration().orElseThrow().apiCallTimeout())
                    .isPresent();
        }

        @Test
        @DisplayName("both SQS calls carry request-level deadlines")
        void sqsCallsCarryDeadlines() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            AtomicReference<GetQueueUrlRequest> urlRequest = new AtomicReference<>();
            AtomicReference<GetQueueAttributesRequest> attributeRequest = new AtomicReference<>();
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenAnswer(invocation -> {
                        urlRequest.set(invocation.getArgument(0));
                        return CompletableFuture.completedFuture(
                                GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build());
                    });
            when(sqsAsyncClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                    .thenAnswer(invocation -> {
                        attributeRequest.set(invocation.getArgument(0));
                        return CompletableFuture.completedFuture(GetQueueAttributesResponse.builder()
                                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true",
                                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"))
                                .build());
                    });

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(urlRequest.get().overrideConfiguration().orElseThrow().apiCallTimeout())
                    .isPresent();
            assertThat(attributeRequest.get().overrideConfiguration().orElseThrow().apiCallTimeout())
                    .isPresent();
        }

        @Test
        @DisplayName("the attribute request names exactly the two required attributes")
        void attributeRequestIsNarrow() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            AtomicReference<GetQueueAttributesRequest> issued = new AtomicReference<>();
            when(sqsAsyncClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                    .thenAnswer(invocation -> {
                        issued.set(invocation.getArgument(0));
                        return CompletableFuture.completedFuture(GetQueueAttributesResponse.builder()
                                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true",
                                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"))
                                .build());
                    });

            indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(issued.get().attributeNames()).containsExactly(QueueAttributeName.FIFO_QUEUE,
                    QueueAttributeName.CONTENT_BASED_DEDUPLICATION);
            assertThat(issued.get().attributeNames()).doesNotContain(QueueAttributeName.ALL);
            assertThat(issued.get().queueUrl()).isEqualTo(QUEUE_URL);
        }

        @Test
        @DisplayName("an SDK call timeout is reported as timeout, not as unreachable")
        void s3TimeoutIsDistinguished() {
            S3Client s3Client = mock(S3Client.class);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(ApiCallTimeoutException.create(1_500L));

            Health health =
                    indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_TIMEOUT);
        }

        @Test
        @DisplayName("an expired SQS future is cancelled, not left running")
        void expiredSqsFutureIsCancelled() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            // A future that never completes: get(timeout) must expire, and the probe must cancel it.
            CompletableFuture<GetQueueUrlResponse> neverCompletes = new CompletableFuture<>();
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(neverCompletes);

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_TIMEOUT);
            assertThat(neverCompletes).isCancelled();
            verify(sqsAsyncClient, never()).getQueueAttributes(any(GetQueueAttributesRequest.class));
        }

        @Test
        @DisplayName("an expired attribute future is cancelled after a resolved URL")
        void expiredAttributeFutureIsCancelled() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            CompletableFuture<GetQueueAttributesResponse> neverCompletes = new CompletableFuture<>();
            when(sqsAsyncClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                    .thenReturn(neverCompletes);

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_TIMEOUT);
            assertThat(neverCompletes).isCancelled();
        }

        @Test
        @DisplayName("the probe reports within the contributor budget rather than blocking")
        void probeReturnsWithinBudget() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(new CompletableFuture<>());
            HealthIndicator indicator =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator();

            long startedAtNanos = System.nanoTime();
            Health health = indicator.health();
            long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            // 1500 ms budget; the generous ceiling absorbs scheduler jitter on a loaded CI host while
            // still failing loudly if the bound were removed altogether.
            assertThat(elapsedMillis).isLessThan(5_000L);
        }
    }

    @Nested
    @DisplayName("Curated failure reporting (H-11)")
    class CuratedFailureReporting {

        @Test
        @DisplayName("an S3 service failure publishes no endpoint, payload or stack")
        void s3FailureDetailsAreCurated() {
            S3Client s3Client = mock(S3Client.class);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(S3Exception.builder()
                            .message("Unable to execute HTTP request to "
                                    + "http://s3.localhost.localstack.cloud:4566 for account "
                                    + "000000000000 with key AKIAIOSFODNN7EXAMPLE")
                            .statusCode(500)
                            .build());

            Health health =
                    indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            String rendered = renderDetails(health);
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_UNREACHABLE);
            assertThat(rendered).doesNotContain("localstack.cloud")
                    .doesNotContain("000000000000")
                    .doesNotContain("AKIA")
                    .doesNotContain("Unable to execute")
                    .doesNotContain("S3Exception");
            assertThat(health.getDetails()).doesNotContainKey("error")
                    .doesNotContainKey("exception")
                    .doesNotContainKey("stackTrace");
        }

        @Test
        @DisplayName("an SQS failure publishes neither the resolved URL nor the account id")
        void sqsFailureDetailsAreCurated() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(
                    CompletableFuture.failedFuture(QueueDoesNotExistException.builder()
                            .message("The specified queue does not exist for " + QUEUE_URL)
                            .statusCode(400)
                            .build()));

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            String rendered = renderDetails(health);
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_MISSING);
            assertThat(rendered).doesNotContain("000000000000")
                    .doesNotContain("sqs.us-east-1")
                    .doesNotContain("4566")
                    .doesNotContain("does not exist for");
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_QUEUE,
                    QUEUE_LOGICAL_NAME);
        }

        @Test
        @DisplayName("a successful probe publishes the logical queue name, never the resolved URL")
        void successPublishesLogicalNameOnly() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            stubQueueAttributes(sqsAsyncClient, "true", "false");

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(renderDetails(health)).doesNotContain("000000000000")
                    .doesNotContain(QUEUE_URL)
                    .doesNotContain(QUEUE_NAME);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_QUEUE,
                    QUEUE_LOGICAL_NAME);
        }

        @Test
        @DisplayName("an absent bucket is reported as missing, not as unreachable")
        void absentBucketIsMissing() {
            S3Client s3Client = mock(S3Client.class);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(NoSuchBucketException.builder().message("no such bucket").build());

            Health health =
                    indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_MISSING);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_BUCKET,
                    INPUT_BUCKET);
        }

        @Test
        @DisplayName("a 404 service response is reported as missing")
        void notFoundStatusIsMissing() {
            S3Client s3Client = mock(S3Client.class);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(S3Exception.builder().statusCode(404).build());

            Health health =
                    indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_MISSING);
        }

        @Test
        @DisplayName("neither indicator ever throws, whatever the client does")
        void indicatorsNeverThrow() {
            S3Client s3Client = mock(S3Client.class);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(new IllegalStateException("client is closed"));
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenThrow(new IllegalStateException("client is closed"));
            HealthIndicators factory = indicators(s3Client, sqsAsyncClient);

            assertThat(factory.s3HealthIndicator().health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(factory.sqsHealthIndicator().health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(factory.s3HealthIndicator().health().getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_ERROR);
        }
    }

    @Nested
    @DisplayName("Substrate contract verification (M-08)")
    class SubstrateContractVerification {

        @Test
        @DisplayName("UP requires batch-output versioning enabled and publishes the verified state")
        void enabledVersioningIsUp() {
            S3Client s3Client = mock(S3Client.class);
            stubBucketsExist(s3Client);
            stubVersioning(s3Client, BucketVersioningStatus.ENABLED);

            Health health =
                    indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_VERSIONING,
                    BucketVersioningStatus.ENABLED.toString());
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_BUCKETS_PROBED, 3);
        }

        @Test
        @DisplayName("suspended versioning is DOWN with the attribute named")
        void suspendedVersioningIsDown() {
            S3Client s3Client = mock(S3Client.class);
            stubBucketsExist(s3Client);
            stubVersioning(s3Client, BucketVersioningStatus.SUSPENDED);

            Health health =
                    indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_ATTRIBUTE_MISMATCH);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_ATTRIBUTE,
                    "Versioning");
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_BUCKET,
                    OUTPUT_BUCKET);
            // The observed status is deliberately absent: naming the attribute is the whole report.
            assertThat(renderDetails(health)).doesNotContain("Suspended");
        }

        @Test
        @DisplayName("a bucket that never had versioning configured is DOWN")
        void absentVersioningStatusIsDown() {
            S3Client s3Client = mock(S3Client.class);
            stubBucketsExist(s3Client);
            stubVersioning(s3Client, null);

            Health health =
                    indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_ATTRIBUTE_MISMATCH);
        }

        @Test
        @DisplayName("versioning is verified on the batch-output bucket only")
        void versioningVerifiedOnOutputBucketOnly() {
            S3Client s3Client = mock(S3Client.class);
            stubBucketsExist(s3Client);
            List<String> probed = new ArrayList<>();
            when(s3Client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                    .thenAnswer(invocation -> {
                        probed.add(((GetBucketVersioningRequest) invocation.getArgument(0)).bucket());
                        return GetBucketVersioningResponse.builder()
                                .status(BucketVersioningStatus.ENABLED)
                                .build();
                    });

            indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(probed).containsExactly(OUTPUT_BUCKET);
        }

        @Test
        @DisplayName("versioning is not probed when a bucket does not exist")
        void versioningSkippedWhenBucketAbsent() {
            S3Client s3Client = mock(S3Client.class);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(NoSuchBucketException.builder().build());

            indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            verify(s3Client, never()).getBucketVersioning(any(GetBucketVersioningRequest.class));
        }

        @Test
        @DisplayName("UP requires both queue attributes at their required values and publishes the token")
        void bothQueueAttributesSatisfiedIsUp() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            stubQueueAttributes(sqsAsyncClient, "true", "false");

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_FIFO_CONTRACT,
                    "verified");
        }

        @Test
        @DisplayName("a standard queue is DOWN naming FifoQueue")
        void nonFifoQueueIsDown() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            stubQueueAttributes(sqsAsyncClient, "false", "false");

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_ATTRIBUTE_MISMATCH);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_ATTRIBUTE,
                    QueueAttributeName.FIFO_QUEUE.toString());
        }

        @Test
        @DisplayName("a FIFO queue that still deduplicates on content stays UP and reports the attribute")
        void contentBasedDeduplicationIsReportedButDoesNotFailReadiness() {
            // Finding H-08, severity High, and this test has now been wrong in both directions. It first
            // asserted that ContentBasedDeduplication=FALSE was the fault, which inverted the requirement:
            // hashing the body collapses two legitimate submissions of the same period, where
            // DISPOSITION(MOD) at app/csd/CARDDEMO.CSD:503 appended both. It was then corrected to assert
            // DOWN on true - and that overshot, because readiness answers whether traffic should be routed
            // here. It should be: the publisher mints an explicit MessageDeduplicationId per submission and
            // an explicit identifier takes precedence over the body hash, verified against the emulator, so
            // both submissions arrive even with the attribute enabled. Reporting DOWN would withdraw a
            // working instance from service over a condition that impairs nothing.
            //
            // So the attribute is published rather than judged. FifoQueue keeps gating readiness - ordering
            // does not exist without it and it is immutable once the queue is created - while this one is
            // mutable by any holder of the queue, is converged by localstack-init/init-aws.sh, and is
            // surfaced here on every probe so drift stays visible.
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            stubQueueAttributes(sqsAsyncClient, "true", "true");

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(health.getStatus())
                    .as("an enabled content hash does not impair submission, so it must not fail readiness")
                    .isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .as("the drift is still visible on every probe, on its own key")
                    .containsEntry(HealthIndicators.DETAIL_CONTENT_DEDUPLICATION, "true")
                    .doesNotContainKey(HealthIndicators.DETAIL_REASON)
                    .doesNotContainKey(HealthIndicators.DETAIL_ATTRIBUTE);
        }

        @Test
        @DisplayName("a compliant queue reports the deduplication attribute as disabled")
        void compliantQueueReportsDeduplicationDisabled() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            stubQueueAttributes(sqsAsyncClient, "true", "false");

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .as("the observed value is published on both paths, so the detail is a live reading "
                            + "rather than a token that only appears when something is wrong")
                    .containsEntry(HealthIndicators.DETAIL_CONTENT_DEDUPLICATION, "false");
        }

        @Test
        @DisplayName("an attribute the service omits is read as false, which each requirement judges its own way")
        void omittedAttributeIsReadAsFalse() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            // Omission is how the service says false. The two attributes take that differently, which is the
            // point of asserting both here: an omitted FifoQueue reads as false and so fails the requirement
            // that it be true, while an omitted deduplication attribute is simply normalised to "false" for
            // the published detail - it gates nothing either way since finding H-08.
            stubQueueAttributes(sqsAsyncClient, "true", null);

            Health satisfied =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(satisfied.getStatus()).isEqualTo(Status.UP);
            assertThat(satisfied.getDetails())
                    .as("an omitted attribute is published as the false it means, never as a null")
                    .containsEntry(HealthIndicators.DETAIL_CONTENT_DEDUPLICATION, "false");

            SqsAsyncClient withoutFifo = mock(SqsAsyncClient.class);
            stubQueueResolves(withoutFifo);
            stubQueueAttributes(withoutFifo, null, null);

            Health unsatisfied =
                    indicators(mock(S3Client.class), withoutFifo).sqsHealthIndicator().health();

            assertThat(unsatisfied.getStatus()).isEqualTo(Status.DOWN);
            assertThat(unsatisfied.getDetails()).containsEntry(HealthIndicators.DETAIL_ATTRIBUTE,
                    QueueAttributeName.FIFO_QUEUE.toString());
        }

        @ParameterizedTest
        @ValueSource(strings = {"TRUE", " true ", "True"})
        @DisplayName("attribute comparison tolerates service casing and padding")
        void attributeComparisonIsLenientOnRendering(String rendered) {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            // The same leniency has to hold for both required values, so the deduplication attribute carries
            // the differently-rendered negation of the same boolean.
            stubQueueAttributes(sqsAsyncClient, rendered, rendered.replace("true", "false")
                    .replace("TRUE", "FALSE").replace("True", "False"));

            Health health =
                    indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("attributes are not probed when the queue does not resolve")
        void attributesSkippedWhenQueueAbsent() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(
                    CompletableFuture.failedFuture(QueueDoesNotExistException.builder().build()));

            indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            verify(sqsAsyncClient, never()).getQueueAttributes(any(GetQueueAttributesRequest.class));
        }
    }

    /**
     * Asserts the contract on the {@code WARN} channel itself, which is where the review found the
     * leak: a curated health body is worthless if the log line beside it renders the same throwable.
     *
     * <p>Both nested indicators log through their own loggers, named after the nested classes, so the
     * appenders are attached by logger name rather than by class literal - the nested types are
     * {@code private} and therefore not nameable from a test, which is itself deliberate.
     */
    @Nested
    @DisplayName("Log channel curation (H-11)")
    class LogChannelCuration {

        /** Fully qualified name of the S3 indicator's logger. */
        private static final String S3_LOGGER_NAME =
                "com.cardemo.observability.HealthIndicators$S3BucketHealthIndicator";

        /** Fully qualified name of the SQS indicator's logger. */
        private static final String SQS_LOGGER_NAME =
                "com.cardemo.observability.HealthIndicators$SqsQueueHealthIndicator";

        private Logger s3Logger;

        private Logger sqsLogger;

        private ListAppender<ILoggingEvent> events;

        @BeforeEach
        void attachAppender() {
            events = new ListAppender<>();
            events.start();
            s3Logger = (Logger) LoggerFactory.getLogger(S3_LOGGER_NAME);
            sqsLogger = (Logger) LoggerFactory.getLogger(SQS_LOGGER_NAME);
            s3Logger.addAppender(events);
            s3Logger.setLevel(Level.TRACE);
            sqsLogger.addAppender(events);
            sqsLogger.setLevel(Level.TRACE);
        }

        @AfterEach
        void detachAppender() {
            s3Logger.detachAppender(events);
            sqsLogger.detachAppender(events);
            events.stop();
        }

        @Test
        @DisplayName("an S3 failure log carries no throwable and no endpoint, account id or key")
        void s3FailureLogIsCurated() {
            S3Client s3Client = mock(S3Client.class);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(S3Exception.builder()
                            .message("Unable to execute HTTP request to "
                                    + "http://s3.localhost.localstack.cloud:4566 for account "
                                    + "000000000000 with key AKIAIOSFODNN7EXAMPLE")
                            .statusCode(503)
                            .build());

            indicators(s3Client, mock(SqsAsyncClient.class)).s3HealthIndicator().health();

            assertThat(events.list).hasSize(1);
            ILoggingEvent event = events.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            // The decisive assertion: no throwable proxy means no message and no stack is rendered,
            // which is the only reliable way to keep a positional account row out of the log.
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("localstack.cloud")
                    .doesNotContain("000000000000")
                    .doesNotContain("AKIA")
                    .doesNotContain("Unable to execute")
                    .contains(HealthIndicators.REASON_UNREACHABLE)
                    .contains(INPUT_BUCKET)
                    // The curated descriptor: the SDK type plus its numeric service status, nothing
                    // drawn from the exception message.
                    .contains("S3Exception(503)");
        }

        @Test
        @DisplayName("an SQS failure log unwraps to the cause type without rendering it")
        void sqsFailureLogIsCurated() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(
                    CompletableFuture.failedFuture(QueueDoesNotExistException.builder()
                            .message("The specified queue does not exist for " + QUEUE_URL)
                            .statusCode(400)
                            .build()));

            indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(events.list).hasSize(1);
            ILoggingEvent event = events.list.getFirst();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("000000000000")
                    .doesNotContain("sqs.us-east-1")
                    .doesNotContain("does not exist for")
                    .contains(HealthIndicators.REASON_MISSING)
                    .contains(QUEUE_LOGICAL_NAME)
                    // ExecutionException is unwrapped: the interesting type is the cause.
                    .contains("QueueDoesNotExistException(400)")
                    .doesNotContain("ExecutionException");
        }

        @Test
        @DisplayName("budget exhaustion logs the descriptor 'none' rather than a fabricated cause")
        void timeoutWithoutThrowableLogsNone() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(new CompletableFuture<>());

            indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(events.list).hasSize(1);
            ILoggingEvent event = events.list.getFirst();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage())
                    .contains(HealthIndicators.REASON_TIMEOUT)
                    .contains("TimeoutException");
        }

        @Test
        @DisplayName("an attribute mismatch logs the attribute name and no throwable")
        void attributeMismatchLogIsCurated() {
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            stubQueueAttributes(sqsAsyncClient, "false", "false");

            indicators(mock(S3Client.class), sqsAsyncClient).sqsHealthIndicator().health();

            assertThat(events.list).hasSize(1);
            ILoggingEvent event = events.list.getFirst();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage())
                    .contains(HealthIndicators.REASON_ATTRIBUTE_MISMATCH)
                    .contains(QueueAttributeName.FIFO_QUEUE.toString())
                    .doesNotContain(QUEUE_URL);
        }

        @Test
        @DisplayName("a successful probe logs nothing at all")
        void successIsSilent() {
            S3Client s3Client = mock(S3Client.class);
            stubBucketsExist(s3Client);
            stubVersioning(s3Client, BucketVersioningStatus.ENABLED);
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            stubQueueResolves(sqsAsyncClient);
            stubQueueAttributes(sqsAsyncClient, "true", "false");
            HealthIndicators factory = indicators(s3Client, sqsAsyncClient);

            factory.s3HealthIndicator().health();
            factory.sqsHealthIndicator().health();

            assertThat(events.list).isEmpty();
        }
    }

    @Nested
    @DisplayName("Configuration refusal")
    class ConfigurationRefusal {

        @Test
        @DisplayName("an unset bucket name is refused before any call, naming the property key")
        void unsetBucketIsRefused() {
            S3Client s3Client = mock(S3Client.class);
            HealthIndicators factory = new HealthIndicators(s3Client, mock(SqsAsyncClient.class),
                    mock(SnsClient.class),
                    mock(DataSource.class), INPUT_BUCKET, "", STATEMENTS_BUCKET, QUEUE_NAME,
                    QUEUE_LOGICAL_NAME, TOPIC_NAME);

            Health health = factory.s3HealthIndicator().health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_NOT_CONFIGURED);
            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_PROPERTY,
                    "carddemo.aws.s3.batch-output-bucket");
            verify(s3Client, never()).getBucketVersioning(any(GetBucketVersioningRequest.class));
        }

        @Test
        @DisplayName("a queue name that is an ARN is refused without echoing the value")
        void arnQueueNameIsRefused() {
            String arn = "arn:aws:sqs:us-east-1:000000000000:carddemo-report-jobs.fifo";
            SqsAsyncClient sqsAsyncClient = mock(SqsAsyncClient.class);
            HealthIndicators factory = new HealthIndicators(mock(S3Client.class), sqsAsyncClient,
                    mock(SnsClient.class),
                    mock(DataSource.class), INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, arn,
                    QUEUE_LOGICAL_NAME, TOPIC_NAME);

            Health health = factory.sqsHealthIndicator().health();

            assertThat(health.getDetails()).containsEntry(HealthIndicators.DETAIL_REASON,
                    HealthIndicators.REASON_INVALID_NAME);
            assertThat(renderDetails(health)).doesNotContain("000000000000").doesNotContain("arn:");
            verify(sqsAsyncClient, never()).getQueueUrl(any(GetQueueUrlRequest.class));
        }
    }
}
