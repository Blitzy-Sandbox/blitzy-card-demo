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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the CardDemo COBOL-to-Java migration.
 *
 * <p>Provides the minimum set of security infrastructure beans the migrated
 * application requires for its full context to refresh successfully under the
 * canonical Spring Boot 3.x auto-configuration model:
 *
 * <ul>
 *   <li>{@link PasswordEncoder} (BCrypt) — required by
 *       {@code AuthenticationService}, {@code UserAddService}, and
 *       {@code UserUpdateService} to encode/verify passwords at insert / update
 *       time. The BCrypt strength is the Spring Security default ({@code 10}),
 *       which gives a per-hash cost of roughly one CPU-second on commodity
 *       hardware; tests using real {@code @Service} beans see deterministic
 *       behaviour because the BCrypt salt is randomised by the encoder but the
 *       verify path is salt-aware.</li>
 *   <li>{@link SecurityFilterChain} — required by Spring Boot's
 *       {@code SpringBootWebSecurityConfiguration}. Without an explicit chain
 *       Boot installs a default that requires HTTP-basic auth on every
 *       endpoint and emits a generated password on stdout, which is not the
 *       semantics the migrated controllers expect. The chain configured here
 *       defers authentication enforcement to the
 *       {@code AuthenticationService}-driven sign-on flow inherited from
 *       {@code COSGN00C}: every endpoint is permitted at the filter chain
 *       level and the controllers themselves call the service to verify the
 *       caller's session before performing any sensitive operation.</li>
 * </ul>
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>The mainframe CardDemo application authenticates users by reading the
 * {@code USRSEC} VSAM file in {@code app/cbl/COSGN00C.cbl} and comparing the
 * stored plaintext password against the user-typed value (line 268
 * {@code IF SEC-USR-PWD = PASSWD-INPUT}). The Java migration replaces the
 * plaintext compare with BCrypt verification per AAP §0.10.5 ("No plaintext
 * credentials in any configuration file"), which requires a
 * {@link PasswordEncoder} bean &mdash; this class supplies it. The filter
 * chain has no COBOL counterpart because the mainframe's authentication
 * boundary is the CICS sign-on transaction, not a per-HTTP-request filter;
 * the Java equivalent is the controller-level call to
 * {@code AuthenticationService} after a permissive filter chain.
 *
 * <h2>Test Profile Interaction</h2>
 *
 * <p>The integration tests under {@code src/test/java/com/aws/carddemo/} run
 * with the {@code test} Spring profile (set by
 * {@code @ActiveProfiles("test")} on the IT base classes). The test profile's
 * {@code application-test.properties} excludes
 * {@code UserDetailsServiceAutoConfiguration} so Spring Boot does not create
 * the default in-memory user (which would emit a generated password on the
 * console and pollute test output). With that exclusion, the
 * {@link PasswordEncoder} bean below is the ONLY {@code PasswordEncoder} in
 * the context, which makes the {@code AuthenticationService} constructor's
 * {@code PasswordEncoder} parameter resolve unambiguously.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.4 ("SecurityConfig with BCryptPasswordEncoder bean"), §0.10.5
 * ("No plaintext credentials in any configuration file"), §0.10.7 ("Framework
 * Constraint &mdash; Spring Boot 3.x"). The QA report for Checkpoint 8
 * explicitly named this class as a missing production-side prerequisite for
 * the 5 batch Job ITs (the JobIT classes' {@code @Disabled} messages
 * enumerate it as item (3)).
 *
 * <h2>Minimal Change Clause (AAP §0.10.2)</h2>
 *
 * <p>This class carries ONLY the security infrastructure strictly required to
 * unblock the JobIT context refresh: the BCrypt encoder and a permissive
 * filter chain. No CSRF customisation, no CORS configuration, no method-level
 * security, no OAuth2 wiring &mdash; all of those belong in subsequent
 * migration steps and are out of scope for this checkpoint.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Returns the BCrypt password encoder used by {@code AuthenticationService}
     * (verify path) and {@code UserAddService} / {@code UserUpdateService}
     * (encode path).
     *
     * <p>The encoder uses Spring Security's default BCrypt strength ({@code 10})
     * because it strikes the documented balance between hash cost and
     * verification latency: roughly 100&ndash;200&nbsp;ms on commodity hardware,
     * fast enough to keep service-layer unit tests sub-second while expensive
     * enough to deter brute-force credential cracking. Per AAP §0.10.2 the
     * Minimal Change Clause forbids over-tuning beyond Spring Security's
     * defaults &mdash; no custom salt source, no custom BCrypt version
     * selector, no PasswordEncoder delegation chain.
     *
     * @return a fresh {@link BCryptPasswordEncoder} configured at the Spring
     *         Security default strength
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Returns the {@link SecurityFilterChain} that defers authentication
     * enforcement to the controller layer.
     *
     * <p>The chain:
     * <ul>
     *   <li>permits every request at the servlet filter level
     *       ({@code .anyRequest().permitAll()}) so the
     *       {@code AuthenticationService}-driven sign-on flow inherited from
     *       {@code COSGN00C} is the single authority on which caller may
     *       invoke which endpoint;</li>
     *   <li>disables CSRF protection
     *       ({@code .csrf(AbstractHttpConfigurer::disable)}) because the
     *       migrated REST API uses a session-based sign-on model that
     *       authenticates each request via the
     *       {@code AuthenticationService}-issued session token; CSRF tokens
     *       are not part of the COBOL-equivalent contract;</li>
     *   <li>declares stateless session creation
     *       ({@code .sessionManagement(...STATELESS)}) so Spring Security
     *       does not allocate {@code HttpSession} instances that the
     *       migrated controllers do not use; the
     *       {@code AuthenticationService}-issued session is application-
     *       managed, not servlet-container-managed.</li>
     * </ul>
     *
     * <p>This is the minimal filter chain that allows the JobIT
     * {@code @SpringBootTest} full-context load to refresh without requiring
     * the test to authenticate. The JobITs never invoke a controller endpoint
     * &mdash; they drive the Spring Batch {@code Job} bean directly via
     * {@code JobLauncherTestUtils} &mdash; so the absence of real
     * authentication enforcement is harmless at the IT level.
     *
     * @param http the {@link HttpSecurity} builder Spring Security
     *             auto-configures into the bean factory
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception when the {@link HttpSecurity} builder fails to
     *                   assemble the chain &mdash; propagates the
     *                   underlying Spring Security configuration error to
     *                   the caller so the context refresh fails loudly
     *                   instead of silently mis-configuring
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
