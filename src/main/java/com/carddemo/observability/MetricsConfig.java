/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer / Prometheus metrics configuration for the CardDemo service.
 *
 * <p>This configuration fulfils two responsibilities mandated by the Observability
 * goal (G6) of the CardDemo mainframe-to-Spring migration:</p>
 * <ol>
 *   <li><strong>Common tagging</strong> &mdash; it stamps the low-cardinality static tag
 *       {@code application=carddemo} onto every meter registered in the application, so
 *       that all series are attributable to this service when multiple workloads share a
 *       Prometheus / Grafana stack.</li>
 *   <li><strong>Domain counters</strong> &mdash; it declares the two business counters that
 *       mirror the transaction tallies of the legacy batch posting program
 *       {@code CBTRN02C} (see the mapping below), so the modernised POSTTRAN batch job can
 *       surface the same operational signal the COBOL program emitted via {@code DISPLAY}.</li>
 * </ol>
 *
 * <h2>Auto-configured registry contract (do not create a registry here)</h2>
 * <p>This class deliberately does <em>not</em> create a {@link MeterRegistry} (nor a
 * {@code PrometheusMeterRegistry}). Spring Boot Actuator together with
 * {@code micrometer-registry-prometheus} auto-configure the Prometheus registry and the
 * scrape endpoint. Hand-instantiating a registry would shadow or duplicate the
 * auto-configured one and break the endpoint. Instead, this class only:</p>
 * <ul>
 *   <li><em>customises</em> the auto-configured registry via a
 *       {@link MeterRegistryCustomizer} (the common tag), and</li>
 *   <li><em>registers meters</em> into the injected {@link MeterRegistry} (the counters).</li>
 * </ul>
 *
 * <h2>Scrape contract</h2>
 * <p>Metrics are exposed for scraping at {@code /actuator/prometheus}. Actuator web
 * exposure ({@code health,info,prometheus}) and the {@code management.metrics.tags.application}
 * property are declared in {@code application.yml}; setting the same
 * {@code application=carddemo} tag programmatically here is intentional and idempotent, and
 * guarantees the tag independently of external configuration. This class must not widen the
 * actuator exposure and must not add endpoints. Distributed tracing (Micrometer Tracing
 * &rarr; OpenTelemetry &rarr; OTLP &rarr; Jaeger) is configured elsewhere
 * ({@code config.ObservabilityConfig} + {@code application*.yml}) and is intentionally not
 * touched here.</p>
 *
 * <h2>Legacy traceability (CBTRN02C &rarr; Java)</h2>
 * <p>Source of truth: {@code app/cbl/CBTRN02C.cbl} at commit SHA {@code 27d6c6f}
 * (full {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}); the COBOL is referenced, never
 * copied. The {@code 01 WS-COUNTERS} working-storage group maps as follows:</p>
 * <ul>
 *   <li>{@code WS-TRANSACTION-COUNT} (PIC 9(09)) &rarr; {@link #transactionsPostedCounter(MeterRegistry)}
 *       &rarr; Prometheus series {@code carddemo_transactions_posted_total}.</li>
 *   <li>{@code WS-REJECT-COUNT} (PIC 9(09)) &rarr; {@link #transactionsRejectedCounter(MeterRegistry)}
 *       &rarr; Prometheus series {@code carddemo_transactions_rejected_total}.</li>
 * </ul>
 * <p>The counters are <em>defined</em> here but <em>incremented</em> by the POSTTRAN batch
 * step in the {@code batch} layer (which injects these beans), preserving the COBOL
 * {@code ADD 1 TO WS-TRANSACTION-COUNT} / {@code ADD 1 TO WS-REJECT-COUNT} semantics. This
 * configuration must never fabricate increments. Rationale is recorded in
 * {@code docs/decision-log.md}; the paragraph-level mapping is recorded in
 * {@code docs/traceability-matrix.md}.</p>
 *
 * @see MeterRegistryCustomizer
 * @see io.micrometer.core.instrument.Counter
 */
@Configuration
public class MetricsConfig {

    /**
     * Common tag key applied to every meter. Kept in lock-step with the
     * {@code management.metrics.tags.application} property in {@code application.yml}.
     */
    private static final String COMMON_TAG_KEY = "application";

    /** Common tag value identifying this service. */
    private static final String COMMON_TAG_VALUE = "carddemo";

    /**
     * Micrometer (dot-delimited) name for the posted-transactions counter. Prometheus
     * renders this as {@code carddemo_transactions_posted_total}.
     */
    private static final String METRIC_TRANSACTIONS_POSTED = "carddemo.transactions.posted";

    /**
     * Micrometer (dot-delimited) name for the rejected-transactions counter. Prometheus
     * renders this as {@code carddemo_transactions_rejected_total}.
     */
    private static final String METRIC_TRANSACTIONS_REJECTED = "carddemo.transactions.rejected";

    /**
     * Customises the auto-configured {@link MeterRegistry} by attaching the single static
     * common tag {@code application=carddemo} to every meter.
     *
     * <p>Tag cardinality is intentionally kept low: only this one static tag is applied.
     * Per-request / per-entity values (card numbers, account IDs, user IDs, or any other
     * high-cardinality or PII value) must never be added as tags, as they would explode the
     * time-series cardinality and risk leaking sensitive data into the metrics store.</p>
     *
     * @return a customizer that registers the common {@code application} tag on the
     *         auto-configured registry
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> carddemoCommonTags() {
        return registry -> registry.config().commonTags(COMMON_TAG_KEY, COMMON_TAG_VALUE);
    }

    /**
     * Posted-transactions counter &mdash; mirrors {@code CBTRN02C WS-TRANSACTION-COUNT}
     * ({@code ADD 1 TO WS-TRANSACTION-COUNT} fires for every daily transaction processed).
     *
     * <p>Registered into the injected auto-configured registry and incremented by the
     * POSTTRAN batch step; this configuration never increments it.</p>
     *
     * @param registry the auto-configured {@link MeterRegistry} (never {@code null})
     * @return the registered posted-transactions {@link Counter}
     *         ({@code carddemo_transactions_posted_total})
     */
    @Bean
    Counter transactionsPostedCounter(MeterRegistry registry) {
        // A Micrometer base unit is intentionally NOT set: the Prometheus naming convention appends
        // any non-null base unit to the series name as a unit suffix (yielding, e.g.,
        // "carddemo_transactions_posted_transactions_total"). Omitting it keeps the scraped series
        // exactly "carddemo_transactions_posted_total" (the counter "_total" suffix is still added
        // automatically). Rationale is recorded in docs/decision-log.md.
        return Counter.builder(METRIC_TRANSACTIONS_POSTED)
                .description("Total daily transactions processed/posted by the POSTTRAN batch job "
                        + "(mirrors CBTRN02C WS-TRANSACTION-COUNT)")
                .register(registry);
    }

    /**
     * Rejected-transactions counter &mdash; mirrors {@code CBTRN02C WS-REJECT-COUNT}
     * ({@code ADD 1 TO WS-REJECT-COUNT} fires when validation fails, before a reject record
     * is written; a non-zero total is what drives the COBOL {@code RETURN-CODE=4}).
     *
     * <p>Registered into the injected auto-configured registry and incremented by the
     * POSTTRAN batch step; this configuration never increments it.</p>
     *
     * @param registry the auto-configured {@link MeterRegistry} (never {@code null})
     * @return the registered rejected-transactions {@link Counter}
     *         ({@code carddemo_transactions_rejected_total})
     */
    @Bean
    Counter transactionsRejectedCounter(MeterRegistry registry) {
        // baseUnit omitted intentionally (see transactionsPostedCounter for the full rationale) so
        // the Prometheus series renders exactly as "carddemo_transactions_rejected_total".
        return Counter.builder(METRIC_TRANSACTIONS_REJECTED)
                .description("Daily transactions rejected during validation by the POSTTRAN batch job "
                        + "(mirrors CBTRN02C WS-REJECT-COUNT)")
                .register(registry);
    }
}
