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
package com.carddemo.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Non-auto-configured observability glue that complements the property-driven
 * Micrometer / OpenTelemetry / Actuator baseline declared in {@code application.yml}.
 *
 * <p>This class deliberately does <em>not</em> declare a {@link MeterRegistry}
 * (Spring Boot owns it), does not set the common {@code application} tag (set via
 * {@code management.metrics.tags.application}), and does not re-declare the custom
 * business meters (registered in {@code com.carddemo.observability.MetricsConfig}).
 * It contributes only the two pieces that are not produced by property
 * auto-configuration:</p>
 * <ul>
 *   <li>a {@link MeterFilter} that enables percentile histograms and client-side
 *       percentiles for HTTP server timers and the application's own
 *       {@code carddemo.*} meters, yielding dashboard-ready latency distributions
 *       on {@code /actuator/prometheus} (the {@code le=...} bucket series); and</li>
 *   <li>the {@link ObservedAspect} and {@link TimedAspect} aspects that activate
 *       {@code @Observed} (Observation &rarr; span + metric) and {@code @Timed}
 *       (method timer) on annotated service and component methods, providing
 *       per-operation tracing and timing across service boundaries.</li>
 * </ul>
 *
 * <p>The aspects rely on AspectJ auto-proxying, which Spring Boot enables
 * automatically because {@code aspectjweaver} and {@code spring-aop} are already
 * present on the classpath. Trace export, sampling, and Prometheus export remain
 * property-driven; no endpoints or secrets are referenced here.</p>
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Enables percentile histograms and the 50th/95th/99th client-side
     * percentiles for HTTP server request timers and every {@code carddemo.*}
     * meter, while leaving all other meters untouched.
     *
     * <p>Spring Boot applies every {@link MeterFilter} bean to the
     * auto-configured registry, so this filter requires no further wiring. The
     * filter only augments distribution statistics; it never accepts, denies, or
     * renames meters and never alters common tags.</p>
     *
     * @return a meter filter that merges distribution-statistics settings onto
     *         the matched meters' existing configuration
     */
    @Bean
    public MeterFilter carddemoDistributionStatisticFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                String name = id.getName();
                if (name.startsWith("http.server.requests") || name.startsWith("carddemo")) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .percentiles(0.5, 0.95, 0.99)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    /**
     * Activates the {@code @Observed} annotation so annotated service and
     * component methods create an {@link io.micrometer.observation.Observation}
     * that produces both a trace span and a timer metric.
     *
     * @param observationRegistry the Actuator auto-configured observation registry
     * @return the aspect that intercepts {@code @Observed} methods
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Activates the {@code @Timed} annotation so annotated methods publish a
     * timer to the auto-configured registry.
     *
     * @param meterRegistry the Spring Boot auto-configured meter registry
     * @return the aspect that intercepts {@code @Timed} methods
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        return new TimedAspect(meterRegistry);
    }
}
