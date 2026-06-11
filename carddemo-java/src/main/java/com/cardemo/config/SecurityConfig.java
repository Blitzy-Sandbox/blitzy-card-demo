package com.cardemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

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
 * <p>No speculative security features are introduced (no OAuth2 server, no LDAP, no method-security
 * matrices). To preserve the &quot;foundational infrastructure &mdash; no sibling dependencies&quot;
 * property of the {@code config} package, this class injects <strong>no</strong> service-,
 * repository- or entity-layer bean (in particular no {@code UserDetailsService} and no
 * {@code UserSecurityRepository}); its only imports are framework types. No credential, username,
 * password, API key or token-signing secret is hardcoded anywhere in this file (AAP &sect;0.7.2).</p>
 *
 * @see org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
 * @see org.springframework.security.web.SecurityFilterChain
 * @see org.springframework.security.config.http.SessionCreationPolicy#STATELESS
 */
@Configuration
@EnableWebSecurity
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
     *       Spring {@code /error} dispatch is open so error responses are not themselves blocked; and
     *       every other request (accounts, cards, transactions, billing, reports, user admin, menu)
     *       requires an authenticated principal.</li>
     *   <li><strong>HTTP Basic and form login disabled.</strong> Both browser-oriented mechanisms are
     *       turned off because authentication is performed by {@code AuthenticationService} issuing a
     *       token; no browser credential popup or login page is appropriate for this headless API.</li>
     *   <li><strong>401 entry point.</strong> Unauthenticated requests to protected endpoints receive
     *       {@code 401 Unauthorized} (via {@link HttpStatusEntryPoint}) rather than a redirect,
     *       signalling to a client that it must present a token obtained from {@code /api/auth/**}.</li>
     * </ul>
     *
     * <p><strong>Token-filter registration is intentionally deferred.</strong> The COMMAREA &rarr;
     * stateless-REST rule means request-scoped auth state is carried by a token issued by
     * {@code AuthenticationService}. Registering a token-validation {@code Filter} here would couple
     * this foundational config to the service layer and violate the package's &quot;no sibling
     * dependencies&quot; rule; per the Minimal Change Clause this config therefore defines only the
     * posture above and does not invent a JWT subsystem beyond what {@code AuthController} /
     * {@code AuthenticationService} actually implement. A self-contained token filter, if introduced
     * later, is wired where it is defined, not here.</p>
     *
     * @param http the {@link HttpSecurity} builder supplied by Spring Security
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if the {@link HttpSecurity} builder fails to assemble the chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
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
                        // Everything else (accounts, cards, transactions, billing, reports, admin,
                        // menu) requires an authenticated principal.
                        .anyRequest().authenticated())
                // Headless token API: no browser Basic popup and no form-login page.
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
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
