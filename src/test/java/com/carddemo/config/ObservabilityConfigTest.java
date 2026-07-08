package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Fast, DB-free unit test for {@link ObservabilityConfig}, the <em>tracing</em> leg of the
 * observability trio introduced by the CardDemo migration (goal&nbsp;G6, the Observability rule).
 *
 * <p>The legacy COBOL batch tier carried <strong>zero</strong> structured observability: the
 * representative posting program {@code CBTRN02C} (source referenced read-only at commit SHA
 * {@code 27d6c6f}) emits only console {@code DISPLAY} lines and threads no trace, metric, or
 * correlation context through its {@code PERFORM UNTIL END-OF-FILE} loop. {@link ObservabilityConfig}
 * closes that gap for the Java target, and this test pins the configuration's contract so the gap
 * cannot silently reopen.</p>
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li><strong>Tracing enablement</strong> &mdash; the {@link ObservedAspect} bean is registered
 *       (so {@code @Observed} methods produce observations, and therefore spans) whenever an
 *       {@link ObservationRegistry} is available.</li>
 *   <li><strong>Tracing-only scope</strong> &mdash; the configuration defines <em>no</em>
 *       {@link MeterRegistry}, {@link Counter}, or {@link HealthIndicator} bean. Metrics live in
 *       {@code com.carddemo.observability.MetricsConfig} and health in
 *       {@code com.carddemo.observability.HealthIndicators}; the registry, {@code Tracer}, and OTLP
 *       exporter come from Spring Boot auto-configuration. Asserting their absence here proves the
 *       division of responsibility agreed during Phase-3 coordination.</li>
 *   <li><strong>Optional actuator-skip customizer</strong> &mdash; when the
 *       {@link ObservationRegistryCustomizer} bean is present, its decision logic skips
 *       {@code /actuator/**} server requests (keeping high-frequency scrape/health traffic out of
 *       Jaeger) while keeping business ({@code /api/**}) requests. This behaviour is <em>optional</em>:
 *       the assertion is guarded by an assumption so an absent customizer skips the check rather than
 *       failing it.</li>
 * </ol>
 *
 * <p>The suite uses Spring Boot's lightweight {@link ApplicationContextRunner} &mdash; no
 * {@code @SpringBootTest}, database, or network &mdash; so it runs in milliseconds and contributes
 * fast line coverage toward the Gate&nbsp;8 (&ge;80%) JaCoCo threshold while compiling warning-free
 * under {@code -Xlint:all} (Gate&nbsp;2). Design rationale for the tracing/metrics/health split lives
 * in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 *
 * @see ObservabilityConfig
 * @see ObservedAspect
 * @see ObservationRegistryCustomizer
 */
@DisplayName("ObservabilityConfig — tracing-only wiring (ObservedAspect + optional actuator skip)")
class ObservabilityConfigTest {

    /**
     * Minimal context under test: only the collaborator {@link ObservabilityConfig} requires
     * (an {@link ObservationRegistry}, which Spring Boot auto-configures in production and which
     * {@link ObservationRegistry#create()} supplies here as a real, no-op-friendly instance) plus
     * the configuration itself. No auto-configuration is loaded, so any bean discovered in the
     * context must originate from {@link ObservabilityConfig} &mdash; this is what makes the
     * "tracing-only scope" assertions meaningful.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObservationRegistry.class, ObservationRegistry::create)
            .withUserConfiguration(ObservabilityConfig.class);

    // ------------------------------------------------------------------
    // 1) ObservedAspect is registered when an ObservationRegistry exists
    // ------------------------------------------------------------------

    @Test
    @DisplayName("registers a single ObservedAspect bean and starts cleanly")
    void registersObservedAspectWhenObservationRegistryPresent() {
        runner.run(context -> {
            // Context must refresh without failure (ObservedAspect construction needs the registry
            // and aspectjweaver, both present) before any bean assertion is trustworthy.
            assertThat(context).hasNotFailed();
            // Exactly one ObservedAspect proves @Observed AOP weaving is enabled by this config.
            assertThat(context).hasSingleBean(ObservedAspect.class);
        });
    }

    // ------------------------------------------------------------------
    // 2) Tracing-only scope: no metrics / health / registry beans here
    // ------------------------------------------------------------------

    @Test
    @DisplayName("stays tracing-scoped: defines no MeterRegistry, Counter, or HealthIndicator bean")
    void definesNoMetricsOrHealthBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Metrics counters live in com.carddemo.observability.MetricsConfig and health in
            // com.carddemo.observability.HealthIndicators; the MeterRegistry itself comes from
            // auto-configuration. Their absence from a context that loaded ONLY ObservabilityConfig
            // (plus the supplied ObservationRegistry) proves ObservabilityConfig owns tracing alone.
            assertThat(context).doesNotHaveBean(MeterRegistry.class);
            assertThat(context).doesNotHaveBean(Counter.class);
            assertThat(context).doesNotHaveBean(HealthIndicator.class);
        });
    }

    // ------------------------------------------------------------------
    // 3) Optional actuator-skip customizer (guarded: absent -> skipped)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("optional actuator-skip customizer: skips /actuator/** and keeps /api/** when present")
    void actuatorSkipCustomizerSkipsActuatorAndKeepsApiWhenPresent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // The actuator-skip customizer is documented as OPTIONAL. If ObservabilityConfig does not
            // register it, this test is SKIPPED (not failed) via a JUnit assumption.
            String[] customizerBeanNames =
                    context.getBeanNamesForType(ObservationRegistryCustomizer.class);
            assumeTrue(customizerBeanNames.length > 0,
                    "actuator-skip customizer is optional; skipping predicate assertion when absent");

            // Present -> the isolated context loaded only ObservabilityConfig, so exactly one
            // ObservationRegistryCustomizer is expected.
            assertThat(customizerBeanNames).hasSize(1);

            // Exercise the exact decision the customizer installs. Its predicate delegates to the
            // package-private, side-effect-free ObservabilityConfig.shouldObserve(Observation.Context),
            // which this same-package test can invoke directly with real ServerRequestObservationContext
            // carriers — avoiding raw ObservationRegistryCustomizer generics (Gate 2, zero warnings).
            assertThat(ObservabilityConfig.shouldObserve(serverRequestContext("/actuator/prometheus")))
                    .as("an /actuator/** scrape must be skipped (not traced)")
                    .isFalse();
            assertThat(ObservabilityConfig.shouldObserve(serverRequestContext("/api/accounts/1")))
                    .as("a business /api/** request must be observed (traced)")
                    .isTrue();
        });
    }

    /**
     * Builds a real {@link ServerRequestObservationContext} whose carrier is a
     * {@link MockHttpServletRequest} with the given request URI, matching the exact shape
     * {@link ObservabilityConfig#shouldObserve(io.micrometer.observation.Observation.Context)}
     * inspects at runtime (it reads {@code getCarrier().getRequestURI()}).
     *
     * @param requestUri the server request URI to simulate (for example {@code /actuator/prometheus})
     * @return a server-request observation context carrying a request for {@code requestUri}
     */
    private static ServerRequestObservationContext serverRequestContext(final String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(requestUri);
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }
}
