package com.carddemo.observability;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;

/**
 * Composite Spring Boot Actuator {@link HealthIndicator} beans for the CardDemo migration's
 * external runtime dependencies: PostgreSQL, AWS S3, and AWS SQS.
 *
 * <p>Three indicators are contributed to the {@code /actuator/health} endpoint, one per
 * dependency. Spring Boot derives each health-component key from the bean name by stripping the
 * {@code HealthIndicator} suffix, so these beans surface under the keys {@code postgres},
 * {@code s3}, and {@code sqs}:</p>
 *
 * <ul>
 *   <li>{@code postgres} &mdash; borrows a pooled JDBC {@link Connection}, validates it within a
 *       bounded timeout, and reports the database product name and version.</li>
 *   <li>{@code s3} &mdash; issues a {@code HeadBucket} against the configured input bucket,
 *       verifying both connectivity and that the bucket exists and is accessible.</li>
 *   <li>{@code sqs} &mdash; resolves the configured report-queue URL within a bounded timeout.</li>
 * </ul>
 *
 * <p>The PostgreSQL indicator is intentionally keyed {@code postgres} (not {@code db}) so that it is
 * <em>additive</em> to&mdash;and does not override&mdash;Spring Boot's auto-configured {@code db}
 * {@code DataSourceHealthIndicator}. Boot's {@code db} indicator runs the standard validation
 * query; this {@code postgres} indicator contributes a bounded {@link Connection#isValid(int)}
 * check plus database product/version details.</p>
 *
 * <p>Kubernetes-style probes are enabled by the application configuration
 * ({@code management.endpoint.health.probes.enabled: true}), which exposes
 * {@code /actuator/health/liveness} and {@code /actuator/health/readiness}. By default the
 * {@code liveness} group contains only {@code livenessState}; external dependency outages must not
 * fail liveness, otherwise Kubernetes would restart an otherwise healthy pod. To fold these
 * dependency checks into readiness, the recommended configuration-owned setting (declared in
 * {@code application.yml}, not in this class) is:
 * {@code management.endpoint.health.group.readiness.include: readinessState,db,postgres,s3,sqs}.
 * This class only supplies the indicators with stable keys so the configuration owner can group
 * them.</p>
 *
 * <p>Every probe path returns a {@link Health} value (UP, DOWN, or UNKNOWN) and never propagates an
 * exception. The {@link DataSource} and AWS clients are injected defensively through
 * {@link ObjectProvider}, so a bean that is absent in a particular profile or test slice degrades
 * to {@link Health#unknown()} instead of failing context startup. Client construction, endpoint,
 * region, and credentials are owned by the AWS configuration and {@code application*.yml}; this
 * class never constructs a client and never embeds an endpoint or secret. Health details are
 * limited to bucket/queue/database identifiers and carry no credentials.</p>
 *
 * <p>Cross-cutting observability infrastructure with no COBOL equivalent; the observed system is
 * referenced by source commit SHA {@code 27d6c6f} (the COBOL sources are not copied into the
 * target).</p>
 */
@Configuration(proxyBeanMethods = false)
public class HealthIndicators {

    /**
     * Upper bound, in seconds, for the JDBC {@link Connection#isValid(int)} validity check so a
     * stalled database cannot block the health endpoint.
     */
    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

    /**
     * Upper bound, in seconds, applied to the asynchronous AWS calls (SQS queue-URL resolution) so
     * a hung dependency cannot block the health endpoint.
     */
    private static final int AWS_TIMEOUT_SECONDS = 3;

    /**
     * Health indicator for PostgreSQL, surfaced under the {@code postgres} key. Additive to Boot's
     * built-in {@code db} indicator; reports the database product name and version on success.
     *
     * @param dataSourceProvider defensive provider for the JDBC {@link DataSource} bean
     * @return an indicator reporting UP with product/version, DOWN on validation failure or
     *         {@link SQLException}, or UNKNOWN when no {@link DataSource} is configured
     */
    @Bean
    public HealthIndicator postgresHealthIndicator(ObjectProvider<DataSource> dataSourceProvider) {
        return () -> {
            DataSource dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource == null) {
                return Health.unknown().withDetail("datasource", "not configured").build();
            }
            try (Connection connection = dataSource.getConnection()) {
                boolean valid = connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
                if (!valid) {
                    return Health.down().withDetail("database", "connection validation failed").build();
                }
                DatabaseMetaData metaData = connection.getMetaData();
                return Health.up()
                        .withDetail("database", metaData.getDatabaseProductName())
                        .withDetail("version", metaData.getDatabaseProductVersion())
                        .build();
            } catch (SQLException ex) {
                return Health.down(ex).build();
            }
        };
    }

    /**
     * Health indicator for AWS S3, surfaced under the {@code s3} key. A successful
     * {@code HeadBucket} confirms both connectivity and that the configured input bucket exists and
     * is accessible, which is an appropriate readiness signal.
     *
     * @param s3Provider defensive provider for the synchronous {@link S3Client} bean
     * @param bucket     the configured input bucket name
     * @return an indicator reporting UP with the bucket name, DOWN on {@link SdkException}, or
     *         UNKNOWN when no {@link S3Client} is configured
     */
    @Bean
    public HealthIndicator s3HealthIndicator(ObjectProvider<S3Client> s3Provider,
            @Value("${carddemo.aws.s3.bucket-input:carddemo-batch-input}") String bucket) {
        return () -> {
            S3Client s3Client = s3Provider.getIfAvailable();
            if (s3Client == null) {
                return Health.unknown().withDetail("s3", "not configured").build();
            }
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                return Health.up().withDetail("bucket", bucket).build();
            } catch (SdkException ex) {
                return Health.down(ex).withDetail("bucket", bucket).build();
            }
        };
    }

    /**
     * Health indicator for AWS SQS, surfaced under the {@code sqs} key. The asynchronous queue-URL
     * resolution is bounded by {@link #AWS_TIMEOUT_SECONDS}. The {@link InterruptedException} path
     * restores the thread's interrupt flag before reporting DOWN.
     *
     * @param sqsProvider defensive provider for the asynchronous {@link SqsAsyncClient} bean
     * @param queueName   the configured report-queue name
     * @return an indicator reporting UP with the queue name and URL, DOWN on interruption,
     *         execution failure, or timeout, or UNKNOWN when no {@link SqsAsyncClient} is configured
     */
    @Bean
    public HealthIndicator sqsHealthIndicator(ObjectProvider<SqsAsyncClient> sqsProvider,
            @Value("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}") String queueName) {
        return () -> {
            SqsAsyncClient sqsClient = sqsProvider.getIfAvailable();
            if (sqsClient == null) {
                return Health.unknown().withDetail("sqs", "not configured").build();
            }
            try {
                GetQueueUrlResponse response = sqsClient
                        .getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                        .get(AWS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return Health.up()
                        .withDetail("queue", queueName)
                        .withDetail("queueUrl", response.queueUrl())
                        .build();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Health.down(ex).withDetail("queue", queueName).build();
            } catch (ExecutionException | TimeoutException ex) {
                return Health.down(ex).withDetail("queue", queueName).build();
            }
        };
    }
}
