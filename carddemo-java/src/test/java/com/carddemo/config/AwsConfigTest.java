package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.config.AwsConfig.CardDemoAwsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Unit tests for {@link AwsConfig}. Builds the synchronous AWS SDK v2 client
 * beans ({@link S3Client}, {@link SnsClient}) with and without an endpoint
 * override (exercising both branches of the private endpoint-override guard)
 * and verifies the typed {@link CardDemoAwsProperties} resource-name holder.
 * Client construction is offline-safe: the SDK opens no connection until a
 * request is made.
 *
 * <p>The asynchronous {@code sqsAsyncClient} bean is intentionally not
 * constructed here: the Netty-based async HTTP client it initializes emits a
 * JVM {@code sun.misc.Unsafe} notice on Java 25 that is irrelevant to a unit
 * test. Its real construction and behavior are covered by the LocalStack
 * integration tests, keeping this unit test free of framework runtime noise.
 *
 * <p>Lives in the {@code com.carddemo.config} package so the package-private
 * {@code @Bean} factory methods are accessible.
 */
class AwsConfigTest {

    private static final String REGION = "us-east-1";
    private static final String KEY = "test";
    private static final String LOCALSTACK = "http://localhost:4566";

    private final AwsConfig config = new AwsConfig();

    @Test
    @DisplayName("s3Client builds with a LocalStack endpoint override and path-style access")
    void s3ClientWithEndpoint() {
        try (S3Client s3 = config.s3Client(REGION, KEY, KEY, LOCALSTACK, true)) {
            assertThat(s3).isNotNull();
        }
    }

    @Test
    @DisplayName("s3Client builds for real AWS when no endpoint override is configured")
    void s3ClientWithoutEndpoint() {
        try (S3Client s3 = config.s3Client(REGION, KEY, KEY, "", false)) {
            assertThat(s3).isNotNull();
        }
    }

    @Test
    @DisplayName("snsClient builds with and without an endpoint override")
    void snsClientBothBranches() {
        try (SnsClient sns = config.snsClient(REGION, KEY, KEY, LOCALSTACK)) {
            assertThat(sns).isNotNull();
        }
        try (SnsClient sns = config.snsClient(REGION, KEY, KEY, "")) {
            assertThat(sns).isNotNull();
        }
    }

    @Test
    @DisplayName("CardDemoAwsProperties exposes the configured resource names")
    void resourceNameProperties() {
        CardDemoAwsProperties props = new CardDemoAwsProperties(
                new CardDemoAwsProperties.S3("carddemo-batch-input", "carddemo-batch-output", "carddemo-statements"),
                new CardDemoAwsProperties.Sqs("carddemo-report-jobs.fifo"),
                new CardDemoAwsProperties.Sns("carddemo-notifications"));

        assertThat(props.s3().bucketInput()).isEqualTo("carddemo-batch-input");
        assertThat(props.s3().bucketOutput()).isEqualTo("carddemo-batch-output");
        assertThat(props.s3().bucketStatements()).isEqualTo("carddemo-statements");
        assertThat(props.sqs().reportQueue()).isEqualTo("carddemo-report-jobs.fifo");
        assertThat(props.sns().notificationsTopic()).isEqualTo("carddemo-notifications");
    }
}
