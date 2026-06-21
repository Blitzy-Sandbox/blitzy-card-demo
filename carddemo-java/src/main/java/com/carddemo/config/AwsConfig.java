package com.carddemo.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
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
 * AWS SDK v2 client configuration replacing the CardDemo mainframe I/O substrates: S3 for
 * GDG/batch file staging, SQS FIFO for the CICS TDQ report bridge, and SNS for notifications.
 * Resource lineage references {@code app/jcl/DEFGDGB.jcl} at source commit {@code 27d6c6f}
 * (REFERENCE ONLY; COBOL/JCL is not copied). Clients target LocalStack for the {@code local} and
 * {@code test} profiles (endpoint + path-style overrides) and real AWS regional endpoints in the
 * base profile, with connection settings resolved from the environment via {@code spring.cloud.aws.*}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AwsConfig.CardDemoAwsProperties.class)
public class AwsConfig {

    /**
     * Overall wall-clock budget for a single API call, spanning all retry attempts. Bounds the
     * total time the application will block on an outbound AWS operation before failing fast.
     * Defaults to 30 seconds; tunable via {@code carddemo.aws.client.api-call-timeout-millis}.
     */
    @Value("${carddemo.aws.client.api-call-timeout-millis:30000}")
    private long apiCallTimeoutMillis = 30_000L;

    /**
     * Per-attempt timeout applied to each individual HTTP attempt within an API call. A hung or
     * slow attempt is abandoned at this bound so the retry strategy can engage. Defaults to 10
     * seconds; tunable via {@code carddemo.aws.client.api-call-attempt-timeout-millis}.
     */
    @Value("${carddemo.aws.client.api-call-attempt-timeout-millis:10000}")
    private long apiCallAttemptTimeoutMillis = 10_000L;

    /**
     * Maximum number of attempts (the initial call plus retries) made by the standard retry
     * strategy for transient/throttling failures. Defaults to 3; tunable via
     * {@code carddemo.aws.client.max-retry-attempts}.
     */
    @Value("${carddemo.aws.client.max-retry-attempts:3}")
    private int maxRetryAttempts = 3;

    /**
     * Builds the central {@link ClientOverrideConfiguration} shared by every AWS SDK client bean
     * (S3, SQS, SNS). It pins three resilience controls so no outbound AWS call relies on
     * unbounded SDK defaults: a bounded overall {@code apiCallTimeout}, a bounded per-attempt
     * {@code apiCallAttemptTimeout}, and an explicit standard {@link RetryStrategy} with a fixed
     * maximum attempt count (exponential backoff with a circuit breaker for transient and
     * throttling errors). A hung dependency therefore fails fast and predictably rather than
     * blocking a batch step or request thread indefinitely.
     *
     * <p>Package-private so the configuration is unit-testable without a Spring context.</p>
     *
     * @return the shared client override configuration applied to all AWS clients
     */
    ClientOverrideConfiguration clientOverrideConfiguration() {
        RetryStrategy retryStrategy = DefaultRetryStrategy.standardStrategyBuilder()
                .maxAttempts(maxRetryAttempts)
                .build();
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMillis))
                .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMillis))
                .retryStrategy(retryStrategy)
                .build();
    }

    /**
     * Returns {@code true} when an explicit AWS endpoint override is configured (LocalStack for
     * local/test). When blank, the SDK resolves the standard regional endpoint (real AWS).
     *
     * @param endpoint the configured {@code spring.cloud.aws.endpoint} value (may be blank)
     * @return whether an endpoint override should be applied to the client builders
     */
    private boolean hasEndpoint(String endpoint) {
        return StringUtils.hasText(endpoint);
    }

    /**
     * Synchronous {@link S3Client} for batch staging, statement, report, and rejection objects.
     * Path-style access is enabled for LocalStack to avoid virtual-host bucket DNS that does not
     * resolve locally.
     *
     * @param region          AWS region identifier
     * @param accessKey       AWS access key (non-secret LocalStack dummy by default)
     * @param secretKey       AWS secret key (non-secret LocalStack dummy by default)
     * @param endpoint        optional endpoint override (LocalStack {@code :4566}); blank for real AWS
     * @param pathStyleAccess whether S3 path-style addressing is enabled (true for LocalStack)
     * @return a configured synchronous S3 client
     */
    @Bean
    S3Client s3Client(@Value("${spring.cloud.aws.region.static:us-east-1}") String region,
                      @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
                      @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
                      @Value("${spring.cloud.aws.endpoint:}") String endpoint,
                      @Value("${spring.cloud.aws.s3.path-style-access-enabled:false}") boolean pathStyleAccess) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(clientOverrideConfiguration())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build());
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Asynchronous {@link SqsAsyncClient} backing the report-submission queue (CICS TDQ
     * replacement). The asynchronous variant is mandated so the higher-level {@code @SqsListener}
     * container factory and {@code SqsTemplate} consume this client; there is no synchronous
     * {@code SqsClient} bean.
     *
     * @param region    AWS region identifier
     * @param accessKey AWS access key (non-secret LocalStack dummy by default)
     * @param secretKey AWS secret key (non-secret LocalStack dummy by default)
     * @param endpoint  optional endpoint override (LocalStack {@code :4566}); blank for real AWS
     * @return a configured asynchronous SQS client
     */
    @Bean
    SqsAsyncClient sqsAsyncClient(@Value("${spring.cloud.aws.region.static:us-east-1}") String region,
                                  @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
                                  @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
                                  @Value("${spring.cloud.aws.endpoint:}") String endpoint) {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(clientOverrideConfiguration());
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Synchronous {@link SnsClient} for notification fan-out.
     *
     * @param region    AWS region identifier
     * @param accessKey AWS access key (non-secret LocalStack dummy by default)
     * @param secretKey AWS secret key (non-secret LocalStack dummy by default)
     * @param endpoint  optional endpoint override (LocalStack {@code :4566}); blank for real AWS
     * @return a configured synchronous SNS client
     */
    @Bean
    SnsClient snsClient(@Value("${spring.cloud.aws.region.static:us-east-1}") String region,
                        @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
                        @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey,
                        @Value("${spring.cloud.aws.endpoint:}") String endpoint) {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(clientOverrideConfiguration());
        if (hasEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Typed, constructor-bound holder for the CardDemo AWS resource names, centralizing the values
     * defined in {@code application*.yml}, {@code docker-compose.yml}, and
     * {@code localstack-init/init-aws.sh}. Relaxed binding maps kebab-case keys (for example
     * {@code carddemo.aws.s3.bucket-input}) to the record accessors.
     *
     * @param s3  S3 bucket names
     * @param sqs SQS queue names
     * @param sns SNS topic names
     */
    @ConfigurationProperties("carddemo.aws")
    public record CardDemoAwsProperties(S3 s3, Sqs sqs, Sns sns) {

        /**
         * S3 bucket names for batch input, batch output, and statement objects.
         *
         * @param bucketInput      batch input bucket (for example {@code carddemo-batch-input})
         * @param bucketOutput     batch output bucket (for example {@code carddemo-batch-output})
         * @param bucketStatements statement bucket (for example {@code carddemo-statements})
         */
        public record S3(String bucketInput, String bucketOutput, String bucketStatements) {
        }

        /**
         * SQS queue names.
         *
         * @param reportQueue report-submission FIFO queue (for example {@code carddemo-report-jobs.fifo})
         */
        public record Sqs(String reportQueue) {
        }

        /**
         * SNS topic names.
         *
         * @param notificationsTopic notification topic (for example {@code carddemo-notifications})
         */
        public record Sns(String notificationsTopic) {
        }
    }
}
