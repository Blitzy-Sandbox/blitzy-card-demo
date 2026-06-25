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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the four custom CardDemo application metrics as Micrometer
 * {@link Counter} meters against the Spring Boot auto-configured
 * {@link MeterRegistry}.
 *
 * <p>Each meter is registered eagerly so that it is visible at value {@code 0}
 * on the Actuator Prometheus endpoint ({@code /actuator/prometheus}) from
 * startup, before the first business event is recorded. Micrometer exposes the
 * dotted meter names in snake_case and appends a {@code _total} suffix to
 * counter series.</p>
 *
 * <p>The metric-name constants are the single source of truth for every call
 * site, and each {@code @Bean} method name is the bean name used for qualified
 * injection (for example {@code @Qualifier("batchRecordsProcessedCounter")}).</p>
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

    @Bean
    Counter batchRecordsRejectedCounter(MeterRegistry registry) {
        return Counter.builder(BATCH_RECORDS_REJECTED)
                .description("Total daily-transaction records rejected by the posting validation cascade")
                .baseUnit("records")
                .register(registry);
    }

    @Bean
    Counter authAttemptsCounter(MeterRegistry registry) {
        return Counter.builder(AUTH_ATTEMPTS)
                .description("Total authentication (sign-on) attempts")
                .baseUnit("attempts")
                .register(registry);
    }

    @Bean
    Counter transactionAmountTotalCounter(MeterRegistry registry) {
        return Counter.builder(TRANSACTION_AMOUNT_TOTAL)
                .description("Cumulative monetary amount of posted transactions")
                .register(registry);
    }
}
