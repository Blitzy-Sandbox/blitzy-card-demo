package com.carddemo.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Web-layer configuration supplying the CORS policy (consumed by Spring Security) and the Jackson
 * JSON customizations; REST error handling is delegated to {@code GlobalExceptionHandler}. No COBOL
 * equivalent (BMS 3270 screens become REST/JSON); source system referenced by SHA {@code 27d6c6f}.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    /**
     * Supplies the CORS configuration consumed by Spring Security.
     *
     * <p>The bean is named exactly {@code corsConfigurationSource} because Spring Security's
     * {@code .cors(Customizer.withDefaults())} resolves a {@link CorsConfigurationSource} bean by
     * that conventional name; renaming it would silently disable CORS. Allowed origins are read from
     * the {@code carddemo.web.cors.allowed-origins} property (comma-separated, trimmed, default
     * {@code *}); that key is declared in {@code application.yml} under {@code carddemo.web.cors} and
     * is overridable per environment via the {@code CORS_ALLOWED_ORIGINS} env var. Credentials are not
     * allowed because the stateless JWT is carried in the {@code Authorization} header rather than in
     * cookies.
     *
     * @param allowedOrigins comma-separated list of permitted origins (defaults to {@code *})
     * @return a URL-based CORS source applying the policy to every path
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
        config.setAllowCredentials(false); // stateless JWT travels in the Authorization header, not cookies
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Customizes Boot's auto-configured {@code ObjectMapper} so {@code java.time} values serialize as
     * ISO-8601 strings and unknown request properties are ignored during deserialization. Null
     * inclusion is intentionally left at Boot's default so null DTO fields remain present in JSON.
     *
     * <p>Global REST error handling is owned by {@code com.carddemo.controller.GlobalExceptionHandler}
     * (RFC 7807 ProblemDetail); this configuration intentionally adds no exception handler so that
     * framework 400/404 semantics are preserved.
     *
     * @return a customizer disabling timestamp date serialization and strict unknown-property handling
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder.featuresToDisable(
                SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
