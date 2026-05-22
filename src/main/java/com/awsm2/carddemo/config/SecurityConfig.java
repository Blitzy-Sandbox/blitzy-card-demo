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
package com.awsm2.carddemo.config;

import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 configuration for the CardDemo Java target.
 *
 * <p>Per AAP &sect;0.7.1 ("Spring Security 6 + JWT + BCrypt + role-based
 * access (ADMIN/USER)") and AAP &sect;0.3.3 (Layered Architecture &mdash;
 * the security layer wraps the controller layer at the servlet filter
 * boundary), this configuration defines the single
 * {@link SecurityFilterChain} that protects every REST endpoint in
 * {@code com.awsm2.carddemo.controller}.</p>
 *
 * <h2>Replaces (AAP &sect;0.7.1)</h2>
 * <p>Replaces: RACF resource access control on z/OS + CICS sign-on
 * verification ({@code COSGN00C}) + the {@code USRSEC} VSAM cluster's
 * plaintext password lookup. In the Java target the equivalent flow is:</p>
 * <ol>
 *   <li>{@code AuthController.signin} (replacing {@code COSGN00C}) accepts
 *       credentials, verifies the BCrypt-hashed password against
 *       {@code UserSecurity} (via {@code SignonService} / {@code UserSecurityRepository}),
 *       and issues a JWT signed with the rotated key from Secrets Manager.</li>
 *   <li>The client presents the JWT on every subsequent request via the
 *       {@code Authorization: Bearer ...} header.</li>
 *   <li>The {@link JwtAuthenticationFilter} (registered below) extracts
 *       the JWT, validates signature and expiration via
 *       {@code JwtTokenProvider}, and populates the
 *       {@link org.springframework.security.core.context.SecurityContext}
 *       with a {@link org.springframework.security.authentication.UsernamePasswordAuthenticationToken}
 *       carrying the authenticated user ID and authorities.</li>
 *   <li>Subsequent filter chain elements enforce path-level and
 *       method-level access rules.</li>
 * </ol>
 *
 * <h2>Stateless session policy (AAP &sect;0.6.4, &sect;0.3.4)</h2>
 * <p>The filter chain runs with
 * {@link SessionCreationPolicy#STATELESS}: the server never creates a
 * {@code JSESSIONID} cookie or in-memory session. Every request carries
 * its own JWT; the principal is reconstructed from the token per request.
 * This replaces the CICS pseudo-conversational COMMAREA model from the
 * original source (per AAP &sect;0.1.1 "CICS pseudo-conversational
 * COMMAREA state (COCOM01Y.cpy) is replaced by stateless REST with JWT").</p>
 *
 * <h2>Role mapping (AAP &sect;0.4.1)</h2>
 * <p>The COBOL {@code SEC-USR-TYPE} field (CSUSR01Y.cpy:L17,
 * {@code PIC X(01)} with values {@code A}/{@code U}) maps directly to
 * Spring Security authorities:</p>
 * <ul>
 *   <li>{@code A} (admin) &rarr; {@code ROLE_ADMIN}</li>
 *   <li>{@code U} (regular user) &rarr; {@code ROLE_USER}</li>
 * </ul>
 * <p>{@code /api/admin/**} endpoints require {@code ROLE_ADMIN};
 * {@code /api/**} endpoints (non-admin) require either role. Public
 * endpoints (auth, health, OpenAPI docs) are unauthenticated.</p>
 *
 * <h2>CSRF policy</h2>
 * <p>CSRF protection is DISABLED for the stateless API paths because
 * (a) the API uses JWT bearer tokens, not session cookies, so the classic
 * CSRF attack vector does not apply, and (b) cross-origin JSON requests
 * with an {@code Authorization} header trigger a CORS preflight that the
 * ALB CORS configuration must explicitly allow. Per OWASP guidance, CSRF
 * protection is only meaningful for cookie-authenticated origins.</p>
 *
 * @see com.awsm2.carddemo.security.JwtTokenProvider
 * @see com.awsm2.carddemo.security.JwtAuthenticationFilter
 * @see com.awsm2.carddemo.security.BCryptPasswordEncoderBean
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Path patterns exempt from authentication. Centralised here so the
     * list is the single source of truth and the JWT filter can skip
     * processing for the same set of paths.
     */
    static final String[] PUBLIC_PATTERNS = new String[] {
            // Authentication endpoints (signon, refresh) — must be reachable
            // without a token (the user has none yet).
            "/api/auth/**",
            // Spring Boot Actuator health probes — required by ECS task
            // health checks (AAP §0.7.2).
            "/actuator/health/**",
            "/actuator/info",
            // OpenAPI / Swagger UI — gated by profile in production
            // (springdoc.api-docs.enabled property), but the filter chain
            // exempts them so the gating is at the bean level rather than
            // at security.
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api-docs/**"
    };

    /**
     * The single {@link SecurityFilterChain} for the application.
     *
     * <p>The chain composition is:</p>
     * <ol>
     *   <li>{@link JwtAuthenticationFilter} (registered before
     *       {@link UsernamePasswordAuthenticationFilter}) &mdash; extracts
     *       and validates the JWT, populates the security context.</li>
     *   <li>{@link UsernamePasswordAuthenticationFilter} (the default
     *       Spring Security filter, retained for any non-JWT flow that
     *       might be added later for operational tooling).</li>
     *   <li>Authorization rules &mdash; path-based and role-based.</li>
     * </ol>
     *
     * @param http                  Spring Security's fluent builder
     * @param jwtAuthenticationFilter the JWT pre-filter bean from
     *                              {@code com.awsm2.carddemo.security}
     * @return the configured filter chain
     * @throws Exception if the builder fails to assemble the chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {

        // Replaces: RACF resource access control + CICS sign-on enforcement.
        LOG.info("Configuring stateless JWT SecurityFilterChain — "
                + "ROLE_ADMIN for /api/admin/**, ROLE_USER/ROLE_ADMIN for /api/**");

        http
                // CSRF is irrelevant for stateless bearer-token APIs (see
                // class-level Javadoc for OWASP rationale).
                .csrf(csrf -> csrf.disable())

                // Disable the built-in HTTP Basic auth challenge — the API
                // expects JWT bearer tokens exclusively.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())

                // Stateless: no JSESSIONID cookies, no HttpSession storage.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ----------------------------------------------------------------
                // Authorization rules
                // ----------------------------------------------------------------
                .authorizeHttpRequests(auth -> auth
                        // OPTIONS preflight requests are always allowed so
                        // CORS works for SPA clients hitting the API.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Public endpoints from PUBLIC_PATTERNS.
                        .requestMatchers(PUBLIC_PATTERNS).permitAll()
                        // Admin endpoints — only ROLE_ADMIN (A-type users).
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // All other /api/** endpoints — either role.
                        .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                        // Every remaining path requires authentication; the
                        // default rule deliberately denies anonymous access
                        // so a forgotten matcher cannot accidentally expose
                        // a new endpoint to the public Internet.
                        .anyRequest().authenticated())

                // ----------------------------------------------------------------
                // Pre-filter — extract and validate the JWT before Spring's
                // default UsernamePasswordAuthenticationFilter sees the request.
                // ----------------------------------------------------------------
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
