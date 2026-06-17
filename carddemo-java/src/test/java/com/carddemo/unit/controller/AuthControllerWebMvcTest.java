package com.carddemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.AuthController;
import com.carddemo.exception.AuthenticationFailedException;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.service.auth.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer (MockMvc) contract tests for {@link AuthController}.
 *
 * <p>Resolves the CP4 review's JWT TTL configuration finding at the HTTP layer. The controller now
 * binds {@code carddemo.security.jwt.expiration-seconds} (the single authoritative key defined in
 * {@code application.yml}); this class overrides that property to a non-default value and asserts
 * the minted token's {@code exp - iat} tracks it, proving the configured expiration is honoured
 * rather than the {@code 3600}-second fallback (review finding + DECISION_LOG D-023).</p>
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "carddemo.security.jwt.secret=test-jwt-secret-key-at-least-32-bytes-long-0123456789",
        "carddemo.security.jwt.expiration-seconds=120"
})
class AuthControllerWebMvcTest {

    /** The non-default expiration injected via {@link TestPropertySource} above. */
    private static final long CONFIGURED_TTL_SECONDS = 120L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthenticationService authenticationService;

    @Test
    void signin_validCredentials_issuesTokenWithConfiguredExpiry() throws Exception {
        UserSecurity user = new UserSecurity();
        user.setSecUsrId("USER0001");
        user.setSecUsrType(UserType.USER);
        when(authenticationService.authenticate("USER0001", "pass1234")).thenReturn(user);

        String body = "{\"userId\":\"USER0001\",\"password\":\"pass1234\"}";

        String responseJson = mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.userType").value("USER"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(responseJson).get("token").asText();
        Jwt decoded = jwtDecoder.decode(token);

        // F2 / D-023: the configured expiration-seconds (120) must drive the token lifetime,
        // NOT the 3600-second fallback that the prior ttl-seconds key mismatch silently used.
        long ttlSeconds = ChronoUnit.SECONDS.between(decoded.getIssuedAt(), decoded.getExpiresAt());
        assertThat(ttlSeconds).isEqualTo(CONFIGURED_TTL_SECONDS);
        assertThat(decoded.getSubject()).isEqualTo("USER0001");
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
    }

    @Test
    void signin_blankUserId_isBadRequest() throws Exception {
        String body = "{\"userId\":\"\",\"password\":\"pass1234\"}";

        mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signin_authenticationFailure_isUnauthorizedAndGeneralized() throws Exception {
        // F13/F14: a failed credential check (unknown user OR wrong password) reaches the controller
        // as a single AuthenticationFailedException and must surface as a generalized 401 via
        // GlobalExceptionHandler — never a 404/400 that would let a caller enumerate valid user ids.
        // Password length stays within the SignOnRequest @Size(max=8) contract so the request passes
        // @Valid and actually reaches the (mocked) service, exercising the auth-failure → 401 path.
        when(authenticationService.authenticate("USER0001", "WRONGPWD"))
                .thenThrow(new AuthenticationFailedException(
                        "Authentication failed. Please check your credentials and try again."));

        String body = "{\"userId\":\"USER0001\",\"password\":\"WRONGPWD\"}";

        mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Authentication Failed"))
                .andExpect(jsonPath("$.detail").value(
                        "Authentication failed. Please check your credentials and try again."));
    }
}
