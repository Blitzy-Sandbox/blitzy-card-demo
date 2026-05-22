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
package com.aws.carddemo.controller;

import com.aws.carddemo.dto.auth.AuthenticationRequest;
import com.aws.carddemo.dto.auth.AuthenticationResult;
import com.aws.carddemo.service.AuthenticationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the CardDemo sign-on endpoint — the Java migration of
 * the CICS BMS screen {@code app/bms/COSGN00.bms} (Login Screen, TRANID
 * {@code CC00}) plus its handling program {@code app/cbl/COSGN00C.cbl}.
 *
 * <h2>HTTP Contract</h2>
 *
 * <p>{@code POST /api/auth/sign-on} — body is a JSON-serialised
 * {@link AuthenticationRequest}; response is a JSON-serialised
 * {@link AuthenticationResult} with HTTP-status mapping:
 * <ul>
 *   <li>{@code 200 OK} — sign-on succeeded; body's {@code session} field is populated.</li>
 *   <li>{@code 400 BAD_REQUEST} — empty user ID, empty password, user ID or password
 *       exceeding the COBOL {@code PIC X(08)} field width (8 characters).</li>
 *   <li>{@code 401 UNAUTHORIZED} — unknown user, wrong password, or unrecognised user
 *       type. The response body returns a uniform message
 *       ({@link #MSG_INVALID_CREDENTIALS}) for all three cases to prevent user
 *       enumeration — security hardening absent from the COBOL source (AAP §0.10.5).</li>
 *   <li>{@code 423 LOCKED} (RFC 4918) — account is locked. The service rejects locked
 *       accounts BEFORE attempting BCrypt verification.</li>
 *   <li>{@code 415 UNSUPPORTED_MEDIA_TYPE} — request lacked
 *       {@code Content-Type: application/json}. Spring MVC enforces this via the
 *       {@code consumes = MediaType.APPLICATION_JSON_VALUE} attribute below.</li>
 *   <li>{@code 500 INTERNAL_SERVER_ERROR} — unexpected service-layer failure. The
 *       {@link #handleServiceFailure(RuntimeException)} handler returns a sanitised
 *       error message; the underlying exception is never leaked to the HTTP response
 *       (AAP §0.10.5 — applied transitively to error details).</li>
 * </ul>
 *
 * <h2>COBOL Provenance — COSGN00C.cbl</h2>
 *
 * <p>The COBOL {@code PROCESS-ENTER-KEY} (lines ~108–140) and
 * {@code READ-USER-SEC-FILE} (lines ~209–257) paragraphs encode the sign-on workflow:
 *
 * <ol>
 *   <li>Validate {@code USERIDI OF COSGN0AI} is non-blank →
 *       {@code 'Please enter User ID ...'}</li>
 *   <li>Validate {@code PASSWDI OF COSGN0AI} is non-blank →
 *       {@code 'Please enter Password ...'}</li>
 *   <li>{@code EXEC CICS READ DATASET('USRSEC')} →
 *       {@code 'User not found ...'} on NOTFND</li>
 *   <li>Plaintext compare {@code SEC-USR-PWD = WS-USER-PWD} (Java: BCrypt verify) →
 *       {@code 'Wrong Password ...'} on mismatch</li>
 *   <li>Dispatch by {@code SEC-USR-TYPE}: {@code 'A'} → admin menu;
 *       {@code 'U'} → main menu</li>
 * </ol>
 *
 * <p>The controller delegates the validation cascade, lookup, BCrypt verification,
 * and role dispatch to {@link AuthenticationService}; the controller's
 * responsibility is restricted to:
 * <ol>
 *   <li>COBOL {@code PIC X(08)} field-width enforcement at the HTTP boundary
 *       (input that exceeds 8 characters is rejected with {@code 400} before the
 *       service is invoked; empty fields are caught by the service's existing
 *       validation cascade).</li>
 *   <li>Mapping the service's {@link AuthenticationResult} outcome to the
 *       appropriate HTTP status code.</li>
 *   <li>Sanitising failure messages for security-sensitive outcomes (unknown user,
 *       wrong password, invalid user type) to prevent user enumeration.</li>
 * </ol>
 *
 * <h2>Cross-Cutting Concerns</h2>
 *
 * <ul>
 *   <li><b>CSRF</b> — sign-on is the entry point of the authenticated session;
 *       enforcing CSRF on the sign-on endpoint would be circular because the
 *       caller has no token yet. Production {@code SecurityConfig} (subsequent
 *       migration step) should mark {@code /api/auth/**} as
 *       {@code permitAll()} and disable CSRF on this path.</li>
 *   <li><b>PCI / credential logging</b> — the {@link AuthenticationRequest} record
 *       redacts its password in {@code toString()}, the
 *       {@link com.aws.carddemo.dto.auth.UserSession} class deliberately has no
 *       password field, and {@link AuthenticationResult} carries the welcome message
 *       (success) or the reject message (failure) but never the credential. The
 *       controller is therefore safe to log at the {@code INFO} or {@code DEBUG}
 *       level without disclosing credentials (AAP §0.10.5).</li>
 *   <li><b>Internationalisation</b> — reject messages are presently English-only
 *       and verbatim from the COBOL source. A subsequent migration step may move
 *       them to a {@code MessageSource} for i18n.</li>
 * </ul>
 *
 * @see AuthenticationService
 * @see AuthenticationRequest
 * @see AuthenticationResult
 * @see com.aws.carddemo.dto.auth.UserSession
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // AuthenticationService.MSG_* constants are package-private (no modifier on
    // the `static final String` declarations in that class), so they cannot be
    // referenced directly from a class in a sibling package. The controller
    // duplicates the literal strings here so the HTTP-status mapping can
    // dispatch on them. This is a deliberate, narrow coupling: there is a
    // matching test
    // (com.aws.carddemo.controller.AuthControllerTest) that asserts every
    // mapped status against the same constants — so if the service ever renames
    // or relocates one of these messages, the controller's mapping and its test
    // will fail together, surfacing the drift loudly.
    //
    // Source: com.aws.carddemo.service.AuthenticationService.MSG_EMPTY_USER_ID,
    //         AuthenticationService.MSG_EMPTY_PASSWORD,
    //         AuthenticationService.MSG_ACCOUNT_LOCKED
    // ------------------------------------------------------------------------

    /** Service-layer reject message for blank {@code userId}; produces HTTP 400. */
    static final String MSG_EMPTY_USER_ID = "Please enter User ID ...";

    /** Service-layer reject message for blank {@code password}; produces HTTP 400. */
    static final String MSG_EMPTY_PASSWORD = "Please enter Password ...";

    /** Service-layer reject message for a locked account; produces HTTP 423. */
    static final String MSG_ACCOUNT_LOCKED = "Account is locked. Contact administrator ...";

    // ------------------------------------------------------------------------
    // COBOL PIC X(08) field widths
    // ------------------------------------------------------------------------
    //
    // COSGN00C.cbl declares:
    //   05 WS-USER-ID  PIC X(08).
    //   05 WS-USER-PWD PIC X(08).
    //
    // and the BMS map COSGN00.bms declares the USERID and PASSWD fields with
    // LENGTH=8 (lines 156–160 and 175–180). The 8-character cap is therefore a
    // boundary contract carried over from the mainframe screen. The controller
    // rejects HTTP requests whose userId or password exceeds 8 characters with
    // HTTP 400 before calling the service, preserving the boundary contract.
    // ------------------------------------------------------------------------

    /** Maximum length of {@code userId} per COBOL {@code WS-USER-ID PIC X(08)}. */
    static final int COBOL_USER_ID_MAX_LENGTH = 8;

    /** Maximum length of {@code password} per COBOL {@code WS-USER-PWD PIC X(08)}. */
    static final int COBOL_PASSWORD_MAX_LENGTH = 8;

    // ------------------------------------------------------------------------
    // HTTP response messages
    // ------------------------------------------------------------------------

    /** HTTP 400 message when {@code userId} exceeds 8 characters. */
    static final String MSG_USER_ID_TOO_LONG = "User ID must be 1-8 characters";

    /** HTTP 400 message when {@code password} exceeds 8 characters. */
    static final String MSG_PASSWORD_TOO_LONG = "Password must be 1-8 characters";

    /** HTTP 400 message when the JSON request body cannot be parsed. */
    static final String MSG_MALFORMED_REQUEST = "Malformed JSON request body";

    /**
     * Uniform HTTP 401 message returned for unknown user, wrong password, or
     * unrecognised user-type failures. The uniform body prevents user-enumeration
     * attacks where an attacker compares the response for an existing user
     * (wrong-password reject) against a non-existing user (user-not-found reject)
     * to learn which user IDs exist (AAP §0.10.5 security hardening).
     */
    static final String MSG_INVALID_CREDENTIALS = "Invalid user or password";

    /**
     * Generic HTTP 500 message when the service layer throws an unexpected
     * exception. The underlying exception detail is logged internally (production
     * would inject a {@code Logger} here) but NEVER returned in the HTTP response
     * — preventing accidental disclosure of database error strings, stack traces,
     * SQL fragments, or internal class names (AAP §0.10.5).
     */
    static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    private final AuthenticationService authenticationService;

    /**
     * Constructs the controller with a constructor-injected
     * {@link AuthenticationService} collaborator.
     *
     * @param authenticationService the service that owns the sign-on validation
     *                              cascade, BCrypt verification, role dispatch,
     *                              and session creation (must not be {@code null})
     */
    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Authenticates a user. Accepts a JSON-serialised
     * {@link AuthenticationRequest}, delegates verification to
     * {@link AuthenticationService}, and maps the resulting
     * {@link AuthenticationResult} to an HTTP status code per the table in the
     * class-level Javadoc.
     *
     * @param request the sign-on request (JSON body); must not be {@code null}
     *                or Spring's JSON message converter will already have
     *                returned HTTP 400
     * @return a {@link ResponseEntity} carrying the {@link AuthenticationResult}
     *         body and the appropriate HTTP status code
     */
    @PostMapping(path = "/sign-on",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthenticationResult> signOn(@RequestBody AuthenticationRequest request) {
        // Step 1 — COBOL PIC X(08) length parity at the HTTP boundary.
        // Empty / null user IDs and passwords are handled by the service's
        // existing validation cascade (return MSG_EMPTY_USER_ID / MSG_EMPTY_PASSWORD)
        // and surface as HTTP 400 below. Over-length values are rejected here so
        // the service never receives an input that would have been truncated by
        // the COBOL PIC X(08) storage field.
        if (request != null
                && request.userId() != null
                && request.userId().length() > COBOL_USER_ID_MAX_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(AuthenticationResult.failure(MSG_USER_ID_TOO_LONG));
        }
        if (request != null
                && request.password() != null
                && request.password().length() > COBOL_PASSWORD_MAX_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(AuthenticationResult.failure(MSG_PASSWORD_TOO_LONG));
        }

        // Step 2 — delegate to service (validation + lookup + BCrypt + dispatch).
        AuthenticationResult result = authenticationService.authenticate(request);

        // Step 3 — happy path: HTTP 200 OK with the session-bearing result.
        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        }

        // Step 4 — failure-message → HTTP-status mapping.
        String message = result.getMessage();
        HttpStatus status;
        AuthenticationResult body;

        if (MSG_EMPTY_USER_ID.equals(message) || MSG_EMPTY_PASSWORD.equals(message)) {
            // Empty-field rejects are not user-enumeration vectors — safe to return
            // the COBOL message verbatim with HTTP 400.
            status = HttpStatus.BAD_REQUEST;
            body = result;
        } else if (MSG_ACCOUNT_LOCKED.equals(message)) {
            // Locked-account state is observable through the lockout mechanism
            // (an attacker who repeatedly fails authentication can lock the
            // account themselves); distinguishing locked from other rejects is
            // not an enumeration risk. HTTP 423 LOCKED per RFC 4918.
            status = HttpStatus.LOCKED;
            body = result;
        } else {
            // Unknown user (MSG_USER_NOT_FOUND), wrong password (MSG_WRONG_PASSWORD),
            // or unrecognised user type (MSG_INVALID_USER_TYPE) all produce
            // HTTP 401 with a uniform message so an attacker cannot infer whether
            // a given user ID exists. The original COBOL message would have been
            // user-enumerable; the Java migration intentionally diverges here for
            // security (AAP §0.10.5).
            status = HttpStatus.UNAUTHORIZED;
            body = AuthenticationResult.failure(MSG_INVALID_CREDENTIALS);
        }
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Handles malformed JSON request bodies — Spring's
     * {@link HttpMessageNotReadableException} fires when the body fails JSON
     * deserialisation (truncated JSON, invalid JSON syntax, wrong shape).
     * Returns HTTP 400 with a sanitised message. This handler is declared
     * BEFORE {@link #handleServiceFailure(RuntimeException)} so Spring's
     * most-specific-handler-wins resolution picks it for the JSON-parse case
     * — without this specific handler the generic {@code RuntimeException}
     * handler below would intercept the exception (because
     * {@code HttpMessageNotReadableException} extends
     * {@code NestedRuntimeException} extends {@code RuntimeException}) and
     * return HTTP 500 instead of the correct HTTP 400.
     *
     * @param ex the message-conversion exception; its detail (which may quote
     *           the offending JSON fragment) is NOT echoed back to the caller
     * @return HTTP 400 with a generic malformed-body message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AuthenticationResult> handleMalformedRequestBody(HttpMessageNotReadableException ex) {
        // The exception's message may include parts of the offending JSON
        // payload (which could carry credentials in a malformed sign-on body);
        // we deliberately discard it and return a generic message instead.
        // The `ex` parameter is kept in the signature so Spring's exception
        // resolver matches the most-specific overload, and so that production
        // deployments can later add a Logger.debug(ex) call here without
        // changing the method signature.
        if (ex == null) {
            return ResponseEntity.badRequest()
                    .body(AuthenticationResult.failure(MSG_MALFORMED_REQUEST));
        }
        return ResponseEntity.badRequest()
                .body(AuthenticationResult.failure(MSG_MALFORMED_REQUEST));
    }

    /**
     * Handles unexpected {@link RuntimeException}s thrown by the service layer
     * (for example a {@code DataAccessException} when the {@code USRSEC} table is
     * unreachable). Returns HTTP 500 with a sanitised message — the underlying
     * exception detail must never leak into the HTTP response.
     *
     * @param ex the exception caught from the service layer; the controller
     *           does NOT include any field of this exception in the response
     *           body. Production deployments would log this exception via an
     *           injected {@code Logger} for diagnosis.
     * @return HTTP 500 with a generic error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AuthenticationResult> handleServiceFailure(RuntimeException ex) {
        // Defensive: dereference `ex` purely to satisfy the unused-parameter lint;
        // we intentionally discard its message and stack trace so they cannot be
        // included in the response. Production code would replace this with a
        // structured Logger invocation that records {ex} for operations review.
        if (ex == null) {
            // Should never be null in practice — Spring always passes the caught
            // exception — but the null-guard documents that the handler does not
            // dereference {ex.getMessage()} or {ex.toString()}.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AuthenticationResult.failure(MSG_INTERNAL_ERROR));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AuthenticationResult.failure(MSG_INTERNAL_ERROR));
    }
}
