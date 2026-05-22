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
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterRegistryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Wires the Micrometer &rarr; Amazon CloudWatch metrics bridge required by
 * AAP &sect;0.6.6 ("CloudWatch + Container Insights captures ECS
 * task-level&hellip; Spring Actuator metrics are exported via Micrometer
 * &rarr; CloudWatch under namespace {@code CardDemo}").
 *
 * <p>Configuration constants per the CP3 checkpoint:</p>
 * <ul>
 *   <li>Namespace: {@code CardDemo} (constant; overridable via
 *       {@code carddemo.metrics.cloudwatch.namespace}).</li>
 *   <li>Step interval: 60 seconds &mdash; CloudWatch's free-tier
 *       resolution.</li>
 *   <li>Batch size: 20 &mdash; CloudWatch's per-request hard limit on
 *       {@code MetricDatum} entries (the constant
 *       {@code CloudWatchConfig.MAX_BATCH_SIZE} from Micrometer matches
 *       this value at 20).</li>
 *   <li>Common tags: {@code service}, {@code environment}, {@code instance}.
 *       <strong>Sensitive tag names are deliberately filtered out</strong>
 *       so that an inadvertent tag containing PII / card data is never
 *       shipped to CloudWatch.</li>
 * </ul>
 *
 * <h2>Replaces (AAP &sect;0.1.1)</h2>
 * <p>Replaces: implicit JES2 batch job statistics + CICS RMF II metrics.
 * The mainframe surfaced these via SDSF and RMF reports; the Java target
 * surfaces them in CloudWatch Metrics / Dashboards / Alarms.</p>
 *
 * <h2>Tag-safety filter</h2>
 * <p>A {@link MeterFilter} strips any tag whose key contains a known
 * sensitive substring (password, card, account, pan, cvv, ssn, secret,
 * token, key). This protects against accidental tag pollution from
 * instrumentation libraries or future contributors that might add
 * dynamic tag values containing customer data.</p>
 *
 * @see io.micrometer.cloudwatch2.CloudWatchMeterRegistry
 * @see com.awsm2.carddemo.config.AwsSdkConfig#cloudWatchAsyncClient()
 */
@Configuration
public class CloudWatchConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CloudWatchConfig.class);

    /** CloudWatch metrics namespace per AAP &sect;0.6.6. */
    private static final String DEFAULT_NAMESPACE = "CardDemo";
    /** CloudWatch step interval &mdash; lowest free-tier publish rate. */
    private static final Duration STEP = Duration.ofSeconds(60);
    /** CloudWatch hard cap on {@code MetricDatum} entries per request. */
    private static final int BATCH_SIZE = 20;

    /**
     * Lower-case substrings of tag keys we will scrub from every metric
     * before publishing to CloudWatch. AAP rule: "No plaintext card/account
     * data in logs &mdash; enforced via CloudWatch log filters + Macie".
     * The same principle applies to metric tag values: if the key suggests
     * the value might contain sensitive data, drop it.
     */
    private static final List<String> SENSITIVE_TAG_SUBSTRINGS = List.of(
            "password", "passwd", "pwd",
            "card", "pan",
            "account", "acct",
            "cvv", "cvc", "pin",
            "ssn",
            "secret", "token", "apikey", "api_key",
            "authorization", "auth",
            "privatekey", "private_key");

    private final String namespace;
    private final String serviceName;
    private final String environment;
    private final String instance;

    public CloudWatchConfig(
            @Value("${carddemo.metrics.cloudwatch.namespace:CardDemo}") String namespace,
            @Value("${spring.application.name:carddemo}") String serviceName,
            @Value("${spring.profiles.active:local}") String environment,
            @Value("${HOSTNAME:${INSTANCE_ID:local}}") String instance) {
        this.namespace = (namespace == null || namespace.isBlank())
                ? DEFAULT_NAMESPACE : namespace;
        this.serviceName = (serviceName == null || serviceName.isBlank())
                ? "carddemo" : serviceName;
        this.environment = (environment == null || environment.isBlank())
                ? "local" : environment;
        this.instance = (instance == null || instance.isBlank())
                ? "local" : instance;
    }

    /**
     * Provides the immutable Micrometer-side configuration for the
     * CloudWatch registry &mdash; namespace, step, and batch size.
     * Declared as a {@link MeterRegistryConfig} sub-type
     * ({@code io.micrometer.cloudwatch2.CloudWatchConfig}) so Spring
     * Boot's {@code MetricsAutoConfiguration} integrates it normally.
     *
     * @return the Micrometer CloudWatch config
     */
    @Bean
    public io.micrometer.cloudwatch2.CloudWatchConfig cloudWatchMicrometerConfig() {
        // Replaces: RMF / SDSF batch statistics — now native CloudWatch metrics.
        return new io.micrometer.cloudwatch2.CloudWatchConfig() {
            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public Duration step() {
                return STEP;
            }

            @Override
            public int batchSize() {
                return BATCH_SIZE;
            }

            @Override
            public String get(String k) {
                // Defer to the StepRegistryConfig defaults for any
                // property not explicitly overridden above.
                return null;
            }
        };
    }

    /**
     * The CloudWatch {@link MeterRegistry} itself. Publishes metrics via
     * the shared {@link CloudWatchAsyncClient} produced by
     * {@link AwsSdkConfig#cloudWatchAsyncClient()}, on every {@link #STEP}
     * interval.
     *
     * @param config              the Micrometer-side config (above)
     * @param clock               the Micrometer clock (auto-configured)
     * @param cloudWatchAsyncClient  shared async CW client
     * @return the registry
     */
    @Bean(destroyMethod = "close")
    public CloudWatchMeterRegistry cloudWatchMeterRegistry(
            io.micrometer.cloudwatch2.CloudWatchConfig config,
            Clock clock,
            CloudWatchAsyncClient cloudWatchAsyncClient) {
        Objects.requireNonNull(cloudWatchAsyncClient,
                "cloudWatchAsyncClient must not be null");
        LOG.info("CloudWatchMeterRegistry configured namespace={} step={} batchSize={}",
                namespace, STEP, BATCH_SIZE);
        return new CloudWatchMeterRegistry(config, clock, cloudWatchAsyncClient);
    }

    /**
     * Adds the constant {@code service}/{@code environment}/{@code instance}
     * tags to <em>every</em> meter so CloudWatch dashboards can slice by
     * these dimensions without each instrumented call having to set them.
     *
     * @return the customizer applied during registry assembly
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer() {
        return registry -> registry.config().commonTags(List.of(
                Tag.of("service", serviceName),
                Tag.of("environment", environment),
                Tag.of("instance", instance)));
    }

    /**
     * Adds a {@link MeterFilter} that strips tags whose keys look
     * sensitive. Acts as a defence-in-depth against accidental metric
     * tag pollution &mdash; the Macie / log-filter rules cover free-form
     * log content; this filter covers structured metric tags.
     *
     * @return the customizer
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> sensitiveTagFilterCustomizer() {
        return registry -> registry.config().meterFilter(new MeterFilter() {
            @Override
            public io.micrometer.core.instrument.Meter.Id map(
                    io.micrometer.core.instrument.Meter.Id id) {
                List<Tag> filtered = new java.util.ArrayList<>();
                boolean changed = false;
                for (Tag tag : id.getTagsAsIterable()) {
                    if (isSensitive(tag.getKey())) {
                        changed = true;
                        continue;
                    }
                    filtered.add(tag);
                }
                return changed ? id.replaceTags(filtered) : id;
            }
        });
    }

    /**
     * Returns true when the given tag key contains any substring listed
     * in {@link #SENSITIVE_TAG_SUBSTRINGS}. Case-insensitive.
     */
    static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(java.util.Locale.US);
        for (String s : SENSITIVE_TAG_SUBSTRINGS) {
            if (lower.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
