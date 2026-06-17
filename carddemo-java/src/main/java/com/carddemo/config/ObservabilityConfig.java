package com.carddemo.config;

import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Additive observability wiring: the metrics, correlation-id filter, and health indicators live in
 * {@code com.carddemo.observability} and self-register while tracing, OTLP export, and Prometheus are
 * auto-configured, so this class contributes a predicate that excludes {@code /actuator} HTTP server
 * observations from tracing and metrics, plus a meter filter that suppresses a duplicated framework
 * batch meter. No COBOL equivalent (source commit 27d6c6f).
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /**
     * Micrometer meter name of the Spring Batch "currently-active jobs" gauge that two framework
     * instrumentation paths register with incompatible tag keys.
     */
    static final String BATCH_JOB_ACTIVE_METER = "spring.batch.job.active";

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

    /**
     * Suppresses the {@value #BATCH_JOB_ACTIVE_METER} meter.
     *
     * <p>Spring Batch instruments active jobs through two paths whose tag keys differ
     * ({@code [application, spring_batch_job_active_name]} from the legacy job-metrics gauge versus
     * {@code [application, spring_batch_job_name, spring_batch_job_status]} from the observation
     * convention). Prometheus requires all meters of the same name to share one tag-key set, so the
     * second registration is rejected with a recurring {@code WARN}. This meter is Spring Batch
     * framework auto-instrumentation — none of the AAP-mandated custom metrics
     * ({@code carddemo.batch.records.processed}/{@code .rejected}, {@code carddemo.auth.attempts},
     * {@code carddemo.transaction.amount.total}) depend on it and no dashboard panel consumes it — so
     * denying it removes the conflict cleanly. The rationale and rejected alternatives are recorded in
     * {@code DECISION_LOG.md}.</p>
     *
     * @return a meter filter that denies the conflicting batch active-job meter by exact name
     */
    @Bean
    MeterFilter denyConflictingBatchActiveMeter() {
        return MeterFilter.deny(id -> BATCH_JOB_ACTIVE_METER.equals(id.getName()));
    }
}
