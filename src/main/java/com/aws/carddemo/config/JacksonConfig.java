/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson configuration for the CardDemo migration that enforces
 * {@link java.math.BigDecimal} scale parity through the entire JSON pipeline,
 * including the {@link com.fasterxml.jackson.databind.JsonNode}-based tree
 * model used by integration-test {@code RestTemplate} response parsing.
 *
 * <h2>Why this customizer is required (AAP §0.10.3 + Jackson tree-mapper
 * trailing-zero stripping)</h2>
 *
 * <p>The COBOL {@code PIC S9(09)V99} contract on monetary fields
 * ({@code TRAN-AMT}, account balances, credit limits, etc.) maps to
 * {@code BigDecimal} with {@code scale=2}. AAP §0.10.3 mandates that this
 * scale be preserved at every boundary — the in-memory JPA entity, the
 * service DTO, the JSON wire format, and the test-side deserialised
 * representation.
 *
 * <p>Jackson 2.x's tree mapper ({@link com.fasterxml.jackson.databind.node.JsonNode})
 * has a default behaviour that <strong>strips trailing zeros</strong> from
 * BigDecimal-valued JSON numbers when building
 * {@link com.fasterxml.jackson.databind.node.DecimalNode} instances via
 * {@code readTree(...)}. The behaviour is gated by
 * {@link JsonNodeFactory#willStripTrailingBigDecimalZeroes()} which returns
 * {@code true} for the default factory (the inverse of {@code _cfgBigDecimalExact}).
 * As a result a payload {@code {"amount":100.50}} deserialises to
 * {@code DecimalNode(BigDecimal("100.5"))} with {@code scale=1} — silently
 * violating the scale contract on every assertion that reads the tree.
 *
 * <p>The E2E test {@code OnlineTransactionE2ETest.step4_viewTransaction_...}
 * verifies the scale via {@code body.get("amount").asText()} → {@code new
 * BigDecimal(...).scale() == 2}. Without this customizer that assertion
 * fails because {@code asText()} returns {@code "100.5"} for a default
 * {@code DecimalNode} — the trailing zero has been irreversibly dropped
 * during tree construction.
 *
 * <h2>What this customizer changes</h2>
 *
 * <ul>
 *   <li><strong>{@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS}</strong>
 *       — pins the {@link com.fasterxml.jackson.core.JsonParser}'s numeric
 *       token resolution to {@link java.math.BigDecimal} rather than the
 *       default {@link Double}. Without it, the parser path can collapse
 *       {@code 100.50} into a {@code Double} which loses precision before
 *       the tree node is built.</li>
 *   <li><strong>{@link JsonNodeFactory#withExactBigDecimals(boolean)
 *       JsonNodeFactory.withExactBigDecimals(true)}</strong>
 *       — flips the {@code _cfgBigDecimalExact} flag on the factory so the
 *       {@code DecimalNode} stores the BigDecimal verbatim (with its source
 *       scale) instead of normalising via {@code stripTrailingZeros}.</li>
 * </ul>
 *
 * <p>Both settings are required together: {@code USE_BIG_DECIMAL_FOR_FLOATS}
 * ensures the {@link java.lang.Number} that reaches the node factory is a
 * {@code BigDecimal} (preserving scale at the parser level); the
 * exact-{@code BigDecimal} factory then preserves that scale through to the
 * {@code DecimalNode}. Setting only one of the two has no effect on the
 * end-to-end behaviour — empirically verified against
 * {@code com.fasterxml.jackson:jackson-databind:2.17.x}.
 *
 * <h2>What this customizer does NOT change</h2>
 *
 * <ul>
 *   <li>Spring MVC {@code @ResponseBody} serialisation — already preserves
 *       scale via {@link com.fasterxml.jackson.core.JsonGenerator#writeNumber(java.math.BigDecimal)}
 *       which uses {@code BigDecimal.toString} (scale-preserving for
 *       financial-magnitude values).</li>
 *   <li>Spring MVC {@code @RequestBody} deserialisation into a typed object
 *       (DTO or {@code record}) with a {@code BigDecimal} field — already
 *       preserves scale through the {@code JsonParser#getDecimalValue()}
 *       path which is independent of the tree-mapper trailing-zero
 *       behaviour.</li>
 *   <li>Number-shape contract — JSON numbers remain JSON numbers; this
 *       customizer does not switch any DTO field to JSON string. Tests that
 *       assert {@code amount.isNumber()} (E2E) and tests that assert the
 *       raw JSON literal contains {@code 100.50} (controller slice) both
 *       observe a JSON number.</li>
 *   <li>Other Jackson features — {@code FAIL_ON_UNKNOWN_PROPERTIES},
 *       {@code WRITE_DATES_AS_TIMESTAMPS}, the JavaTimeModule registration,
 *       and every other auto-configured setting from
 *       {@code spring-boot-autoconfigure}'s {@code JacksonAutoConfiguration}
 *       remain untouched. The customizer is strictly additive.</li>
 * </ul>
 *
 * <h2>Why this lives in {@code config} (Minimal Change Clause)</h2>
 *
 * <p>AAP §0.10.2 forbids introducing patterns beyond what the migration
 * requires. This class carries the smallest possible change that bridges
 * the Jackson tree-mapper behaviour gap exposed by AAP §0.10.3 +
 * {@code OnlineTransactionE2ETest}'s scale assertion: a single
 * {@link Jackson2ObjectMapperBuilderCustomizer} {@code @Bean} that piggybacks
 * on Spring Boot's already-active {@code JacksonAutoConfiguration} rather
 * than overriding the {@code ObjectMapper} bean wholesale or registering a
 * custom {@code HttpMessageConverter}.
 *
 * <h2>Test profile interaction</h2>
 *
 * <p>The {@code test} Spring profile (active by default under
 * {@code @SpringBootTest}, {@code @WebMvcTest}, {@code @DataJpaTest})
 * inherits this customizer because no profile-specific Jackson bean
 * overrides it. The unit-level {@code @WebMvcTest} slice tests
 * (e.g., {@code TransactionControllerTest}) share the same auto-configured
 * {@code ObjectMapper} via Spring Boot's test-slice autowiring; the
 * full-context E2E tests (e.g., {@code OnlineTransactionE2ETest}) use
 * {@code TestRestTemplate} which routes through the same converters. Both
 * tiers observe scale-preserving behaviour.
 *
 * @see com.aws.carddemo.controller.TransactionController.TransactionDetailJsonResponse
 * @see com.aws.carddemo.controller.TransactionController.TransactionSummary
 */
@Configuration
public class JacksonConfig {

    /**
     * Returns a {@link Jackson2ObjectMapperBuilderCustomizer} that wires
     * BigDecimal scale-preservation into the Spring-Boot-managed
     * {@code ObjectMapper}.
     *
     * <p>The customizer is invoked once during
     * {@code JacksonAutoConfiguration}'s {@code Jackson2ObjectMapperBuilder}
     * post-processing — Spring discovers it as a {@code @Bean} and applies
     * its lambda to the in-flight builder before {@code build()} is
     * invoked. The auto-configured {@code ObjectMapper} bean and every
     * {@code HttpMessageConverter} that consumes it (including the one
     * inside {@link org.springframework.boot.test.web.client.TestRestTemplate})
     * inherit the new settings transparently.
     *
     * <p>The {@code postConfigurer} hook is the documented Spring Boot
     * mechanism for properties that the builder DSL does not expose
     * directly — the
     * {@link com.fasterxml.jackson.databind.ObjectMapper#setNodeFactory(JsonNodeFactory)}
     * setter falls into that category because the {@code JsonNodeFactory}
     * is constructor-injected on the {@code ObjectMapper}, not on the
     * builder. The post-configurer runs after the builder has produced the
     * mapper instance, so the override has effect from the very first JSON
     * read/write operation.
     *
     * @return the customizer; never {@code null}
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalExactScaleCustomizer() {
        return builder -> {
            // -----------------------------------------------------------
            // (1) Parser-level setting: pin floating-point JSON numbers to
            // BigDecimal so the JsonNode tree-mapper sees a BigDecimal
            // (not a Double) when it constructs the DecimalNode. Without
            // this, the parser collapses "100.50" to Double 100.5 BEFORE
            // the node factory is consulted — at which point the trailing
            // zero is already lost and no factory tweak can recover it.
            // -----------------------------------------------------------
            builder.featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

            // -----------------------------------------------------------
            // (2) Node-factory setting: switch to the exact-BigDecimals
            // node factory so DecimalNode stores the BigDecimal with its
            // source scale instead of stripping trailing zeros via
            // BigDecimal.stripTrailingZeros() (the default behaviour
            // controlled by JsonNodeFactory#willStripTrailingBigDecimalZeroes
            // which returns true unless _cfgBigDecimalExact is true).
            //
            // postConfigurer is the canonical Spring Boot hook for
            // ObjectMapper customisations that the Jackson2ObjectMapperBuilder
            // DSL does not surface directly — the JsonNodeFactory is set
            // via ObjectMapper#setNodeFactory(JsonNodeFactory) which is an
            // instance-level setter on the mapper, not on the builder.
            // -----------------------------------------------------------
            builder.postConfigurer(objectMapper ->
                    objectMapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true)));
        };
    }
}
