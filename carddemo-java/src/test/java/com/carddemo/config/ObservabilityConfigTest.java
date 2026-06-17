package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Unit tests for {@link ObservabilityConfig}.
 *
 * <p>Traceability (REFERENCE-ONLY; no COBOL equivalent; source commit {@code 27d6c6f}): verifies
 * that the contributed {@link ObservationPredicate} excludes {@code /actuator} HTTP server
 * observations while admitting application requests and non-HTTP observation contexts, and that the
 * contributed {@link MeterFilter} denies only the conflicting {@code spring.batch.job.active}
 * framework meter while leaving the AAP-mandated custom metrics untouched.</p>
 */
@DisplayName("ObservabilityConfig - actuator observation exclusion predicate + batch meter filter")
class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();
    private final ObservationPredicate predicate = config.actuatorObservationPredicate();
    private final MeterFilter meterFilter = config.denyConflictingBatchActiveMeter();

    private static ServerRequestObservationContext serverContext(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return new ServerRequestObservationContext(request, mock(HttpServletResponse.class));
    }

    private static Meter.Id meterId(String name) {
        return new Meter.Id(name, Tags.empty(), null, null, Meter.Type.LONG_TASK_TIMER);
    }

    @Test
    @DisplayName("Actuator HTTP requests are excluded from observation")
    void actuatorExcluded() {
        assertThat(predicate.test("http.server.requests", serverContext("/actuator/health"))).isFalse();
    }

    @Test
    @DisplayName("Application HTTP requests are observed")
    void applicationRequestObserved() {
        assertThat(predicate.test("http.server.requests", serverContext("/api/accounts/1"))).isTrue();
    }

    @Test
    @DisplayName("Non-HTTP observation contexts are observed")
    void nonServerContextObserved() {
        assertThat(predicate.test("jdbc.query", new Observation.Context())).isTrue();
    }

    @Test
    @DisplayName("The conflicting spring.batch.job.active meter is denied")
    void batchActiveMeterDenied() {
        assertThat(meterFilter.accept(meterId("spring.batch.job.active")))
                .isEqualTo(MeterFilterReply.DENY);
    }

    @Test
    @DisplayName("AAP-mandated custom metrics are not denied by the batch meter filter")
    void customMetricsNotDenied() {
        assertThat(meterFilter.accept(meterId("carddemo.batch.records.processed")))
                .isEqualTo(MeterFilterReply.NEUTRAL);
        assertThat(meterFilter.accept(meterId("carddemo.transaction.amount.total")))
                .isEqualTo(MeterFilterReply.NEUTRAL);
        // A different batch meter sharing the prefix must remain registered (exact-name match only).
        assertThat(meterFilter.accept(meterId("spring.batch.job.launch.count")))
                .isEqualTo(MeterFilterReply.NEUTRAL);
    }
}
