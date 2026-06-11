package com.cardemo.observability;

import java.math.BigDecimal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Custom business-metric definitions for the greenfield Java 25 LTS + Spring Boot 3.5.11 migration
 * of the AWS CardDemo COBOL/CICS mainframe application.
 *
 * <h2>Provenance &mdash; net-new observability component (no COBOL equivalent)</h2>
 * <p>This class has <strong>no COBOL source</strong>: it is a pure technology-substitution component
 * in the net-new cross-cutting observability layer. The legacy AWS CardDemo COBOL/CICS estate
 * shipped <em>zero</em> metrics infrastructure &mdash; no counters, no timers, no distribution
 * statistics, no scrape endpoint (tech-spec L818). Per the <strong>Minimal Change Clause</strong>
 * (AAP &sect;0.7.1) this file only makes the running application observable; it alters <em>no</em>
 * business behaviour. Traceability to the frozen legacy baseline is by original COBOL repository
 * commit SHA {@code 27d6c6f}; the COBOL source is <em>never copied</em> into this repository
 * (AAP &sect;0.7.2 &mdash; Preservation Requirements). The file role is defined by the authoritative
 * target tree as &quot;Custom business metrics&quot; (tech-spec L460), within the Metrics Endpoint
 * portion of the Observability Implementation Analysis (tech-spec L816-L836).</p>
 *
 * <h2>Single responsibility &mdash; the four custom business metrics</h2>
 * <p>This {@code @Configuration} defines and exposes exactly the four custom Micrometer business
 * meters, surfaced through Spring Boot Actuator at {@code /actuator/prometheus} (the auto-configured
 * Prometheus registry from {@code micrometer-registry-prometheus}). It owns <em>only</em> these
 * four concrete meters plus their facade &mdash; nothing else (see the boundaries section below).</p>
 * <table border="1">
 *   <caption>Custom business meters owned by this file</caption>
 *   <tr><th>Micrometer name</th><th>Type</th><th>Tag(s)</th><th>Meaning</th></tr>
 *   <tr><td>{@code carddemo.batch.records.processed}</td><td>{@link Counter}</td>
 *       <td>{@code job}</td>
 *       <td>Records successfully processed by a batch job (e.g. {@code job=DailyTransactionPosting});
 *           conceptually mirrors the COBOL batch programs' processed-record tallies such as
 *           {@code CBTRN02C}.</td></tr>
 *   <tr><td>{@code carddemo.batch.records.rejected}</td><td>{@link Counter}</td>
 *       <td>{@code reason}</td>
 *       <td>Records rejected during batch validation (e.g. {@code reason=INVALID_CARD},
 *           {@code reason=ACCOUNT_NOT_FOUND}).</td></tr>
 *   <tr><td>{@code carddemo.auth.attempts}</td><td>{@link Counter}</td>
 *       <td>{@code outcome}</td>
 *       <td>Authentication attempts tagged {@code outcome=success}/{@code outcome=failure}, emitted
 *           by the sign-on flow translated from {@code COSGN00C}.</td></tr>
 *   <tr><td>{@code carddemo.transaction.amount.total}</td><td>{@link DistributionSummary}</td>
 *       <td>(none)</td>
 *       <td>Distribution (count, total, max) of posted transaction monetary amounts in
 *           {@value #CURRENCY_BASE_UNIT}, emitted by the posting paths translated from
 *           {@code COTRN02C}/{@code COBIL00C} and the posting batch.</td></tr>
 * </table>
 *
 * <h2>Why a call-site-tagged facade (runtime-varying tags)</h2>
 * <p>Three of the four meters carry tags whose values are only known at runtime ({@code job},
 * {@code reason}, {@code outcome}). A Micrometer {@link Counter} is immutable and bound to a single
 * name+tag-set, so these <em>cannot</em> be pre-registered as one fixed bean. The idiomatic
 * Micrometer pattern is therefore to apply the tag at the <strong>call site</strong>:
 * {@code Counter.builder(name).tag(key, value).register(registry).increment()}. The registry caches
 * and de-duplicates meters by their identity (name + tags), so repeatedly building a counter for the
 * same name+tag combination returns the same underlying meter &mdash; this is both safe and
 * efficient and needs <em>no</em> hand-rolled meter cache.</p>
 * <p>To keep that pattern out of business code, the nested {@link BusinessMetrics} {@code @Component}
 * is a thin facade: service and batch components inject it and call intention-revealing methods
 * (for example {@link BusinessMetrics#recordAuthAttempt(boolean)}) rather than constructing meters
 * inline. The single non-tagged meter, the transaction-amount {@link DistributionSummary}, has no
 * runtime-varying tag and so <em>is</em> pre-registered once as the {@link #transactionAmountSummary
 * transactionAmountSummary} {@code @Bean} and injected into the facade.</p>
 *
 * <h2>Cross-file metric-name contract (character-exact)</h2>
 * <p>The four name literals are a <strong>frozen cross-file contract</strong>. They MUST stay
 * byte-identical to the names documented in the sibling {@code carddemo-java/prometheus.yml} (which
 * scrapes {@code app:8080} at {@code /actuator/prometheus}) and referenced in
 * {@code src/main/resources/application.yml} (which states these meters are &quot;emitted by Java
 * code ({@code observability/MetricsConfig}) &mdash; do NOT define them here&quot;). The Prometheus
 * registry converts dots to underscores and appends a type suffix, so for example
 * {@code carddemo.batch.records.processed} is scraped as
 * {@code carddemo_batch_records_processed_total}. To preserve that documented scrape name the
 * counters deliberately set <strong>no</strong> base unit (a base unit would inject an extra token
 * into the exposed name); the {@link DistributionSummary} sets base unit {@value #CURRENCY_BASE_UNIT}
 * as required for a monetary distribution.</p>
 *
 * <h2>Decimal precision &mdash; {@code BigDecimal} &rarr; {@code double} only at the metrics boundary</h2>
 * <p>The decimal-precision rule (AAP &sect;0.7.3) mandates {@code BigDecimal} for every monetary value
 * with <em>zero</em> floating-point substitution. Micrometer's {@link DistributionSummary#record(double)}
 * however accepts only a {@code double}. The narrowing {@code BigDecimal.doubleValue()} conversion is
 * therefore confined to the single telemetry boundary in
 * {@link BusinessMetrics#recordTransactionAmount(BigDecimal)} &mdash; this is a reporting/telemetry
 * value, never an input to a financial calculation. All actual monetary arithmetic elsewhere in the
 * application stays in {@code BigDecimal}; no {@code float}/{@code double} is used for any monetary
 * computation in this file.</p>
 *
 * <h2>Separation of concerns (boundaries this file does not cross)</h2>
 * <ul>
 *   <li>It does <strong>not</strong> set the Actuator endpoint exposure
 *       ({@code management.endpoints.web.exposure.include}) &mdash; that is owned by
 *       {@code application.yml} ({@code health,info,metrics,prometheus}).</li>
 *   <li>It does <strong>not</strong> set {@code spring.application.name} nor any global
 *       application-name common tag/registry customizer &mdash; that is owned by
 *       {@code application.yml} ({@code management.metrics.tags.application: carddemo}).</li>
 *   <li>It does <strong>not</strong> register the Micrometer {@code ObservedAspect} bean &mdash; that
 *       is owned exclusively by {@code config/ObservabilityConfig.java}.</li>
 *   <li>It does <strong>not</strong> define the {@link MeterRegistry} itself &mdash; that is
 *       auto-configured by Spring Boot Actuator together with the Prometheus registry starter; it is
 *       injected here.</li>
 *   <li>It performs <strong>no</strong> tracing, correlation-ID, health-check, security, database,
 *       S3, or SQS work.</li>
 * </ul>
 *
 * @see io.micrometer.core.instrument.Counter
 * @see io.micrometer.core.instrument.DistributionSummary
 * @see io.micrometer.core.instrument.MeterRegistry
 */
@Configuration
public class MetricsConfig {

    // -------------------------------------------------------------------------------------------
    // Frozen cross-file metric-name contract (must stay byte-identical to prometheus.yml).
    // Centralized as named constants so each canonical literal is declared in exactly one place.
    // -------------------------------------------------------------------------------------------

    /** Counter: records successfully processed by batch jobs (tagged {@link #TAG_JOB}). */
    static final String METRIC_BATCH_RECORDS_PROCESSED = "carddemo.batch.records.processed";

    /** Counter: records rejected during batch validation (tagged {@link #TAG_REASON}). */
    static final String METRIC_BATCH_RECORDS_REJECTED = "carddemo.batch.records.rejected";

    /** Counter: authentication attempts (tagged {@link #TAG_OUTCOME}). */
    static final String METRIC_AUTH_ATTEMPTS = "carddemo.auth.attempts";

    /** Distribution summary: monetary amount of posted transactions. */
    static final String METRIC_TRANSACTION_AMOUNT_TOTAL = "carddemo.transaction.amount.total";

    // -------------------------------------------------------------------------------------------
    // Tag keys (the value of each is supplied at the call site, per the runtime-tag rationale).
    // -------------------------------------------------------------------------------------------

    /** Tag key differentiating the {@link #METRIC_BATCH_RECORDS_PROCESSED} counter per batch job. */
    static final String TAG_JOB = "job";

    /** Tag key carrying the rejection reason on the {@link #METRIC_BATCH_RECORDS_REJECTED} counter. */
    static final String TAG_REASON = "reason";

    /** Tag key carrying the {@code success}/{@code failure} outcome on {@link #METRIC_AUTH_ATTEMPTS}. */
    static final String TAG_OUTCOME = "outcome";

    /** Value of the {@link #TAG_OUTCOME} tag for a successful authentication attempt. */
    static final String OUTCOME_SUCCESS = "success";

    /** Value of the {@link #TAG_OUTCOME} tag for a failed authentication attempt. */
    static final String OUTCOME_FAILURE = "failure";

    /**
     * Base unit of the transaction-amount {@link DistributionSummary}. The amounts are a monetary
     * distribution, so the currency unit is recorded for self-describing metrics.
     */
    static final String CURRENCY_BASE_UNIT = "USD";

    /**
     * Builds and registers the pre-registered transaction-amount {@link DistributionSummary}
     * ({@value #METRIC_TRANSACTION_AMOUNT_TOTAL}).
     *
     * <p>Unlike the three counters, this meter has <em>no</em> runtime-varying tag, so it is created
     * exactly once as a bean (rather than lazily at the call site) and injected into the
     * {@link BusinessMetrics} facade. The base unit {@value #CURRENCY_BASE_UNIT} marks it as a
     * monetary distribution; the registry surfaces {@code _count}, {@code _sum}, and {@code _max}
     * series for it at {@code /actuator/prometheus}.</p>
     *
     * @param meterRegistry the auto-configured Micrometer registry (Spring Boot Actuator +
     *                       Prometheus registry); injected, never created here
     * @return the registered transaction-amount distribution summary bean
     */
    @Bean
    public DistributionSummary transactionAmountSummary(MeterRegistry meterRegistry) {
        return DistributionSummary.builder(METRIC_TRANSACTION_AMOUNT_TOTAL)
                .description("Monetary amount of posted transactions (count, total, max)")
                .baseUnit(CURRENCY_BASE_UNIT)
                .register(meterRegistry);
    }

    /**
     * Thin, intention-revealing facade over the four custom business meters.
     *
     * <p>Declared as a nested {@code static} {@link Component} so Spring component scanning detects it
     * (base package {@code com.cardemo}, decision <strong>D-006</strong>). Service and batch code
     * inject this facade and call semantic methods rather than constructing Micrometer meters inline,
     * keeping the metric names and tag keys &mdash; the frozen cross-file contract &mdash; in exactly
     * one place.</p>
     *
     * <p><strong>Counters are built per call</strong> via
     * {@code Counter.builder(name).tag(key, value).register(registry)}. This is the correct idiom for
     * the runtime-varying {@code job}/{@code reason}/{@code outcome} tags: the {@link MeterRegistry}
     * de-duplicates by meter identity (name + tags), so the same name+tag combination always resolves
     * to the same underlying counter. No hand-rolled cache is needed or kept. The non-tagged
     * transaction-amount {@link DistributionSummary} is injected pre-registered.</p>
     *
     * <p><strong>Thread-safety:</strong> the facade holds only the (thread-safe) registry and the
     * pre-registered summary and adds no mutable state, so a single shared instance safely serves all
     * request and batch threads. <strong>Robustness:</strong> as a telemetry component it must never
     * break a business flow, so tag values are normalized (a {@code null}/blank value becomes
     * {@value #UNKNOWN_TAG_VALUE}) and a {@code null} amount is ignored; this is telemetry-path safety
     * only and changes no business rule.</p>
     */
    @Component
    public static class BusinessMetrics {

        /** Placeholder substituted for a {@code null}/blank tag value so a meter id is always valid. */
        private static final String UNKNOWN_TAG_VALUE = "unknown";

        /**
         * Auto-configured Micrometer registry that the counters are registered on. Held as the meter
         * factory/cache; the registry de-duplicates by meter identity so per-call counter building is
         * cheap.
         */
        private final MeterRegistry meterRegistry;

        /** Pre-registered transaction-amount distribution summary (no runtime-varying tag). */
        private final DistributionSummary transactionAmountSummary;

        /**
         * Constructor injection (no field {@code @Autowired}) of the framework-provided registry and
         * the pre-registered transaction-amount summary bean.
         *
         * @param meterRegistry            the auto-configured Micrometer registry
         * @param transactionAmountSummary the {@link #transactionAmountSummary} bean
         */
        public BusinessMetrics(MeterRegistry meterRegistry, DistributionSummary transactionAmountSummary) {
            this.meterRegistry = meterRegistry;
            this.transactionAmountSummary = transactionAmountSummary;
        }

        /**
         * Increments {@code carddemo.batch.records.processed} for the given batch job &mdash; call once
         * per record a batch job successfully processes.
         *
         * @param jobName value of the {@code job} tag (e.g. {@code DailyTransactionPosting}); a
         *                {@code null}/blank value is normalized to {@value #UNKNOWN_TAG_VALUE}
         */
        public void recordBatchRecordProcessed(String jobName) {
            Counter.builder(METRIC_BATCH_RECORDS_PROCESSED)
                    .tag(TAG_JOB, normalizeTag(jobName))
                    .register(meterRegistry)
                    .increment();
        }

        /**
         * Increments {@code carddemo.batch.records.rejected} for the given rejection reason &mdash;
         * call once per record rejected during batch validation.
         *
         * @param reason value of the {@code reason} tag (e.g. {@code INVALID_CARD},
         *               {@code ACCOUNT_NOT_FOUND}); a {@code null}/blank value is normalized to
         *               {@value #UNKNOWN_TAG_VALUE}
         */
        public void recordBatchRecordRejected(String reason) {
            Counter.builder(METRIC_BATCH_RECORDS_REJECTED)
                    .tag(TAG_REASON, normalizeTag(reason))
                    .register(meterRegistry)
                    .increment();
        }

        /**
         * Increments {@code carddemo.auth.attempts} with {@code outcome=success} or
         * {@code outcome=failure} &mdash; call once per authentication attempt from the sign-on flow
         * translated from {@code COSGN00C}.
         *
         * @param success {@code true} for a successful authentication, {@code false} otherwise
         */
        public void recordAuthAttempt(boolean success) {
            Counter.builder(METRIC_AUTH_ATTEMPTS)
                    .tag(TAG_OUTCOME, success ? OUTCOME_SUCCESS : OUTCOME_FAILURE)
                    .register(meterRegistry)
                    .increment();
        }

        /**
         * Records a posted transaction amount onto the {@code carddemo.transaction.amount.total}
         * distribution summary.
         *
         * <p><strong>Decimal-precision boundary (AAP &sect;0.7.3):</strong> {@link DistributionSummary}
         * accepts only a {@code double}, so {@link BigDecimal#doubleValue()} is applied here &mdash;
         * the <em>sole</em> place in this file a monetary {@code BigDecimal} is narrowed to
         * {@code double}. This is a telemetry value only; it is never fed back into any financial
         * calculation, all of which remain {@code BigDecimal}.</p>
         *
         * @param amount the posted transaction amount; a {@code null} amount is ignored (no-op).
         *               Micrometer additionally ignores negative recordings by default
         */
        public void recordTransactionAmount(BigDecimal amount) {
            if (amount == null) {
                // Telemetry-path safety: never throw from a metrics call into business logic.
                return;
            }
            // BigDecimal -> double ONLY at this metrics record() boundary (see method Javadoc).
            transactionAmountSummary.record(amount.doubleValue());
        }

        /**
         * Normalizes a runtime tag value so the resulting meter id is always valid: Micrometer rejects
         * a {@code null} tag value, and a blank value yields an unhelpful empty series, so both are
         * mapped to {@value #UNKNOWN_TAG_VALUE}.
         *
         * @param value the raw tag value supplied by the caller
         * @return the value when non-blank, otherwise {@value #UNKNOWN_TAG_VALUE}
         */
        private static String normalizeTag(String value) {
            return (value == null || value.isBlank()) ? UNKNOWN_TAG_VALUE : value;
        }
    }
}
