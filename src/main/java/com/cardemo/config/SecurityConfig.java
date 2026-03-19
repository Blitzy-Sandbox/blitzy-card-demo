/*
 * SecurityConfig.java — Spring Security Configuration for CardDemo Application
 *
 * Migrated from COBOL source artifacts:
 *   - app/cbl/COSGN00C.cbl  (Sign-on program, commit 27d6c6f)
 *   - app/cpy/CSUSR01Y.cpy  (SEC-USER-DATA record layout, 80 bytes, commit 27d6c6f)
 *   - app/jcl/DUSRSECJ.jcl  (User security seed data definition, commit 27d6c6f)
 *
 * This configuration class replaces the COBOL COSGN00C.cbl sign-on logic and
 * USRSEC VSAM file-based authentication. The COBOL program performs:
 *   1. READ USRSEC by SEC-USR-ID (line 211-219) → UserSecurityRepository.findBySecUsrId()
 *   2. Plaintext password comparison (line 223) → BCrypt verification via PasswordEncoder
 *   3. SEC-USR-TYPE routing: 'A' → COADM01C, else → COMEN01C (lines 230-240)
 *      → Spring Security ROLE_ADMIN / ROLE_USER role-based access control
 *
 * SECURITY UPGRADE: The original COBOL application stores passwords in plaintext
 * (constraint C-003 from SEC-USR-PWD PIC X(08)). This migration upgrades to BCrypt
 * hashing per AAP §0.8.1 security requirement. See DECISION_LOG.md D-002.
 *
 * @see com.cardemo.model.entity.UserSecurity
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.enums.UserType
 */
package com.cardemo.config;

import java.io.IOException;
import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security configuration for the CardDemo application.
 *
 * <p>Migrated from COBOL COSGN00C.cbl sign-on program and CSUSR01Y.cpy user security
 * record layout. The original COBOL application used plaintext password comparison
 * against the USRSEC VSAM dataset; this implementation upgrades to BCrypt hashing
 * per AAP §0.8.1 security improvement requirement (Decision D-002).</p>
 *
 * <h3>Authentication Model</h3>
 * <p>The COBOL COSGN00C.cbl program reads SEC-USR-ID and SEC-USR-PWD from the USRSEC
 * VSAM KSDS dataset and compares passwords in plaintext (line 223:
 * {@code IF SEC-USR-PWD = WS-USER-PWD}). The Java equivalent uses
 * Spring Security's {@link UserDetailsService} backed by {@link UserSecurityRepository}
 * with BCrypt password encoding. The SEC-USR-TYPE field ('A' for admin, 'U' for regular
 * user) maps to Spring Security roles: ROLE_ADMIN and ROLE_USER respectively.</p>
 *
 * <h3>Authorization Rules — Mirrors COBOL COSGN00C Routing</h3>
 * <ul>
 *     <li>{@code /api/auth/signin} — Publicly accessible (COSGN00C entry point, transaction CC00)</li>
 *     <li>{@code /api/admin/**} — Requires ROLE_ADMIN (COADM01C admin menu access, lines 230-234)</li>
 *     <li>{@code /api/**} — Requires authentication (any valid user, COMEN01C access)</li>
 *     <li>{@code /actuator/health/**} — Public health/readiness/liveness probes (AAP §0.7.1)</li>
 *     <li>{@code /actuator/info} — Public application info endpoint</li>
 *     <li>{@code /actuator/prometheus} — Public metrics endpoint (AAP §0.7.1)</li>
 * </ul>
 *
 * <h3>Session Management</h3>
 * <p>Stateless session policy — REST API follows stateless request model,
 * replacing CICS pseudo-conversational RETURN TRANSID COMMAREA (COSGN00C.cbl
 * lines 98-102). No server-side session is maintained.</p>
 *
 * <h3>Design Decisions (see DECISION_LOG.md)</h3>
 * <ul>
 *   <li>D-002: BCrypt chosen over Argon2/PBKDF2 for broad ecosystem compatibility
 *       and Spring Security default support. Plaintext preservation rejected per
 *       constraint C-003 security upgrade requirement.</li>
 *   <li>CSRF disabled — standard for stateless token-authenticated REST APIs.</li>
 *   <li>HTTP Basic authentication as initial implementation; can be extended to
 *       JWT token-based authentication in future iterations.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Configures the HTTP security filter chain with role-based access control.
     *
     * <p>Endpoint security mirrors the COBOL program routing logic where
     * COSGN00C validates credentials before routing to COMEN01C (regular menu)
     * or COADM01C (admin menu) based on SEC-USR-TYPE. The Spring Security
     * equivalent enforces this at the HTTP layer.</p>
     *
     * <p>Authorization rules in evaluation order (first match wins):</p>
     * <ol>
     *   <li>{@code /api/auth/signin} — permitAll (public sign-on, maps COSGN00C entry)</li>
     *   <li>{@code /actuator/health/**} — permitAll (readiness/liveness probes, AAP §0.7.1)</li>
     *   <li>{@code /actuator/info} — permitAll (application info endpoint)</li>
     *   <li>{@code /actuator/prometheus} — permitAll (metrics endpoint, AAP §0.7.1)</li>
     *   <li>{@code /api/admin/**} — ROLE_ADMIN only (COADM01C admin menu access)</li>
     *   <li>{@code /actuator/**} — ROLE_ADMIN only (sensitive management endpoints)</li>
     *   <li>All other requests — authenticated (any valid user, COMEN01C access)</li>
     * </ol>
     *
     * <p>CSRF is disabled because this is a stateless REST API that does not use
     * cookie-based sessions. Session management is set to STATELESS, replacing
     * the CICS pseudo-conversational model (RETURN TRANSID COMMAREA).</p>
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if security configuration fails during filter chain assembly
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF protection — REST API is stateless, no cookie-based sessions
            // COBOL CICS had no equivalent (3270 terminals used a different I/O model)
            .csrf(csrf -> csrf.disable())

            // Stateless session management — no server-side HTTP session
            // Replaces CICS pseudo-conversational model: RETURN TRANSID COMMAREA
            // (COSGN00C.cbl lines 98-102)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // HTTP Basic authentication — initial REST API auth mechanism
            // Can be extended to JWT token-based auth for production deployments
            // Custom entry point returns structured JSON on 401 (matching app error format)
            .httpBasic(basic -> basic
                .authenticationEntryPoint((request, response, authException) ->
                    writeSecurityErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                            "Authentication required. Provide valid credentials.",
                            "AUTHENTICATION_REQUIRED")))

            // Custom 403 handler returns structured JSON (matching app error format)
            // with correlationId for observability tracing
            .exceptionHandling(ex -> ex
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    writeSecurityErrorResponse(response, request, HttpStatus.FORBIDDEN,
                            "Access denied. Insufficient privileges for this resource.",
                            "ACCESS_DENIED")))

            // Endpoint authorization rules — mirrors COBOL COSGN00C routing logic
            .authorizeHttpRequests(auth -> auth
                // Public sign-on endpoint — maps from COSGN00C transaction CC00 entry
                .requestMatchers("/api/auth/signin").permitAll()

                // Public actuator endpoints for observability (AAP §0.7.1)
                // Health/readiness/liveness probes for container orchestration
                .requestMatchers("/actuator/health/**").permitAll()
                // Application info endpoint
                .requestMatchers("/actuator/info").permitAll()
                // Prometheus metrics scraping endpoint
                .requestMatchers("/actuator/prometheus").permitAll()

                // Admin-only endpoints — maps COSGN00C lines 230-234:
                // IF CDEMO-USRTYP-ADMIN → XCTL PROGRAM('COADM01C')
                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                // Sensitive actuator endpoints restricted to admin users
                .requestMatchers("/actuator/**").hasRole("ADMIN")

                // All other API endpoints require authentication —
                // maps COSGN00C successful authentication → COMEN01C/COADM01C routing
                .anyRequest().authenticated()
            );

        return http.build();
    }

    /**
     * Provides the BCrypt password encoder bean used throughout the application.
     *
     * <p>Replaces the COBOL plaintext password comparison in COSGN00C.cbl
     * READ-USER-SEC-FILE paragraph (line 223: {@code IF SEC-USR-PWD = WS-USER-PWD}).
     * The {@code user_security} table stores BCrypt hashes (prefix {@code $2a$10$},
     * typically 60 characters) generated during seed data migration (V3__seed_data.sql).</p>
     *
     * <p>BCrypt uses a cost factor of 10 (default) which provides a good balance
     * between security and performance for this application's authentication workload.
     * Decision D-002 documents the rationale: BCrypt chosen over Argon2 (newer but
     * less ecosystem support), PBKDF2 (slower hardware acceleration resistance),
     * and plaintext preservation (security risk, constraint C-003).</p>
     *
     * <p>The seed users from DUSRSECJ.jcl (ADMIN001-ADMIN005 with 'PASSWORDA',
     * USER0001-USER0005 with 'PASSWORDU') must be pre-hashed with BCrypt in the
     * V3__seed_data.sql Flyway migration script.</p>
     *
     * @return a {@link BCryptPasswordEncoder} instance with default strength (10)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Provides a {@link UserDetailsService} backed by the {@code user_security}
     * PostgreSQL table via {@link UserSecurityRepository}.
     *
     * <p>Replaces the COBOL COSGN00C.cbl authentication flow:</p>
     * <ol>
     *   <li><strong>User lookup</strong> — COBOL: {@code EXEC CICS READ DATASET('USRSEC')
     *       RIDFLD(WS-USER-ID)} (lines 211-219) → Java: {@code findBySecUsrId(username)}</li>
     *   <li><strong>Not found handling</strong> — COBOL: {@code WHEN 13} (RESP NOTFND,
     *       line 247) with message "User not found. Try again ..." → Java:
     *       {@link UsernameNotFoundException}</li>
     *   <li><strong>Password verification</strong> — COBOL: plaintext compare
     *       {@code IF SEC-USR-PWD = WS-USER-PWD} (line 223) → Java: BCrypt via
     *       {@link PasswordEncoder#matches(CharSequence, String)} (delegated to
     *       Spring Security authentication manager)</li>
     *   <li><strong>Role assignment</strong> — COBOL: {@code IF CDEMO-USRTYP-ADMIN}
     *       (line 230) routes to COADM01C (admin menu) vs COMEN01C (regular menu)
     *       → Java: ROLE_ADMIN vs ROLE_USER Spring Security authorities</li>
     * </ol>
     *
     * <p>Error handling follows the COBOL EVALUATE WS-RESP-CD structure:</p>
     * <ul>
     *   <li>WHEN 0 (success) + password match → return authenticated UserDetails</li>
     *   <li>WHEN 0 + password mismatch → handled by Spring Security (BadCredentialsException)</li>
     *   <li>WHEN 13 (NOTFND) → {@link UsernameNotFoundException} with descriptive message</li>
     *   <li>WHEN OTHER → unexpected errors propagate as runtime exceptions</li>
     * </ul>
     *
     * @param userSecurityRepository the JPA repository for {@code user_security} table access,
     *                                providing the {@code findBySecUsrId()} method that maps
     *                                the COBOL keyed READ on USRSEC VSAM dataset
     * @return a {@link UserDetailsService} lambda that authenticates against the database
     */
    @Bean
    public UserDetailsService userDetailsService(UserSecurityRepository userSecurityRepository) {
        return username -> {
            // Step 1: Read USRSEC record by SEC-USR-ID
            // Maps COSGN00C.cbl lines 211-219: READ DATASET('USRSEC') RIDFLD(WS-USER-ID)
            UserSecurity userSecurity = userSecurityRepository.findBySecUsrId(username)
                    .orElseThrow(() -> new UsernameNotFoundException(
                            // Maps COSGN00C.cbl line 249: "User not found. Try again ..."
                            // COBOL RESP code 13 (NOTFND) handling
                            "User not found: " + username));

            // Step 2: Map SEC-USR-TYPE to Spring Security role
            // COSGN00C.cbl lines 230-240:
            //   IF CDEMO-USRTYP-ADMIN → XCTL PROGRAM('COADM01C') → ROLE_ADMIN
            //   ELSE → XCTL PROGRAM('COMEN01C') → ROLE_USER
            String role = (userSecurity.getSecUsrType() == UserType.ADMIN) ? "ADMIN" : "USER";

            // Step 3: Build and return Spring Security UserDetails
            // Password verification is delegated to BCryptPasswordEncoder.matches()
            // by the Spring Security authentication manager — replaces COBOL
            // plaintext comparison: IF SEC-USR-PWD = WS-USER-PWD (line 223)
            UserDetails userDetails = User.builder()
                    .username(userSecurity.getSecUsrId())
                    .password(userSecurity.getSecUsrPwd())
                    .roles(role)
                    .build();

            return userDetails;
        };
    }

    // =========================================================================
    // Security Error Response Helper
    // =========================================================================

    /**
     * Writes a structured JSON error response for Spring Security rejections (401/403).
     *
     * <p>Produces the same {@code ErrorResponse} format used by the application's
     * {@code GlobalExceptionHandler} in {@link WebConfig}, ensuring consistent error
     * structure across all HTTP error responses. Includes the correlation ID from MDC
     * for end-to-end observability tracing.</p>
     *
     * <p>This replaces Spring Security's default behavior:
     * <ul>
     *   <li>401: default sends empty body → now sends structured JSON with AUTHENTICATION_REQUIRED</li>
     *   <li>403: default sends Spring's own format (missing correlationId, errorCode) → now matches app format</li>
     * </ul>
     *
     * @param response      the HTTP servlet response to write the JSON body to
     * @param request       the HTTP servlet request for extracting the request URI
     * @param httpStatus    the HTTP status (401 or 403) to set on the response
     * @param message       the human-readable error message
     * @param errorCode     the machine-readable error code (AUTHENTICATION_REQUIRED or ACCESS_DENIED)
     * @throws IOException if writing to the response output stream fails
     */
    private static void writeSecurityErrorResponse(HttpServletResponse response,
                                                    HttpServletRequest request,
                                                    HttpStatus httpStatus,
                                                    String message,
                                                    String errorCode) throws IOException {
        response.setStatus(httpStatus.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // Build the same error structure as WebConfig.GlobalExceptionHandler.buildErrorResponse()
        var errorBody = java.util.Map.of(
                "status", httpStatus.value(),
                "error", httpStatus.getReasonPhrase(),
                "message", message,
                "errorCode", errorCode,
                "fieldErrors", java.util.Collections.emptyList(),
                "timestamp", Instant.now().toString(),
                "path", request.getRequestURI(),
                "correlationId", MDC.get("correlationId") != null ? MDC.get("correlationId") : ""
        );

        new ObjectMapper().writeValue(response.getOutputStream(), errorBody);
    }
}
