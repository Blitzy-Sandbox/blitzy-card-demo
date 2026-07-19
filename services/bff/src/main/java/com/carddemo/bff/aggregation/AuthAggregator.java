package com.carddemo.bff.aggregation;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.carddemo.bff.model.LoginRequest;
import com.carddemo.bff.model.LoginResponse;

/**
 * [DEFERRED] Permissive sign-on aggregator (typed stub).
 *
 * <p>Issues a real opaque bearer token locally so the token hop and React
 * Router route guards function; performs NO credential validation and NO
 * downstream call to auth-svc (BFF -&gt; auth is a stub in this skeleton; only
 * Card Detail is the live tracer). Provenance: [SRC: COSGN00 | COSGN00C].</p>
 *
 * <p>The walking-skeleton contract keeps this seam intentionally thin: the
 * correlation-ID / token hop is real (a genuine opaque token is minted so the
 * UI can attach it to every subsequent request and the router guards can gate
 * navigation), but there is deliberately no persistence, no domain logic, no
 * password hashing, no RBAC, and no session management. A typed placeholder is
 * the correct outcome for this stub; anything more would be a hallucinated
 * implementation of a deferred capability.</p>
 *
 * <p>The sign-on fields mirror the legacy CICS Sign-On screen COSGN00
 * (USERID/PASSWD, each up to 8 characters) handled by program COSGN00C over the
 * USRSEC VSAM file (transaction CC00).</p>
 */
@Service
public class AuthAggregator {

    /**
     * Permissive login: echoes the supplied user id, issues an opaque token,
     * and defaults the user type. No validation is performed. [DEFERRED]
     *
     * <p>Because auth is a stub in this skeleton, this method makes no
     * downstream call and inspects no credentials. It mints a fresh, opaque
     * bearer token ({@link UUID}) so the real token hop through
     * UI -&gt; BFF -&gt; card-svc continues to function, then echoes the
     * requested user id back to the caller.</p>
     *
     * @param request the sign-on request (userId + password); never {@code null}
     * @return a typed placeholder {@link LoginResponse} carrying a real opaque token
     */
    public LoginResponse login(LoginRequest request) {
        LoginResponse response = new LoginResponse();
        response.setToken(UUID.randomUUID().toString());
        response.setUserId(request.getUserId());
        response.setUserType(LoginResponse.UserTypeEnum.U);
        response.setExpiresIn(3600L);
        response.setTokenType("Bearer");
        return response;
    }
}
