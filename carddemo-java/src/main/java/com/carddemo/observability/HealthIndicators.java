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
 * Composite Spring Boot Actuator {@link HealthIndicator} beans for the CardDemo Java
 * migration, contributing dependency-level readiness signals to {@code /actuator/health}.
 *
 * <p>Three indicators are provided, each keyed by its bean/method name with the
 * {@code HealthIndicator} suffix stripped by Boot's health registry:
 * <ul>
 *   <li>{@code postgres} &mdash; PostgreSQL connectivity via a bounded
 *       {@link Connection#isValid(int)} probe, plus database product and version detail.</li>
 *   <li>{@code s3} &mdash; Amazon S3 reachability and existence/accessibility of the
 *       configured input bucket via a {@code HeadBucket} call.</li>
 *   <li>{@code sqs} &mdash; Amazon SQS reachability and existence of the configured report
 *       queue via a {@code GetQueueUrl} call.</li>
 * </ul>
 *
 * <p>The PostgreSQL indicator is intentionally named {@code postgres} (not {@code db}) so it is
 * <em>additive</em> to Boot's auto-configured {@code db} {@code DataSourceHealthIndicator}
 * rather than overriding it; it supplies the bounded validity probe and product/version
 * details alongside the built-in indicator.
 *
 * <p>Boot's Kubernetes-style probes are enabled in {@code application.yml}
 * ({@code management.endpoint.health.probes.enabled: true}), exposing
 * {@code /actuator/health/liveness} and {@code /actuator/health/readiness}. The
 * {@code liveness} group contains only {@code livenessState} by default, which is correct:
 * an external dependency outage must not fail liveness, or Kubernetes would terminate an
 * otherwise healthy pod. To surface these dependency checks under readiness, the recommended
 * configuration &mdash; owned by {@code application.yml}, not this class &mdash; is
 * {@code management.endpoint.health.group.readiness.include: readinessState,db,postgres,s3,sqs}.
 * This class only supplies the indicators with the stable keys {@code postgres}, {@code s3},
 * and {@code sqs} so they can be grouped there.
 *
 * <p>All clients are injected by type ({@link S3Client}, {@link SqsAsyncClient},
 * {@link DataSource}); endpoint, region, and credentials are owned by the
 * {@code application*.yml} profiles (LocalStack locally) and are never constructed or
 * hardcoded here. {@link ObjectProvider} injection lets a profile or test slice that lacks a
 * given client degrade to {@link Health#unknown()} instead of failing context startup. Each
 * probe is time-bounded and never propagates an exception; every path returns a {@link Health}.
 *
 * <p>Cross-cutting observability infrastructure with no COBOL equivalent; the observed system
 * is referenced via source commit SHA {@code 27d6c6f} (COBOL not copied).
 */
@Configuration(proxyBeanMethods = false)
public class HealthIndicators {

    /** Maximum seconds the JDBC driver may take to validate a connection. */
    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

    /** Maximum seconds to await an asynchronous AWS control-plane call. */
    private static final int AWS_TIMEOUT_SECONDS = 3;

    /**
     * PostgreSQL connectivity indicator (component key {@code postgres}). Performs a bounded
     * {@link Connection#isValid(int)} check and, on success, reports the database product name
     * and version. Additive to Boot's built-in {@code db} indicator (does not replace it).
     *
     * @param dataSourceProvider provider for the application {@link DataSource}; may be empty
     * @return a {@link HealthIndicator} reporting UP, DOWN, or UNKNOWN for PostgreSQL
     */
    @Bean
    public HealthIndicator postgresHealthIndicator(ObjectProvider<DataSource> dataSourceProvider) {
        return () -> {
            DataSource ds = dataSourceProvider.getIfAvailable();
            if (ds == null) {
                return Health.unknown().withDetail("datasource", "not configured").build();
            }
            // try-with-resources guarantees the probe connection is returned to the pool.
            try (Connection connection = ds.getConnection()) {
                boolean valid = connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
                if (!valid) {
                    return Health.down().withDetail("database", "connection validation failed").build();
                }
                DatabaseMetaData md = connection.getMetaData();
                return Health.up()
                        .withDetail("database", md.getDatabaseProductName())
                        .withDetail("version", md.getDatabaseProductVersion())
                        .build();
            } catch (SQLException ex) {
                return Health.down(ex).build();
            }
        };
    }

    /**
     * Amazon S3 indicator (component key {@code s3}). Issues {@code HeadBucket} against the
     * configured input bucket, which verifies both connectivity and that the bucket exists and
     * is accessible &mdash; an appropriate readiness signal for batch staging.
     *
     * @param s3Provider provider for the synchronous {@link S3Client}; may be empty
     * @param bucket     configured input bucket name (default {@code carddemo-batch-input})
     * @return a {@link HealthIndicator} reporting UP, DOWN, or UNKNOWN for S3
     */
    @Bean
    public HealthIndicator s3HealthIndicator(
            ObjectProvider<S3Client> s3Provider,
            @Value("${carddemo.aws.s3.bucket-input:carddemo-batch-input}") String bucket) {
        return () -> {
            S3Client s3 = s3Provider.getIfAvailable();
            if (s3 == null) {
                return Health.unknown().withDetail("s3", "not configured").build();
            }
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                return Health.up().withDetail("bucket", bucket).build();
            } catch (SdkException ex) {
                // SdkException is the SDK v2 base type: covers NoSuchBucketException,
                // S3Exception, and connectivity (SdkClientException) failures.
                return Health.down(ex).withDetail("bucket", bucket).build();
            }
        };
    }

    /**
     * Amazon SQS indicator (component key {@code sqs}). Resolves the configured report queue URL
     * via {@code GetQueueUrl}, bounding the asynchronous call so a hung dependency cannot stall
     * the health endpoint.
     *
     * @param sqsProvider provider for the asynchronous {@link SqsAsyncClient}; may be empty
     * @param queueName   configured report queue name (default {@code carddemo-report-jobs.fifo})
     * @return a {@link HealthIndicator} reporting UP, DOWN, or UNKNOWN for SQS
     */
    @Bean
    public HealthIndicator sqsHealthIndicator(
            ObjectProvider<SqsAsyncClient> sqsProvider,
            @Value("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}") String queueName) {
        return () -> {
            SqsAsyncClient sqs = sqsProvider.getIfAvailable();
            if (sqs == null) {
                return Health.unknown().withDetail("sqs", "not configured").build();
            }
            try {
                GetQueueUrlResponse response = sqs
                        .getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                        .get(AWS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return Health.up()
                        .withDetail("queue", queueName)
                        .withDetail("queueUrl", response.queueUrl())
                        .build();
            } catch (InterruptedException ex) {
                // Never swallow an interrupt: restore the flag before reporting DOWN.
                Thread.currentThread().interrupt();
                return Health.down(ex).withDetail("queue", queueName).build();
            } catch (ExecutionException | TimeoutException ex) {
                return Health.down(ex).withDetail("queue", queueName).build();
            }
        };
    }
}
