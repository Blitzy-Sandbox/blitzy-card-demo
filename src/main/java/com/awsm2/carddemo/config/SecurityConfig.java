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

import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 6 configuration for the CardDemo Java target.
 *
 * <p>Wires Spring Security 6 with JWT bearer-token authentication and
 * role-based authorization per AAP &sect;0.4.1 and &sect;0.7.1. This is
 * the central security infrastructure of the application: every REST
 * endpoint in {@code com.awsm2.carddemo.controller} passes through the
 * single {@link SecurityFilterChain} defined here before reaching its
 * handler method.</p>
 *
 * <h2>Replaces (AAP &sect;0.1.1, &sect;0.7.1, &sect;0.7.3)</h2>
 * <p>Replaces three z/OS mainframe constructs that collectively performed
 * authentication, authorization and identity propagation:</p>
 * <ul>
 *   <li><b>CICS-managed signon program {@code COSGN00C}</b>
 *       ({@code app/cbl/COSGN00C.cbl}, Tran-ID {@code CC00}) &mdash; the
 *       COBOL program read the {@code USRSEC} VSAM cluster to validate
 *       signon credentials. In the Java target the equivalent flow is
 *       {@code POST /api/auth/signin} (
 *       {@code AuthController.signin} &rarr; {@code SignonService}) which
 *       verifies the BCrypt-hashed password from
 *       {@code UserSecurityRepository} and issues a JWT signed with the
 *       rotated key from AWS Secrets Manager.</li>
 *   <li><b>VSAM {@code USRSEC} plaintext password storage</b>
 *       ({@code app/cpy/CSUSR01Y.cpy:L21 SEC-USR-PWD PIC X(08)}) &mdash;
 *       now BCrypt-hashed (strength 12) by
 *       {@code com.awsm2.carddemo.security.BCryptPasswordEncoderBean}. The
 *       {@code PasswordEncoder} bean is intentionally <em>not</em>
 *       redefined in this configuration class to preserve the
 *       single-responsibility separation mandated by AAP &sect;0.7.1
 *       (Minimal Change Clause). The encoder is consumed by
 *       {@code SignonService}.</li>
 *   <li><b>RACF resource-level access control</b> on z/OS &mdash; now
 *       expressed as URL-based authorization rules in
 *       {@link #securityFilterChain(HttpSecurity)} plus method-level
 *       {@code @PreAuthorize} annotations enabled by
 *       {@link EnableMethodSecurity}. The COBOL {@code SEC-USR-TYPE}
 *       field ({@code CSUSR01Y.cpy:L22 PIC X(01)}, values {@code 'A'}
 *       for admin / anything else for regular user) is mapped to Spring
 *       Security authorities by
 *       {@link JwtAuthenticationFilter}: {@code 'A'} &rarr;
 *       {@code ROLE_ADMIN}; otherwise &rarr; {@code ROLE_USER}.</li>
 * </ul>
 *
 * <h2>Stateless session policy (AAP &sect;0.1.1, &sect;0.3.4)</h2>
 * <p>The filter chain runs with
 * {@link SessionCreationPolicy#STATELESS}: the server never creates a
 * {@code JSESSIONID} cookie or in-memory HTTP session. Every request
 * carries its own JWT in the {@code Authorization} header; the principal
 * is reconstructed from the token on every request. This replaces the
 * CICS pseudo-conversational COMMAREA state model from the original
 * source (per AAP &sect;0.1.1: "CICS pseudo-conversational COMMAREA
 * state (COCOM01Y.cpy) is replaced by stateless REST with JWT").</p>
 *
 * <h2>CSRF policy</h2>
 * <p>CSRF protection is disabled because (a) the API authenticates with
 * JWT bearer tokens rather than session cookies, so the classical CSRF
 * attack vector (a browser auto-attaching a session cookie to a
 * cross-origin POST) does not apply, and (b) cross-origin JSON requests
 * with an {@code Authorization} header trigger a CORS preflight that
 * the {@link #corsConfigurationSource()} below explicitly governs. Per
 * OWASP guidance, CSRF protection is only meaningful for
 * cookie-authenticated origins.</p>
 *
 * <h2>What this class deliberately does NOT do (AAP &sect;0.7.1 Minimal Change Clause)</h2>
 * <ul>
 *   <li>It does not define a {@code PasswordEncoder} bean &mdash; that is
 *       owned by {@code BCryptPasswordEncoderBean} in the
 *       {@code security/} package so the encoder can be injected
 *       wherever needed without dragging in the security filter chain.</li>
 *   <li>It does not load the JWT signing key &mdash; that is owned by
 *       {@code JwtTokenProvider} in the {@code security/} package which
 *       fetches the key from Secrets Manager via
 *       {@code SecretsManagerService}.</li>
 *   <li>It does not contain business logic &mdash; per AAP &sect;0.7.1
 *       this class wires beans only.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.security.JwtAuthenticationFilter
 * @see com.awsm2.carddemo.security.JwtTokenProvider
 * @see com.awsm2.carddemo.security.BCryptPasswordEncoderBean
 * @see com.awsm2.carddemo.controller.AuthController
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = false, jsr250Enabled = false)
public class SecurityConfig {

    // -----------------------------------------------------------------
    // CORS configuration properties — externalised via Spring's
    // property resolution so each profile (local / dev / prod) can
    // override origins/methods/headers without recompiling. AAP §0.7.1
    // requires all environment-specific values to be externalised.
    // -----------------------------------------------------------------

    /**
     * Comma-separated list of allowed CORS origins (for example,
     * {@code "https://app.example.com,https://admin.example.com"}). Defaults
     * to {@code "*"} for local-development convenience &mdash; production
     * profiles MUST override with an explicit origin list.
     */
    @Value("${carddemo.security.cors.allowed-origins:*}")
    private String allowedOrigins;

    /**
     * Comma-separated list of allowed CORS HTTP methods. The default
     * covers every verb in AAP &sect;0.3.4's REST endpoint inventory
     * (GET, POST, PUT, DELETE) plus {@code OPTIONS} for browser
     * preflight requests.
     */
    @Value("${carddemo.security.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethods;

    /**
     * Comma-separated list of allowed CORS request headers. The defaults
     * cover the {@code Authorization} header (required for JWT),
     * {@code Content-Type} (required for JSON request bodies),
     * {@code X-Requested-With} (commonly sent by browser-side
     * frameworks), and {@code Accept} (required for content negotiation).
     */
    @Value("${carddemo.security.cors.allowed-headers:Authorization,Content-Type,X-Requested-With,Accept}")
    private String allowedHeaders;

    /**
     * CORS preflight {@code Access-Control-Max-Age} (in seconds) &mdash;
     * the browser will cache the preflight response for this duration
     * before re-issuing an {@code OPTIONS} request. One hour
     * ({@code 3600}) is the default; production deployments may extend
     * this to reduce preflight overhead.
     */
    @Value("${carddemo.security.cors.max-age:3600}")
    private long corsMaxAge;

    // -----------------------------------------------------------------
    // Collaborator beans — JwtAuthenticationFilter is constructor-injected
    // per AAP §0.3.3 (constructor injection for all collaborators).
    // -----------------------------------------------------------------

    /**
     * The {@link JwtAuthenticationFilter} bean registered in the
     * security filter chain. Performs JWT extraction + validation on
     * every request and establishes the
     * {@link org.springframework.security.core.context.SecurityContext}.
     * Final so the field is guaranteed to be visible to all servlet
     * container worker threads after Spring publishes the bean.
     */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Application-wide Jackson mapper used to serialize authentication and
     * authorization failures that occur inside Spring Security's filter chain.
     *
     * <p>Those failures never reach {@code GlobalExceptionHandler} because
     * Spring Security's {@code ExceptionTranslationFilter} handles them before
     * Spring MVC dispatch. Injecting the same {@link ObjectMapper} used by MVC
     * keeps the wire shape identical to controller-level
     * {@link ApiResponse} errors.</p>
     */
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection of the JWT authentication filter, per AAP
     * &sect;0.3.3 (Dependency Injection &mdash; constructor injection
     * for all {@code @Component} beans). Field injection via
     * {@code @Autowired} is deliberately not used.
     *
     * <p>Replaces: {@code WS-AUTH-FLAG} working-storage variable in
     * {@code COSGN00C.cbl} L40-L42 (declared and toggled at signon to
     * indicate whether the caller had successfully authenticated). In
     * the Java target the equivalent gate is the JWT signature check
     * performed by the injected filter on every request.</p>
     *
     * @param jwtAuthenticationFilter the JWT pre-filter from the
     *                                {@code security/} package; Spring's
     *                                bean factory enforces non-null
     *                                injection so an explicit
     *                                {@code Objects.requireNonNull} is
     *                                unnecessary
     * @param objectMapper            application Jackson mapper used to
     *                                write the standard JSON envelope for
     *                                filter-chain authentication failures
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------
    // SecurityFilterChain — the central HTTP security definition
    // -----------------------------------------------------------------

    /**
     * Configures the single HTTP {@link SecurityFilterChain} that
     * protects every endpoint in the application, mapped from the AAP
     * &sect;0.3.4 REST endpoint inventory.
     *
     * <h4>Public endpoints (no authentication required)</h4>
     * <ul>
     *   <li>{@code POST /api/auth/signin} &mdash; issues a JWT;
     *       replaces {@code COSGN00C} signon transaction (Tran-ID
     *       {@code CC00}).</li>
     *   <li>{@code GET /actuator/health}, {@code /actuator/health/**},
     *       {@code /actuator/info} &mdash; health and liveness probes
     *       consumed by ECS task health checks and ALB target group
     *       health checks per AAP &sect;0.7.2 operational
     *       requirements.</li>
     *   <li>{@code GET /v3/api-docs}, {@code /v3/api-docs/**},
     *       {@code /v3/api-docs.yaml}, {@code /swagger-ui},
     *       {@code /swagger-ui/**}, {@code /swagger-ui.html} &mdash;
     *       OpenAPI docs (JSON + YAML variants + Swagger UI).
     *       Production deployments gate these by setting
     *       {@code springdoc.api-docs.enabled=false} and
     *       {@code springdoc.swagger-ui.enabled=false} so the endpoints
     *       are not even registered.</li>
     * </ul>
     *
     * <h4>Admin-only endpoints (require {@code ROLE_ADMIN})</h4>
     * <ul>
     *   <li>{@code /api/admin/**} &mdash; user administration
     *       endpoints from {@code COUSR00C} through {@code COUSR03C}.</li>
     *   <li>{@code GET /api/menu/admin} &mdash; admin menu from
     *       {@code COADM01C}.</li>
     * </ul>
     *
     * <h4>Authenticated endpoints</h4>
     * <p>All other {@code /api/**} endpoints require an authenticated
     * principal with either {@code ROLE_USER} or {@code ROLE_ADMIN}.</p>
     *
     * <p>The non-probe Actuator endpoints (e.g.
     * {@code /actuator/metrics}, {@code /actuator/metrics/**},
     * {@code /actuator/prometheus}, {@code /actuator/env}) also require an
     * authenticated principal with either {@code ROLE_USER} or
     * {@code ROLE_ADMIN}. This honours AAP &sect;0.7.2 (PCI-DSS &sect;10.1
     * &mdash; limit access to system component metrics to authenticated
     * users) and matches the documented behaviour in
     * {@code docs/project-guide.md} &sect;9.6 (Observability Access).
     * QA Final Checkpoint 12 Issue 5: previously
     * {@code /actuator/metrics} fell through to {@code denyAll()},
     * producing {@code 403 Forbidden} even for authenticated callers and
     * contradicting the documentation.</p>
     *
     * <h4>Default deny</h4>
     * <p>{@code anyRequest().denyAll()} ensures any URL not explicitly
     * permitted is denied. This is the secure-by-default posture
     * mandated by AAP &sect;0.7.2 (PCI-DSS). A new endpoint will be
     * inaccessible until it is explicitly authorised here, preventing
     * accidental exposure.</p>
     *
     * <h4>Filter ordering</h4>
     * <p>{@link JwtAuthenticationFilter} is registered BEFORE
     * {@link UsernamePasswordAuthenticationFilter} so that JWT-based
     * authentication takes precedence over any form-based flow.</p>
     *
     * <h4>Method-level authorization</h4>
     * <p>{@link EnableMethodSecurity} with {@code prePostEnabled = true}
     * is declared on the class so individual controller methods may
     * additionally use {@code @PreAuthorize}.</p>
     *
     * @param http the Spring Security fluent {@link HttpSecurity}
     *             builder; must not be {@code null}
     * @return the assembled {@link SecurityFilterChain} bean published
     *         to Spring Boot's auto-configuration
     * @throws Exception if any builder step fails; the framework wraps
     *                   this and surfaces it as a {@code BeanCreationException}
     *                   during context startup
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Replaces: RACF resource access control + CICS DFHRSRC profiles.
        // In the source mainframe system, transaction- and resource-level
        // access was enforced by RACF before the CICS dispatcher
        // ever invoked the program. Here the equivalent gate is the
        // .authorizeHttpRequests(...) stage of the Spring Security
        // filter chain.
        http
                // -------------------------------------------------------
                // CSRF: disabled for stateless bearer-token APIs (see
                // class-level Javadoc for OWASP rationale). NEVER
                // re-enable without first switching to cookie-based
                // authentication and re-introducing the CSRF token in
                // every state-changing form / fetch request.
                // -------------------------------------------------------
                .csrf(csrf -> csrf.disable())

                // -------------------------------------------------------
                // CORS: drive from the externalised configuration source
                // built by corsConfigurationSource() below.
                // -------------------------------------------------------
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // -------------------------------------------------------
                // Session: STATELESS. No JSESSIONID cookie, no
                // server-side HttpSession. Every request is
                // independently authenticated from the JWT.
                // -------------------------------------------------------
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // -------------------------------------------------------
                // Authorization: ordered matchers from most-specific to
                // least-specific. Spring Security evaluates matchers in
                // declared order so the admin pattern MUST come before
                // the generic /api/** pattern.
                // -------------------------------------------------------
                .authorizeHttpRequests(authz -> authz
                        // Public endpoints — no authentication required.
                        // Replaces: COSGN00C entry point (the only CICS
                        // transaction that was reachable before signon).
                        .requestMatchers("/api/auth/signin").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                // JSON variant (canonical springdoc path)
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                // YAML variant — springdoc serves the
                                // same spec as YAML at .yaml suffix; the
                                // /** matcher above does NOT match a path
                                // segment that contains a dot, so the
                                // .yaml endpoint must be permitted
                                // explicitly. QA Final Checkpoint 12,
                                // Issue 13: previously /v3/api-docs.yaml
                                // returned 401 because only the JSON
                                // path matchers were registered.
                                "/v3/api-docs.yaml",
                                "/swagger-ui",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()

                        // Authenticated Actuator endpoints — non-probe
                        // operational endpoints (metrics, prometheus
                        // scrape target, environment) require an
                        // authenticated USER/ADMIN. Replaces: implicit
                        // SDSF/CEMT operator-only access on the
                        // mainframe (only RACF-authenticated operators
                        // could SDSF.D or CEMT INQ resources). QA Final
                        // Checkpoint 12, Issue 5: the docs at
                        // docs/project-guide.md §9.6 explicitly promise
                        // that `curl -H "Authorization: Bearer $TOKEN"
                        // .../actuator/metrics` works for authenticated
                        // USER/ADMIN; without this matcher the request
                        // fell through to .anyRequest().denyAll() and
                        // returned 403 Forbidden, contradicting both
                        // the documentation and the AAP §0.7.2
                        // observability requirement that metrics be
                        // operationally accessible.
                        .requestMatchers(HttpMethod.GET,
                                "/actuator/metrics",
                                "/actuator/metrics/**",
                                "/actuator/prometheus",
                                "/actuator/env",
                                "/actuator/env/**",
                                "/actuator/loggers",
                                "/actuator/loggers/**",
                                "/actuator/configprops",
                                "/actuator/configprops/**",
                                "/actuator/beans",
                                "/actuator/mappings",
                                "/actuator/conditions",
                                "/actuator/scheduledtasks",
                                "/actuator/threaddump",
                                "/actuator/heapdump",
                                "/actuator/httpexchanges",
                                "/actuator/caches",
                                "/actuator/caches/**"
                        ).hasAnyRole("USER", "ADMIN")

                        // Admin-only endpoints — replaces RACF
                        // SECLABEL=ADMIN profile + CSUSR01Y.cpy
                        // SEC-USR-TYPE='A' check in COSGN00C L230-L240.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/menu/admin").hasRole("ADMIN")

                        // All other /api/** endpoints — either role.
                        // Replaces: implicit "any signed-on user" check
                        // in COSGN00C — once authentication succeeded
                        // every CICS transaction was reachable.
                        .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")

                        // Default-deny: every URL not explicitly
                        // permitted is rejected.
                        .anyRequest().denyAll()
                )

                // -------------------------------------------------------
                // Insert the JWT filter BEFORE Spring's default
                // UsernamePasswordAuthenticationFilter so that JWT-based
                // identity establishment runs before any form / basic
                // auth processing.
                // -------------------------------------------------------
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)

                // -------------------------------------------------------
                // Exception translation: Spring Security handles
                // unauthenticated requests before Spring MVC can delegate
                // to GlobalExceptionHandler. Register a REST entry point
                // so missing/invalid credentials produce HTTP 401 with
                // the same ApiResponse JSON envelope as MVC errors.
                //
                // QA CR-03: reserve HTTP 403 for authenticated callers
                // that lack a required role; unauthenticated callers must
                // receive REST-conventional HTTP 401.
                // QA CR-08/CR-12: permitting all methods on the signon
                // path lets Spring MVC return 405 Method Not Allowed for
                // unsupported methods instead of Security short-circuiting
                // them as 403.
                // -------------------------------------------------------
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(restAuthenticationEntryPoint())
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeSecurityError(
                                        response,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN",
                                        "Access denied")))

                // -------------------------------------------------------
                // Disable HTTP basic, form login and logout endpoints —
                // the only authentication mechanism is the JWT issued by
                // POST /api/auth/signin.
                // -------------------------------------------------------
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }

    /**
     * Spring Security {@link AuthenticationEntryPoint} that converts
     * unauthenticated access attempts into the CardDemo standard JSON
     * envelope.
     *
     * <p><b>COBOL provenance:</b> Replaces the pre-transaction signon gate in
     * {@code COSGN00C}. In the CICS source, a caller without a valid signon
     * context could not proceed to protected transactions. In REST, the
     * equivalent condition is an absent, malformed, expired, or otherwise
     * unauthenticated JWT, surfaced as HTTP {@code 401 Unauthorized}.</p>
     *
     * @return authentication entry point emitting HTTP 401 and
     *         {@link ApiResponse#error(String, String)}
     */
    @Bean
    public AuthenticationEntryPoint restAuthenticationEntryPoint() {
        return (request, response, authException) ->
                writeSecurityError(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "UNAUTHORIZED",
                        "Authentication required");
    }

    /**
     * Writes a Spring-Security-layer error as the same {@link ApiResponse}
     * envelope used by {@code GlobalExceptionHandler}.
     *
     * <p>This helper deliberately does not include {@code authException} or
     * {@code accessDeniedException} messages in the response body. Security
     * exception messages can contain implementation details (filter names,
     * matcher internals, token parsing failures), so the wire response stays
     * generic per AAP &sect;0.7.2 no-information-disclosure guidance.</p>
     *
     * @param response servlet response to write
     * @param status   HTTP status code
     * @param code     API error code
     * @param message  generic client-facing message
     * @throws IOException if the servlet output stream cannot be written
     */
    private void writeSecurityError(HttpServletResponse response,
                                    int status,
                                    String code,
                                    String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }

    // -----------------------------------------------------------------
    // AuthenticationManager — exposed for SignonService
    // -----------------------------------------------------------------

    /**
     * Exposes Spring's auto-configured {@link AuthenticationManager} as
     * a bean. {@code SignonService} consumes this bean to invoke
     * {@code AuthenticationManager.authenticate(UsernamePasswordAuthenticationToken)}
     * during the {@code POST /api/auth/signin} flow.
     *
     * <p>Replaces: {@code WS-AUTH-FLAG} + manual VSAM {@code USRSEC}
     * lookup pattern in {@code COSGN00C.cbl} L40-L42 and the
     * surrounding READ logic at L211-L219. In the COBOL program the
     * signon paragraph performed an explicit {@code EXEC CICS READ
     * DATASET(USRSEC) ... RIDFLD(WS-USER-ID) ... INTO(SEC-USER-DATA)}
     * and then compared {@code SEC-USR-PWD} to {@code WS-USER-PWD}.
     * Spring Security's {@link AuthenticationManager} is the equivalent
     * abstraction in the Java target &mdash; it delegates to a
     * {@code DaoAuthenticationProvider} configured with the
     * {@code UserDetailsService} backed by
     * {@code UserSecurityRepository}, and uses
     * {@code BCryptPasswordEncoder} for password verification.</p>
     *
     * @param authConfig the Spring Boot auto-configured
     *                   {@link AuthenticationConfiguration}; must not
     *                   be {@code null}
     * @return the {@link AuthenticationManager} bean
     * @throws Exception if {@link AuthenticationConfiguration#getAuthenticationManager()}
     *                   fails to resolve the manager &mdash; this is
     *                   exceptionally rare and indicates a misconfigured
     *                   Spring Security context, in which case the
     *                   framework will fail-fast at startup
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig)
            throws Exception {
        // Replaces: signon password-compare logic in COSGN00C.cbl
        // PROCESS-ENTER-KEY paragraph (L108-L140). Returns the framework
        // manager which delegates verification to the configured
        // PasswordEncoder + UserDetailsService chain.
        return authConfig.getAuthenticationManager();
    }

    // -----------------------------------------------------------------
    // CORS configuration source — private helper consumed by
    // securityFilterChain(HttpSecurity) above. Kept private so the
    // public surface of this class is exactly the three members
    // declared in the AAP exports schema.
    // -----------------------------------------------------------------

    /**
     * Builds the {@link CorsConfigurationSource} that drives the
     * {@code .cors(...)} HttpSecurity DSL above. The source maps the
     * configuration to the path pattern {@code /api/**} so the policy
     * applies only to the REST API surface &mdash; the static OpenAPI
     * docs and Actuator endpoints, which are same-origin in normal
     * deployments, do not receive a CORS configuration.
     *
     * <p>Replaces: no source equivalent. CORS is an HTTP/REST concern
     * absent in the CICS world where the 3270 terminal and the
     * application ran in a single-host environment with no
     * cross-origin notion.</p>
     *
     * <p>The {@link CorsConfiguration#setAllowCredentials(Boolean)
     * allowCredentials} flag is intentionally set to {@code false}
     * because the API authenticates with the {@code Authorization}
     * header (JWT), never with cookies. Setting it to {@code true}
     * would (a) be unnecessary for this auth mode and (b) preclude the
     * use of {@code "*"} in the allowed-origins list, complicating
     * local development.</p>
     *
     * @return the configured {@link CorsConfigurationSource}; never
     *         {@code null}
     */
    private CorsConfigurationSource corsConfigurationSource() {
        // Replaces: no source equivalent — CORS is a browser/HTTP
        // concept absent from CICS / 3270.
        CorsConfiguration config = new CorsConfiguration();

        // Trim each comma-separated entry so that human-friendly
        // whitespace in environment overrides does not silently
        // produce an unmatched origin like " https://app.example.com".
        config.setAllowedOrigins(Arrays.asList(splitAndTrim(allowedOrigins)));
        config.setAllowedMethods(Arrays.asList(splitAndTrim(allowedMethods)));
        config.setAllowedHeaders(Arrays.asList(splitAndTrim(allowedHeaders)));

        // JWT is carried in the Authorization header, not cookies, so
        // credentials are not required. Keep this as false — see
        // method Javadoc for the rationale and security implications.
        config.setAllowCredentials(false);
        config.setMaxAge(corsMaxAge);

        // Expose Authorization and Content-Type so browser clients can
        // read these response headers in cross-origin requests (e.g.,
        // to capture a refreshed JWT or to inspect content negotiation
        // results from the server).
        config.setExposedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // -----------------------------------------------------------------
    // Internal helpers — kept package-private for unit testability.
    // -----------------------------------------------------------------

    /**
     * Defensive split-and-trim helper used by
     * {@link #corsConfigurationSource()} to convert a comma-separated
     * {@code @Value} string into a clean array of tokens with
     * surrounding whitespace removed and empty segments filtered out.
     *
     * <p>This guards against three classes of operational defect that
     * surface as opaque CORS failures in production:</p>
     * <ul>
     *   <li>Trailing commas in environment overrides (
     *       {@code "https://a.example.com,"}).</li>
     *   <li>Human-friendly whitespace (
     *       {@code "https://a.example.com, https://b.example.com"}).</li>
     *   <li>Accidental empty strings from missing properties.</li>
     * </ul>
     *
     * <p>Using {@link Customizer} as the lambda base type elsewhere in
     * this class is a Spring Security convenience; this helper is a
     * plain {@code String[]} normaliser with no Spring dependency.</p>
     *
     * @param csv the comma-separated value string; may be empty
     *            (the method returns a single-element array containing
     *            the empty string in that case, which the
     *            {@link CorsConfiguration} setters treat as a no-op)
     * @return an array of trimmed, non-blank tokens
     */
    private static String[] splitAndTrim(String csv) {
        // No source equivalent — pure Java I/O hygiene.
        if (csv == null || csv.isEmpty()) {
            return new String[]{""};
        }
        String[] raw = csv.split(",");
        // Java 17+ stream API would produce the same result but keeping
        // it as an explicit array operation makes the contract obvious
        // to readers familiar with COBOL where strings are
        // fixed-length, character-oriented and have no built-in split.
        int count = 0;
        for (int i = 0; i < raw.length; i++) {
            raw[i] = raw[i].trim();
            if (!raw[i].isEmpty()) {
                count++;
            }
        }
        if (count == raw.length) {
            return raw;
        }
        String[] cleaned = new String[count];
        int j = 0;
        for (String value : raw) {
            if (!value.isEmpty()) {
                cleaned[j++] = value;
            }
        }
        return cleaned;
    }

    // -----------------------------------------------------------------
    // Customizer reference — documentation aid only.
    //
    // The Customizer<?> functional interface is the base type of every
    // lambda passed to the HttpSecurity DSL methods used above
    // (csrf, cors, sessionManagement, authorizeHttpRequests, httpBasic,
    // formLogin, logout). The import keeps the type visible to readers
    // and to IDE tooling; the lambdas above are themselves
    // Customizer<?> instances inferred by the compiler from the DSL
    // method signatures.
    // -----------------------------------------------------------------
    @SuppressWarnings("unused")
    private static final Class<?> CUSTOMIZER_FUNCTIONAL_INTERFACE_REF = Customizer.class;
}
