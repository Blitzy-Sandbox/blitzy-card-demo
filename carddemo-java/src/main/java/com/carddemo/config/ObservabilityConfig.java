package com.carddemo.config;

import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Additive observability wiring: the metrics, correlation-id filter, and health indicators live in
 * {@code com.carddemo.observability} and self-register while tracing, OTLP export, and Prometheus are
 * auto-configured, so this class only contributes a predicate that excludes {@code /actuator} HTTP
 * server observations from tracing and metrics. No COBOL equivalent (source commit 27d6c6f).
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    @Bean
    ObservationPredicate actuatorObservationPredicate() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                HttpServletRequest request = serverContext.getCarrier();
                return request == null || !request.getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
