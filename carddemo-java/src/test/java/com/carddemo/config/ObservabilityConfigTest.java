package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * observations while admitting application requests and non-HTTP observation contexts.</p>
 */
@DisplayName("ObservabilityConfig - actuator observation exclusion predicate")
class ObservabilityConfigTest {

    private final ObservationPredicate predicate =
            new ObservabilityConfig().actuatorObservationPredicate();

    private static ServerRequestObservationContext serverContext(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return new ServerRequestObservationContext(request, mock(HttpServletResponse.class));
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
}
