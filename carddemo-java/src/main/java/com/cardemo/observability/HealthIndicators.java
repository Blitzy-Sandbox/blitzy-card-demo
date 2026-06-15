package com.cardemo.observability;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

import javax.sql.DataSource;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import com.cardemo.config.AwsConfig;

/**
 * Custom Spring Boot Actuator {@link HealthIndicator} definitions for the greenfield Java 25 LTS +
 * Spring Boot 3.5.15 migration of the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS mainframe application.
 *
 * <h2>Provenance &mdash; net-new observability component (no COBOL equivalent)</h2>
 * <p>This class has <strong>no COBOL source</strong>: it is a pure technology-substitution component
 * in the net-new cross-cutting observability layer. The legacy AWS CardDemo COBOL/CICS estate shipped
 * <em>zero</em> health-check infrastructure &mdash; no liveness probe, no readiness probe, no
 * connectivity check of any external resource (tech-spec L818). Per the <strong>Minimal Change
 * Clause</strong> (AAP &sect;0.7.1) this file only makes the running application observable; it
 * alters <em>no</em> business behaviour. Traceability to the frozen legacy baseline is by original
 * COBOL repository commit SHA {@code 27d6c6f}; the COBOL source is <em>never copied</em> into this
 * repository (AAP &sect;0.7.2 &mdash; Preservation Requirements). The file role is defined by the
 * authoritative target tree as &quot;DB, S3, SQS health checks&quot; (tech-spec L461), within the
 * Health/Readiness Checks portion of the Observability Implementation Analysis (tech-spec L816-L836).</p>
 *
 * <h2>Single responsibility &mdash; the three external-dependency health indicators</h2>
 * <p>The migrated system has exactly three external runtime dependencies: the PostgreSQL database
 * (the VSAM&nbsp;KSDS&nbsp;&rarr;&nbsp;relational substitution), the AWS S3 batch-staging bucket
 * (the sequential-PS / GDG substitution), and the AWS SQS FIFO report-jobs queue (the CICS TDQ
 * online&rarr;batch-bridge substitution). This {@code @Configuration} contributes one
 * {@link HealthIndicator} bean per dependency. Spring Boot Actuator surfaces each under
 * {@code /actuator/health}, keyed by the bean name with its trailing {@code HealthIndicator} suffix
 * removed (the {@code HealthContributorNameFactory} convention):</p>
 * <table border="1">
 *   <caption>Health indicators owned by this file</caption>
 *   <tr><th>{@code @Bean} method / bean name</th><th>Health key</th><th>Probe</th></tr>
 *   <tr><td>{@link #databaseHealthIndicator(DataSource) databaseHealthIndicator}</td>
 *       <td>{@code database}</td>
 *       <td>{@link Connection#isValid(int)} on a pooled connection (short timeout)</td></tr>
 *   <tr><td>{@link #s3HealthIndicator(S3Client, AwsConfig.AwsResourceProperties) s3HealthIndicator}</td>
 *       <td>{@code s3}</td>
 *       <td>{@code S3Client.headBucket(...)} on the batch-input bucket</td></tr>
 *   <tr><td>{@link #sqsHealthIndicator(SqsAsyncClient, AwsConfig.AwsResourceProperties) sqsHealthIndicator}</td>
 *       <td>{@code sqs}</td>
 *       <td>{@code SqsAsyncClient.getQueueUrl(...)} on the report-jobs FIFO queue (short timeout)</td></tr>
 * </table>
 *
 * <h2>Distinct {@code database} key vs Spring Boot's built-in {@code db} indicator</h2>
 * <p>Spring Boot Actuator already auto-configures a built-in {@code DataSourceHealthIndicator} under
 * the key {@code db} whenever a {@link DataSource} is present. The custom database indicator here is
 * named {@code databaseHealthIndicator} so it resolves to the <strong>distinct</strong> key
 * {@code database}, deliberately <em>not</em> overriding or colliding with the built-in {@code db}
 * indicator &mdash; both can coexist under {@code /actuator/health}. The custom indicator is provided
 * because the authoritative spec (tech-spec L816-L836) explicitly lists the database among the three
 * external-dependency checks and keeps the three checks co-located and uniformly documented. If a
 * future maintainer decides the built-in {@code db} indicator is sufficient, this custom
 * {@code database} indicator can be removed without affecting the {@code s3}/{@code sqs} checks.</p>
 *
 * <h2>Graceful degradation (mandatory)</h2>
 * <p>A health endpoint that throws would break Kubernetes-style readiness polling, so every probe is
 * wrapped such that {@link HealthIndicator#health()} <strong>never</strong> propagates an exception:
 * it always returns a {@link Health} object &mdash; {@code UP} on a successful probe, otherwise
 * {@code DOWN} (carrying the failure as a detail via {@link Health#down(Throwable)} or an explicit
 * {@code reason} detail). Each probe is intentionally <strong>fast and cheap</strong> &mdash; a
 * connection-validity check, an S3 {@code HEAD} bucket, and an SQS queue-URL lookup, all with short
 * timeouts &mdash; because readiness probes poll frequently; no data reads, writes, or scans are
 * performed.</p>
 *
 * <h2>LocalStack verifiability (AAP &sect;0.7.7 L1091)</h2>
 * <p>The S3 {@code headBucket} and SQS {@code getQueueUrl} probes target the application-owned
 * resource <em>names</em> only and use no live AWS credentials, so they succeed against the
 * LocalStack-provisioned bucket and queue (created by {@code localstack-init/init-aws.sh} for
 * local/compose runs, and by the integration tests' own setup for the {@code test} profile) exactly
 * as they would against live AWS &mdash; satisfying the &quot;every AWS interaction verifiable
 * against LocalStack with zero live dependencies&quot; rule.</p>
 *
 * <h2>Resource names come from properties, never hardcoded</h2>
 * <p>The S3 bucket and SQS queue names are resolved exclusively through
 * {@link AwsConfig.AwsResourceProperties} (the {@code @ConfigurationProperties(prefix="carddemo.aws")}
 * binder owned by {@code config/AwsConfig.java}); they are <strong>never</strong> string literals in
 * this file. Concretely the S3 probe reads {@code awsProps.getS3().getBatchInputBucket()} (contract
 * default {@code carddemo-batch-input}) and the SQS probe reads
 * {@code awsProps.getSqs().getReportJobsQueue()} (contract default {@code carddemo-report-jobs.fifo}).
 * These getter names match the actual {@link AwsConfig.AwsResourceProperties} API as defined in
 * {@code config/AwsConfig.java}.</p>
 *
 * <h2>Readiness-group coordination (configuration owned elsewhere)</h2>
 * <p>This file provides only well-named indicator beans. Whether {@code database}/{@code s3}/
 * {@code sqs} participate in the Kubernetes readiness probe is decided by
 * {@code management.endpoint.health.group.readiness.include} in
 * {@code src/main/resources/application.yml} &mdash; that wiring is the responsibility of the YAML
 * configuration, not this class. No health group is configured here.</p>
 *
 * <h2>Separation of concerns (boundaries this file does not cross)</h2>
 * <ul>
 *   <li>It does <strong>not</strong> define the {@link DataSource} &mdash; that is auto-configured by
 *       Spring Boot from {@code spring.datasource.*}; {@code config/JpaConfig.java} governs the JPA
 *       context. It is injected here.</li>
 *   <li>It does <strong>not</strong> define the {@link S3Client}, {@link SqsAsyncClient}, or
 *       {@link AwsConfig.AwsResourceProperties} &mdash; the clients are auto-configured by Spring
 *       Cloud AWS 3.3.0 and the resource-name binder is owned by {@code config/AwsConfig.java}
 *       (via {@code @EnableConfigurationProperties}). All three are injected here.</li>
 *   <li>It does <strong>not</strong> configure Actuator endpoint exposure or health groups &mdash;
 *       those are owned by {@code application.yml}.</li>
 *   <li>It performs <strong>no</strong> metrics, correlation-ID, tracing, {@code ObservedAspect}, or
 *       business work &mdash; those live in {@code observability/MetricsConfig},
 *       {@code observability/CorrelationIdFilter}, and {@code config/ObservabilityConfig}.</li>
 * </ul>
 *
 * @see org.springframework.boot.actuate.health.HealthIndicator
 * @see org.springframework.boot.actuate.health.Health
 * @see com.cardemo.config.AwsConfig.AwsResourceProperties
 */
@Configuration
public class HealthIndicators {

    /**
     * Short, fixed timeout (in seconds) applied to every probe so a slow or hung dependency cannot
     * stall frequent readiness polling. Used as the {@link Connection#isValid(int)} timeout, as the
     * wall-clock bound for the database probe's {@code Future.get(...)} on the
     * {@link #databaseProbeExecutor}, and as the {@code SqsAsyncClient.getQueueUrl(...)} future
     * {@code get} timeout. Kept intentionally low (within the 1&ndash;2&nbsp;second guidance) because
     * these probes run on the readiness path.
     */
    private static final int PROBE_TIMEOUT_SECONDS = 2;

    /**
     * Dedicated, bounded executor used <em>only</em> to run the PostgreSQL connectivity probe under a
     * hard {@link #PROBE_TIMEOUT_SECONDS} wall-clock ceiling.
     *
     * <h3>Why a separate executor is required (finding F-OBS-1)</h3>
     * <p>The probe's first action, {@code dataSource.getConnection()}, blocks for up to the HikariCP
     * {@code spring.datasource.hikari.connection-timeout} (default 30&nbsp;000&nbsp;ms) when the
     * database is unreachable &mdash; and that wait happens <em>before</em> the in-probe
     * {@link Connection#isValid(int)} timeout can ever take effect. Running the probe inline on the
     * Actuator request thread therefore let a database outage stall {@code /actuator/health} and the
     * frequently-polled {@code /actuator/health/readiness} for ~30&ndash;60&nbsp;seconds, defeating the
     * &quot;fast and cheap&quot; readiness-probe intent. By executing the borrow-and-validate work on
     * this executor and waiting on the returned {@link Future} with
     * {@code get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)}, the indicator now returns {@code DOWN}
     * within the ~2&nbsp;second bound regardless of the HikariCP connection-timeout &mdash; mirroring
     * the bounded {@code get(timeout)} pattern already used by {@link #sqsHealthIndicator}. The global
     * HikariCP {@code connection-timeout} is intentionally left at its production value (the
     * Minimal-Change Clause, AAP &sect;0.7.1, forbids altering business connection-acquisition
     * behaviour); only the health probe is bounded.</p>
     *
     * <p>Two daemon worker threads are sufficient because readiness polling is periodic and each
     * timed-out task is {@linkplain Future#cancel(boolean) cancelled with interruption} (HikariCP
     * aborts a blocked {@code getConnection()} on interrupt), so worker threads are released promptly.
     * Daemon threads never block JVM shutdown, and {@link #shutdownDatabaseProbeExecutor()} disposes
     * the pool deterministically when the Spring context closes.</p>
     */
    private final ExecutorService databaseProbeExecutor = Executors.newFixedThreadPool(2, runnable -> {
        final Thread thread = new Thread(runnable, "carddemo-db-health-probe");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Disposes the {@link #databaseProbeExecutor} when the Spring application context shuts down so the
     * (daemon) probe threads are released deterministically rather than relying on JVM exit.
     * {@link ExecutorService#shutdownNow()} also interrupts any probe still blocked on a hung
     * {@code getConnection()}.
     */
    @PreDestroy
    void shutdownDatabaseProbeExecutor() {
        databaseProbeExecutor.shutdownNow();
    }

    /**
     * Health indicator for PostgreSQL connectivity, surfaced under the health key {@code database}
     * (bean name {@code databaseHealthIndicator} with the {@code HealthIndicator} suffix stripped).
     *
     * <p>The probe borrows a connection from the pool inside a try-with-resources block (so it is
     * always returned) and calls {@link Connection#isValid(int)} with {@link #PROBE_TIMEOUT_SECONDS}.
     * A valid connection yields {@code UP} with a {@code database=PostgreSQL} detail; an invalid one
     * yields {@code DOWN} with a {@code reason=connection invalid} detail. Any {@link SQLException}
     * (or any other unchecked runtime failure) is caught and rendered as {@code DOWN} so the probe
     * never throws &mdash; the mandatory graceful-degradation contract.</p>
     *
     * <h3>Bounded execution &mdash; ~2&nbsp;second ceiling under a database outage (finding F-OBS-1)</h3>
     * <p>The borrow-and-validate work runs on the dedicated {@link #databaseProbeExecutor} and the
     * caller waits on the {@link Future} with {@code get(}{@link #PROBE_TIMEOUT_SECONDS}{@code ,
     * TimeUnit.SECONDS)}. This is required because {@code dataSource.getConnection()} blocks for up to
     * the HikariCP {@code connection-timeout} (~30&nbsp;s) when the database is unreachable,
     * <em>before</em> the in-probe {@link Connection#isValid(int)} timeout can apply &mdash; which
     * previously stalled {@code /actuator/health} (~60&nbsp;s) and {@code /actuator/health/readiness}
     * (~30&nbsp;s). With the bound, a database outage now surfaces as {@code DOWN} within
     * ~{@value #PROBE_TIMEOUT_SECONDS}&nbsp;seconds: a {@link TimeoutException} on the {@code get} adds
     * a {@code reason=probe timed out} detail and cancels/interrupts the worker, keeping the readiness
     * path &quot;fast and cheap&quot;. The global HikariCP {@code connection-timeout} is deliberately
     * left unchanged so business connection-acquisition behaviour is preserved (Minimal-Change Clause);
     * only this health probe is bounded.</p>
     *
     * <p>This is a custom indicator under the distinct key {@code database}; it intentionally does not
     * override Spring Boot's built-in {@code db} {@code DataSourceHealthIndicator}. To avoid a second,
     * <em>unbounded</em> {@code getConnection()} block on {@code /actuator/health} during a database
     * outage, that redundant built-in {@code db} indicator is disabled via
     * {@code management.health.db.enabled=false} in {@code application.yml}; this bounded
     * {@code database} indicator is the single source of PostgreSQL health (see the class Javadoc).</p>
     *
     * @param dataSource the auto-configured application {@link DataSource} (injected; never created
     *                   here). Spring Boot configures it from {@code spring.datasource.*}.
     * @return a {@link HealthIndicator} that reports PostgreSQL connectivity and degrades gracefully
     */
    @Bean
    public HealthIndicator databaseHealthIndicator(final DataSource dataSource) {
        return () -> {
            // Borrow-and-validate is submitted to a dedicated executor and bounded by
            // future.get(PROBE_TIMEOUT_SECONDS): dataSource.getConnection() blocks up to the HikariCP
            // connection-timeout (~30s) when the DB is down, which would otherwise stall the readiness
            // path long before the in-probe isValid() timeout applies (finding F-OBS-1).
            final Callable<Health> probeTask = () -> {
                try (Connection connection = dataSource.getConnection()) {
                    if (connection.isValid(PROBE_TIMEOUT_SECONDS)) {
                        return Health.up()
                                .withDetail("database", "PostgreSQL")
                                .build();
                    }
                    // Connection obtained but failed its own validity self-check within the timeout.
                    return Health.down()
                            .withDetail("reason", "connection invalid")
                            .build();
                } catch (SQLException e) {
                    // Could not obtain or validate a connection (driver / pool / network failure).
                    return Health.down(e).build();
                }
            };

            final Future<Health> future = databaseProbeExecutor.submit(probeTask);
            try {
                // Hard wall-clock ceiling: a DB outage now surfaces as DOWN within
                // ~PROBE_TIMEOUT_SECONDS instead of blocking for the full HikariCP connection-timeout.
                return future.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // Probe exceeded its short bound (e.g. getConnection() still blocked on the pool).
                // Interrupt the worker so a blocked getConnection() is aborted and its thread freed.
                future.cancel(true);
                return Health.down(e)
                        .withDetail("reason", "probe timed out")
                        .build();
            } catch (ExecutionException e) {
                // The probe task itself failed; surface the underlying cause as DOWN.
                final Throwable cause = e.getCause() != null ? e.getCause() : e;
                return Health.down(cause).build();
            } catch (InterruptedException e) {
                // Restore the interrupt status, cancel the in-flight probe, then degrade gracefully.
                future.cancel(true);
                Thread.currentThread().interrupt();
                return Health.down(e).build();
            } catch (RuntimeException e) {
                // Defensive catch-all so health() can never propagate an unexpected unchecked
                // exception out of the readiness path (graceful-degradation mandate).
                return Health.down(e).build();
            }
        };
    }

    /**
     * Health indicator for AWS S3 batch-staging-bucket accessibility, surfaced under the health key
     * {@code s3} (bean name {@code s3HealthIndicator} with the {@code HealthIndicator} suffix
     * stripped).
     *
     * <p>The probe issues a single cheap {@code HEAD} request against the batch-input bucket via
     * {@code s3Client.headBucket(...)}. The bucket name is resolved from configuration
     * ({@code awsProps.getS3().getBatchInputBucket()}, contract default {@code carddemo-batch-input})
     * and is never a literal in this file. A successful {@code headBucket} yields {@code UP} with a
     * {@code bucket=<name>} detail.</p>
     *
     * <p>Failure handling (graceful degradation &mdash; the probe never throws):</p>
     * <ul>
     *   <li>{@link NoSuchBucketException} &rarr; {@code DOWN} with {@code reason=bucket not found}
     *       and the {@code bucket} detail (the bucket is genuinely absent).</li>
     *   <li>{@link S3Exception} (a broader S3 service error, e.g. access denied) &rarr;
     *       {@code DOWN} carrying the exception. Caught before {@link SdkException} because it is a
     *       subtype.</li>
     *   <li>{@link SdkException} (any other AWS SDK error, e.g. a client/connection failure) &rarr;
     *       {@code DOWN} carrying the exception.</li>
     *   <li>{@link RuntimeException} (defensive catch-all) &rarr; {@code DOWN} carrying the exception,
     *       guaranteeing {@code health()} never propagates an unexpected unchecked failure.</li>
     * </ul>
     *
     * <p>The probe targets the resource name only and uses no live credentials, so it succeeds
     * against the LocalStack-provisioned bucket with zero live AWS dependencies (AAP &sect;0.7.7).</p>
     *
     * @param s3Client the auto-configured AWS SDK v2 {@link S3Client} (Spring Cloud AWS
     *                 {@code spring-cloud-aws-starter-s3}; injected, never created here)
     * @param awsProps the {@link AwsConfig.AwsResourceProperties} resource-name binder owned by
     *                 {@code config/AwsConfig.java} (injected); supplies the batch-input bucket name
     * @return a {@link HealthIndicator} that reports S3 bucket accessibility and degrades gracefully
     */
    @Bean
    public HealthIndicator s3HealthIndicator(final S3Client s3Client,
                                             final AwsConfig.AwsResourceProperties awsProps) {
        return () -> {
            // Resource name sourced exclusively from configuration (never a hardcoded literal).
            final String bucketName = awsProps.getS3().getBatchInputBucket();
            try {
                // Cheap metadata-only HEAD; the response is intentionally not retained.
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
                return Health.up()
                        .withDetail("bucket", bucketName)
                        .build();
            } catch (NoSuchBucketException e) {
                return Health.down()
                        .withDetail("reason", "bucket not found")
                        .withDetail("bucket", bucketName)
                        .build();
            } catch (S3Exception e) {
                // Broader S3 service error (subtype of SdkException; must be caught first).
                return Health.down(e).build();
            } catch (SdkException e) {
                // Any other AWS SDK error (client/connection level).
                return Health.down(e).build();
            } catch (RuntimeException e) {
                // Defensive catch-all so health() can never propagate an unexpected unchecked
                // exception out of the readiness path (graceful-degradation mandate).
                return Health.down(e).build();
            }
        };
    }

    /**
     * Health indicator for AWS SQS report-jobs-queue availability, surfaced under the health key
     * {@code sqs} (bean name {@code sqsHealthIndicator} with the {@code HealthIndicator} suffix
     * stripped).
     *
     * <p>The probe issues a single cheap {@code GetQueueUrl} request against the FIFO report-jobs
     * queue. The queue name is resolved from configuration
     * ({@code awsProps.getSqs().getReportJobsQueue()}, contract default
     * {@code carddemo-report-jobs.fifo}) and is never a literal in this file. Spring Cloud AWS
     * auto-configures the asynchronous {@link SqsAsyncClient}, so {@code getQueueUrl} returns a
     * {@link java.util.concurrent.CompletableFuture}; the probe waits at most
     * {@link #PROBE_TIMEOUT_SECONDS} seconds for it via {@code get(timeout, TimeUnit.SECONDS)}. A
     * successful lookup yields {@code UP} with a {@code queue=<name>} detail.</p>
     *
     * <p>Failure handling (graceful degradation &mdash; the probe never throws):</p>
     * <ul>
     *   <li>{@link QueueDoesNotExistException} &rarr; {@code DOWN} with {@code reason=queue not found}
     *       and the {@code queue} detail. Because the client is asynchronous, this unchecked SDK
     *       exception normally surfaces wrapped inside an {@link ExecutionException}; the probe both
     *       catches it directly (defensive) and unwraps {@link ExecutionException#getCause()} to
     *       detect it precisely.</li>
     *   <li>{@link ExecutionException} (any other async failure) &rarr; {@code DOWN} carrying the
     *       exception, unless its cause is a missing queue (handled above).</li>
     *   <li>{@link TimeoutException} (the lookup exceeded {@link #PROBE_TIMEOUT_SECONDS}) &rarr;
     *       {@code DOWN} carrying the exception.</li>
     *   <li>{@link InterruptedException} &rarr; the thread's interrupt status is restored
     *       ({@code Thread.currentThread().interrupt()}) and {@code DOWN} is returned.</li>
     *   <li>{@link RuntimeException} (defensive catch-all, also covers any synchronous
     *       {@code SdkClientException} thrown before the future is returned) &rarr; {@code DOWN}
     *       carrying the exception.</li>
     * </ul>
     *
     * <p>The probe targets the resource name only and uses no live credentials, so it succeeds
     * against the LocalStack-provisioned queue with zero live AWS dependencies (AAP &sect;0.7.7).</p>
     *
     * <p>SQS client choice: the auto-configured {@link SqsAsyncClient} is used (rather than the
     * higher-level {@code SqsTemplate}, which exposes no cheap queue-existence probe), matching the
     * Spring Cloud AWS auto-configuration.</p>
     *
     * @param sqsAsyncClient the auto-configured AWS SDK v2 {@link SqsAsyncClient} (Spring Cloud AWS
     *                       {@code spring-cloud-aws-starter-sqs}; injected, never created here)
     * @param awsProps       the {@link AwsConfig.AwsResourceProperties} resource-name binder owned by
     *                       {@code config/AwsConfig.java} (injected); supplies the report-jobs queue
     *                       name
     * @return a {@link HealthIndicator} that reports SQS queue availability and degrades gracefully
     */
    @Bean
    public HealthIndicator sqsHealthIndicator(final SqsAsyncClient sqsAsyncClient,
                                              final AwsConfig.AwsResourceProperties awsProps) {
        return () -> {
            // Resource name sourced exclusively from configuration (never a hardcoded literal).
            final String queueName = awsProps.getSqs().getReportJobsQueue();
            try {
                // Cheap name-resolution call; the response is intentionally not retained. A short,
                // bounded wait keeps the readiness path responsive even if SQS is unreachable.
                sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                        .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return Health.up()
                        .withDetail("queue", queueName)
                        .build();
            } catch (QueueDoesNotExistException e) {
                // Direct (non-wrapped) path: the queue is genuinely absent.
                return Health.down()
                        .withDetail("reason", "queue not found")
                        .withDetail("queue", queueName)
                        .build();
            } catch (ExecutionException e) {
                // Async failures surface wrapped; unwrap to detect a missing queue precisely.
                if (e.getCause() instanceof QueueDoesNotExistException) {
                    return Health.down()
                            .withDetail("reason", "queue not found")
                            .withDetail("queue", queueName)
                            .build();
                }
                return Health.down(e).build();
            } catch (TimeoutException e) {
                // The probe exceeded its short bound; report DOWN rather than blocking readiness.
                return Health.down(e).build();
            } catch (InterruptedException e) {
                // Restore the interrupt status, then degrade gracefully.
                Thread.currentThread().interrupt();
                return Health.down(e).build();
            } catch (RuntimeException e) {
                // Defensive catch-all (also covers a synchronous SdkClientException thrown by
                // getQueueUrl before the future is returned) so health() never propagates an
                // unexpected unchecked exception out of the readiness path.
                return Health.down(e).build();
            }
        };
    }
}
