package com.carddemo.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central definition of the CardDemo application's custom Micrometer meters.
 *
 * <p>This configuration is the single source of truth for the four custom
 * application metrics mandated by the Observability requirement. Sibling
 * components reference the canonical name and tag constants declared here so
 * that meter names and tag keys never drift across the codebase.</p>
 *
 * <h2>Metrics</h2>
 * <p>All four meters are <strong>counters</strong> (monotonically increasing).
 * Micrometer renders the dotted names into the Prometheus exposition form by
 * replacing dots with underscores and appending {@code _total}:</p>
 * <ul>
 *   <li>{@value #BATCH_RECORDS_PROCESSED} &rarr;
 *       {@code carddemo_batch_records_processed_total} (untagged) &mdash; batch
 *       records successfully posted.</li>
 *   <li>{@value #BATCH_RECORDS_REJECTED} &rarr;
 *       {@code carddemo_batch_records_rejected_total} (tagged
 *       {@value #TAG_REASON}) &mdash; batch records rejected, by reject
 *       reason.</li>
 *   <li>{@value #AUTH_ATTEMPTS} &rarr;
 *       {@code carddemo_auth_attempts_total} (tagged {@value #TAG_OUTCOME})
 *       &mdash; authentication attempts, by outcome.</li>
 *   <li>{@value #TRANSACTION_AMOUNT_TOTAL} &rarr;
 *       {@code carddemo_transaction_amount_total} (untagged) &mdash; running
 *       total of posted transaction amounts.</li>
 * </ul>
 *
 * <h2>Consumer contract</h2>
 * <p>Sibling services obtain meters through the static helper methods, which
 * delegate to Micrometer's idempotent {@code registry.counter(...)} lookups
 * (the same time series is returned on repeated calls for a given name and tag
 * set):</p>
 * <ul>
 *   <li>Batch processing &mdash; per successfully posted record call
 *       {@code MetricsConfig.recordsProcessed(registry).increment()}; per
 *       rejected record call
 *       {@code MetricsConfig.recordsRejected(registry, rejectReason).increment()},
 *       where {@code rejectReason} is a low-cardinality reject-code string.</li>
 *   <li>Authentication &mdash; call
 *       {@code MetricsConfig.authAttempts(registry, outcome).increment()} with
 *       {@link #OUTCOME_SUCCESS} or {@link #OUTCOME_FAILURE}.</li>
 *   <li>Transaction add, bill payment, and posting &mdash; call
 *       {@code MetricsConfig.transactionAmountTotal(registry).increment(amount.doubleValue())}.</li>
 * </ul>
 *
 * <h2>Precision note</h2>
 * <p>Metric recording uses {@code double} because that is Micrometer's native
 * measurement type; this is observational telemetry only and does
 * <strong>not</strong> affect the authoritative {@code BigDecimal} financial
 * computations or persistence. The zero-floating-point-substitution rule
 * governs business-logic and persisted money fields, not telemetry, so
 * converting an amount to {@code double} solely to increment a metric is
 * compliant.</p>
 *
 * <p>Registry creation, the Prometheus exporter, and the common
 * {@code application} tag are owned by Spring Boot Actuator auto-configuration
 * and the externalized application configuration; this class only defines the
 * custom meters and their canonical constants.</p>
 */
@Configuration(proxyBeanMethods = false)
public final class MetricsConfig {

    /** Counter name: batch records successfully processed (untagged). */
    public static final String BATCH_RECORDS_PROCESSED = "carddemo.batch.records.processed";

    /** Counter name: batch records rejected, tagged by {@link #TAG_REASON}. */
    public static final String BATCH_RECORDS_REJECTED = "carddemo.batch.records.rejected";

    /** Counter name: authentication attempts, tagged by {@link #TAG_OUTCOME}. */
    public static final String AUTH_ATTEMPTS = "carddemo.auth.attempts";

    /** Counter name: running total of posted transaction amounts (untagged). */
    public static final String TRANSACTION_AMOUNT_TOTAL = "carddemo.transaction.amount.total";

    /** Tag key identifying the reject reason on {@link #BATCH_RECORDS_REJECTED}. */
    public static final String TAG_REASON = "reason";

    /** Tag key identifying the outcome on {@link #AUTH_ATTEMPTS}. */
    public static final String TAG_OUTCOME = "outcome";

    /** Value of the {@link #TAG_OUTCOME} tag for a successful authentication. */
    public static final String OUTCOME_SUCCESS = "success";

    /** Value of the {@link #TAG_OUTCOME} tag for a failed authentication. */
    public static final String OUTCOME_FAILURE = "failure";

    /**
     * Creates the configuration. Spring instantiates this bean during context
     * startup; it holds no state of its own.
     */
    public MetricsConfig() {
        // No initialization required; meters are registered by the bean below.
    }

    /**
     * Pre-registers the two untagged counters at startup so their time series
     * exist at value zero before the first increment, preventing
     * dashboard panels from showing "No data" until the first event. Spring
     * Boot automatically applies every {@link MeterBinder} bean to the
     * {@link MeterRegistry}.
     *
     * <p>The tagged counters ({@link #BATCH_RECORDS_REJECTED} and
     * {@link #AUTH_ATTEMPTS}) are intentionally not pre-registered: their tag
     * values are only known at use time, and inventing placeholder values would
     * pollute label cardinality.</p>
     *
     * @return a binder that registers the untagged CardDemo core counters
     */
    @Bean
    public MeterBinder carddemoCoreMetrics() {
        return registry -> {
            Counter.builder(BATCH_RECORDS_PROCESSED)
                    .description("Total batch records successfully processed by CardDemo batch jobs")
                    .register(registry);
            Counter.builder(TRANSACTION_AMOUNT_TOTAL)
                    .description("Monotonic running total of posted transaction amounts")
                    .register(registry);
        };
    }

    /**
     * Returns the untagged batch-records-processed counter.
     *
     * @param registry the meter registry to resolve the counter against
     * @return the {@link #BATCH_RECORDS_PROCESSED} counter
     */
    public static Counter recordsProcessed(final MeterRegistry registry) {
        return registry.counter(BATCH_RECORDS_PROCESSED);
    }

    /**
     * Returns the batch-records-rejected counter tagged with the given reason.
     *
     * @param registry the meter registry to resolve the counter against
     * @param reason   low-cardinality reject reason (for example a reject code)
     * @return the {@link #BATCH_RECORDS_REJECTED} counter for {@code reason}
     */
    public static Counter recordsRejected(final MeterRegistry registry, final String reason) {
        return registry.counter(BATCH_RECORDS_REJECTED, TAG_REASON, reason);
    }

    /**
     * Returns the authentication-attempts counter tagged with the given outcome.
     *
     * @param registry the meter registry to resolve the counter against
     * @param outcome  the attempt outcome, typically {@link #OUTCOME_SUCCESS}
     *                 or {@link #OUTCOME_FAILURE}
     * @return the {@link #AUTH_ATTEMPTS} counter for {@code outcome}
     */
    public static Counter authAttempts(final MeterRegistry registry, final String outcome) {
        return registry.counter(AUTH_ATTEMPTS, TAG_OUTCOME, outcome);
    }

    /**
     * Returns the untagged running-total counter for posted transaction amounts.
     *
     * @param registry the meter registry to resolve the counter against
     * @return the {@link #TRANSACTION_AMOUNT_TOTAL} counter
     */
    public static Counter transactionAmountTotal(final MeterRegistry registry) {
        return registry.counter(TRANSACTION_AMOUNT_TOTAL);
    }
}
