package com.carddemo.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

/**
 * Idempotent startup provisioner that reconciles the AWS resources the migrated batch pipeline
 * depends on so the delivered runtime behaviour matches the target architecture &mdash; specifically
 * the two migration mappings that the online/batch code alone cannot guarantee:
 *
 * <ul>
 *   <li><strong>GDG generations &rarr; versioned S3 objects</strong> (AAP&nbsp;&sect;0.7.7,
 *       &sect;0.8.5). Every batch-staging bucket ({@code carddemo-batch-input},
 *       {@code carddemo-batch-output}, {@code carddemo-statements}) has S3 <em>bucket versioning</em>
 *       enabled, so each re-run of a job that re-writes the same object key preserves the prior
 *       payload as a distinct, retrievable version &mdash; the modern equivalent of a
 *       {@code DALYREJS(+1)} / statement GDG generation. Without this, a re-run silently overwrites
 *       the previous generation.</li>
 *   <li><strong>Poison-message containment</strong> for the CICS TDQ&nbsp;&rarr;&nbsp;SQS FIFO report
 *       bridge. A dead-letter FIFO queue is created and a {@code RedrivePolicy} is attached to the
 *       report request queue, so a report request that repeatedly fails processing is moved to the
 *       DLQ after a bounded number of receives instead of redelivering forever.</li>
 * </ul>
 *
 * <h2>Scope and safety</h2>
 * <p>This runner is {@code @Profile("!test")}: it runs for the {@code local} and {@code prod}
 * profiles but <strong>never</strong> under the {@code test} profile, preserving the AAP&nbsp;&sect;0.7.7
 * contract that integration tests self-provision (and tear down) their own LocalStack resources and
 * never depend on pre-existing state. It performs <em>startup-only</em> reconciliation, not per-job
 * work, so a job that deletes/loses a bucket mid-run still fails loudly rather than being silently
 * "repaired".</p>
 *
 * <p>Every action is best-effort and idempotent: existing resources are detected and left in place,
 * versioning/redrive settings are (re)applied to the desired state, and any individual failure is
 * logged and skipped rather than aborting application startup (buckets/queues may legitimately be
 * managed by external IaC with the app lacking create permissions). The whole runner can be disabled
 * with {@code carddemo.aws.provisioning.enabled=false}. No endpoint, region, ARN, or credential is
 * hardcoded &mdash; the injected clients carry the LocalStack wiring from {@code spring.cloud.aws.*}
 * (zero live AWS). Rationale and alternatives are recorded in {@code docs/decision-log.md}
 * (Explainability rule); source traceability anchor commit SHA {@code 27d6c6f}.</p>
 *
 * @see AwsConfig.CardDemoAwsProperties
 */
@Component
@Profile("!test")
@Order(Ordered.LOWEST_PRECEDENCE)
public class AwsResourceProvisioner implements ApplicationRunner {

    /** SLF4J logger; structured JSON with the MDC {@code correlationId} is applied by logback. */
    private static final Logger log = LoggerFactory.getLogger(AwsResourceProvisioner.class);

    /** Bounded deadline (ms) applied to each asynchronous SQS control-plane call. */
    private static final long SQS_CALL_TIMEOUT_MS = 10_000L;

    private final ObjectProvider<S3Client> s3ClientProvider;
    private final ObjectProvider<SqsAsyncClient> sqsAsyncClientProvider;
    private final AwsConfig.CardDemoAwsProperties awsProperties;
    private final ObjectMapper objectMapper;

    /** Master switch for the whole reconciliation ({@code carddemo.aws.provisioning.enabled}). */
    private final boolean provisioningEnabled;

    /** Logical name of the report dead-letter FIFO queue ({@code carddemo.aws.sqs.report-dlq}). */
    private final String reportDlqName;

    /** {@code maxReceiveCount} for the report queue's {@code RedrivePolicy}. */
    private final int maxReceiveCount;

    /**
     * Creates the provisioner with tolerant client providers and the externalized resource names.
     *
     * @param s3ClientProvider       provider for the auto-configured {@link S3Client}; tolerant of
     *                               absence (S3 auto-configuration disabled)
     * @param sqsAsyncClientProvider provider for the auto-configured {@link SqsAsyncClient}; tolerant
     *                               of absence (SQS auto-configuration disabled)
     * @param awsProperties          bound logical bucket/queue names ({@code carddemo.aws.*})
     * @param objectMapper           application JSON mapper, used to render the {@code RedrivePolicy}
     * @param provisioningEnabled    master switch (default {@code true})
     * @param reportDlqName          report DLQ FIFO name (default {@code carddemo-report-jobs-dlq.fifo})
     * @param maxReceiveCount        redrive threshold (default {@code 5})
     */
    public AwsResourceProvisioner(
            final ObjectProvider<S3Client> s3ClientProvider,
            final ObjectProvider<SqsAsyncClient> sqsAsyncClientProvider,
            final AwsConfig.CardDemoAwsProperties awsProperties,
            final ObjectMapper objectMapper,
            @Value("${carddemo.aws.provisioning.enabled:true}") final boolean provisioningEnabled,
            @Value("${carddemo.aws.sqs.report-dlq:carddemo-report-jobs-dlq.fifo}") final String reportDlqName,
            @Value("${carddemo.aws.provisioning.max-receive-count:5}") final int maxReceiveCount) {
        this.s3ClientProvider = s3ClientProvider;
        this.sqsAsyncClientProvider = sqsAsyncClientProvider;
        this.awsProperties = awsProperties;
        this.objectMapper = objectMapper;
        this.provisioningEnabled = provisioningEnabled;
        this.reportDlqName = reportDlqName;
        this.maxReceiveCount = maxReceiveCount;
    }

    /**
     * Reconciles S3 bucket versioning and the SQS report DLQ / redrive policy on application startup.
     *
     * @param args the incoming application arguments (unused)
     */
    @Override
    public void run(final ApplicationArguments args) {
        if (!provisioningEnabled) {
            log.info("AWS resource provisioning disabled (carddemo.aws.provisioning.enabled=false); "
                    + "skipping S3 versioning and SQS DLQ reconciliation");
            return;
        }
        provisionBucketVersioning();
        provisionReportDeadLetterQueue();
    }

    // ---------------------------------------------------------------------------------------------
    // S3 — GDG generations -> versioned objects (F1)
    // ---------------------------------------------------------------------------------------------

    /**
     * Ensures each batch-staging bucket exists and has versioning enabled. Each bucket is handled
     * independently so one failure does not block the others.
     */
    private void provisionBucketVersioning() {
        final S3Client s3 = s3ClientProvider.getIfAvailable();
        if (s3 == null) {
            log.warn("No S3Client bean available; skipping S3 bucket-versioning provisioning");
            return;
        }
        final AwsConfig.CardDemoAwsProperties.S3 s3Names = awsProperties.s3();
        if (s3Names == null) {
            log.warn("No carddemo.aws.s3.* bucket names bound; skipping S3 bucket-versioning provisioning");
            return;
        }
        for (final String bucket : List.of(
                s3Names.inputBucket(), s3Names.outputBucket(), s3Names.statementsBucket())) {
            if (bucket == null || bucket.isBlank()) {
                continue;
            }
            try {
                ensureBucketExists(s3, bucket);
                enableBucketVersioning(s3, bucket);
            } catch (final RuntimeException e) {
                log.error("Failed to provision versioning for S3 bucket '{}' (continuing)", bucket, e);
            }
        }
    }

    /**
     * Ensures {@code bucket} exists, creating it when a {@code HEAD} probe reports it missing.
     *
     * @param s3     the S3 client
     * @param bucket the bucket name to ensure
     */
    private void ensureBucketExists(final S3Client s3, final String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (final NoSuchBucketException e) {
            createBucket(s3, bucket);
        } catch (final S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket(s3, bucket);
            } else {
                throw e;
            }
        }
    }

    /**
     * Creates {@code bucket}. A concurrent creation (already-exists) is treated as success.
     *
     * @param s3     the S3 client
     * @param bucket the bucket name to create
     */
    private void createBucket(final S3Client s3, final String bucket) {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created missing S3 bucket '{}'", bucket);
        } catch (final S3Exception e) {
            // BucketAlreadyOwnedByYou / BucketAlreadyExists => another instance won the race; fine.
            log.info("S3 bucket '{}' already present (create race, {}); continuing", bucket,
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : e.getClass().getSimpleName());
        }
    }

    /**
     * Enables versioning on {@code bucket} (idempotent) and logs the resulting status.
     *
     * @param s3     the S3 client
     * @param bucket the bucket name to enable versioning on
     */
    private void enableBucketVersioning(final S3Client s3, final String bucket) {
        s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());
        final GetBucketVersioningResponse status = s3.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(bucket).build());
        log.info("S3 bucket '{}' versioning status={} (GDG generation -> versioned object)",
                bucket, status.statusAsString());
    }

    // ---------------------------------------------------------------------------------------------
    // SQS — report DLQ + RedrivePolicy (F3/F4 poison-message containment)
    // ---------------------------------------------------------------------------------------------

    /**
     * Ensures the report dead-letter FIFO queue exists and attaches a {@code RedrivePolicy} to the
     * report request queue pointing at it. Both are FIFO (a FIFO source queue requires a FIFO DLQ).
     */
    private void provisionReportDeadLetterQueue() {
        final SqsAsyncClient sqs = sqsAsyncClientProvider.getIfAvailable();
        if (sqs == null) {
            log.warn("No SqsAsyncClient bean available; skipping report DLQ/redrive provisioning");
            return;
        }
        final AwsConfig.CardDemoAwsProperties.Sqs sqsNames = awsProperties.sqs();
        final String reportQueue = (sqsNames != null) ? sqsNames.reportQueue() : null;
        if (reportQueue == null || reportQueue.isBlank()) {
            log.warn("No carddemo.aws.sqs.report-queue bound; skipping report DLQ/redrive provisioning");
            return;
        }
        try {
            // 1) Create the DLQ (FIFO, content-based dedup) if absent, and resolve its ARN.
            final Map<QueueAttributeName, String> fifoAttrs = new LinkedHashMap<>();
            fifoAttrs.put(QueueAttributeName.FIFO_QUEUE, "true");
            fifoAttrs.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true");
            final String dlqUrl = sqs.createQueue(CreateQueueRequest.builder()
                            .queueName(reportDlqName)
                            .attributes(fifoAttrs)
                            .build())
                    .get(SQS_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .queueUrl();
            final String dlqArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(dlqUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN)
                            .build())
                    .get(SQS_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .attributes()
                    .get(QueueAttributeName.QUEUE_ARN);
            log.info("Report DLQ ready: name='{}' arn='{}'", reportDlqName, dlqArn);

            // 2) Resolve the request queue URL (created here if the listener has not yet created it).
            final String requestQueueUrl = resolveOrCreateFifoQueue(sqs, reportQueue, fifoAttrs);

            // 3) Attach the RedrivePolicy so poison messages are dead-lettered after maxReceiveCount.
            final String redrivePolicy = objectMapper.writeValueAsString(Map.of(
                    "deadLetterTargetArn", dlqArn,
                    "maxReceiveCount", Integer.toString(maxReceiveCount)));
            sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                            .queueUrl(requestQueueUrl)
                            .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy))
                            .build())
                    .get(SQS_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            log.info("Attached RedrivePolicy to report queue '{}' (maxReceiveCount={} -> DLQ '{}')",
                    reportQueue, maxReceiveCount, reportDlqName);
        } catch (final JsonProcessingException e) {
            log.error("Failed to render the RedrivePolicy JSON for report queue '{}' (continuing)",
                    reportQueue, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted provisioning the report DLQ/redrive policy (continuing)", e);
        } catch (final RuntimeException | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            log.error("Failed to provision the report DLQ/redrive policy for queue '{}' (continuing)",
                    reportQueue, e);
        }
    }

    /**
     * Resolves the URL of an existing FIFO queue, creating it (FIFO, content-based dedup) when absent.
     *
     * @param sqs       the async SQS client
     * @param queueName the FIFO queue name
     * @param fifoAttrs the FIFO creation attributes
     * @return the resolved queue URL
     * @throws InterruptedException                       if interrupted awaiting an SQS call
     * @throws java.util.concurrent.ExecutionException    if an SQS call fails
     * @throws java.util.concurrent.TimeoutException       if an SQS call exceeds the bounded deadline
     */
    private String resolveOrCreateFifoQueue(final SqsAsyncClient sqs, final String queueName,
                                            final Map<QueueAttributeName, String> fifoAttrs)
            throws InterruptedException, java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
        try {
            return sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                    .get(SQS_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .queueUrl();
        } catch (final java.util.concurrent.ExecutionException e) {
            // Queue absent (QueueDoesNotExistException wrapped in ExecutionException): create it.
            return sqs.createQueue(CreateQueueRequest.builder()
                            .queueName(queueName)
                            .attributes(fifoAttrs)
                            .build())
                    .get(SQS_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .queueUrl();
        }
    }
}
