package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.carddemo.observability.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Fast, DB-free unit test for {@link WebConfig}, the web-tier assembly of the headless REST
 * CardDemo migration. The suite pins the two behavioural contracts {@link WebConfig} exists to
 * guarantee, invoking the configuration's factory methods directly (plain {@code new WebConfig()})
 * with no Spring context, database, or {@code MockMvc} &mdash; so it runs in milliseconds and
 * compiles warning-free under {@code -Xlint:all} (Gate&nbsp;2) while contributing line coverage
 * toward the Gate&nbsp;8 (&ge;80%) JaCoCo threshold.
 *
 * <h2>Contract 1 &mdash; {@code BigDecimal} JSON fidelity (decimal precision, AAP&nbsp;&sect;0.8.2)</h2>
 * <p>Every COBOL {@code PIC S9(n)V99} / {@code COMP-3} monetary field maps to
 * {@link java.math.BigDecimal} with a matching scale, and {@code float}/{@code double} is
 * prohibited for money (goal&nbsp;G2). The decimal-fidelity anchor is
 * {@code app/cpy/CVACT01Y.cpy} (read-only @ commit SHA {@code 27d6c6f}), whose {@code ACCOUNT-RECORD}
 * models {@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
 * {@code ACCT-CURR-CYC-CREDIT}, and {@code ACCT-CURR-CYC-DEBIT} all as {@code PIC S9(10)V99}
 * (signed, scale&nbsp;2). {@link WebConfig#bigDecimalPlainCustomizer()} enables plain
 * ({@code toPlainString}) rendering so such values can never leak into scientific/E-notation in a
 * JSON response (for example {@code 1.23456789E+9}), preserving byte-faithful money semantics for
 * the interface-contract parity gates (Gates&nbsp;1 and&nbsp;5).</p>
 *
 * <h2>Contract 2 &mdash; the correlation-ID filter runs exactly once</h2>
 * <p>{@link CorrelationIdFilter} is a high-precedence {@code @Component}
 * {@code OncePerRequestFilter}. Spring Boot would auto-register it directly with the servlet
 * container, while {@code SecurityConfig} also positions the same bean inside the Spring Security
 * filter chain &mdash; two registrations, hence two executions per request. To make it run exactly
 * once, {@link WebConfig#correlationIdFilterRegistration(CorrelationIdFilter)} returns a
 * <em>disabled</em> {@link FilterRegistrationBean}, suppressing the redundant servlet-container
 * registration and leaving the security chain as the single execution site.</p>
 *
 * <p>Design rationale, alternatives, and risks live in {@code docs/decision-log.md}, not in these
 * comments (Explainability rule).</p>
 *
 * @see WebConfig
 * @see CorrelationIdFilter
 */
@DisplayName("WebConfig — plain-BigDecimal JSON + single correlation-ID filter registration")
class WebConfigTest {

    /**
     * Builds an {@link ObjectMapper} exactly as Spring Boot would in production: a bare
     * {@link Jackson2ObjectMapperBuilder} to which {@link WebConfig#bigDecimalPlainCustomizer()} is
     * applied via its {@code customize(...)} callback. This mirrors how Boot feeds registered
     * {@code Jackson2ObjectMapperBuilderCustomizer} beans into the auto-configured builder, so the
     * mapper under test carries precisely the {@code BigDecimal} behaviour the customizer
     * contributes &mdash; and nothing else this test would have to stub.
     *
     * @return an {@link ObjectMapper} whose {@link java.math.BigDecimal} output is plain notation
     */
    private static ObjectMapper mapperWithBigDecimalPlain() {
        final Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new WebConfig().bigDecimalPlainCustomizer().customize(builder);
        return builder.build();
    }

    /**
     * Verifies that the customizer forces {@link java.math.BigDecimal} values into plain decimal
     * notation, never scientific/E-notation, through both bare-value and object-graph serialization.
     *
     * <p>The assertions fall into three groups:</p>
     * <ol>
     *   <li><strong>Scale-2 money fidelity.</strong> Representative {@code PIC S9(10)V99} values
     *       ({@code 1234567890.00}, {@code 0.10}) serialize verbatim with their scale intact &mdash;
     *       trailing zeros are preserved and the decimal point is never dropped.</li>
     *   <li><strong>Discriminating guard.</strong> A {@link java.math.BigDecimal} whose natural
     *       {@link java.math.BigDecimal#toString()} is scientific notation (here the large balance
     *       {@code 1234567890.00} after {@link java.math.BigDecimal#stripTrailingZeros()}, which
     *       carries a negative scale and renders as {@code 1.23456789E+9}) MUST still serialize as
     *       the plain token {@code 1234567890}. This is the assertion that fails if
     *       {@code WRITE_BIGDECIMAL_AS_PLAIN} were not enabled &mdash; making the test a meaningful
     *       guard rather than a tautology, since Jackson falls back to {@code toString()} (scientific)
     *       without the feature. Negative-scale values arise routinely from arithmetic and
     *       normalization such as {@code stripTrailingZeros()}, exactly the path that would otherwise
     *       corrupt a money field in an API response.</li>
     *   <li><strong>Object-graph coverage.</strong> The same discriminating value carried on a
     *       record field proves the behaviour applies through ordinary POJO/record serialization,
     *       not only to a bare top-level number.</li>
     * </ol>
     *
     * <p>{@code writeValueAsString} throws the checked {@code JsonProcessingException}; the method
     * declares {@code throws Exception} (permitted by JUnit&nbsp;5) so no swallow-and-hide catch is
     * needed and the build stays warning-free.</p>
     *
     * @throws Exception if JSON serialization fails (fails the test cleanly)
     */
    @Test
    @DisplayName("BigDecimal serializes in plain notation (never scientific), scale preserved")
    void bigDecimalSerializesPlainNotScientific() throws Exception {
        final ObjectMapper mapper = mapperWithBigDecimalPlain();

        // (1) Scale-2 money fidelity: a large balance and a small amount keep their exact scale.
        //     These pass regardless of the feature (their toString() is already plain) and document
        //     the PIC S9(10)V99 -> BigDecimal(scale 2) contract from app/cpy/CVACT01Y.cpy.
        assertThat(mapper.writeValueAsString(new BigDecimal("1234567890.00")))
                .isEqualTo("1234567890.00");
        assertThat(mapper.writeValueAsString(new BigDecimal("0.10")))
                .isEqualTo("0.10");

        // (2) Discriminating guard: stripTrailingZeros() gives this large balance a NEGATIVE scale,
        //     so its natural rendering is scientific ("1.23456789E+9"). The feature must convert it
        //     to the plain token "1234567890". Without WRITE_BIGDECIMAL_AS_PLAIN this assertion FAILS.
        final BigDecimal scientificByDefault = new BigDecimal("1234567890.00").stripTrailingZeros();
        assertThat(scientificByDefault.toString())
                .as("precondition: the raw value renders in scientific notation by default")
                .contains("E");
        final String bareJson = mapper.writeValueAsString(scientificByDefault);
        assertThat(bareJson)
                .isEqualTo("1234567890")
                .doesNotContain("E")
                .doesNotContain("e");

        // (3) Object-graph coverage: the same discriminating value on a record field must also emit
        //     plain notation, proving the customizer applies through normal object serialization.
        //     The exact-equality assertion is the definitive guard here: were the value rendered in
        //     scientific notation the string would be {"balance":1.23456789E+9}, which is unequal to
        //     the expected plain form. (A letter-based doesNotContain check is deliberately NOT used
        //     on keyed JSON, since the field name "balance" itself legitimately contains an 'e'.)
        final String objectJson = mapper.writeValueAsString(new AccountBalance(scientificByDefault));
        assertThat(objectJson).isEqualTo("{\"balance\":1234567890}");
    }

    /**
     * Verifies that {@link WebConfig#correlationIdFilterRegistration(CorrelationIdFilter)} returns a
     * <em>disabled</em> {@link FilterRegistrationBean} wrapping the injected filter instance.
     *
     * <p>A disabled registration means the auto-detected {@code @Component}
     * {@code OncePerRequestFilter} is NOT also registered as a standalone servlet filter;
     * {@code SecurityConfig} inserts the very same bean into the security chain instead, guaranteeing
     * a single execution per request (the filter's {@code OncePerRequestFilter} once-guard is the
     * defensive backstop). Asserting {@code getFilter()} is the same instance proves the container's
     * managed singleton &mdash; carrying its {@code @Order(HIGHEST_PRECEDENCE)} and the shared
     * {@code correlationId} MDC contract &mdash; is the one being wrapped, not a fresh copy.</p>
     */
    @Test
    @DisplayName("CorrelationIdFilter servlet auto-registration is disabled (registered exactly once)")
    void correlationIdFilterServletRegistrationDisabled() {
        final CorrelationIdFilter filter = new CorrelationIdFilter();

        final FilterRegistrationBean<CorrelationIdFilter> registration =
                new WebConfig().correlationIdFilterRegistration(filter);

        assertThat(registration)
                .as("the configuration must contribute a servlet registration for the filter")
                .isNotNull();
        assertThat(registration.isEnabled())
                .as("servlet-container registration must be disabled to prevent double execution")
                .isFalse();
        assertThat(registration.getFilter())
                .as("the disabled registration must wrap the injected @Component singleton")
                .isSameAs(filter);
    }

    /**
     * Minimal record used solely to prove that the plain-{@link BigDecimal} behaviour applies through
     * ordinary object serialization (not just to a bare top-level number). The single component is
     * named after {@code ACCT-CURR-BAL} from {@code app/cpy/CVACT01Y.cpy}; Jackson (record-aware)
     * serializes it under the JSON property {@code "balance"}.
     *
     * @param balance a monetary balance modelled as a scale-bearing {@link BigDecimal}
     */
    private record AccountBalance(BigDecimal balance) {
    }
}
