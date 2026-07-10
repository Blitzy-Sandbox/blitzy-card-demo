package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.carddemo.config.SecurityConfig;
import com.carddemo.dto.PageResponse;
import com.carddemo.dto.UserCreateRequest;
import com.carddemo.dto.UserListItem;
import com.carddemo.dto.UserListResponse;
import com.carddemo.dto.UserResponse;
import com.carddemo.dto.UserUpdateRequest;
import com.carddemo.exception.DuplicateResourceException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.JwtService;
import com.carddemo.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web-slice test for {@link UserController} &mdash; the headless REST migration of the four legacy
 * CICS user-administration programs {@code COUSR00C} (CU00, list users), {@code COUSR01C} (CU01,
 * add user), {@code COUSR02C} (CU02, update user) and {@code COUSR03C} (CU03, view/delete user);
 * legacy source referenced read-only at commit SHA {@code 27d6c6f}.
 *
 * <h2>Why a full {@code @WebMvcTest} slice (not standalone {@code MockMvc})</h2>
 * <p>Unlike the sibling controller tests, this suite loads the real Spring Security filter chain by
 * importing {@link SecurityConfig} (which activates {@code @EnableMethodSecurity} so the controller's
 * class-level {@code @PreAuthorize("hasRole('ADMIN')")} is enforced) and the real
 * {@link CorrelationIdFilter}. That wiring is mandatory: all four {@code COUSR*} transactions are
 * admin-only, so the migration must prove that a regular {@code ROLE_USER} caller receives
 * <strong>403</strong>, an anonymous caller receives <strong>401</strong>, and only a
 * {@code ROLE_ADMIN} caller reaches the endpoint (200/201). A standalone {@code MockMvc} bypasses the
 * security chain and therefore cannot verify this gate. Authentication is supplied by
 * {@code spring-security-test} via {@link WithMockUser}; the {@code JwtService} bearer path is not
 * exercised here (no {@code Authorization} header), so its bean is a Mockito mock present only to
 * satisfy the {@link SecurityConfig} constructor.
 *
 * <h2>{@code @MockitoBean} rather than {@code @MockBean}</h2>
 * <p>{@code org.springframework.boot.test.mock.mockito.MockBean} is {@code @Deprecated} in the Spring
 * Boot 3.5.x line and would raise a {@code [deprecation]} warning under the project's
 * {@code -Xlint:all} compilation, breaking the zero-warning build gate. The non-deprecated,
 * behaviourally identical {@code org.springframework.test.context.bean.override.mockito.MockitoBean}
 * (Spring Framework 6.2) is used instead, preserving the locked harness intent (register Mockito
 * mocks of the collaborators as bean overrides in the web-slice context) while keeping the build
 * warning-free.
 *
 * <h2>Behavioural coverage</h2>
 * <ul>
 *   <li><strong>Admin gating</strong> &mdash; USER&nbsp;&rarr;&nbsp;403 and anonymous&nbsp;&rarr;
 *       &nbsp;401 on representative endpoints, with the service never invoked; ADMIN reaches every
 *       endpoint.</li>
 *   <li><strong>Page size 10</strong> &mdash; the user list fixes ten rows per page
 *       ({@code COUSR00C USER-REC OCCURS 10 TIMES}); asserted from a genuine stub.</li>
 *   <li><strong>No credential leakage</strong> &mdash; no response body ever carries a password (the
 *       {@link UserResponse} projection omits it) and the submitted password value is never echoed on
 *       create.</li>
 *   <li><strong>Error contract</strong> &mdash; not-found&nbsp;&rarr;&nbsp;404, duplicate&nbsp;&rarr;
 *       &nbsp;409 ({@code code == DUPLICATE_KEY}), invalid body&nbsp;&rarr;&nbsp;400
 *       ({@code code == VALIDATION_ERROR}); every error body carries a correlation id.</li>
 * </ul>
 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class})
@DisplayName("UserController — user CRUD (CU00–CU03), ADMIN-only REST endpoints")
class UserControllerTest {

    /** Base path of the user-administration surface (matches the controller's {@code @RequestMapping}). */
    private static final String USERS_PATH = "/api/users";

    /** Existing user id used for read/update/delete happy and not-found paths ({@code SEC-USR-ID}). */
    private static final String USER_ID = "USER0001";

    /** New user id used for the create happy path. */
    private static final String NEW_USER_ID = "USER0002";

    /** Non-sensitive first name ({@code SEC-USR-FNAME}). */
    private static final String FIRST_NAME = "John";

    /** Non-sensitive last name ({@code SEC-USR-LNAME}). */
    private static final String LAST_NAME = "Public";

    /** Regular-user type flag ({@code SEC-USR-TYPE 'U'}); matches the {@code @Pattern("[AU]")} edit. */
    private static final String USER_TYPE = "U";

    /** A valid (&le;8-char) plaintext password submitted on create; must never appear in a response. */
    private static final String VALID_PASSWORD = "PASS0002";

    /**
     * A non-blank but over-length (9-char) password used in the invalid-create body: it violates
     * {@code @Size(max = 8)} so the {@code password} field surfaces in the validation errors, letting
     * the test prove the raw submitted value is never echoed back to the caller.
     */
    private static final String OVERLONG_PASSWORD = "LEAKME999";

    /** Fixed user-list page size mirroring {@code COUSR00C USER-REC OCCURS 10 TIMES}. */
    private static final int PAGE_SIZE = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** The consolidated user-administration service the controller delegates to (mocked). */
    @MockitoBean
    private UserService userService;

    /**
     * JWT session service. Not exercised by these tests (authentication is supplied by
     * {@link WithMockUser}), but required as a Mockito mock so the imported {@link SecurityConfig}
     * constructor can be satisfied inside the web slice.
     */
    @MockitoBean
    private JwtService jwtService;

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a representative {@link UserResponse} (no password field by construction).
     *
     * @param userId  the user id to embed
     * @param message the optional confirmation message ({@code null} for a plain read)
     * @return a password-free response view
     */
    private static UserResponse sampleUser(final String userId, final String message) {
        return new UserResponse(userId, FIRST_NAME, LAST_NAME, USER_TYPE, message);
    }

    /**
     * Builds a one-row user-list response whose page reports the fixed size of ten.
     *
     * @return a {@link UserListResponse} with {@code page.pageSize == 10}
     */
    private static UserListResponse sampleList() {
        final UserListItem row = new UserListItem(USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE);
        final PageResponse<UserListItem> page = PageResponse.of(List.of(row), 1, PAGE_SIZE, 1L);
        return new UserListResponse(null, page);
    }

    /**
     * Serializes a {@link UserCreateRequest} body to JSON (the plaintext password legitimately travels
     * in the request; the no-leak guarantee is asserted only on the response).
     */
    private String createBody(final String userId, final String firstName, final String lastName,
                              final String password, final String userType) throws Exception {
        return objectMapper.writeValueAsString(
                new UserCreateRequest(userId, firstName, lastName, password, userType));
    }

    /**
     * Serializes a {@link UserUpdateRequest} body to JSON (password optional on update).
     */
    private String updateBody(final String firstName, final String lastName,
                              final String password, final String userType) throws Exception {
        return objectMapper.writeValueAsString(
                new UserUpdateRequest(firstName, lastName, password, userType));
    }

    // ------------------------------------------------------------------------------------------
    // GET /api/users — list users (COUSR00C / CU00)
    // ------------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET list as ADMIN -> 200, page size 10, no password, one-based page bridged to zero")
    void listUsersAsAdminReturnsPageOfTenWithoutPassword() throws Exception {
        when(userService.listUsers(any(), anyInt())).thenReturn(sampleList());

        final MvcResult result = mockMvc.perform(get(USERS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.pageSize").value(PAGE_SIZE))
                .andExpect(jsonPath("$.page.content[0].userId").value(USER_ID))
                .andExpect(jsonPath("$.page.content[0].userType").value(USER_TYPE))
                .andReturn();

        // No credential may ever appear in a list projection.
        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("password");
        // The one-based REST page (default 1) must be bridged to the zero-based service index.
        verify(userService).listUsers(null, 0);
    }

    // ------------------------------------------------------------------------------------------
    // GET /api/users/{userId} — view user (COUSR03C / CU03)
    // ------------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET user as ADMIN -> 200 with the user id and no password")
    void getUserAsAdminReturnsUserWithoutPassword() throws Exception {
        when(userService.getUser(USER_ID)).thenReturn(sampleUser(USER_ID, null));

        final MvcResult result = mockMvc.perform(get(USERS_PATH + "/{userId}", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.firstName").value(FIRST_NAME))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("password");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET user not found -> 404 with a correlation id")
    void getUserNotFoundReturns404() throws Exception {
        when(userService.getUser(USER_ID))
                .thenThrow(new ResourceNotFoundException("User ID NOT found..."));

        mockMvc.perform(get(USERS_PATH + "/{userId}", USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    // ------------------------------------------------------------------------------------------
    // POST /api/users — add user (COUSR01C / CU01)
    // ------------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST create as ADMIN -> 201, confirmation body, no password or submitted value echoed")
    void createUserAsAdminReturns201WithoutPassword() throws Exception {
        when(userService.createUser(any(UserCreateRequest.class)))
                .thenReturn(sampleUser(NEW_USER_ID, "User " + NEW_USER_ID + " has been added ..."));

        final MvcResult result = mockMvc.perform(post(USERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(NEW_USER_ID, FIRST_NAME, LAST_NAME, VALID_PASSWORD, USER_TYPE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(NEW_USER_ID))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        final String body = result.getResponse().getContentAsString();
        // Neither a password field nor the submitted plaintext value may leak into the response.
        assertThat(body).doesNotContainIgnoringCase("password");
        assertThat(body).doesNotContain(VALID_PASSWORD);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST create duplicate -> 409 with code DUPLICATE_KEY and a correlation id")
    void createUserDuplicateReturns409() throws Exception {
        when(userService.createUser(any(UserCreateRequest.class)))
                .thenThrow(new DuplicateResourceException("User ID already exist..."));

        mockMvc.perform(post(USERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(NEW_USER_ID, FIRST_NAME, LAST_NAME, VALID_PASSWORD, USER_TYPE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DUPLICATE_KEY"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST create invalid -> 400 VALIDATION_ERROR, field errors, password value never echoed, service not called")
    void createUserInvalidReturns400WithoutLeakingPassword() throws Exception {
        // Blank mandatory fields (@NotBlank), an over-length password (@Size), and a bad user type
        // (@Pattern("[AU]")) — every field-edit branch of COUSR01C, in one request.
        final String invalidBody = createBody("", "", "", OVERLONG_PASSWORD, "X");

        final MvcResult result = mockMvc.perform(post(USERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists())
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn();

        // The rejected password value must never be echoed back (rejectedValueSummary stays null).
        assertThat(result.getResponse().getContentAsString()).doesNotContain(OVERLONG_PASSWORD);
        // Validation fails before delegation: the service is never touched.
        verify(userService, never()).createUser(any());
    }

    // ------------------------------------------------------------------------------------------
    // PUT /api/users/{userId} — update user (COUSR02C / CU02)
    // ------------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT update as ADMIN -> 200 with confirmation and no password")
    void updateUserAsAdminReturns200WithoutPassword() throws Exception {
        when(userService.updateUser(eq(USER_ID), any(UserUpdateRequest.class)))
                .thenReturn(sampleUser(USER_ID, "User " + USER_ID + " has been updated ..."));

        final MvcResult result = mockMvc.perform(put(USERS_PATH + "/{userId}", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(FIRST_NAME, LAST_NAME, null, USER_TYPE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("password");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT update not found -> 404 with a correlation id")
    void updateUserNotFoundReturns404() throws Exception {
        when(userService.updateUser(eq(USER_ID), any(UserUpdateRequest.class)))
                .thenThrow(new ResourceNotFoundException("User ID NOT found..."));

        mockMvc.perform(put(USERS_PATH + "/{userId}", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(FIRST_NAME, LAST_NAME, null, USER_TYPE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    // ------------------------------------------------------------------------------------------
    // DELETE /api/users/{userId} — delete user (COUSR03C / CU03)
    // ------------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE as ADMIN -> 200 with confirmation and no password")
    void deleteUserAsAdminReturns200() throws Exception {
        when(userService.deleteUser(USER_ID))
                .thenReturn(sampleUser(USER_ID, "User " + USER_ID + " has been deleted ..."));

        final MvcResult result = mockMvc.perform(delete(USERS_PATH + "/{userId}", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("password");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE not found -> 404 with a correlation id")
    void deleteUserNotFoundReturns404() throws Exception {
        when(userService.deleteUser(USER_ID))
                .thenThrow(new ResourceNotFoundException("User ID NOT found..."));

        mockMvc.perform(delete(USERS_PATH + "/{userId}", USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    // ------------------------------------------------------------------------------------------
    // Admin gating — non-admin (ROLE_USER) is forbidden; anonymous is unauthorized
    // ------------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("GET list as non-admin USER -> 403; service never invoked")
    void listUsersAsUserIsForbidden() throws Exception {
        mockMvc.perform(get(USERS_PATH))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST create as non-admin USER -> 403; service never invoked")
    void createUserAsUserIsForbidden() throws Exception {
        mockMvc.perform(post(USERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(NEW_USER_ID, FIRST_NAME, LAST_NAME, VALID_PASSWORD, USER_TYPE)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("DELETE as non-admin USER -> 403; service never invoked")
    void deleteUserAsUserIsForbidden() throws Exception {
        mockMvc.perform(delete(USERS_PATH + "/{userId}", USER_ID))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("GET list as anonymous -> 401; service never invoked")
    void listUsersAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(USERS_PATH))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }
}
