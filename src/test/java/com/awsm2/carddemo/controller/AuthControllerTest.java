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

import com.awsm2.carddemo.dto.SignonRequestDto;
import com.awsm2.carddemo.dto.SignonResponseDto;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.SignonService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller slice test for {@link AuthController}.
 *
 * <p><b>CP5 review coverage.</b> This test is required by the CP5
 * review report under "Add security tests proving signin is reachable
 * unauthenticated". It validates:
 * <ul>
 *   <li>{@code POST /api/auth/signin} is the AAP &sect;0.3.4 route and
 *       it is reachable (HTTP 200) on the happy path.</li>
 *   <li>The response envelope is the standardized
 *       {@code ApiResponse<SignonResponseDto>} shape per AAP
 *       &sect;0.3.4 / &sect;0.7.1.</li>
 *   <li>Jakarta Bean Validation errors return HTTP 400 with the
 *       standardized error envelope produced by
 *       {@link GlobalExceptionHandler}.</li>
 *   <li>Invalid credentials surface as HTTP 401 via
 *       {@link BadCredentialsException} handled by
 *       {@link GlobalExceptionHandler}.</li>
 * </ul>
 *
 * <p>The Spring Security filter chain is disabled
 * ({@link AutoConfigureMockMvc#addFilters() addFilters} =
 * {@code false}) because the underlying security configuration is
 * exercised by {@code SecurityConfig} bean tests and by integration
 * tests; here we focus on the controller's HTTP contract and its
 * delegation to {@link SignonService}.</p>
 */
@WebMvcTest(
        controllers = AuthController.class,
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
@DisplayName("AuthController slice tests (POST /api/auth/signin)")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SignonService signonService;

    /**
     * The JWT token provider is required to satisfy the constructor
     * dependency of {@link JwtAuthenticationFilter} that may still be
     * eagerly discovered by component scanning. Mocking it here keeps
     * the slice test lightweight and stable.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Nested
    @DisplayName("Happy path — valid credentials")
    class HappyPath {

        @Test
        @DisplayName("returns HTTP 200 + ApiResponse envelope with JWT + role")
        void signin_validRequest_returns200WithToken() throws Exception {
            SignonRequestDto request = new SignonRequestDto("USER0001", "Pa55w0rd");
            SignonResponseDto response = new SignonResponseDto(
                    "header.payload.signature",
                    "USER0001",
                    "John",
                    "Doe",
                    "U",
                    1_700_000_000_000L);
            when(signonService.signon(any(SignonRequestDto.class)))
                    .thenReturn(response);

            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("Sign-on successful"))
                    .andExpect(jsonPath("$.data.token").value("header.payload.signature"))
                    .andExpect(jsonPath("$.data.userId").value("USER0001"))
                    .andExpect(jsonPath("$.data.userType").value("U"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("admin user returns userType 'A' in response data")
        void signin_adminUser_returnsAdminType() throws Exception {
            SignonRequestDto request = new SignonRequestDto("ADMIN001", "AdminPwd");
            SignonResponseDto response = new SignonResponseDto(
                    "admin-token",
                    "ADMIN001",
                    "Jane",
                    "Smith",
                    "A",
                    1_700_000_000_000L);
            when(signonService.signon(any(SignonRequestDto.class)))
                    .thenReturn(response);

            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userType").value("A"));
        }
    }

    @Nested
    @DisplayName("Validation errors — Jakarta Bean Validation")
    class ValidationErrors {

        @Test
        @DisplayName("blank userId returns HTTP 400")
        void signin_blankUserId_returns400() throws Exception {
            String json = "{\"userId\":\"\",\"password\":\"Pa55w0rd\"}";

            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("missing password returns HTTP 400")
        void signin_blankPassword_returns400() throws Exception {
            String json = "{\"userId\":\"USER0001\",\"password\":\"\"}";

            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("oversized userId returns HTTP 400")
        void signin_oversizedUserId_returns400() throws Exception {
            // BMS USERID PIC X(8) — max length 8
            String json = "{\"userId\":\"VERYLONGUSER\",\"password\":\"Pa55w0rd\"}";

            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("non-alphanumeric userId returns HTTP 400")
        void signin_specialCharsUserId_returns400() throws Exception {
            String json = "{\"userId\":\"USR@001\",\"password\":\"Pa55w0rd\"}";

            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("malformed JSON returns HTTP 400")
        void signin_malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("not json"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("missing content-type returns HTTP 415")
        void signin_missingContentType_returns415() throws Exception {
            mockMvc.perform(post("/api/auth/signin")
                            .content("{}"))
                    .andExpect(status().isUnsupportedMediaType());
        }
    }

    @Nested
    @DisplayName("Authentication failure — invalid credentials")
    class InvalidCredentials {

        @Test
        @DisplayName("BadCredentialsException returns HTTP 401")
        void signin_badCredentials_returns401() throws Exception {
            SignonRequestDto request = new SignonRequestDto("USER0001", "WrongPwd");
            when(signonService.signon(any(SignonRequestDto.class)))
                    .thenThrow(new BadCredentialsException("Invalid User ID or Password"));

            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
