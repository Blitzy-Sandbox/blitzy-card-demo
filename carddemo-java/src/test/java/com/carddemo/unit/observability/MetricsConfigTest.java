package com.carddemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.observability.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

/**
 * Unit tests for {@link MetricsConfig}, the single source of truth for the four
 * custom CardDemo Micrometer meters. Exercises the {@link MeterBinder}
 * pre-registration, the three static counter helpers, and the signed
 * transaction-amount gauge accumulator (including the {@code null} no-op).
 */
class MetricsConfigTest {

    private SimpleMeterRegistry registry;
    private MetricsConfig metricsConfig;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsConfig = new MetricsConfig();
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    @DisplayName("core-metrics binder pre-registers the processed counter and amount gauge at zero")
    void binderPreRegistersUntaggedMeters() {
        MeterBinder binder = metricsConfig.carddemoCoreMetrics();
        binder.bindTo(registry);

        Counter processed = registry.find(MetricsConfig.BATCH_RECORDS_PROCESSED).counter();
        Gauge amount = registry.find(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).gauge();
        assertThat(processed).isNotNull();
        assertThat(processed.count()).isZero();
        assertThat(amount).isNotNull();
        assertThat(amount.value()).isZero();
    }

    @Test
    @DisplayName("static counter helpers resolve idempotent, correctly-tagged counters")
    void staticCounterHelpers() {
        MetricsConfig.recordsProcessed(registry).increment();
        MetricsConfig.recordsProcessed(registry).increment();
        MetricsConfig.recordsRejected(registry, "101").increment();
        MetricsConfig.authAttempts(registry, MetricsConfig.OUTCOME_SUCCESS).increment();
        MetricsConfig.authAttempts(registry, MetricsConfig.OUTCOME_FAILURE).increment();

        assertThat(registry.find(MetricsConfig.BATCH_RECORDS_PROCESSED).counter().count()).isEqualTo(2.0);
        assertThat(registry.find(MetricsConfig.BATCH_RECORDS_REJECTED)
                .tag(MetricsConfig.TAG_REASON, "101").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(MetricsConfig.AUTH_ATTEMPTS)
                .tag(MetricsConfig.TAG_OUTCOME, MetricsConfig.OUTCOME_SUCCESS).counter().count()).isEqualTo(1.0);
        assertThat(registry.find(MetricsConfig.AUTH_ATTEMPTS)
                .tag(MetricsConfig.TAG_OUTCOME, MetricsConfig.OUTCOME_FAILURE).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("addTransactionAmount accumulates signed amounts; null is a no-op")
    void transactionAmountGaugeAccumulatesSignedAmounts() {
        metricsConfig.carddemoCoreMetrics().bindTo(registry);

        metricsConfig.addTransactionAmount(new BigDecimal("100.50"));
        metricsConfig.addTransactionAmount(new BigDecimal("-25.25"));
        metricsConfig.addTransactionAmount(null);

        Gauge amount = registry.find(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).gauge();
        assertThat(amount).isNotNull();
        assertThat(amount.value()).isEqualTo(75.25);
    }
}
