package com.carddemo.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CardDemo Auth Service &mdash; context-load / health smoke test.
 *
 * <p>The one and only test class in the {@code auth-svc} test source tree. It backs the
 * <em>build-and-start-green</em> requirement for {@code auth-svc}, a request-serving,
 * health-gated Spring Boot 3.5.16 / Java 21 microservice of the CardDemo walking
 * skeleton (a clean-room, modernized topology of the legacy AWS CardDemo mainframe
 * application). This test verifies that the seams hold together &mdash; that the whole
 * Spring application context wires and loads &mdash; not behavioral parity with the
 * mainframe.</p>
 *
 * <p><strong>Offline-green by design.</strong> The primary assertion is implicit: the
 * full Spring context boots with <em>no live Oracle database present</em>. The Docker
 * image build and the offline CI compile phase have no database, so this test must pass
 * without one. That guarantee is provided entirely by the sibling test-scoped
 * {@code src/test/resources/application.yml}, which shadows the production configuration
 * so Hikari never eagerly connects, Hibernate uses an explicit dialect (no live metadata
 * probe) over an empty entity model, and Flyway does not migrate. This class relies on
 * that configuration and adds nothing that would reintroduce a database dependency.</p>
 *
 * <p>Booting the context with a plain {@link SpringBootTest} exercises the hand-written
 * {@code com.carddemo.auth.web.AuthController}, the {@code com.carddemo.auth.config}
 * cross-cutting beans ({@code CorrelationIdFilter} and {@code OpenApiConfig}), and the
 * OpenAPI-generated {@code com.carddemo.auth.api} interfaces / {@code com.carddemo.auth.model}
 * types &mdash; all wired together. Because this class shares the base package
 * {@code com.carddemo.auth} with the {@code @SpringBootApplication} entrypoint
 * ({@code AuthApplication}), {@link SpringBootTest} auto-discovers that configuration
 * class automatically &mdash; no explicit configuration-class override or component-scan
 * override is declared here, which is intentional and required.</p>
 *
 * <p>The second test drives the permissive sign-on seam over HTTP (via {@link MockMvc},
 * no network, no database): it confirms a real bearer token is issued, the user id is
 * upper-cased, the typed response carries the expected fields, and &mdash; honoring the
 * legacy masked-secret behavior &mdash; the response never carries a password field.</p>
 *
 * <p>Provenance: {@code [SRC: COSGN00C | COSGN00.bms]} &mdash; the legacy CICS Sign-On
 * transaction {@code CC00 -> program COSGN00C} reading the {@code USRSEC} VSAM KSDS,
 * whose modern permissive-authentication bounded context {@code auth-svc} represents.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApplicationTests {

    /**
     * Server-side Spring MVC test client, auto-configured by {@link AutoConfigureMockMvc}.
     * It dispatches requests through the full servlet stack (filters + the
     * {@code AuthController} that implements the generated API interface) entirely
     * in-process &mdash; no HTTP socket and no database are involved.
     */
    @Autowired
    MockMvc mockMvc;

    /**
     * PRIMARY (mandatory) smoke test: the entire Spring application context loads green
     * offline. The assertion is implicit &mdash; if any bean fails to wire or the context
     * fails to start (including a stray attempt to open a database connection), Spring
     * throws and this test fails.
     */
    @Test
    void contextLoads() {
        // Context loaded: the full auth-svc application context wired and started with no
        // live Oracle database present. No explicit assertion is required.
    }

    /**
     * Drives the permissive sign-on seam end-to-end over HTTP without a database, and
     * enforces the no-password-leak guarantee.
     *
     * <p>{@code POST /auth/login} is declared with empty security in the frozen contract
     * and there is no Spring Security on the classpath, so no token is needed to invoke
     * it; the {@code X-Correlation-ID} header is optional, so omitting it still yields
     * HTTP 200. The controller builds its response with no database access, keeping this
     * test faithful to the offline-green guarantee.</p>
     *
     * <p>The request body is an inline JSON literal (rather than a generated model
     * object) so the test stays decoupled from build-time generated code. Assertions are
     * made structurally via JSON path only; neither the submitted credentials nor the raw
     * response body are ever logged or printed &mdash; honoring the legacy masked-secret
     * behavior ({@code COSGN00.bms} defines the password field as dark / non-display).</p>
     *
     * @throws Exception if the mock request dispatch fails
     */
    @Test
    void loginReturnsTokenAndNeverLeaksPassword() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user1\",\"password\":\"x\"}"))
                // A well-formed permissive sign-on succeeds.
                .andExpect(status().isOk())
                // A real, opaque bearer token is issued (never blank).
                .andExpect(jsonPath("$.token").isNotEmpty())
                // The user id is normalized to upper case (legacy MOVE FUNCTION UPPER-CASE(USERIDI)).
                .andExpect(jsonPath("$.userId").value("USER1"))
                // The skeleton defaults every caller to the User role.
                .andExpect(jsonPath("$.userType").value("U"))
                // Opaque bearer scheme with the advertised lifetime.
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                // MANDATORY: the response must never carry a password field.
                .andExpect(jsonPath("$.password").doesNotExist());
    }
}
