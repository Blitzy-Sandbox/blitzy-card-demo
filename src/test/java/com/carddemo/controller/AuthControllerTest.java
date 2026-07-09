package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.carddemo.dto.SignonRequest;
import com.carddemo.dto.SignonResponse;
import com.carddemo.service.SignonService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Context-free unit test for {@link AuthController} &mdash; the sign-on REST endpoint that replaces the
 * legacy {@code COSGN00C} program (transaction {@code CC00}; source commit {@code 27d6c6f}).
 *
 * <p>The suite loads no Spring {@code ApplicationContext} and no security filter chain: it mocks the
 * {@link SignonService} collaborator and drives the controller through a standalone {@link MockMvc}
 * wired with the real {@link GlobalExceptionHandler} advice, so the controller's HTTP contract and
 * the exception&rarr;status mapping are exercised exactly as at runtime while the test stays fast and
 * hermetic. It pins the behaviour the migration mandates: valid credentials yield {@code 200} with a
 * JWT-bearing body, blank fields are rejected with {@code 400} by bean validation, a
 * {@code BadCredentialsException} surfaces as a generic {@code 401} (no account-existence leak), and
 * the response never carries a {@code password} field.</p>
 */
@DisplayName("AuthController — POST /api/auth/login (COSGN00C / CC00)")
class AuthControllerTest {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String USER_ID = "ADMIN001";
    private static final String PASSWORD = "PASS0001";
    private static final String JWT = "header.payload.signature";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SignonService signonService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        signonService = mock(SignonService.class);
        AuthController controller = new AuthController(signonService);
        // Standalone setup: no Spring context, no security chain. Register the real advice so the
        // exception -> HTTP mapping under test is the production mapping.
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("valid credentials -> 200 OK with token/tokenType/role and no password echoed")
    void validCredentialsReturnOkWithToken() throws Exception {
        SignonResponse response = new SignonResponse(
                JWT, SignonResponse.BEARER, USER_ID, "ADMIN", "USER", SignonResponse.ROLE_ADMIN);
        when(signonService.authenticate(any(SignonRequest.class))).thenReturn(response);

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(USER_ID, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(JWT))
                .andExpect(jsonPath("$.tokenType").value(SignonResponse.BEARER))
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.role").value(SignonResponse.ROLE_ADMIN))
                // SECURITY: the response must never contain the submitted password.
                .andExpect(jsonPath("$.password").doesNotExist());

        // The controller must delegate exactly once, passing the deserialized request through unchanged.
        ArgumentCaptor<SignonRequest> captor = ArgumentCaptor.forClass(SignonRequest.class);
        verify(signonService, times(1)).authenticate(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().password()).isEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("regular user role is passed through to the response body")
    void regularUserRoleReturned() throws Exception {
        SignonResponse response = new SignonResponse(
                JWT, SignonResponse.BEARER, "USER0001", "Reg", "Ular", SignonResponse.ROLE_USER);
        when(signonService.authenticate(any(SignonRequest.class))).thenReturn(response);

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("USER0001", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(SignonResponse.ROLE_USER));
    }

    @Test
    @DisplayName("blank userId -> 400 (bean validation) with fieldErrors; service never called")
    void blankUserIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'userId')]").exists());

        verify(signonService, never()).authenticate(any());
    }

    @Test
    @DisplayName("blank password -> 400 (bean validation) with fieldErrors; service never called")
    void blankPasswordReturnsBadRequest() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(USER_ID, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());

        verify(signonService, never()).authenticate(any());
    }

    @Test
    @DisplayName("userId over 8 chars -> 400 (@Size) with fieldErrors; service never called")
    void oversizedUserIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("TOOLONGID9", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'userId')]").exists());

        verify(signonService, never()).authenticate(any());
    }

    @Test
    @DisplayName("BadCredentialsException -> 401 with a GENERIC message (no account-existence leak)")
    void badCredentialsReturnsUnauthorizedGenericMessage() throws Exception {
        // The legacy program distinguished "User not found" from "Wrong Password"; the API must not.
        when(signonService.authenticate(any(SignonRequest.class)))
                .thenThrow(new BadCredentialsException("User not found. Try again ..."));

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(USER_ID, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid user id or password"));

        verify(signonService, times(1)).authenticate(any(SignonRequest.class));
    }

    /**
     * Serializes a sign-on request body as JSON.
     *
     * @param userId   the user id to place in the payload
     * @param password the password to place in the payload
     * @return the JSON string body
     * @throws Exception if serialization fails
     */
    private String json(String userId, String password) throws Exception {
        return objectMapper.writeValueAsString(new SignonRequest(userId, password));
    }
}
