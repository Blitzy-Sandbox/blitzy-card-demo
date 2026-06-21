package com.carddemo.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.math.BigDecimal;
import java.util.concurrent.atomic.DoubleAdder;
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
 * <p>Three meters are <strong>counters</strong> (monotonically increasing) and
 * one is a <strong>gauge</strong> (the signed running total). Micrometer renders
 * the dotted names into the Prometheus exposition form by replacing dots with
 * underscores; counters additionally carry the {@code _total} suffix:</p>
 * <ul>
 *   <li>{@value #BATCH_RECORDS_PROCESSED} &rarr;
 *       {@code carddemo_batch_records_processed_total} (counter, untagged)
 *       &mdash; batch records successfully posted.</li>
 *   <li>{@value #BATCH_RECORDS_REJECTED} &rarr;
 *       {@code carddemo_batch_records_rejected_total} (counter, tagged
 *       {@value #TAG_REASON}) &mdash; batch records rejected, by reject
 *       reason.</li>
 *   <li>{@value #AUTH_ATTEMPTS} &rarr;
 *       {@code carddemo_auth_attempts_total} (counter, tagged
 *       {@value #TAG_OUTCOME}) &mdash; authentication attempts, by outcome.</li>
 *   <li>{@value #TRANSACTION_AMOUNT_TOTAL} &rarr;
 *       {@code carddemo_transaction_amount} (<strong>gauge</strong>, untagged)
 *       &mdash; signed running total of posted transaction amounts. Note the
 *       exposed Prometheus series has <strong>no</strong> {@code _total} suffix:
 *       although the meter id ends in {@code .total} (the canonical
 *       Observability metric name mandated by the AAP), Micrometer reserves the
 *       {@code _total} suffix for counters and strips it from gauges, so this
 *       gauge is exposed as {@code carddemo_transaction_amount}. It is a gauge
 *       rather than a counter because CardDemo transaction amounts can be
 *       negative (credits and returns); a Micrometer counter rejects negative
 *       increments, whereas a gauge backed by a
 *       {@link java.util.concurrent.atomic.DoubleAdder} accumulates signed
 *       amounts safely and never throws on valid business data. Dashboards and
 *       Prometheus queries must therefore reference
 *       {@code carddemo_transaction_amount} (without {@code _total}).</li>
 * </ul>
 *
 * <h2>Consumer contract</h2>
 * <p>Sibling services record the three counters through the static helper
 * methods, which delegate to Micrometer's idempotent meter registration (the
 * same time series is returned on repeated calls for a given name and tag set;
 * the rejected-records counter is registered through a {@code Counter.builder}
 * so it always carries a HELP description), and record the signed
 * transaction-amount total through the {@code addTransactionAmount(BigDecimal)}
 * instance method on the injected {@code MetricsConfig} bean:</p>
 * <ul>
 *   <li>Batch processing &mdash; per successfully posted record call
 *       {@code MetricsConfig.recordsProcessed(registry).increment()}; per
 *       rejected record call
 *       {@code MetricsConfig.recordsRejected(registry, rejectReason).increment()},
 *       where {@code rejectReason} is a low-cardinality reject-code string.</li>
 *   <li>Authentication &mdash; call
 *       {@code MetricsConfig.authAttempts(registry, outcome).increment()} with
 *       {@link #OUTCOME_SUCCESS} or {@link #OUTCOME_FAILURE}.</li>
 *   <li>Transaction add, bill payment, and posting &mdash; inject the
 *       {@code MetricsConfig} bean and call
 *       {@code metricsConfig.addTransactionAmount(amount)} with the
 *       {@link java.math.BigDecimal} amount (which may be negative for credits
 *       or returns). Unlike a counter increment, this never throws on a
 *       negative value.</li>
 * </ul>
 *
 * <h2>Precision note</h2>
 * <p>Metric recording uses {@code double} because that is Micrometer's native
 * measurement type; this is observational telemetry only and does
 * <strong>not</strong> affect the authoritative {@code BigDecimal} financial
 * computations or persistence. The zero-floating-point-substitution rule
 * governs business-logic and persisted money fields, not telemetry, so
 * converting an amount to {@code double} solely to record a metric is
 * compliant. Because the transaction-amount gauge is backed by a
 * {@link java.util.concurrent.atomic.DoubleAdder}, negative amounts (credits
 * and returns) are accumulated correctly and recording an amount never
 * throws.</p>
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

    /** Gauge name: signed running total of posted transaction amounts (untagged). */
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
     * Backing accumulator for the {@link #TRANSACTION_AMOUNT_TOTAL} gauge. A
     * {@link DoubleAdder} accepts signed contributions (positive debits and
     * negative credits/returns) under concurrent access and never throws, which
     * is why the running total is modelled as a gauge over this accumulator
     * rather than as a monotonic counter. One instance exists per Spring
     * application context (this bean is a singleton), so the running total is
     * shared application-wide yet isolated between independent test contexts.
     */
    private final DoubleAdder transactionAmountAccumulator = new DoubleAdder();

    /**
     * Creates the configuration. Spring instantiates this bean during context
     * startup; it holds only the transaction-amount accumulator that backs the
     * {@link #TRANSACTION_AMOUNT_TOTAL} gauge.
     */
    public MetricsConfig() {
        // No initialization required; meters are registered by the bean below.
    }

    /**
     * Pre-registers the untagged batch-records-processed counter and the
     * transaction-amount gauge at startup so their time series exist (at value
     * zero) before the first event, preventing dashboard panels from showing
     * "No data" until the first record. Spring Boot automatically applies every
     * {@link MeterBinder} bean to the {@link MeterRegistry}.
     *
     * <p>The tagged counters ({@link #BATCH_RECORDS_REJECTED} and
     * {@link #AUTH_ATTEMPTS}) are intentionally not pre-registered: their tag
     * values are only known at use time, and inventing placeholder values would
     * pollute label cardinality.</p>
     *
     * @return a binder that registers the untagged CardDemo core meters
     */
    @Bean
    public MeterBinder carddemoCoreMetrics() {
        return registry -> {
            Counter.builder(BATCH_RECORDS_PROCESSED)
                    .description("Total batch records successfully processed by CardDemo batch jobs")
                    .register(registry);
            Gauge.builder(TRANSACTION_AMOUNT_TOTAL, transactionAmountAccumulator, DoubleAdder::sum)
                    .description("Signed running total of posted transaction amounts "
                            + "(supports negative credits/returns)")
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
     * <p>The counter is registered through a {@link Counter#builder} so that it
     * always carries a HELP description in the Prometheus exposition, regardless
     * of which batch job (posting or reporting) first touches the metric in a
     * given JVM. Registration is idempotent: repeated calls for the same name
     * and {@value #TAG_REASON} tag value return the same time series.</p>
     *
     * @param registry the meter registry to resolve the counter against
     * @param reason   low-cardinality reject reason (for example a numeric
     *                 reject code such as {@code "102"})
     * @return the {@link #BATCH_RECORDS_REJECTED} counter for {@code reason}
     */
    public static Counter recordsRejected(final MeterRegistry registry, final String reason) {
        return Counter.builder(BATCH_RECORDS_REJECTED)
                .tag(TAG_REASON, reason)
                .description("Total batch records rejected, tagged by reject reason")
                .register(registry);
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
     * Adds a posted transaction amount to the signed running total reported by
     * the {@link #TRANSACTION_AMOUNT_TOTAL} gauge.
     *
     * <p>The amount may be positive (a debit/purchase) or negative (a credit or
     * return); both are accumulated correctly. This is an instance method
     * because the running total is held in the bean's {@link DoubleAdder}
     * accumulator, so callers inject the {@code MetricsConfig} bean to record an
     * amount. Unlike a Micrometer counter, it never rejects or throws on a
     * negative value, so valid business data can never break telemetry
     * recording. A {@code null} amount is ignored, because telemetry must never
     * disrupt the business flow.</p>
     *
     * @param amount the transaction amount to add to the running total; may be
     *               negative, and {@code null} is treated as a no-op
     */
    public void addTransactionAmount(final BigDecimal amount) {
        if (amount != null) {
            transactionAmountAccumulator.add(amount.doubleValue());
        }
    }
}
