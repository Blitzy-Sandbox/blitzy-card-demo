package com.carddemo.config;

import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security assembly for the CardDemo migration &mdash; the Java&nbsp;25 / Spring&nbsp;Boot
 * replacement for the legacy file-based {@code USRSEC} / RACF-style signon and the CICS
 * pseudo-conversational {@code COMMAREA} session.
 *
 * <p>In the mainframe design, the signon program {@code COSGN00C} (transaction {@code CC00}) read
 * the {@code USRSEC} VSAM file by user id, compared the supplied password against the plaintext
 * {@code SEC-USR-PWD} field of {@code CSUSR01Y}, and then used {@code SEC-USR-TYPE} to route the
 * operator to {@code COADM01C} (administrators) or {@code COMEN01C} (regular users), carrying the
 * user identity forward in the CICS {@code COMMAREA} across every subsequent BMS screen. This
 * configuration re-expresses that flow as a <strong>stateless, JWT-bearer</strong> security model:
 * </p>
 * <ul>
 *   <li>the plaintext password compare becomes a BCrypt {@link PasswordEncoder#matches(CharSequence,
 *       String)} verification (the C-003 / Decision&nbsp;Log&nbsp;D-002 upgrade), performed by
 *       {@code SignonService};</li>
 *   <li>the {@code SEC-USR-TYPE} role indicator ({@code 'A'} &rarr; {@code ROLE_ADMIN},
 *       {@code 'U'} &rarr; {@code ROLE_USER}) becomes a Spring authority;</li>
 *   <li>the {@code COMMAREA} user context becomes JWT claims (subject&nbsp;=&nbsp;user id, plus a
 *       {@code role} claim), so no server-side session is retained.</li>
 * </ul>
 *
 * <p>This class provides the two beans the rest of the application wires against &mdash; the
 * {@link PasswordEncoder} (consumed by {@code SignonService} for verification and by
 * {@code UserService} to hash passwords on create/update) and the {@link SecurityFilterChain}
 * &mdash; and it enables method security so the {@code @PreAuthorize("hasRole('ADMIN')")} gates on
 * {@code UserController} and {@code MenuController#getAdminMenu} are enforced. The JWT bearer-token
 * authentication filter is a nested class (not a separate {@code @Component}), keeping the
 * {@code config} package at exactly six configuration classes.</p>
 *
 * <p>The JWT signing secret is never present in this file; it flows through {@link JwtService} from
 * the externalized property {@code carddemo.security.jwt.secret}. Design rationale is recorded in
 * {@code docs/decision-log.md} and the COBOL&nbsp;&rarr;&nbsp;Java paragraph mapping in
 * {@code docs/traceability-matrix.md} (legacy source referenced read-only by commit SHA
 * {@code 27d6c6f}); rationale is intentionally kept out of code comments per the Explainability
 * rule.</p>
 *
 * @see JwtService
 * @see CorrelationIdFilter
 * @see SecurityFilterChain
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Public signon endpoint that issues the JWT; must be reachable without a token. */
    private static final String LOGIN_PATH = "/api/auth/login";

    /**
     * Actuator management endpoints that are safe to expose without authentication: the liveness /
     * readiness probes, the build-info endpoint, and the Prometheus scrape target.
     */
    private static final String[] PUBLIC_ACTUATOR_PATHS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus"
    };

    /**
     * Administrator-only URL surfaces (defense-in-depth alongside the method-level
     * {@code @PreAuthorize} gates on {@code UserController} and {@code MenuController}).
     */
    private static final String[] ADMIN_PATHS = {
        "/api/users/**",
        "/api/admin/**"
    };

    /** Spring role name required for the administrator surfaces; maps from {@code SEC-USR-TYPE 'A'}. */
    private static final String ROLE_ADMIN = "ADMIN";

    /** Domain error code emitted for authentication failures (missing/invalid token). */
    private static final String CODE_UNAUTHORIZED = "UNAUTHORIZED";

    /** Domain error code emitted for authorization failures (authenticated but insufficient role). */
    private static final String CODE_ACCESS_DENIED = "ACCESS_DENIED";

    /** Generic, non-revealing message for a 401; never discloses whether a user exists. */
    private static final String MESSAGE_UNAUTHORIZED = "Authentication required";

    /** Generic, non-revealing message for a 403. */
    private static final String MESSAGE_ACCESS_DENIED = "Access denied";

    /** Issues and validates the stateless JWT session; supplied via constructor injection. */
    private final JwtService jwtService;

    /**
     * The observability correlation-id filter. It is positioned in the security filter chain here
     * (its servlet-container auto-registration is disabled by {@code WebConfig}) so it executes
     * exactly once, early, publishing the MDC {@code correlationId} for all downstream logging.
     */
    private final CorrelationIdFilter correlationIdFilter;

    /** JSON serializer used to render the {@code ErrorResponse}-shaped 401 / 403 bodies. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the security configuration with its collaborators.
     *
     * <p>Constructor injection is used exclusively (no field injection) so the configuration is
     * explicit about its dependencies and can be instantiated directly in tests.</p>
     *
     * @param jwtService          the stateless JWT session service (never {@code null})
     * @param correlationIdFilter the observability correlation-id filter to position in the chain
     *                            (never {@code null})
     * @param objectMapper        the JSON serializer for filter-chain error bodies (never
     *                            {@code null})
     */
    public SecurityConfig(final JwtService jwtService,
                          final CorrelationIdFilter correlationIdFilter,
                          final ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.correlationIdFilter = correlationIdFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * BCrypt password encoder bean &mdash; the C-003 / Decision&nbsp;Log&nbsp;D-002 upgrade that
     * replaces the legacy plaintext {@code SEC-USR-PWD} compare in {@code COSGN00C}.
     *
     * <p>This bean is a hard contract: {@code SignonService} uses it to verify a presented password
     * against the stored hash, and {@code UserService} uses it to hash passwords when users are
     * created or updated. BCrypt is provided by {@code spring-security-crypto} and embeds a
     * per-hash random salt, so no salt management is required here.</p>
     *
     * @return a {@link BCryptPasswordEncoder} using the library default strength
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Builds the stateless, JWT-bearer security filter chain.
     *
     * <p>The chain is headless (no HTML login form, no HTTP Basic), holds no server-side session
     * ({@link SessionCreationPolicy#STATELESS}, the {@code COMMAREA} replacement), and disables CSRF
     * because there are no browser-managed cookies to protect. Authorization mirrors the verified
     * controller map: the signon endpoint and the safe actuator probes are public, the administrator
     * surfaces require {@code ROLE_ADMIN}, and every other request must be authenticated.</p>
     *
     * <p>Two filters are inserted at deliberately distinct, stable reference positions so their
     * relative order is unambiguous: the {@link CorrelationIdFilter} runs before
     * {@link SecurityContextHolderFilter} (so the MDC {@code correlationId} is set before any
     * security or application code logs), and the {@link JwtAuthenticationFilter} runs before
     * {@link UsernamePasswordAuthenticationFilter} (resolving the bearer token before the unused
     * form-login position). Denials are rendered as JSON: missing/invalid tokens trigger
     * {@link #writeUnauthorized} (401) and insufficient roles trigger {@link #writeForbidden}
     * (403).</p>
     *
     * @param http the Spring Security HTTP builder (never {@code null})
     * @return the assembled {@link SecurityFilterChain}
     * @throws Exception if the HTTP security configuration cannot be built
     */
    @Bean
    SecurityFilterChain filterChain(final HttpSecurity http) throws Exception {
        final JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtService);

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, LOGIN_PATH).permitAll()
                .requestMatchers(PUBLIC_ACTUATOR_PATHS).permitAll()
                .requestMatchers(ADMIN_PATHS).hasRole(ROLE_ADMIN)
                .anyRequest().authenticated())
            .addFilterBefore(correlationIdFilter, SecurityContextHolderFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(this::writeUnauthorized)
                .accessDeniedHandler(this::writeForbidden));

        return http.build();
    }

    /**
     * {@code AuthenticationEntryPoint} callback for unauthenticated access to a protected resource
     * (a missing, malformed, or expired token). Emits a 401 with the shared JSON error shape.
     *
     * @param request                 the current request (never {@code null})
     * @param response                the current response (never {@code null})
     * @param authenticationException the triggering authentication exception (unused; the response
     *                                is intentionally generic and never leaks its detail)
     * @throws IOException if the JSON body cannot be written to the response stream
     */
    private void writeUnauthorized(final HttpServletRequest request,
                                   final HttpServletResponse response,
                                   final AuthenticationException authenticationException)
            throws IOException {
        writeErrorResponse(request, response, HttpStatus.UNAUTHORIZED,
                CODE_UNAUTHORIZED, MESSAGE_UNAUTHORIZED);
    }

    /**
     * {@code AccessDeniedHandler} callback for an authenticated caller whose role is insufficient
     * (for example a {@code ROLE_USER} token hitting an administrator surface). Emits a 403 with the
     * shared JSON error shape.
     *
     * @param request               the current request (never {@code null})
     * @param response              the current response (never {@code null})
     * @param accessDeniedException the triggering access-denied exception (unused; the response is
     *                              intentionally generic)
     * @throws IOException if the JSON body cannot be written to the response stream
     */
    private void writeForbidden(final HttpServletRequest request,
                                final HttpServletResponse response,
                                final AccessDeniedException accessDeniedException)
            throws IOException {
        writeErrorResponse(request, response, HttpStatus.FORBIDDEN,
                CODE_ACCESS_DENIED, MESSAGE_ACCESS_DENIED);
    }

    /**
     * Writes a JSON error body whose shape matches {@code dto.ErrorResponse} so that filter-chain
     * denials are indistinguishable, on the wire, from the {@code @RestControllerAdvice} responses
     * produced deeper in the stack.
     *
     * <p>An ordered {@link LinkedHashMap} is used rather than the {@code ErrorResponse} record so
     * this security-layer class does not compile-couple to the DTO's constructor while still
     * producing the identical field order and payload. The {@code correlationId} is read from the
     * MDC key populated by {@link CorrelationIdFilter}; it may be {@code null}. No token, secret, or
     * password is ever placed in the body or logged.</p>
     *
     * @param request    the current request, used for the {@code path} field (never {@code null})
     * @param response   the current response to write to (never {@code null})
     * @param httpStatus the HTTP status to set and report (never {@code null})
     * @param code       the domain error code ({@code UNAUTHORIZED} / {@code ACCESS_DENIED})
     * @param message    the generic, leak-free human-readable message
     * @throws IOException if serialization to the response stream fails
     */
    private void writeErrorResponse(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final HttpStatus httpStatus,
                                    final String code,
                                    final String message) throws IOException {
        response.setStatus(httpStatus.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", httpStatus.value());
        body.put("error", httpStatus.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("correlationId", MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));
        body.put("fieldErrors", null);

        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /**
     * Stateless JWT bearer-token authentication filter.
     *
     * <p>This is a nested filter (deliberately <em>not</em> a {@code @Component}); it is instantiated
     * explicitly in {@link SecurityConfig#filterChain(HttpSecurity)} so it is not servlet-container
     * auto-registered, preserving the exactly-six-configuration-classes rule. For each request it
     * inspects the {@code Authorization} header for a {@code Bearer} token; when a token is present
     * and {@link JwtService#validateToken(String) valid}, it establishes an authenticated
     * {@link SecurityContextHolder} context whose single authority is derived from the token
     * {@code role} claim ({@code ROLE_ADMIN} / {@code ROLE_USER}). A missing, malformed, or expired
     * token is never fatal: the request simply proceeds unauthenticated, and the authorization rules
     * plus the {@code AuthenticationEntryPoint} produce the 401 for protected resources.</p>
     */
    private static final class JwtAuthenticationFilter extends OncePerRequestFilter {

        /** HTTP {@code Authorization} scheme prefix that introduces a bearer token. */
        private static final String BEARER_PREFIX = "Bearer ";

        /** Prefix applied to the token {@code role} claim to form a Spring authority. */
        private static final String ROLE_PREFIX = "ROLE_";

        /** JWT session service used to validate tokens and extract the user id and role. */
        private final JwtService jwtService;

        /**
         * @param jwtService the JWT session service (never {@code null})
         */
        private JwtAuthenticationFilter(final JwtService jwtService) {
            this.jwtService = jwtService;
        }

        /**
         * Authenticates the request from a bearer token when present and valid, then always
         * delegates to the remainder of the chain.
         *
         * @param request     the inbound HTTP request (never {@code null})
         * @param response    the outbound HTTP response (never {@code null})
         * @param filterChain the remaining filter chain (never {@code null})
         * @throws ServletException if the downstream chain raises a servlet-level failure
         * @throws IOException      if the downstream chain raises an I/O failure
         */
        @Override
        protected void doFilterInternal(final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final FilterChain filterChain)
                throws ServletException, IOException {

            final String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith(BEARER_PREFIX)) {
                final String token = header.substring(BEARER_PREFIX.length());
                try {
                    if (jwtService.validateToken(token)) {
                        authenticate(request, token);
                    }
                } catch (final RuntimeException ex) {
                    // Malformed / expired / tampered token: leave the request unauthenticated so the
                    // authorization rules and AuthenticationEntryPoint yield a 401. The token and the
                    // failure detail are never logged (they may carry sensitive material).
                    SecurityContextHolder.clearContext();
                }
            }

            filterChain.doFilter(request, response);
        }

        /**
         * Establishes an authenticated security context for a verified token.
         *
         * @param request the request whose details are attached to the authentication
         * @param token   the already-validated compact JWT string
         */
        private void authenticate(final HttpServletRequest request, final String token) {
            final String userId = jwtService.extractUserId(token);
            final String role = jwtService.extractRole(token);
            final List<SimpleGrantedAuthority> authorities =
                    List.of(new SimpleGrantedAuthority(ROLE_PREFIX + role));
            final UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    }
}
