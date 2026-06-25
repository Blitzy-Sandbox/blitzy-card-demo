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
package com.carddemo.service;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates each HTTP request from the bearer JSON Web Token (JWT) it
 * carries, establishing the per-request {@code SecurityContext} that downstream
 * authorization rules consult.
 *
 * <p>The filter reads the {@code Authorization: Bearer <token>} header,
 * delegates all token verification and claim extraction to
 * {@link JwtTokenService}, and, for a valid token, populates the
 * {@link SecurityContextHolder} with a {@link UsernamePasswordAuthenticationToken}
 * whose principal is the token subject and whose single granted authority is
 * {@code ROLE_ADMIN} (user-type {@code 'A'}) or {@code ROLE_USER}
 * (user-type {@code 'U'}). The subject and user-type correspond to the legacy
 * {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} fields once carried in the
 * CICS COMMAREA; here they are reconstructed from the token on every request, so
 * no server-side session is retained.</p>
 *
 * <p>The filter is purely additive: it never rejects a request. A missing,
 * malformed, expired, or otherwise invalid token leaves the context
 * unauthenticated and the chain proceeds; access to protected routes is then
 * denied by the configured {@code AuthenticationEntryPoint}. The filter holds no
 * signing secret, performs no error rendering, and is immutable after
 * construction, so a single instance is safe to share across threads.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** HTTP header inspected for the bearer token. */
    private static final String AUTH_HEADER = "Authorization";

    /** Scheme prefix that must precede the encoded token within {@link #AUTH_HEADER}. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** User-type code that grants administrative authority; mirrors COMMAREA {@code CDEMO-USRTYP-ADMIN}. */
    private static final String USER_TYPE_ADMIN = "A";

    /** Authority granted to administrators; matches {@code hasRole("ADMIN")} in the security configuration. */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** Authority granted to standard users; matches {@code hasRole("USER")} in the security configuration. */
    private static final String ROLE_USER = "ROLE_USER";

    private final JwtTokenService jwtTokenService;

    /**
     * Creates the filter with the token service that owns all JWT verification
     * and claim extraction.
     *
     * @param jwtTokenService the service used to validate tokens and read the
     *                        subject and user-type claims; never {@code null}
     */
    public JwtAuthenticationFilter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = resolveBearerToken(request);
        if (token != null
                && SecurityContextHolder.getContext().getAuthentication() == null
                && jwtTokenService.validateToken(token)) {
            authenticate(request, token);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the encoded token from the {@code Authorization} header.
     *
     * @param request the inbound request
     * @return the token following the {@code "Bearer "} prefix, or {@code null}
     *         when the header is absent or does not use the bearer scheme
     */
    private static String resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * Builds the authentication from a token already proven valid and stores it
     * in the {@link SecurityContextHolder} for the remainder of the request.
     *
     * @param request the inbound request, used to attach web authentication details
     * @param token   the verified bearer token whose claims identify the caller
     */
    private void authenticate(HttpServletRequest request, String token) {
        String userId = jwtTokenService.getUserId(token);
        String userType = jwtTokenService.getUserType(token);
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(resolveRole(userType));
        List<SimpleGrantedAuthority> authorities = List.of(authority);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * Maps the single-character user-type claim to its Spring Security authority.
     *
     * @param userType the {@code CDEMO-USER-TYPE} claim value, may be {@code null}
     * @return {@link #ROLE_ADMIN} for {@code 'A'}; otherwise {@link #ROLE_USER}
     */
    private static String resolveRole(String userType) {
        return USER_TYPE_ADMIN.equals(userType) ? ROLE_ADMIN : ROLE_USER;
    }
}
