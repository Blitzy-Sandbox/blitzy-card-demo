/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.controller;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.service.auth.AuthenticationService;

/**
 * Spring MVC REST controller providing authentication (sign-on) operations,
 * replacing CICS BMS screen COSGN00 from the CardDemo mainframe application.
 *
 * <p>This is the application entry point — all user sessions begin here.
 * The controller delegates to {@link AuthenticationService} which performs
 * USRSEC lookup, BCrypt password verification, and token generation.
 * This controller is the ONE exception to the {@code @ControllerAdvice}
 * pattern: authentication-specific exception handling is implemented directly
 * in the controller to map both user-not-found and wrong-password to HTTP 401
 * Unauthorized, preventing user enumeration attacks.</p>
 *
 * <h3>COBOL Program Coverage</h3>
 * <table>
 *   <caption>BMS Screen to REST endpoint mapping</caption>
 *   <tr><th>BMS Screen</th><th>COBOL Program</th><th>REST Endpoint</th><th>HTTP Method</th></tr>
 *   <tr><td>COSGN00 (Sign-On)</td><td>COSGN00C.cbl (260 lines)</td>
 *       <td>/api/auth/signin</td><td>POST</td></tr>
 * </table>
 *
 * <h3>COBOL Behavioral Parity — Sign-On Flow (COSGN00C.cbl)</h3>
 * <ol>
 *   <li>BMS screen displays USERID (PIC X(8)) and PASSWORD (PIC X(8), dark) fields</li>
 *   <li>User enters credentials and presses ENTER</li>
 *   <li>COBOL reads USRSEC file with SEC-USR-ID as key</li>
 *   <li>Compares SEC-USR-PWD with entered password (plaintext in COBOL; BCrypt in Java)</li>
 *   <li>If match AND type is 'A': XCTL to COADM01C (admin menu) — Java: toProgram=COADM01C, toTranId=CA00</li>
 *   <li>If match AND type is 'U': XCTL to COMEN01C (main menu) — Java: toProgram=COMEN01C, toTranId=CM01</li>
 *   <li>If no match: display "Sign-on is unsuccessful..." — Java: HTTP 401 Unauthorized</li>
 * </ol>
 *
 * <h3>HTTP Status Codes</h3>
 * <ul>
 *   <li><strong>200 OK</strong> — Successful authentication (SignOnResponse body with token, routing metadata)</li>
 *   <li><strong>400 Bad Request</strong> — Validation failures (blank userId/password via {@code @Valid})</li>
 *   <li><strong>401 Unauthorized</strong> — ALL authentication failures (user not found OR wrong password)</li>
 * </ul>
 *
 * <h3>Security Design Decisions</h3>
 * <ul>
 *   <li>Same generic error message for both "user not found" and "wrong password" —
 *       prevents user enumeration attacks. Matches COBOL behavior where the same error
 *       message "Sign-on is unsuccessful..." is shown for all auth failures.</li>
 *   <li>Passwords are NEVER logged — not plaintext, not masked, not hashed.</li>
 *   <li>This endpoint is permitted without authentication in SecurityConfig
 *       (it IS the login endpoint).</li>
 *   <li>RecordNotFoundException and IllegalArgumentException from the service layer
 *       are both caught and mapped to HTTP 401, NOT their default ControllerAdvice
 *       mappings (404/400), to prevent information leakage.</li>
 * </ul>
 *
 * <p>Source traceability: COSGN00C.cbl — CardDemo v1.0-15-g27d6c6f-68</p>
 *
 * @see AuthenticationService
 * @see SignOnRequest
 * @see SignOnResponse
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs authentication attempts at INFO level, successful sign-ins at INFO level
     * with userId and userType, and failed attempts at WARN level with userId only.
     * CRITICAL: Passwords are NEVER logged — not plaintext, not masked, not hashed.
     * Supports AAP §0.7.1 observability requirements for structured logging with
     * correlation IDs propagated via MDC.
     */
    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    /**
     * Authentication service implementing USRSEC lookup and BCrypt verification.
     * Migrated from COBOL program COSGN00C.cbl (260 lines, CICS transaction CC00).
     * Performs: uppercase normalization → USRSEC repository lookup → BCrypt password
     * verification → routing metadata construction (ADMIN→COADM01C, USER→COMEN01C).
     */
    private final AuthenticationService authenticationService;

    /**
     * Constructs an {@code AuthController} with the required authentication service.
     * Spring auto-wires via single-constructor injection — no {@code @Autowired} needed.
     *
     * @param authenticationService the service handling authentication logic
     *                              migrated from COSGN00C.cbl
     */
    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Authenticates a user via userId and password, returning a sign-on response
     * with a token and routing metadata on success.
     *
     * <p>Maps COSGN00C.cbl PROCESS-ENTER-KEY paragraph — USRSEC read + password
     * verification + routing determination. The service uppercases both userId and
     * password before processing, preserving COBOL {@code FUNCTION UPPER-CASE}
     * behavioral semantics.</p>
     *
     * <h4>Authentication Flow</h4>
     * <ol>
     *   <li>Log authentication attempt (userId only, NEVER password)</li>
     *   <li>Delegate to {@link AuthenticationService#authenticate(SignOnRequest)}</li>
     *   <li>On success: log userId + userType, return HTTP 200 with SignOnResponse</li>
     *   <li>On failure: log warning with userId, return HTTP 401 Unauthorized</li>
     * </ol>
     *
     * <h4>Security: Auth-Specific Exception Handling</h4>
     * <p>This is the ONE controller where exception handling is implemented at the
     * controller level instead of delegating to {@code @ControllerAdvice}. Both
     * {@link RecordNotFoundException} (user not found) and
     * {@link IllegalArgumentException} (wrong password) are caught and mapped to
     * HTTP 401 Unauthorized with a generic error message. This prevents user
     * enumeration by ensuring the same response for both error conditions —
     * matching the COBOL behavior of showing "Sign-on is unsuccessful..." for
     * all authentication failures.</p>
     *
     * @param request the sign-on request containing userId and password,
     *                validated via {@code @Valid} for @NotBlank and @Size(max=8)
     * @return HTTP 200 with {@link SignOnResponse} on success,
     *         or HTTP 401 Unauthorized on authentication failure
     */
    @PostMapping("/signin")
    public ResponseEntity<SignOnResponse> signIn(@Valid @RequestBody SignOnRequest request) {
        logger.info("Sign-in attempt for user {}", request.getUserId());

        try {
            SignOnResponse response = authenticationService.authenticate(request);

            logger.info("User {} authenticated successfully as {}",
                    response.getUserId(), response.getUserType());

            return ResponseEntity.ok(response);
        } catch (RecordNotFoundException ex) {
            // User not found — map to 401 (NOT 404) to prevent user enumeration.
            // Matches COBOL COSGN00C.cbl RESP code 13 (NOTFND) behavior where
            // the same generic message "Sign-on is unsuccessful..." is displayed.
            logger.warn("Authentication failed for user {}", request.getUserId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
        } catch (IllegalArgumentException ex) {
            // Invalid password or blank input — map to 401 (NOT 400) to prevent
            // distinguishing between "user exists but wrong password" and "user
            // not found". Matches COBOL COSGN00C.cbl behavior where SEC-USR-PWD
            // mismatch shows the same "Sign-on is unsuccessful..." message.
            logger.warn("Authentication failed for user {}", request.getUserId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
        }
    }
}
