package com.carddemo.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Web-layer configuration for the REST API: the CORS policy (consumed by Spring Security) and
 * Jackson JSON serialization customizations; global REST error handling is owned by
 * {@code com.carddemo.controller.GlobalExceptionHandler} (RFC 7807 ProblemDetail) and this class
 * intentionally registers no exception handler so framework 400/404 semantics are preserved.
 * No COBOL equivalent (BMS 3270 screens become REST/JSON); source commit {@code 27d6c6f}.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    /**
     * CORS source resolved by Spring Security's {@code .cors(Customizer.withDefaults())}; the bean
     * name must remain {@code corsConfigurationSource} for that lookup to apply this policy.
     *
     * @param allowedOrigins comma-separated origins from {@code carddemo.web.cors.allowed-origins}
     *                       (default {@code *})
     * @return the CORS configuration source registered for every path
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${carddemo.web.cors.allowed-origins:*}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);   // stateless JWT travels in the Authorization header, not cookies
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Tunes Boot's auto-configured {@code ObjectMapper} via a builder customizer (rather than
     * replacing it) so Boot's defaults and modules are retained. Null inclusion is left at the
     * Jackson default so null DTO fields remain present in the JSON contract.
     *
     * @return the Jackson builder customizer applied to Boot's {@code ObjectMapper}
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder.featuresToDisable(
                SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
