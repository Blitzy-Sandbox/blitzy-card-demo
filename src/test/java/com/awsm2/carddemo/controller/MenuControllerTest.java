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
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.AdminMenuDto;
import com.awsm2.carddemo.dto.MainMenuDto;
import com.awsm2.carddemo.dto.MenuOptionDto;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.MenuService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller slice test for {@link MenuController}.
 *
 * <p><b>CP5 review coverage.</b> Verifies three review findings:</p>
 * <ul>
 *   <li>The out-of-scope {@code POST /api/menu/resolve} endpoint was
 *       removed (returns HTTP 405 Method Not Allowed because no POST
 *       handler is registered on the base path).</li>
 *   <li>{@code GET /api/menu/main} is implemented (HTTP 200) and
 *       delegates to {@link MenuService#getMainMenu(String)}.</li>
 *   <li>{@code GET /api/menu/admin} is implemented (HTTP 200) and
 *       delegates to {@link MenuService#getAdminMenu(String)}.</li>
 * </ul>
 *
 * <p>The Spring Security filter chain is disabled via
 * {@link AutoConfigureMockMvc#addFilters() addFilters} = {@code false}
 * so that we can verify the controller's pure HTTP contract without
 * needing the JWT subsystem; the role-based authorization is
 * exercised separately in {@code SecurityConfig} integration tests.</p>
 */
@WebMvcTest(
        controllers = MenuController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("MenuController slice tests (GET /api/menu/main, GET /api/menu/admin)")
class MenuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MenuService menuService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Nested
    @DisplayName("GET /api/menu/main — main-menu happy path")
    class MainMenuHappyPath {

        @Test
        @DisplayName("returns HTTP 200 with ApiResponse envelope and options")
        void mainMenu_userRole_returns200WithOptions() throws Exception {
            MenuOptionDto option1 = new MenuOptionDto(
                    1, "Account View", "COACTVWC",
                    "/api/accounts/{id}", "U", true);
            MainMenuDto menu = new MainMenuDto(
                    "USER0001", "U", "John", "Doe",
                    List.of(option1), "AWS CardDemo - Main Menu");
            when(menuService.getMainMenu(anyString())).thenReturn(menu);

            mockMvc.perform(get("/api/menu/main"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.data.userId").value("USER0001"))
                    .andExpect(jsonPath("$.data.userType").value("U"))
                    .andExpect(jsonPath("$.data.options[0].optionNumber").value(1))
                    .andExpect(jsonPath("$.data.options[0].label").value("Account View"))
                    .andExpect(jsonPath("$.data.options[0].targetProgram").value("COACTVWC"))
                    .andExpect(jsonPath("$.data.options[0].targetEndpoint").value("/api/accounts/{id}"))
                    .andExpect(jsonPath("$.data.title").value("AWS CardDemo - Main Menu"));

            verify(menuService).getMainMenu("U");
        }

        @Test
        @DisplayName("admin caller can also retrieve the main menu (USER+ADMIN role)")
        void mainMenu_adminRole_returns200() throws Exception {
            MainMenuDto menu = new MainMenuDto(
                    "ADMIN001", "U", "Admin", "User",
                    List.of(), "AWS CardDemo - Main Menu");
            when(menuService.getMainMenu(anyString())).thenReturn(menu);

            mockMvc.perform(get("/api/menu/main"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.userId").value("ADMIN001"));
        }
    }

    @Nested
    @DisplayName("GET /api/menu/admin — admin-menu happy path")
    class AdminMenuHappyPath {

        @Test
        @DisplayName("returns HTTP 200 with admin options for admin caller")
        void adminMenu_adminRole_returns200WithOptions() throws Exception {
            MenuOptionDto option = new MenuOptionDto(
                    1, "User List (Security)", "COUSR00C",
                    "/api/admin/users", "A", true);
            AdminMenuDto menu = new AdminMenuDto(
                    "ADMIN001", "Admin", "User",
                    List.of(option), "AWS CardDemo - Admin Menu");
            when(menuService.getAdminMenu(anyString())).thenReturn(menu);

            mockMvc.perform(get("/api/menu/admin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.data.userId").value("ADMIN001"))
                    .andExpect(jsonPath("$.data.options[0].optionNumber").value(1))
                    .andExpect(jsonPath("$.data.options[0].label").value("User List (Security)"))
                    .andExpect(jsonPath("$.data.options[0].targetProgram").value("COUSR00C"))
                    .andExpect(jsonPath("$.data.title").value("AWS CardDemo - Admin Menu"));

            verify(menuService).getAdminMenu("A");
        }
    }

    @Nested
    @DisplayName("Scope discipline — removed /api/menu/resolve endpoint")
    class RemovedResolveEndpoint {

        /**
         * The CP5 review removed the out-of-scope {@code POST /api/menu/resolve}
         * endpoint because it was not declared in AAP &sect;0.3.4 and because
         * it trusted client-supplied role discriminators. Verifies the
         * removal: a POST against {@code /api/menu/resolve} URL surfaces as
         * HTTP 404 with the standardized {@code ENDPOINT_NOT_FOUND} envelope
         * via the {@code NoResourceFoundException} handler in
         * {@code GlobalExceptionHandler}, and {@code menuService} is never
         * invoked.
         */
        @Test
        @DisplayName("POST /api/menu/resolve no longer exists — returns 404")
        void resolveEndpoint_removed_doesNotMatchHandler() throws Exception {
            mockMvc.perform(post("/api/menu/resolve")
                            .contentType("application/json")
                            .content("{\"userType\":\"A\",\"adminMenu\":true}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

            // Confirm the resolveMenuTarget service method was NOT invoked.
            verifyNoInteractions(menuService);
        }

        /**
         * Defense in depth: a GET against {@code /api/menu/resolve} also
         * must not match any handler. Surfaces as HTTP 404 via the
         * {@code NoResourceFoundException} handler.
         */
        @Test
        @DisplayName("GET /api/menu/resolve no longer exists — returns 404")
        void resolveEndpoint_get_doesNotMatchHandler() throws Exception {
            mockMvc.perform(get("/api/menu/resolve"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

            verifyNoInteractions(menuService);
        }
    }
}
