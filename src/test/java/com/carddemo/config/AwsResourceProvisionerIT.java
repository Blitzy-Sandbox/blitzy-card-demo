package com.carddemo.config;

import java.util.List;

import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.batch.AbstractBatchIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime verification of {@link AwsResourceProvisioner} against a real LocalStack instance
 * (Testcontainers; <strong>zero live AWS</strong>, AAP&nbsp;&sect;0.7.7). It proves the two migration
 * guarantees the provisioner is responsible for &mdash; the ones the batch/online code alone cannot
 * assure &mdash; are actually applied at runtime:
 *
 * <ul>
 *   <li><strong>F1 &mdash; GDG&nbsp;&rarr;&nbsp;versioned S3.</strong> After the provisioner runs, all
 *       three batch-staging buckets ({@code carddemo-batch-input}, {@code carddemo-batch-output},
 *       {@code carddemo-statements}) report {@code Versioning=Enabled}.</li>
 *   <li><strong>F3/F4 &mdash; poison-message containment.</strong> The report dead-letter FIFO queue
 *       ({@code carddemo-report-jobs-dlq.fifo}) exists and a {@code RedrivePolicy} pointing at it (with
 *       the configured {@code maxReceiveCount}) is attached to the request queue
 *       ({@code carddemo-report-jobs.fifo}).</li>
 * </ul>
 *
 * <h2>How the {@code @Profile("!test")} provisioner is exercised under the {@code test} profile</h2>
 * <p>{@link AwsResourceProvisioner} is annotated {@code @Profile("!test")} so it never auto-runs during
 * integration tests (which must self-provision per &sect;0.7.7). This test therefore does <em>not</em>
 * rely on Spring to instantiate it; instead it constructs the provisioner directly with the base
 * class's LocalStack-backed {@link #s3Client}/{@link #sqsAsyncClient} (wrapped in trivial
 * {@link ObjectProvider}s) and invokes {@link AwsResourceProvisioner#run} explicitly, then asserts the
 * resulting AWS state. This keeps the production auto-run gated off in tests while still exercising the
 * provisioner's real logic against real S3/SQS control-plane calls.</p>
 *
 * <h2>Listener/queue ordering</h2>
 * <p>Because {@link AbstractBatchIntegrationTest} does not exclude {@code SqsAutoConfiguration}, the
 * {@code @SqsListener} on the report launcher starts with the context. As in {@link com.carddemo.batch}'s
 * report ITs, the request FIFO queue is pre-created in a {@link DynamicPropertySource} hook (distinct
 * from any sibling's, giving this class its own isolated context) so the listener finds it on startup;
 * the listener is stopped in teardown before the queues are removed.</p>
 *
 * <p>Source COBOL/JCL is referenced read-only at commit SHA {@code 27d6c6f}; design rationale
 * (decisions {@code D-025}/{@code D-026}) lives in {@code docs/decision-log.md}, not in these comments
 * (Explainability rule).</p>
 *
 * @see AwsResourceProvisioner
 * @see AbstractBatchIntegrationTest
 */
@DisplayName("AwsResourceProvisioner IT — S3 versioning (F1) + report DLQ & RedrivePolicy (F3/F4) on LocalStack")
class AwsResourceProvisionerIT extends AbstractBatchIntegrationTest {

    /** Report dead-letter FIFO queue name provisioned by the runner (matches its default). */
    private static final String QUEUE_REPORT_DLQ = "carddemo-report-jobs-dlq.fifo";

    /** Redrive threshold asserted on the request queue's policy (matches the runner default). */
    private static final int EXPECTED_MAX_RECEIVE_COUNT = 5;

    /** Real JSON mapper used both to build (in the runner) and parse (here) the redrive policy. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * SQS listener registry, stopped before queue deletion so the {@code @SqsListener} is not left
     * polling a removed queue for the remainder of the JVM's test run.
     */
    @Autowired
    private MessageListenerContainerRegistry sqsListenerContainerRegistry;

    /**
     * Pre-creates the report request FIFO queue before the context refreshes so the auto-started
     * {@code @SqsListener} binds successfully; distinct from any sibling IT's hook so this class gets its
     * own isolated application context.
     *
     * @param registry the Spring dynamic-property registry (unused; the queue is a pre-refresh side effect)
     */
    @DynamicPropertySource
    static void createReportQueueBeforeContextRefresh(final DynamicPropertyRegistry registry) {
        // Delegate to the shared, idempotent + normalizing creator (see
        // AbstractBatchIntegrationTest#ensureReportRequestFifoQueue). The batch IT suite shares one static
        // LocalStack across all subclass tests and application-test.yml sets queue-not-found-strategy=create,
        // so a sibling full-context IT's @SqsListener may have already auto-created this FIFO queue WITHOUT
        // ContentBasedDeduplication; the shared helper tolerates that (and any RedrivePolicy left by a prior
        // run of this very IT) and forces CBD=true. The provisioner under test then attaches its DLQ
        // RedrivePolicy on top, which this IT asserts.
        ensureReportRequestFifoQueue();
    }

    /**
     * Runs the provisioner against LocalStack and asserts the resulting S3 versioning (F1) and SQS
     * DLQ/redrive (F3/F4) state.
     */
    @Test
    @DisplayName("run() enables versioning on all 3 buckets and attaches a DLQ RedrivePolicy to the report queue")
    void provisionerEnablesVersioningAndDeadLetterRedrive() throws Exception {
        // Build the provisioner with the base class's LocalStack-backed clients and the production
        // logical names; it will create the buckets (none exist yet) and the DLQ, and attach the policy.
        final AwsConfig.CardDemoAwsProperties props = new AwsConfig.CardDemoAwsProperties(
                new AwsConfig.CardDemoAwsProperties.S3(BUCKET_INPUT, BUCKET_OUTPUT, BUCKET_STATEMENTS),
                new AwsConfig.CardDemoAwsProperties.Sqs(QUEUE_REPORT_FIFO));
        final AwsResourceProvisioner provisioner = new AwsResourceProvisioner(
                fixedProvider(s3Client),
                fixedProvider(sqsAsyncClient),
                props,
                objectMapper,
                true,
                QUEUE_REPORT_DLQ,
                EXPECTED_MAX_RECEIVE_COUNT);

        provisioner.run(null);

        // (F1) Every batch-staging bucket now has versioning enabled.
        for (final String bucket : List.of(BUCKET_INPUT, BUCKET_OUTPUT, BUCKET_STATEMENTS)) {
            final GetBucketVersioningResponse versioning =
                    s3Client.getBucketVersioning(request -> request.bucket(bucket));
            assertThat(versioning.status())
                    .as("bucket %s must have versioning ENABLED (GDG -> versioned object, F1)", bucket)
                    .isEqualTo(BucketVersioningStatus.ENABLED);
        }

        // (F3/F4) The DLQ exists and its ARN is resolvable.
        final String dlqUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(QUEUE_REPORT_DLQ)).get().queueUrl();
        final String dlqArn = sqsAsyncClient.getQueueAttributes(r -> r.queueUrl(dlqUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)).get()
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        assertThat(dlqArn).as("report DLQ ARN must be resolvable").isNotBlank();

        // (F3/F4) The request queue carries a RedrivePolicy targeting the DLQ with the configured count.
        final String requestQueueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(QUEUE_REPORT_FIFO)).get().queueUrl();
        final GetQueueAttributesResponse requestAttrs = sqsAsyncClient.getQueueAttributes(r -> r.queueUrl(requestQueueUrl)
                        .attributeNames(QueueAttributeName.REDRIVE_POLICY)).get();
        final String redrivePolicyJson = requestAttrs.attributes().get(QueueAttributeName.REDRIVE_POLICY);
        assertThat(redrivePolicyJson)
                .as("report request queue must carry a RedrivePolicy")
                .isNotBlank();
        final JsonNode redrive = objectMapper.readTree(redrivePolicyJson);
        assertThat(redrive.path("deadLetterTargetArn").asText())
                .as("RedrivePolicy must target the report DLQ ARN")
                .isEqualTo(dlqArn);
        assertThat(redrive.path("maxReceiveCount").asText())
                .as("RedrivePolicy maxReceiveCount must equal the configured threshold")
                .isEqualTo(Integer.toString(EXPECTED_MAX_RECEIVE_COUNT));
    }

    /**
     * Tears down the buckets and queues this test provisioned (§0.7.7), stopping the SQS listener first.
     */
    @AfterEach
    void teardown() {
        sqsListenerContainerRegistry.stop();
        deleteBucketRecursively(BUCKET_INPUT);
        deleteBucketRecursively(BUCKET_OUTPUT);
        deleteBucketRecursively(BUCKET_STATEMENTS);
        deleteQueue(QUEUE_REPORT_FIFO);
        deleteQueue(QUEUE_REPORT_DLQ);
    }

    /**
     * Wraps a fixed instance in a minimal {@link ObjectProvider}. Only {@link ObjectProvider#getIfAvailable()}
     * is exercised by {@link AwsResourceProvisioner}; every other method retains its interface default.
     *
     * @param instance the instance to return; must not be {@code null}
     * @param <T>      the provided type
     * @return an {@code ObjectProvider} that always resolves to {@code instance}
     */
    private static <T> ObjectProvider<T> fixedProvider(final T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }
        };
    }
}
