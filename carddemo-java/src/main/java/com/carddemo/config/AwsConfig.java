package com.carddemo.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

/**
 * AWS SDK v2 client configuration for CardDemo: the synchronous {@link S3Client} (GDG / batch
 * file staging and statement/report output), the asynchronous {@link SqsAsyncClient} (the CICS
 * TDQ report bridge, FIFO), and the synchronous {@link SnsClient} (notification fan-out), plus a
 * typed {@link CardDemoAwsProperties} holder for the CardDemo resource names. Clients target
 * LocalStack for the {@code local}/{@code test} profiles (path-style S3, {@code :4566} endpoint)
 * and real AWS regional endpoints in the base profile via environment-supplied settings.
 *
 * <p>Every client is built with a shared, bounded {@link ClientOverrideConfiguration}
 * (see {@link #buildOverrideConfiguration}) carrying an API call timeout, a per-attempt timeout, and
 * a retry strategy, so no outbound AWS call can block indefinitely and transient failures retry
 * within a fixed envelope. These settings are tunable via {@code carddemo.aws.client.*} in
 * {@code application*.yml} and are exercisable against LocalStack.</p>
 *
 * <p>Lineage (reference only): derived from {@code app/jcl/DEFGDGB.jcl} at source commit
 * {@code 27d6c6f}; no COBOL/JCL is copied. Rationale is recorded in {@code DECISION_LOG.md}.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AwsConfig.CardDemoAwsProperties.class)
public class AwsConfig {

    /**
     * Synchronous Amazon S3 client for GDG / batch file staging and statement/report output.
     * Path-style access is enabled so LocalStack bucket addressing resolves without virtual-host
     * DNS; the endpoint is overridden only when {@code spring.cloud.aws.endpoint} is set
     * (LocalStack), otherwise the real AWS regional endpoint is used.
     */
    @Bean
    S3Client s3Client(
            @Value("${spring.cloud.aws.region.static:us-east-1}") String region,
            @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
            @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
            @Value("${spring.cloud.aws.endpoint:}") String endpoint,
            @Value("${spring.cloud.aws.s3.path-style-access-enabled:false}") boolean pathStyleAccess,
            @Value("${carddemo.aws.client.api-call-timeout-millis:30000}") long apiCallTimeoutMillis,
            @Value("${carddemo.aws.client.api-call-attempt-timeout-millis:10000}") long apiCallAttemptTimeoutMillis,
            @Value("${carddemo.aws.client.retry-mode:STANDARD}") String retryMode,
            @Value("${carddemo.aws.client.max-attempts:3}") int maxAttempts,
            ExecutionInterceptor awsSdkTracingInterceptor) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(withTracing(buildOverrideConfiguration(
                        apiCallTimeoutMillis, apiCallAttemptTimeoutMillis, retryMode, maxAttempts),
                        awsSdkTracingInterceptor))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Asynchronous Amazon SQS client backing the report-submission FIFO queue (the CICS TDQ
     * replacement) that {@code @SqsListener} consumers read from and the report service publishes
     * to. By design this is the only SQS bean; no synchronous {@code SqsClient} is defined.
     *
     * <p>This client reads its timeouts from the dedicated {@code carddemo.aws.sqs-client.*}
     * properties (not the shared {@code carddemo.aws.client.*} used by S3/SNS): the per-attempt
     * timeout must exceed the SQS long-poll wait so an idle {@code ReceiveMessage} returning empty
     * is normal operation rather than a timeout. See DECISION_LOG D-028.
     */
    @Bean
    SqsAsyncClient sqsAsyncClient(
            @Value("${spring.cloud.aws.region.static:us-east-1}") String region,
            @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
            @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
            @Value("${spring.cloud.aws.endpoint:}") String endpoint,
            @Value("${carddemo.aws.sqs-client.api-call-timeout-millis:60000}") long apiCallTimeoutMillis,
            @Value("${carddemo.aws.sqs-client.api-call-attempt-timeout-millis:20000}") long apiCallAttemptTimeoutMillis,
            @Value("${carddemo.aws.sqs-client.retry-mode:STANDARD}") String retryMode,
            @Value("${carddemo.aws.sqs-client.max-attempts:3}") int maxAttempts,
            ExecutionInterceptor awsSdkTracingInterceptor) {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(withTracing(buildOverrideConfiguration(
                        apiCallTimeoutMillis, apiCallAttemptTimeoutMillis, retryMode, maxAttempts),
                        awsSdkTracingInterceptor));
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Synchronous Amazon SNS client used for notification fan-out. The endpoint is overridden only
     * when {@code spring.cloud.aws.endpoint} is set (LocalStack), otherwise the real AWS regional
     * endpoint is used.
     */
    @Bean
    SnsClient snsClient(
            @Value("${spring.cloud.aws.region.static:us-east-1}") String region,
            @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
            @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
            @Value("${spring.cloud.aws.endpoint:}") String endpoint,
            @Value("${carddemo.aws.client.api-call-timeout-millis:30000}") long apiCallTimeoutMillis,
            @Value("${carddemo.aws.client.api-call-attempt-timeout-millis:10000}") long apiCallAttemptTimeoutMillis,
            @Value("${carddemo.aws.client.retry-mode:STANDARD}") String retryMode,
            @Value("${carddemo.aws.client.max-attempts:3}") int maxAttempts,
            ExecutionInterceptor awsSdkTracingInterceptor) {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(withTracing(buildOverrideConfiguration(
                        apiCallTimeoutMillis, apiCallAttemptTimeoutMillis, retryMode, maxAttempts),
                        awsSdkTracingInterceptor));
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * AWS SDK v2 execution interceptor that emits an OpenTelemetry span for every S3, SQS, and SNS
     * call, so AWS operations appear as nested spans within each request / batch-step trace
     * (Observability rule, AAP §0.7.1: spans for "S3/SQS operations"). It is built from the
     * Spring-managed {@link OpenTelemetry} bean supplied by the Micrometer Tracing → OpenTelemetry
     * bridge (which carries the OTLP exporter and sampler), so AWS spans flow through the same
     * pipeline as controller, repository, and batch spans. When no {@link OpenTelemetry} bean is
     * present (for example a slice test that does not activate tracing) it falls back to
     * {@link OpenTelemetry#noop()} so client construction never fails. Experimental span attributes
     * are disabled to keep span cardinality bounded. Registered on each client through
     * {@link #withTracing(ClientOverrideConfiguration, ExecutionInterceptor)}.
     *
     * @param openTelemetry provider for the application {@link OpenTelemetry} (may be empty)
     * @return an execution interceptor that records a span per AWS SDK call via the configured tracer
     */
    @Bean
    ExecutionInterceptor awsSdkTracingInterceptor(ObjectProvider<OpenTelemetry> openTelemetry) {
        return AwsSdkTelemetry.builder(openTelemetry.getIfAvailable(OpenTelemetry::noop))
                .setCaptureExperimentalSpanAttributes(false)
                .build()
                .newExecutionInterceptor();
    }

    /**
     * Returns {@code true} when an explicit AWS endpoint override is configured (LocalStack),
     * keeping the optional {@code endpointOverride} application DRY across the client beans.
     */
    private boolean hasEndpoint(String endpoint) {
        return StringUtils.hasText(endpoint);
    }

    /**
     * Returns a copy of the supplied bounded {@link ClientOverrideConfiguration} with the AWS SDK
     * OpenTelemetry {@code tracingInterceptor} appended, so every CardDemo AWS client keeps its
     * timeout/retry envelope (from {@link #buildOverrideConfiguration}) <em>and</em> emits a span per
     * call. Kept separate from {@code buildOverrideConfiguration} so that helper stays trivially
     * unit-testable without constructing any OpenTelemetry instrumentation.
     *
     * @param base               the bounded timeout/retry override configuration
     * @param tracingInterceptor the AWS SDK v2 OpenTelemetry execution interceptor
     * @return a new override configuration carrying the resilience settings plus tracing
     */
    private static ClientOverrideConfiguration withTracing(
            ClientOverrideConfiguration base, ExecutionInterceptor tracingInterceptor) {
        return base.toBuilder().addExecutionInterceptor(tracingInterceptor).build();
    }

    /**
     * Builds the shared, bounded {@link ClientOverrideConfiguration} applied to every CardDemo AWS
     * SDK v2 client (S3, SQS, SNS) so that no outbound call can block indefinitely and transient
     * failures retry within a fixed envelope. This gives every AWS interaction explicit
     * timeout/retry/resilience semantics and bounded behavior that is verifiable against LocalStack.
     *
     * <ul>
     *   <li>{@code apiCallTimeout} caps the total wall-clock time for a single logical API call
     *       across all internal attempts.</li>
     *   <li>{@code apiCallAttemptTimeout} caps each individual HTTP attempt within that call.</li>
     *   <li>The {@link RetryStrategy} bounds the number of attempts (initial + retries) using the
     *       SDK's non-deprecated retry-strategy API.</li>
     * </ul>
     *
     * <p>Package-private and {@code static} so it is unit-testable without constructing a network
     * client (which would eagerly initialize the async HTTP layer).</p>
     *
     * @param apiCallTimeoutMillis        total per-call timeout in milliseconds
     * @param apiCallAttemptTimeoutMillis per-attempt timeout in milliseconds
     * @param retryMode                   retry strategy selector ({@code STANDARD}, {@code LEGACY},
     *                                    or {@code ADAPTIVE}; case-insensitive, defaults to
     *                                    {@code STANDARD})
     * @param maxAttempts                 maximum number of attempts (initial call plus retries)
     * @return a bounded client override configuration shared across all AWS clients
     */
    static ClientOverrideConfiguration buildOverrideConfiguration(
            long apiCallTimeoutMillis,
            long apiCallAttemptTimeoutMillis,
            String retryMode,
            int maxAttempts) {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMillis))
                .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMillis))
                .retryStrategy(buildRetryStrategy(retryMode, maxAttempts))
                .build();
    }

    /**
     * Constructs a bounded {@link RetryStrategy} for the requested mode using the SDK's
     * non-deprecated {@link DefaultRetryStrategy} builders (the legacy {@code RetryPolicy} API is
     * deprecated in this SDK line and would break the zero-warning build). The {@code STANDARD}
     * strategy is used for any unrecognized mode so misconfiguration fails safe rather than
     * disabling retries.
     *
     * @param retryMode   retry strategy selector (case-insensitive)
     * @param maxAttempts maximum number of attempts (initial call plus retries)
     * @return a retry strategy bounded to {@code maxAttempts}
     */
    static RetryStrategy buildRetryStrategy(String retryMode, int maxAttempts) {
        String mode = StringUtils.hasText(retryMode)
                ? retryMode.trim().toUpperCase(Locale.ROOT)
                : "STANDARD";
        return switch (mode) {
            case "LEGACY" -> DefaultRetryStrategy.legacyStrategyBuilder().maxAttempts(maxAttempts).build();
            case "ADAPTIVE" -> DefaultRetryStrategy.adaptiveStrategyBuilder().maxAttempts(maxAttempts).build();
            default -> DefaultRetryStrategy.standardStrategyBuilder().maxAttempts(maxAttempts).build();
        };
    }

    /**
     * Typed, constructor-bound holder for the CardDemo AWS resource names, centralizing the S3
     * bucket, SQS queue, and SNS topic identifiers that batch jobs and services inject rather than
     * scattering {@code @Value} strings. Values are supplied by {@code application*.yml} (relaxed
     * binding maps, for example, {@code carddemo.aws.s3.bucket-input} to {@code s3().bucketInput()}).
     */
    @ConfigurationProperties("carddemo.aws")
    public record CardDemoAwsProperties(S3 s3, Sqs sqs, Sns sns) {

        /** S3 bucket names for batch input, batch output, and generated statements. */
        public record S3(String bucketInput, String bucketOutput, String bucketStatements) {
        }

        /** SQS FIFO queue name for the report-submission bridge (CICS TDQ replacement). */
        public record Sqs(String reportQueue) {
        }

        /** SNS topic name for notification fan-out. */
        public record Sns(String notificationsTopic) {
        }
    }
}
