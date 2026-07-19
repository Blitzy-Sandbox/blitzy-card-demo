package com.carddemo.auth.web;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.auth.api.AuthApi;
import com.carddemo.auth.model.LoginRequest;
import com.carddemo.auth.model.LoginResponse;
import com.carddemo.auth.model.TokenValidationResponse;

/**
 * CardDemo Auth Service REST controller &mdash; permissive sign-on stub.
 *
 * <p>Web/REST layer of the {@code auth-svc} microservice in the CardDemo walking
 * skeleton. This class implements the OpenAPI-generated {@link AuthApi} interface
 * (contract-first: {@code contracts/auth-svc.openapi.yaml} is the frozen single
 * source of truth) and returns <strong>typed placeholder</strong> responses. It is
 * the only hand-written class in the {@code com.carddemo.auth.web} package; the API
 * interface and DTO models are generated into {@code target/generated-sources} at
 * build time and are never hand-edited.</p>
 *
 * <p>Provenance: {@code [SRC: COSGN00C | COSGN00.bms]} &mdash; the legacy CICS Sign-On
 * transaction {@code CC00 -> COSGN00C}, which read the {@code USRSEC} VSAM KSDS and
 * compared the stored password before routing by user type (A=Admin, U=User).</p>
 *
 * <p><strong>Permissive stub by design.</strong> The controller issues a real,
 * opaque bearer token and participates in the real correlation-ID hop, but performs
 * <em>no</em> credential validation, password hashing, RBAC, or session management:
 * any well-formed request succeeds. The legacy {@code USRSEC} lookup and password
 * comparison are deliberately omitted in the skeleton, and the user type defaults to
 * {@code U} (User). The submitted password is never validated, logged, or returned.</p>
 *
 * <p>Cross-cutting concerns live elsewhere: request-body validation is enforced by the
 * bean-validation annotations on the generated {@link AuthApi} / {@link LoginRequest}
 * (a malformed request yields HTTP 400 before a method body runs); the correlation id
 * is placed into the SLF4J MDC by the sibling {@code config.CorrelationIdFilter}; and
 * {@code /actuator/health} is served by Spring Boot Actuator (the generated
 * {@code HealthApi} is intentionally left unimplemented). This controller therefore
 * holds no injected dependencies and implements {@link AuthApi} only.</p>
 */
@RestController
public class AuthController implements AuthApi {

    /** Token scheme returned to callers; the CardDemo skeleton issues opaque bearer tokens. */
    private static final String TOKEN_TYPE = "Bearer";

    /** Advertised token lifetime in seconds (int64), mirrored into {@code LoginResponse.expiresIn}. */
    private static final long EXPIRES_IN_SECONDS = 3600L;

    /**
     * Permissive sign-on: issues a real bearer token for any well-formed request.
     *
     * <p>Mirrors the legacy {@code COSGN00C} sign-on flow minus real authentication.
     * Blank, missing, or oversized fields are rejected up front by bean validation
     * (the generated {@code LoginRequest} carries {@code @NotNull}/{@code @Size(min=1,max=8)}
     * and the interface method is {@code @Valid}), yielding HTTP 400 before this body
     * runs &mdash; the modern equivalent of the legacy blank-field checks
     * ({@code COSGN00C.cbl} L118-127). The user id is normalized to upper case, mirroring
     * {@code MOVE FUNCTION UPPER-CASE(USERIDI)} ({@code COSGN00C.cbl} L132-134). The legacy
     * {@code USRSEC} read and {@code SEC-USR-PWD} comparison are intentionally not performed;
     * the returned user type defaults to {@code U} (User).</p>
     *
     * @param loginRequest validated sign-on credentials (userId + password, 1-8 chars each)
     * @param xCorrelationID optional propagated correlation id; the sibling filter populates the
     *                       SLF4J MDC, so it is intentionally not referenced here
     * @return HTTP 200 with a {@link LoginResponse} carrying a freshly generated bearer token,
     *         the upper-cased user id, {@code userType=U}, a 3600s lifetime, and {@code tokenType="Bearer"}
     */
    @Override
    public ResponseEntity<LoginResponse> login(LoginRequest loginRequest, UUID xCorrelationID) {
        // Normalize the user id to upper case (legacy MOVE FUNCTION UPPER-CASE(USERIDI)) using an
        // explicit, locale-independent transform so the result is deterministic across environments.
        final String userId = loginRequest.getUserId().toUpperCase(Locale.ROOT);

        // Issue a REAL, opaque bearer token: a genuine random value (never a hardcoded placeholder)
        // that downstream services and the BFF propagate on subsequent requests.
        final String token = UUID.randomUUID().toString();

        // Assemble the typed response. userType defaults to U (User): the skeleton deliberately omits
        // the legacy admin/user routing. The submitted password is never read into, or echoed by, the response.
        final LoginResponse response = new LoginResponse(token, userId, LoginResponse.UserTypeEnum.U)
                .expiresIn(EXPIRES_IN_SECONDS)
                .tokenType(TOKEN_TYPE);

        return ResponseEntity.ok(response);
    }

    /**
     * Bearer-token validation &mdash; {@code [DEFERRED]} typed stub.
     *
     * <p>Returns a permissive typed placeholder in the walking skeleton: the token is not parsed,
     * decoded, or cryptographically verified, and no session or RBAC lookup is performed. The
     * response reports {@code valid=true} with a default {@code userType=U}; the user id is left
     * unset because it is not required by the contract and no real identity data is fabricated.</p>
     *
     * @param xCorrelationID optional propagated correlation id; handled by the sibling filter/MDC,
     *                       so it is intentionally not referenced here
     * @return HTTP 200 with a permissive {@link TokenValidationResponse} ({@code valid=true})
     */
    @Override
    public ResponseEntity<TokenValidationResponse> validateToken(UUID xCorrelationID) {
        // [DEFERRED] permissive stub — no real token parsing/verification (F-SKEL skeleton).
        final TokenValidationResponse response = new TokenValidationResponse(Boolean.TRUE)
                .userType(TokenValidationResponse.UserTypeEnum.U);

        return ResponseEntity.ok(response);
    }
}
