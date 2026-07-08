package com.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;

/**
 * Pure, dependency-free unit test for {@link MetricsConfig}, the Micrometer
 * metrics configuration that delivers the Observability goal (G6) on day one.
 *
 * <p>It verifies the metrics contract with no Spring context, no Actuator, no
 * Prometheus registry, no Docker, and no network: the configuration is
 * instantiated directly ({@code new MetricsConfig()}) and driven with an
 * in-memory {@link SimpleMeterRegistry} (Actuator auto-configures the real
 * registry in production, so the unit test supplies its own). The
 * {@code @Bean} factory methods are package-private, which is why this test
 * lives in the same {@code com.carddemo.observability} package.</p>
 *
 * <p>Three behaviours are asserted, covering all three factory methods so the
 * class contributes fast line coverage toward the Gate&nbsp;8 (&ge;80%) JaCoCo
 * threshold:</p>
 * <ul>
 *   <li>the two domain counters register under their exact Micrometer names
 *       {@code carddemo.transactions.posted} and
 *       {@code carddemo.transactions.rejected} and start at zero;</li>
 *   <li>the counters are incrementable, mirroring the COBOL
 *       {@code ADD 1 TO WS-TRANSACTION-COUNT} / {@code ADD 1 TO WS-REJECT-COUNT}
 *       working-storage tallies of the batch posting engine {@code CBTRN02C};
 *       and</li>
 *   <li>the {@link MeterRegistryCustomizer} stamps the low-cardinality common
 *       tag {@code application=carddemo} on every meter registered after it
 *       runs.</li>
 * </ul>
 *
 * <p><strong>Base unit.</strong> The production counters intentionally omit a
 * Micrometer base unit: a non-null base unit is appended by the Prometheus
 * naming convention as a unit suffix, which would corrupt the scraped series
 * name. The tests therefore assert a {@code null} base unit, locking in that
 * deliberate design decision. The two counters trace to the {@code CBTRN02C}
 * working-storage tallies; the rationale for these choices is recorded in
 * {@code docs/decision-log.md} and the paragraph-level mapping in
 * {@code docs/traceability-matrix.md}. No COBOL source is reproduced here.</p>
 */
@DisplayName("MetricsConfig — domain counters and the application=carddemo common tag")
class MetricsConfigTest {

    /**
     * Unit under test. The configuration is a plain object with a default
     * constructor; no Spring context is required to exercise its factory
     * methods. A fresh {@link SimpleMeterRegistry} is created inside each test
     * for full isolation.
     */
    private final MetricsConfig config = new MetricsConfig();

    // ------------------------------------------------------------------
    // Phase 2 — posted counter: exact name, no base unit, registered, zero.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("transactionsPostedCounter: name carddemo.transactions.posted, no base unit, registered, starts at 0")
    void transactionsPostedCounter_hasExpectedNameUnitAndIsRegistered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Counter counter = config.transactionsPostedCounter(registry);

        assertThat(counter).isNotNull();
        assertThat(counter.getId().getName()).isEqualTo("carddemo.transactions.posted");
        // The production counter deliberately does NOT set a Micrometer base unit: a non-null
        // base unit would be appended by the Prometheus naming convention as a unit suffix
        // (e.g. "carddemo_transactions_posted_transactions_total"), whereas the contract requires
        // the series to render exactly "carddemo_transactions_posted_total". Asserting null here
        // locks in that intentional design (rationale in docs/decision-log.md).
        assertThat(counter.getId().getBaseUnit()).isNull();
        // Registration side effect: builder.register(registry) must have added the meter, so the
        // registry can locate it by name.
        assertThat(registry.find("carddemo.transactions.posted").counter()).isNotNull();
        // A freshly created counter has not been incremented yet.
        assertThat(counter.count()).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------
    // Phase 3 — rejected counter: exact name, no base unit, registered, zero.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("transactionsRejectedCounter: name carddemo.transactions.rejected, no base unit, registered, starts at 0")
    void transactionsRejectedCounter_hasExpectedNameAndIsRegistered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Counter counter = config.transactionsRejectedCounter(registry);

        assertThat(counter).isNotNull();
        assertThat(counter.getId().getName()).isEqualTo("carddemo.transactions.rejected");
        // Base unit is intentionally null for the same Prometheus-naming reason as the posted
        // counter (see transactionsPostedCounter_hasExpectedNameUnitAndIsRegistered).
        assertThat(counter.getId().getBaseUnit()).isNull();
        assertThat(registry.find("carddemo.transactions.rejected").counter()).isNotNull();
        assertThat(counter.count()).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------
    // Phase 4 — incrementability: mirrors CBTRN02C ADD 1 TO WS-*-COUNT.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("counters are incrementable — mirrors CBTRN02C ADD 1 TO WS-TRANSACTION-COUNT / WS-REJECT-COUNT")
    void countersIncrement() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        // Posted counter: one ADD 1, then two more processed transactions (increment by 2.0).
        Counter posted = config.transactionsPostedCounter(registry);
        posted.increment();
        posted.increment(2.0);
        assertThat(posted.count()).isEqualTo(3.0);

        // Rejected counter: a single ADD 1 TO WS-REJECT-COUNT.
        Counter rejected = config.transactionsRejectedCounter(registry);
        rejected.increment();
        assertThat(rejected.count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // Phase 5 — common-tag customizer: application=carddemo on later meters.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("common-tag customizer stamps application=carddemo on meters registered after it runs")
    void commonTagsCustomizer_stampsApplicationCarddemoOnMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        MeterRegistryCustomizer<MeterRegistry> customizer = config.carddemoCommonTags();
        assertThat(customizer).isNotNull();

        // Micrometer applies common tags only to meters registered AFTER the customizer runs,
        // so the customizer is applied to the registry before any probe meter is created.
        customizer.customize(registry);

        Counter probe = registry.counter("probe.meter");
        assertThat(probe.getId().getTag("application")).isEqualTo("carddemo");

        // Stronger check: a domain counter created via the config AFTER customization also
        // inherits the common application tag, proving the business signals carry it too.
        Counter posted = config.transactionsPostedCounter(registry);
        assertThat(posted.getId().getTag("application")).isEqualTo("carddemo");
    }
}
