package com.carddemo.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Distributed-tracing assembly for the CardDemo migration &mdash; the <em>tracing</em> leg of the
 * observability trio mandated by goal&nbsp;G6.
 *
 * <p>The legacy COBOL/CICS/VSAM system carried <strong>zero</strong> observability, so tracing is
 * entirely net-new. This {@code @Configuration} has a single, focused responsibility: make
 * {@link io.micrometer.observation.annotation.Observed @Observed}-annotated methods produce
 * observations (and therefore spans), so that a request trace stitches together the
 * {@code REST &rarr; service &rarr; repository &rarr; AWS} boundaries.</p>
 *
 * <h2>What is auto-configured (and therefore NOT created here)</h2>
 * <p>The {@code Tracer}, the OTLP span exporter, the {@code SpanProcessor}, the
 * {@link ObservationRegistry}, and the Micrometer&nbsp;&harr;&nbsp;OpenTelemetry bridge are all
 * provided by Spring Boot auto-configuration, driven purely by the externalized properties
 * {@code management.tracing.*} and {@code management.otlp.tracing.endpoint} (the Jaeger OTLP
 * collector, declared in {@code application*.yml}). This class deliberately hard-codes no
 * endpoint, host, or port, and defines none of those beans &mdash; hand-declaring them would
 * shadow or conflict with the auto-configured pipeline. The bridge chain is
 * {@code Micrometer Tracing &rarr; OpenTelemetry &rarr; OTLP &rarr; Jaeger}.</p>
 *
 * <h2>Separation of concerns within the observability trio</h2>
 * <p>The remaining observability responsibilities live in sibling {@code com.carddemo.observability}
 * components and are intentionally not touched here:</p>
 * <ul>
 *   <li><strong>Metrics</strong> &mdash; {@code observability.MetricsConfig} (common tags and the
 *       Micrometer counters); this class creates no {@code MeterRegistry} or {@code Counter}.</li>
 *   <li><strong>Health / readiness</strong> &mdash; {@code observability.HealthIndicators}
 *       ({@code database} / {@code s3} / {@code sqs}); this class creates no {@code HealthIndicator}.</li>
 *   <li><strong>Log correlation</strong> &mdash; {@code observability.CorrelationIdFilter} (the MDC
 *       {@code correlationId} on the request path); this class registers no filter.</li>
 * </ul>
 * <p>Those siblings are {@code @Component}/{@code @Configuration} classes discovered by the root
 * component scan, so they are neither imported nor {@code @Import}ed here. Design rationale is
 * recorded in {@code docs/decision-log.md} and the COBOL&nbsp;&rarr;&nbsp;Java paragraph mapping in
 * {@code docs/traceability-matrix.md} (source referenced read-only by commit SHA {@code 27d6c6f});
 * rationale is intentionally kept out of code comments per the Explainability rule.</p>
 *
 * @see ObservedAspect
 * @see ObservationRegistry
 * @see ObservationRegistryCustomizer
 */
@Configuration
public class ObservabilityConfig {

    /**
     * URI prefix of the Actuator management endpoints. Server requests whose path starts with this
     * value (for example the Prometheus scrape at {@code /actuator/prometheus} and the readiness poll
     * at {@code /actuator/health}) are excluded from tracing so they do not flood Jaeger with
     * high-frequency infrastructure spans.
     */
    private static final String ACTUATOR_PATH_PREFIX = "/actuator";

    /**
     * Registers the Micrometer {@link ObservedAspect} so that
     * {@link io.micrometer.observation.annotation.Observed @Observed} methods on service, repository,
     * and AWS-adapter beans are woven into observations (and, through the auto-configured tracing
     * bridge, into OpenTelemetry spans exported to Jaeger).
     *
     * <p>This mirrors Spring Boot's own {@code @ConditionalOnMissingBean} definition, so declaring it
     * explicitly is safe: Boot backs off and this bean is used, making the intent visible at the
     * point where the {@code REST &rarr; service &rarr; repository &rarr; AWS} span propagation is
     * enabled. AspectJ weaving ({@code aspectjweaver}) is present on the classpath, satisfying the
     * aspect's runtime requirement.</p>
     *
     * @param observationRegistry the auto-configured {@link ObservationRegistry}, injected as a
     *                            method parameter (never {@code null})
     * @return the aspect that observes {@code @Observed}-annotated methods
     */
    @Bean
    ObservedAspect observedAspect(final ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Customises the auto-configured {@link ObservationRegistry} to skip observations for Actuator
     * management endpoints, keeping the trace stream focused on business traffic.
     *
     * <p>The customizer installs a predicate (see {@link #shouldObserve(Observation.Context)}) on the
     * registry; it neither replaces the registry nor alters tracing propagation for application
     * requests. This is a low-risk, purely additive noise-reduction step: without it, every Prometheus
     * scrape and health poll would emit a span to Jaeger.</p>
     *
     * @return a customizer that suppresses tracing of {@code /actuator/**} server requests
     */
    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> skipActuatorObservations() {
        return registry -> registry.observationConfig()
                .observationPredicate((name, context) -> shouldObserve(context));
    }

    /**
     * Decides whether a given observation {@code context} should be recorded.
     *
     * <p>Returns {@code false} only for inbound HTTP <em>server</em> requests whose URI targets the
     * Actuator management surface ({@code /actuator/**}); every other context &mdash; including
     * non-server observations such as service, repository, and AWS-client spans, for which the
     * carrier is not a {@link ServerRequestObservationContext} &mdash; is observed. A defensive
     * {@code null}-carrier check keeps the predicate total.</p>
     *
     * <p>Package-private and side-effect free so it can be unit-tested directly without bootstrapping
     * a Spring context.</p>
     *
     * @param context the observation context under evaluation (never {@code null})
     * @return {@code true} to observe (and trace) the context; {@code false} to skip an Actuator
     *         server request
     */
    static boolean shouldObserve(final Observation.Context context) {
        if (context instanceof ServerRequestObservationContext serverContext) {
            var request = serverContext.getCarrier();
            return request == null || !request.getRequestURI().startsWith(ACTUATOR_PATH_PREFIX);
        }
        return true;
    }
}
