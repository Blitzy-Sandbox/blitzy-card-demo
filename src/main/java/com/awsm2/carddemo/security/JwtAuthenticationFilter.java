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
package com.awsm2.carddemo.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Filter that authenticates every incoming HTTP request by validating the
 * {@code Authorization: Bearer <jwt>} header, then populates the Spring
 * Security {@link SecurityContextHolder} with the bearer's identity and
 * granted authorities.
 *
 * <p>Per AAP &sect;0.3.4 and the CP3 checkpoint requirements, this filter:</p>
 * <ul>
 *   <li>Extends {@link OncePerRequestFilter} so it runs exactly once per
 *       request even in dispatcher async / forward / include scenarios.</li>
 *   <li>Validates the JWT BEFORE setting the security context &mdash; an
 *       invalid token never grants authority.</li>
 *   <li>Uppercases user IDs with {@link Locale#US} to mirror the COBOL
 *       {@code USRID} convention (all uppercase, single-byte EBCDIC).</li>
 *   <li>Stores {@code null} credentials in the
 *       {@link UsernamePasswordAuthenticationToken} &mdash; the JWT is
 *       not retained in the security context once it has been validated,
 *       satisfying the requirement that credentials never sit in memory.</li>
 *   <li>Maps each role claim to a {@link SimpleGrantedAuthority} with the
 *       {@code "ROLE_"} prefix expected by Spring Security's role-based
 *       checks ({@code hasRole("ADMIN")} matches {@code ROLE_ADMIN}).</li>
 *   <li>Skips JWT processing entirely for the public endpoint patterns
 *       declared in {@link com.awsm2.carddemo.config.SecurityConfig#PUBLIC_PATTERNS},
 *       to avoid the cost of header parsing on health / docs / auth
 *       endpoints.</li>
 * </ul>
 *
 * <h2>Replaces (AAP &sect;0.1.1)</h2>
 * <p>Replaces: CICS sign-on validation flow (COSGN00C) per-request
 * COMMAREA-based identity check. The COBOL pseudo-conversational pattern
 * relied on CICS reading the saved COMMAREA at every pseudo-conversational
 * re-entry; the Java target validates a self-contained signed token
 * instead.</p>
 *
 * <h2>Error handling discipline</h2>
 * <p>This filter NEVER throws to short-circuit the chain &mdash; it
 * always invokes {@code chain.doFilter(request, response)}. If the token
 * is missing or invalid, the security context is simply left unauthenticated
 * and downstream Spring Security authorization checks decide how to
 * respond (typically with HTTP 401 via the default
 * {@code Http403ForbiddenEntryPoint} or a configured authentication
 * entry point). This keeps the filter free of CORS / pre-flight side
 * effects.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** RFC 6750 token type prefix on the {@code Authorization} header. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Spring Security role authority prefix used by {@code hasRole(...)}. */
    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * URL patterns that bypass JWT processing. Kept in sync with
     * {@link com.awsm2.carddemo.config.SecurityConfig#PUBLIC_PATTERNS}.
     */
    private static final String[] SKIP_PATTERNS = new String[] {
            "/api/auth/**",
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api-docs/**"
    };

    private final JwtTokenProvider jwtTokenProvider;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * @param jwtTokenProvider component that signs / verifies JWTs and
     *                         reads claims; must not be {@code null}
     */
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        // Replaces: COSGN00C pseudo-conversational sign-on flow that
        // re-read COMMAREA on every CICS RETURN/RECEIVE pair.
        this.jwtTokenProvider = Objects.requireNonNull(jwtTokenProvider,
                "jwtTokenProvider must not be null");
    }

    /**
     * Suppress JWT processing on public endpoints (auth, health, docs).
     * Returning {@code true} here causes {@link OncePerRequestFilter} to
     * skip {@link #doFilterInternal(HttpServletRequest, HttpServletResponse, FilterChain)}.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getServletPath();
        if (uri == null || uri.isEmpty()) {
            uri = request.getRequestURI();
        }
        if (uri == null) {
            return false;
        }
        for (String pattern : SKIP_PATTERNS) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validate the JWT (if present) and populate the security context.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        // Replaces: CICS RETURN TRANSID COMMAREA flow where the saved
        // COMMAREA was the only proof of identity on the next interaction.
        String token = extractBearerToken(request);
        if (token == null || token.isBlank()) {
            // No JWT present — leave the context unauthenticated and let
            // Spring Security authorization decide the outcome.
            chain.doFilter(request, response);
            return;
        }

        // Validate BEFORE populating the security context. We use
        // parseAndValidate so we can read claims after a successful check;
        // failure paths are logged at DEBUG to avoid log-flooding from
        // hostile clients that send garbage tokens.
        try {
            Claims claims = jwtTokenProvider.parseAndValidate(token);
            String userId = jwtTokenProvider.getUserId(claims);
            if (userId == null || userId.isBlank()) {
                LOG.debug("JWT validated but subject/userId is empty; leaving unauthenticated");
                chain.doFilter(request, response);
                return;
            }
            // Mirror the COBOL convention: USRID is always uppercase A-Z/0-9.
            String normalizedUserId = userId.toUpperCase(Locale.US);

            List<SimpleGrantedAuthority> authorities = toAuthorities(
                    jwtTokenProvider.getRoles(claims));

            // Credentials are intentionally NULL — we have already proven
            // the token's authenticity; retaining the token in memory
            // would create a PCI-DSS exposure surface.
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            normalizedUserId, null, authorities);
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Authenticated request user={} authorities={}",
                        normalizedUserId, authorities);
            }
        } catch (RuntimeException e) {
            // parseAndValidate throws CardDemoException; getRoles/getUserId
            // throw IllegalArgumentException on null. Any other runtime
            // failure here is treated as authentication absence — never as
            // a 5xx — to avoid leaking JWT internals to clients.
            LOG.debug("JWT processing failed; request will proceed unauthenticated cause={}",
                    e.getMessage());
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Pull the JWT compact string out of the
     * {@code Authorization: Bearer <token>} header, ignoring case on the
     * scheme keyword as RFC 7235 mandates.
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.length() > BEARER_PREFIX.length()
                && trimmed.substring(0, BEARER_PREFIX.length())
                          .equalsIgnoreCase(BEARER_PREFIX)) {
            return trimmed.substring(BEARER_PREFIX.length()).trim();
        }
        // Not a Bearer scheme — return null so the request passes through
        // unauthenticated.
        return null;
    }

    /**
     * Convert role strings from the JWT claim into Spring Security
     * authorities. Idempotent against an existing {@code "ROLE_"} prefix
     * so claims emitted by older issuers continue to work.
     */
    private List<SimpleGrantedAuthority> toAuthorities(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        List<SimpleGrantedAuthority> result = new ArrayList<>(roles.size());
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String authority = role.startsWith(ROLE_PREFIX)
                    ? role
                    : ROLE_PREFIX + role.toUpperCase(Locale.US);
            result.add(new SimpleGrantedAuthority(authority));
        }
        return List.copyOf(result);
    }
}
