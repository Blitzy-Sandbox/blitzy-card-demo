package com.carddemo.controller;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.carddemo.config.SecurityConfig;
import com.carddemo.dto.MenuOption;
import com.carddemo.dto.MenuResponse;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.JwtService;
import com.carddemo.service.MenuService;

/**
 * Web-slice test for {@link MenuController} &mdash; the stateless REST replacement for the two legacy
 * CICS menu programs of the CardDemo application: {@code COMEN01C} (main menu, transaction
 * {@code CM00}) and {@code COADM01C} (admin menu, transaction {@code CA00}). The frozen COBOL source
 * is referenced read-only at commit SHA {@code 27d6c6f} and is never copied into the target.
 *
 * <p><b>What is verified.</b> The legacy programs presented a role-dependent option set and gated the
 * administrator functions behind the {@code SEC-USR-TYPE} indicator ({@code COMEN01C}
 * {@code PROCESS-ENTER-KEY} rejected a regular user's admin-only selection with
 * {@code "No access - Admin Only option... "}). In the migrated system that becomes <em>stateless,
 * role-based routing</em>: {@code GET /api/menu} returns the role-appropriate menu to any
 * authenticated caller, while {@code GET /api/admin/menu} is administrator-only. This suite pins that
 * contract end-to-end through the real Spring Security filter chain (Gate&nbsp;5, interface-contract
 * verification), proving the method-security gating actually enforces:</p>
 * <ul>
 *   <li>{@code GET /api/menu} &rarr; {@code 200} for a {@code USER} <em>and</em> for an {@code ADMIN},
 *       {@code 401} for an anonymous caller;</li>
 *   <li>{@code GET /api/admin/menu} &rarr; {@code 200} only for an {@code ADMIN}, {@code 403} for a
 *       {@code USER} (and the service is never consulted), {@code 401} for an anonymous caller;</li>
 *   <li>the regular-user main menu never carries an administrator-only option (the migrated form of
 *       the {@code COMEN01C} visibility filter);</li>
 *   <li>the single-option resolution endpoint honors its {@code @Min(1)} lower-bound edit.</li>
 * </ul>
 *
 * <p><b>Harness.</b> The test uses a {@link WebMvcTest @WebMvcTest} slice around
 * {@code MenuController} and {@link Import @Import}s the real {@link SecurityConfig} (so the
 * {@code @EnableMethodSecurity} {@code @PreAuthorize} gate and the URL authorization rules are
 * assembled exactly as in production) together with the real {@link CorrelationIdFilter}. Because the
 * slice wires Spring Security, {@code spring-security-test}'s {@link WithMockUser @WithMockUser}
 * establishes the caller's authorities (the {@code roles} attribute auto-prefixes {@code ROLE_}), and
 * a test with no annotation exercises the anonymous path. The two collaborators are supplied as
 * Mockito test doubles: {@link MenuService} (the behavior under delegation) and {@link JwtService}
 * (required only to satisfy the {@link SecurityConfig} constructor; the bearer filter is never
 * triggered because these requests carry no {@code Authorization} header).</p>
 *
 * <p><b>On {@code @MockitoBean} vs {@code @MockBean}.</b> The Spring Boot {@code @MockBean} annotation
 * is deprecated for removal since Boot&nbsp;3.4; referencing it would raise a {@code [deprecation]}
 * warning under the project's {@code -Xlint:all} build (Gate&nbsp;2, zero-warning). This test
 * therefore uses the functionally equivalent, non-deprecated
 * {@link MockitoBean @MockitoBean} (Spring&nbsp;Framework&nbsp;6.2) to register the same mocks in the
 * slice context, preserving the intended harness while keeping the compilation warning-free.</p>
 */
@WebMvcTest(controllers = MenuController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class})
@DisplayName("MenuController — main menu (CM00/COMEN01C) + admin menu (CA00/COADM01C) REST endpoints")
class MenuControllerTest {

    /** Role-appropriate menu route; reachable by any authenticated caller. */
    private static final String MENU_PATH = "/api/menu";

    /** Administrator-only menu route ({@code COADM01C} / {@code CA00}). */
    private static final String ADMIN_MENU_PATH = "/api/admin/menu";

    /** Single-option resolution route; a 1-based option number is appended. */
    private static final String OPTIONS_PATH = "/api/menu/options/";

    /** Role token the controller derives for a regular user and hands to {@link MenuService}. */
    private static final String ROLE_USER = "USER";

    /** Role token the controller derives for an administrator and hands to {@link MenuService}. */
    private static final String ROLE_ADMIN = "ADMIN";

    /** {@link MenuResponse#menuType()} discriminator for the main (regular-user) menu. */
    private static final String MENU_TYPE_MAIN = "MAIN";

    /** {@link MenuResponse#menuType()} discriminator for the administrator menu. */
    private static final String MENU_TYPE_ADMIN = "ADMIN";

    /** Display title of the main menu (legacy screen shell {@code COMEN01}). */
    private static final String TITLE_MAIN = "Main Menu";

    /** Display title of the admin menu (legacy screen shell {@code COADM01}). */
    private static final String TITLE_ADMIN = "Admin Menu";

    /** {@link MenuOption#userType()} flag for a regular-user option (COBOL {@code 'U'}). */
    private static final String USRTYPE_USER = "U";

    /** {@link MenuOption#userType()} flag for an administrator-only option (COBOL {@code 'A'}). */
    private static final String USRTYPE_ADMIN = "A";

    /** Main-menu option count, from {@code COMEN02Y} {@code CDEMO-MENU-OPT-COUNT}. */
    private static final int MAIN_MENU_OPTION_COUNT = 10;

    /** Admin-menu option count, from {@code COADM02Y} {@code CDEMO-ADMIN-OPT-COUNT}. */
    private static final int ADMIN_MENU_OPTION_COUNT = 4;

    @Autowired
    private MockMvc mockMvc;

    /** The delegated menu provider; stubbed per test to isolate the controller's HTTP contract. */
    @MockitoBean
    private MenuService menuService;

    /** Present only to satisfy the {@link SecurityConfig} constructor; never invoked by these tests. */
    @MockitoBean
    private JwtService jwtService;

    /**
     * Builds a main {@link MenuResponse} that mirrors the ten regular-user options declared in
     * {@code COMEN02Y} (every option carries the user-type flag {@code 'U'}), so the size, ordering,
     * and role-visibility assertions reflect the production {@code MenuService} table.
     *
     * @return the ten-option main menu ({@code menuType == "MAIN"})
     */
    private static MenuResponse mainMenu() {
        return new MenuResponse(MENU_TYPE_MAIN, TITLE_MAIN, List.of(
                new MenuOption(1, "Account View", "COACTVWC", USRTYPE_USER),
                new MenuOption(2, "Account Update", "COACTUPC", USRTYPE_USER),
                new MenuOption(3, "Credit Card List", "COCRDLIC", USRTYPE_USER),
                new MenuOption(4, "Credit Card View", "COCRDSLC", USRTYPE_USER),
                new MenuOption(5, "Credit Card Update", "COCRDUPC", USRTYPE_USER),
                new MenuOption(6, "Transaction List", "COTRN00C", USRTYPE_USER),
                new MenuOption(7, "Transaction View", "COTRN01C", USRTYPE_USER),
                new MenuOption(8, "Transaction Add", "COTRN02C", USRTYPE_USER),
                new MenuOption(9, "Transaction Reports", "CORPT00C", USRTYPE_USER),
                new MenuOption(10, "Bill Payment", "COBIL00C", USRTYPE_USER)));
    }

    /**
     * Builds an admin {@link MenuResponse} that mirrors the four options declared in {@code COADM02Y};
     * each option is flagged {@code 'A'} to make its administrator-only visibility explicit, matching
     * the production {@code MenuService} table.
     *
     * @return the four-option admin menu ({@code menuType == "ADMIN"})
     */
    private static MenuResponse adminMenu() {
        return new MenuResponse(MENU_TYPE_ADMIN, TITLE_ADMIN, List.of(
                new MenuOption(1, "User List (Security)", "COUSR00C", USRTYPE_ADMIN),
                new MenuOption(2, "User Add (Security)", "COUSR01C", USRTYPE_ADMIN),
                new MenuOption(3, "User Update (Security)", "COUSR02C", USRTYPE_ADMIN),
                new MenuOption(4, "User Delete (Security)", "COUSR03C", USRTYPE_ADMIN)));
    }

    /**
     * {@code GET /api/menu} &mdash; the role-appropriate menu route migrated from the
     * {@code COMEN01C} main-menu screen. Any authenticated caller may fetch it; the controller derives
     * the role from the security authority and delegates to {@link MenuService#getMenuForRole(String)}.
     */
    @Nested
    @DisplayName("GET /api/menu — role-appropriate menu (COMEN01C / CM00)")
    class MainMenuEndpoint {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("regular user -> 200 with the ten-option main menu, delegating with role USER")
        void regularUserGetsMainMenu() throws Exception {
            when(menuService.getMenuForRole(ROLE_USER)).thenReturn(mainMenu());

            mockMvc.perform(get(MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.menuType").value(MENU_TYPE_MAIN))
                    .andExpect(jsonPath("$.title").value(TITLE_MAIN))
                    .andExpect(jsonPath("$.options.length()").value(MAIN_MENU_OPTION_COUNT))
                    .andExpect(jsonPath("$.options[0].optionNumber").value(1))
                    .andExpect(jsonPath("$.options[0].targetProgram").value("COACTVWC"));

            // The role indicator is derived from the JWT authority (ROLE_USER -> "USER").
            verify(menuService).getMenuForRole(ROLE_USER);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("administrator -> 200 with the role-appropriate menu, delegating with role ADMIN")
        void administratorGetsRoleAppropriateMenu() throws Exception {
            when(menuService.getMenuForRole(ROLE_ADMIN)).thenReturn(adminMenu());

            mockMvc.perform(get(MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.menuType").value(MENU_TYPE_ADMIN))
                    .andExpect(jsonPath("$.options.length()").value(ADMIN_MENU_OPTION_COUNT));

            // ROLE_ADMIN authority -> "ADMIN" is handed to the service (COMEN01C vs COADM01C routing).
            verify(menuService).getMenuForRole(ROLE_ADMIN);
        }

        @Test
        @DisplayName("anonymous -> 401 (authentication required); the service is never consulted")
        void anonymousIsUnauthorized() throws Exception {
            mockMvc.perform(get(MENU_PATH))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(menuService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("regular-user menu hides admin-only options (COMEN01C role-visibility parity)")
        void regularUserMenuHidesAdminOptions() throws Exception {
            when(menuService.getMenuForRole(ROLE_USER)).thenReturn(mainMenu());

            mockMvc.perform(get(MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.options.length()").value(MAIN_MENU_OPTION_COUNT))
                    // No option is flagged administrator-only ('A') for a regular user...
                    .andExpect(jsonPath("$.options[?(@.userType == '" + USRTYPE_ADMIN + "')]").isEmpty())
                    // ...and every option carries the regular-user flag ('U').
                    .andExpect(jsonPath("$.options[*].userType", everyItem(is(USRTYPE_USER))));
        }
    }

    /**
     * {@code GET /api/admin/menu} &mdash; the administrator-only menu route migrated from the
     * {@code COADM01C} admin-menu screen. It is defended by the assembled security filter chain (the
     * {@code /api/admin/**} authorization rule and the {@code @PreAuthorize("hasRole('ADMIN')")} gate
     * enabled by {@code @EnableMethodSecurity}), so a non-administrator never reaches the controller.
     */
    @Nested
    @DisplayName("GET /api/admin/menu — admin-only menu (COADM01C / CA00)")
    class AdminMenuEndpoint {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("administrator -> 200 with the four-option admin menu")
        void administratorGetsAdminMenu() throws Exception {
            when(menuService.getAdminMenu()).thenReturn(adminMenu());

            mockMvc.perform(get(ADMIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.menuType").value(MENU_TYPE_ADMIN))
                    .andExpect(jsonPath("$.title").value(TITLE_ADMIN))
                    .andExpect(jsonPath("$.options.length()").value(ADMIN_MENU_OPTION_COUNT))
                    .andExpect(jsonPath("$.options[0].targetProgram").value("COUSR00C"));

            verify(menuService).getAdminMenu();
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("regular user -> 403 (method-security gating); the service is never consulted")
        void regularUserIsForbidden() throws Exception {
            mockMvc.perform(get(ADMIN_MENU_PATH))
                    .andExpect(status().isForbidden());

            // The admin route is gated before the handler runs, so getAdminMenu() must not be called.
            verify(menuService, never()).getAdminMenu();
        }

        @Test
        @DisplayName("anonymous -> 401 (authentication required); the service is never consulted")
        void anonymousIsUnauthorized() throws Exception {
            mockMvc.perform(get(ADMIN_MENU_PATH))
                    .andExpect(status().isUnauthorized());

            verify(menuService, never()).getAdminMenu();
        }
    }

    /**
     * {@code GET /api/menu/options/{optionNumber}} &mdash; single-option resolution migrated from the
     * {@code PROCESS-ENTER-KEY} handling of {@code COMEN01C} / {@code COADM01C}. The controller derives
     * the role and delegates to {@link MenuService#resolveOption(String, int)}; the class-level
     * {@code @Validated} plus the {@code @Min(1)} edit reproduces the legacy lower-bound guard.
     */
    @Nested
    @DisplayName("GET /api/menu/options/{optionNumber} — option resolution + @Min(1) edit")
    class ResolveOptionEndpoint {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("valid option for a regular user -> 200 with the resolved option payload")
        void validOptionResolves() throws Exception {
            MenuOption option = new MenuOption(1, "Account View", "COACTVWC", USRTYPE_USER);
            when(menuService.resolveOption(ROLE_USER, 1)).thenReturn(option);

            mockMvc.perform(get(OPTIONS_PATH + 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.optionNumber").value(1))
                    .andExpect(jsonPath("$.optionName").value("Account View"))
                    .andExpect(jsonPath("$.targetProgram").value("COACTVWC"))
                    .andExpect(jsonPath("$.userType").value(USRTYPE_USER));

            verify(menuService).resolveOption(ROLE_USER, 1);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("option number 0 violates @Min(1) -> 400; the service is never consulted")
        void belowMinimumOptionIsRejected() throws Exception {
            mockMvc.perform(get(OPTIONS_PATH + 0))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(menuService);
        }
    }
}
