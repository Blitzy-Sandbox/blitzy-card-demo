/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.dto.auth;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable session object representing a successfully authenticated user — the Java
 * replacement for the COBOL {@code CARDDEMO-COMMAREA} fields populated by
 * {@code COSGN00C.cbl}:
 * <pre>
 *   MOVE WS-USER-ID   TO CDEMO-USER-ID
 *   MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 *   MOVE WS-TRANID    TO CDEMO-FROM-TRANID
 *   MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
 *   MOVE ZEROS        TO CDEMO-PGM-CONTEXT
 * </pre>
 *
 * <p>In COBOL, the next program is invoked via {@code EXEC CICS XCTL PROGRAM('...')}
 * with the COMMAREA carrying these fields. In Java, the {@link #nextRoute} field
 * encodes the same dispatch decision (e.g., {@code "MAIN_MENU"} for regular users,
 * {@code "ADMIN_MENU"} for admins) and the controller layer interprets it to redirect
 * the HTTP response or compose the next view-model.
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@link #userId} — the 8-character authenticated user identifier (COBOL
 *       {@code CDEMO-USER-ID})</li>
 *   <li>{@link #userType} — one of {@code "U"} or {@code "A"} (COBOL
 *       {@code CDEMO-USER-TYPE}; drives the dispatch decision in COSGN00C
 *       {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...})</li>
 *   <li>{@link #loginTime} — the wall-clock instant of authentication, stamped from
 *       the injected {@code Clock} (deterministic in tests via
 *       {@code Clock.fixed(Instant.parse("2024-01-15T00:00:00Z"), ZoneOffset.UTC)})</li>
 *   <li>{@link #nextRoute} — the next-screen hint ({@code "MAIN_MENU"} or
 *       {@code "ADMIN_MENU"}); the COBOL equivalent is the program name passed to
 *       {@code EXEC CICS XCTL}</li>
 *   <li>{@link #token} — optional opaque session token for stateless
 *       Bearer-token authentication on subsequent HTTP requests. {@code null} when
 *       the session is produced by {@code AuthenticationService} in isolation
 *       (e.g., unit tests that mock the service); populated by the
 *       {@code AuthController} after successful sign-on by registering the session
 *       with the {@code SessionTokenRegistry} so the
 *       {@code TokenAuthenticationFilter} can authenticate later requests carrying
 *       {@code Authorization: Bearer <token>}.</li>
 * </ul>
 *
 * <h2>Security — No Password Field</h2>
 *
 * <p>By design, this class has NO password field — neither plaintext nor hashed.
 * The {@link #toString()} method is therefore safe to log: it cannot disclose the
 * credential under any circumstances. Per AAP §0.10.5 ("No financial data written
 * to logs at any level"), applied transitively to credentials.
 *
 * <h2>Token-Transport Model (AAP §0.10.4)</h2>
 *
 * <p>The session token is the canonical Spring Security opaque-token pattern: a
 * UUID generated server-side, exchanged with the client in the sign-on response
 * body (NOT a cookie — AAP §0.10.5 architectural decision documented in
 * {@code AuthControllerTest.signOn_neverIssuesSessionCookie_onAnyResponsePath()}),
 * and presented by the client on subsequent requests via {@code Authorization:
 * Bearer <token>}. The {@code SessionTokenRegistry} maps the token back to a
 * {@code (userId, userType)} pair so the {@code TokenAuthenticationFilter} can
 * reconstruct the security principal without a server-side {@code HttpSession}.
 *
 * @see com.aws.carddemo.service.AuthenticationService
 * @see AuthenticationResult#getSession()
 */
public final class UserSession {

    private final String userId;
    private final String userType;
    private final LocalDateTime loginTime;
    private final String nextRoute;
    private final String token;

    /**
     * Constructs a new immutable session WITHOUT a token. This is the canonical
     * service-layer constructor: {@code AuthenticationService} produces a
     * token-less session because token issuance is a transport concern owned by
     * the controller (so the service can be unit-tested without coupling to the
     * {@code SessionTokenRegistry} Spring bean).
     *
     * @param userId    the authenticated user identifier
     * @param userType  one of {@code "U"} or {@code "A"}
     * @param loginTime the authentication moment from the injected clock
     * @param nextRoute the next-screen hint
     */
    public UserSession(String userId, String userType, LocalDateTime loginTime, String nextRoute) {
        this(userId, userType, loginTime, nextRoute, null);
    }

    /**
     * Constructs a new immutable session WITH a token. Invoked by
     * {@code AuthController.signOn} after {@code SessionTokenRegistry.register}
     * to produce the on-the-wire response shape that carries the Bearer token
     * for stateless authentication of subsequent requests.
     *
     * @param userId    the authenticated user identifier
     * @param userType  one of {@code "U"} or {@code "A"}
     * @param loginTime the authentication moment from the injected clock
     * @param nextRoute the next-screen hint
     * @param token     the opaque session token to be presented on subsequent
     *                  requests as {@code Authorization: Bearer <token>};
     *                  may be {@code null} when this constructor is invoked
     *                  in a code path that does not require token issuance
     */
    public UserSession(String userId,
                       String userType,
                       LocalDateTime loginTime,
                       String nextRoute,
                       String token) {
        this.userId = userId;
        this.userType = userType;
        this.loginTime = loginTime;
        this.nextRoute = nextRoute;
        this.token = token;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserType() {
        return userType;
    }

    public LocalDateTime getLoginTime() {
        return loginTime;
    }

    public String getNextRoute() {
        return nextRoute;
    }

    /**
     * Returns the opaque session token presented by the client on subsequent
     * requests, or {@code null} when no token is associated with this session
     * (for example, a session produced by {@code AuthenticationService} in a
     * unit test where token issuance is not exercised).
     *
     * <p>The token is serialised to JSON as {@code session.token} by the default
     * Jackson naming strategy; the {@code OnlineTransactionE2ETest} signIn
     * helper reads either {@code response.token} or {@code response.session.token}
     * to extract the Bearer credential.
     *
     * @return the opaque session token, or {@code null} when not issued
     */
    public String getToken() {
        return token;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserSession)) {
            return false;
        }
        UserSession that = (UserSession) o;
        return Objects.equals(userId, that.userId)
                && Objects.equals(userType, that.userType)
                && Objects.equals(loginTime, that.loginTime)
                && Objects.equals(nextRoute, that.nextRoute)
                && Objects.equals(token, that.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, userType, loginTime, nextRoute, token);
    }

    /**
     * String representation containing the non-credential session fields. Both
     * the password field (which does not exist) and the session token (which
     * is a Bearer credential equivalent to a session cookie and therefore PCI-
     * adjacent per AAP §0.10.5) are deliberately omitted from this rendering
     * so the method is safe to call from log statements.
     */
    @Override
    public String toString() {
        return "UserSession{"
                + "userId='" + userId + '\''
                + ", userType='" + userType + '\''
                + ", loginTime=" + loginTime
                + ", nextRoute='" + nextRoute + '\''
                + '}';
    }
}
