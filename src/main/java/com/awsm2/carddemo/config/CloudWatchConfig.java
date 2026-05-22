/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.config;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring {@code @Configuration} that wires Micrometer to publish application
 * metrics to Amazon CloudWatch under the namespace {@code CardDemo}
 * (AAP &sect;0.6.6).
 *
 * <p>This configuration is enabled when
 * {@code management.metrics.export.cloudwatch.enabled=true} (the default in
 * the {@code dev} and {@code prod} profiles via the
 * {@code CLOUDWATCH_METRICS_ENABLED} environment variable); the {@code local}
 * and {@code test} profiles leave it {@code false} so Spring Boot's
 * auto-configured {@code SimpleMeterRegistry} is used instead. This keeps
 * local development free of AWS API calls.</p>
 *
 * <p>Replaces: SDSF / RMF (Resource Measurement Facility) mainframe
 * observability &mdash; the source CardDemo COBOL application had no
 * application-level metrics. CloudWatch + Container Insights provides ECS
 * task-level CPU, memory, network, and Docker metrics; Micrometer adds
 * application-level metrics (HTTP request counts, JPA query latencies,
 * Kafka producer/consumer lag, BigDecimal arithmetic operation counts)
 * shipped via the Spring Boot {@code spring-boot-starter-actuator} hook.</p>
 *
 * <p>Per AAP &sect;0.6.6, the registry adds the following dimensions to every
 * metric:</p>
 * <ul>
 *   <li>{@code service} &mdash; the application name (default {@code carddemo})</li>
 *   <li>{@code environment} &mdash; the active Spring profile (e.g.,
 *       {@code dev}, {@code prod})</li>
 *   <li>{@code instance} &mdash; the ECS task ID / host name</li>
 * </ul>
 *
 * <p>Per AAP &sect;0.7.1 &mdash; no business logic; no AWS SDK calls inline.
 * The {@link CloudWatchAsyncClient} bean is provided by {@link AwsSdkConfig}.
 * Per AAP &sect;0.5.1 &mdash; AWS SDK v2 only ({@code software.amazon.awssdk.*});
 * the deprecated v1 ({@code com.amazonaws.*}) is NEVER referenced.</p>
 *
 * <p><b>Naming-collision note:</b> The Micrometer-provided configuration
 * interface is also named {@code CloudWatchConfig} (in package
 * {@code io.micrometer.cloudwatch2}). To prevent confusion with this class,
 * the Micrometer interface is referenced via its fully qualified name
 * ({@code io.micrometer.cloudwatch2.CloudWatchConfig}) rather than imported.</p>
 *
 * @see AwsSdkConfig
 * @see com.awsm2.carddemo.adapter.AuditLogService
 * @see io.micrometer.cloudwatch2.CloudWatchMeterRegistry
 */
@Configuration
public class CloudWatchConfig {

    /**
     * CloudWatch metrics namespace per AAP &sect;0.6.6. Default {@code CardDemo};
     * the {@code dev} overlay may set {@code CardDemo/dev}.
     */
    @Value("${management.metrics.export.cloudwatch.namespace:CardDemo}")
    private String namespace;

    /**
     * Publish interval (Micrometer step). Default {@code 60s} &mdash; matches
     * CloudWatch's free-tier resolution. Shorter intervals (e.g., {@code 10s})
     * raise CloudWatch costs; longer (e.g., {@code 5m}) reduce real-time
     * visibility.
     */
    @Value("${management.metrics.export.cloudwatch.step:60s}")
    private Duration step;

    /**
     * Maximum {@code MetricDatum} entries per {@code PutMetricData} request.
     * CloudWatch hard limit is 20.
     */
    @Value("${management.metrics.export.cloudwatch.batch-size:20}")
    private int batchSize;

    /**
     * Mirror of the {@code @ConditionalOnProperty} value &mdash; exposed as a
     * field for logging / diagnostics, not consulted at bean-creation time
     * (the {@code @ConditionalOnProperty} on
     * {@link #cloudWatchMeterRegistry(CloudWatchAsyncClient, Clock)} handles
     * gating).
     */
    @Value("${management.metrics.export.cloudwatch.enabled:false}")
    private boolean enabled;

    /**
     * Service name dimension applied to every metric. Mirrors
     * {@code spring.application.name} (default {@code carddemo}).
     */
    @Value("${spring.application.name:carddemo}")
    private String serviceName;

    /**
     * Environment dimension applied to every metric. Mirrors the active
     * Spring profile (set via the {@code SPRING_PROFILES_ACTIVE} env var by
     * ECS). Defaults to {@code unknown} when no profile is active.
     */
    @Value("${spring.profiles.active:unknown}")
    private String environment;

    /**
     * Instance dimension applied to every metric. ECS task ID or host name.
     * Resolved from the {@code HOSTNAME} env var first (ECS sets this to the
     * task ID for awsvpc-mode tasks), falling back to {@code INSTANCE_ID},
     * then to {@code unknown}.
     */
    @Value("${HOSTNAME:${INSTANCE_ID:unknown}}")
    private String instance;

    /**
     * Default no-arg constructor; {@code @Value}-injected fields are populated
     * by Spring after instantiation, and bean methods are invoked with
     * runtime-injected dependencies ({@link CloudWatchAsyncClient},
     * {@link Clock}) provided by the Spring container.
     */
    public CloudWatchConfig() {
        // no-op — Spring populates @Value fields via reflection after
        // construction; bean methods receive their args from the container.
    }

    /**
     * Creates a {@link CloudWatchMeterRegistry} that publishes Micrometer
     * metrics to Amazon CloudWatch via the AWS SDK v2
     * {@link CloudWatchAsyncClient} bean provided by {@link AwsSdkConfig}.
     *
     * <p>Replaces: SDSF / RMF mainframe observability &mdash; application-level
     * metrics are emitted by Micrometer {@code @Timed} / {@code @Counted}
     * instrumentation (and by Spring Actuator's HTTP, JPA, JVM, and Kafka
     * meter bindings) and shipped to CloudWatch under namespace
     * {@code CardDemo} per AAP &sect;0.6.6.</p>
     *
     * <p>Gated by
     * {@code management.metrics.export.cloudwatch.enabled=true}; the
     * {@code local} and {@code test} profiles leave this {@code false} so
     * Spring Boot's auto-configured {@code SimpleMeterRegistry} is used
     * instead. This keeps local development free of AWS API calls.</p>
     *
     * <p>The async client variant is chosen because metric publication is
     * inherently non-blocking and Micrometer's CloudWatch registry batches
     * data points asynchronously on the scheduled flush interval defined
     * by {@link #step}.</p>
     *
     * @param cloudWatchAsyncClient the AWS SDK v2 CloudWatch async client
     *                              (injected from
     *                              {@link AwsSdkConfig#cloudWatchAsyncClient()})
     * @param clock                 the Micrometer {@link Clock} used for
     *                              metric timestamping (Spring Boot
     *                              auto-configures {@code Clock.SYSTEM})
     * @return a configured {@link CloudWatchMeterRegistry}
     */
    @Bean
    @ConditionalOnProperty(
            name = "management.metrics.export.cloudwatch.enabled",
            havingValue = "true")
    public CloudWatchMeterRegistry cloudWatchMeterRegistry(
            CloudWatchAsyncClient cloudWatchAsyncClient,
            Clock clock) {
        // Replaces: SDSF / RMF mainframe observability — application-level
        // metrics are shipped to CloudWatch under namespace `CardDemo` per
        // AAP §0.6.6. The async client is supplied by AwsSdkConfig so this
        // configuration contains no AWS SDK builder calls itself
        // (AAP §0.7.1: never inline AWS SDK calls in business logic / config).
        io.micrometer.cloudwatch2.CloudWatchConfig micrometerCloudWatchConfig =
                cloudWatchConfig();
        return new CloudWatchMeterRegistry(
                micrometerCloudWatchConfig, clock, cloudWatchAsyncClient);
    }

    /**
     * Adds common dimensions (tags) to every metric emitted by any
     * {@link MeterRegistry} bean &mdash; including the CloudWatch registry
     * and the fallback {@code SimpleMeterRegistry} used in the {@code local}
     * profile.
     *
     * <p>Per AAP &sect;0.6.6, every CloudWatch metric must include:</p>
     * <ul>
     *   <li>{@code service} &mdash; the application name (default
     *       {@code carddemo})</li>
     *   <li>{@code environment} &mdash; the active Spring profile</li>
     *   <li>{@code instance} &mdash; the ECS task ID or host name</li>
     * </ul>
     *
     * <p>This {@link MeterRegistryCustomizer} is applied at registry-init
     * time and decorates the registry's {@code config().commonTags(...)}.
     * Because it operates on {@code MeterRegistry} (the generic type), it
     * is applied to every registry bean in the Spring context, including
     * composite registries assembled by Spring Boot
     * {@code MetricsAutoConfiguration}.</p>
     *
     * <p>Replaces: implicit job/transaction identification fields embedded
     * in COBOL audit-trail records (e.g., {@code JOB-NAME},
     * {@code STEP-NAME}, {@code OPERATOR-ID}) &mdash; these are now metric
     * dimensions in CloudWatch so dashboards can slice metrics by service,
     * environment, and instance without each instrumentation site having
     * to attach tags manually.</p>
     *
     * @return a customizer that attaches the common service/environment/
     *         instance tags to every metric
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> meterRegistryCustomizer() {
        // Replaces: COBOL audit-trail JOB-NAME / STEP-NAME / OPERATOR-ID
        // record fields — these are now CloudWatch metric dimensions.
        // NB: MeterRegistry.Config#commonTags has two overloads —
        // (String... keyValues) and (Iterable<Tag>). We construct a List of
        // Tag instances and rely on the Iterable<Tag> overload so we can
        // pass strongly typed Tag instances rather than untyped strings.
        return registry -> registry.config().commonTags(List.of(
                Tag.of("service", resolveTag(serviceName, "carddemo")),
                Tag.of("environment", resolveTag(environment, "unknown")),
                Tag.of("instance", resolveTag(instance, "unknown"))));
    }

    /**
     * Builds the Micrometer {@link io.micrometer.cloudwatch2.CloudWatchConfig}
     * key-value source from this class's {@code @Value}-injected fields.
     *
     * <p>The Micrometer {@code CloudWatchConfig} is an interface whose
     * essential contract is {@code String get(String key)}. The interface
     * supplies default implementations for {@code namespace()},
     * {@code step()}, and {@code batchSize()} that read keys
     * {@code cloudwatch.namespace}, {@code cloudwatch.step}, and
     * {@code cloudwatch.batchSize} from the underlying key-value source.
     * We therefore populate an in-memory {@link Map} with those three keys
     * and return {@code properties::get} as the lambda implementing
     * {@code CloudWatchConfig#get(String)}.</p>
     *
     * @return the Micrometer CloudWatch configuration adapter
     */
    private io.micrometer.cloudwatch2.CloudWatchConfig cloudWatchConfig() {
        final Map<String, String> properties = new HashMap<>();
        properties.put("cloudwatch.namespace", resolveTag(namespace, "CardDemo"));
        properties.put("cloudwatch.step", step != null ? step.toString() : "PT1M");
        properties.put("cloudwatch.batchSize", String.valueOf(batchSize));
        // Lambda implementing CloudWatchConfig#get(String). Returning null
        // for unknown keys triggers the StepRegistryConfig defaults
        // (everything not in our explicit list defers to Micrometer defaults).
        return properties::get;
    }

    /**
     * Returns {@code value} if non-null and non-blank, otherwise
     * {@code fallback}. Used to harden tag values against accidental blank
     * properties so CloudWatch never receives an empty dimension value
     * (CloudWatch rejects {@code MetricDatum} entries with empty tag values).
     *
     * @param value    the candidate value
     * @param fallback the value to use when {@code value} is null or blank
     * @return a non-null, non-blank tag value
     */
    private static String resolveTag(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
