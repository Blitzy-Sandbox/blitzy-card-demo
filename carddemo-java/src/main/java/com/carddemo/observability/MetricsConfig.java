package com.carddemo.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custom application-metrics configuration for the CardDemo Java migration.
 *
 * <p>This class is the single source of truth for the four custom business
 * metrics mandated by the Observability rule. They are exposed on
 * {@code /actuator/prometheus} (the Prometheus scrape endpoint) alongside the
 * standard framework meters. The Prometheus registry, the exporter, the common
 * {@code application} tag, and the HTTP latency histograms are all configured
 * elsewhere (Spring Boot Actuator auto-configuration, {@code application.yml},
 * and {@code config/ObservabilityConfig}); this class deliberately defines only
 * the custom meters and their canonical name and tag constants, so that meter
 * names never drift across the batch, authentication, and transaction
 * subsystems that record them.
 *
 * <p>All four metrics are Micrometer {@link Counter}s. Micrometer renders a
 * counter into Prometheus by lowercasing, replacing dots with underscores, and
 * appending a {@code _total} suffix; the resulting names below are the ones
 * consumed by the Grafana dashboard:
 * <ul>
 *   <li>{@code carddemo.batch.records.processed} is exposed as
 *       {@code carddemo_batch_records_processed_total} (untagged).</li>
 *   <li>{@code carddemo.batch.records.rejected} is exposed as
 *       {@code carddemo_batch_records_rejected_total} (tagged {@code reason}).</li>
 *   <li>{@code carddemo.auth.attempts} is exposed as
 *       {@code carddemo_auth_attempts_total} (tagged {@code outcome}).</li>
 *   <li>{@code carddemo.transaction.amount.total} is exposed as
 *       {@code carddemo_transaction_amount_total} (untagged).</li>
 * </ul>
 *
 * <p>The two untagged counters are pre-registered at startup by
 * {@link #carddemoCoreMetrics()} so their time series exist at value zero from
 * boot, which prevents "No data" Grafana panels before the first increment.
 * Spring Boot automatically applies every {@link MeterBinder} bean to the
 * active {@link MeterRegistry}. The two tagged counters are intentionally not
 * pre-registered: their label values are only known at record time, so they are
 * created lazily on first use to avoid inventing placeholder labels and
 * polluting cardinality.
 *
 * <p><b>Consumer contract.</b> Sibling components record metrics through the
 * static helpers below, which are idempotent by name and tags (repeated calls
 * return the same time series):
 * <ul>
 *   <li>Batch posting (processors and writers): on each successfully posted
 *       record call {@code MetricsConfig.recordsProcessed(registry).increment()};
 *       on each rejected record call
 *       {@code MetricsConfig.recordsRejected(registry, rejectReason).increment()},
 *       where {@code rejectReason} is a low-cardinality reject-code string
 *       (reject codes 100 to 109).</li>
 *   <li>Authentication: call
 *       {@code MetricsConfig.authAttempts(registry, outcome).increment()} with
 *       {@code outcome} set to {@link #OUTCOME_SUCCESS} or
 *       {@link #OUTCOME_FAILURE}.</li>
 *   <li>Transaction add, bill payment, and posting: call
 *       {@code MetricsConfig.transactionAmountTotal(registry).increment(amount.doubleValue())}.</li>
 * </ul>
 *
 * <p><b>Precision note.</b> Metric recording uses {@code double} because that is
 * Micrometer's native measurement type; this is observational telemetry only
 * and does not affect the authoritative {@link java.math.BigDecimal} financial
 * computations or persistence, so converting a monetary amount to {@code double}
 * solely to increment a metric is compliant with the zero-floating-point
 * substitution rule, which governs business-logic and persisted money fields
 * rather than telemetry.
 *
 * <p>Tag cardinality is intentionally bounded to {@code reason} and
 * {@code outcome}; user identifiers, card numbers, and amounts are never used as
 * tags or embedded in metric names.
 */
@Configuration(proxyBeanMethods = false)
public final class MetricsConfig {

    /** Counter name: total batch records successfully processed. Untagged. */
    public static final String BATCH_RECORDS_PROCESSED = "carddemo.batch.records.processed";

    /** Counter name: total batch records rejected, tagged by {@link #TAG_REASON}. */
    public static final String BATCH_RECORDS_REJECTED = "carddemo.batch.records.rejected";

    /** Counter name: authentication attempts, tagged by {@link #TAG_OUTCOME}. */
    public static final String AUTH_ATTEMPTS = "carddemo.auth.attempts";

    /** Counter name: monotonic running total of posted transaction amounts. Untagged. */
    public static final String TRANSACTION_AMOUNT_TOTAL = "carddemo.transaction.amount.total";

    /** Tag key for the rejection reason on {@link #BATCH_RECORDS_REJECTED}. */
    public static final String TAG_REASON = "reason";

    /** Tag key for the authentication outcome on {@link #AUTH_ATTEMPTS}. */
    public static final String TAG_OUTCOME = "outcome";

    /** Value of the {@link #TAG_OUTCOME} tag for a successful authentication. */
    public static final String OUTCOME_SUCCESS = "success";

    /** Value of the {@link #TAG_OUTCOME} tag for a failed authentication. */
    public static final String OUTCOME_FAILURE = "failure";

    /**
     * Pre-registers the two untagged core counters so their time series exist at
     * value zero from application startup. Spring Boot automatically binds every
     * {@link MeterBinder} bean to the active {@link MeterRegistry}.
     *
     * <p>The tagged counters ({@link #BATCH_RECORDS_REJECTED} and
     * {@link #AUTH_ATTEMPTS}) are deliberately not pre-registered here, because
     * their label values are only known at record time.
     *
     * @return a binder that registers the untagged {@link #BATCH_RECORDS_PROCESSED}
     *         and {@link #TRANSACTION_AMOUNT_TOTAL} counters
     */
    @Bean
    MeterBinder carddemoCoreMetrics() {
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
     * Returns the untagged counter of successfully processed batch records,
     * creating it on first use if necessary.
     *
     * @param registry the active meter registry; must not be {@code null}
     * @return the {@link #BATCH_RECORDS_PROCESSED} counter
     */
    public static Counter recordsProcessed(MeterRegistry registry) {
        return registry.counter(BATCH_RECORDS_PROCESSED);
    }

    /**
     * Returns the rejected-records counter for the given reason, creating it on
     * first use. The {@code reason} must be a low-cardinality value such as a
     * reject code (100 to 109).
     *
     * @param registry the active meter registry; must not be {@code null}
     * @param reason   the low-cardinality rejection reason or reject code
     * @return the {@link #BATCH_RECORDS_REJECTED} counter tagged with
     *         {@link #TAG_REASON} set to {@code reason}
     */
    public static Counter recordsRejected(MeterRegistry registry, String reason) {
        return registry.counter(BATCH_RECORDS_REJECTED, TAG_REASON, reason);
    }

    /**
     * Returns the authentication-attempts counter for the given outcome,
     * creating it on first use.
     *
     * @param registry the active meter registry; must not be {@code null}
     * @param outcome  the attempt outcome, typically {@link #OUTCOME_SUCCESS} or
     *                 {@link #OUTCOME_FAILURE}
     * @return the {@link #AUTH_ATTEMPTS} counter tagged with {@link #TAG_OUTCOME}
     *         set to {@code outcome}
     */
    public static Counter authAttempts(MeterRegistry registry, String outcome) {
        return registry.counter(AUTH_ATTEMPTS, TAG_OUTCOME, outcome);
    }

    /**
     * Returns the untagged monotonic counter of posted transaction amounts.
     * Callers increment by the transaction amount via
     * {@code increment(amount.doubleValue())} (see the class-level precision
     * note).
     *
     * @param registry the active meter registry; must not be {@code null}
     * @return the {@link #TRANSACTION_AMOUNT_TOTAL} counter
     */
    public static Counter transactionAmountTotal(MeterRegistry registry) {
        return registry.counter(TRANSACTION_AMOUNT_TOTAL);
    }
}
