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
package com.carddemo.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.carddemo.service.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for the headless, stateless, JWT-secured REST
 * surface (the sign-in entry point is {@code POST /api/auth/signin}).
 *
 * <p>Two ordered {@link SecurityFilterChain} beans are defined: a first chain
 * scoped to the operational Actuator endpoints (served only on the isolated
 * management port), and the main chain that secures the public API. The API
 * chain has CSRF disabled (the API is token-based and holds no session or
 * cookie), CORS delegated to {@code WebConfig},
 * {@link SessionCreationPolicy#STATELESS} sessions, BCrypt password hashing, the
 * injected {@link JwtAuthenticationFilter} inserted ahead of
 * {@link UsernamePasswordAuthenticationFilter}, and role-based authorization
 * keyed on the {@code ROLE_ADMIN} and {@code ROLE_USER} authorities carried by
 * the bearer token. HTTP Basic and form login are disabled.</p>
 *
 * <p>Pre-controller authorization failures are rendered here as RFC 7807
 * {@code application/problem+json} responses ({@code 401} for an absent or
 * invalid token, {@code 403} for an authenticated caller lacking the required
 * role); the envelope mirrors the one produced by the application's central
 * {@code GlobalExceptionHandler} so clients parse one consistent error
 * contract.</p>
 *
 * <p>Operational actuator endpoints (info, metrics, prometheus) are served on
 * the isolated, unpublished management port and are never exposed on this public
 * port. The rationale for the stateless JWT model (replacing the CICS COMMAREA),
 * the BCrypt credential upgrade, and the management-port isolation is recorded in
 * {@code DECISION_LOG.md} (entries D-006, D-002, and D-033 respectively).</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Base URI for the problem {@code type} member; identical to {@code GlobalExceptionHandler}. */
    private static final String ERROR_TYPE_BASE = "https://carddemo/errors/";

    /** SLF4J MDC key holding the per-request correlation id, published by {@code CorrelationIdFilter}. */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** Member keys for the RFC 7807 problem body, in stable serialization order. */
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_DETAIL = "detail";
    private static final String FIELD_INSTANCE = "instance";
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_CORRELATION_ID = "correlationId";

    /** Problem metadata for an unauthenticated or invalid-token request (HTTP 401). */
    private static final String UNAUTHORIZED_TYPE_SLUG = "unauthorized";
    private static final String UNAUTHORIZED_TITLE = "Unauthorized";
    private static final String UNAUTHORIZED_DETAIL = "Authentication required or token invalid";

    /** Problem metadata for an authenticated caller lacking the required role (HTTP 403). */
    private static final String FORBIDDEN_TYPE_SLUG = "forbidden";
    private static final String FORBIDDEN_TITLE = "Forbidden";
    private static final String FORBIDDEN_DETAIL = "Access is denied";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    /**
     * Creates the security configuration with its collaborators.
     *
     * @param jwtAuthenticationFilter the per-request bearer-token filter, injected by its
     *                                concrete type so it is unambiguous with the other
     *                                {@code OncePerRequestFilter} components in the context;
     *                                never {@code null}
     * @param objectMapper            the Spring Boot auto-configured Jackson mapper used to
     *                                serialize the RFC 7807 error bodies; never {@code null}
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * Supplies the adaptive BCrypt password encoder used to hash and verify
     * credentials. (Rationale for the BCrypt credential model is in
     * {@code DECISION_LOG.md} entry D-002.)
     *
     * @return a {@link BCryptPasswordEncoder} at the default strength
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Exposes the framework-built {@link AuthenticationManager} so the sign-in service can
     * authenticate credentials. Using {@link AuthenticationConfiguration} is the Spring
     * Security 6 idiom for exposing the manager as an injectable bean.
     *
     * @param authenticationConfiguration the auto-configured authentication context
     * @return the shared authentication manager
     * @throws Exception if the manager cannot be obtained
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * Secures the operational Actuator endpoints, which are served exclusively on
     * the isolated management port ({@code management.server.port}, default 9091).
     *
     * <p>This chain is ordered ahead of {@link #securityFilterChain(HttpSecurity)}
     * and matches only the Actuator endpoints (via {@link EndpointRequest#toAnyEndpoint()}),
     * permitting them so that in-network monitoring can reach them: the Prometheus
     * scraper reads {@code /actuator/prometheus} and the container / orchestrator
     * liveness and readiness probes read {@code /actuator/health}. The management
     * port is deliberately not published outside the container network, so that
     * port boundary &mdash; not per-request authentication &mdash; is what keeps the
     * operational metadata off the public surface. On the public application port
     * these endpoints are not mapped at all, so they are never exposed there.</p>
     *
     * <p>Only the endpoints explicitly exposed by
     * {@code management.endpoints.web.exposure.include} (health, info, metrics,
     * prometheus) are reachable; all others remain unmapped. CSRF is disabled and
     * no session is created, consistent with the stateless API chain. See
     * {@code DECISION_LOG.md} entry D-033.</p>
     *
     * @param http the HTTP security builder
     * @return the Actuator/management filter chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Builds the stateless filter chain that secures the public REST API surface.
     *
     * <p>This chain handles every non-Actuator request (the Actuator endpoints are
     * matched first by {@link #actuatorSecurityFilterChain(HttpSecurity)}). CSRF is
     * disabled because the API is token-based and holds no session or cookie; CORS
     * delegates to the MVC configuration owned by {@code WebConfig}; sessions are
     * {@link SessionCreationPolicy#STATELESS}. Authorization is evaluated in
     * declaration order: the sign-in endpoints are open, the administrative
     * endpoints ({@code /api/admin/**} and the admin menu {@code /api/menu/admin})
     * require {@code ROLE_ADMIN}, and every other request requires a valid token.
     * The bearer-token filter runs before the username/password filter, and the
     * browser-oriented HTTP Basic and form-login mechanisms are disabled.</p>
     *
     * @param http the HTTP security builder
     * @return the configured filter chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public sign-in endpoints (legacy COSGN00C / transaction CC00).
                        .requestMatchers("/api/auth/**").permitAll()
                        // Administrative surface: user-management CRUD and the admin menu
                        // require ROLE_ADMIN (the legacy CDEMO-USRTYP-ADMIN user type).
                        .requestMatchers("/api/admin/**", "/api/menu/admin").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }

    /**
     * Renders a {@code 401 Unauthorized} RFC 7807 response when a request reaches a
     * protected route without valid authentication.
     *
     * @return the authentication entry point
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authenticationException) ->
                writeProblemResponse(request, response, HttpStatus.UNAUTHORIZED,
                        UNAUTHORIZED_TITLE, UNAUTHORIZED_DETAIL, UNAUTHORIZED_TYPE_SLUG);
    }

    /**
     * Renders a {@code 403 Forbidden} RFC 7807 response when an authenticated caller lacks
     * the role required for the requested resource.
     *
     * @return the access-denied handler
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeProblemResponse(request, response, HttpStatus.FORBIDDEN,
                        FORBIDDEN_TITLE, FORBIDDEN_DETAIL, FORBIDDEN_TYPE_SLUG);
    }

    /**
     * Writes a single, consistently shaped RFC 7807 {@code application/problem+json} body.
     *
     * <p>The members are emitted in a stable order ({@code type}, {@code title},
     * {@code status}, {@code detail}, {@code instance}, {@code timestamp}, and, when
     * present, {@code correlationId}) so the envelope matches the one produced by the
     * application's {@code GlobalExceptionHandler}. The detail text is fixed and never
     * echoes the underlying exception, so no sensitive information is leaked.</p>
     *
     * @param request  the request whose URI identifies the problem instance
     * @param response the response to populate with status, content type, and body
     * @param status   the HTTP status to set
     * @param title    the human-readable problem title
     * @param detail   the human-readable, non-sensitive problem detail
     * @param typeSlug the slug appended to {@link #ERROR_TYPE_BASE} to form the problem type
     * @throws IOException if the response body cannot be written
     */
    private void writeProblemResponse(HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String title, String detail, String typeSlug) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_TYPE, ERROR_TYPE_BASE + typeSlug);
        body.put(FIELD_TITLE, title);
        body.put(FIELD_STATUS, status.value());
        body.put(FIELD_DETAIL, detail);
        body.put(FIELD_INSTANCE, request.getRequestURI());
        body.put(FIELD_TIMESTAMP, OffsetDateTime.now().toString());
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            body.put(FIELD_CORRELATION_ID, correlationId);
        }

        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
