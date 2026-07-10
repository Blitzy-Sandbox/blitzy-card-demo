package com.carddemo.config;

import java.time.Duration;

import io.awspring.cloud.autoconfigure.s3.S3ClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.builder.SdkClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

/**
 * Applies an overall API-call deadline to the auto-configured AWS SDK&nbsp;v2 <strong>S3</strong>
 * client so a slow or unresponsive S3 endpoint cannot extend batch (or request) work unboundedly
 * &mdash; the gap identified by QA finding <strong>Issue&nbsp;3</strong> (Gate&nbsp;3
 * resource-management review: the SDK clients relied only on the low-level HTTP socket timeouts and
 * had no overall {@code apiCallTimeout}/{@code apiCallAttemptTimeout}, so a persistently slow S3
 * endpoint on the batch thread could delay job completion indefinitely).
 *
 * <h2>Scope: S3 only &mdash; why the SQS async client is deliberately excluded</h2>
 * <p>An SDK-level {@code apiCallTimeout}/{@code apiCallAttemptTimeout} is applied <em>only</em> to
 * the S3 client, whose operations ({@code GetObject}/{@code PutObject} for report and statement
 * staging) are bounded request/response calls where an absolute deadline is exactly the right
 * safety net. It is <strong>not</strong> applied to the asynchronous SQS client, because that client
 * is used by the Spring&nbsp;Cloud&nbsp;AWS {@code @SqsListener} container for <em>long-polling</em>
 * message reception: a single {@code ReceiveMessage} call is designed to hold the connection open
 * for up to the configured long-poll wait, so an absolute {@code apiCallTimeout} aborts the
 * legitimately long-lived poll and the listener logs a continuous stream of
 * {@code ApiCallTimeoutException}s (and can force needless message redelivery toward the DLQ).
 * Applying SDK call-timeouts to a long-polling SQS async client is a known incompatibility.</p>
 * <p>The SQS side of finding Issue&nbsp;3 is nonetheless covered without an SDK client timeout:</p>
 * <ul>
 *   <li><strong>Request-path send</strong> &mdash; {@code ReportService} enqueues the report job and
 *       awaits the send future with an application-level deadline
 *       ({@code carddemo.aws.sqs.report-send-timeout-ms}, default 5000&nbsp;ms), so a stalled send
 *       cannot hang the request thread.</li>
 *   <li><strong>Listener receive</strong> &mdash; resilience is governed by the listener's own poll
 *       model (long-poll wait + poll timeout), which is the SQS-native mechanism; layering an SDK
 *       call-timeout on top would break it rather than harden it.</li>
 * </ul>
 *
 * <h2>How it works</h2>
 * <p>Spring&nbsp;Cloud&nbsp;AWS exposes a <em>typed per-service</em> customizer for the S3 client
 * builder &mdash; {@link S3ClientCustomizer} &mdash; and its auto-configuration invokes
 * <strong>all</strong> registered customizer beans (via {@code ObjectProvider.orderedStream()})
 * while assembling the S3 client builder. This class contributes one S3 customizer that
 * <em>merges</em> the two timeouts into whatever {@link ClientOverrideConfiguration}
 * Spring&nbsp;Cloud&nbsp;AWS (and any other customizer, such as {@code AwsTracingConfig}'s
 * OpenTelemetry {@code ExecutionInterceptor}) has already configured, copying the existing
 * configuration with {@code toBuilder()} rather than replacing it. Because the S3 builder is
 * consumed as a stream of customizers, this customizer composes with the tracing customizer: both
 * are applied, and neither clobbers the other's contribution to the override configuration.</p>
 *
 * <h2>Why a dedicated {@code @Configuration} (not {@code AwsConfig} or {@code AwsTracingConfig})</h2>
 * <p>{@code AwsConfig} intentionally declares a single producer bean (the customized
 * {@code SqsTemplate}) and hand-rolls no SDK clients; that minimal contract is asserted by
 * {@code AwsConfigTest}. Keeping the timeout customizer here preserves that contract and its
 * isolation test untouched. {@code AwsTracingConfig}'s customizers are deliberately no-ops when
 * tracing is disabled (the {@code test} profile has no {@code OpenTelemetry} bean), whereas the
 * S3 timeout deadline must apply in <em>every</em> profile; separating the concern keeps each
 * customizer's activation rule clear and independent.</p>
 *
 * <h2>Why the typed per-service customizer (not the global {@code AwsSyncClientCustomizer})</h2>
 * <p>The global {@code AwsSyncClientCustomizer}/{@code AwsAsyncClientCustomizer} callbacks receive a
 * wildcard-typed builder whose declared super-interfaces do not expose
 * {@code overrideConfiguration(...)} on a wildcard capture. The typed {@link S3ClientCustomizer}
 * hands back the <em>concrete</em>
 * {@link software.amazon.awssdk.services.s3.S3ClientBuilder S3ClientBuilder}, whose full interface
 * set includes the {@code AwsClientBuilder → SdkClientBuilder} branch, so the recursive bound
 * {@code B extends SdkClientBuilder<B, C>} is satisfied by concrete inference and
 * {@link SdkClientBuilder#overrideConfiguration()} resolves cleanly &mdash; no wildcard capture, no
 * raw types, preserving the {@code -Xlint:all} zero-warning build (Gate&nbsp;2). This mirrors the
 * established pattern in {@code AwsTracingConfig}.</p>
 *
 * <h2>Chosen deadlines</h2>
 * <ul>
 *   <li>{@code apiCallTimeout} &mdash; overall deadline for a logical API call <em>including</em>
 *       any SDK retries. Default {@code 30000&nbsp;ms}.</li>
 *   <li>{@code apiCallAttemptTimeout} &mdash; deadline for a single underlying HTTP attempt (before a
 *       retry). Default {@code 10000&nbsp;ms}.</li>
 * </ul>
 * <p>Both are configurable (kebab-case keys under {@code carddemo.aws}, mirroring the existing
 * {@code carddemo.aws.sqs.report-send-timeout-ms}). The defaults sit far above the sub-second
 * LocalStack round-trips exercised by the integration suite, so they never trip in tests yet still
 * bound a genuinely stalled S3 dependency on the off-request-path batch write path.</p>
 *
 * <p>Design rationale is recorded in {@code docs/decision-log.md}, not in code comments
 * (Explainability rule); the COBOL&nbsp;&rarr;&nbsp;Java mapping is in
 * {@code docs/traceability-matrix.md} (source referenced read-only by commit SHA {@code 27d6c6f}).</p>
 *
 * @see S3ClientCustomizer
 * @see AwsTracingConfig
 */
@Configuration
public class AwsClientTimeoutConfig {

    /** Overall per-API-call deadline (milliseconds), including SDK retries. */
    private final long apiCallTimeoutMillis;

    /** Per-attempt deadline (milliseconds) for a single underlying HTTP attempt. */
    private final long apiCallAttemptTimeoutMillis;

    /**
     * @param apiCallTimeoutMillis        overall API-call deadline in milliseconds
     *                                    ({@code carddemo.aws.api-call-timeout-ms}, default 30000)
     * @param apiCallAttemptTimeoutMillis single-attempt deadline in milliseconds
     *                                    ({@code carddemo.aws.api-call-attempt-timeout-ms},
     *                                    default 10000)
     */
    public AwsClientTimeoutConfig(
            @Value("${carddemo.aws.api-call-timeout-ms:30000}") final long apiCallTimeoutMillis,
            @Value("${carddemo.aws.api-call-attempt-timeout-ms:10000}") final long apiCallAttemptTimeoutMillis) {
        this.apiCallTimeoutMillis = apiCallTimeoutMillis;
        this.apiCallAttemptTimeoutMillis = apiCallAttemptTimeoutMillis;
    }

    /**
     * Customizer applied to the S3 client builder: sets the overall and per-attempt API-call
     * deadlines on the S3 client (for example {@code S3.PutObject} for report/statement staging).
     * The asynchronous SQS client is intentionally left untouched (see the class Javadoc: an
     * absolute API-call timeout is incompatible with the {@code @SqsListener} long-poll model).
     *
     * @return a customizer that merges the timeouts into the S3 client's override configuration
     */
    @Bean
    S3ClientCustomizer timeoutS3ClientCustomizer() {
        return builder -> applyTimeouts(builder);
    }

    /**
     * Merges {@link #apiCallTimeoutMillis} and {@link #apiCallAttemptTimeoutMillis} into the given SDK
     * client builder's {@link ClientOverrideConfiguration}. The existing configuration (set by Spring
     * Cloud AWS and possibly extended by other customizers, e.g. the tracing interceptor) is copied
     * with {@code toBuilder()} and extended, never replaced, so retry/credential/interceptor settings
     * survive; a {@code null} current configuration starts from a fresh builder. The typed per-service
     * customizer hands back the concrete builder, satisfying the recursive bound
     * {@code B extends SdkClientBuilder<B, C>} by concrete inference so
     * {@link SdkClientBuilder#overrideConfiguration()} resolves without wildcards or raw types.
     *
     * @param builder the SDK client builder under assembly; never {@code null}
     * @param <B>     the self-referential builder type
     * @param <C>     the built client type
     */
    private <B extends SdkClientBuilder<B, C>, C> void applyTimeouts(final B builder) {
        final ClientOverrideConfiguration current = builder.overrideConfiguration();
        final ClientOverrideConfiguration.Builder overrideBuilder =
                (current != null) ? current.toBuilder() : ClientOverrideConfiguration.builder();
        builder.overrideConfiguration(overrideBuilder
                .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMillis))
                .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMillis))
                .build());
    }
}
