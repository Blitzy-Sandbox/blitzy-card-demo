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

import com.aws.carddemo.security.SessionTokenRegistry;
import com.aws.carddemo.security.TokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for the CardDemo COBOL-to-Java migration.
 *
 * <p>Provides the security infrastructure beans the migrated application
 * requires for its full context to refresh successfully under the canonical
 * Spring Boot 3.x auto-configuration model AND to enforce
 * authentication / authorisation on the migrated REST API surface:
 *
 * <ul>
 *   <li>{@link PasswordEncoder} (BCrypt) — required by
 *       {@code AuthenticationService}, {@code UserAddService}, and
 *       {@code UserUpdateService} to encode/verify passwords at insert / update
 *       time. The BCrypt strength is the Spring Security default ({@code 10}).</li>
 *   <li>{@link SecurityFilterChain} — installs the
 *       {@link TokenAuthenticationFilter} early in the filter chain so the
 *       Bearer-token {@code Authorization} header issued by
 *       {@code AuthController.signOn} produces a populated
 *       {@code SecurityContext} on every protected request, and applies the
 *       canonical authorisation policy: {@code /api/auth/**} is permit-all
 *       (so anonymous sign-on is possible), {@code /actuator/health} is
 *       permit-all (so platform health probes work without credentials), and
 *       every other endpoint requires authentication.</li>
 * </ul>
 *
 * <h2>Method-Level Security (the AAP §0.10.2 Minimal Change Clause-compliant
 * enabler for {@code @PreAuthorize})</h2>
 *
 * <p>The {@code @EnableMethodSecurity(prePostEnabled = true)} annotation
 * activates Spring Security's {@code @PreAuthorize} / {@code @PostAuthorize}
 * support. The migrated controllers
 * ({@code MenuController.getAdminMenu / postAdminDispatch} and
 * {@code UserAdminController.listUsers / createUser / updateUser / deleteUser})
 * carry {@code @PreAuthorize("hasRole('ADMIN')")} annotations as the
 * Java-idiomatic replacement for the COBOL
 * {@code IF CDEMO-USRTYP-ADMIN ... ELSE ... END-IF} role-gate pattern in
 * {@code COADM01C.cbl} and the {@code COUSR0*C.cbl} programs. Without this
 * annotation those guards are silent no-ops and admin-only endpoints become
 * accessible to regular users — the {@code AdminUserManagementE2ETest} and
 * {@code GateVerificationE2ETest} 403-Forbidden expectations would never be
 * met.
 *
 * <h2>Filter Chain Authorisation Policy</h2>
 *
 * <pre>
 *   /api/auth/**               -&gt; permit anonymous (sign-on entry point)
 *   /actuator/health           -&gt; permit anonymous (platform health probe)
 *   /api/**                    -&gt; require authentication (Bearer token)
 *   anything else              -&gt; permit anonymous
 * </pre>
 *
 * <p>The "anything else" fall-through is intentional: the migration owns the
 * {@code /api/} URL space, not the entire servlet container. Static-resource
 * URLs (e.g., {@code /favicon.ico}, {@code /error}) that Spring Boot serves
 * unconditionally remain accessible.
 *
 * <h2>Stateless Session and CSRF</h2>
 *
 * <p>The chain disables CSRF protection ({@code .csrf(...disable())}) and
 * declares {@link SessionCreationPolicy#STATELESS} session creation. Both
 * follow from the token-transport model documented on
 * {@link com.aws.carddemo.dto.auth.UserSession} and
 * {@link com.aws.carddemo.security.SessionTokenRegistry}: the migrated REST
 * API authenticates each request via the
 * {@link TokenAuthenticationFilter}-resolved Bearer token, never via a
 * server-allocated {@code HttpSession} / {@code JSESSIONID} cookie. CSRF
 * tokens defend against state-changing requests forged by a third-party
 * site that piggybacks on the browser's cookie auth; since CardDemo issues
 * no cookies, that attack surface does not exist.
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
 * the Java equivalent is the {@link TokenAuthenticationFilter}-driven
 * per-request principal resolution against the
 * {@link com.aws.carddemo.security.SessionTokenRegistry}.
 *
 * <h2>Test Profile Interaction</h2>
 *
 * <p>The integration tests under {@code src/test/java/com/aws/carddemo/} run
 * with the {@code test} Spring profile (set by
 * {@code @ActiveProfiles("test")} on the IT base classes). The test profile's
 * {@code application-test.properties} excludes
 * {@code UserDetailsServiceAutoConfiguration} so Spring Boot does not create
 * the default in-memory user (which would emit a generated password on the
 * console). With that exclusion, the {@link PasswordEncoder} bean below is
 * the ONLY {@code PasswordEncoder} in the context, which makes the
 * {@code AuthenticationService} constructor's {@code PasswordEncoder}
 * parameter resolve unambiguously.
 *
 * <p>Controller-slice tests ({@code @WebMvcTest}) either disable filters via
 * {@code @AutoConfigureMockMvc(addFilters = false)} (the
 * {@code AuthControllerTest} pattern) or supply a permissive
 * {@code SecurityTestConfig} via {@code @Import(SecurityTestConfig.class)}
 * (the other seven controller test pattern) so this production filter chain
 * does not interfere with slice-level assertions. End-to-end tests under
 * {@code com.aws.carddemo.e2e} sign on through {@code POST /api/auth/sign-on}
 * to obtain a Bearer token and present it as
 * {@code Authorization: Bearer &lt;token&gt;} on subsequent requests, exercising
 * the production filter chain as written.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.4 ("SecurityConfig with BCryptPasswordEncoder bean"), §0.10.5
 * ("No plaintext credentials in any configuration file"), §0.10.7 ("Framework
 * Constraint &mdash; Spring Boot 3.x"). The QA report for Checkpoint 11
 * named this class as a missing production-side prerequisite for the four
 * {@code @Disabled} E2E test classes (the @Disabled message strings enumerate
 * {@code @EnableMethodSecurity} wiring and the Spring Security filter-chain
 * population of {@code MenuController#getMainMenu}'s {@code Authentication}
 * parameter as the items the testing-flavor agent cannot land).
 *
 * <h2>Minimal Change Clause (AAP §0.10.2)</h2>
 *
 * <p>This class carries ONLY the security infrastructure strictly required to
 * unblock the four E2E test classes:
 *
 * <ul>
 *   <li>{@code @EnableMethodSecurity(prePostEnabled = true)} — activates the
 *       {@code @PreAuthorize} guards already present on the migrated
 *       controllers.</li>
 *   <li>{@link TokenAuthenticationFilter} registration — resolves the Bearer
 *       token issued at sign-on into a populated {@code SecurityContext}.</li>
 *   <li>{@code authorizeHttpRequests} policy — protects the {@code /api/**}
 *       URL space behind authentication except the sign-on endpoint itself.</li>
 * </ul>
 *
 * <p>No CSRF customisation, no CORS configuration, no OAuth2 wiring, no JWT
 * issuer / decoder, no custom {@code AuthenticationProvider},
 * no {@code UserDetailsService} &mdash; all of those belong in subsequent
 * migration steps and are not required by the QA findings being addressed.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
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
     * Returns the in-memory {@link SessionTokenRegistry} that binds an opaque
     * Bearer token (UUID string) to the authenticated principal it represents.
     *
     * <p>The registry is declared as a {@code @Bean} on this configuration
     * class rather than annotated {@code @Component} on its own type so it is
     * NOT picked up by {@code @WebMvcTest} component scans (which would
     * otherwise force every controller slice test to supply a stub). End-to-
     * end tests load this bean via the full {@code @SpringBootTest} context;
     * the {@code AuthController}'s {@code @WebMvcTest} slice provides its own
     * {@code @MockBean SessionTokenRegistry} so it can pin the minted token
     * to a deterministic value.
     *
     * @return a fresh {@link SessionTokenRegistry}
     * @see com.aws.carddemo.controller.AuthController#signOn(com.aws.carddemo.dto.auth.AuthenticationRequest)
     */
    @Bean
    public SessionTokenRegistry sessionTokenRegistry() {
        return new SessionTokenRegistry();
    }

    /**
     * Returns the {@link TokenAuthenticationFilter} that resolves
     * {@code Authorization: Bearer &lt;token&gt;} credentials against the
     * {@link SessionTokenRegistry} and populates {@code SecurityContextHolder}
     * with a {@code UsernamePasswordAuthenticationToken} carrying the
     * principal's userId and the role authority mapped from the COBOL
     * {@code SEC-USR-TYPE} value.
     *
     * <p>The filter is declared as a {@code @Bean} on this configuration
     * class rather than annotated {@code @Component} on its own type so it is
     * NOT picked up by {@code @WebMvcTest} component scans (which would
     * otherwise discover it as a {@link jakarta.servlet.Filter} and fail
     * because the registry dependency would be missing in the slice).
     *
     * @param sessionTokenRegistry the in-memory token store (constructor-
     *                             injected from the {@link #sessionTokenRegistry()}
     *                             bean above)
     * @return the configured {@link TokenAuthenticationFilter}
     */
    @Bean
    public TokenAuthenticationFilter tokenAuthenticationFilter(SessionTokenRegistry sessionTokenRegistry) {
        return new TokenAuthenticationFilter(sessionTokenRegistry);
    }

    /**
     * Returns the {@link SecurityFilterChain} that enforces authentication on
     * the {@code /api/**} URL space (except the {@code /api/auth/**} sign-on
     * entry point) and dispatches Bearer-token credentials through the
     * {@link TokenAuthenticationFilter}.
     *
     * <p>The chain:
     * <ul>
     *   <li>installs the {@link TokenAuthenticationFilter} BEFORE Spring
     *       Security's
     *       {@link UsernamePasswordAuthenticationFilter} via
     *       {@code addFilterBefore(...)} so the Bearer-token resolution
     *       happens before any default form-login filter would otherwise
     *       intercept the request;</li>
     *   <li>declares the authorisation policy via {@code authorizeHttpRequests}:
     *       {@code /api/auth/**} and {@code /actuator/health} are permit-all,
     *       every other {@code /api/**} URL requires authentication, and
     *       everything else (static resources, the {@code /error} dispatch)
     *       is permit-all;</li>
     *   <li>disables CSRF protection
     *       ({@code .csrf(AbstractHttpConfigurer::disable)}) because the
     *       migrated REST API uses a token-transport model where the client
     *       presents an explicit {@code Authorization} header on every
     *       state-changing request — there is no session cookie for a
     *       cross-site request to silently piggyback on;</li>
     *   <li>declares stateless session creation
     *       ({@code .sessionManagement(...STATELESS)}) so Spring Security
     *       does not allocate {@code HttpSession} instances and the response
     *       never carries a {@code Set-Cookie: JSESSIONID=...} header (also
     *       enforced from the test side by
     *       {@code AuthControllerTest.signOn_neverIssuesSessionCookie_onAnyResponsePath}).</li>
     * </ul>
     *
     * @param http                       the {@link HttpSecurity} builder
     *                                   Spring Security auto-configures into
     *                                   the bean factory
     * @param tokenAuthenticationFilter  the Bearer-token resolution filter
     *                                   (a {@code @Component}, auto-resolved
     *                                   by constructor parameter injection)
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception when the {@link HttpSecurity} builder fails to
     *                   assemble the chain &mdash; propagates the
     *                   underlying Spring Security configuration error to
     *                   the caller so the context refresh fails loudly
     *                   instead of silently mis-configuring
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    TokenAuthenticationFilter tokenAuthenticationFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                // ----------------------------------------------------------------
                // AuthenticationEntryPoint — return HTTP 401 (NOT 403) on missing
                // or invalid credentials.
                //
                // Spring Security's default behaviour for an UNAUTHENTICATED
                // request that hits an .authenticated() rule is to delegate to
                // {@link org.springframework.security.web.access.AccessDeniedHandler}
                // which produces HTTP 403 Forbidden. That is incorrect for a
                // REST API: by convention HTTP 401 Unauthorized means "you have
                // not authenticated" and HTTP 403 means "you authenticated but
                // are not authorised". The {@code GateVerificationE2ETest}
                // suite explicitly asserts this distinction (Test 3.2 expects
                // 401 for missing auth across 18 parametric endpoint
                // invocations; Test 3.4 expects 403 for a regular user against
                // an admin endpoint).
                //
                // Installing an {@link HttpStatusEntryPoint} pinned at
                // {@link HttpStatus#UNAUTHORIZED} delivers the canonical REST
                // semantics: any request that reaches the security chain
                // without a valid {@code Authorization: Bearer <token>} header
                // (and is not on the {@code /api/auth/**} or
                // {@code /actuator/health} permit-list) receives HTTP 401, and
                // any request that authenticates but fails the
                // {@code @PreAuthorize} role gate receives the standard HTTP
                // 403 from the {@code AccessDeniedHandler} default — both via
                // a single line of declarative configuration.
                //
                // The entry point produces a body-less response (no JSON
                // envelope, no WWW-Authenticate challenge) — matching the
                // {@link HttpStatusEntryPoint}'s minimal contract and aligned
                // with AAP §0.10.5 ("No financial data written to logs"): a
                // 401 with an empty body cannot leak PCI/PII or any
                // application detail to an unauthenticated caller.
                // ----------------------------------------------------------------
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(tokenAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
