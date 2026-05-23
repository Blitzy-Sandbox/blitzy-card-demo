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
package com.aws.carddemo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Spring Security filter that authenticates HTTP requests carrying an opaque
 * Bearer session token (issued by {@code AuthController.signOn} and stored in
 * the {@link SessionTokenRegistry}) by looking up the token, mapping the
 * COBOL-equivalent {@code SEC-USR-TYPE} to a Spring Security role authority,
 * and populating {@link SecurityContextHolder} for the duration of the
 * request.
 *
 * <h2>Wire Contract</h2>
 *
 * <p>The filter reads the {@code Authorization} request header and matches
 * the canonical Bearer scheme:
 * <pre>
 *   Authorization: Bearer 550e8400-e29b-41d4-a716-446655440000
 * </pre>
 *
 * <p>The token portion (everything after {@code "Bearer "}) is passed to
 * {@link SessionTokenRegistry#lookup(String)}. When the lookup succeeds the
 * filter constructs an {@link UsernamePasswordAuthenticationToken} carrying
 * the principal's userId and a single granted authority — either
 * {@code ROLE_ADMIN} (mapped from {@code SEC-USR-TYPE = 'A'}) or
 * {@code ROLE_USER} (mapped from {@code SEC-USR-TYPE = 'U'}). The token is
 * marked authenticated (the no-credentials constructor is used) and placed
 * on the {@link SecurityContextHolder}.
 *
 * <h2>Why ROLE_ADMIN, not ADMIN</h2>
 *
 * <p>Spring Security's {@code hasRole(...)} expression-language helper
 * prepends {@code "ROLE_"} to its argument, so a controller annotated
 * {@code @PreAuthorize("hasRole('ADMIN')")} matches the granted authority
 * {@code "ROLE_ADMIN"}. The mapping is therefore: COBOL
 * {@code SEC-USR-TYPE = 'A'} → granted authority {@code "ROLE_ADMIN"} →
 * matches {@code hasRole('ADMIN')}.
 *
 * <h2>Anonymous Passthrough</h2>
 *
 * <p>When the {@code Authorization} header is absent, malformed, or carries
 * an unknown token, the filter leaves {@link SecurityContextHolder} as-is
 * (typically an anonymous authentication produced by Spring Security's
 * {@code AnonymousAuthenticationFilter} later in the chain) and forwards
 * the request to the next filter. The downstream
 * {@code authorizeHttpRequests} configuration in {@code SecurityConfig}
 * then decides whether the endpoint requires authentication: permit-all
 * endpoints serve the request anonymously; protected endpoints return
 * HTTP 401 via {@code AuthenticationEntryPoint}.
 *
 * <h2>Filter Ordering (AAP §0.10.2 Minimal Change Clause)</h2>
 *
 * <p>{@code SecurityConfig} registers this filter via
 * {@code addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)}
 * so it runs early in the chain — before {@code AnonymousAuthenticationFilter}
 * and before any
 * {@code BasicAuthenticationFilter} / {@code UsernamePasswordAuthenticationFilter}
 * that Spring Boot might auto-configure. The {@link OncePerRequestFilter}
 * base class guarantees the filter executes exactly once per HTTP request
 * even when the request is forwarded internally (e.g., when Spring MVC
 * dispatches an error page).
 *
 * <h2>SecurityContext Cleanup</h2>
 *
 * <p>Spring Security's {@code SecurityContextHolderFilter}
 * (servlet-container-level) clears {@link SecurityContextHolder} after the
 * filter chain finishes, so this filter does NOT need an explicit
 * {@code finally} block to reset the holder.
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>The COBOL CardDemo authentication boundary is the CICS sign-on
 * transaction; subsequent transactions inherit the authenticated user
 * context from the CICS region. The Java migration has no such ambient
 * context, so a per-request token-resolution filter is the smallest
 * mechanism that reconstitutes the authenticated principal between calls.
 *
 * <h2>Bean Registration</h2>
 *
 * <p>This class is NOT annotated {@code @Component} on purpose: it implements
 * {@link jakarta.servlet.Filter} indirectly via {@link OncePerRequestFilter},
 * and Spring Boot's {@code @WebMvcTest} slice test annotation auto-scans for
 * {@code Filter} beans. Adding {@code @Component} would cause every
 * {@code @WebMvcTest}-based controller test in the project to attempt to
 * instantiate this filter — and because the filter's constructor depends on
 * {@link SessionTokenRegistry} (which is NOT picked up by {@code @WebMvcTest}
 * scans), the context refresh would fail with
 * {@code UnsatisfiedDependencyException}. Declaring the filter as a
 * {@code @Bean} on {@code SecurityConfig} instead binds it to the production
 * security context only — {@code @WebMvcTest} controller slice tests do not
 * load {@code SecurityConfig} unless they explicitly import it, and
 * {@code AuthControllerTest} already supplies a {@code @MockBean
 * SessionTokenRegistry} so it can wire the controller's constructor.
 *
 * @see SessionTokenRegistry
 * @see com.aws.carddemo.config.SecurityConfig
 */
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    /**
     * The Bearer-token scheme prefix per RFC 6750 §2.1.
     */
    static final String BEARER_PREFIX = "Bearer ";

    /**
     * COBOL {@code SEC-USR-TYPE} sentinel for the admin user-type per
     * {@code CSUSR01Y.cpy}. Mirrored from
     * {@code AuthenticationService.USER_TYPE_ADMIN} but redeclared here so
     * this class does not depend on the service package.
     */
    static final String USER_TYPE_ADMIN = "A";

    /**
     * Spring Security role authority granted to admin sign-on sessions
     * ({@code SEC-USR-TYPE = 'A'}). The {@code "ROLE_"} prefix is required
     * for {@code @PreAuthorize("hasRole('ADMIN')")} matching.
     */
    static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * Spring Security role authority granted to regular sign-on sessions
     * ({@code SEC-USR-TYPE = 'U'}).
     */
    static final String ROLE_USER = "ROLE_USER";

    private final SessionTokenRegistry sessionTokenRegistry;

    /**
     * Constructs the filter with the registry whose tokens it will resolve.
     *
     * @param sessionTokenRegistry the in-memory token store; must not be
     *                             {@code null}
     */
    public TokenAuthenticationFilter(SessionTokenRegistry sessionTokenRegistry) {
        this.sessionTokenRegistry = sessionTokenRegistry;
    }

    /**
     * Resolves the Bearer token from the {@code Authorization} header,
     * looks it up in the registry, and populates {@link SecurityContextHolder}
     * when the lookup succeeds. Passes through to the next filter in all
     * cases — anonymous requests, malformed Authorization headers, and
     * unknown tokens are all forwarded to let {@code authorizeHttpRequests}
     * decide the response status.
     *
     * @param request     the incoming HTTP request
     * @param response    the HTTP response (unused — filter does not write
     *                    headers or body)
     * @param filterChain the downstream filter chain
     * @throws ServletException propagated from the chain
     * @throws IOException      propagated from the chain
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token != null) {
            Optional<SessionTokenRegistry.AuthenticatedPrincipal> principalOpt =
                    sessionTokenRegistry.lookup(token);
            if (principalOpt.isPresent()) {
                SessionTokenRegistry.AuthenticatedPrincipal principal = principalOpt.get();
                String role = USER_TYPE_ADMIN.equals(principal.userType()) ? ROLE_ADMIN : ROLE_USER;
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal.userId(),
                                null,
                                List.of(new SimpleGrantedAuthority(role)));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the Bearer-scheme token from the {@code Authorization} header.
     * Returns {@code null} when the header is absent, the scheme is not
     * Bearer, or the token portion is blank.
     *
     * <p>The Bearer scheme name is compared case-insensitively per RFC 6750
     * §2.1, but the canonical form ({@code "Bearer "}) is the case the test
     * suite produces via {@code HttpHeaders.setBearerAuth}.
     *
     * @param request the incoming HTTP request
     * @return the raw token portion (everything after {@code "Bearer "}),
     *         or {@code null} when no Bearer credential is present
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
