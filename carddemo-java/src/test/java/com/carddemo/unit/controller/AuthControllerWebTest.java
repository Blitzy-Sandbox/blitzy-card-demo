package com.carddemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.AuthController;
import com.carddemo.model.dto.SignOnResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.service.auth.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web-slice tests for {@link AuthController} covering the CP4 sign-on contract and the JWT
 * expiration-property binding fix.
 *
 * <p>Two findings are asserted here against the real Spring Security filter chain
 * ({@link SecurityConfig} imported so the genuine {@link org.springframework.security.oauth2.jwt.JwtEncoder}
 * / {@link JwtDecoder} beans are exercised):</p>
 * <ul>
 *   <li><b>JWT expiration property (api-contracts.md §5.1 / config):</b> the controller must read
 *       {@code carddemo.security.jwt.expiration-seconds} (backed by {@code JWT_EXPIRATION}), not the
 *       former mismatched {@code ttl-seconds} key. This test overrides the property to a non-default
 *       1800s and asserts the issued token's {@code exp - iat} delta is exactly 1800s; were the wrong
 *       key still read, the value would silently fall back to the 3600s default and fail.</li>
 *   <li><b>Response contract (api-contracts.md §5.1):</b> the JSON body exposes {@code token},
 *       {@code userId}, {@code userType} (single-char {@code A}/{@code U} code), {@code role}
 *       ({@code ADMIN}/{@code USER}), and {@code expiresAt}, and carries no {@code errorMessage}
 *       field.</li>
 * </ul>
 *
 * <p>Source lineage (reference only, COBOL not copied): COSGN00C, AWS CardDemo commit 27d6c6f.</p>
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // >= 32 bytes so SecurityConfig.hmacKey() accepts it; test-only, never a real secret.
        "carddemo.security.jwt.secret=carddemo-web-test-signing-secret-0123456789",
        // Non-default value: proves the controller binds expiration-seconds (not ttl-seconds=3600).
        "carddemo.security.jwt.expiration-seconds=1800"
})
@DisplayName("AuthController web slice - sign-on JWT contract & expiration binding")
class AuthControllerWebTest {

    private static final long CONFIGURED_TTL_SECONDS = 1800L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthenticationService authenticationService;

    private static UserSecurity user(String id, UserType type) {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(id);
        u.setSecUsrType(type);
        return u;
    }

    @Nested
    @DisplayName("POST /api/auth/signin")
    class SignIn {

        @Test
        @DisplayName("admin sign-on returns full §5.1 contract and a JWT whose exp honors expiration-seconds")
        void adminSignOnContractAndExpiry() throws Exception {
            when(authenticationService.authenticate(eq("ADMIN001"), eq("PASS001")))
                    .thenReturn(user("ADMIN001", UserType.ADMIN));

            MvcResult result = mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"ADMIN001\",\"password\":\"PASS001\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isNotEmpty())
                    .andExpect(jsonPath("$.userId").value("ADMIN001"))
                    // userType is the single-char code 'A' (admin), role is the derived 'ADMIN'.
                    .andExpect(jsonPath("$.userType").value("A"))
                    .andExpect(jsonPath("$.role").value("ADMIN"))
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                    // The success contract carries no error field (failures surface via the handler).
                    .andExpect(jsonPath("$.errorMessage").doesNotExist())
                    .andReturn();

            SignOnResponse body = objectMapper.readValue(
                    result.getResponse().getContentAsString(), SignOnResponse.class);

            // Decode the issued token and assert exp - iat == configured expiration-seconds (1800),
            // which can only hold if the controller bound the correct property key.
            Jwt jwt = jwtDecoder.decode(body.token());
            assertThat(jwt.getIssuedAt()).isNotNull();
            assertThat(jwt.getExpiresAt()).isNotNull();
            assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds())
                    .isEqualTo(CONFIGURED_TTL_SECONDS);
            // Token claims carry the role/userType used for stateless authorization.
            assertThat(jwt.getSubject()).isEqualTo("ADMIN001");
            assertThat(jwt.getClaimAsString("userType")).isEqualTo("ADMIN");
            assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("standard user sign-on returns userType 'U' / role 'USER' per §3.2 derivation")
        void userSignOnRoleDerivation() throws Exception {
            when(authenticationService.authenticate(eq("USER0001"), eq("PASS0001")))
                    .thenReturn(user("USER0001", UserType.USER));

            mockMvc.perform(post("/api/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"USER0001\",\"password\":\"PASS0001\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userType").value("U"))
                    .andExpect(jsonPath("$.role").value("USER"))
                    .andExpect(jsonPath("$.token").isNotEmpty())
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty());
        }
    }
}
