/*
 * MetricsConfig.java — Custom Business Metrics Registration
 *
 * Registers custom Micrometer business metrics for the CardDemo application,
 * providing operational visibility that the original COBOL system lacked entirely.
 * The COBOL source (aws-samples/carddemo commit 27d6c6f) has zero observability
 * infrastructure; this class is greenfield per AAP §0.7.1.
 *
 * Metrics registered:
 *   - carddemo.batch.records.processed  — Counter per batch job name
 *   - carddemo.batch.records.rejected   — Counter per job + reason code
 *   - carddemo.auth.attempts            — Counter per success/failure outcome
 *   - carddemo.transaction.amount.total — DistributionSummary with p50/p95/p99
 *
 * COBOL program mappings:
 *   CBTRN02C.cbl → batch record counters (WS-TRANSACTION-COUNT, WS-REJECT-COUNT,
 *                   WS-VALIDATION-FAIL-REASON codes 100-103, 109)
 *   COSGN00C.cbl → authentication counters (PROCESS-ENTER-KEY / READ-USER-SEC-FILE
 *                   success vs failure paths)
 *   COTRN02C.cbl → transaction amount distribution (TRAN-AMT PIC S9(7)V99)
 *
 * All metrics are automatically exposed via the /actuator/prometheus endpoint
 * configured in application.yml (management.endpoints.web.exposure.include:
 * prometheus) and tagged with application=carddemo (management.metrics.tags.
 * application: carddemo). The Grafana dashboard (docs/grafana-dashboard.json)
 * queries these metrics by their exact names.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.cardemo.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Registers and provides access to custom business metrics for the CardDemo
 * application using Micrometer's {@link MeterRegistry}.
 *
 * <p>This configuration class is constructor-injected with the Spring Boot
 * Actuator auto-configured {@link MeterRegistry} (backed by the Prometheus
 * registry via {@code micrometer-registry-prometheus}). It pre-registers the
 * transaction amount distribution summary in the constructor and provides
 * thread-safe helper methods for dynamically-tagged counters.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>All public methods are thread-safe. {@link Counter#builder(String)} with
 * {@code .register(MeterRegistry)} is idempotent — invoking it multiple times
 * with the same name and tags returns the same counter instance from the
 * registry's internal cache. The {@link DistributionSummary} is pre-registered
 * once and stored as a final field.</p>
 *
 * <h3>Usage by Service Classes</h3>
 * <ul>
 *   <li>{@code AuthenticationService} calls {@link #recordAuthAttempt(boolean)}</li>
 *   <li>{@code TransactionPostingProcessor} calls {@link #recordBatchProcessed(String)}
 *       and {@link #recordBatchRejected(String, String)}</li>
 *   <li>{@code TransactionAddService} calls {@link #recordTransactionAmount(double)}</li>
 * </ul>
 *
 * @see io.micrometer.core.instrument.MeterRegistry
 * @see io.micrometer.core.instrument.Counter
 * @see io.micrometer.core.instrument.DistributionSummary
 */
@Configuration
public class MetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);

    // -------------------------------------------------------------------------
    // Metric name constants — must match AAP §0.7.1 exactly
    // Referenced in docs/grafana-dashboard.json for Prometheus/Grafana queries
    // -------------------------------------------------------------------------

    /**
     * Counter metric name for batch records successfully processed.
     * Tagged with {@code job} dimension identifying the batch job name.
     * Maps to COBOL CBTRN02C.cbl WS-TRANSACTION-COUNT.
     */
    static final String METRIC_BATCH_PROCESSED = "carddemo.batch.records.processed";

    /**
     * Counter metric name for batch records rejected during validation.
     * Tagged with {@code job} and {@code reason} dimensions.
     * Maps to COBOL CBTRN02C.cbl WS-REJECT-COUNT and
     * WS-VALIDATION-FAIL-REASON codes:
     * <ul>
     *   <li>100 — INVALID CARD NUMBER FOUND</li>
     *   <li>101 — ACCOUNT RECORD NOT FOUND</li>
     *   <li>102 — OVERLIMIT TRANSACTION</li>
     *   <li>103 — TRANSACTION RECEIVED AFTER ACCT EXPIRATION</li>
     *   <li>109 — OTHER VALIDATION FAILURES</li>
     * </ul>
     */
    static final String METRIC_BATCH_REJECTED = "carddemo.batch.records.rejected";

    /**
     * Counter metric name for authentication attempts.
     * Tagged with {@code outcome} dimension ({@code success} or {@code failure}).
     * Maps to COBOL COSGN00C.cbl PROCESS-ENTER-KEY / READ-USER-SEC-FILE
     * paragraphs where authentication result is determined.
     */
    static final String METRIC_AUTH_ATTEMPTS = "carddemo.auth.attempts";

    /**
     * Distribution summary metric name for transaction amounts in USD.
     * Records individual transaction amounts with p50/p95/p99 percentile
     * histogram buckets for Prometheus/Grafana visualization.
     * Maps to COBOL COTRN02C.cbl TRAN-AMT (PIC S9(7)V99) and
     * CBTRN02C.cbl DALYTRAN-AMT (PIC S9(7)V99).
     */
    static final String METRIC_TRANSACTION_AMOUNT = "carddemo.transaction.amount.total";

    // -------------------------------------------------------------------------
    // Tag name constants — lowercase, following Micrometer naming conventions
    // -------------------------------------------------------------------------

    private static final String TAG_JOB = "job";
    private static final String TAG_REASON = "reason";
    private static final String TAG_OUTCOME = "outcome";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILURE = "failure";

    // -------------------------------------------------------------------------
    // Instance fields
    // -------------------------------------------------------------------------

    private final MeterRegistry meterRegistry;
    private final DistributionSummary transactionAmountSummary;

    /**
     * Constructs a new {@code MetricsConfig} with the auto-configured
     * {@link MeterRegistry} injected by Spring Boot Actuator.
     *
     * <p>Pre-registers the transaction amount distribution summary with
     * percentile histogram configuration for Prometheus scraping. Counter
     * metrics are registered lazily on first use via their respective
     * helper methods, since they require dynamic tag values (job name,
     * reason code, outcome).</p>
     *
     * @param meterRegistry the Micrometer meter registry auto-configured by
     *                      Spring Boot Actuator with the Prometheus backend
     */
    public MetricsConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Pre-register the transaction amount distribution summary.
        // This metric has no dynamic tags, so it can be registered once.
        // publishPercentiles(0.5, 0.95, 0.99) generates client-side quantile
        // approximations; publishPercentileHistogram() additionally generates
        // histogram buckets for server-side quantile computation in Prometheus.
        this.transactionAmountSummary = DistributionSummary
                .builder(METRIC_TRANSACTION_AMOUNT)
                .baseUnit("USD")
                .description("Distribution of transaction amounts in USD. "
                        + "Maps to COBOL PIC S9(7)V99 fields TRAN-AMT and DALYTRAN-AMT.")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        log.info("Registered custom CardDemo business metrics: "
                + "batch.records.processed (counter), "
                + "batch.records.rejected (counter), "
                + "auth.attempts (counter), "
                + "transaction.amount.total (distribution summary with p50/p95/p99)");
    }

    // -------------------------------------------------------------------------
    // Public helper methods — called by service and batch processor classes
    // -------------------------------------------------------------------------

    /**
     * Records a successfully processed batch record.
     *
     * <p>Increments the {@value #METRIC_BATCH_PROCESSED} counter tagged with
     * the specified job name. This maps to the COBOL CBTRN02C.cbl pattern
     * where {@code WS-TRANSACTION-COUNT} is incremented for each record
     * that passes validation and is posted to the transaction file.</p>
     *
     * <p>Counter.builder().register() is idempotent — the same counter instance
     * is returned for repeated calls with identical name and tags.</p>
     *
     * @param jobName the name of the batch job (e.g., "DailyTransactionPosting",
     *                "InterestCalculation", "CombineTransactions",
     *                "StatementGeneration", "TransactionReport")
     */
    public void recordBatchProcessed(String jobName) {
        Counter.builder(METRIC_BATCH_PROCESSED)
                .tag(TAG_JOB, sanitizeTag(jobName))
                .description("Number of batch records successfully processed. "
                        + "Maps to COBOL CBTRN02C WS-TRANSACTION-COUNT.")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Records a rejected batch record with the specified reason code.
     *
     * <p>Increments the {@value #METRIC_BATCH_REJECTED} counter tagged with
     * the job name and rejection reason code. This maps to the COBOL
     * CBTRN02C.cbl 4-stage validation cascade where
     * {@code WS-VALIDATION-FAIL-REASON} captures the rejection reason:</p>
     * <ul>
     *   <li>{@code 100} — INVALID CARD NUMBER FOUND (1500-A-LOOKUP-XREF)</li>
     *   <li>{@code 101} — ACCOUNT RECORD NOT FOUND (1500-B-LOOKUP-ACCT)</li>
     *   <li>{@code 102} — OVERLIMIT TRANSACTION (1500-B-LOOKUP-ACCT)</li>
     *   <li>{@code 103} — TRANSACTION RECEIVED AFTER ACCT EXPIRATION</li>
     *   <li>{@code 109} — OTHER VALIDATION FAILURES</li>
     * </ul>
     *
     * @param jobName    the name of the batch job
     * @param reasonCode the rejection reason code (e.g., "100", "101", "102",
     *                   "103", "109")
     */
    public void recordBatchRejected(String jobName, String reasonCode) {
        Counter.builder(METRIC_BATCH_REJECTED)
                .tag(TAG_JOB, sanitizeTag(jobName))
                .tag(TAG_REASON, sanitizeTag(reasonCode))
                .description("Number of batch records rejected during validation. "
                        + "Maps to COBOL CBTRN02C WS-REJECT-COUNT.")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Records an authentication attempt with the specified outcome.
     *
     * <p>Increments the {@value #METRIC_AUTH_ATTEMPTS} counter tagged with
     * {@code outcome=success} or {@code outcome=failure}. This maps to the
     * COBOL COSGN00C.cbl authentication flow:</p>
     * <ul>
     *   <li>Success: User found in USRSEC file and password matches
     *       (READ-USER-SEC-FILE paragraph, RESP=0, password comparison)</li>
     *   <li>Failure: User not found (RESP=13) or password mismatch</li>
     * </ul>
     *
     * @param success {@code true} if authentication succeeded, {@code false}
     *                if it failed
     */
    public void recordAuthAttempt(boolean success) {
        String outcome = success ? OUTCOME_SUCCESS : OUTCOME_FAILURE;
        Counter.builder(METRIC_AUTH_ATTEMPTS)
                .tag(TAG_OUTCOME, outcome)
                .description("Number of authentication attempts. "
                        + "Maps to COBOL COSGN00C PROCESS-ENTER-KEY.")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Records a transaction amount observation in the distribution summary.
     *
     * <p>The {@code double} parameter type is a Micrometer API requirement for
     * recording observations. Business logic must still use
     * {@link java.math.BigDecimal} for all financial calculations (AAP §0.8.2
     * zero floating-point substitution rule). The {@code BigDecimal} to
     * {@code double} conversion happens only at this metrics boundary — for
     * example: {@code metricsConfig.recordTransactionAmount(amount.doubleValue())}.</p>
     *
     * <p>This maps to the COBOL transaction amount fields:</p>
     * <ul>
     *   <li>COTRN02C.cbl — online transaction add (TRAN-AMT PIC S9(7)V99)</li>
     *   <li>CBTRN02C.cbl — batch daily posting (DALYTRAN-AMT PIC S9(7)V99)</li>
     * </ul>
     *
     * @param amount the transaction amount in USD as a double value.
     *               Must be converted from BigDecimal at the call site.
     */
    public void recordTransactionAmount(double amount) {
        transactionAmountSummary.record(amount);
    }

    // -------------------------------------------------------------------------
    // Internal utility methods
    // -------------------------------------------------------------------------

    /**
     * Sanitizes a tag value for safe use in Micrometer metric tags.
     * Replaces null or blank values with "unknown" to prevent metric
     * registration failures.
     *
     * @param value the raw tag value
     * @return the sanitized tag value, never null or blank
     */
    private static String sanitizeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value;
    }
}
