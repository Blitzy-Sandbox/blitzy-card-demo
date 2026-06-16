package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Unit tests for {@link WebConfig}.
 *
 * <p>Traceability (REFERENCE-ONLY; no COBOL equivalent &mdash; BMS 3270 screens become REST/JSON;
 * source commit {@code 27d6c6f}): verifies the CORS policy consumed by Spring Security and the
 * Jackson serialization customizations. Resides in {@code com.carddemo.config} to exercise the
 * package-private {@code @Bean} factory methods directly.</p>
 */
@DisplayName("WebConfig - CORS policy and Jackson customization")
class WebConfigTest {

    private final WebConfig config = new WebConfig();

    @Test
    @DisplayName("Wildcard origins produce a permissive '/**' CORS policy without credentials")
    void wildcardCors() {
        CorsConfigurationSource source = config.corsConfigurationSource("*");

        CorsConfiguration cfg =
                ((UrlBasedCorsConfigurationSource) source).getCorsConfigurations().get("/**");
        assertThat(cfg).isNotNull();
        assertThat(cfg.getAllowedOrigins()).containsExactly("*");
        assertThat(cfg.getAllowedMethods()).contains("GET", "POST", "PUT", "DELETE", "OPTIONS");
        assertThat(cfg.getAllowedHeaders()).containsExactly("*");
        assertThat(cfg.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("Comma-separated origins are split and trimmed")
    void specificOriginsTrimmed() {
        CorsConfigurationSource source =
                config.corsConfigurationSource("http://a.com, http://b.com ");

        CorsConfiguration cfg =
                ((UrlBasedCorsConfigurationSource) source).getCorsConfigurations().get("/**");
        assertThat(cfg.getAllowedOrigins()).containsExactly("http://a.com", "http://b.com");
    }

    @Test
    @DisplayName("Jackson customizer disables date-timestamps and fail-on-unknown-properties")
    void jacksonCustomizerDisablesFeatures() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();

        config.jacksonCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        assertThat(mapper.getSerializationConfig()
                .isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
        assertThat(mapper.getDeserializationConfig()
                .isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
    }
}
