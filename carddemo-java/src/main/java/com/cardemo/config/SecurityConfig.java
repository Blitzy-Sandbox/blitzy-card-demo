package com.cardemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.cardemo.security.TokenAuthenticationFilter;
import com.cardemo.security.TokenService;

/**
 * Foundational Spring Security configuration for the greenfield Java 25 LTS + Spring Boot 3.5.11
 * migration of the AWS CardDemo COBOL/CICS mainframe application.
 *
 * <p>This {@code @Configuration} is the single home of the application's HTTP security posture and
 * of the BCrypt {@link PasswordEncoder} that realizes the <strong>only permitted behavioral
 * change</strong> of the entire migration. It is component-scanned by {@code CardDemoApplication}
 * (base package {@code com.cardemo}, decision <strong>D-006</strong>), which deliberately defines no
 * security configuration of its own and delegates all of it here.</p>
 *
 * <h2>Migration provenance (AAP &sect;0.7.2)</h2>
 * <p>Behaviour is translated from the frozen AWS CardDemo COBOL estate at commit SHA
 * {@code 27d6c6f}. The COBOL source is <em>never copied</em> into this repository; only its
 * observable behaviour is reproduced. The legacy behavioural context for this file is:</p>
 * <ul>
 *   <li>{@code app/cbl/COSGN00C.cbl} (sign-on) &mdash; reads the {@code USRSEC} dataset by user id
 *       and performs the <em>plaintext</em> comparison {@code IF SEC-USR-PWD = WS-USER-PWD}, then
 *       routes an admin to {@code COADM01C} and a regular user to {@code COMEN01C}.</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} (add user) &mdash; moves the <em>plaintext</em> {@code PASSWDI}
 *       into {@code SEC-USR-PWD} and issues {@code EXEC CICS WRITE USRSEC}.</li>
 * </ul>
 *
 * <h2>Technology substitutions (AAP &sect;0.7.1 &mdash; documented at the point of change)</h2>
 * <dl>
 *   <dt>Mainframe security concept &rarr; Spring Security</dt>
 *   <dd>CardDemo authenticates against a <strong>file-based {@code USRSEC} store</strong> (now the
 *       {@code UserSecurity} JPA entity) and <strong>not RACF</strong>. The file-based model is
 *       preserved: this class supplies only the framework beans (the password encoder and the
 *       servlet filter chain). The actual read + verify + token-issue logic lives in
 *       {@code service/auth/AuthenticationService} (&larr; {@code COSGN00C}), keeping the login-flow
 *       semantics intact while honouring the layered architecture.</dd>
 *
 *   <dt>Plaintext {@code USRSEC} password &rarr; BCrypt (constraint C-003 / decision D-002)</dt>
 *   <dd>The COBOL plaintext password compare and store are upgraded to BCrypt hashing &mdash; the
 *       single security improvement explicitly permitted within scope. It is isolated here in
 *       {@code SecurityConfig} so exactly one hashing policy is shared by every consumer.</dd>
 *
 *   <dt>CICS pseudo-conversational state ({@code RETURN TRANSID COMMAREA}) &rarr; stateless REST</dt>
 *   <dd>The legacy {@code COMMAREA} that carried conversational state between 3270 turns has no
 *       server-side analogue here: the filter chain holds <strong>no</strong> HTTP session
 *       ({@link SessionCreationPolicy#STATELESS}). Per-request authentication state is carried by a
 *       token issued by {@code AuthenticationService}, matching tech-spec L29/L83.</dd>
 * </dl>
 *
 * <h2>Minimal Change Clause &amp; decoupling (AAP &sect;0.7.1)</h2>
 * <p>No speculative security features are introduced (no OAuth2 server, no LDAP). The role model is
 * the minimum the legacy estate requires: the COBOL {@code CDEMO-USER-TYPE} value ({@code 'A'} =
 * admin, {@code 'U'} = user) becomes a {@code ROLE_ADMIN}/{@code ROLE_USER} authority, enforced both
 * at the route level for the admin surfaces and (defence in depth) at the admin-service method level
 * via {@link EnableMethodSecurity method security}. To preserve the &quot;foundational
 * infrastructure&quot; property of the {@code config} package, this class still injects <strong>no</strong>
 * service-, repository- or entity-layer bean (no {@code UserDetailsService}, no
 * {@code UserSecurityRepository}); its only collaborator is the security-infrastructure
 * {@link TokenService} (package {@code com.cardemo.security}), used to construct the request-side
 * {@link TokenAuthenticationFilter}. No credential, username, password, API key or token-signing
 * secret is hardcoded anywhere in this file (AAP &sect;0.7.2).</p>
 *
 * @see org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
 * @see org.springframework.security.web.SecurityFilterChain
 * @see org.springframework.security.config.http.SessionCreationPolicy#STATELESS
 * @see com.cardemo.security.TokenAuthenticationFilter
 */
@Configuration
@EnableWebSecurity
// Enables @PreAuthorize on the admin-service methods (defence in depth behind the route rules
// below). prePostEnabled defaults to true on @EnableMethodSecurity (Spring Security 6).
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Canonical BCrypt {@link PasswordEncoder} for the whole application &mdash; the realization of
     * the migration's single permitted behavioral change.
     *
     * <p><strong>C-003 / D-002</strong> &mdash; the COBOL plaintext {@code USRSEC} passwords are
     * upgraded to BCrypt. This one bean is shared by <em>both</em> sides of the credential
     * lifecycle so a single, consistent hashing policy governs the system:</p>
     * <ul>
     *   <li>{@code AuthenticationService} (&larr; {@code COSGN00C}) calls {@code matches(...)} on
     *       sign-on, replacing the plaintext {@code IF SEC-USR-PWD = WS-USER-PWD} comparison.</li>
     *   <li>{@code UserAddService} (&larr; {@code COUSR01C}) calls {@code encode(...)} on user
     *       creation, replacing the plaintext {@code MOVE PASSWDI TO SEC-USR-PWD} move.</li>
     * </ul>
     *
     * <p>Existing seed credentials (the plaintext values in the {@code USRSEC} fixtures) are migrated
     * with the hash-on-first-login pattern recorded in {@code DECISION_LOG} D-002; this encoder is
     * the component that performs the rehash. The plaintext comparison is therefore never exposed
     * anywhere in the Java target.</p>
     *
     * <p>The BCrypt default strength (log rounds = 10) is used deliberately: no stronger cost factor
     * is configured because the Minimal Change Clause forbids enhancement beyond the technology
     * transition, and 10 is the framework-recommended default.</p>
     *
     * @return a singleton {@link BCryptPasswordEncoder} (BCrypt with the default strength of 10)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // C-003/D-002 — plaintext USRSEC passwords upgraded to BCrypt; existing seed passwords
        // migrate via hash-on-first-login per DECISION_LOG D-002.
        return new BCryptPasswordEncoder();
    }

    /**
     * Defines the stateless REST security posture for every HTTP request the application serves.
     *
     * <p>The configuration mirrors the CICS pseudo-conversational &rarr; stateless-REST mapping
     * (tech-spec L29/L83): there is no server-side session, and authentication state travels in a
     * token issued by {@code AuthenticationService}. The chain is intentionally minimal &mdash; it
     * declares only what the migration requires (Minimal Change Clause, AAP &sect;0.7.1):</p>
     * <ul>
     *   <li><strong>CORS enabled (Security-aware).</strong> {@code .cors(Customizer.withDefaults())}
     *       enables CORS <em>within</em> the Security filter chain so a cross-origin pre-flight
     *       ({@code OPTIONS}) is handled before the authorization rules can reject it as
     *       unauthenticated. The policy itself is declared once in {@code config/WebConfig}
     *       ({@code addCorsMappings}); Spring Security reuses it via the MVC
     *       {@code HandlerMappingIntrospector} (no {@code CorsConfigurationSource} bean is defined
     *       here), keeping CORS single-sourced.</li>
     *   <li><strong>CSRF disabled.</strong> CSRF protection guards cookie/session-backed browser
     *       forms. This API is token-based with {@link SessionCreationPolicy#STATELESS} sessions and
     *       exposes no cookie session and no web UI (AAP &sect;0.3.4), so CSRF protection is both
     *       unnecessary and inapplicable; disabling it is the standard, safe posture for a stateless
     *       credential/token API.</li>
     *   <li><strong>Stateless sessions.</strong> {@link SessionCreationPolicy#STATELESS} ensures the
     *       container never creates or consults an {@code HttpSession}, faithfully reproducing the
     *       pseudo-conversational model in which no conversational state is retained between
     *       requests.</li>
     *   <li><strong>Authorization rules.</strong> The sign-on entry point ({@code /api/auth/**},
     *       e.g. {@code POST /api/auth/signin} &larr; {@code COSGN00C}) is open so a caller can obtain
     *       a token; the Actuator health/info/metrics/Prometheus endpoints are open so container
     *       liveness/readiness probes and the Prometheus scrape can reach them (matching the
     *       {@code management.endpoints.web.exposure.include} list in {@code application.yml}); the
     *       Spring {@code /error} dispatch is open so error responses are not themselves blocked; the
     *       <strong>admin surfaces</strong> &mdash; the user-administration CRUD under
     *       {@code /api/admin/**} (&larr; {@code COUSR00C}&ndash;{@code COUSR03C}) and the admin menu
     *       {@code GET /api/menu/admin} (&larr; {@code COADM01C}) &mdash; require the
     *       {@code ROLE_ADMIN} authority (a non-admin authenticated caller receives
     *       {@code 403 Forbidden}); and every other request (accounts, cards, transactions, billing,
     *       reports, main menu) requires an authenticated principal.</li>
     *   <li><strong>HTTP Basic and form login disabled.</strong> Both browser-oriented mechanisms are
     *       turned off because authentication is performed by {@code AuthenticationService} issuing a
     *       token; no browser credential popup or login page is appropriate for this headless API.</li>
     *   <li><strong>401 entry point.</strong> Unauthenticated requests to protected endpoints receive
     *       {@code 401 Unauthorized} (via {@link HttpStatusEntryPoint}) rather than a redirect,
     *       signalling to a client that it must present a token obtained from {@code /api/auth/**}.</li>
     * </ul>
     *
     * <p><strong>Token-validation filter.</strong> The COMMAREA &rarr; stateless-REST rule means
     * request-scoped auth state is carried by the token issued at sign-on. A
     * {@link TokenAuthenticationFilter} is therefore registered <em>before</em> the
     * {@link UsernamePasswordAuthenticationFilter}: on every request it validates a presented
     * {@code Bearer} token (signature + expiry) via {@link TokenService} and, on success, populates
     * the {@code SecurityContext} with the token subject and a {@code ROLE_ADMIN}/{@code ROLE_USER}
     * authority derived from the token's user-type claim &mdash; the stateless analogue of the COBOL
     * {@code CDEMO-USER-TYPE} role carried in the COMMAREA. This is the component that makes the
     * admin route rules and the admin-service {@code @PreAuthorize} guards enforceable, closing the
     * gap where tokens were issued but never validated. The filter is a self-contained
     * security-infrastructure component (package {@code com.cardemo.security}); it depends only on
     * {@code TokenService} (not on the service/repository/entity layers), so the {@code config}
     * package's decoupling property is preserved. It is constructed here (rather than being a
     * {@code @Component}) so the servlet container does not <em>also</em> auto-register it as a
     * top-level filter, which would run it twice. No JWT library is introduced.</p>
     *
     * @param http         the {@link HttpSecurity} builder supplied by Spring Security
     * @param tokenService the stateless-token validator used to build the request-side filter;
     *                     injected as a {@code @Bean}-method parameter from the application context
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if the {@link HttpSecurity} builder fails to assemble the chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenService tokenService) throws Exception {
        http
                // CORS must be enabled INSIDE the Security filter chain (not only in Spring MVC) so a
                // cross-origin pre-flight (OPTIONS) is handled before the authorization rules below can
                // reject it as unauthenticated. Customizer.withDefaults() makes Spring Security reuse the
                // MVC CORS policy declared once in config/WebConfig#addCorsMappings (Spring Security
                // delegates to the MVC HandlerMappingIntrospector when no CorsConfigurationSource bean is
                // present), so the CORS policy stays single-sourced — no duplicate configuration here.
                .cors(Customizer.withDefaults())
                // Stateless token/credential API: no cookie session, no web UI -> CSRF inapplicable.
                .csrf(csrf -> csrf.disable())
                // CICS pseudo-conversational COMMAREA -> stateless REST (tech-spec L29/L83):
                // never create or use an HttpSession.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Sign-on entry point (<- COSGN00C): open so a caller can obtain a token.
                        .requestMatchers("/api/auth/**").permitAll()
                        // Actuator endpoints reachable for container probes and the Prometheus
                        // scrape; mirrors management.endpoints.web.exposure.include in application.yml.
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/actuator/metrics/**").permitAll()
                        // Spring error dispatch must not itself require authentication.
                        .requestMatchers("/error").permitAll()
                        // Admin surfaces require the ADMIN role (COBOL CDEMO-USRTYP-ADMIN). These
                        // MUST precede anyRequest(): the user-administration CRUD (COUSR00C-COUSR03C)
                        // under /api/admin/** and the admin menu (COADM01C) at GET /api/menu/admin.
                        // A non-admin authenticated caller gets 403; an unauthenticated caller gets 401.
                        .requestMatchers(HttpMethod.GET, "/api/menu/admin").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Everything else (accounts, cards, transactions, billing, reports, main
                        // menu) requires an authenticated principal.
                        .anyRequest().authenticated())
                // Headless token API: no browser Basic popup and no form-login page.
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                // Validate the bearer token and establish ROLE_ADMIN/ROLE_USER BEFORE the
                // username/password filter slot, so the authorization rules above can be enforced.
                .addFilterBefore(new TokenAuthenticationFilter(tokenService),
                        UsernamePasswordAuthenticationFilter.class)
                // Unauthenticated access to a protected endpoint -> 401 (present a token), not a
                // redirect; correct semantics for a stateless token API.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    /*
     * Spring AuthenticationManager bean — intentionally NOT declared (AAP §0.7.1 Minimal Change
     * Clause). The migrated sign-on flow (COSGN00C -> AuthenticationService) authenticates by
     * reading the UserSecurity entity directly and verifying the supplied password against the
     * stored BCrypt hash via the passwordEncoder() bean above; it does NOT route through Spring's
     * AuthenticationManager / DaoAuthenticationProvider. Exposing an AuthenticationManager here
     * would add an unused bean and is therefore omitted. If a downstream component later genuinely
     * requires it, it can be exposed as:
     *     @Bean
     *     AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) {
     *         return cfg.getAuthenticationManager();
     *     }
     * without building a UserDetailsService-backed provider that would couple this config to the
     * repository layer.
     */
}
