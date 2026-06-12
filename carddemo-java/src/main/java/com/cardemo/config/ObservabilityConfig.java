package com.cardemo.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Framework-level observability wiring for the greenfield Java 25 LTS + Spring Boot 3.5.11
 * migration of the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS mainframe application.
 *
 * <p>This {@code @Configuration} is component-scanned by {@code CardDemoApplication} (base package
 * {@code com.cardemo}, decision <strong>D-006</strong> &mdash; deliberately <em>not</em>
 * {@code com.carddemo}), which delegates all cross-cutting configuration to the {@code config}
 * package. Its role in the authoritative target tree is &quot;Tracing, metrics, logging&quot;
 * (tech-spec L341), within the Observability Implementation Analysis (tech-spec L816&ndash;L836).
 * The class is intentionally <strong>thin</strong>: its single active behavior is registering the
 * Micrometer {@link ObservedAspect} bean, and &mdash; as with the other {@code config} classes
 * &mdash; its most important contract is what it deliberately does <em>not</em> do (see the
 * separation-of-concerns section below).</p>
 *
 * <h2>Migration provenance (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>This is a brand-new file with <strong>no COBOL source equivalent</strong>; it is a pure
 * technology-substitution component in the net-new cross-cutting observability layer. The legacy
 * AWS CardDemo COBOL/CICS estate shipped <em>zero</em> observability infrastructure &mdash; no
 * logging framework, no metrics, no tracing, no health checks (tech-spec L818). The blueprint's
 * &quot;Observability&quot; cross-cutting requirement (AAP &sect;0.7.7) mandates that the
 * application is not complete until it is observable, and that observability ships with the
 * initial implementation rather than as a follow-up. Behaviour for the wider application is
 * translated from the frozen AWS CardDemo COBOL baseline at commit SHA {@code 27d6c6f}; the COBOL
 * source is <em>never copied</em> into this repository, and traceability to the legacy baseline is
 * by that commit SHA only (AAP &sect;0.7.2 &mdash; Preservation Requirements).</p>
 *
 * <h2>Single responsibility &mdash; framework-level observation enablement</h2>
 * <p>This class wires the <strong>framework-level</strong> observability plumbing only: it
 * registers the {@link ObservedAspect} so that methods annotated with
 * {@code io.micrometer.observation.annotation.Observed} (in the {@code com.cardemo.service.*} and
 * {@code com.cardemo.batch.*} layers) are intercepted to produce a Micrometer {@code Observation}.
 * Through Spring Boot's auto-configured {@link ObservationRegistry}, each observation fans out to
 * <strong>both</strong> a timer metric (Micrometer &rarr; Prometheus via
 * {@code micrometer-registry-prometheus}) <strong>and</strong> a trace span (Micrometer Tracing
 * &rarr; OTLP via {@code micrometer-tracing-bridge-otel} &rarr; Jaeger). This single bean is the
 * canonical, minimal enablement that makes distributed tracing and metrics function for annotated
 * methods; the concrete tracing/metrics backends are supplied by the starters declared in
 * {@code pom.xml} and configured in {@code application.yml}.</p>
 *
 * <h2>Technology substitution (AAP &sect;0.7.1 &mdash; documented at the point of change)</h2>
 * <dl>
 *   <dt>CICS/COBOL observability gap &rarr; Micrometer Observation API ({@link ObservedAspect})</dt>
 *   <dd>The legacy estate had no instrumentation hook of any kind. The Observation API provides the
 *       vendor-neutral instrumentation seam: a single {@code @Observed} annotation yields a metric
 *       and a span without coupling business code to either Prometheus or OpenTelemetry. Enabling
 *       the aspect here is the framework-level counterpart of the net-new observability rule
 *       (tech-spec L816&ndash;L836).</dd>
 *
 *   <dt>Spring AOP requirement</dt>
 *   <dd>{@link ObservedAspect} is an AspectJ {@code @Aspect}; it is woven by Spring AOP. The
 *       {@code org.aspectj:aspectjweaver} dependency is present transitively (via the Spring Boot
 *       starters), so Spring Boot's {@code AopAutoConfiguration} activates
 *       {@code @EnableAspectJAutoProxy} automatically and proxies the {@code @Observed} join
 *       points. No explicit {@code @EnableAspectJAutoProxy} is declared here (Minimal Change
 *       Clause): adding it would merely duplicate the auto-configuration.</dd>
 * </dl>
 *
 * <h2>Separation of concerns (boundaries this class does not cross)</h2>
 * <p>The observability implementation is split across two packages and several externalized
 * resources. To avoid duplicate-bean / duplicate-tag conflicts, this {@code config} class performs
 * <strong>only</strong> framework enablement and crosses none of the following boundaries:</p>
 * <ul>
 *   <li>It does <strong>not</strong> define logging appenders or encoders &mdash; the structured
 *       JSON appenders and the {@code traceId}/{@code spanId}/{@code correlationId} MDC pattern
 *       (using {@code logstash-logback-encoder}) are owned by
 *       {@code src/main/resources/logback-spring.xml}.</li>
 *   <li>It does <strong>not</strong> register business meters &mdash; the custom counters /
 *       distribution summary ({@code carddemo.batch.records.processed},
 *       {@code carddemo.batch.records.rejected}, {@code carddemo.auth.attempts},
 *       {@code carddemo.transaction.amount.total}) are owned by
 *       {@code com.cardemo.observability.MetricsConfig}.</li>
 *   <li>It does <strong>not</strong> define health indicators &mdash; the PostgreSQL / S3 / SQS
 *       readiness &amp; health checks are owned by
 *       {@code com.cardemo.observability.HealthIndicators}.</li>
 *   <li>It does <strong>not</strong> register a correlation-ID servlet filter. The
 *       {@code com.cardemo.observability.CorrelationIdFilter} is a self-registering
 *       {@code @Component} servlet filter that Spring Boot auto-detects and inserts into the filter
 *       chain. This resolves the loose mention at tech-spec L822 (which informally suggested this
 *       config might register such a {@code Filter}) in favour of the standalone filter named at
 *       tech-spec L459: this class owns <strong>only</strong> the {@link ObservedAspect} bean.</li>
 *   <li>It does <strong>not</strong> define the {@link ObservationRegistry} nor any
 *       {@code MeterRegistry} / tracer &mdash; those are auto-configured by Spring Boot Actuator
 *       (the {@code ObservationRegistry} is supplied by Actuator's
 *       {@code ObservationAutoConfiguration} and injected into the bean method below).</li>
 *   <li>It does <strong>not</strong> redeclare any {@code application.yml} value. Actuator endpoint
 *       exposure ({@code management.endpoints.web.exposure.include = health,info,metrics,prometheus}),
 *       trace sampling probability ({@code management.tracing.sampling.probability}), the OTLP
 *       endpoint ({@code management.otlp.tracing.endpoint}, an {@code ${ENV:default}} placeholder),
 *       and the common {@code application} meter tag
 *       ({@code management.metrics.tags.application = carddemo}, aligned with
 *       {@code spring.application.name}) all live in {@code application.yml}. Re-declaring them in
 *       Java would create redundant or conflicting configuration.</li>
 * </ul>
 *
 * <h2>Minimal Change Clause &amp; secret policy (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>No speculative observability beans are introduced beyond the single {@link ObservedAspect}.
 * In particular, no {@code MeterRegistryCustomizer} for a common {@code application} tag is declared
 * here because that tag is already supplied by {@code application.yml} (see the boundaries section);
 * declaring it would duplicate the tag. This file contains <strong>no</strong> endpoint literals,
 * URLs, credentials, or other secrets &mdash; every endpoint is resolved from properties &mdash;
 * and performs no {@code @Value} wiring and no {@code System.out} I/O.</p>
 *
 * @see ObservedAspect
 * @see ObservationRegistry
 * @see org.springframework.context.annotation.Configuration
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Registers the Micrometer {@link ObservedAspect} that intercepts
     * {@code io.micrometer.observation.annotation.Observed}-annotated methods and turns each
     * invocation into a Micrometer {@code Observation}.
     *
     * <p>The supplied {@link ObservationRegistry} is the application-wide registry auto-configured
     * by Spring Boot Actuator ({@code ObservationAutoConfiguration}); it is wired with both the
     * metrics handler (so every observation becomes a timer surfaced at {@code /actuator/prometheus})
     * and the tracing handler (so every observation becomes an OTLP-exported span). Registering this
     * aspect is therefore the single switch that makes {@code @Observed} on services and batch
     * components emit metrics and traces simultaneously.</p>
     *
     * <p>The aspect is woven by Spring AOP. Because {@code org.aspectj:aspectjweaver} is on the
     * classpath (transitively via the Spring Boot starters), Spring Boot's {@code AopAutoConfiguration}
     * enables AspectJ auto-proxying automatically; no additional configuration is required for this
     * bean to take effect.</p>
     *
     * @param registry the auto-configured, application-wide {@link ObservationRegistry} (never
     *                  {@code null}); injected by Spring from Actuator's observation
     *                  auto-configuration
     * @return the {@link ObservedAspect} bean enabling {@code @Observed}-driven metrics and traces
     */
    @Bean
    public ObservedAspect observedAspect(final ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}
