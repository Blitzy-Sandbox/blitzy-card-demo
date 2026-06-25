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
package com.carddemo.unit.service;

import java.io.IOException;

import com.carddemo.service.JwtAuthenticationFilter;
import com.carddemo.service.JwtTokenService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link JwtAuthenticationFilter}, the
 * {@link org.springframework.web.filter.OncePerRequestFilter} that authenticates
 * every inbound HTTP request from the bearer JSON Web Token (JWT) it carries.
 *
 * <p>The filter is modern infrastructure with no 1:1 COBOL ancestor: it replaces
 * the legacy CICS pseudo-conversational {@code COMMAREA} (copybook {@code COCOM01Y})
 * carry-over of {@code CDEMO-USER-ID}/{@code CDEMO-USER-TYPE} with a stateless,
 * per-request {@code SecurityContext} reconstructed from the token, so no
 * server-side session is retained (AAP&nbsp;&sect;0.1.1.1, &sect;0.3.2). For a valid
 * token the filter grants exactly one authority &mdash; {@code ROLE_ADMIN} for
 * user-type {@code "A"}, otherwise {@code ROLE_USER}.</p>
 *
 * <p>The suite is deliberately framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong> {@code @SpringBootTest},
 * {@code MockMvc}, {@code spring-security-test} ({@code @WithMockUser}), or
 * Testcontainers, and touches no database, AWS, or network resource. The single
 * real collaborator, {@link JwtTokenService}, is Mockito-mocked, as are the
 * {@link HttpServletRequest}, {@link HttpServletResponse}, and {@link FilterChain}
 * passed through the servlet contract. This keeps the test fast and isolated while
 * still asserting the complete authentication contract.</p>
 *
 * <p><strong>Invocation entry point.</strong> {@code doFilterInternal} is
 * {@code protected} and therefore not visible from this {@code com.carddemo.unit.service}
 * package; the tests instead drive the filter through its inherited
 * {@code public final}
 * {@link org.springframework.web.filter.OncePerRequestFilter#doFilter(jakarta.servlet.ServletRequest,
 * jakarta.servlet.ServletResponse, FilterChain) doFilter(request, response, chain)}
 * entry point, exactly as the servlet container would.</p>
 *
 * <p><strong>Context isolation.</strong> Because {@code SecurityContextHolder}
 * stores the authentication in a thread-local, the context is cleared both
 * {@link BeforeEach} and {@link AfterEach} test so that authentication established
 * by one test can never leak into another (or into an unrelated test sharing the
 * Surefire worker thread).</p>
 *
 * <p>Every behavior is observed only through the public {@code SecurityContextHolder}
 * state and Mockito verifications of the mocked collaborators, keeping the test
 * decoupled from the filter's internals and clean under the project's zero-warning
 * ({@code -Werror}) build.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter — per-request bearer-token authentication")
class JwtAuthenticationFilterTest {

    /**
     * Name of the HTTP header the filter inspects for the bearer token; mirrors the
     * filter's own private {@code AUTH_HEADER} constant, which is not visible here.
     */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** The mocked token service that owns all JWT verification and claim extraction. */
    @Mock
    private JwtTokenService jwtTokenService;

    /** The mocked inbound request whose {@code Authorization} header drives each scenario. */
    @Mock
    private HttpServletRequest request;

    /** The mocked response; the filter only forwards it down the chain, never writing to it. */
    @Mock
    private HttpServletResponse response;

    /** The mocked downstream chain; verified to always proceed (the filter never sends a 401). */
    @Mock
    private FilterChain filterChain;

    /** System under test, constructor-injected with the single {@link JwtTokenService} collaborator. */
    @InjectMocks
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void clearContextBefore() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContextAfter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("admin bearer token authenticates the request with ROLE_ADMIN")
    void adminTokenPopulatesRoleAdmin() throws ServletException, IOException {
        when(request.getHeader(AUTHORIZATION_HEADER)).thenReturn("Bearer good-admin-token");
        when(jwtTokenService.validateToken("good-admin-token")).thenReturn(true);
        when(jwtTokenService.getUserId("good-admin-token")).thenReturn("ADMIN001");
        when(jwtTokenService.getUserType("good-admin-token")).thenReturn("A");

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo("ADMIN001");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("standard-user bearer token authenticates the request with ROLE_USER")
    void userTokenPopulatesRoleUser() throws ServletException, IOException {
        when(request.getHeader(AUTHORIZATION_HEADER)).thenReturn("Bearer good-user-token");
        when(jwtTokenService.validateToken("good-user-token")).thenReturn(true);
        when(jwtTokenService.getUserId("good-user-token")).thenReturn("USER0001");
        when(jwtTokenService.getUserType("good-user-token")).thenReturn("U");

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo("USER0001");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("absent Authorization header leaves the context unauthenticated and proceeds")
    void missingHeaderLeavesContextUnauthenticated() throws ServletException, IOException {
        when(request.getHeader(AUTHORIZATION_HEADER)).thenReturn(null);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtTokenService, never()).validateToken(any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("non-Bearer Authorization scheme is ignored; the chain still proceeds")
    void nonBearerHeaderIsIgnored() throws ServletException, IOException {
        when(request.getHeader(AUTHORIZATION_HEADER)).thenReturn("Basic not-a-real-credential");

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtTokenService, never()).validateToken(any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("invalid bearer token sets no authentication and never sends 401; the chain proceeds")
    void invalidTokenSetsNoAuthenticationAndProceeds() throws ServletException, IOException {
        when(request.getHeader(AUTHORIZATION_HEADER)).thenReturn("Bearer bad-token");
        when(jwtTokenService.validateToken("bad-token")).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtTokenService).validateToken("bad-token");
        verifyNoMoreInteractions(jwtTokenService);
        verify(filterChain).doFilter(request, response);
    }
}
