package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Focused web-security test for {@link SecurityConfig}, the Java&nbsp;25 / Spring&nbsp;Boot
 * replacement for the legacy file-based {@code USRSEC} signon ({@code COSGN00C}, transaction
 * {@code CC00}) and the CICS pseudo-conversational {@code COMMAREA} session. Legacy source is
 * referenced read-only at commit SHA {@code 27d6c6f} and is never copied into the target.
 *
 * <p>This suite pins the <strong>URL authorization contract</strong> of the assembled
 * {@link org.springframework.security.web.SecurityFilterChain} &mdash; the exact behaviour the CP2
 * review flagged as a gap: the administrator menu view ({@code GET /api/menu/admin}, the migration
 * of the {@code COADM01C} / {@code CA00} admin menu) must require {@code ROLE_ADMIN} and must never
 * be reachable by a regular {@code ROLE_USER} token, while the regular-user menu view
 * ({@code /api/menu/main}) remains reachable by any authenticated caller. The legacy design routed
 * on {@code SEC-USR-TYPE} ({@code 'A'} &rarr; admin, {@code 'U'} &rarr; user) after a plaintext
 * password compare; here that role indicator is a JWT {@code role} claim that the nested bearer
 * filter turns into a Spring {@code ROLE_ADMIN} / {@code ROLE_USER} authority.</p>
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li><strong>Admin-menu lock-down (the CP2 fix).</strong> {@code GET /api/menu/admin} is
 *       {@code 403} for a {@code ROLE_USER} token, {@code 200} for a {@code ROLE_ADMIN} token, and
 *       {@code 401} for an anonymous caller.</li>
 *   <li><strong>Regular menu stays open to any authenticated caller.</strong>
 *       {@code GET /api/menu/main} is {@code 200} for a {@code ROLE_USER} token and {@code 401}
 *       anonymously &mdash; proving the admin rule did not over-reach onto the shared
 *       {@code /api/menu/{type}} route.</li>
 *   <li><strong>Pre-existing administrator surfaces still enforced.</strong> {@code /api/users/**}
 *       is {@code 403} for {@code ROLE_USER} and {@code 200} for {@code ROLE_ADMIN}.</li>
 *   <li><strong>Public surfaces stay public.</strong> {@code POST /api/auth/signin} and
 *       {@code GET /actuator/health} succeed with no token.</li>
 *   <li><strong>JSON denial shape.</strong> The {@code 401} and {@code 403} bodies carry the shared
 *       {@code ErrorResponse}-shaped payload ({@code status}, {@code error}, {@code code},
 *       {@code message}, {@code path}) with the domain codes {@code UNAUTHORIZED} /
 *       {@code ACCESS_DENIED}, and the correlation-id filter is wired into the chain (its
 *       {@code X-Correlation-Id} response header is present).</li>
 *   <li><strong>BCrypt password encoder.</strong> The exported {@link PasswordEncoder} bean is a
 *       {@link BCryptPasswordEncoder} (the C-003 / Decision&nbsp;Log&nbsp;D-002 upgrade) that
 *       round-trips a password via {@code encode} / {@code matches}.</li>
 * </ol>
 *
 * <p>The test bootstraps a minimal, Boot-free web context ({@code @EnableWebMvc} +
 * {@link SecurityConfig}) with real collaborators &mdash; a {@link JwtService} minting genuine HS256
 * tokens, a real {@link CorrelationIdFilter}, and a plain {@link ObjectMapper} &mdash; plus a tiny
 * probe controller mapping the routes under test. Authorization is therefore exercised end-to-end
 * through the real nested {@code JwtAuthenticationFilter} rather than mocked. No database, AWS, or
 * network is involved, so the suite runs fast and compiles warning-free under {@code -Xlint:all}
 * (Gate&nbsp;2) while contributing to the Gate&nbsp;8 (&ge;80%) JaCoCo coverage. Rationale lives in
 * {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 *
 * @see SecurityConfig
 * @see JwtService
 * @see CorrelationIdFilter
 */
@SpringJUnitWebConfig(classes = SecurityConfigTest.TestContext.class)
@DisplayName("SecurityConfig — JWT authorization contract (admin-menu lock-down, public/denial shape, BCrypt)")
class SecurityConfigTest {

    /** A CardDemo administrator user id (maps from {@code SEC-USR-TYPE 'A'}). */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /** A CardDemo regular user id (maps from {@code SEC-USR-TYPE 'U'}). */
    private static final String REGULAR_USER_ID = "USER0001";

    /** Token {@code role} claim value for administrators (&rarr; {@code ROLE_ADMIN} authority). */
    private static final String ROLE_ADMIN = "ADMIN";

    /** Token {@code role} claim value for regular users (&rarr; {@code ROLE_USER} authority). */
    private static final String ROLE_USER = "USER";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Apply springSecurity() so the assembled SecurityFilterChain (including the nested bearer
        // filter and the correlation-id filter) is exercised exactly as in production.
        this.mockMvc = MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /**
     * Builds an {@code Authorization: Bearer <jwt>} header value from a genuine token minted by the
     * real {@link JwtService}, so the request is authenticated through the production filter path.
     *
     * @param userId the token subject (CardDemo user id)
     * @param role   the token role claim ({@code ADMIN} / {@code USER})
     * @return the full {@code Bearer} header value
     */
    private String bearer(final String userId, final String role) {
        return "Bearer " + this.jwtService.generateToken(userId, role);
    }

    // =====================================================================
    // 1) Admin-menu lock-down — the CP2 critical fix
    // =====================================================================

    @Nested
    @DisplayName("Admin menu (GET /api/menu/admin) — the CP2 admin-only lock-down")
    class AdminMenuAuthorization {

        @Test
        @DisplayName("a ROLE_USER token is DENIED (403) on GET /api/menu/admin")
        void regularUserForbiddenFromAdminMenu() throws Exception {
            mockMvc.perform(get("/api/menu/admin")
                            .header(HttpHeaders.AUTHORIZATION, bearer(REGULAR_USER_ID, ROLE_USER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andExpect(jsonPath("$.path").value("/api/menu/admin"));
        }

        @Test
        @DisplayName("a ROLE_ADMIN token is ALLOWED (200) on GET /api/menu/admin")
        void adminAllowedOnAdminMenu() throws Exception {
            mockMvc.perform(get("/api/menu/admin")
                            .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_USER_ID, ROLE_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(content().string("admin-menu"));
        }

        @Test
        @DisplayName("an anonymous caller is UNAUTHENTICATED (401) on GET /api/menu/admin")
        void anonymousUnauthorizedOnAdminMenu() throws Exception {
            mockMvc.perform(get("/api/menu/admin"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.error").value("Unauthorized"))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.path").value("/api/menu/admin"));
        }

        @Test
        @DisplayName("a nested admin-menu path (/api/menu/admin/options) also requires ROLE_ADMIN")
        void nestedAdminMenuPathAlsoRequiresAdmin() throws Exception {
            // The rule matches "/api/menu/admin/**", so a deeper path is denied to a regular user
            // and allowed to an admin, proving the wildcard leg of ADMIN_MENU_PATHS is effective.
            mockMvc.perform(get("/api/menu/admin/options")
                            .header(HttpHeaders.AUTHORIZATION, bearer(REGULAR_USER_ID, ROLE_USER)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/menu/admin/options")
                            .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_USER_ID, ROLE_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(content().string("admin-menu-options"));
        }
    }

    // =====================================================================
    // 2) Regular menu stays open to any authenticated caller
    // =====================================================================

    @Nested
    @DisplayName("Regular menu (GET /api/menu/main) — reachable by any authenticated caller")
    class RegularMenuAuthorization {

        @Test
        @DisplayName("a ROLE_USER token is ALLOWED (200) on GET /api/menu/main")
        void regularUserAllowedOnMainMenu() throws Exception {
            mockMvc.perform(get("/api/menu/main")
                            .header(HttpHeaders.AUTHORIZATION, bearer(REGULAR_USER_ID, ROLE_USER)))
                    .andExpect(status().isOk())
                    .andExpect(content().string("main-menu"));
        }

        @Test
        @DisplayName("an anonymous caller is UNAUTHENTICATED (401) on GET /api/menu/main")
        void anonymousUnauthorizedOnMainMenu() throws Exception {
            mockMvc.perform(get("/api/menu/main"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    // =====================================================================
    // 3) Pre-existing administrator surfaces still enforced (/api/users/**)
    // =====================================================================

    @Nested
    @DisplayName("Administrator surfaces (/api/users/**) — still ROLE_ADMIN only")
    class AdminPathAuthorization {

        @Test
        @DisplayName("a ROLE_USER token is DENIED (403) on GET /api/users/**")
        void regularUserForbiddenFromUsers() throws Exception {
            mockMvc.perform(get("/api/users/USER0001")
                            .header(HttpHeaders.AUTHORIZATION, bearer(REGULAR_USER_ID, ROLE_USER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("a ROLE_ADMIN token is ALLOWED (200) on GET /api/users/**")
        void adminAllowedOnUsers() throws Exception {
            mockMvc.perform(get("/api/users/USER0001")
                            .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_USER_ID, ROLE_ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    // =====================================================================
    // 4) Public surfaces stay public
    // =====================================================================

    @Nested
    @DisplayName("Public surfaces — signon and safe actuator probes need no token")
    class PublicSurfaces {

        @Test
        @DisplayName("POST /api/auth/signin is permitted without a token")
        void signinIsPublic() throws Exception {
            mockMvc.perform(post("/api/auth/signin"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("token-issued"));
        }

        @Test
        @DisplayName("GET /actuator/health is permitted without a token")
        void actuatorHealthIsPublic() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("UP"));
        }
    }

    // =====================================================================
    // 5) JSON denial shape + correlation-id wiring
    // =====================================================================

    @Nested
    @DisplayName("Denial rendering — shared ErrorResponse JSON shape + correlation-id wiring")
    class DenialRendering {

        @Test
        @DisplayName("the 403 body is JSON with the ACCESS_DENIED code and generic message")
        void forbiddenBodyIsSharedJsonShape() throws Exception {
            mockMvc.perform(get("/api/menu/admin")
                            .header(HttpHeaders.AUTHORIZATION, bearer(REGULAR_USER_ID, ROLE_USER)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andExpect(jsonPath("$.message").value("Access denied"))
                    // The CorrelationIdFilter is positioned in the security chain, so its response
                    // header is present on a denial rendered by the filter-chain error writers.
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));
        }

        @Test
        @DisplayName("the 401 body is JSON with the UNAUTHORIZED code and generic message")
        void unauthorizedBodyIsSharedJsonShape() throws Exception {
            mockMvc.perform(get("/api/menu/admin"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Authentication required"))
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));
        }
    }

    // =====================================================================
    // 6) BCrypt password encoder bean
    // =====================================================================

    @Nested
    @DisplayName("PasswordEncoder bean — BCrypt (C-003 / Decision Log D-002)")
    class PasswordEncoderBean {

        @Test
        @DisplayName("is a BCryptPasswordEncoder that round-trips encode/matches")
        void passwordEncoderIsBcryptAndMatches() {
            assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);

            final String raw = "PA55W0RD";
            final String hash = passwordEncoder.encode(raw);
            // BCrypt embeds a per-hash random salt: the hash is never the raw value, carries the
            // "$2" BCrypt prefix, and verifies via matches().
            assertThat(hash).isNotEqualTo(raw).startsWith("$2");
            assertThat(passwordEncoder.matches(raw, hash)).isTrue();
            assertThat(passwordEncoder.matches("wrong", hash)).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Minimal Boot-free web context: SecurityConfig + real collaborators
    // ------------------------------------------------------------------

    /**
     * Minimal MVC + security context under test. It imports the production {@link SecurityConfig}
     * verbatim and supplies the three collaborators its constructor requires plus a probe controller
     * that maps the exact routes the authorization rules govern. {@code @EnableWebMvc} provides the
     * DispatcherServlet MVC infrastructure without Spring Boot auto-configuration, so nothing but the
     * security rules under test influences the outcome.
     */
    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestContext {

        /**
         * HS256 signing secret used only by this test. It is &ge;32&nbsp;bytes as HS256 requires and
         * is not a production credential; no secret is ever hard-coded in {@code main} sources.
         */
        private static final String TEST_JWT_SECRET = "carddemo-test-jwt-secret-hs256-0123456789";

        /** One-hour token lifetime, matching the production default. */
        private static final long TEST_JWT_TTL_MS = 3_600_000L;

        @Bean
        JwtService jwtService() {
            return new JwtService(TEST_JWT_SECRET, TEST_JWT_TTL_MS);
        }

        @Bean
        CorrelationIdFilter correlationIdFilter() {
            return new CorrelationIdFilter();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /**
     * Tiny probe controller mapping exactly the routes exercised by the authorization assertions.
     * Each handler returns a short marker string so a {@code 200} can be distinguished from a
     * security denial by both status and body.
     */
    @RestController
    static class ProbeController {

        @GetMapping("/api/menu/admin")
        String adminMenu() {
            return "admin-menu";
        }

        @GetMapping("/api/menu/admin/options")
        String adminMenuOptions() {
            return "admin-menu-options";
        }

        @GetMapping("/api/menu/main")
        String mainMenu() {
            return "main-menu";
        }

        @GetMapping("/api/users/{userId}")
        String userDetail() {
            return "user-detail";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }

        @PostMapping("/api/auth/signin")
        String signin() {
            return "token-issued";
        }
    }
}
