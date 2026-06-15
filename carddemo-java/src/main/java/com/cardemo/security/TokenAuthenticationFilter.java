package com.cardemo.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-request bearer-token authentication filter &mdash; the request-side half of
 * the CardDemo migration's <strong>COMMAREA&nbsp;&rarr;&nbsp;stateless token</strong>
 * substitution (AAP &sect;0.1.2), and the component that closes the CP3 admin-
 * authorization gap.
 *
 * <h2>Why this filter exists</h2>
 * <p>The legacy CICS programs distinguished an administrator from a regular user
 * by the {@code CDEMO-USER-TYPE} value carried in the {@code CARDDEMO-COMMAREA}
 * ({@code 'A'} = admin, {@code 'U'} = user), and the admin-only programs
 * ({@code COUSR00C}&ndash;{@code COUSR03C}, {@code COADM01C}) were reached only
 * through that admin context. In the stateless Java target there is no COMMAREA,
 * so the equivalent signal is the signed token issued at sign-on by
 * {@link com.cardemo.service.auth.AuthenticationService} (via {@link TokenService}).
 * Before this filter existed, tokens were <em>issued</em> but never
 * <em>validated</em>, and Spring Security only required authentication (not the
 * admin role), so any authenticated caller could reach the admin surfaces. This
 * filter validates the presented token on every request and translates the
 * token's {@code typ} claim into a Spring Security authority
 * ({@code ROLE_ADMIN}/{@code ROLE_USER}), so that the {@code /api/admin/**} and
 * {@code GET /api/menu/admin} route rules and the {@code @PreAuthorize} method
 * guards on the admin services can be enforced.</p>
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>If the {@code Authorization} header carries a {@code Bearer} token and no
 *       authentication is already present, the token is validated via
 *       {@link TokenService#parse(String)}. On success an authenticated
 *       {@link UsernamePasswordAuthenticationToken} is placed in the
 *       {@link SecurityContextHolder}, with the principal set to the token
 *       subject (the signed-in user id) and a single granted authority derived
 *       from the user type (<code>"ROLE_" + userType.name()</code>).</li>
 *   <li>If there is no {@code Bearer} token, or the token is malformed, tampered,
 *       or expired, the filter does <strong>nothing</strong> &mdash; it leaves the
 *       security context empty and lets the chain proceed. Downstream
 *       authorization then yields {@code 401 Unauthorized} for a protected
 *       endpoint (via the {@code HttpStatusEntryPoint} configured in
 *       {@code SecurityConfig}) or {@code 403 Forbidden} when a non-admin
 *       principal hits an admin-only route. The filter never throws and never
 *       writes to the response, so it cannot break unrelated requests.</li>
 * </ul>
 *
 * <h2>Statelessness</h2>
 * <p>The filter extends {@link OncePerRequestFilter} and stores no per-request
 * state on the instance; the authentication it creates lives only in the
 * thread-bound {@link SecurityContextHolder} for the duration of the request,
 * matching the {@code SessionCreationPolicy.STATELESS} posture. It is constructed
 * once and wired into the Spring Security chain by {@code SecurityConfig}; it is
 * deliberately <em>not</em> a {@code @Component}, so the servlet container does
 * not also auto-register it as a top-level filter (which would run it twice).</p>
 *
 * <p><strong>Traceability:</strong> derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f} (the {@code CDEMO-USER-TYPE} role semantics of
 * {@code COCOM01Y}/{@code COSGN00C}). The COBOL source is read-only reference and
 * is never copied into this repository.</p>
 *
 * @see TokenService
 * @see com.cardemo.config.SecurityConfig
 * @see com.cardemo.service.auth.AuthenticationService
 */
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    /** The HTTP header that carries the bearer token. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** The RFC&nbsp;6750 bearer-token scheme prefix (note the trailing space). */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Spring Security role-authority prefix. {@code hasRole("ADMIN")} matches the
     * authority {@code "ROLE_ADMIN"}, so authorities granted here are prefixed
     * accordingly.
     */
    private static final String ROLE_PREFIX = "ROLE_";

    /** The stateless-token validator (shared with token issuance at sign-on). */
    private final TokenService tokenService;

    /**
     * Constructs the filter with the token validator it delegates to.
     *
     * @param tokenService the stateless-token issuer/validator that owns the
     *                      signing secret and performs signature/expiry checks
     */
    public TokenAuthenticationFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Validates a presented bearer token (if any) and, on success, establishes
     * the request's authentication with a role authority derived from the token's
     * user type.
     *
     * <p>This method is intentionally permissive on failure: any absent,
     * malformed, tampered, or expired token simply leaves the security context
     * untouched so that the configured authorization rules decide the outcome
     * (401 for missing/invalid credentials, 403 for an authenticated non-admin on
     * an admin route). It never short-circuits the chain or writes a response
     * itself.</p>
     *
     * @param request     the current HTTP request
     * @param response    the current HTTP response
     * @param filterChain the remaining filter chain to continue
     * @throws ServletException if a downstream filter raises it
     * @throws IOException      if a downstream filter raises it
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        final String header = request.getHeader(AUTHORIZATION_HEADER);

        // Only attempt token authentication when a Bearer token is present and the
        // request is not already authenticated (do not clobber an existing context).
        if (header != null
                && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            final String token = header.substring(BEARER_PREFIX.length()).trim();
            final Optional<TokenService.TokenClaims> claims = tokenService.parse(token);

            if (claims.isPresent()) {
                final TokenService.TokenClaims validated = claims.get();
                // Map the token's user type to a Spring authority. The enum name
                // (ADMIN/USER) yields ROLE_ADMIN/ROLE_USER, tying the authority
                // directly to the single source of truth for the COBOL role codes.
                final String authority = ROLE_PREFIX + validated.userType().name();
                final UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                validated.subject(),
                                null,
                                List.of(new SimpleGrantedAuthority(authority)));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            // Invalid/expired token: leave the context empty -> downstream 401/403.
        }

        // Always continue the chain; authorization is decided downstream.
        filterChain.doFilter(request, response);
    }
}
