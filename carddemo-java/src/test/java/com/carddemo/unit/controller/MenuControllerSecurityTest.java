package com.carddemo.unit.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.MenuController;
import com.carddemo.model.dto.MenuOption;
import com.carddemo.model.enums.UserType;
import com.carddemo.service.menu.AdminMenuService;
import com.carddemo.service.menu.MainMenuService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice security tests for {@link MenuController} against the real {@link SecurityConfig}
 * filter chain.
 *
 * <p>Primary (CRITICAL) assertion: the CP4-introduced {@code GET /api/menu/admin} route — which
 * exposes the COADM01 admin option table — must require {@code ROLE_ADMIN}. Before the fix the
 * route sat outside {@code /api/admin/**} and fell through to generic authenticated access, letting
 * a standard {@code USER} read the admin menu (api-contracts.md §3.2/§5.8, COADM01C admin-gate
 * parity). These tests prove an authenticated {@code USER} now receives {@code 403}, an
 * {@code ADMIN} receives {@code 200}, and an anonymous caller receives {@code 401}.</p>
 *
 * <p>Secondary assertion: the menu JSON honors the published §5.8 contract — {@code menuType} plus
 * {@code options[]} of {@code optionNumber}/{@code label}/{@code target} — and the BMS-derived
 * {@code programName}/{@code userType} components are {@code @JsonIgnore}d, so they never leak into
 * the read-only menu catalog response.</p>
 *
 * <p>Source lineage (reference only, COBOL not copied): COMEN01C / COADM01C, AWS CardDemo commit
 * 27d6c6f.</p>
 */
@WebMvcTest(MenuController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // >= 32 bytes so SecurityConfig.hmacKey() accepts it; test-only, never a real secret.
        "carddemo.security.jwt.secret=carddemo-web-test-signing-secret-0123456789"
})
@DisplayName("MenuController web slice - /api/menu/admin authorization & §5.8 contract")
class MenuControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MainMenuService mainMenuService;

    @MockitoBean
    private AdminMenuService adminMenuService;

    @Nested
    @DisplayName("GET /api/menu/admin authorization")
    class AdminMenuAuthorization {

        @Test
        @DisplayName("anonymous caller is rejected with 401")
        void anonymousIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/menu/admin"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("authenticated USER is forbidden with 403 (admin-gate parity)")
        void userIsForbidden() throws Exception {
            mockMvc.perform(get("/api/menu/admin")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("authenticated ADMIN is granted 200 and the §5.8 menu contract")
        void adminIsAllowed() throws Exception {
            when(adminMenuService.getMenuOptions()).thenReturn(List.of(
                    new MenuOption(1, "User List (Security)", "GET /api/admin/users", "COUSR00C", null)));

            mockMvc.perform(get("/api/menu/admin")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.menuType").value("ADMIN"))
                    .andExpect(jsonPath("$.options[0].optionNumber").value(1))
                    .andExpect(jsonPath("$.options[0].label").value("User List (Security)"))
                    .andExpect(jsonPath("$.options[0].target").value("GET /api/admin/users"))
                    // BMS-derived internals must not leak into the read-only catalog contract.
                    .andExpect(jsonPath("$.options[0].programName").doesNotExist())
                    .andExpect(jsonPath("$.options[0].userType").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /api/menu/main")
    class MainMenu {

        @Test
        @DisplayName("authenticated USER may read the main menu (200) with the §5.8 contract")
        void userReadsMainMenu() throws Exception {
            when(mainMenuService.getMenuOptions()).thenReturn(List.of(
                    new MenuOption(1, "Account View", "GET /api/accounts/{id}", "COACTVWC", UserType.USER)));

            mockMvc.perform(get("/api/menu/main")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.menuType").value("MAIN"))
                    .andExpect(jsonPath("$.options[0].label").value("Account View"))
                    .andExpect(jsonPath("$.options[0].target").value("GET /api/accounts/{id}"))
                    .andExpect(jsonPath("$.options[0].programName").doesNotExist())
                    .andExpect(jsonPath("$.options[0].userType").doesNotExist());
        }
    }
}
