package com.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MetricsConfig}.
 *
 * <p>Traceability (REFERENCE-ONLY; source commit {@code 27d6c6f}): cross-cutting observability
 * with no COBOL equivalent. {@link MetricsConfig} is the single source of truth for the four
 * custom business metrics mandated by the Observability rule. These tests verify that the
 * {@link MeterBinder} pre-registers the two untagged core counters at zero, and that the static
 * accessor helpers create/return the canonical, idempotent counters with the correct tags.</p>
 *
 * <p>This test resides in the {@code com.carddemo.observability} package so it can exercise the
 * package-private {@link MetricsConfig#carddemoCoreMetrics()} {@code @Bean} factory directly.</p>
 */
@DisplayName("MetricsConfig - custom Micrometer business meters")
class MetricsConfigTest {

    @Test
    @DisplayName("carddemoCoreMetrics pre-registers the untagged counters at zero")
    void coreMetricsPreRegistered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeterBinder binder = new MetricsConfig().carddemoCoreMetrics();

        binder.bindTo(registry);

        assertThat(registry.find(MetricsConfig.BATCH_RECORDS_PROCESSED).counter()).isNotNull();
        assertThat(registry.find(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).counter()).isNotNull();
        assertThat(registry.counter(MetricsConfig.BATCH_RECORDS_PROCESSED).count()).isZero();
        assertThat(registry.counter(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).count()).isZero();
    }

    @Test
    @DisplayName("recordsProcessed returns the untagged processed counter")
    void recordsProcessed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Counter counter = MetricsConfig.recordsProcessed(registry);
        counter.increment();

        assertThat(counter.getId().getName()).isEqualTo(MetricsConfig.BATCH_RECORDS_PROCESSED);
        assertThat(registry.counter(MetricsConfig.BATCH_RECORDS_PROCESSED).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordsRejected tags the counter with the reject reason")
    void recordsRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Counter counter = MetricsConfig.recordsRejected(registry, "100");
        counter.increment();

        assertThat(counter.getId().getName()).isEqualTo(MetricsConfig.BATCH_RECORDS_REJECTED);
        assertThat(counter.getId().getTag(MetricsConfig.TAG_REASON)).isEqualTo("100");
    }

    @Test
    @DisplayName("authAttempts tags the counter with the outcome")
    void authAttempts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Counter success = MetricsConfig.authAttempts(registry, MetricsConfig.OUTCOME_SUCCESS);
        Counter failure = MetricsConfig.authAttempts(registry, MetricsConfig.OUTCOME_FAILURE);

        assertThat(success.getId().getTag(MetricsConfig.TAG_OUTCOME))
                .isEqualTo(MetricsConfig.OUTCOME_SUCCESS);
        assertThat(failure.getId().getTag(MetricsConfig.TAG_OUTCOME))
                .isEqualTo(MetricsConfig.OUTCOME_FAILURE);
    }

    @Test
    @DisplayName("transactionAmountTotal returns the untagged amount counter and increments by amount")
    void transactionAmountTotal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Counter counter = MetricsConfig.transactionAmountTotal(registry);
        counter.increment(123.45);

        assertThat(counter.getId().getName()).isEqualTo(MetricsConfig.TRANSACTION_AMOUNT_TOTAL);
        assertThat(registry.counter(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).count()).isEqualTo(123.45);
    }
}
