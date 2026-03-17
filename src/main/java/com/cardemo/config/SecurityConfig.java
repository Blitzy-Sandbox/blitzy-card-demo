package com.cardemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the CardDemo application.
 *
 * <p>Maps from the COBOL sign-on logic in {@code COSGN00C.cbl} and the user
 * security record layout in {@code CSUSR01Y.cpy}. The original COBOL
 * application used plaintext password comparison against the USRSEC VSAM
 * dataset; the Java target upgrades to BCrypt password hashing while
 * preserving the same authentication flow semantics.</p>
 *
 * <p>At this infrastructure checkpoint the full AuthenticationService and
 * UserDetailsService backed by the {@code user_security} table are not yet
 * wired. The filter chain therefore permits all requests so that actuator
 * health, metrics, and API endpoints are reachable for verification. When
 * the authentication service layer is implemented the chain will be
 * tightened to enforce role-based access (ADMIN vs USER) per the AAP.</p>
 *
 * <h3>Design Decisions (see DECISION_LOG.md D-002)</h3>
 * <ul>
 *   <li>BCrypt chosen over Argon2/PBKDF2 for broad ecosystem compatibility
 *       and Spring Security default support.</li>
 *   <li>Stateless session policy — REST API follows stateless request model,
 *       replacing CICS pseudo-conversational RETURN TRANSID COMMAREA.</li>
 *   <li>CSRF disabled — standard for token-authenticated REST APIs.</li>
 * </ul>
 *
 * @see com.cardemo.model.entity.UserSecurity
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Configures the HTTP security filter chain.
     *
     * <p>Current checkpoint configuration permits all requests to enable
     * infrastructure verification. Production-ready configuration will
     * restrict endpoints based on authenticated roles:
     * <ul>
     *   <li>{@code /api/admin/**} — ADMIN role only</li>
     *   <li>{@code /api/**} — authenticated users (ADMIN or USER)</li>
     *   <li>{@code /actuator/health/**} — public (readiness/liveness)</li>
     *   <li>{@code /actuator/**} — ADMIN role only</li>
     *   <li>{@code /api/auth/signin} — public</li>
     * </ul>
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — REST API uses token-based auth, not cookies
            .csrf(csrf -> csrf.disable())

            // Stateless sessions — no server-side session (replaces CICS COMMAREA)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Endpoint authorization rules
            .authorizeHttpRequests(auth -> auth
                // Actuator health endpoints are always public
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/actuator/info").permitAll()
                // Authentication endpoint is public
                .requestMatchers("/api/auth/**").permitAll()
                // Actuator management endpoints (prometheus, etc.)
                .requestMatchers("/actuator/**").permitAll()
                // All API endpoints — permit all during infrastructure phase;
                // will require authentication once AuthenticationService is wired
                .anyRequest().permitAll()
            );

        return http.build();
    }

    /**
     * Provides the BCrypt password encoder bean used throughout the application.
     *
     * <p>Replaces the COBOL plaintext password comparison in
     * {@code COSGN00C.cbl PROCESS-ENTER-KEY} paragraph. The user_security
     * table stores BCrypt hashes (prefix {@code $2a$10$}, 60 characters)
     * generated during seed data migration.</p>
     *
     * <p>BCrypt uses a cost factor of 10 (default) which provides a good
     * balance between security and performance for this application's
     * authentication workload.</p>
     *
     * @return a {@link BCryptPasswordEncoder} instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Provides a {@link UserDetailsService} bean to suppress Spring Boot's
     * {@code UserDetailsServiceAutoConfiguration}.
     *
     * <p>At this infrastructure checkpoint the full {@code AuthenticationService}
     * backed by the {@code user_security} table is not yet wired. This
     * in-memory implementation provides a bootstrap admin account so the
     * Spring Security context initializes without errors. When the
     * database-backed {@code AuthenticationService} is implemented it will
     * define its own {@code UserDetailsService} and this bean will be
     * replaced or removed.</p>
     *
     * @return an {@link InMemoryUserDetailsManager} with a bootstrap admin user
     */
    @Bean
    public UserDetailsService userDetailsService() {
        // Bootstrap admin account — suppresses UserDetailsServiceAutoConfiguration.
        // Will be replaced by database-backed UserDetailsService when
        // AuthenticationService is implemented.
        return new InMemoryUserDetailsManager(
            User.builder()
                .username("admin")
                .password(passwordEncoder().encode("admin"))
                .roles("ADMIN")
                .build()
        );
    }
}
