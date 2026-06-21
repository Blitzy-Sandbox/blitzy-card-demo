package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.UserAdminController;
import com.carddemo.model.dto.UserAddRequest;
import com.carddemo.model.dto.UserUpdateRequest;
import com.carddemo.service.admin.UserAddService;
import com.carddemo.service.admin.UserDeleteService;
import com.carddemo.service.admin.UserListService;
import com.carddemo.service.admin.UserUpdateService;
import org.junit.jupiter.api.DisplayName;
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
 * Web-slice tests for {@link UserAdminController} asserting the user-administration REST contract:
 * the create endpoint returns {@code 201 Created}, and the update endpoint binds and enforces the
 * {@code {userId}} path variable against the request-body user id.
 *
 * <p>Before the fixes {@code POST /api/admin/users} returned the framework-default {@code 200} (the
 * published contract is {@code 201 Created}), and {@code PUT /api/admin/users/{userId}} ignored the
 * path variable and trusted the body id, enabling wrong-user updates. Requests are authenticated as
 * {@code ROLE_ADMIN} because the routes sit under {@code /api/admin/**} (COUSR01C/COUSR02C parity,
 * source commit 27d6c6f — reference only, no COBOL copied).</p>
 */
@WebMvcTest(UserAdminController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // >= 32 bytes so SecurityConfig.hmacKey() accepts it; test-only, never a real secret.
        "carddemo.security.jwt.secret=carddemo-web-test-signing-secret-0123456789"
})
@DisplayName("UserAdminController web slice - 201 create & PUT path/body binding")
class UserAdminControllerWebTest {

    private static final SimpleGrantedAuthority ADMIN = new SimpleGrantedAuthority("ROLE_ADMIN");

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
    @DisplayName("POST /api/admin/users returns 201 Created on a successful add")
    void addUserReturns201() throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .with(jwt().authorities(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"JOHN\",\"lastName\":\"DOE\","
                                + "\"userId\":\"USER0001\",\"password\":\"PASS1234\"}"))
                .andExpect(status().isCreated());

        verify(userAddService).addUser(any(UserAddRequest.class));
    }

    @Test
    @DisplayName("matching path and body user id updates the targeted user (service invoked)")
    void matchingPathAndBodyInvokesService() throws Exception {
        mockMvc.perform(put("/api/admin/users/USER0001")
                        .with(jwt().authorities(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"USER0001\",\"firstName\":\"JOHN\","
                                + "\"lastName\":\"DOE\",\"password\":\"PASS1234\"}"))
                .andExpect(status().isOk());

        verify(userUpdateService).updateUser(any(UserUpdateRequest.class));
    }

    @Test
    @DisplayName("mismatched path and body user id is rejected with 400 and the service is never called")
    void mismatchedPathAndBodyIsRejected() throws Exception {
        mockMvc.perform(put("/api/admin/users/USER0001")
                        .with(jwt().authorities(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"USER0002\",\"firstName\":\"JOHN\","
                                + "\"lastName\":\"DOE\",\"password\":\"PASS1234\"}"))
                .andExpect(status().isBadRequest());

        verify(userUpdateService, never()).updateUser(any());
    }
}
