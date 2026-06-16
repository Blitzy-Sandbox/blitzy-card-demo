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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Web-layer (MockMvc) contract and security tests for {@link MenuController}.
 *
 * <p>Resolves the CP4 review's CRITICAL authorization finding at the HTTP layer: the admin-menu
 * route {@code GET /api/menu/admin} is not under the {@code /api/admin/**} URL prefix that
 * {@code SecurityConfig} guards by role, so it relies on a method-level
 * {@code @PreAuthorize("hasRole('ADMIN')")}. These tests prove a {@code ROLE_USER} principal
 * receives {@code 403} while a {@code ROLE_ADMIN} principal receives {@code 200}, and that the
 * route requires authentication.</p>
 *
 * <p>The real {@link SecurityConfig} filter chain and method-security interceptor are imported so
 * the authorization rules are exercised exactly as in production; the menu services are mocked
 * because this slice validates routing/security/serialization, not menu business logic.</p>
 */
@WebMvcTest(MenuController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "carddemo.security.jwt.secret=test-jwt-secret-key-at-least-32-bytes-long-0123456789"
})
class MenuControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MainMenuService mainMenuService;

    @MockitoBean
    private AdminMenuService adminMenuService;

    /** A bearer-token principal carrying only the {@code ROLE_USER} authority. */
    private static RequestPostProcessor userJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /** A bearer-token principal carrying the {@code ROLE_ADMIN} authority. */
    private static RequestPostProcessor adminJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static List<MenuOption> sampleOptions() {
        return List.of(new MenuOption(1, "Account View", "COACTVWC", UserType.USER));
    }

    @Test
    void mainMenu_authenticatedUser_returns200() throws Exception {
        when(mainMenuService.getMenuOptions()).thenReturn(sampleOptions());

        mockMvc.perform(get("/api/menu/main").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].optionName").value("Account View"));
    }

    @Test
    void adminMenu_roleUser_isForbidden() throws Exception {
        // CRITICAL finding proof: a normal ROLE_USER must NOT reach the admin menu.
        mockMvc.perform(get("/api/menu/admin").with(userJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminMenu_roleAdmin_returns200() throws Exception {
        when(adminMenuService.getMenuOptions()).thenReturn(sampleOptions());

        mockMvc.perform(get("/api/menu/admin").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].optionName").value("Account View"));
    }

    @Test
    void adminMenu_unauthenticated_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/menu/admin"))
                .andExpect(status().isUnauthorized());
    }
}
