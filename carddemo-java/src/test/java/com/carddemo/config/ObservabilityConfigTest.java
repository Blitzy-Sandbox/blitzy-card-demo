package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link ObservabilityConfig#actuatorObservationPredicate()}.
 * Verifies that {@code /actuator} HTTP server observations are suppressed while
 * all other server requests and non-HTTP observations (JPA, batch, S3/SQS) are
 * kept. Lives in the {@code com.carddemo.config} package so the package-private
 * {@code @Bean} factory method is accessible.
 */
class ObservabilityConfigTest {

    private final ObservationPredicate predicate =
            new ObservabilityConfig().actuatorObservationPredicate();

    private static ServerRequestObservationContext serverContext(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }

    @Test
    @DisplayName("/actuator HTTP server observations are suppressed")
    void suppressesActuatorRequests() {
        assertThat(predicate.test("http.server.requests", serverContext("/actuator/prometheus"))).isFalse();
        assertThat(predicate.test("http.server.requests", serverContext("/actuator/health"))).isFalse();
    }

    @Test
    @DisplayName("non-actuator HTTP server observations are kept")
    void keepsApplicationRequests() {
        assertThat(predicate.test("http.server.requests", serverContext("/api/accounts/1"))).isTrue();
    }

    @Test
    @DisplayName("non-HTTP-server observations are always kept")
    void keepsNonServerObservations() {
        assertThat(predicate.test("jpa.query", new Observation.Context())).isTrue();
    }
}
