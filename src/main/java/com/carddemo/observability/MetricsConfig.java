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
package com.carddemo.observability;

import com.carddemo.enums.RejectReasonCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registers the custom CardDemo application metrics against the Spring Boot
 * auto-configured {@link MeterRegistry}.
 *
 * <p>The registered meters and the Prometheus series they expose on
 * {@code /actuator/prometheus} are:</p>
 * <ul>
 *   <li>{@code carddemo.batch.records.processed} &mdash; {@link Counter},
 *       series {@code carddemo_batch_records_processed_total}.</li>
 *   <li>{@code carddemo.batch.records.rejected} &mdash; one {@link Counter} per
 *       reject reason, each tagged {@code reason=<code>} (the
 *       {@link RejectReasonCode} numeric code 100&ndash;109), series
 *       {@code carddemo_batch_records_rejected_total{reason="..."}} so the
 *       dashboard query {@code sum by (reason) (...)} resolves.</li>
 *   <li>{@code carddemo.auth.attempts} &mdash; one {@link Counter} per sign-on
 *       outcome, each tagged {@code result=success} or {@code result=failure},
 *       exposing the two series
 *       {@code carddemo_auth_attempts_total{result="success"}} and
 *       {@code {result="failure"}} so the dashboard query
 *       {@code sum by (result) (...)} resolves.</li>
 *   <li>{@code carddemo.transaction.amount.total} &mdash;
 *       {@link DistributionSummary}. Micrometer strips the reserved
 *       {@code _total} suffix from non-counter meters, so the exposed series are
 *       {@code carddemo_transaction_amount_sum} / {@code _count} /
 *       {@code _max}, which the dashboard amount panel reads.</li>
 * </ul>
 *
 * <p>Each meter is registered eagerly so it is visible (counters at {@code 0})
 * from startup, before the first business event is recorded. Micrometer exposes
 * the dotted meter names in snake_case; counters gain a {@code _total} suffix
 * and a distribution summary gains {@code _sum}/{@code _count}/{@code _max}. A
 * base unit is deliberately omitted from the processed counter, the rejected
 * counter, and the amount summary because the Prometheus naming convention would
 * otherwise append the unit token to the series name (for example
 * {@code carddemo_batch_records_processed_records_total}) and break the dashboard
 * queries. The auth counters keep the {@code attempts} base unit only because the
 * meter name already ends in {@code attempts}, so no token is inserted.</p>
 *
 * <p>The metric-name constants are the single source of truth for every call
 * site. The {@code @Bean} method names are the bean names used for injection
 * (for example {@code @Qualifier("batchRecordsProcessedCounter")}); the
 * reject-reason counters are injected as a single
 * {@code Map<RejectReasonCode, Counter>}.</p>
 */
@Configuration
public class MetricsConfig {

    /** Daily-transaction records successfully posted by the batch pipeline. */
    public static final String BATCH_RECORDS_PROCESSED = "carddemo.batch.records.processed";

    /** Daily-transaction records rejected by the posting validation cascade. */
    public static final String BATCH_RECORDS_REJECTED = "carddemo.batch.records.rejected";

    /** Authentication (sign-on) attempts. */
    public static final String AUTH_ATTEMPTS = "carddemo.auth.attempts";

    /**
     * Tag key carrying the sign-on outcome on the {@code carddemo.auth.attempts}
     * meter. The "Auth Attempts (success vs failure)" dashboard panel aggregates
     * with {@code sum by (result) (...)}, so the key MUST be {@code result}.
     */
    public static final String AUTH_RESULT_TAG = "result";

    /** {@link #AUTH_RESULT_TAG} value for a successful sign-on. */
    public static final String AUTH_RESULT_SUCCESS = "success";

    /** {@link #AUTH_RESULT_TAG} value for a failed sign-on (validation, no such user, wrong password, or read error). */
    public static final String AUTH_RESULT_FAILURE = "failure";

    /** Cumulative monetary amount of posted transactions (observability only). */
    public static final String TRANSACTION_AMOUNT_TOTAL = "carddemo.transaction.amount.total";

    @Bean
    Counter batchRecordsProcessedCounter(MeterRegistry registry) {
        // No base unit is set so the Prometheus series name is exactly
        // carddemo_batch_records_processed_total. A base unit ("records") would be
        // appended by the Prometheus naming convention (the name does not already
        // end in "records"), yielding carddemo_batch_records_processed_records_total
        // and breaking the dashboard's primary batch-throughput panel, which queries
        // sum(carddemo_batch_records_processed_total). This mirrors the rejected
        // counter and the transaction distribution summary, which also omit base units.
        return Counter.builder(BATCH_RECORDS_PROCESSED)
                .description("Total daily-transaction records successfully posted by the batch pipeline")
                .register(registry);
    }

    /**
     * Registers one rejected-record counter per {@link RejectReasonCode} (the
     * {@link RejectReasonCode#NONE} "no rejection" sentinel is excluded), each
     * carrying a {@code reason} tag set to the numeric reject code
     * (100&ndash;109). All counters share the {@code carddemo.batch.records.rejected}
     * name and therefore expose a single tagged Prometheus series
     * {@code carddemo_batch_records_rejected_total{reason="..."}}, which the
     * "Batch Rejects by Reason" dashboard panel aggregates with
     * {@code sum by (reason) (...)}. No base unit is set so the series name is
     * exactly {@code ..._rejected_total} (a base unit would be appended to the
     * name by the Prometheus naming convention).
     *
     * @param registry the Spring Boot auto-configured meter registry
     * @return an immutable lookup of reject reason to its tagged counter, for
     *         constructor injection by the posting batch as
     *         {@code Map<RejectReasonCode, Counter>}
     */
    @Bean
    Map<RejectReasonCode, Counter> batchRecordsRejectedCounters(MeterRegistry registry) {
        Map<RejectReasonCode, Counter> counters = new EnumMap<>(RejectReasonCode.class);
        for (RejectReasonCode reason : RejectReasonCode.values()) {
            if (reason == RejectReasonCode.NONE) {
                continue;
            }
            Counter counter = Counter.builder(BATCH_RECORDS_REJECTED)
                    .description("Daily-transaction records rejected by the posting validation cascade, by reject reason")
                    .tag("reason", String.valueOf(reason.getCode()))
                    .register(registry);
            counters.put(reason, counter);
        }
        return Map.copyOf(counters);
    }

    /**
     * Registers the successful-sign-on counter on the
     * {@code carddemo.auth.attempts} meter, tagged {@code result=success}.
     *
     * <p>The auth meter is split into one counter per outcome rather than a single
     * untagged series so the "Auth Attempts (success vs failure)" dashboard panel
     * can break attempts down with {@code sum by (result) (...)}. Both outcome
     * counters share the {@code carddemo.auth.attempts} name and a consistent
     * {@code result} tag key, exposing the two Prometheus series
     * {@code carddemo_auth_attempts_total{result="success"}} and
     * {@code {result="failure"}}; a base unit of {@code attempts} is retained (the
     * name already ends in {@code attempts}, so no unit token is inserted and the
     * series name stays {@code carddemo_auth_attempts_total}). Both are registered
     * eagerly so each series is visible at {@code 0} from startup.</p>
     *
     * @param registry the Spring Boot auto-configured meter registry
     * @return the successful-sign-on counter, injected as
     *         {@code @Qualifier("authAttemptsSuccessCounter")}
     */
    @Bean
    Counter authAttemptsSuccessCounter(MeterRegistry registry) {
        return Counter.builder(AUTH_ATTEMPTS)
                .description("Authentication (sign-on) attempts, by result")
                .baseUnit("attempts")
                .tag(AUTH_RESULT_TAG, AUTH_RESULT_SUCCESS)
                .register(registry);
    }

    /**
     * Registers the failed-sign-on counter on the {@code carddemo.auth.attempts}
     * meter, tagged {@code result=failure}. See
     * {@link #authAttemptsSuccessCounter(MeterRegistry)} for the rationale behind
     * the outcome split and the resulting Prometheus series.
     *
     * @param registry the Spring Boot auto-configured meter registry
     * @return the failed-sign-on counter, injected as
     *         {@code @Qualifier("authAttemptsFailureCounter")}
     */
    @Bean
    Counter authAttemptsFailureCounter(MeterRegistry registry) {
        return Counter.builder(AUTH_ATTEMPTS)
                .description("Authentication (sign-on) attempts, by result")
                .baseUnit("attempts")
                .tag(AUTH_RESULT_TAG, AUTH_RESULT_FAILURE)
                .register(registry);
    }

    /**
     * Registers the posted-transaction monetary amount as a
     * {@link DistributionSummary} rather than a counter. The meter is named
     * {@code carddemo.transaction.amount.total}; because Micrometer strips the
     * reserved {@code _total} suffix from non-counter meters, the Prometheus
     * endpoint exposes {@code carddemo_transaction_amount_sum},
     * {@code carddemo_transaction_amount_count}, and
     * {@code carddemo_transaction_amount_max}. The dashboard amount panel reads
     * the {@code _sum} and {@code _count} series, which a plain counter (a
     * single {@code _total} series) would not provide. No base unit is set so
     * no unit token is inserted into the series name.
     *
     * @param registry the Spring Boot auto-configured meter registry
     * @return the registered transaction-amount distribution summary
     */
    @Bean
    DistributionSummary transactionAmountSummary(MeterRegistry registry) {
        return DistributionSummary.builder(TRANSACTION_AMOUNT_TOTAL)
                .description("Monetary amount of posted transactions (distribution: sum, count, max)")
                .register(registry);
    }
}
