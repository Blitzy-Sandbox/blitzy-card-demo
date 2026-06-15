/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Unit test for the AWS SDK api-call timeout customizers in AwsConfig
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield test with NO COBOL source equivalent. It guards decision
 *  D-013 (explicit, auditable AWS api-call timeouts) — the resolution of QA
 *  checkpoint FINAL 4, Finding #2 (no explicit application-level AWS api-call
 *  timeout configuration). Base package is com.cardemo (decision D-006).
 *
 *  This test lives in package com.cardemo.config (same package as AwsConfig) so
 *  it can invoke the package-private @Bean customizer factory methods directly
 *  and exercise their pure logic without bootstrapping a Spring context or any
 *  live/LocalStack AWS endpoint (no client is ever built()).
 * ============================================================================
 */
package com.cardemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.cardemo.config.AwsConfig.AwsResourceProperties;

import io.awspring.cloud.autoconfigure.AwsAsyncClientCustomizer;
import io.awspring.cloud.autoconfigure.AwsSyncClientCustomizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

/**
 * Verifies that {@link AwsConfig}'s sync/async client customizers pin the configured
 * {@code apiCallTimeout}/{@code apiCallAttemptTimeout} bounds onto AWS SDK client builders while
 * preserving any pre-existing {@link ClientOverrideConfiguration} (e.g. the Spring Cloud AWS
 * user-agent), exactly as required by decision D-013 / QA FINAL 4 Finding #2.
 */
class AwsConfigTimeoutCustomizerTest {

    /** Distinct, non-default values so an assertion failure cannot be masked by the field defaults. */
    private static final Duration EXPECTED_API_CALL = Duration.ofSeconds(7);
    private static final Duration EXPECTED_API_CALL_ATTEMPT = Duration.ofSeconds(3);

    /** Builds an {@link AwsResourceProperties} whose timeout group holds the distinct test values. */
    private static AwsResourceProperties propertiesWithTimeouts() {
        final AwsResourceProperties properties = new AwsResourceProperties();
        properties.getTimeout().setApiCall(EXPECTED_API_CALL);
        properties.getTimeout().setApiCallAttempt(EXPECTED_API_CALL_ATTEMPT);
        return properties;
    }

    @Test
    @DisplayName("AwsResourceProperties.Timeout exposes the contract defaults (60s / 20s)")
    void timeoutDefaultsMatchContract() {
        final AwsResourceProperties.Timeout timeout = new AwsResourceProperties().getTimeout();

        assertThat(timeout.getApiCall()).isEqualTo(Duration.ofSeconds(60));
        assertThat(timeout.getApiCallAttempt()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("Both customizer factory methods return non-null customizers")
    void customizerFactoriesReturnBeans() {
        final AwsConfig config = new AwsConfig();
        final AwsResourceProperties properties = propertiesWithTimeouts();

        assertThat(config.cardDemoAwsSyncClientTimeoutCustomizer(properties)).isNotNull();
        assertThat(config.cardDemoAwsAsyncClientTimeoutCustomizer(properties)).isNotNull();
    }

    @Test
    @DisplayName("Sync customizer pins both api-call timeouts onto a sync (S3) client builder")
    void syncCustomizerAppliesTimeouts() {
        final AwsSyncClientCustomizer customizer =
                new AwsConfig().cardDemoAwsSyncClientTimeoutCustomizer(propertiesWithTimeouts());
        final S3ClientBuilder builder = S3Client.builder();

        customizer.customize(builder);

        final ClientOverrideConfiguration override = builder.overrideConfiguration();
        assertThat(override).isNotNull();
        assertThat(override.apiCallTimeout()).contains(EXPECTED_API_CALL);
        assertThat(override.apiCallAttemptTimeout()).contains(EXPECTED_API_CALL_ATTEMPT);
    }

    @Test
    @DisplayName("Async customizer pins both api-call timeouts onto an async (SQS) client builder")
    void asyncCustomizerAppliesTimeouts() {
        final AwsAsyncClientCustomizer customizer =
                new AwsConfig().cardDemoAwsAsyncClientTimeoutCustomizer(propertiesWithTimeouts());
        final SqsAsyncClientBuilder builder = SqsAsyncClient.builder();

        customizer.customize(builder);

        final ClientOverrideConfiguration override = builder.overrideConfiguration();
        assertThat(override).isNotNull();
        assertThat(override.apiCallTimeout()).contains(EXPECTED_API_CALL);
        assertThat(override.apiCallAttemptTimeout()).contains(EXPECTED_API_CALL_ATTEMPT);
    }

    @Test
    @DisplayName("Customizer ADDS timeouts onto an existing override, preserving the user-agent header")
    void customizerPreservesExistingOverride() {
        final AwsSyncClientCustomizer customizer =
                new AwsConfig().cardDemoAwsSyncClientTimeoutCustomizer(propertiesWithTimeouts());
        final S3ClientBuilder builder = S3Client.builder();

        // Simulate Spring Cloud AWS having already installed a framework override (e.g. user-agent
        // carried as a header). The customizer must read this via the getter, toBuilder() it, layer
        // the timeouts on top, and write it back WITHOUT discarding the pre-existing settings.
        builder.overrideConfiguration(ClientOverrideConfiguration.builder()
                .putHeader("User-Agent", "spring-cloud-aws/3.3.0")
                .build());

        customizer.customize(builder);

        final ClientOverrideConfiguration override = builder.overrideConfiguration();
        assertThat(override.apiCallTimeout()).contains(EXPECTED_API_CALL);
        assertThat(override.apiCallAttemptTimeout()).contains(EXPECTED_API_CALL_ATTEMPT);
        assertThat(override.headers()).containsEntry("User-Agent", List.of("spring-cloud-aws/3.3.0"));
    }

    @Test
    @DisplayName("Customizer applies timeouts onto a builder that starts with no timeout config")
    void customizerHandlesNoPriorTimeoutConfig() {
        final AwsSyncClientCustomizer customizer =
                new AwsConfig().cardDemoAwsSyncClientTimeoutCustomizer(propertiesWithTimeouts());
        final S3ClientBuilder builder = S3Client.builder();

        // A fresh AWS SDK v2 builder already exposes a NON-null but EMPTY ClientOverrideConfiguration
        // (no api-call timeouts set). The customizer therefore reads this empty override via the
        // getter, toBuilder()s it, and layers the timeouts on top. (The withApiCallTimeouts helper
        // additionally null-guards the getter per the SdkClientBuilder contract, even though a real
        // builder never returns null here.)
        final ClientOverrideConfiguration before = builder.overrideConfiguration();
        assertThat(before).isNotNull();
        assertThat(before.apiCallTimeout()).isEmpty();
        assertThat(before.apiCallAttemptTimeout()).isEmpty();

        customizer.customize(builder);

        final ClientOverrideConfiguration override = builder.overrideConfiguration();
        assertThat(override).isNotNull();
        assertThat(override.apiCallTimeout()).contains(EXPECTED_API_CALL);
        assertThat(override.apiCallAttemptTimeout()).contains(EXPECTED_API_CALL_ATTEMPT);
    }
}
