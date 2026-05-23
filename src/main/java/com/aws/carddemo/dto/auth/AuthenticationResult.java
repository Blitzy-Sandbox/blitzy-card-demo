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

import java.util.Objects;

/**
 * Immutable result of an authentication attempt — the Java replacement for the
 * COBOL {@code WS-MESSAGE} display value plus the {@code CDEMO-USER-*} COMMAREA
 * fields populated on a successful sign-on in {@code app/cbl/COSGN00C.cbl}.
 *
 * <h2>Outcome Encoding</h2>
 *
 * <ul>
 *   <li><b>Success</b> — {@link #isSuccess()} returns {@code true},
 *       {@link #getSession()} returns a populated {@link UserSession},
 *       {@link #getMessage()} returns the welcome message
 *       ({@code "Welcome <FIRST> <LAST> ..."} in COBOL).</li>
 *   <li><b>Failure</b> — {@link #isSuccess()} returns {@code false},
 *       {@link #getSession()} returns {@code null},
 *       {@link #getMessage()} returns the reject message (one of the COBOL
 *       {@code WS-MESSAGE} literals: {@code "Please enter User ID ..."},
 *       {@code "Please enter Password ..."}, {@code "User not found. Try again ..."},
 *       {@code "Wrong Password. Try again ..."}, {@code "Account is locked. Contact
 *       administrator ..."}, or {@code "User type not valid ..."}).</li>
 * </ul>
 *
 * <h2>Construction</h2>
 *
 * <p>Instances are created via the static factory methods {@link #success(String,
 * UserSession)} and {@link #failure(String)} — the private constructor enforces
 * that callers use the factories rather than constructing inconsistent states
 * (e.g., {@code success=true} with a null session, or {@code success=false} with
 * a populated session).
 *
 * @see com.aws.carddemo.service.AuthenticationService#authenticate(AuthenticationRequest)
 * @see UserSession
 */
public final class AuthenticationResult {

    private final boolean success;
    private final String message;
    private final UserSession session;

    /**
     * Private constructor — callers MUST use {@link #success(String, UserSession)}
     * or {@link #failure(String)} static factories to construct a result in a
     * consistent state.
     */
    private AuthenticationResult(boolean success, String message, UserSession session) {
        this.success = success;
        this.message = message;
        this.session = session;
    }

    /**
     * Creates a successful authentication result with a welcome message and a
     * populated session.
     *
     * @param message the welcome message (typically
     *                {@code "Welcome <FIRST> <LAST> ..."})
     * @param session the authenticated user session (must not be {@code null} for
     *                a success result)
     * @return a success-marked result carrying the welcome message and session
     */
    public static AuthenticationResult success(String message, UserSession session) {
        return new AuthenticationResult(true, message, session);
    }

    /**
     * Creates a failed authentication result with a reject message and a {@code null}
     * session.
     *
     * @param message the reject message (one of the COBOL {@code WS-MESSAGE} literals
     *                — see class JavaDoc for the canonical list)
     * @return a failure-marked result carrying the reject message and a null session
     */
    public static AuthenticationResult failure(String message) {
        return new AuthenticationResult(false, message, null);
    }

    /**
     * @return {@code true} iff authentication succeeded
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return the welcome message (on success) or the reject message (on failure);
     *         never {@code null}
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return the authenticated user session on success; {@code null} on failure
     */
    public UserSession getSession() {
        return session;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuthenticationResult)) {
            return false;
        }
        AuthenticationResult that = (AuthenticationResult) o;
        return success == that.success
                && Objects.equals(message, that.message)
                && Objects.equals(session, that.session);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, message, session);
    }

    @Override
    public String toString() {
        return "AuthenticationResult{"
                + "success=" + success
                + ", message='" + message + '\''
                + ", session=" + session
                + '}';
    }
}
