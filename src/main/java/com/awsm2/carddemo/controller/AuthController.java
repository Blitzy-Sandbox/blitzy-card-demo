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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * Authentication REST controller &mdash; exposes the sign-on endpoint.
 *
 * <p>Replaces the CICS pseudo-conversational signon transaction implemented
 * by the COBOL program {@code COSGN00C} (Tran-ID {@code CC00}) at
 * {@code app/cbl/COSGN00C.cbl}, paired with the BMS mapset
 * {@code app/bms/COSGN00.bms} and the symbolic-map copybook
 * {@code app/cpy-bms/COSGN00.CPY}. The USRSEC record layout is
 * {@code app/cpy/CSUSR01Y.cpy} (80-byte {@code SEC-USER-DATA}) and the
 * CICS COMMAREA carrying {@code CDEMO-USER-ID} / {@code CDEMO-USER-TYPE} is
 * {@code app/cpy/COCOM01Y.cpy} (now replaced by the JWT claim set returned
 * inside {@link SignonResponseDto}).</p>
 *
 * <h2>Endpoint inventory (AAP &sect;0.3.4)</h2>
 * <ul>
 *   <li>{@code POST /api/auth/signin} &mdash; authenticate User ID and
 *       password and issue a signed JWT bearer token plus the user-identity
 *       attributes ({@code userId}, {@code firstName}, {@code lastName},
 *       {@code userType}, {@code expiresAt}) needed for client-side menu
 *       routing.</li>
 * </ul>
 *
 * <h2>Public endpoint declaration (AAP &sect;0.3.4, &sect;0.6.6, &sect;0.7.1)</h2>
 * <p>This controller exposes the <i>only</i> PUBLIC endpoint in the
 * CardDemo REST API surface. Two coordinating configurations make
 * {@code /api/auth/signin} reachable without an
 * {@code Authorization: Bearer} header:</p>
 * <ul>
 *   <li><b>Spring Security</b> &mdash; the
 *       {@code SecurityConfig#securityFilterChain} bean declares
 *       {@code .requestMatchers(HttpMethod.POST, "/api/auth/signin").permitAll()}
 *       so the filter chain bypasses authentication on this path.</li>
 *   <li><b>OpenAPI / springdoc</b> &mdash; the class-level
 *       {@link SecurityRequirements @SecurityRequirements({})} annotation
 *       on this controller opts out of the global {@code BearerAuth}
 *       requirement declared by {@code OpenApiConfig#cardDemoOpenAPI()}.
 *       Without this opt-out, Swagger UI would render a padlock icon
 *       beside {@code /api/auth/signin} and require an
 *       {@code Authorization} header before the "Try it out" button
 *       would issue the request &mdash; an incorrect contract for the
 *       endpoint that <i>produces</i> the JWT in the first place.</li>
 * </ul>
 *
 * <h2>COBOL provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <p>Behaviours preserved (from {@code app/cbl/COSGN00C.cbl} lines
 * 73&ndash;257):</p>
 * <ul>
 *   <li><b>Initial entry (no COMMAREA, {@code EIBCALEN = 0}):</b> in the
 *       3270 world this rendered an empty sign-on screen. In the REST
 *       world this is the GET landing for the client UI and is handled
 *       by the client (browser, mobile app) &mdash; there is no
 *       server-side endpoint for "render the empty signon form".</li>
 *   <li><b>{@code PROCESS-ENTER-KEY} paragraph (lines 108&ndash;140):</b>
 *       the non-blank User ID and Password guards (lines 118-130) plus
 *       the {@code MOVE FUNCTION UPPER-CASE} of both fields (lines
 *       132-136). Replicated in
 *       {@link SignonService#signon(SignonRequestDto)} and in the
 *       Jakarta Bean Validation constraints on {@link SignonRequestDto}.
 *       This controller's only role is to forward the validated
 *       payload.</li>
 *   <li><b>{@code READ-USER-SEC-FILE} paragraph (lines 207&ndash;257):</b>
 *       VSAM keyed read of {@code USRSEC} by user id, plaintext
 *       password compare, dispatch to {@code COADM01C} (admin) or
 *       {@code COMEN01C} (user). Replicated in
 *       {@link SignonService#signon(SignonRequestDto)} which performs a
 *       BCrypt-hash compare against the {@code UserSecurity} JPA entity
 *       and issues a JWT carrying the {@code userType} claim. Client-
 *       side code reads {@link SignonResponseDto#userType()} and routes
 *       to the appropriate menu URL (replaces the COBOL
 *       {@code EXEC CICS XCTL} dispatch).</li>
 *   <li><b>{@code DFHPF3} (exit) and {@code OTHER} (invalid key)
 *       handlers (lines 88&ndash;94):</b> N/A in REST &mdash; clients
 *       implement logout by discarding the JWT and abandoning the
 *       session; no server endpoint is required.</li>
 *   <li><b>{@code WS-USER-ID PIC X(08)} and {@code WS-USER-PWD PIC X(08)}
 *       (lines 45-46):</b> the 8-character contracts on User ID and
 *       Password are enforced by Jakarta Bean Validation
 *       ({@code @Size(min = 1, max = 8)}) on
 *       {@link SignonRequestDto}.</li>
 * </ul>
 *
 * <h2>Layered architecture compliance (AAP &sect;0.3.3, &sect;0.7.1)</h2>
 * <p>This controller is a thin Spring MVC fa&ccedil;ade. It holds no
 * business state, performs no I/O, and never accesses a repository,
 * an AWS adapter, or a JPA entity directly. All sign-on validation,
 * USRSEC lookup, BCrypt password verification, JWT issuance, and audit
 * emission are delegated to {@link SignonService} via constructor
 * injection (the only field is a final {@code SignonService}
 * reference). Domain exceptions raised by the service
 * ({@link com.awsm2.carddemo.exception.RecordNotFoundException},
 * {@link com.awsm2.carddemo.exception.ValidationException},
 * {@link org.springframework.security.authentication.BadCredentialsException},
 * {@link org.springframework.security.core.AuthenticationException})
 * propagate unhandled through this controller and are caught by
 * {@code GlobalExceptionHandler} (a {@code @RestControllerAdvice}) which
 * translates them to the standardized JSON error envelope and the
 * appropriate HTTP status.</p>
 *
 * <h2>PCI-DSS log discipline (AAP &sect;0.6.6)</h2>
 * <p>The controller's logger NEVER emits the raw password
 * ({@link SignonRequestDto#password()}), the BCrypt hash from the
 * {@code UserSecurity} entity, or the issued JWT token. The User ID is
 * logged in <i>masked</i> form via {@link #maskUserId(String)} (which
 * shows the first two characters, then three asterisks, then the last
 * character) so the audit pipeline can correlate signon attempts
 * without leaking the operator identity to downstream log consumers.
 * The {@link SignonRequestDto#toString()} method is itself overridden
 * to redact the password to {@code "********"} as a defense-in-depth
 * measure against accidental DTO interpolation into log lines.</p>
 *
 * <h2>Standardized response envelope (AAP &sect;0.3.4)</h2>
 * <p>The success response wraps {@link SignonResponseDto} in the
 * standardized {@link ApiResponse} envelope via
 * {@link ApiResponse#success(Object, String)}. The envelope shape is
 * {@code {"code":"OK","message":"Sign-on successful","data":{...},"timestamp":"..."}};
 * the {@code fieldErrors} and {@code correlationId} components are
 * {@code null} on this success path and suppressed by Jackson's
 * {@code @JsonInclude(NON_NULL)}. Authentication and validation
 * failures surface the same envelope shape with the appropriate
 * {@code code} and HTTP status (400 for validation, 401 for invalid
 * credentials, 404 for unknown user id when the service raises
 * {@code RecordNotFoundException}).</p>
 *
 * @see SignonService
 * @see SignonRequestDto
 * @see SignonResponseDto
 * @see ApiResponse
 * @see com.awsm2.carddemo.config.SecurityConfig
 * @see com.awsm2.carddemo.config.OpenApiConfig
 */
// Replaces: CICS COSGN00C.cbl (Tran-ID 'CC00') + BMS mapset COSGN00.bms +
//           symbolic map COSGN00.CPY. The COBOL pseudo-conversational
//           sign-on transaction is replaced by this stateless REST
//           controller; the CARDDEMO-COMMAREA (COCOM01Y.cpy) routing
//           state is replaced by the JWT bearer token returned in
//           SignonResponseDto.
@RestController
@RequestMapping("/api/auth")
@Tag(
        name = "Authentication",
        description = "Sign-on and JWT issuance. Replaces CICS COSGN00C / Tran-ID CC00."
)
@SecurityRequirements({}) // Class-level opt-out from global BearerAuth scheme (OpenApiConfig).
public class AuthController {

    /**
     * SLF4J facade for structured JSON logging. Backed by Logback +
     * {@code logstash-logback-encoder} per AAP &sect;0.7.2; log records
     * are shipped to CloudWatch Logs by the ECS Fargate task definition
     * (the {@code awslogs} log driver) and indexed in OpenSearch for
     * fraud investigation per AAP &sect;0.6.6.
     *
     * <p><b>CRITICAL PCI-DSS DISCIPLINE:</b> this logger MUST NEVER emit
     * the raw password {@link SignonRequestDto#password()}, the stored
     * BCrypt hash, the JWT token, or any other credential-equivalent
     * value at any level (INFO, DEBUG, WARN, ERROR). Only the
     * <i>masked</i> user id (see {@link #maskUserId(String)}) and the
     * non-sensitive {@code userType} discriminator may appear in log
     * lines. This is enforced by code review and by the structured-
     * logging redaction filters declared in {@code logback-spring.xml}.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    /**
     * Sentinel for the {@link #maskUserId(String)} helper when the User
     * ID is too short to retain any positional context (length &lt; 3).
     * Returned literally so very short identifiers (e.g., {@code "AB"})
     * are not partially exposed in audit logs.
     */
    private static final String FULLY_MASKED_USER_ID = "***";

    /**
     * Three-asterisk infix used by {@link #maskUserId(String)} to
     * elide the middle portion of a User ID. The first two and last
     * one characters surround this token so log readers retain enough
     * positional context to correlate audit entries without learning
     * the complete identifier.
     */
    private static final String USER_ID_MASK_INFIX = "***";

    /**
     * Minimum {@code userId} length for {@link #maskUserId(String)} to
     * apply the partial-mask format ({@code "XX***Y"}); any shorter
     * value is returned as {@link #FULLY_MASKED_USER_ID} so the masked
     * output cannot accidentally reveal the entire identifier.
     */
    private static final int MIN_USER_ID_LENGTH_FOR_PARTIAL_MASK = 3;

    /**
     * Success message attached to the {@link ApiResponse#success(Object, String)}
     * envelope returned by {@link #signin(SignonRequestDto)}. Hyphenated
     * spelling ("Sign-on") matches the CICS-era spelling used in the
     * COBOL source ({@code COSGN00.bms} title bar) and is consumed
     * verbatim by downstream clients and the controller slice test in
     * {@code src/test/java/com/awsm2/carddemo/controller/AuthControllerTest.java}.
     */
    private static final String SIGNON_SUCCESS_MESSAGE = "Sign-on successful";

    /**
     * Sign-on service collaborator &mdash; encapsulates USRSEC lookup
     * (replaces COBOL VSAM read keyed on {@code SEC-USR-ID} from
     * {@code app/cpy/CSUSR01Y.cpy}), BCrypt password verification
     * (replaces the COBOL plaintext compare
     * {@code IF SEC-USR-PWD = WS-USER-PWD} at line 223 of
     * {@code COSGN00C.cbl}), and JWT issuance carrying the
     * {@code userType} claim (replaces the {@code EXEC CICS XCTL
     * PROGRAM('COADM01C' | 'COMEN01C')} dispatch at lines 231-239).
     *
     * <p>The reference is {@code final} (immutable post-construction)
     * per AAP &sect;0.3.3 / &sect;0.7.1 constructor-injection /
     * loose-coupling discipline.</p>
     */
    private final SignonService signonService;

    /**
     * Constructor used by Spring's dependency-injection container.
     *
     * <p>Constructor injection is mandated by AAP &sect;0.3.3
     * (&quot;Dependency Injection &mdash; constructor injection for all
     * &#64;Service beans&quot;) and AAP &sect;0.7.1 (&quot;Use
     * dependency injection for loose coupling&quot;). The single
     * constructor parameter means Spring 4.3+ does not require an
     * {@code @Autowired} annotation.</p>
     *
     * @param signonService the {@link SignonService} collaborator;
     *                      Spring fails fast at startup if the bean
     *                      cannot be found (no graceful fallback)
     */
    public AuthController(SignonService signonService) {
        // Replaces: implicit CICS dependency injection via PROGRAM-ID +
        //           DFHCOMMAREA in COSGN00C.cbl. The Spring container
        //           wires SignonService at startup; the reference is
        //           held final for the lifetime of the controller bean.
        this.signonService = signonService;
    }

    /**
     * Authenticate an operator and issue a signed JWT bearer token.
     *
     * <p><b>COBOL provenance:</b> Replaces the
     * {@code PROCESS-ENTER-KEY} (lines 108&ndash;140) and
     * {@code READ-USER-SEC-FILE} (lines 207&ndash;257) paragraphs of
     * {@code app/cbl/COSGN00C.cbl}. The original CICS flow was:</p>
     * <ol>
     *   <li>{@code EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00')}
     *       &mdash; read the 3270 buffer into {@code COSGN0AI}.</li>
     *   <li>Validate {@code USERIDI} and {@code PASSWDI} are not spaces
     *       (lines 118-130).</li>
     *   <li>{@code MOVE FUNCTION UPPER-CASE} of both fields (lines
     *       132-136).</li>
     *   <li>{@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)}
     *       (lines 211-219).</li>
     *   <li>On {@code WS-RESP-CD = 13} (NOTFND, line 247): display
     *       "User not found" and re-render the screen.</li>
     *   <li>On {@code WS-RESP-CD = 0}: compare {@code SEC-USR-PWD} to
     *       {@code WS-USER-PWD} byte-for-byte (line 223). Mismatch:
     *       display "Wrong Password. Try again ..." (line 242).</li>
     *   <li>On match: copy {@code WS-USER-ID} and {@code SEC-USR-TYPE}
     *       into the COMMAREA (lines 224-228) and issue
     *       {@code EXEC CICS XCTL PROGRAM('COADM01C')} for
     *       {@code CDEMO-USRTYP-ADMIN} or
     *       {@code EXEC CICS XCTL PROGRAM('COMEN01C')} otherwise
     *       (lines 230-240).</li>
     * </ol>
     *
     * <p>The Java target replaces these steps with:</p>
     * <ol>
     *   <li>Jackson binds the JSON request body to
     *       {@link SignonRequestDto} (an immutable {@code record}).</li>
     *   <li>{@code @Valid} triggers Jakarta Bean Validation against the
     *       {@code @NotBlank}, {@code @Size(min=1,max=8)}, and
     *       {@code @Pattern} constraints declared on the DTO; any
     *       failure raises {@code MethodArgumentNotValidException}
     *       which {@code GlobalExceptionHandler} translates to HTTP
     *       400 Bad Request with the standardized envelope (replaces
     *       the COBOL "Please enter User ID..." / "Please enter
     *       Password..." re-render path).</li>
     *   <li>{@link SignonService#signon(SignonRequestDto)} uppercases
     *       the userId, performs JPA {@code findById} on the
     *       {@code user_security} table (replaces VSAM
     *       {@code READ DATASET('USRSEC')}), verifies the supplied
     *       password against the stored BCrypt hash via
     *       {@code PasswordEncoder.matches} (replaces the COBOL
     *       plaintext compare), and on success issues a HS256-signed
     *       JWT carrying the {@code userType} claim (replaces the
     *       {@code EXEC CICS XCTL} dispatch). Failure surfaces as a
     *       typed exception &mdash;
     *       {@link com.awsm2.carddemo.exception.RecordNotFoundException}
     *       for an unknown user id (HTTP 404) or
     *       {@link com.awsm2.carddemo.exception.ValidationException}
     *       for a bad password (HTTP 400). The Spring Security
     *       {@link org.springframework.security.authentication.BadCredentialsException}
     *       is mapped to HTTP 401 by {@code GlobalExceptionHandler}.</li>
     *   <li>{@link SignonResponseDto} carries the JWT, the operator's
     *       resolved role discriminator
     *       ({@link SignonResponseDto#userType()}), and the JWT
     *       expiry; the client reads {@code userType} and routes to
     *       {@code /api/menu/admin} (for {@code "A"}) or
     *       {@code /api/menu/main} (for {@code "U"}) &mdash; replaces
     *       the COBOL {@code EXEC CICS XCTL} server-side dispatch with
     *       client-side routing keyed on the response payload.</li>
     * </ol>
     *
     * <p><b>HTTP semantics:</b> 200 OK on success (sign-on is an action
     * not a resource creation, so 200 is preferred over 201 per common
     * REST conventions). 400 on validation failure. 401 on bad
     * credentials. 404 on unknown user id (the service deliberately
     * uses a different status than 401 for unknown-user vs
     * bad-password failures internally; the
     * {@code GlobalExceptionHandler} preserves these distinct codes so
     * an observability tooling team can differentiate the two failure
     * modes in CloudWatch metric dimensions while the OpenAPI contract
     * documents both as authentication failures).</p>
     *
     * <p><b>Log emission (PCI-DSS, AAP &sect;0.6.6):</b> exactly two
     * log lines may be produced by this method:</p>
     * <ul>
     *   <li>{@code INFO Signon attempt for userId=...} &mdash; emitted
     *       <i>before</i> the service call so a synchronous service
     *       failure (e.g., DB down) still leaves an audit breadcrumb.
     *       The user id is masked.</li>
     *   <li>{@code INFO Signon successful for userId=... userType=...}
     *       &mdash; emitted only on success. The user id is masked;
     *       the userType ({@code "A"}/{@code "U"}) is non-sensitive
     *       and is logged verbatim.</li>
     * </ul>
     * <p>On any failure path the second log line is suppressed (the
     * exception propagates and {@code GlobalExceptionHandler} logs the
     * failure separately with a correlation id). Neither log line
     * emits the password, the JWT, the stored BCrypt hash, or any
     * other credential material.</p>
     *
     * @param request the validated {@link SignonRequestDto} carrying
     *                {@code userId} (1-8 alphanumeric characters) and
     *                {@code password} (1-8 characters). The
     *                {@code @Valid} annotation triggers Jakarta Bean
     *                Validation against the record's components before
     *                this method body executes; any constraint
     *                violation aborts the request with HTTP 400 via
     *                {@code GlobalExceptionHandler} so this method
     *                never receives a malformed payload.
     * @return a {@link ResponseEntity} with HTTP 200 carrying the
     *         standardized {@link ApiResponse} envelope wrapping the
     *         {@link SignonResponseDto} (JWT, user identity, role
     *         discriminator, and expiry); never {@code null}
     */
    @PostMapping("/signin")
    @Operation(
            summary = "Sign on with User ID and password",
            description = "Authenticates against USRSEC (UserSecurity). On success returns "
                    + "a JWT bearer token plus user routing metadata. "
                    + "Replaces CICS COSGN00C / Tran-ID CC00 signon transaction."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Signon successful",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error (blank User ID / Password)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Invalid User ID or Password"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "User ID not found (mapped to invalid-credentials by the service to prevent enumeration)")
    })
    public ResponseEntity<ApiResponse<SignonResponseDto>> signin(
            @Valid @RequestBody SignonRequestDto request) {
        // COBOL: COSGN00C / Tran-ID CC00 — PROCESS-ENTER-KEY + READ-USER-SEC-FILE
        //        paragraphs. The COBOL flow:
        //          (1) RECEIVE MAP COSGN0A   → Jackson JSON binding (above)
        //          (2) Non-blank guards      → @Valid / @NotBlank on the DTO
        //          (3) FUNCTION UPPER-CASE   → SignonService.signon() upper-cases
        //                                       the userId; password is NOT
        //                                       upper-cased per the CP5 review
        //                                       (preserves BCrypt entropy)
        //          (4) READ DATASET('USRSEC') → UserSecurityRepository.findById
        //          (5) IF SEC-USR-PWD = WS-USER-PWD → PasswordEncoder.matches
        //          (6) MOVE ... CDEMO-USER-*  → JWT claim set
        //          (7) EXEC CICS XCTL         → SignonResponseDto.userType()
        //                                       drives client-side routing
        //
        // PCI-DSS log discipline (AAP §0.6.6): the audit breadcrumb is
        // emitted BEFORE the service call so that even a service-side
        // failure (DB down, JWT signing key unavailable, etc.) still
        // leaves a "signon attempt for userId=..." entry in CloudWatch
        // Logs. The user id is masked; the password is never logged,
        // never echoed, and never written to OpenSearch.
        LOG.info("Signon attempt for userId={}", maskUserId(request.userId()));

        // Delegate to the service. Any exception raised here propagates
        // unhandled to GlobalExceptionHandler which translates it to
        // the standardized JSON error envelope and the appropriate
        // HTTP status (400 for ValidationException, 401 for
        // BadCredentialsException, 404 for RecordNotFoundException,
        // 500 for unexpected runtime exceptions).
        SignonResponseDto response = signonService.signon(request);

        // PCI-DSS log discipline (AAP §0.6.6): success-path audit
        // breadcrumb. The masked user id and the userType (non-
        // sensitive role discriminator 'A' / 'U') are the only fields
        // emitted. The JWT, the stored BCrypt hash, and the raw
        // password are NEVER logged.
        LOG.info("Signon successful for userId={} userType={}",
                maskUserId(response.userId()), response.userType());

        // Wrap the response in the standardized ApiResponse envelope per
        // AAP §0.3.4 ({"code":"OK","message":"Sign-on successful",
        // "data":{...},"timestamp":"..."}). The fieldErrors and
        // correlationId components are null on the success path and
        // suppressed by Jackson's @JsonInclude(NON_NULL).
        return ResponseEntity.ok(ApiResponse.success(response, SIGNON_SUCCESS_MESSAGE));
    }

    /**
     * Mask a User ID for audit-log emission so the full operator
     * identity does not leak into CloudWatch Logs / OpenSearch.
     *
     * <p>This helper is intentionally simple and pure so it is trivial
     * to unit test without a Spring context. The masking strategy
     * retains the first two characters and the last character of the
     * (trimmed) input so the masked output remains correlatable across
     * multiple audit entries belonging to the same operator (e.g., a
     * support engineer can recognise {@code "AD***1"} as a particular
     * admin without needing to know the full id), while still preventing
     * full disclosure to anyone reading the logs.</p>
     *
     * <p>Inputs shorter than three characters are fully masked because
     * partial-mask output of a two-character id would reveal both
     * characters; for completeness, {@code null} and blank inputs are
     * also fully masked.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code maskUserId("ADMIN001")} &rarr; {@code "AD***1"}</li>
     *   <li>{@code maskUserId("USER0001")} &rarr; {@code "US***1"}</li>
     *   <li>{@code maskUserId("ABC")}      &rarr; {@code "AB***C"}</li>
     *   <li>{@code maskUserId("AB")}       &rarr; {@code "***"}</li>
     *   <li>{@code maskUserId("")}         &rarr; {@code "***"}</li>
     *   <li>{@code maskUserId(null)}       &rarr; {@code "***"}</li>
     *   <li>{@code maskUserId("  AD  ")}   &rarr; {@code "***"} (trims first; "AD" is then too short)</li>
     * </ul>
     *
     * <p>This method is {@code private static} because it has no
     * dependency on instance state and is invoked only from this
     * controller's {@link #signin(SignonRequestDto)} method. Promoting
     * it to a shared utility would require a corresponding scope
     * expansion in the AAP transformation mapping; for now, keeping it
     * private preserves the AAP-mandated Minimal Change Clause.</p>
     *
     * @param userId the raw User ID (may be {@code null}, blank, or any
     *               length); never logged in unmasked form
     * @return a partially-masked representation of the User ID suitable
     *         for emission to CloudWatch Logs / OpenSearch; never
     *         {@code null}
     */
    private static String maskUserId(String userId) {
        // Defensive: null or empty → fully mask. The Jakarta Bean
        // Validation @NotBlank constraint on SignonRequestDto means
        // this branch should not fire on a request that reached the
        // controller body, but the helper is also invoked on the
        // response-side userId returned by the service, which could
        // (in a defensive-coding sense) be null if a future refactor
        // returns a partial response. Fully masking the empty input
        // keeps the log line well-formed.
        if (userId == null || userId.isBlank()) {
            return FULLY_MASKED_USER_ID;
        }
        // Trim before measuring length so a space-padded BMS-era value
        // (e.g., "ADMIN001" was previously received on a 3270 device
        // padded to 8 bytes with trailing spaces) doesn't inflate the
        // perceived length. The trimmed value is what we mask.
        final String trimmed = userId.trim();
        // After trim, if the value is too short to retain any
        // positional context safely, fully mask it. A two-character id
        // would otherwise produce output like "AB***B" which fully
        // reveals both characters.
        if (trimmed.length() < MIN_USER_ID_LENGTH_FOR_PARTIAL_MASK) {
            return FULLY_MASKED_USER_ID;
        }
        // Partial mask: keep the first two characters, hide the
        // middle, keep the last character. Examples:
        //   "ADMIN001" → "AD" + "***" + "1" = "AD***1"
        //   "ABC"       → "AB" + "***" + "C" = "AB***C"
        return trimmed.substring(0, 2)
                + USER_ID_MASK_INFIX
                + trimmed.substring(trimmed.length() - 1);
    }
}
