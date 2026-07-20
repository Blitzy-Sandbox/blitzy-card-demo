package com.carddemo.bff.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.carddemo.bff.model.LoginRequest;
import com.carddemo.bff.model.LoginResponse;

/**
 * Focused tests for {@link AuthAggregator} &mdash; the real BFF&nbsp;-&gt;&nbsp;auth-svc sign-on hop.
 *
 * <p>Verifies that the aggregator (a) issues a real {@code POST /auth/login} to auth-svc's root
 * (no {@code /api} prefix) carrying the sign-on body, (b) returns the token auth-svc issues rather
 * than minting one locally &mdash; proving auth-svc is the single token authority (AAP §0.1.3 /
 * §0.4) &mdash; and (c) lets a downstream error status propagate as a
 * {@link RestClientResponseException} so the sibling {@code web.GlobalExceptionHandler} can map it
 * to {@code 502 Bad Gateway}. A {@link MockRestServiceServer} bound to the same
 * {@link RestClient.Builder} stands in for auth-svc, so the hop is exercised end-to-end without a
 * running service. Provenance: {@code [SRC: COSGN00C | COSGN00.bms]}.</p>
 */
class AuthAggregatorTest {

    private static final String AUTH_BASE_URL = "http://auth-svc:8080";
    private static final String LOGIN_URI = AUTH_BASE_URL + "/auth/login";

    private MockRestServiceServer server;
    private AuthAggregator aggregator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(AUTH_BASE_URL);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.aggregator = new AuthAggregator(builder.build());
    }

    @Test
    void login_forwardsToAuthSvc_andReturnsIssuedToken() {
        // auth-svc is the token authority: it returns a real opaque token and the upper-cased user id.
        server.expect(requestTo(LOGIN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"userId\":\"user0001\",\"password\":\"secret12\"}"))
                .andRespond(withSuccess(
                        "{\"token\":\"auth-issued-token-123\",\"userId\":\"USER0001\","
                                + "\"userType\":\"U\",\"expiresIn\":3600,\"tokenType\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        LoginResponse response = aggregator.login(new LoginRequest("user0001", "secret12"));

        // The BFF returns exactly what auth-svc issued (it does not mint its own token).
        assertThat(response).isNotNull();
        assertThat(response.getToken()).isEqualTo("auth-issued-token-123");
        assertThat(response.getUserId()).isEqualTo("USER0001");
        assertThat(response.getUserType()).isEqualTo(LoginResponse.UserTypeEnum.U);
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        server.verify();
    }

    @Test
    void login_propagatesDownstreamErrorStatus() {
        // A 5xx from auth-svc must surface as a RestClientResponseException so the web layer maps it
        // to 502 Bad Gateway (rather than the BFF silently masking the downstream failure).
        server.expect(requestTo(LOGIN_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> aggregator.login(new LoginRequest("user0001", "secret12")))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }
}
