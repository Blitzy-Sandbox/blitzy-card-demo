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

import com.awsm2.carddemo.dto.UserAddDto;
import com.awsm2.carddemo.dto.UserDeleteDto;
import com.awsm2.carddemo.dto.UserListDto;
import com.awsm2.carddemo.dto.UserUpdateDto;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.UserAddService;
import com.awsm2.carddemo.service.UserDeleteService;
import com.awsm2.carddemo.service.UserListService;
import com.awsm2.carddemo.service.UserUpdateService;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller slice test for {@link UserAdminController}.
 *
 * <p><b>CP5 review coverage.</b> Verifies the four CP5 review-required
 * endpoints exist with the correct HTTP shapes and delegate to their
 * services as documented in AAP &sect;0.3.4:</p>
 * <ul>
 *   <li>{@code GET    /api/admin/users}      &rarr; HTTP 200 with
 *       {@code UserListDto} envelope.</li>
 *   <li>{@code POST   /api/admin/users}      &rarr; HTTP 201 with the
 *       created {@code UserAddDto}.</li>
 *   <li>{@code PUT    /api/admin/users/{id}} &rarr; HTTP 200 with the
 *       updated {@code UserUpdateDto}.</li>
 *   <li>{@code DELETE /api/admin/users/{id}} &rarr; HTTP 204 No Content
 *       when confirmed, or HTTP 200 cancellation envelope for
 *       {@code confirm=N}.</li>
 * </ul>
 *
 * <p>Additionally exercises validation-error paths (HTTP 400) for
 * malformed inputs and not-found paths (HTTP 404) for updates and
 * deletes of missing users.</p>
 *
 * <p>The Spring Security filter chain is disabled
 * ({@link AutoConfigureMockMvc#addFilters() addFilters} = {@code false})
 * because the role-based authorization is exercised separately in
 * {@code SecurityConfig} integration tests; here we verify the
 * controller's HTTP/JSON contract.</p>
 */
@WebMvcTest(
        controllers = UserAdminController.class,
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
@DisplayName("UserAdminController slice tests (/api/admin/users)")
class UserAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserListService userListService;

    @MockBean
    private UserAddService userAddService;

    @MockBean
    private UserUpdateService userUpdateService;

    @MockBean
    private UserDeleteService userDeleteService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // =================================================================
    // GET /api/admin/users
    // =================================================================

    @Nested
    @DisplayName("GET /api/admin/users — list users")
    class ListUsers {

        @Test
        @DisplayName("returns HTTP 200 with paginated user rows")
        void listUsers_default_returns200WithUsers() throws Exception {
            UserListDto.UserRow row = new UserListDto.UserRow(
                    "USER0001", "John", "Doe", "U");
            UserListDto list = new UserListDto(
                    List.of(row),
                    0,           // page
                    10,          // size
                    1L,          // totalElements
                    1,           // totalPages
                    true,        // first
                    true,        // last
                    null);       // searchFilter
            when(userListService.listUsers(any(), anyInt())).thenReturn(list);

            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.rows[0].userId").value("USER0001"))
                    .andExpect(jsonPath("$.data.rows[0].firstName").value("John"))
                    .andExpect(jsonPath("$.data.rows[0].lastName").value("Doe"))
                    .andExpect(jsonPath("$.data.rows[0].userType").value("U"))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(1));

            verify(userListService).listUsers(null, 0);
        }

        @Test
        @DisplayName("forwards search and page query params to service")
        void listUsers_searchAndPage_passedToService() throws Exception {
            UserListDto list = new UserListDto(
                    List.of(), 2, 10, 0L, 0, false, true, "ADMIN");
            when(userListService.listUsers(anyString(), anyInt())).thenReturn(list);

            mockMvc.perform(get("/api/admin/users")
                            .param("search", "ADMIN")
                            .param("page", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.searchFilter").value("ADMIN"))
                    .andExpect(jsonPath("$.data.page").value(2));

            verify(userListService).listUsers("ADMIN", 2);
        }
    }

    // =================================================================
    // POST /api/admin/users — addUser
    // =================================================================

    @Nested
    @DisplayName("POST /api/admin/users — add user")
    class AddUser {

        @Test
        @DisplayName("returns HTTP 201 with created user envelope")
        void addUser_validRequest_returns201() throws Exception {
            UserAddDto request = new UserAddDto(
                    "USER0001", "John", "Doe", "Pa55w0rd", "U");
            UserAddDto saved = new UserAddDto(
                    "USER0001", "John", "Doe", "********", "U");
            when(userAddService.addUser(any(UserAddDto.class))).thenReturn(saved);

            mockMvc.perform(post("/api/admin/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("User created successfully"))
                    .andExpect(jsonPath("$.data.userId").value("USER0001"))
                    .andExpect(jsonPath("$.data.firstName").value("John"))
                    .andExpect(jsonPath("$.data.lastName").value("Doe"))
                    .andExpect(jsonPath("$.data.userType").value("U"));

            verify(userAddService).addUser(any(UserAddDto.class));
        }

        @Test
        @DisplayName("blank userId returns HTTP 400 VALIDATION")
        void addUser_blankUserId_returns400() throws Exception {
            String json = "{\"userId\":\"\",\"firstName\":\"John\",\"lastName\":\"Doe\","
                    + "\"password\":\"Pa55w0rd\",\"userType\":\"U\"}";
            mockMvc.perform(post("/api/admin/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));

            verifyNoInteractions(userAddService);
        }

        @Test
        @DisplayName("invalid userType returns HTTP 400 VALIDATION")
        void addUser_invalidUserType_returns400() throws Exception {
            String json = "{\"userId\":\"USER0001\",\"firstName\":\"John\",\"lastName\":\"Doe\","
                    + "\"password\":\"Pa55w0rd\",\"userType\":\"X\"}";
            mockMvc.perform(post("/api/admin/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));

            verifyNoInteractions(userAddService);
        }
    }

    // =================================================================
    // PUT /api/admin/users/{id} — updateUser
    // =================================================================

    @Nested
    @DisplayName("PUT /api/admin/users/{id} — update user")
    class UpdateUser {

        @Test
        @DisplayName("returns HTTP 200 with updated user envelope")
        void updateUser_validRequest_returns200() throws Exception {
            UserUpdateDto request = new UserUpdateDto(
                    "USER0001", "Jane", "Smith", null, "U");
            UserUpdateDto saved = new UserUpdateDto(
                    "USER0001", "Jane", "Smith", null, "U");
            when(userUpdateService.updateUser(eq("USER0001"), any(UserUpdateDto.class)))
                    .thenReturn(saved);

            mockMvc.perform(put("/api/admin/users/USER0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("User updated successfully"))
                    .andExpect(jsonPath("$.data.userId").value("USER0001"))
                    .andExpect(jsonPath("$.data.firstName").value("Jane"))
                    .andExpect(jsonPath("$.data.lastName").value("Smith"));

            verify(userUpdateService).updateUser(eq("USER0001"), any(UserUpdateDto.class));
        }

        @Test
        @DisplayName("path/body userId mismatch returns HTTP 400 USER_ID_MISMATCH")
        void updateUser_pathBodyMismatch_returns400() throws Exception {
            UserUpdateDto request = new UserUpdateDto(
                    "USER0002", "Jane", "Smith", null, "U");
            mockMvc.perform(put("/api/admin/users/USER0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("USER_ID_MISMATCH"));

            verifyNoInteractions(userUpdateService);
        }

        @Test
        @DisplayName("user not found returns HTTP 404")
        void updateUser_notFound_returns404() throws Exception {
            UserUpdateDto request = new UserUpdateDto(
                    "USER9999", "Ghost", "Person", null, "U");
            when(userUpdateService.updateUser(eq("USER9999"), any(UserUpdateDto.class)))
                    .thenThrow(new RecordNotFoundException(
                            "USER_NOT_FOUND",
                            "User USER9999 not found"));

            mockMvc.perform(put("/api/admin/users/USER9999")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // =================================================================
    // DELETE /api/admin/users/{id} — deleteUser
    // =================================================================

    @Nested
    @DisplayName("DELETE /api/admin/users/{id} — delete user")
    class DeleteUser {

        @Test
        @DisplayName("returns HTTP 204 with no body when deletion is confirmed")
        void deleteUser_confirmed_returns204() throws Exception {
            UserDeleteDto request = new UserDeleteDto(
                    "USER0001", "John", "Doe", "U", "Y");
            UserDeleteDto deleted = new UserDeleteDto(
                    "USER0001", "John", "Doe", "U", "Y");
            when(userDeleteService.deleteUser(eq("USER0001"), any(UserDeleteDto.class)))
                    .thenReturn(deleted);

            mockMvc.perform(delete("/api/admin/users/USER0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            verify(userDeleteService).deleteUser(eq("USER0001"), any(UserDeleteDto.class));
        }

        @Test
        @DisplayName("returns HTTP 200 with cancellation envelope when confirm=N")
        void deleteUser_confirmNo_returnsCancellationEnvelope() throws Exception {
            UserDeleteDto request = new UserDeleteDto(
                    "USER0001", "John", "Doe", "U", "N");
            UserDeleteDto cancelled = new UserDeleteDto(
                    "USER0001", null, null, null, "N");
            when(userDeleteService.deleteUser(eq("USER0001"), any(UserDeleteDto.class)))
                    .thenReturn(cancelled);

            mockMvc.perform(delete("/api/admin/users/USER0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("User deletion cancelled"))
                    .andExpect(jsonPath("$.data.userId").value("USER0001"))
                    .andExpect(jsonPath("$.data.confirm").value("N"));

            verify(userDeleteService).deleteUser(eq("USER0001"), any(UserDeleteDto.class));
        }

        @Test
        @DisplayName("path/body userId mismatch returns HTTP 400 USER_ID_MISMATCH")
        void deleteUser_pathBodyMismatch_returns400() throws Exception {
            UserDeleteDto request = new UserDeleteDto(
                    "USER0002", "John", "Doe", "U", "Y");
            mockMvc.perform(delete("/api/admin/users/USER0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("USER_ID_MISMATCH"));

            verifyNoInteractions(userDeleteService);
        }

        @Test
        @DisplayName("user not found returns HTTP 404")
        void deleteUser_notFound_returns404() throws Exception {
            UserDeleteDto request = new UserDeleteDto(
                    "USER9999", null, null, null, "Y");
            when(userDeleteService.deleteUser(eq("USER9999"), any(UserDeleteDto.class)))
                    .thenThrow(new RecordNotFoundException(
                            "USER_NOT_FOUND",
                            "User USER9999 not found"));

            mockMvc.perform(delete("/api/admin/users/USER9999")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }
}
