package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.config.AwsConfig.CardDemoAwsProperties;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.retries.AdaptiveRetryStrategy;
import software.amazon.awssdk.retries.LegacyRetryStrategy;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Unit tests for {@link AwsConfig}.
 *
 * <p>Traceability (REFERENCE-ONLY; lineage {@code app/jcl/DEFGDGB.jcl}; source commit
 * {@code 27d6c6f}): verifies the synchronous S3 and SNS client factory beans build successfully
 * both with an explicit endpoint override (the LocalStack path that drives
 * {@code hasEndpoint == true}) and without one (the real-AWS path that drives
 * {@code hasEndpoint == false}), and that the typed {@link CardDemoAwsProperties} holder exposes
 * its nested resource-name accessors. Clients are built with explicit region and static test
 * credentials so no network or AWS metadata lookup occurs, and each client is closed promptly.</p>
 *
 * <p>The asynchronous {@code sqsAsyncClient} bean is intentionally exercised by the LocalStack
 * integration tests rather than here: constructing it eagerly initializes the Netty NIO transport,
 * which emits a {@code sun.misc.Unsafe} deprecation warning on Java 25 that would violate the
 * zero-warning build gate. Its body is structurally identical to {@code snsClient} (region,
 * credentials, the same {@code hasEndpoint} override branch, and the same bounded
 * {@code buildOverrideConfiguration}), which is covered above.</p>
 *
 * <p>The bounded timeout/retry resilience (R6 / AAP AWS resilience) is verified directly against
 * the package-private static {@code buildOverrideConfiguration}/{@code buildRetryStrategy} helpers
 * so the {@link software.amazon.awssdk.core.client.config.ClientOverrideConfiguration} can be
 * asserted without constructing any network client (avoiding the Netty/Unsafe warning above).</p>
 */
@DisplayName("AwsConfig - S3/SQS/SNS client beans and properties")
class AwsConfigTest {

    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";
    private static final String ENDPOINT = "http://localhost:4566";

    // Bounded resilience settings supplied to the client factory beans (mirrors the
    // carddemo.aws.client.* defaults in application.yml).
    private static final long API_CALL_TIMEOUT_MILLIS = 30_000L;
    private static final long API_CALL_ATTEMPT_TIMEOUT_MILLIS = 10_000L;
    private static final String RETRY_MODE = "STANDARD";
    private static final int MAX_ATTEMPTS = 3;

    // No-op AWS SDK OpenTelemetry execution interceptor supplied to the client factory beans so
    // the S3/SNS builds exercise the new tracing-aware override path without any real telemetry.
    private static final ExecutionInterceptor TRACING_INTERCEPTOR =
            AwsSdkTelemetry.create(OpenTelemetry.noop()).newExecutionInterceptor();

    private final AwsConfig awsConfig = new AwsConfig();

    @Nested
    @DisplayName("S3 client")
    class S3 {

        @Test
        @DisplayName("Builds with a LocalStack endpoint and path-style access")
        void buildsWithEndpoint() {
            try (S3Client client = awsConfig.s3Client(
                    REGION, ACCESS_KEY, SECRET_KEY, ENDPOINT, true,
                    API_CALL_TIMEOUT_MILLIS, API_CALL_ATTEMPT_TIMEOUT_MILLIS, RETRY_MODE, MAX_ATTEMPTS,
                    TRACING_INTERCEPTOR)) {
                assertThat(client).isNotNull();
            }
        }

        @Test
        @DisplayName("Builds without an endpoint override")
        void buildsWithoutEndpoint() {
            try (S3Client client = awsConfig.s3Client(
                    REGION, ACCESS_KEY, SECRET_KEY, "", false,
                    API_CALL_TIMEOUT_MILLIS, API_CALL_ATTEMPT_TIMEOUT_MILLIS, RETRY_MODE, MAX_ATTEMPTS,
                    TRACING_INTERCEPTOR)) {
                assertThat(client).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("SNS client")
    class Sns {

        @Test
        @DisplayName("Builds with a LocalStack endpoint")
        void buildsWithEndpoint() {
            try (SnsClient client = awsConfig.snsClient(
                    REGION, ACCESS_KEY, SECRET_KEY, ENDPOINT,
                    API_CALL_TIMEOUT_MILLIS, API_CALL_ATTEMPT_TIMEOUT_MILLIS, RETRY_MODE, MAX_ATTEMPTS,
                    TRACING_INTERCEPTOR)) {
                assertThat(client).isNotNull();
            }
        }

        @Test
        @DisplayName("Builds without an endpoint override")
        void buildsWithoutEndpoint() {
            try (SnsClient client = awsConfig.snsClient(
                    REGION, ACCESS_KEY, SECRET_KEY, "",
                    API_CALL_TIMEOUT_MILLIS, API_CALL_ATTEMPT_TIMEOUT_MILLIS, RETRY_MODE, MAX_ATTEMPTS,
                    TRACING_INTERCEPTOR)) {
                assertThat(client).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("Client override configuration (timeout + retry resilience)")
    class OverrideConfiguration {

        @Test
        @DisplayName("Carries bounded API call + attempt timeouts and a bounded retry strategy")
        void carriesBoundedTimeoutsAndRetry() {
            ClientOverrideConfiguration cfg = AwsConfig.buildOverrideConfiguration(
                    API_CALL_TIMEOUT_MILLIS, API_CALL_ATTEMPT_TIMEOUT_MILLIS, RETRY_MODE, MAX_ATTEMPTS);

            // Both timeouts are present (bounded) and equal to the configured millis.
            assertThat(cfg.apiCallTimeout()).contains(Duration.ofMillis(API_CALL_TIMEOUT_MILLIS));
            assertThat(cfg.apiCallAttemptTimeout())
                    .contains(Duration.ofMillis(API_CALL_ATTEMPT_TIMEOUT_MILLIS));
            // A non-deprecated retry strategy is present and bounded to maxAttempts.
            assertThat(cfg.retryStrategy()).isPresent();
            assertThat(cfg.retryStrategy().get().maxAttempts()).isEqualTo(MAX_ATTEMPTS);
            // The deprecated retry-policy slot is intentionally unused.
            assertThat(cfg.retryPolicy()).isEmpty();
        }

        @Test
        @DisplayName("STANDARD mode yields a bounded standard retry strategy")
        void standardModeIsBoundedStandardStrategy() {
            RetryStrategy strategy = AwsConfig.buildRetryStrategy("STANDARD", 4);
            assertThat(strategy).isInstanceOf(StandardRetryStrategy.class);
            assertThat(strategy.maxAttempts()).isEqualTo(4);
        }

        @Test
        @DisplayName("LEGACY mode (case-insensitive) yields a bounded legacy retry strategy")
        void legacyModeIsBoundedLegacyStrategy() {
            RetryStrategy strategy = AwsConfig.buildRetryStrategy("legacy", 5);
            assertThat(strategy).isInstanceOf(LegacyRetryStrategy.class);
            assertThat(strategy.maxAttempts()).isEqualTo(5);
        }

        @Test
        @DisplayName("ADAPTIVE mode yields a bounded adaptive retry strategy")
        void adaptiveModeIsBoundedAdaptiveStrategy() {
            RetryStrategy strategy = AwsConfig.buildRetryStrategy("ADAPTIVE", 6);
            assertThat(strategy).isInstanceOf(AdaptiveRetryStrategy.class);
            assertThat(strategy.maxAttempts()).isEqualTo(6);
        }

        @Test
        @DisplayName("Unknown / blank / null mode fails safe to a bounded standard strategy")
        void unknownModeFailsSafeToStandard() {
            assertThat(AwsConfig.buildRetryStrategy("UNRECOGNIZED", 7))
                    .isInstanceOf(StandardRetryStrategy.class);
            assertThat(AwsConfig.buildRetryStrategy("UNRECOGNIZED", 7).maxAttempts()).isEqualTo(7);
            assertThat(AwsConfig.buildRetryStrategy("", 2)).isInstanceOf(StandardRetryStrategy.class);
            assertThat(AwsConfig.buildRetryStrategy(null, 2)).isInstanceOf(StandardRetryStrategy.class);
            assertThat(AwsConfig.buildRetryStrategy(null, 2).maxAttempts()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("AWS SDK tracing interceptor")
    @SuppressWarnings("unchecked") // mock(ObjectProvider.class) is a raw type; localized to this test.
    class TracingInterceptor {

        @Test
        @DisplayName("Builds from the application OpenTelemetry bean when one is available")
        void buildsFromAvailableOpenTelemetry() {
            ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable(any())).thenReturn(OpenTelemetry.noop());

            ExecutionInterceptor interceptor = awsConfig.awsSdkTracingInterceptor(provider);

            assertThat(interceptor).isNotNull();
        }

        @Test
        @DisplayName("Falls back to a no-op OpenTelemetry when no bean is present")
        void fallsBackToNoopOpenTelemetryWhenNoBean() {
            // An empty provider drives getIfAvailable(Supplier) to invoke the OpenTelemetry::noop
            // fallback supplied by the production code; the interceptor must still build.
            ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable(any())).thenAnswer(invocation -> {
                Supplier<OpenTelemetry> fallback = invocation.getArgument(0);
                return fallback.get();
            });

            ExecutionInterceptor interceptor = awsConfig.awsSdkTracingInterceptor(provider);

            assertThat(interceptor).isNotNull();
        }
    }

    @Nested
    @DisplayName("CardDemoAwsProperties holder")
    class Properties {

        @Test
        @DisplayName("Exposes nested S3/SQS/SNS resource names")
        void exposesNestedNames() {
            CardDemoAwsProperties props = new CardDemoAwsProperties(
                    new CardDemoAwsProperties.S3(
                            "carddemo-batch-input", "carddemo-batch-output", "carddemo-statements"),
                    new CardDemoAwsProperties.Sqs("carddemo-report-jobs.fifo"),
                    new CardDemoAwsProperties.Sns("carddemo-notifications"));

            assertThat(props.s3().bucketInput()).isEqualTo("carddemo-batch-input");
            assertThat(props.s3().bucketOutput()).isEqualTo("carddemo-batch-output");
            assertThat(props.s3().bucketStatements()).isEqualTo("carddemo-statements");
            assertThat(props.sqs().reportQueue()).isEqualTo("carddemo-report-jobs.fifo");
            assertThat(props.sns().notificationsTopic()).isEqualTo("carddemo-notifications");
        }
    }
}
