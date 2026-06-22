package com.carddemo.config;

import io.micrometer.core.instrument.config.MeterFilter;
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

    /**
     * Normalizes the tag-key set of Spring Batch's framework-owned {@code spring.batch.job.active}
     * meter so the Prometheus registry registers it exactly once.
     *
     * <p>Spring Batch's Micrometer instrumentation offers the active-job {@code LongTaskTimer} to the
     * registry twice with two different tag-key conventions: the canonical observation variant (tag
     * key {@code spring.batch.job.active.name}) and a duplicate that carries the finished-job timer's
     * tag keys ({@code spring.batch.job.name} plus {@code spring.batch.job.status}). Because Prometheus
     * requires every meter sharing a name to share one tag-key set, the second offer is rejected and
     * {@code PrometheusMeterRegistry} logs a one-time tag-key-mismatch WARN. The application declares no
     * {@code spring.batch.*} meter and uses no {@code @EnableBatchProcessing}; the collision is purely
     * internal to the framework and does not affect the AAP custom meters ({@code carddemo.*}).
     *
     * <p>This filter denies only the duplicate variant — the one named {@code spring.batch.job.active}
     * that also carries a {@code spring.batch.job.status} tag — before it reaches the Prometheus naming
     * layer. The canonical observation meter (which has no {@code spring.batch.job.status} tag) is never
     * matched and therefore always survives, so active-job timing remains observable and no required
     * metric is lost. Rationale and alternatives are recorded in {@code DECISION_LOG.md} (D-037).
     *
     * @return a {@link MeterFilter} that suppresses the mis-tagged duplicate active-job meter
     */
    @Bean
    MeterFilter batchActiveJobDuplicateMeterFilter() {
        return MeterFilter.deny(id ->
                "spring.batch.job.active".equals(id.getName())
                        && id.getTag("spring.batch.job.status") != null);
    }
}
