package com.carddemo.config;

import io.awspring.cloud.autoconfigure.s3.S3ClientCustomizer;
import io.awspring.cloud.autoconfigure.sns.SnsClientCustomizer;
import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.builder.SdkClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/**
 * Attaches the OpenTelemetry AWS SDK&nbsp;v2 instrumentation to <em>every</em> auto-configured AWS
 * client (S3, SQS, SNS) so a request/batch trace crosses the final <strong>service&nbsp;&rarr;&nbsp;AWS</strong>
 * boundary &mdash; the gap identified by QA finding <strong>F-2</strong> (only REST + Spring-Security
 * spans reached Jaeger; zero AWS/messaging spans).
 *
 * <h2>How it works</h2>
 * <p>The OpenTelemetry {@link io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry AwsSdkTelemetry}
 * helper produces a single {@link ExecutionInterceptor} that emits a client span per AWS API call
 * (for example {@code Sqs.SendMessage}, {@code S3.PutObject}), tagged with the OpenTelemetry RPC/messaging
 * semantic conventions ({@code rpc.system=aws-api}, {@code rpc.service}, {@code rpc.method}, and the SQS
 * {@code messaging.*} attributes). Spring&nbsp;Cloud&nbsp;AWS exposes a <em>typed per-service</em> customizer
 * for each client builder &mdash; {@link S3ClientCustomizer}, {@link SqsAsyncClientCustomizer}, and
 * {@link SnsClientCustomizer} &mdash; each of which the corresponding auto-configuration invokes (via an
 * {@code ObjectProvider}) while its SDK client builder is being assembled. Each customizer here
 * <em>appends</em> the tracing interceptor to whatever {@link ClientOverrideConfiguration}
 * Spring&nbsp;Cloud&nbsp;AWS has already configured, preserving its retry/timeout/credential settings rather
 * than replacing them.</p>
 *
 * <h2>Why the typed per-service customizers (not the global {@code AwsSyncClientCustomizer})</h2>
 * <p>The global {@code AwsSyncClientCustomizer}/{@code AwsAsyncClientCustomizer} callbacks receive a
 * wildcard-typed {@code AwsSyncClientBuilder<?, ?>}/{@code AwsAsyncClientBuilder<?, ?>}. Those declared
 * super-interfaces reach only {@code SdkSyncClientBuilder}/{@code SdkAsyncClientBuilder}, neither of which
 * declares {@code overrideConfiguration(...)} &mdash; that method arrives via the separate
 * {@code AwsClientBuilder → SdkClientBuilder} branch, which {@code javac} cannot resolve on a wildcard
 * capture. The typed per-service customizers instead hand back the <em>concrete</em> builder
 * ({@link software.amazon.awssdk.services.s3.S3ClientBuilder S3ClientBuilder}, etc.), whose full interface
 * set includes that branch, so {@code overrideConfiguration()} resolves cleanly with no raw types or
 * unchecked casts (preserving the {@code -Xlint:all} zero-warning build, Gate&nbsp;2).</p>
 *
 * <h2>Why {@link ObjectProvider} instead of a bean parameter or {@code @ConditionalOnBean}</h2>
 * <p>The {@link OpenTelemetry} bean exists only when tracing is enabled ({@code management.tracing.enabled=true},
 * the default for the {@code local}/{@code prod} profiles); the {@code test} profile disables tracing, so no
 * such bean is created. Injecting {@code OpenTelemetry} directly would fail context startup under the
 * {@code test} profile, and {@code @ConditionalOnBean(OpenTelemetry.class)} on a user {@code @Configuration}
 * is unreliable because user configuration is processed before the tracing auto-configuration that defines
 * the bean (an ordering hazard). Resolving the bean lazily through an {@link ObjectProvider} at
 * customizer-invocation time sidesteps both problems: when tracing is enabled the provider resolves the
 * {@code OpenTelemetry} instance (forcing its creation if necessary, regardless of bean ordering); when it
 * is disabled the provider is empty and the customizer is a no-op, so the AWS clients are built exactly as
 * before. This keeps F-1 (the SQS report bridge) and the {@code test}-profile integration suite unaffected.</p>
 *
 * <h2>Interaction with F-1 (SQS report bridge)</h2>
 * <p>The AWS SDK instrumentation adds W3C trace-context as SQS <em>message attributes</em>; it never
 * mutates the message <em>body</em>. The {@code @SqsListener} in {@code ReportJobLauncher} parses only the
 * body (a manual {@code ObjectMapper.readValue}), so trace propagation is additive and does not regress the
 * F-1 fix. FIFO content-based deduplication hashes the body (not attributes), so it is likewise unaffected.</p>
 *
 * <p>Design rationale is recorded in {@code docs/decision-log.md}, not in these comments (Explainability
 * rule); the COBOL&nbsp;&rarr;&nbsp;Java mapping is in {@code docs/traceability-matrix.md} (source referenced
 * read-only by commit SHA {@code 27d6c6f}).</p>
 *
 * @see AwsSdkTelemetry
 * @see S3ClientCustomizer
 * @see SqsAsyncClientCustomizer
 * @see SnsClientCustomizer
 */
@Configuration
public class AwsTracingConfig {

    /**
     * Customizer applied to the S3 client builder: appends the OpenTelemetry execution interceptor so its
     * API calls (for example {@code S3.PutObject} for report/statement staging) produce client spans.
     *
     * @param openTelemetryProvider lazy provider for the (optional) auto-configured {@link OpenTelemetry}
     *                              bean; empty when tracing is disabled
     * @return a customizer that is a no-op when tracing is disabled
     */
    @Bean
    S3ClientCustomizer otelS3ClientCustomizer(final ObjectProvider<OpenTelemetry> openTelemetryProvider) {
        return builder -> {
            final ExecutionInterceptor interceptor = resolveTracingInterceptor(openTelemetryProvider);
            if (interceptor != null) {
                appendInterceptor(builder, interceptor);
            }
        };
    }

    /**
     * Customizer applied to the asynchronous SQS client builder: appends the OpenTelemetry execution
     * interceptor so send/receive calls (the report-job bridge) produce messaging client spans.
     *
     * @param openTelemetryProvider lazy provider for the (optional) auto-configured {@link OpenTelemetry}
     *                              bean; empty when tracing is disabled
     * @return a customizer that is a no-op when tracing is disabled
     */
    @Bean
    SqsAsyncClientCustomizer otelSqsAsyncClientCustomizer(final ObjectProvider<OpenTelemetry> openTelemetryProvider) {
        return builder -> {
            final ExecutionInterceptor interceptor = resolveTracingInterceptor(openTelemetryProvider);
            if (interceptor != null) {
                appendInterceptor(builder, interceptor);
            }
        };
    }

    /**
     * Customizer applied to the SNS client builder: appends the OpenTelemetry execution interceptor so its
     * publish calls produce client spans.
     *
     * @param openTelemetryProvider lazy provider for the (optional) auto-configured {@link OpenTelemetry}
     *                              bean; empty when tracing is disabled
     * @return a customizer that is a no-op when tracing is disabled
     */
    @Bean
    SnsClientCustomizer otelSnsClientCustomizer(final ObjectProvider<OpenTelemetry> openTelemetryProvider) {
        return builder -> {
            final ExecutionInterceptor interceptor = resolveTracingInterceptor(openTelemetryProvider);
            if (interceptor != null) {
                appendInterceptor(builder, interceptor);
            }
        };
    }

    /**
     * Resolves the OpenTelemetry AWS SDK execution interceptor, or {@code null} when tracing is disabled
     * (no {@link OpenTelemetry} bean, e.g. the {@code test} profile). Resolving through the
     * {@link ObjectProvider} defers the lookup to customizer-invocation time, forcing bean creation if
     * necessary regardless of bean-definition ordering, and returning {@code null} when the bean is absent.
     *
     * @param openTelemetryProvider lazy provider for the optional {@link OpenTelemetry} bean
     * @return the AWS SDK tracing interceptor, or {@code null} when tracing is disabled
     */
    private static ExecutionInterceptor resolveTracingInterceptor(
            final ObjectProvider<OpenTelemetry> openTelemetryProvider) {
        final OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable();
        if (openTelemetry == null) {
            return null;
        }
        return AwsSdkTelemetry.create(openTelemetry).newExecutionInterceptor();
    }

    /**
     * Appends the tracing interceptor to the given SDK client builder's override configuration. The typed
     * per-service customizers hand back the <em>concrete</em> builder (for example
     * {@link software.amazon.awssdk.services.s3.S3ClientBuilder S3ClientBuilder}), whose interface set
     * includes the {@code AwsClientBuilder → SdkClientBuilder} branch, so the recursive bound
     * {@code B extends SdkClientBuilder<B, C>} is satisfied by concrete inference and the inherited
     * {@link SdkClientBuilder#overrideConfiguration()} getter/setter resolve cleanly (no wildcard capture,
     * no raw types). The existing {@link ClientOverrideConfiguration} (set by Spring Cloud AWS) is copied and
     * extended, never replaced, so its retry/timeout/credential settings survive; a {@code null} current
     * configuration starts from a fresh builder.
     *
     * @param builder     the SDK client builder under assembly; never {@code null}
     * @param interceptor the tracing interceptor to append; never {@code null}
     * @param <B>         the self-referential builder type
     * @param <C>         the built client type
     */
    private static <B extends SdkClientBuilder<B, C>, C> void appendInterceptor(final B builder,
                                                                                final ExecutionInterceptor interceptor) {
        final ClientOverrideConfiguration current = builder.overrideConfiguration();
        final ClientOverrideConfiguration.Builder overrideBuilder =
                (current != null) ? current.toBuilder() : ClientOverrideConfiguration.builder();
        builder.overrideConfiguration(overrideBuilder.addExecutionInterceptor(interceptor).build());
    }
}
