package com.carddemo.observability;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Registers the CardDemo application's custom Spring Boot Actuator
 * {@link HealthIndicator} beans, surfaced under {@code /actuator/health}.
 *
 * <p>Three infrastructure dependencies are probed, each contributing a
 * dedicated entry to the aggregate health response (the health contributor
 * key is derived from the {@code @Bean} method name with the
 * {@code HealthIndicator} suffix removed):</p>
 * <ul>
 *   <li><b>{@code database}</b> &mdash; PostgreSQL connectivity, validated with a
 *       short-lived JDBC {@link Connection#isValid(int)} check.</li>
 *   <li><b>{@code s3}</b> &mdash; AWS S3 (LocalStack) reachability, probed with a
 *       lightweight {@link S3Client#listBuckets()} call.</li>
 *   <li><b>{@code sqs}</b> &mdash; AWS SQS (LocalStack) reachability, probed with a
 *       time-bounded {@link SqsAsyncClient#listQueues(java.util.function.Consumer)}
 *       call.</li>
 * </ul>
 *
 * <h2>Graceful degradation</h2>
 * <p>The AWS clients ({@link S3Client}, {@link SqsAsyncClient}) and even the
 * {@link DataSource} may legitimately be absent from the application context in
 * Spring profiles or test slices that do not enable those integrations.
 * Consequently every dependency is injected defensively through an
 * {@link ObjectProvider} and resolved lazily via
 * {@link ObjectProvider#getIfAvailable()} on each health check. The resulting
 * policy is:</p>
 * <ul>
 *   <li><b>Not configured</b> (bean absent) &rarr; {@link Health#unknown()} with a
 *       {@code not-configured} detail. An intentionally omitted optional
 *       dependency never drives the endpoint {@code DOWN} and never fails
 *       context startup.</li>
 *   <li><b>Configured but unreachable</b> &rarr; {@link Health#down()} / {@code down(Throwable)},
 *       reflecting a genuine readiness problem.</li>
 *   <li><b>Configured and reachable</b> &rarr; {@link Health#up()}.</li>
 * </ul>
 *
 * <p>The {@code s3} and {@code sqs} contributors are intended to participate in
 * the Actuator <em>readiness</em> group (not liveness): transient LocalStack
 * unavailability degrades readiness (traffic routing) without triggering a
 * liveness-driven restart. Readiness-group membership is configured externally
 * via {@code management.endpoint.health.group.readiness.include} and is <b>not</b>
 * defined by this class. Every probe is time-bounded and exception-safe so that
 * {@code /actuator/health} can never hang or throw.</p>
 *
 * <h2>Safety</h2>
 * <p>Health details expose only low-cardinality, non-sensitive values
 * (reachability status, database product name, bucket count). Credentials,
 * tokens, JDBC URLs, endpoints, transaction data, and card numbers are never
 * emitted.</p>
 *
 * <p>These indicators are net-new observability infrastructure introduced by the
 * COBOL&rarr;Spring migration (Observability rule / goal G6); they have no direct
 * COBOL counterpart. Thematically they mirror the legacy batch programs (for
 * example {@code CBTRN02C}) that verified file availability &mdash; the file
 * {@code OPEN} status &mdash; before processing records. The rationale for
 * introducing them is documented in {@code docs/decision-log.md}.</p>
 */
@Configuration
public class HealthIndicators {

    /**
     * Timeout, in seconds, for the JDBC {@link Connection#isValid(int)} database
     * validation probe. Kept intentionally short so {@code /actuator/health}
     * stays responsive.
     */
    private static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 2;

    /**
     * Bounded wait, in seconds, applied to the asynchronous SQS reachability
     * probe. Prevents a hung AWS call from stalling the health endpoint.
     */
    private static final long AWS_PROBE_TIMEOUT_SECONDS = 2L;

    /**
     * Creates the configuration. Instantiated by the Spring container during
     * component scanning; not intended for direct use.
     */
    public HealthIndicators() {
        // No initialization required; indicator beans are produced by the @Bean methods below.
    }

    /**
     * Custom database connectivity indicator, contributing the {@code database}
     * key to {@code /actuator/health}.
     *
     * <p>Named {@code databaseHealthIndicator} deliberately so it does <em>not</em>
     * collide with Spring Boot's auto-configured {@code dbHealthIndicator}
     * (contributor key {@code db}); both may coexist.</p>
     *
     * @param dataSourceProvider defensive provider for the JDBC {@link DataSource};
     *                           the bean may be absent in AWS/JPA-less slices
     * @return a {@link HealthIndicator} that reports {@code UP} when a connection
     *         validates, {@code DOWN} when it does not or an error occurs, and
     *         {@code UNKNOWN} when no {@link DataSource} is configured
     */
    @Bean
    HealthIndicator databaseHealthIndicator(final ObjectProvider<DataSource> dataSourceProvider) {
        return () -> checkDatabase(dataSourceProvider.getIfAvailable());
    }

    /**
     * AWS S3 reachability indicator, contributing the {@code s3} key to
     * {@code /actuator/health}.
     *
     * @param s3ClientProvider defensive provider for the AWS SDK v2
     *                         {@link S3Client}; absent when S3 is not enabled in
     *                         the active profile
     * @return a {@link HealthIndicator} that reports {@code UP} when the bucket
     *         listing succeeds, {@code DOWN} on any client/service error, and
     *         {@code UNKNOWN} when no {@link S3Client} is configured
     */
    @Bean
    HealthIndicator s3HealthIndicator(final ObjectProvider<S3Client> s3ClientProvider) {
        return () -> checkS3(s3ClientProvider.getIfAvailable());
    }

    /**
     * AWS SQS reachability indicator, contributing the {@code sqs} key to
     * {@code /actuator/health}.
     *
     * @param sqsClientProvider defensive provider for the AWS SDK v2
     *                          {@link SqsAsyncClient}; absent when SQS is not
     *                          enabled in the active profile
     * @return a {@link HealthIndicator} that reports {@code UP} when a bounded
     *         queue listing completes, {@code DOWN} on timeout/failure, and
     *         {@code UNKNOWN} when no {@link SqsAsyncClient} is configured
     */
    @Bean
    HealthIndicator sqsHealthIndicator(final ObjectProvider<SqsAsyncClient> sqsClientProvider) {
        return () -> checkSqs(sqsClientProvider.getIfAvailable());
    }

    /**
     * Evaluates database health for a (possibly {@code null}) {@link DataSource}.
     *
     * @param dataSource the resolved data source, or {@code null} if none is configured
     * @return the computed {@link Health}
     */
    private static Health checkDatabase(final DataSource dataSource) {
        if (dataSource == null) {
            // Optional dependency intentionally absent: degrade gracefully, do not fail.
            return Health.unknown().withDetail("datasource", "not-configured").build();
        }
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS)) {
                return Health.up().withDetail("database", "PostgreSQL").build();
            }
            return Health.down().withDetail("database", "validation-failed").build();
        } catch (SQLException ex) {
            // SQLException messages are safe (no credentials/JDBC URL); attach a categorical detail.
            return Health.down(ex).withDetail("database", "unavailable").build();
        }
    }

    /**
     * Evaluates S3 health for a (possibly {@code null}) {@link S3Client}.
     *
     * @param s3Client the resolved S3 client, or {@code null} if none is configured
     * @return the computed {@link Health}
     */
    private static Health checkS3(final S3Client s3Client) {
        if (s3Client == null) {
            return Health.unknown().withDetail("s3", "not-configured").build();
        }
        try {
            // listBuckets() is a lightweight, side-effect-free reachability probe.
            final int bucketCount = s3Client.listBuckets().buckets().size();
            return Health.up()
                    .withDetail("s3", "reachable")
                    .withDetail("buckets", bucketCount)
                    .build();
        } catch (RuntimeException ex) {
            // AWS SDK v2 throws unchecked SdkException subtypes; never expose endpoints/credentials.
            return Health.down(ex).withDetail("s3", "unreachable").build();
        }
    }

    /**
     * Evaluates SQS health for a (possibly {@code null}) {@link SqsAsyncClient}.
     *
     * <p>The asynchronous listing is resolved with a short bounded wait; on
     * timeout the pending future is cancelled so a hung call cannot stall the
     * health endpoint.</p>
     *
     * @param sqsClient the resolved SQS client, or {@code null} if none is configured
     * @return the computed {@link Health}
     */
    private static Health checkSqs(final SqsAsyncClient sqsClient) {
        if (sqsClient == null) {
            return Health.unknown().withDetail("sqs", "not-configured").build();
        }
        final var listQueuesFuture = sqsClient.listQueues(builder -> builder.maxResults(1));
        try {
            listQueuesFuture.get(AWS_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Health.up().withDetail("sqs", "reachable").build();
        } catch (TimeoutException ex) {
            listQueuesFuture.cancel(true);
            return Health.down(ex).withDetail("sqs", "timeout").build();
        } catch (ExecutionException ex) {
            return Health.down(ex).withDetail("sqs", "unreachable").build();
        } catch (InterruptedException ex) {
            // Restore the interrupt status before surfacing the DOWN result.
            Thread.currentThread().interrupt();
            return Health.down(ex).withDetail("sqs", "interrupted").build();
        }
    }
}
