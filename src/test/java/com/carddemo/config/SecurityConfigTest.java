package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.controller.AccountController;
import com.carddemo.controller.AuthController;
import com.carddemo.controller.UserController;
import com.carddemo.dto.SignonResponse;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
import com.carddemo.service.JwtService;
import com.carddemo.service.SignonService;
import com.carddemo.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Spring MVC web-slice test for {@link SecurityConfig} &mdash; the Java&nbsp;25 / Spring&nbsp;Boot 3.x
 * replacement for the legacy AWS CardDemo file-based {@code USRSEC} / RACF-style signon
 * ({@code app/cbl/COSGN00C.cbl}, transaction {@code CC00}) and the CICS pseudo-conversational
 * {@code COMMAREA} session. The COBOL source is referenced read-only at commit SHA {@code 27d6c6f}
 * and is never copied into the target.
 *
 * <h2>What this suite pins</h2>
 * <p>It boots the real Spring MVC web slice together with the production security assembly &mdash;
 * the assigned {@link SecurityConfig}, the real {@link JwtService} minting genuine HS256 tokens, and
 * the real {@link CorrelationIdFilter} &mdash; wired against the three controllers the authorization
 * matrix touches ({@link AuthController}, {@link AccountController}, {@link UserController}). Because
 * the genuine {@code JwtService} and the nested bearer filter are in the chain, authorization is
 * exercised end-to-end with <strong>real</strong> {@code Authorization: Bearer &lt;jwt&gt;} headers
 * rather than the {@code @WithMockUser} shortcut, which would bypass the token path. Behaviours
 * verified:</p>
 * <ol>
 *   <li><strong>BCrypt password encoder</strong> &mdash; the exported {@link PasswordEncoder} bean is
 *       a {@link BCryptPasswordEncoder} that round-trips {@code encode}/{@code matches} (the
 *       C-003 / Decision&nbsp;Log&nbsp;D-002 upgrade of the legacy plaintext {@code SEC-USR-PWD}
 *       compare in {@code COSGN00C}).</li>
 *   <li><strong>permitAll surfaces</strong> &mdash; {@code POST /api/auth/login} (the signon
 *       transaction {@code CC00}) and the safe actuator probes need no token.</li>
 *   <li><strong>Stateless authentication</strong> &mdash; a protected resource with no token yields
 *       {@code 401} and an {@code ErrorResponse}-shaped JSON body, a valid {@code USER} token is
 *       admitted, and a malformed bearer token also yields {@code 401}.</li>
 *   <li><strong>Role matrix</strong> &mdash; the administrator surface {@code /api/users} is
 *       {@code 403} for a {@code ROLE_USER} token and {@code 200} for a {@code ROLE_ADMIN} token,
 *       reproducing the legacy {@code SEC-USR-TYPE} routing ({@code 'A'} &rarr; admin,
 *       {@code 'U'} &rarr; user).</li>
 *   <li><strong>STATELESS session policy</strong> &mdash; no server-side {@code HttpSession} is ever
 *       created (the {@code COMMAREA} replacement, AAP&nbsp;&sect;0.8.4).</li>
 * </ol>
 *
 * <h2>Harness notes</h2>
 * <p>The controllers' own service collaborators are replaced with Mockito bean overrides so the
 * controllers can be instantiated in the slice without a database, AWS, or network; the security
 * decision (200 vs 401 vs 403) &mdash; not the response body &mdash; is what each authorization test
 * asserts. {@link MockitoBean} is used in place of the deprecated {@code @MockBean} so the file
 * compiles warning-free under {@code -Xlint:all} (Gate&nbsp;2). The {@code @TestPropertySource} secret
 * is a non-production, &ge;32-byte HS256 key that lets the real {@link JwtService} sign and verify
 * within the slice regardless of profile-property resolution (the {@code JWT_SECRET} env var is not
 * set in test). Rationale lives in {@code docs/decision-log.md}, not in these comments (Explainability
 * rule).</p>
 *
 * @see SecurityConfig
 * @see JwtService
 * @see CorrelationIdFilter
 */
@WebMvcTest(controllers = {AuthController.class, AccountController.class, UserController.class})
@Import({SecurityConfig.class, JwtService.class, CorrelationIdFilter.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // Non-production, >=32-byte HS256 secret so the real JwtService can sign/verify inside the slice
    // even though the JWT_SECRET env var is unset and no application-test.yml is present.
    "carddemo.security.jwt.secret=test-only-jwt-secret-not-used-in-production-0123456789",
    "carddemo.security.jwt.expiration-ms=3600000"
})
@DisplayName("SecurityConfig — stateless JWT security posture, authorization matrix, and BCrypt (COSGN00C @ 27d6c6f)")
class SecurityConfigTest {

    /** A protected account-view resource (transaction {@code CAVW}); reachable by any authenticated role. */
    private static final String ACCOUNT_PATH = "/api/accounts/1";

    /** The administrator-only user-list surface (transactions {@code CU00}&ndash;{@code CU03}). */
    private static final String USERS_PATH = "/api/users";

    /** The public signon endpoint (transaction {@code CC00} / program {@code COSGN00C}); permitAll. */
    private static final String LOGIN_PATH = "/api/auth/login";

    /** A safe, public actuator probe; permitAll. */
    private static final String HEALTH_PATH = "/actuator/health";

    /** A regular CardDemo user id (8 chars; maps from {@code SEC-USR-TYPE 'U'} &rarr; {@code ROLE_USER}). */
    private static final String USER_ID = "USER0001";

    /** An administrator CardDemo user id (8 chars; maps from {@code SEC-USR-TYPE 'A'} &rarr; {@code ROLE_ADMIN}). */
    private static final String ADMIN_ID = "ADMIN001";

    /** Token {@code role} claim value for regular users. */
    private static final String ROLE_USER = "USER";

    /** Token {@code role} claim value for administrators. */
    private static final String ROLE_ADMIN = "ADMIN";

    /**
     * Minimal valid signon body: both fields are non-blank and within the legacy {@code PIC X(08)}
     * width, so Jakarta Bean Validation on {@code SignonRequest} passes and the (mocked) service is
     * reached. The password legitimately travels in the request; the endpoint is permitAll.
     */
    private static final String LOGIN_BODY = "{\"userId\":\"USER0001\",\"password\":\"PASS1234\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** {@link AuthController} collaborator; stubbed on the permitAll login path. */
    @MockitoBean
    private SignonService signonService;

    /** {@link AccountController} collaborator; returns {@code null} (200) on the admitted USER path. */
    @MockitoBean
    private AccountViewService accountViewService;

    /** Required to satisfy the {@link AccountController} constructor inside the slice. */
    @MockitoBean
    private AccountUpdateService accountUpdateService;

    /** {@link UserController} collaborator; returns {@code null} (200) on the admitted ADMIN path. */
    @MockitoBean
    private UserService userService;

    /**
     * Builds an {@code Authorization: Bearer <jwt>} header value from a genuine token minted by the
     * real {@link JwtService}, so the request is authenticated through the production nested
     * {@code JwtAuthenticationFilter} rather than a test shortcut.
     *
     * @param userId the token subject (CardDemo user id)
     * @param role   the token {@code role} claim ({@code ADMIN} / {@code USER})
     * @return the full {@code Bearer} header value
     */
    private String bearer(final String userId, final String role) {
        return "Bearer " + jwtService.generateToken(userId, role);
    }

    // =====================================================================
    // 1) PasswordEncoder bean is BCrypt (C-003 / Decision Log D-002)
    // =====================================================================

    @Test
    @DisplayName("PasswordEncoder bean is BCrypt and round-trips encode/matches (COSGN00C plaintext -> BCrypt, C-003/D-002)")
    void passwordEncoderIsBCryptRoundTrip() {
        // Parity for the legacy plaintext SEC-USR-PWD compare in app/cbl/COSGN00C.cbl (@ SHA 27d6c6f),
        // upgraded to a BCrypt hash comparison (Constraint C-003 / Decision Log D-002).
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);

        final String hash = passwordEncoder.encode("Password1");
        // BCrypt embeds a per-hash random salt: the hash carries the "$2" prefix and is never the raw value.
        assertThat(hash).startsWith("$2").isNotEqualTo("Password1");
        assertThat(passwordEncoder.matches("Password1", hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong", hash)).isFalse();
    }

    // =====================================================================
    // 2) permitAll surfaces — reachable without a token
    // =====================================================================

    @Test
    @DisplayName("POST /api/auth/login is permitAll (no token) and reaches the handler -> 200 with the issued token")
    void loginIsPermitAll() throws Exception {
        // The signon endpoint issues the token, so it must be reachable without prior authentication.
        final SignonResponse issued = new SignonResponse(
                "jwt-token", SignonResponse.BEARER, USER_ID, "John", "Doe", SignonResponse.ROLE_USER);
        when(signonService.authenticate(any())).thenReturn(issued);

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                // Proves the request passed security (not 401/403) and the handler returned the service result.
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.role").value(SignonResponse.ROLE_USER));
    }

    @Test
    @DisplayName("GET /actuator/health is permitAll (no token) — security does not block it (not 401/403)")
    void actuatorHealthIsPermitAll() throws Exception {
        // NOTE (slice limitation): @WebMvcTest does not auto-configure the Actuator, so no handler is
        // mapped to /actuator/health here; the request therefore resolves to 404. The security ASSERTION
        // is that the permitAll rule lets it through the filter chain — i.e. it is NOT blocked with a 401
        // (unauthenticated) or 403 (forbidden). A 404 (handler-absent) still proves permitAll succeeded.
        final int statusCode = mockMvc.perform(get(HEALTH_PATH))
                .andReturn().getResponse().getStatus();
        assertThat(statusCode).isNotEqualTo(401).isNotEqualTo(403);
    }

    // =====================================================================
    // 3) Stateless authentication — no token / valid token / malformed token
    // =====================================================================

    @Test
    @DisplayName("no token on a protected resource -> 401 with an ErrorResponse-shaped JSON body (UNAUTHORIZED)")
    void noTokenOnProtectedResourceReturns401() throws Exception {
        mockMvc.perform(get(ACCOUNT_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.path").value(ACCOUNT_PATH))
                // The CorrelationIdFilter is wired into the chain, so the denial body carries a correlation id
                // and the response echoes the X-Correlation-Id header (Observability rule).
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("valid USER token -> protected account resource is admitted (not 401/403 -> 200)")
    void userTokenAllowedOnAccounts() throws Exception {
        // Account View (CAVW) is reachable by any authenticated role; the mocked service returns null,
        // which the controller wraps as 200 OK. The body is irrelevant here — the point is that a genuine
        // USER token authenticates through the real JWT filter and passes the "authenticated" rule.
        mockMvc.perform(get(ACCOUNT_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(USER_ID, ROLE_USER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("malformed bearer token on a protected resource -> 401 (token rejected, request stays anonymous)")
    void malformedTokenReturns401() throws Exception {
        // A non-JWT bearer value fails validation in the nested filter; the request proceeds
        // unauthenticated and the authorization rules + entry point produce the 401.
        mockMvc.perform(get(ACCOUNT_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // =====================================================================
    // 4) Role matrix — /api/users is administrator-only (SEC-USR-TYPE 'A')
    // =====================================================================

    @Test
    @DisplayName("USER token on GET /api/users -> 403 with an ErrorResponse-shaped JSON body (ACCESS_DENIED)")
    void userTokenForbiddenOnUsers() throws Exception {
        // Defense-in-depth: the URL rule (/api/users/** hasRole ADMIN) and the controller's
        // @PreAuthorize("hasRole('ADMIN')") both route a ROLE_USER caller to the accessDeniedHandler.
        mockMvc.perform(get(USERS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(USER_ID, ROLE_USER)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(jsonPath("$.path").value(USERS_PATH));
    }

    @Test
    @DisplayName("ADMIN token on GET /api/users -> admitted (not 401/403 -> 200)")
    void adminTokenAllowedOnUsers() throws Exception {
        // A ROLE_ADMIN token clears both the URL rule and the method-level @PreAuthorize; the mocked
        // service returns null, which the controller wraps as 200 OK.
        mockMvc.perform(get(USERS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, ROLE_ADMIN)))
                .andExpect(status().isOk());
    }

    // =====================================================================
    // 5) STATELESS session policy — the COMMAREA replacement
    // =====================================================================

    @Test
    @DisplayName("SessionCreationPolicy.STATELESS -> no HttpSession is created for an authenticated request")
    void statelessNoHttpSessionCreated() throws Exception {
        final MvcResult result = mockMvc.perform(get(ACCOUNT_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(USER_ID, ROLE_USER)))
                .andExpect(status().isOk())
                .andReturn();

        // STATELESS means Spring Security never establishes a server-side session (the CICS COMMAREA
        // pseudo-conversational state is carried entirely by the JWT instead).
        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
