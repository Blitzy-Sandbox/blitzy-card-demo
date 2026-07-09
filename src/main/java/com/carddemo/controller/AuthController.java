package com.carddemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.dto.SignonRequest;
import com.carddemo.dto.SignonResponse;
import com.carddemo.service.SignonService;

import jakarta.validation.Valid;

/**
 * Sign-on (authentication) REST controller &mdash; the Java&nbsp;25 / Spring&nbsp;Boot 3.x replacement
 * for the legacy AWS CardDemo signon program {@code COSGN00C} (CICS transaction {@code CC00}; frozen
 * COBOL reference at source commit SHA {@code 27d6c6f}).
 *
 * <p>In the mainframe design the {@code COSGN00} BMS map (3270 screen) gathered a user id and
 * password, and {@code COSGN00C} validated the mandatory fields, upper-cased both values, read the
 * {@code USRSEC} VSAM file, compared the password, and finally routed the operator to
 * {@code COADM01C} (administrators) or {@code COMEN01C} (regular users) while carrying the user
 * identity forward in the CICS pseudo-conversational {@code COMMAREA}. This controller re-expresses
 * the presentation boundary of that flow as a single stateless JSON endpoint; the entire sign-on
 * business logic (blank-field edits, upper-casing, keyed {@code USRSEC} lookup, password
 * verification, and {@code SEC-USR-TYPE} &rarr; role mapping) lives in {@link SignonService}. The
 * controller is intentionally a thin delegator: it receives the request, records a non-sensitive
 * audit line, and returns the service's result.</p>
 *
 * <h2>Endpoint</h2>
 * <p>{@code POST /api/auth/login} accepts a {@link SignonRequest} and, on success, returns HTTP
 * {@code 200 OK} with a {@link SignonResponse} carrying a freshly issued bearer JWT. The JWT is the
 * stateless replacement for the CICS {@code COMMAREA} user context (AAP &sect;0.8.4): no server-side
 * conversational session is retained, and every subsequent call presents the token. After a
 * successful sign-on the client reads the {@code role} from the response and proceeds to the menu
 * controller; role-based routing is deliberately <em>not</em> performed here (mirroring the legacy
 * split between {@code COADM01C} and {@code COMEN01C}, which the client now selects).</p>
 *
 * <h2>Validation and error mapping</h2>
 * <p>Bean validation on {@link SignonRequest} ({@code @NotBlank}, {@code @Size(max = 8)}) reproduces
 * the legacy "Please enter User ID ..." / "Please enter Password ..." mandatory-field prompts: a
 * blank submission fails {@code @Valid} and is rendered as HTTP {@code 400} by the central
 * {@code GlobalExceptionHandler} without ever reaching the service. Credential failures
 * (wrong password / unknown user &mdash; the COBOL {@code WHEN 13} and password-mismatch branches)
 * are raised by {@link SignonService} as {@code BadCredentialsException} and mapped to a single,
 * generic HTTP {@code 401} so the boundary never discloses whether an account exists. This method
 * therefore performs <strong>no exception handling of its own</strong>; all mapping is centralized in
 * the {@code @RestControllerAdvice}.</p>
 *
 * <h2>Security</h2>
 * <p>This endpoint issues the token, so it must be reachable without prior authentication. The
 * {@code permitAll()} rule for {@code /api/auth/login} is declared in {@code config/SecurityConfig}
 * (the security filter chain), not here; consequently no method-level authorization
 * (for example {@code @PreAuthorize}) is applied to this controller. The submitted password, the
 * issued JWT, and any credential-bearing object are never logged: only the user id and the sign-on
 * attempt are audited. The per-request MDC {@code correlationId} used by structured logging is
 * injected upstream by the correlation-id filter and is not set here. Design rationale (including the
 * BCrypt password upgrade, Decision Log&nbsp;D-002) lives in {@code docs/decision-log.md}, not in code
 * comments.</p>
 *
 * <p>The controller is stateless and thread-safe: its single collaborator is a singleton Spring bean
 * injected once at construction and never mutated.</p>
 *
 * @see SignonService
 * @see SignonRequest
 * @see SignonResponse
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    /**
     * SLF4J logger for this controller. It emits only non-sensitive diagnostics (the user id and the
     * sign-on attempt); it never records the password, the {@link SignonRequest} object as a whole, or
     * the issued JWT. Every line carries the MDC {@code correlationId} supplied by the correlation-id
     * filter.
     */
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /**
     * The sign-on service that performs the actual authentication. Constructor injection replaces the
     * legacy static COBOL {@code CALL}/linkage to the security logic and keeps the controller's single
     * dependency explicit and final.
     */
    private final SignonService signonService;

    /**
     * Creates the authentication controller with its sign-on collaborator.
     *
     * <p>Constructor injection is used exclusively (never field injection) so the dependency is
     * explicit and {@code final}, and so the controller can be instantiated directly in unit tests
     * with a mocked {@link SignonService} &mdash; no Spring {@code ApplicationContext} required.</p>
     *
     * @param signonService the sign-on / authentication service (never {@code null})
     */
    public AuthController(SignonService signonService) {
        this.signonService = signonService;
    }

    /**
     * Authenticates a sign-on request and, on success, returns a JWT-bearing {@link SignonResponse}.
     *
     * <p>This is the REST translation of the {@code COSGN00C} enter-key path: the controller records a
     * non-sensitive audit line (user id only) and delegates the full credential check to
     * {@link SignonService#authenticate(SignonRequest)}. On success the service returns a response
     * containing a bearer JWT, the echoed user id, the user's first and last names, and the mapped role
     * ({@code "ADMIN"} or {@code "USER"}), and this method wraps it in an HTTP {@code 200 OK}.</p>
     *
     * <p>No exceptions are caught here (AAP requirement): {@code @Valid} failures on blank or oversized
     * fields become HTTP {@code 400}, a {@code BadCredentialsException} becomes a generic HTTP
     * {@code 401}, and an unexpected {@code USRSEC} I/O error becomes HTTP {@code 500} &mdash; all via
     * the central {@code GlobalExceptionHandler}. The response body never includes the submitted
     * password.</p>
     *
     * @param request the sign-on request carrying the user id and password (validated by
     *                {@code @Valid}); the password is used only in transit and is never logged
     * @return an HTTP {@code 200 OK} {@link ResponseEntity} whose body is the {@link SignonResponse}
     *         with the issued bearer token and the authenticated user's identity and role
     */
    @PostMapping("/login")
    public ResponseEntity<SignonResponse> login(@Valid @RequestBody SignonRequest request) {
        // Audit the attempt by user id only. Never log the password, the whole request, or the token
        // (SignonRequest.toString() already redacts the password, but the object is not logged here).
        log.info("Sign-on attempt for userId={}", request.userId());

        // Delegate the entire COSGN00C credential flow (blank-field edits, upper-casing, USRSEC read,
        // password verification, role mapping, JWT issuance) to the service. Any failure propagates to
        // GlobalExceptionHandler for consistent HTTP status mapping.
        SignonResponse response = signonService.authenticate(request);

        return ResponseEntity.ok(response);
    }
}
