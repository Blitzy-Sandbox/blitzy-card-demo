package com.carddemo.config;

import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Additive observability wiring: the metrics, correlation-id filter, and health indicators
 * live in {@code com.carddemo.observability} and self-register, while tracing, OTLP export,
 * and Prometheus are auto-configured; this class only excludes {@code /actuator} HTTP server
 * observations. No COBOL equivalent (cross-cutting infrastructure; source commit 27d6c6f).
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /**
     * Suppresses observations for {@code /actuator/**} HTTP server requests so that health and
     * Prometheus-scrape traffic does not pollute traces and metrics. Spring Boot's
     * {@code ObservationAutoConfiguration} applies every {@link ObservationPredicate} bean to the
     * global {@code ObservationRegistry} and skips an observation whenever any predicate returns
     * {@code false}. Only HTTP server observations carry a {@link ServerRequestObservationContext};
     * all other observations (JPA, batch, S3/SQS) match neither branch and are always kept.
     *
     * @return a predicate that keeps every observation except {@code /actuator} HTTP server requests
     */
    @Bean
    ObservationPredicate actuatorObservationPredicate() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                HttpServletRequest request = serverContext.getCarrier();
                // A null carrier can occur before the request is bound; treat it as "keep".
                return request == null || !request.getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
