/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.controller;

import com.carddemo.dto.AuthDto;
import com.carddemo.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for CardDemo sign-on under {@code /api/auth}.
 *
 * <p>This controller is the stateless HTTP front for the CICS
 * pseudo-conversational sign-on screen {@code COSGN00C} (transaction
 * {@code CC00}, mapset {@code app/bms/COSGN00.bms}) @ {@code 27d6c6f}. The
 * legacy COMMAREA hand-off and {@code XCTL} routing to the administrator or
 * main menu are replaced by a single {@code POST} endpoint that verifies the
 * submitted credentials and returns a JSON Web Token; subsequent menu
 * selection is the client's concern.</p>
 *
 * <p>The controller is a thin presentation layer: it binds and validates the
 * request body, delegates entirely to {@link AuthService}, and returns the
 * service result. It holds no business logic, performs no data access, and
 * handles no errors inline. Blank-field and oversize-field violations raised by
 * bean validation, and the {@code ValidationException} and
 * {@code AuthenticationFailedException} raised by the service, propagate to the
 * application's central {@code GlobalExceptionHandler} for RFC 7807 rendering.</p>
 *
 * <p>The route is public: the stateless filter chain in {@code SecurityConfig}
 * permits {@code /api/auth/**} without authentication, so no authorization
 * annotation is declared here.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * Creates the controller with its single collaborator.
     *
     * @param authService the service backing sign-on credential verification and
     *                    JWT issuance; never {@code null}
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Verifies sign-on credentials and issues a JWT.
     *
     * <p>Realizes the {@code PROCESS-ENTER-KEY} flow of {@code COSGN00C} for
     * transaction {@code CC00}: the validated user id and password are passed to
     * {@link AuthService#signin(AuthDto.SigninRequest)}, which returns the issued
     * token together with the authenticated user id and user-type code.</p>
     *
     * @param request the validated sign-on request carrying the user id and
     *                password
     * @return {@code 200 OK} wrapping the issued token and authenticated identity
     */
    @PostMapping("/signin")
    public ResponseEntity<AuthDto.SigninResponse> signin(@Valid @RequestBody AuthDto.SigninRequest request) {
        return ResponseEntity.ok(authService.signin(request));
    }
}
