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
package com.carddemo.unit.config;

import com.carddemo.config.SecurityConfig;
import com.carddemo.service.JwtAuthenticationFilter;
import com.carddemo.service.JwtTokenService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link SecurityConfig}'s route authorization rules, exercising
 * the real {@link org.springframework.security.web.SecurityFilterChain} through
 * {@link MockMvc}.
 *
 * <p>These tests directly verify the CP3 admin-gating requirement: the
 * administrative surface ({@code /api/admin/**} and the admin menu
 * {@code /api/menu/admin}) must require {@code ROLE_ADMIN}; the sign-in endpoints
 * ({@code /api/auth/**}) are anonymous; and every other route requires a valid
 * authentication. A lightweight {@link SecuredTestController} stands in for the
 * (CP4) controllers so that authorization &mdash; which runs ahead of the
 * dispatcher &mdash; is observed in isolation: a denied request yields
 * {@code 401}/{@code 403}, while an authorized request reaches the test handler
 * and yields {@code 200}.</p>
 *
 * <p>The {@link JwtAuthenticationFilter} is purely additive (it authenticates
 * only when a bearer token is present), so the
 * {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#user(String)}
 * post-processor populates the {@code SecurityContext} unobstructed; its
 * collaborating {@link JwtTokenService} is therefore a no-op mock here.</p>
 */
@WebMvcTest(controllers = SecurityConfigTest.SecuredTestController.class)
@Import({SecurityConfig.class, SecurityConfigTest.SecuredTestController.class,
        SecurityConfigTest.TestSecurityBeans.class})
@DisplayName("SecurityConfig - REST route authorization (admin gating) @ CP3")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("/api/auth/** is anonymous (permitAll)")
    void signinIsAnonymous() throws Exception {
        mockMvc.perform(get("/api/auth/signin"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a protected route without authentication is rejected with 401")
    void protectedRouteWithoutAuthIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/accounts/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/api/menu/admin is forbidden for ROLE_USER")
    void adminMenuForbiddenForUser() throws Exception {
        mockMvc.perform(get("/api/menu/admin").with(user("ada").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/api/menu/admin is allowed for ROLE_ADMIN")
    void adminMenuAllowedForAdmin() throws Exception {
        mockMvc.perform(get("/api/menu/admin").with(user("root").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/api/admin/** is forbidden for ROLE_USER")
    void adminApiForbiddenForUser() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(user("ada").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/api/admin/** is allowed for ROLE_ADMIN")
    void adminApiAllowedForAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(user("root").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a non-admin protected route is allowed for any authenticated user")
    void protectedRouteAllowedForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/accounts/1").with(user("ada").roles("USER")))
                .andExpect(status().isOk());
    }

    /**
     * Supplies the {@link JwtAuthenticationFilter} dependency of
     * {@link SecurityConfig} with a no-op token service so the slice context can
     * be built without the full token-service wiring.
     */
    @TestConfiguration
    static class TestSecurityBeans {

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(Mockito.mock(JwtTokenService.class));
        }
    }

    /**
     * Minimal controller exposing the routes whose authorization is under test.
     * Every handler returns {@code 200 OK}; reaching a handler therefore proves
     * the request passed authorization.
     */
    @RestController
    static class SecuredTestController {

        @GetMapping("/api/auth/signin")
        String signin() {
            return "signin";
        }

        @GetMapping("/api/menu/admin")
        String adminMenu() {
            return "admin-menu";
        }

        @GetMapping("/api/admin/users")
        String adminUsers() {
            return "admin-users";
        }

        @GetMapping("/api/accounts/{id}")
        String account(@PathVariable String id) {
            return "account-" + id;
        }
    }
}
