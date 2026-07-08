package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.observability.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Fast, framework-free unit test for {@link WebConfig}, the web-tier assembly of the headless
 * REST CardDemo migration. Legacy source is referenced read-only at commit SHA {@code 27d6c6f}.
 *
 * <p>{@link WebConfig} contributes exactly two web-layer contracts, and this suite pins both so a
 * regression cannot silently reopen them:</p>
 * <ol>
 *   <li><strong>{@code BigDecimal} JSON fidelity (goal&nbsp;G2 / AAP&nbsp;&sect;0.8.2).</strong>
 *       {@link WebConfig#bigDecimalPlainCustomizer()} installs a {@code JsonFactory} with
 *       {@code StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN} so that every monetary
 *       {@link BigDecimal} (migrated from COBOL {@code PIC S9(n)V99} / {@code COMP-3}) is written
 *       in plain decimal notation — never scientific notation — preserving scale on the wire and
 *       upholding the interface-contract parity gates (Gates&nbsp;1 and&nbsp;5).</li>
 *   <li><strong>Single execution of the correlation-ID filter.</strong>
 *       {@link WebConfig#correlationIdFilterRegistration(CorrelationIdFilter)} returns a
 *       <em>disabled</em> {@link FilterRegistrationBean} that suppresses the servlet-container
 *       auto-registration of the {@code @Component} {@link CorrelationIdFilter}, because
 *       {@code SecurityConfig} positions the same bean inside the security chain — giving exactly
 *       one execution per request.</li>
 * </ol>
 *
 * <p>The test calls {@link WebConfig}'s {@code @Bean} factory methods directly (it lives in the
 * same package, so their package-private visibility is reachable) — no Spring context, database, or
 * network is required, so it runs in milliseconds, compiles warning-free under {@code -Xlint:all}
 * (Gate&nbsp;2), and contributes to the Gate&nbsp;8 (&ge;80%) JaCoCo coverage. Rationale for the
 * filter-registration ownership split lives in {@code docs/decision-log.md}, not in these comments
 * (Explainability rule).</p>
 *
 * @see WebConfig
 * @see CorrelationIdFilter
 */
@DisplayName("WebConfig — plain-BigDecimal JSON + disabled correlation-id servlet registration")
class WebConfigTest {

    /** The configuration under test; stateless, so a single instance is reused across cases. */
    private final WebConfig webConfig = new WebConfig();

    // =====================================================================
    // 1) BigDecimal JSON fidelity — plain notation, scale preserved
    // =====================================================================

    @Nested
    @DisplayName("bigDecimalPlainCustomizer() — plain decimal notation for money")
    class BigDecimalPlainCustomizer {

        /**
         * Builds an {@link ObjectMapper} exactly as Spring Boot would: a
         * {@link Jackson2ObjectMapperBuilder} with the {@link WebConfig} customizer applied.
         *
         * @return the customized mapper under test
         */
        private ObjectMapper customizedMapper() {
            final Jackson2ObjectMapperBuilderCustomizer customizer =
                    webConfig.bigDecimalPlainCustomizer();
            final Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            customizer.customize(builder);
            return builder.build();
        }

        @Test
        @DisplayName("returns a non-null customizer")
        void customizerIsProvided() {
            assertThat(webConfig.bigDecimalPlainCustomizer()).isNotNull();
        }

        @Test
        @DisplayName("writes a value that would otherwise be scientific (1E+9) in plain notation")
        void writesLargeValueInPlainNotation() throws Exception {
            // new BigDecimal("1E+9") has scale -9; its toString() is "1E+9" (scientific) whereas its
            // toPlainString() is "1000000000". The WRITE_BIGDECIMAL_AS_PLAIN feature selects the
            // plain form, so the customized mapper must emit the plain literal with no exponent.
            final BigDecimal scientific = new BigDecimal("1E+9");

            final String customizedJson = customizedMapper().writeValueAsString(scientific);

            assertThat(customizedJson).isEqualTo("1000000000");
            assertThat(customizedJson).doesNotContainIgnoringCase("E");

            // Control: without the customizer, the default mapper emits the scientific form, proving
            // the customizer is what changes the wire representation.
            final String defaultJson = new ObjectMapper().writeValueAsString(scientific);
            assertThat(defaultJson).isNotEqualTo(customizedJson);
            assertThat(defaultJson).containsIgnoringCase("E");
        }

        @Test
        @DisplayName("preserves scale and trailing zeros of a scale-2 monetary value")
        void preservesScaleOfMoneyValue() throws Exception {
            // A COBOL PIC S9(10)V99 balance such as ACCT-CURR-BAL maps to a scale-2 BigDecimal;
            // the plain form keeps both trailing decimal places and uses no exponent.
            final BigDecimal money = new BigDecimal("1234567890.00");

            final String json = customizedMapper().writeValueAsString(money);

            assertThat(json).isEqualTo("1234567890.00");
            assertThat(json).doesNotContainIgnoringCase("E");
        }
    }

    // =====================================================================
    // 2) Correlation-id filter registration — disabled, same instance
    // =====================================================================

    @Nested
    @DisplayName("correlationIdFilterRegistration() — disabled servlet registration of the shared filter")
    class CorrelationIdFilterRegistration {

        @Test
        @DisplayName("returns a disabled FilterRegistrationBean wrapping the supplied filter instance")
        void registrationIsDisabledAndWrapsSameFilter() {
            final CorrelationIdFilter filter = new CorrelationIdFilter();

            final FilterRegistrationBean<CorrelationIdFilter> registration =
                    webConfig.correlationIdFilterRegistration(filter);

            assertThat(registration).isNotNull();
            // Disabled so Spring Boot does NOT auto-register the @Component filter with the servlet
            // container; SecurityConfig owns the single execution inside the security chain.
            assertThat(registration.isEnabled()).isFalse();
            // The exact managed instance is wrapped (carrying its @Order + shared MDC/header
            // constants), not a copy.
            assertThat(registration.getFilter()).isSameAs(filter);
        }
    }
}
