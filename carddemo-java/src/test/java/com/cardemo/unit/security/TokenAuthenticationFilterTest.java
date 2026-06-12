package com.cardemo.unit.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.enums.UserType;
import com.cardemo.security.TokenAuthenticationFilter;
import com.cardemo.security.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link TokenAuthenticationFilter} — the bearer-token validation filter that maps a
 * token's {@code userType} to a Spring authority ({@code ROLE_ADMIN} / {@code ROLE_USER}). This is
 * the missing piece behind the CP3 admin-authorization findings: without it, {@code SecurityConfig}'s
 * {@code /api/admin/**} and {@code /api/menu/admin} ADMIN rules could never be satisfied.
 *
 * <p>The filter is intentionally permissive: a missing/invalid token simply leaves the security
 * context empty (so downstream rules return 401/403) and the chain always continues.
 */
class TokenAuthenticationFilterTest {

    private static final String VALID_SECRET = "abcdefghijklmnopqrstuvwxyz0123456";

    private TokenService tokenService;
    private TokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(VALID_SECRET, 3600);
        filter = new TokenAuthenticationFilter(tokenService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a valid ADMIN bearer token yields ROLE_ADMIN authority and the subject as principal")
    void adminTokenGrantsRoleAdmin() throws Exception {
        String token = tokenService.issue("ADMIN001", UserType.ADMIN);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("ADMIN001");
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        // The filter always continues the chain.
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    @DisplayName("a valid USER bearer token yields ROLE_USER authority")
    void userTokenGrantsRoleUser() throws Exception {
        String token = tokenService.issue("USER0001", UserType.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    @DisplayName("no Authorization header leaves the context empty and continues the chain")
    void missingHeaderLeavesContextEmpty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    @DisplayName("an invalid/garbage bearer token leaves the context empty and continues the chain")
    void invalidTokenLeavesContextEmpty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-real-token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    @DisplayName("a non-Bearer Authorization scheme is ignored (context stays empty)")
    void nonBearerSchemeIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
