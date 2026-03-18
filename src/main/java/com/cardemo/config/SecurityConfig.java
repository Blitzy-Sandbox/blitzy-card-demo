package com.cardemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Security configuration for the CardDemo application.
 *
 * <p>Migrated from COBOL COSGN00C.cbl sign-on program and CSUSR01Y.cpy user security
 * record layout. The original COBOL application used plaintext password comparison
 * against the USRSEC VSAM dataset; this implementation upgrades to BCrypt hashing
 * per AAP §0.8.1 security improvement requirement.</p>
 *
 * <h3>Authentication Model</h3>
 * <p>The COBOL COSGN00C.cbl program reads SEC-USR-ID and SEC-USR-PWD from the USRSEC
 * VSAM KSDS dataset and compares passwords in plaintext. The Java equivalent uses
 * Spring Security's {@link UserDetailsService} backed by {@link UserSecurityRepository}
 * with BCrypt password encoding. The SEC-USR-TYPE field ('A' for admin, 'U' for regular
 * user) maps to Spring Security roles: ROLE_ADMIN and ROLE_USER respectively.</p>
 *
 * <h3>Authorization Rules</h3>
 * <p>Endpoint security mirrors the COBOL program routing logic:</p>
 * <ul>
 *     <li>{@code /api/auth/**} — Publicly accessible (sign-on equivalent)</li>
 *     <li>{@code /api/admin/**} — Requires ROLE_ADMIN (COADM01C admin menu access)</li>
 *     <li>{@code /api/**} — Requires authentication (any authenticated user)</li>
 *     <li>{@code /actuator/health/**} — Public health check endpoints</li>
 *     <li>{@code /actuator/**} — Requires ROLE_ADMIN for sensitive actuator endpoints</li>
 * </ul>
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
 * @see com.cardemo.repository.UserSecurityRepository
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Configures the HTTP security filter chain with role-based access control.
     *
     * <p>Endpoint security mirrors the COBOL program routing logic where
     * COSGN00C validates credentials before routing to COMEN01C (regular menu)
     * or COADM01C (admin menu) based on SEC-USR-TYPE. The Spring Security
     * equivalent enforces this at the HTTP layer:</p>
     * <ul>
     *   <li>{@code /api/auth/**} — Public (sign-on equivalent)</li>
     *   <li>{@code /api/admin/**} — ROLE_ADMIN only (COADM01C admin access)</li>
     *   <li>{@code /actuator/health/**} — Public (readiness/liveness probes)</li>
     *   <li>{@code /actuator/**} — ROLE_ADMIN only (sensitive management endpoints)</li>
     *   <li>All other requests — Authenticated (any valid user)</li>
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

            // HTTP Basic authentication for REST API access
            .httpBasic(basic -> {})

            // Endpoint authorization rules — mirrors COBOL COSGN00C routing
            .authorizeHttpRequests(auth -> auth
                // Actuator health/info endpoints are always public (readiness/liveness probes)
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/actuator/info").permitAll()
                // Authentication endpoint is public (sign-on equivalent)
                .requestMatchers("/api/auth/**").permitAll()
                // Admin endpoints require ADMIN role (COADM01C admin menu access)
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Sensitive actuator endpoints require ADMIN role
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // All other API endpoints require authentication (any authenticated user)
                .anyRequest().authenticated()
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
     * Provides a {@link UserDetailsService} backed by the {@code user_security}
     * PostgreSQL table via {@link UserSecurityRepository}.
     *
     * <p>Replaces the COBOL COSGN00C.cbl authentication flow where SEC-USR-ID is
     * used to READ the USRSEC VSAM KSDS dataset and SEC-USR-PWD is compared in
     * plaintext. The Spring Security equivalent queries the {@code user_security}
     * table by user ID and delegates password verification to BCrypt.</p>
     *
     * <h3>Role Mapping (SEC-USR-TYPE → Spring Security Roles)</h3>
     * <ul>
     *   <li>{@code 'A'} (ADMIN) → {@code ROLE_ADMIN} — routes to COADM01C admin menu</li>
     *   <li>{@code 'U'} (USER)  → {@code ROLE_USER}  — routes to COMEN01C main menu</li>
     * </ul>
     *
     * @param userSecurityRepository the JPA repository for user_security table access
     * @return a {@link UserDetailsService} that authenticates against the database
     */
    @Bean
    public UserDetailsService userDetailsService(UserSecurityRepository userSecurityRepository) {
        return username -> {
            // Read USRSEC record by SEC-USR-ID — mirrors COSGN00C READ on USRSEC
            UserSecurity userSecurity = userSecurityRepository.findBySecUsrId(username)
                    .orElseThrow(() -> new UsernameNotFoundException(
                            "User not found: " + username));

            // Map SEC-USR-TYPE to Spring Security granted authorities
            // 'A' (ADMIN) → ROLE_ADMIN (COADM01C admin menu routing)
            // 'U' (USER)  → ROLE_USER  (COMEN01C regular menu routing)
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            if (userSecurity.getSecUsrType() == UserType.ADMIN) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            } else {
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            }

            // Return Spring Security UserDetails with BCrypt password hash
            // Password verification is delegated to BCryptPasswordEncoder
            return new User(
                    userSecurity.getSecUsrId(),
                    userSecurity.getSecUsrPwd(),
                    authorities
            );
        };
    }
}
