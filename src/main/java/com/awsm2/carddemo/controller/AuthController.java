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
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.SignonRequestDto;
import com.awsm2.carddemo.dto.SignonResponseDto;
import com.awsm2.carddemo.service.SignonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication REST controller &mdash; issues JWTs to authenticated
 * operators.
 *
 * <p><b>COBOL provenance (AAP &sect;0.4.1):</b> Replaces
 * {@code app/cbl/COSGN00C.cbl} (CICS transaction id {@code CC00}) which
 * read the {@code USRSEC} VSAM KSDS cluster keyed on
 * {@code SEC-USR-ID PIC X(8)} (from
 * {@code app/cpy/CSUSR01Y.cpy:CARD-USER-RECORD}), compared the
 * uppercased plaintext password against {@code SEC-USR-PWD PIC X(8)},
 * and dispatched the operator to {@code COMEN01C} (regular user) or
 * {@code COADM01C} (admin) per the {@code SEC-USR-TYPE PIC X(1)}
 * discriminator. The BMS source is {@code app/bms/COSGN00.bms}
 * (symbolic map {@code app/cpy-bms/COSGN00.CPY}).</p>
 *
 * <p><b>Endpoint inventory (AAP &sect;0.3.4):</b></p>
 * <ul>
 *   <li>{@code POST /api/auth/signin} &mdash; authenticates an operator
 *       by user ID and password; on success returns a signed JWT bearer
 *       token plus the operator's resolved role (USER / ADMIN) and
 *       initial routing target (main menu vs admin menu) so the
 *       client knows where to navigate next. This is the ONLY
 *       unauthenticated endpoint in the entire CardDemo REST surface;
 *       every other endpoint requires the JWT returned here.</li>
 * </ul>
 *
 * <p><b>Security model (AAP &sect;0.3.4, &sect;0.6.6, &sect;0.7.1):</b></p>
 * <ul>
 *   <li>Endpoint is <b>unauthenticated</b> &mdash; declared in
 *       {@code SecurityConfig#securityFilterChain} via the URL matcher
 *       {@code .requestMatchers(HttpMethod.POST, "/api/auth/signin").permitAll()}.
 *       Class-level {@link SecurityRequirements @SecurityRequirements()}
 *       overrides the global OpenAPI {@code BearerAuth} requirement so
 *       that springdoc-openapi does not render a lock icon for this
 *       endpoint.</li>
 *   <li>Credentials are NEVER logged at this layer &mdash; the
 *       {@code SignonRequestDto#toString()} method redacts the password
 *       field for defensive logging. The
 *       {@link SignonService#signon(SignonRequestDto)} collaborator
 *       performs BCrypt comparison against the
 *       {@code SEC-USR-PWD} hash sourced from the {@code UserSecurity}
 *       JPA entity (RDS PostgreSQL).</li>
 *   <li>On invalid credentials the service throws
 *       {@link org.springframework.security.authentication.BadCredentialsException};
 *       the global {@code GlobalExceptionHandler} translates this to
 *       HTTP 401 with the generic message {@code "Invalid User ID or
 *       Password"} (per PCI-DSS the response never reveals whether the
 *       user ID exists or only the password is wrong).</li>
 * </ul>
 *
 * <p><b>Layered architecture compliance (AAP &sect;0.3.3, &sect;0.7.1):</b>
 * this controller is a thin Spring MVC fa&ccedil;ade. It holds no business
 * state, performs no I/O, never accesses a repository or AWS adapter
 * directly, and delegates all sign-on validation and JWT issuance to
 * {@link SignonService} via constructor injection.</p>
 *
 * <p><b>Standardized response envelope (AAP &sect;0.3.4):</b> success
 * responses wrap {@link SignonResponseDto} in
 * {@link ApiResponse#success(Object)}. Authentication and validation
 * failures surface as typed exceptions handled by
 * {@code GlobalExceptionHandler} which emits the same envelope shape
 * with appropriate {@code code} and HTTP status.</p>
 *
 * @see SignonService
 * @see SignonRequestDto
 * @see SignonResponseDto
 * @see com.awsm2.carddemo.dto.ApiResponse
 * @see com.awsm2.carddemo.config.SecurityConfig
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication",
        description = "Sign-on / JWT issuance. Replaces CICS COSGN00C "
                + "(Tran-ID CC00, sign-on screen).")
public class AuthController {

    /**
     * SLF4J facade for structured JSON logging. Backed by Logback +
     * logstash-logback-encoder per AAP &sect;0.7.2. PCI-DSS discipline
     * (AAP &sect;0.6.6): the controller never logs the password, the
     * JWT, or any session-bearing token. Only the user ID is emitted
     * because the user ID itself is a non-sensitive operator
     * identifier (the same value an admin would type into the user
     * administration screens).
     */
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    /**
     * Sign-on service collaborator &mdash; encapsulates the BCrypt
     * comparison against the {@code UserSecurity} JPA entity sourced from
     * RDS PostgreSQL (replaces COBOL VSAM read of {@code USRSEC} keyed on
     * {@code SEC-USR-ID}) and the JWT issuance via
     * {@code JwtTokenProvider}. The reference is {@code final}
     * (immutable post-construction) per AAP &sect;0.3.3 / &sect;0.7.1
     * constructor-injection / loose-coupling discipline.
     */
    private final SignonService signonService;

    /**
     * Constructor used by Spring's dependency injection container.
     *
     * @param signonService the {@link SignonService} collaborator; never
     *                      {@code null} (Spring fails fast at startup if
     *                      the bean cannot be found)
     */
    public AuthController(SignonService signonService) {
        this.signonService = signonService;
    }

    /**
     * Authenticates an operator and returns a signed JWT bearer token.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COSGN00C.cbl:PROCESS-ENTER-KEY} (the sign-on
     * validation paragraph). The original flow is:</p>
     * <ol>
     *   <li>Read user ID from BMS {@code USERIDI} field (max 8 chars).</li>
     *   <li>Read password from BMS {@code PASSWDI} field (max 8 chars).</li>
     *   <li>{@code MOVE FUNCTION UPPER-CASE} on both fields.</li>
     *   <li>{@code READ USRSEC RIDFLD(WS-USER-ID)}.</li>
     *   <li>On {@code DFHRESP(NOTFND)}: display "User not found" and
     *       return to sign-on screen.</li>
     *   <li>On {@code DFHRESP(NORMAL)}: compare
     *       {@code SEC-USR-PWD} to entered password. Mismatch &rarr;
     *       display "Wrong Password... Try again".</li>
     *   <li>On match: route by {@code SEC-USR-TYPE} &mdash; {@code 'A'}
     *       to {@code COADM01C}, anything else to {@code COMEN01C} (via
     *       {@code XCTL PROGRAM}).</li>
     * </ol>
     *
     * <p>The Java target replaces these steps with:</p>
     * <ol>
     *   <li>Jackson binds the JSON {@code {userId, password}} into
     *       {@link SignonRequestDto}.</li>
     *   <li>Jakarta Bean Validation enforces non-blank, length, and
     *       pattern constraints (failures surface as
     *       {@link org.springframework.web.bind.MethodArgumentNotValidException}
     *       and are translated to HTTP 400 by
     *       {@code GlobalExceptionHandler}).</li>
     *   <li>{@link SignonService#signon(SignonRequestDto)} loads the
     *       {@code UserSecurity} JPA entity, performs BCrypt comparison
     *       against the stored hash, and emits the JWT.</li>
     *   <li>{@link SignonResponseDto} carries the JWT, the operator's
     *       resolved role, and the initial routing target
     *       ({@code "/api/menu/main"} or {@code "/api/menu/admin"}).</li>
     * </ol>
     *
     * <p><b>HTTP 200 OK</b> is the success status &mdash; sign-on is an
     * <i>action</i> (not a resource creation), so 200 is preferred over
     * 201 Created per common REST conventions.</p>
     *
     * <p><b>Error paths:</b></p>
     * <ul>
     *   <li>HTTP 400 &mdash; validation failure (blank or malformed
     *       fields).</li>
     *   <li>HTTP 401 &mdash; invalid credentials. The response body
     *       contains only the generic message {@code "Invalid User ID or
     *       Password"} per PCI-DSS guidance (AAP &sect;0.7.2).</li>
     * </ul>
     *
     * @param request the validated {@link SignonRequestDto} containing
     *                {@code userId} (8 characters) and {@code password}
     *                (8 characters). The {@code @Valid} annotation
     *                triggers Jakarta Bean Validation on the record's
     *                components
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link SignonResponseDto} payload (JWT + role + initial
     *         routing target) wrapped in {@link ApiResponse}
     */
    @PostMapping("/signin")
    @Operation(
            summary = "Authenticate operator and issue a JWT",
            description = "Validates user ID and password against the "
                    + "UserSecurity entity (RDS PostgreSQL, mapping from VSAM "
                    + "USRSEC KSDS), and on success returns a signed JWT bearer "
                    + "token plus the resolved role and initial routing target. "
                    + "Replaces CICS COSGN00C / Tran-ID CC00 (sign-on screen)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Sign-on successful; JWT issued"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (blank or malformed user ID / password)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Invalid User ID or Password (generic message per PCI-DSS)")
    })
    @SecurityRequirements() // ← Override the global BearerAuth — this endpoint is unauthenticated.
    public ResponseEntity<ApiResponse<SignonResponseDto>> signin(
            @Valid @RequestBody SignonRequestDto request) {
        // COBOL: COSGN00C / Tran-ID CC00:PROCESS-ENTER-KEY -- sign-on
        //   validation and routing by SEC-USR-TYPE (delegates to
        //   SignonService which performs BCrypt comparison against the
        //   UserSecurity JPA entity and issues the JWT).
        // PCI-DSS log discipline (AAP §0.6.6): only the user ID is
        // logged. Password and JWT are NEVER logged. SignonRequestDto's
        // toString() redacts the password, so even accidental
        // interpolation of the request into a log line would not leak
        // the credential.
        LOG.debug("Sign-on requested for userId={}", request.userId());
        SignonResponseDto response = signonService.signon(request);
        return ResponseEntity.ok(ApiResponse.success(response,
                "Sign-on successful"));
    }
}
