package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.UserAdminController;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.enums.UserType;
import com.carddemo.service.admin.UserAddService;
import com.carddemo.service.admin.UserDeleteService;
import com.carddemo.service.admin.UserListService;
import com.carddemo.service.admin.UserUpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer (MockMvc) contract tests for {@link UserAdminController}.
 *
 * <p>Proves the CP4 user-admin contract fixes at the HTTP layer: {@code POST} returns
 * {@code 201 Created}, {@code PUT /api/admin/users/{userId}} binds and validates the path variable
 * against the body ({@code 400} on mismatch), {@code DELETE} returns {@code 204 No Content}, the
 * previously-added undocumented {@code GET /api/admin/users/{userId}} route is absent ({@code 404}),
 * and the whole surface is admin-gated under {@code /api/admin/**} ({@code 403} for a
 * {@code ROLE_USER}).</p>
 */
@WebMvcTest(UserAdminController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "carddemo.security.jwt.secret=test-jwt-secret-key-at-least-32-bytes-long-0123456789"
})
class UserAdminControllerWebMvcTest {

    private static final String ADD_BODY = "{"
            + "\"firstName\":\"John\","
            + "\"lastName\":\"Doe\","
            + "\"userId\":\"USER0001\","
            + "\"password\":\"pass1234\","
            + "\"userType\":\"USER\"}";

    private static final String UPDATE_BODY = "{"
            + "\"userId\":\"USER0001\","
            + "\"firstName\":\"John\","
            + "\"lastName\":\"Doe\","
            + "\"password\":\"pass1234\","
            + "\"userType\":\"USER\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserListService userListService;

    @MockitoBean
    private UserAddService userAddService;

    @MockitoBean
    private UserUpdateService userUpdateService;

    @MockitoBean
    private UserDeleteService userDeleteService;

    @Test
    void addUser_valid_isCreated() throws Exception {
        // F17: user creation must return 201 Created.
        when(userAddService.addUser(any())).thenReturn(sampleResponse("User added successfully."));

        mockMvc.perform(post("/api/admin/users")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADD_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("USER0001"));
    }

    @Test
    void updateUser_matchingPathAndBody_isOk() throws Exception {
        when(userUpdateService.updateUser(any()))
                .thenReturn(sampleResponse("User updated successfully."));

        mockMvc.perform(put("/api/admin/users/USER0001")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"));
    }

    @Test
    void updateUser_pathBodyMismatch_isBadRequest() throws Exception {
        // F8: path targets a different user than the body → rejected with 400.
        mockMvc.perform(put("/api/admin/users/OTHER999")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteUser_isNoContent() throws Exception {
        // F16: successful delete returns 204 No Content with an empty body.
        when(userDeleteService.deleteUser(anyString()))
                .thenReturn(sampleResponse("User deleted successfully."));

        mockMvc.perform(delete("/api/admin/users/USER0001")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void getUserById_routeAbsent_isMethodNotAllowed() throws Exception {
        // F7: the undocumented GET /api/admin/users/{userId} read route must not exist. The
        // /{userId} path template is registered for PUT and DELETE only, so a GET against it is
        // reported as 405 Method Not Allowed (GET is not among the permitted methods) — proving
        // there is no GET-by-id read handler. An authorised ADMIN is used so the /api/admin/** gate
        // is passed first, making this an assertion about the missing route, not about authorization.
        mockMvc.perform(get("/api/admin/users/USER0001")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void listUsers_asRoleUser_isForbidden() throws Exception {
        // The /api/admin/** surface is admin-only: a ROLE_USER principal receives 403.
        mockMvc.perform(get("/api/admin/users")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_unauthenticated_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    private static UserResponse sampleResponse(String message) {
        return new UserResponse("USER0001", "John", "Doe", UserType.USER, message, null);
    }
}
