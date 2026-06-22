package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Unit tests for {@link WebConfig}: the CORS policy consumed by Spring Security
 * and the Jackson {@code ObjectMapper} customizations. Lives in the
 * {@code com.carddemo.config} package so the package-private {@code @Bean}
 * factory methods are accessible.
 */
class WebConfigTest {

    private final WebConfig webConfig = new WebConfig();

    @Test
    @DisplayName("wildcard origin yields a permissive, credential-free CORS policy on /**")
    void corsWildcardOrigin() {
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) webConfig.corsConfigurationSource("*");
        CorsConfiguration config = source.getCorsConfigurations().get("/**");

        assertThat(config).isNotNull();
        assertThat(config.getAllowedOrigins()).containsExactly("*");
        assertThat(config.getAllowedMethods())
                .containsExactly("GET", "POST", "PUT", "DELETE", "OPTIONS");
        assertThat(config.getAllowedHeaders()).containsExactly("*");
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("comma-separated origins are split and trimmed")
    void corsMultipleOrigins() {
        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource)
                webConfig.corsConfigurationSource("https://a.example.com, https://b.example.com");
        CorsConfiguration config = source.getCorsConfigurations().get("/**");

        assertThat(config.getAllowedOrigins())
                .containsExactly("https://a.example.com", "https://b.example.com");
    }

    @Test
    @DisplayName("blank origin string filters out to an empty origin list")
    void corsBlankOrigin() {
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) webConfig.corsConfigurationSource("");
        CorsConfiguration config = source.getCorsConfigurations().get("/**");

        assertThat(config.getAllowedOrigins()).isEmpty();
    }

    @Test
    @DisplayName("Jackson customizer disables timestamp dates and enables strict unknown-property handling")
    void jacksonCustomizer() {
        Jackson2ObjectMapperBuilderCustomizer customizer = webConfig.jacksonCustomizer();
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        customizer.customize(builder);
        ObjectMapper mapper = builder.build();

        // Dates serialize as ISO-8601 strings, not numeric timestamps.
        assertThat(mapper.getSerializationConfig().isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS))
                .isFalse();
        // Unknown request properties are rejected. This must be explicitly ENABLED: the
        // Jackson2ObjectMapperBuilder disables FAIL_ON_UNKNOWN_PROPERTIES by default, so the customizer
        // calling featuresToEnable(...) is what makes a stray body field (e.g. an `amount` sent to the
        // full-balance bill-payment endpoint) fail deserialization with a 400 instead of being dropped.
        assertThat(mapper.getDeserializationConfig().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .isTrue();
    }
}
