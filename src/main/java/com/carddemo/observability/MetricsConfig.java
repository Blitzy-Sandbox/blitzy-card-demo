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
 *   <li>{@code carddemo.auth.attempts} &mdash; {@link Counter}, series
 *       {@code carddemo_auth_attempts_total}.</li>
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
 * base unit is deliberately omitted from the rejected counter and the amount
 * summary because the Prometheus naming convention would otherwise append the
 * unit to the series name and break the dashboard queries.</p>
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

    /** Cumulative monetary amount of posted transactions (observability only). */
    public static final String TRANSACTION_AMOUNT_TOTAL = "carddemo.transaction.amount.total";

    @Bean
    Counter batchRecordsProcessedCounter(MeterRegistry registry) {
        return Counter.builder(BATCH_RECORDS_PROCESSED)
                .description("Total daily-transaction records successfully posted by the batch pipeline")
                .baseUnit("records")
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

    @Bean
    Counter authAttemptsCounter(MeterRegistry registry) {
        return Counter.builder(AUTH_ATTEMPTS)
                .description("Total authentication (sign-on) attempts")
                .baseUnit("attempts")
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
