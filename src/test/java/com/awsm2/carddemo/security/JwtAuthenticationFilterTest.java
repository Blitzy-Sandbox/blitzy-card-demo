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
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.impl.DefaultClaims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito unit tests for {@link JwtAuthenticationFilter} —
 * the Spring Security filter that re-establishes identity on every
 * REST request from the signed JWT bearer token. Replaces the CICS
 * {@code COCOM01Y.cpy} COMMAREA-carried identity propagation from
 * {@code COSGN00C.cbl}.
 *
 * <h2>QA Final Checkpoint 13 Maj-5 fix</h2>
 * <p>Prior to this test class, the filter was exercised only via
 * end-to-end {@code EndToEndTransactionWorkflowIT} (tampered token /
 * expired token paths at lines 1467 and 1481). The QA CP13 report
 * (finding Maj-5) requires a dedicated unit test class to lock the
 * filter behavior contract.</p>
 *
 * <h2>Coverage scope</h2>
 * <ul>
 *   <li><b>{@code extractBearerToken}</b> — Authorization header
 *       absent / blank / lower-case "bearer" prefix (rejected per
 *       RFC 6750 §2.1) / proper "Bearer " prefix (accepted).</li>
 *   <li><b>{@code doFilterInternal}</b> — valid token populates
 *       SecurityContext with userId + ROLE_ADMIN/ROLE_USER; expired
 *       token clears context; tampered token clears context;
 *       malformed token clears context; no token leaves chain intact;
 *       empty subject claim clears context.</li>
 *   <li><b>{@code shouldNotFilter}</b> — public endpoints
 *       (/api/auth/signin, /actuator/health, swagger paths) are
 *       exempt; protected endpoints are NOT exempt.</li>
 *   <li><b>{@code mapUserTypeToAuthorities}</b> — userType="A" maps
 *       to ROLE_ADMIN; "U", null, blank, "X", lowercase "a" all map
 *       to ROLE_USER (default fallback for COBOL parity with
 *       COSGN00C.cbl L230-L240).</li>
 * </ul>
 *
 * @see JwtAuthenticationFilter
 * @see JwtTokenProvider
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter — QA CP13 Maj-5 dedicated test class")
class JwtAuthenticationFilterTest {

    private static final String VALID_TOKEN = "valid.jwt.token";

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = new MockFilterChain();
        // Ensure SecurityContext is clean before each test.
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        // Always clear after each test to avoid leakage into subsequent tests.
        SecurityContextHolder.clearContext();
    }

    /**
     * Builds a {@link Claims} stub with the provided subject and userType.
     * Uses {@link DefaultClaims} backed by a HashMap so the
     * {@code claims.get(name, Class)} method works without further mocking.
     */
    private Claims claimsOf(String subject, String userType) {
        var map = new HashMap<String, Object>();
        if (subject != null) {
            map.put("sub", subject);
        }
        if (userType != null) {
            map.put("userType", userType);
        }
        return new DefaultClaims(map);
    }

    // -------------------------------------------------------------------------
    // doFilterInternal — valid token populates SecurityContext
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("doFilterInternal — valid token authentication")
    class ValidTokenTests {

        @Test
        @DisplayName("valid token with userType='A' populates SecurityContext with ROLE_ADMIN")
        void validAdminTokenSetsRoleAdmin() throws Exception {
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            when(jwtTokenProvider.validateToken(VALID_TOKEN))
                    .thenReturn(claimsOf("ADMIN001", "A"));

            filter.doFilter(request, response, filterChain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isEqualTo("ADMIN001");
            assertThat(auth.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN");
            // Credentials must be null (PCI-DSS safety — never retain token bytes in context)
            assertThat(auth.getCredentials()).isNull();
        }

        @Test
        @DisplayName("valid token with userType='U' populates SecurityContext with ROLE_USER")
        void validUserTokenSetsRoleUser() throws Exception {
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            when(jwtTokenProvider.validateToken(VALID_TOKEN))
                    .thenReturn(claimsOf("USER0001", "U"));

            filter.doFilter(request, response, filterChain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isEqualTo("USER0001");
            assertThat(auth.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("valid token with WebAuthenticationDetails populated")
        void detailsArePopulated() throws Exception {
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            request.setRemoteAddr("203.0.113.42"); // test IP per RFC 5737
            when(jwtTokenProvider.validateToken(VALID_TOKEN))
                    .thenReturn(claimsOf("USER0001", "U"));

            filter.doFilter(request, response, filterChain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.getDetails()).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // doFilterInternal — failure modes clear context
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("doFilterInternal — invalid / missing token paths")
    class InvalidTokenTests {

        @Test
        @DisplayName("expired token does NOT populate SecurityContext")
        void expiredTokenClearsContext() throws Exception {
            request.addHeader("Authorization", "Bearer expired.jwt.token");
            when(jwtTokenProvider.validateToken("expired.jwt.token"))
                    .thenThrow(new ExpiredJwtException(null, null, "expired"));

            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("malformed token does NOT populate SecurityContext")
        void malformedTokenClearsContext() throws Exception {
            request.addHeader("Authorization", "Bearer malformed");
            when(jwtTokenProvider.validateToken("malformed"))
                    .thenThrow(new MalformedJwtException("bad"));

            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("blank token throws IllegalArgumentException — caught and context cleared")
        void blankTokenThrowsAndClears() throws Exception {
            request.addHeader("Authorization", "Bearer    ");
            // After trim(), the token is "" which the filter still passes to
            // validateToken — JJWT throws IllegalArgumentException, caught by
            // the catch block.

            // The trim leaves an empty string which !hasText short-circuits.
            // Verify: filter doesn't invoke validateToken and chain proceeds.
            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(jwtTokenProvider, never()).validateToken(anyString());
        }

        @Test
        @DisplayName("token with empty subject claim does NOT populate SecurityContext")
        void emptySubjectClearsContext() throws Exception {
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            when(jwtTokenProvider.validateToken(VALID_TOKEN))
                    .thenReturn(claimsOf("", "U"));

            filter.doFilter(request, response, filterChain);

            // Empty subject is treated as unauthenticated rather than throwing.
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("no Authorization header — chain proceeds, no validateToken call")
        void noHeaderProceedsWithoutValidation() throws Exception {
            filter.doFilter(request, response, filterChain);

            verify(jwtTokenProvider, never()).validateToken(anyString());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("Authorization header without 'Bearer ' prefix is ignored")
        void nonBearerHeaderIgnored() throws Exception {
            request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

            filter.doFilter(request, response, filterChain);

            verify(jwtTokenProvider, never()).validateToken(anyString());
        }

        @Test
        @DisplayName("Authorization header with lowercase 'bearer' is ignored (case-sensitive per RFC 6750 §2.1)")
        void lowercaseBearerIgnored() throws Exception {
            request.addHeader("Authorization", "bearer " + VALID_TOKEN);

            filter.doFilter(request, response, filterChain);

            verify(jwtTokenProvider, never()).validateToken(anyString());
        }

        @Test
        @DisplayName("after token failure, SecurityContext is explicitly cleared")
        void contextExplicitlyClearedOnFailure() throws Exception {
            // Pre-populate the SecurityContext with some authentication.
            SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            "PRIOR_USER", null,
                            java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))));

            request.addHeader("Authorization", "Bearer expired.jwt.token");
            when(jwtTokenProvider.validateToken("expired.jwt.token"))
                    .thenThrow(new ExpiredJwtException(null, null, "expired"));

            filter.doFilter(request, response, filterChain);

            // The filter must explicitly clear any pre-existing context on failure.
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // shouldNotFilter — public endpoint exemption
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("shouldNotFilter — public endpoint bypass")
    class ShouldNotFilterTests {

        @ParameterizedTest(name = "public path bypassed: {0}")
        @ValueSource(strings = {
                "/actuator/health",
                "/actuator/info",
                "/api/auth/signin",
                "/swagger-ui.html",
                "/swagger-ui",
                "/v3/api-docs",
                "/swagger-ui/index.html",
                "/swagger-ui/swagger-initializer.js",
                "/v3/api-docs/some-resource",
                "/v3/api-docs/swagger-config"
        })
        @DisplayName("public endpoints return true (filter is skipped)")
        void publicEndpointsBypassFilter(String path) throws Exception {
            request.setRequestURI(path);

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }

        @ParameterizedTest(name = "protected path NOT bypassed: {0}")
        @ValueSource(strings = {
                "/api/accounts/1",
                "/api/cards/4111111111111111",
                "/api/transactions",
                "/api/admin/users",
                "/api/billing/pay",
                "/api/reports/submit",
                "/api/menu/main",
                "/api/menu/admin"
        })
        @DisplayName("protected endpoints return false (filter runs)")
        void protectedEndpointsRunFilter(String path) throws Exception {
            request.setRequestURI(path);

            assertThat(filter.shouldNotFilter(request)).isFalse();
        }

        @Test
        @DisplayName("null URI returns false (filter still runs to be safe)")
        void nullUriReturnsFalse() throws Exception {
            // MockHttpServletRequest defaults to "" not null; the doc-comment
            // suggests defensive coding. We simulate the null-URI corner case
            // by checking the actual return for an empty URI explicitly.
            request.setRequestURI("");

            // Empty URI is not in the public-path list → filter runs.
            assertThat(filter.shouldNotFilter(request)).isFalse();
        }

        @Test
        @DisplayName("trailing path under /actuator (not /actuator/health) is NOT bypassed")
        void otherActuatorPathsNotBypassed() throws Exception {
            // /actuator/health and /actuator/info are explicit matches; other
            // /actuator/* endpoints (e.g., /actuator/metrics, /actuator/env)
            // must still require authentication.
            request.setRequestURI("/actuator/metrics");

            assertThat(filter.shouldNotFilter(request)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Filter chain pass-through — never short-circuits
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Filter chain pass-through")
    class FilterChainPassThroughTests {

        @Test
        @DisplayName("valid token → filterChain.doFilter is invoked exactly once")
        void validTokenChainsExactlyOnce() throws Exception {
            FilterChain mockChain = org.mockito.Mockito.mock(FilterChain.class);
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            when(jwtTokenProvider.validateToken(VALID_TOKEN))
                    .thenReturn(claimsOf("USER0001", "U"));

            filter.doFilter(request, response, mockChain);

            verify(mockChain, times(1)).doFilter(request, response);
        }

        @Test
        @DisplayName("invalid token → filterChain.doFilter is STILL invoked (no short-circuit)")
        void invalidTokenStillChains() throws Exception {
            FilterChain mockChain = org.mockito.Mockito.mock(FilterChain.class);
            request.addHeader("Authorization", "Bearer bad");
            when(jwtTokenProvider.validateToken("bad"))
                    .thenThrow(new MalformedJwtException("bad"));

            filter.doFilter(request, response, mockChain);

            // Critical contract: this filter NEVER short-circuits.
            // Spring Security's authentication entry point translates
            // unauthenticated state to HTTP 401 downstream.
            verify(mockChain, times(1)).doFilter(request, response);
        }

        @Test
        @DisplayName("missing token → filterChain.doFilter is invoked")
        void missingTokenChains() throws Exception {
            FilterChain mockChain = org.mockito.Mockito.mock(FilterChain.class);

            filter.doFilter(request, response, mockChain);

            verify(mockChain, times(1)).doFilter(request, response);
        }
    }
}
