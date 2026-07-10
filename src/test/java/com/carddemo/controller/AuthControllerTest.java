package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.dto.SignonRequest;
import com.carddemo.dto.SignonResponse;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.JwtService;
import com.carddemo.service.SignonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-security web-slice test for {@link AuthController} &mdash; the {@code POST /api/auth/login}
 * endpoint that migrates the legacy AWS CardDemo signon program {@code COSGN00C} (CICS transaction
 * {@code CC00}, BMS map {@code COSGN00}; frozen COBOL reference at source commit SHA {@code 27d6c6f}).
 *
 * <p>Unlike a bare controller unit test, this suite boots the real Spring MVC web slice
 * <em>together with the production security assembly</em> ({@link SecurityConfig}) and the real
 * observability {@link CorrelationIdFilter}. That combination exercises the authentication interface
 * contract end-to-end (Gate&nbsp;5): the request flows through the actual authorization matrix, the
 * JWT bearer filter, the {@code @RestControllerAdvice} exception mapping, and the correlation-id
 * MDC plumbing, exactly as at runtime. The only collaborators replaced by test doubles are the
 * controller's own {@link SignonService} and the {@link JwtService} required by the
 * {@link SecurityConfig} constructor; everything else is the genuine wired stack.</p>
 *
 * <h2>Behaviour pinned by this suite</h2>
 * <ul>
 *   <li><strong>Success.</strong> Valid credentials yield {@code 200 OK} with the JWT-bearing
 *       {@link SignonResponse} body ({@code token} / {@code tokenType} / {@code userId} / {@code role}),
 *       the role is passed through unchanged (regular {@code USER} and administrator {@code ADMIN},
 *       mirroring the {@code COSGN00C} routing to {@code COMEN01C} vs {@code COADM01C}), and the body
 *       never carries the submitted password.</li>
 *   <li><strong>Generic 401.</strong> A {@link BadCredentialsException} surfaces as a single generic
 *       {@code 401} that never discloses whether the account exists &mdash; collapsing the three
 *       distinct legacy messages ("Wrong Password ...", "User not found ...", "Unable to verify the
 *       User ...") into one, as the migration mandates.</li>
 *   <li><strong>Validation.</strong> Blank, oversized, or missing fields are rejected with
 *       {@code 400} by Jakarta Bean Validation before the service is ever consulted, and the rejected
 *       value is never echoed back.</li>
 *   <li><strong>Observability.</strong> The correlation id populated by {@link CorrelationIdFilter}
 *       appears on the error bodies.</li>
 * </ul>
 *
 * <p><strong>Test-double annotation.</strong> {@link MockitoBean} is used in place of the
 * now-deprecated {@code @MockBean}; it is the non-deprecated, semantically identical Spring Boot&nbsp;3.5
 * replacement, chosen so this file compiles warning-free under {@code -Xlint:all} (Gate&nbsp;2) with no
 * suppressed warnings. Rationale for the migration decisions lives in {@code docs/decision-log.md}, not
 * in these comments (Explainability rule).</p>
 *
 * @see AuthController
 * @see SignonService
 * @see SecurityConfig
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class})
@DisplayName("AuthController — POST /api/auth/login (COSGN00C / CC00) full-security web slice")
class AuthControllerTest {

    /** Endpoint under test: the REST migration of the {@code COSGN00C} signon transaction. */
    private static final String LOGIN_PATH = "/api/auth/login";

    /** A regular CardDemo user id (8 chars, {@code SEC-USR-TYPE 'U'} → {@code ROLE_USER}). */
    private static final String USER_ID = "USER0001";

    /** An administrator CardDemo user id (8 chars, {@code SEC-USR-TYPE 'A'} → {@code ROLE_ADMIN}). */
    private static final String ADMIN_ID = "ADMIN001";

    /**
     * A distinctive 8-character password value used across the suite. Being distinctive lets the
     * no-leakage assertions prove the secret never appears in any response body.
     */
    private static final String PASSWORD = "S3CRET99";

    /** A user id one character over the legacy {@code PIC X(8)} width, to trip {@code @Size(max = 8)}. */
    private static final String OVERSIZED_USER_ID = "USER00012";

    /** Opaque JWT value returned by the stubbed service on a successful sign-on. */
    private static final String JWT = "jwt-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** The controller's authentication collaborator; the entire COSGN00C credential flow is stubbed. */
    @MockitoBean
    private SignonService signonService;

    /** Required by the {@link SecurityConfig} constructor; never exercised on the permitAll login path. */
    @MockitoBean
    private JwtService jwtService;

    // ------------------------------------------------------------------
    // Success paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("valid regular-user credentials -> 200 with token/tokenType/userId/role; service invoked once")
    void loginWithValidUserCredentialsReturnsOkTokenAndRole() throws Exception {
        SignonResponse response = new SignonResponse(
                JWT, SignonResponse.BEARER, USER_ID, "John", "Doe", SignonResponse.ROLE_USER);
        when(signonService.authenticate(any(SignonRequest.class))).thenReturn(response);

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(USER_ID, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(JWT))
                .andExpect(jsonPath("$.tokenType").value(SignonResponse.BEARER))
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.role").value(SignonResponse.ROLE_USER))
                // SECURITY: the response must never surface the submitted password.
                .andExpect(jsonPath("$.password").doesNotExist());

        // The controller must delegate exactly once, forwarding the deserialized request unchanged.
        ArgumentCaptor<SignonRequest> captor = ArgumentCaptor.forClass(SignonRequest.class);
        verify(signonService, times(1)).authenticate(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().password()).isEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("successful login never leaks the password (no \"password\" key, no submitted value)")
    void loginSuccessDoesNotLeakPassword() throws Exception {
        SignonResponse response = new SignonResponse(
                JWT, SignonResponse.BEARER, USER_ID, "John", "Doe", SignonResponse.ROLE_USER);
        when(signonService.authenticate(any(SignonRequest.class))).thenReturn(response);

        String body = mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(USER_ID, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Neither the JSON key nor the secret value may appear anywhere in the serialized response.
        assertThat(body)
                .doesNotContain("\"password\"")
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("administrator credentials -> role ADMIN in the response (COSGN00C admin routing to COADM01C)")
    void loginWithAdminCredentialsReturnsAdminRole() throws Exception {
        SignonResponse response = new SignonResponse(
                JWT, SignonResponse.BEARER, ADMIN_ID, "Ada", "Admin", SignonResponse.ROLE_ADMIN);
        when(signonService.authenticate(any(SignonRequest.class))).thenReturn(response);

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(ADMIN_ID, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ADMIN_ID))
                .andExpect(jsonPath("$.role").value(SignonResponse.ROLE_ADMIN));
    }

    // ------------------------------------------------------------------
    // Failure path — generic 401 (no account-existence disclosure)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bad credentials -> 401 with a GENERIC message (no user-exists leak) and a correlation id")
    void loginWithBadCredentialsReturnsGeneric401() throws Exception {
        // COSGN00C distinguished "User not found ..." from "Wrong Password ..."; the API must not.
        when(signonService.authenticate(any(SignonRequest.class)))
                .thenThrow(new BadCredentialsException("User not found. Try again ..."));

        String body = mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(USER_ID, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                // The correlation id set by the real CorrelationIdFilter must be present on the error body.
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // Accept whichever code the wired stack emits: the @RestControllerAdvice maps the thrown
        // BadCredentialsException to AUTHENTICATION_FAILED, whereas a filter-chain denial would emit
        // UNAUTHORIZED. Either satisfies the generic-401 contract.
        String code = objectMapper.readTree(body).path("code").asText();
        assertThat(code).isIn("AUTHENTICATION_FAILED", "UNAUTHORIZED");

        // Generic message: it must not reveal whether the account exists, nor echo the credentials.
        String message = objectMapper.readTree(body).path("message").asText();
        assertThat(message)
                .doesNotContainIgnoringCase("not found")
                .doesNotContainIgnoringCase("exist")
                .doesNotContain(USER_ID);
        // The submitted password value must never appear anywhere in the response body.
        assertThat(body).doesNotContain(PASSWORD);

        verify(signonService, times(1)).authenticate(any(SignonRequest.class));
    }

    // ------------------------------------------------------------------
    // Validation paths — 400 before the service is consulted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("blank userId -> 400 VALIDATION_ERROR with a userId field error; service not invoked")
    void loginWithBlankUserIdReturns400() throws Exception {
        String body = mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                // The correlation id must also be present on validation error bodies.
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldErrorFields(body)).contains("userId");

        verify(signonService, never()).authenticate(any(SignonRequest.class));
    }

    @Test
    @DisplayName("blank password -> 400 with a password field error; rejected value omitted; service not invoked")
    void loginWithBlankPasswordReturns400AndOmitsRejectedValue() throws Exception {
        String body = mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(USER_ID, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldErrorFields(body)).contains("password");
        // The handler passes a null rejected-value summary, which @JsonInclude(NON_NULL) omits entirely:
        // no rejected value (and therefore no password material) is ever echoed back.
        assertThat(body).doesNotContain("rejectedValueSummary");

        verify(signonService, never()).authenticate(any(SignonRequest.class));
    }

    @Test
    @DisplayName("userId longer than 8 chars -> 400 (@Size) with a userId field error; service not invoked")
    void loginWithOversizedUserIdReturns400() throws Exception {
        String body = mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(OVERSIZED_USER_ID, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldErrorFields(body)).contains("userId");

        verify(signonService, never()).authenticate(any(SignonRequest.class));
    }

    @Test
    @DisplayName("empty request body -> 400 (unreadable message); service not invoked")
    void loginWithEmptyBodyReturns400() throws Exception {
        // An empty body cannot be bound to the required @RequestBody; the framework maps this to 400.
        // The assertion is intentionally tolerant (status only), per the file specification.
        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());

        verify(signonService, never()).authenticate(any(SignonRequest.class));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Serializes a {@link SignonRequest} to its JSON wire form using the application's configured
     * {@link ObjectMapper}, so the request bodies exactly match what the running service would receive.
     *
     * @param userId   the user id to place in the payload (may be blank / oversized for negative cases)
     * @param password the password to place in the payload
     * @return the JSON request body
     * @throws Exception if serialization fails
     */
    private String json(String userId, String password) throws Exception {
        return objectMapper.writeValueAsString(new SignonRequest(userId, password));
    }

    /**
     * Extracts the {@code field} names from the {@code fieldErrors} array of an {@code ErrorResponse}
     * JSON body, so validation assertions can check the offending field deterministically (independent
     * of array ordering or JsonPath filter-expression semantics).
     *
     * @param body the serialized {@code ErrorResponse} JSON body
     * @return the list of field names present in {@code fieldErrors} (empty when there are none)
     * @throws Exception if the body cannot be parsed as JSON
     */
    private List<String> fieldErrorFields(String body) throws Exception {
        JsonNode fieldErrors = objectMapper.readTree(body).path("fieldErrors");
        List<String> names = new ArrayList<>();
        for (JsonNode fieldError : fieldErrors) {
            names.add(fieldError.path("field").asText());
        }
        return names;
    }
}
