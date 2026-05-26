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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.awsm2.carddemo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Servlet filter that extracts and validates a JWT bearer token from the
 * {@code Authorization} HTTP header on every incoming request. On successful
 * validation, populates Spring Security's {@link SecurityContextHolder} with a
 * {@link UsernamePasswordAuthenticationToken} carrying the authenticated
 * user's ID and a {@link SimpleGrantedAuthority} derived from the user type
 * ({@code 'A'} &rarr; {@code ROLE_ADMIN}, anything else &rarr;
 * {@code ROLE_USER}).
 *
 * <p>Replaces CICS pseudo-conversational identity propagation. In the source
 * mainframe system, every CICS transaction received the
 * {@code CARDDEMO-COMMAREA} (see {@code app/cpy/COCOM01Y.cpy}) carrying
 * {@code CDEMO-USER-ID PIC X(08)} and {@code CDEMO-USER-TYPE PIC X(01)}. The
 * signon program {@code app/cbl/COSGN00C.cbl} populated these fields after a
 * successful {@code READ USRSEC} (L211-L219) and XCTL'd to the appropriate
 * downstream program (L230-L240). This filter performs the equivalent role
 * for the stateless REST API: on every request the JWT is verified and the
 * caller's identity is established in the {@link SecurityContextHolder}.</p>
 *
 * <h2>Authority mapping (AAP &sect;0.4.1)</h2>
 * <p>The COBOL 88-level constants from {@code app/cpy/COCOM01Y.cpy} L27-L28
 * are mapped to Spring Security role authorities:</p>
 * <ul>
 *   <li>{@code CDEMO-USRTYP-ADMIN VALUE 'A'} &rarr; {@code ROLE_ADMIN}</li>
 *   <li>{@code CDEMO-USRTYP-USER  VALUE 'U'} &rarr; {@code ROLE_USER}</li>
 * </ul>
 * <p>Any value other than the exact uppercase character {@code 'A'}
 * (including {@code 'U'}, {@code null}, and blank) defaults to
 * {@code ROLE_USER} so that a cryptographically valid token never lands in
 * the security context with an empty authority list. This matches the
 * COBOL routing pattern {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...} in
 * {@code COSGN00C.cbl} L230-L240, where every non-admin user follows the
 * regular user flow into {@code COMEN01C}.</p>
 *
 * <h2>Security hygiene (AAP &sect;0.6.6, &sect;0.7.2 PCI-DSS)</h2>
 * <ul>
 *   <li>The raw JWT bytes are NEVER logged at any level &mdash; not on
 *       success, not on failure. Successful authentications log only the
 *       authenticated user ID, the COBOL user type, the HTTP method and
 *       the request URI at DEBUG.</li>
 *   <li>JWT validation failures log only the request method, request URI
 *       and the JJWT exception message at WARN &mdash; the token, the
 *       header value and any signing key material are never emitted.</li>
 *   <li>Credentials are explicitly set to {@code null} on the
 *       {@link UsernamePasswordAuthenticationToken} so the raw token is
 *       not retained in the in-memory security context for the duration
 *       of the request.</li>
 *   <li>On any validation failure, {@link SecurityContextHolder#clearContext()}
 *       is invoked to prevent leaking a stale {@code Authentication} from
 *       a prior request that may have been processed on the same thread
 *       (servlet containers reuse worker threads across requests).</li>
 *   <li>The filter NEVER short-circuits the chain &mdash; it always calls
 *       {@link FilterChain#doFilter(jakarta.servlet.ServletRequest, jakarta.servlet.ServletResponse)}.
 *       Translation of an unauthenticated request to an HTTP 401 response
 *       is the responsibility of Spring Security's authentication entry
 *       point invoked by downstream authorization filters; translation of
 *       an authorization failure to an HTTP 403 response is the
 *       responsibility of {@code GlobalExceptionHandler} in the sibling
 *       {@code exception/} package.</li>
 * </ul>
 *
 * <h2>Replaces (AAP &sect;0.1.1, &sect;0.7.3)</h2>
 * <p>Replaces: CICS pseudo-conversational identity check at the start of
 * each transaction. In COBOL the identity context was carried in
 * {@code CARDDEMO-COMMAREA} ({@code app/cpy/COCOM01Y.cpy}) between
 * pseudo-conversational re-entries; in the Java target the JWT serves as
 * the cryptographically signed, self-contained, per-request equivalent of
 * the COMMAREA's identity fields. Source paragraph chain:
 * {@code COSGN00C.cbl PROCESS-ENTER-KEY} (L108-L140) &rarr;
 * {@code READ-USER-SEC-FILE} (L209-L246) &rarr; {@code XCTL} routing
 * (L230-L240).</p>
 *
 * @see JwtTokenProvider
 * @see org.springframework.security.web.SecurityFilterChain
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * SLF4J logger used for PCI-DSS-compliant security event logging. Per
     * AAP &sect;0.6.6 the implementation MUST NOT emit raw JWT bytes,
     * signing key material, or full {@code Authorization} header values at
     * any level. DEBUG is used for successful authentications, WARN for
     * validation failures.
     */
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * Name of the HTTP request header carrying the bearer token, per
     * RFC 7235 &sect;4.2 / RFC 6750 &sect;2.1. Defined here as a constant
     * rather than referenced via {@code org.springframework.http.HttpHeaders}
     * to keep the filter's external import surface narrow.
     */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * RFC 6750 bearer scheme prefix (trailing space included so the prefix
     * length corresponds exactly to {@code "Bearer "} for substring
     * extraction).
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Name of the custom JWT claim carrying the COBOL {@code SEC-USR-TYPE}
     * value from {@code app/cpy/CSUSR01Y.cpy} L22. The claim name matches
     * the constant published by {@link JwtTokenProvider} so the contract
     * between token issuance and token validation is symmetric.
     */
    private static final String USER_TYPE_CLAIM = "userType";

    /**
     * COBOL {@code SEC-USR-TYPE} value identifying an administrative user.
     * Matches the {@code CDEMO-USRTYP-ADMIN VALUE 'A'} 88-level constant
     * declared in {@code app/cpy/COCOM01Y.cpy} L27. Comparison is
     * case-sensitive on uppercase {@code 'A'} because the upstream signon
     * flow ({@code app/cbl/COSGN00C.cbl} L132-L134 and the V015 seed
     * migration) stores the user type as a single uppercase character.
     */
    private static final String USER_TYPE_ADMIN = "A";

    /**
     * Spring Security role authority granted to administrative users.
     * Combined with Spring Security's convention that {@code hasRole("ADMIN")}
     * matches an authority named {@code "ROLE_ADMIN"}.
     */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * Spring Security role authority granted to regular users and to any
     * authenticated principal whose {@code userType} claim is not the
     * exact uppercase character {@code 'A'}. The default-to-USER fallback
     * mirrors the COBOL pattern {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...}
     * in {@code COSGN00C.cbl} L230-L240.
     */
    private static final String ROLE_USER = "ROLE_USER";

    /**
     * The {@link JwtTokenProvider} used to verify the bearer token's
     * signature, expiration and issuer and to return the parsed claim set.
     * Final so the field is guaranteed to be visible to all servlet
     * container worker threads after Spring publishes the bean.
     */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Constructor injection of the JWT token provider, per AAP &sect;0.3.3
     * (Dependency Injection &mdash; constructor injection for all
     * {@code @Component} beans). Field injection via {@code @Autowired} is
     * deliberately not used.
     *
     * @param jwtTokenProvider the JWT validation collaborator; Spring's
     *                         bean factory enforces non-null injection so
     *                         an explicit {@code Objects.requireNonNull}
     *                         is unnecessary
     */
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        // Replaces: CICS RETURN TRANSID COMMAREA pseudo-conversational flow
        // where the saved COMMAREA was the only proof of identity on the
        // next interaction (COSGN00C.cbl L98-L102, COCOM01Y.cpy L19-L29).
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Main filter entry point invoked exactly once per HTTP request by
     * {@link OncePerRequestFilter}. Extracts the bearer token, validates
     * it, populates the {@link SecurityContextHolder} on success, and
     * always proceeds with the filter chain.
     *
     * <p>The method NEVER throws to short-circuit the chain. On JWT
     * validation failure the security context is cleared and the chain
     * proceeds, leaving authorization decisions to downstream Spring
     * Security filters and ultimately to
     * {@code GlobalExceptionHandler.handleAuthentication(AuthenticationException)}
     * which returns HTTP 401 with the standard error envelope.</p>
     *
     * @param request     the inbound HTTP request; must not be {@code null}
     * @param response    the outbound HTTP response; must not be
     *                    {@code null} &mdash; this filter never writes to
     *                    it directly, response generation is delegated
     *                    downstream
     * @param filterChain the remaining filter chain; must not be
     *                    {@code null}
     * @throws ServletException if any downstream filter or the dispatched
     *                          servlet throws
     * @throws IOException      if any downstream filter or the dispatched
     *                          servlet performs an I/O operation that
     *                          fails
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        // Replaces: CICS PROCESS-ENTER-KEY -> READ-USER-SEC-FILE -> XCTL
        // identity establishment pattern in COSGN00C.cbl. The CICS flow ran
        // once at signon and persisted identity in COMMAREA; the REST flow
        // re-establishes identity on every request from the signed JWT.
        String token = extractBearerToken(request);
        if (!StringUtils.hasText(token)) {
            // No bearer token in the request. The endpoint may be public
            // (in which case shouldNotFilter has already short-circuited
            // us out before we got here) or it may require authentication
            // (in which case Spring Security's authorization filters will
            // emit HTTP 401 downstream). Either way, this filter just
            // proceeds and lets the chain run.
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // COBOL: COSGN00C.cbl L211-L219 -- EXEC CICS READ DATASET (USRSEC)
            //        ... RIDFLD(WS-USER-ID) ...
            //        Java equivalent: cryptographically verify the bearer
            //        token (signature + iss + exp) and read the claims.
            Claims claims = jwtTokenProvider.validateToken(token);
            String userId = claims.getSubject();
            if (!StringUtils.hasText(userId)) {
                // A cryptographically valid token without a subject claim
                // cannot identify a user. Treat as unauthenticated rather
                // than throw -- we still proceed so endpoint-level access
                // rules can decide the outcome.
                log.warn("JWT validated but subject claim is empty for request {} {}",
                        request.getMethod(), request.getRequestURI());
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }
            String userType = claims.get(USER_TYPE_CLAIM, String.class);
            // COBOL: COSGN00C.cbl L227 -- MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
            //        Java equivalent: derive Spring Security authorities
            //        from the userType claim.
            List<GrantedAuthority> authorities = mapUserTypeToAuthorities(userType);

            // Credentials are intentionally NULL: the token has already
            // been verified above and retaining its bytes in the security
            // context for the duration of the request would create a
            // PCI-DSS exposure surface (AAP §0.6.6 / §0.7.2).
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            // Attach standard web request details (remote address, session
            // ID if any) so downstream audit components (CloudWatch /
            // OpenSearch per AAP §0.6.6) can resolve the source IP of
            // every authenticated request.
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            if (log.isDebugEnabled()) {
                // PCI-DSS-safe DEBUG log: userId and userType are not
                // sensitive on their own (they appear in audit trails per
                // AAP §0.6.6); the token bytes are NEVER logged.
                log.debug("Authenticated user {} with type {} for {} {}",
                        userId, userType, request.getMethod(), request.getRequestURI());
            }
        } catch (JwtException | IllegalArgumentException ex) {
            // JwtException covers ExpiredJwtException, SignatureException,
            // MalformedJwtException, UnsupportedJwtException, and any
            // other JJWT 0.12.x validation failure. IllegalArgumentException
            // is thrown by JJWT for blank/whitespace tokens before the
            // parser even runs. Both indicate an unauthenticated client
            // rather than an internal server fault, so we log at WARN
            // (auditable but not a 5xx), clear the security context, and
            // proceed -- Spring Security's authentication entry point
            // will translate this to HTTP 401 downstream if the endpoint
            // requires authentication.
            //
            // PCI-DSS-safe error log: request method/URI + JJWT exception
            // message only. The token bytes, the Authorization header
            // value, and the userId from the token (if any) are NEVER
            // emitted to the log.
            log.warn("JWT validation failed for request {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
        }

        // Always proceed with the filter chain -- this filter never
        // short-circuits the request, even on validation failure.
        filterChain.doFilter(request, response);
    }

    /**
     * Skip JWT extraction for clearly-public endpoints. Authoritative
     * authorization rules are still enforced by
     * {@code SecurityConfig.securityFilterChain(...)} in the sibling
     * {@code config/} package &mdash; this filter exclusion is purely a
     * performance optimisation that avoids parsing the
     * {@code Authorization} header (and clearing the security context)
     * for endpoints that never require an authenticated principal.
     *
     * @param request the inbound HTTP request; must not be {@code null}
     * @return {@code true} if the request URI matches one of the public
     *         endpoint patterns and the filter should be bypassed for
     *         this request; {@code false} otherwise
     * @throws ServletException declared on the superclass signature
     *                          although this implementation never throws
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        // Exact matches first (kept alphabetically for readability), then
        // prefix matches. The authoritative public-endpoint list lives in
        // SecurityConfig.PUBLIC_PATTERNS; this filter's list is a strict
        // subset of those patterns (the patterns here MUST also be
        // permitAll in SecurityConfig or the filter exclusion is moot).
        return path.equals("/actuator/health")
                || path.equals("/actuator/info")
                || path.equals("/api/auth/signin")
                || path.equals("/swagger-ui.html")
                || path.equals("/swagger-ui")
                || path.equals("/v3/api-docs")
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs/");
    }

    // ---------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------

    /**
     * Extracts the JWT compact string from the {@code Authorization} HTTP
     * header. Returns {@code null} when the header is absent, blank, or
     * does not start with the {@code "Bearer "} prefix.
     *
     * <p>The returned string is the substring after {@code "Bearer "}
     * with any surrounding whitespace trimmed. The prefix check is
     * case-sensitive per RFC 6750 &sect;2.1 (the production scheme name
     * is {@code Bearer} starting with uppercase {@code B}).</p>
     *
     * @param request the inbound HTTP request from which to read the
     *                {@code Authorization} header
     * @return the bearer token string with the {@code "Bearer "} prefix
     *         removed and trimmed of whitespace, or {@code null} if the
     *         header is missing or not a bearer token
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * Maps the COBOL {@code SEC-USR-TYPE} value carried in the JWT
     * {@code userType} claim to a single-element immutable list of Spring
     * Security {@link GrantedAuthority} instances.
     *
     * <p>Mapping rules:</p>
     * <ul>
     *   <li>Exact uppercase {@code "A"} &rarr;
     *       {@code [ROLE_ADMIN]}</li>
     *   <li>Anything else &mdash; including {@code "U"}, {@code null},
     *       blank strings, and any unrecognised value &mdash; defaults to
     *       {@code [ROLE_USER]}</li>
     * </ul>
     *
     * <p>The default-to-USER fallback ensures a cryptographically valid
     * token is never accepted with an empty authority list, which would
     * leave the request neither anonymous nor authorised for any
     * role-gated endpoint. This mirrors the COBOL routing pattern
     * {@code IF CDEMO-USRTYP-ADMIN -> XCTL COADM01C ELSE XCTL COMEN01C}
     * in {@code COSGN00C.cbl} L230-L240, where every non-admin user is
     * routed through the regular {@code COMEN01C} main-menu flow.</p>
     *
     * @param userType the COBOL user-type character from the JWT
     *                 {@code userType} claim; may be {@code null} or
     *                 blank
     * @return an immutable single-element list of granted authorities;
     *         never {@code null}, never empty
     */
    private List<GrantedAuthority> mapUserTypeToAuthorities(String userType) {
        // COBOL: IF CDEMO-USRTYP-ADMIN -> XCTL COADM01C ELSE XCTL COMEN01C
        //        (COSGN00C.cbl L230-L240, COCOM01Y.cpy L27-L28).
        if (USER_TYPE_ADMIN.equals(userType)) {
            return Collections.singletonList(new SimpleGrantedAuthority(ROLE_ADMIN));
        }
        return Collections.singletonList(new SimpleGrantedAuthority(ROLE_USER));
    }
}
