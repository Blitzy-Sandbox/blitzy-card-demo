package com.cardemo.controller;

import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.service.auth.AuthenticationService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST replacement for the AWS CardDemo CICS BMS 3270 <strong>Sign-On screen</strong>. It exposes the
 * single route <strong>{@code POST /api/auth/signin}</strong> &mdash; authenticate a user and, on
 * success, issue a session token &mdash; and is a thin adapter over {@link AuthenticationService}.
 *
 * <p>This controller is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5.11 realization of
 * AAP&nbsp;&sect;0.4.1 (tech-spec&nbsp;L646: <em>{@code controller/AuthController.java} CREATE &larr;
 * {@code app/bms/COSGN00.bms}, {@code app/cpy-bms/COSGN00.CPY} &mdash; "POST /api/auth/signin"</em>)
 * and of AAP&nbsp;&sect;0.3.4 (BMS&nbsp;&rarr;&nbsp;REST contract translation). It preserves feature
 * <strong>F-001</strong> (Authentication / Sign-On) without expansion (Minimal Change Clause,
 * AAP&nbsp;&sect;0.7.1): exactly one endpoint, no new behavior.</p>
 *
 * <h2>Authoritative source artifacts (read-only reference, never copied)</h2>
 * <ul>
 *   <li><strong>{@code app/bms/COSGN00.bms}</strong> &mdash; the Login mapset ({@code COSGN00} / map
 *       {@code COSGN0A}, "CardDemo Login Screen"), driven by CICS program {@code COSGN00C},
 *       transaction <strong>{@code CC00}</strong> (confirmed in {@code app/jcl/CBADMCDJ.jcl} CSD
 *       {@code DEFINE PROGRAM(COSGN00C) ... TRANSID(CC00)}). Its BMS field contract is input
 *       {@code USERID} ({@code LENGTH 8}) and {@code PASSWD} ({@code LENGTH 8}, {@code ATTRB=DRK} =
 *       non-display / masked), output {@code ERRMSG} ({@code LENGTH 78}), with AID keys
 *       {@code ENTER=Sign-on} and {@code F3=Exit}.</li>
 *   <li><strong>{@code app/cpy-bms/COSGN00.CPY}</strong> &mdash; the symbolic map ({@code COSGN0AI} /
 *       {@code COSGN0AO}) whose two operator inputs {@code USERIDI}/{@code PASSWDI} ({@code PIC X(8)})
 *       were migrated into {@link SignOnRequest}, and whose error line / routing fields are projected
 *       into {@link SignOnResponse}.</li>
 * </ul>
 * <p>This controller never re-declares those structures and never copies COBOL/BMS text &mdash; only
 * the screen <em>behavior</em> is reproduced, by delegation to {@link AuthenticationService}.</p>
 *
 * <h2>Key insight &mdash; all sign-on logic lives in the service (AAP &sect;0.3.3)</h2>
 * <p>This is a textbook thin REST adapter: one {@code POST}, one service call, one DTO out. It
 * contains <strong>no business logic, no data access and no password handling</strong>. The ordered
 * blank-field validation cascade (with the verbatim COBOL messages), the {@code USRSEC} keyed read,
 * the BCrypt password verification (the single permitted behavioral change, constraint C-003,
 * AAP&nbsp;&sect;0.7.2) and the post-login routing (ADMIN&nbsp;&rarr;&nbsp;admin menu
 * {@code CA00}/{@code COADM01C}; USER&nbsp;&rarr;&nbsp;main menu {@code CM00}/{@code COMEN01C}) all
 * live in {@link AuthenticationService} (the translation of {@code COSGN00C}). This controller adds
 * <strong>zero</strong> logic; it returns {@code 200 OK} carrying the service's {@link SignOnResponse}.</p>
 *
 * <h2>COBOL &rarr; REST substitutions (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code RECEIVE MAP('COSGN0A')} &rarr; {@code @RequestBody}.</strong> The 3270 field
 *       harvest that populated {@code COSGN0AI} ({@code USERIDI}/{@code PASSWDI}) collapses into
 *       Jackson binding a JSON body to {@link SignOnRequest}. Screen chrome, the {@code ERRMSG} line
 *       and BMS control bytes have no REST analogue and are not modeled (AAP&nbsp;&sect;0.4.2).</li>
 *   <li><strong>{@code SEND MAP} / {@code RETURN TRANSID('CC00') COMMAREA} &rarr; stateless
 *       token-bearing response.</strong> The CICS pseudo-conversational hand-off (re-displaying the
 *       map and threading {@code CARDDEMO-COMMAREA} across turns) is replaced by a stateless
 *       {@code 200 OK} whose {@link SignOnResponse} carries a server-issued <em>token</em>; the client
 *       presents that token on subsequent calls (AAP&nbsp;&sect;0.1.2,
 *       {@code RETURN TRANSID COMMAREA -> stateless REST with token state}). This controller holds
 *       <strong>no</strong> server-side conversational state and sets no cookie / {@code HttpSession}.</li>
 *   <li><strong>AID keys &rarr; distinct REST endpoints / no endpoint.</strong> Per AAP&nbsp;&sect;0.1.2
 *       ({@code DFHAID} keys map to distinct REST endpoints), the screen's {@code ENTER=Sign-on} maps
 *       to <em>this</em> {@code POST /api/auth/signin}; {@code F3=Exit} has <strong>no</strong> server
 *       endpoint &mdash; the client simply ends the session (discards the token).</li>
 * </ul>
 *
 * <h2>Validation parity &mdash; delegated to the service (AAP &sect;0.7.2)</h2>
 * <p>The request body is bound as a plain {@code @RequestBody} <strong>without</strong> {@code @Valid}.
 * {@link AuthenticationService} is the authoritative source for validation <em>order</em> and verbatim
 * COBOL message text: {@code COSGN00C} emits "Please enter User ID&nbsp;..." for an empty user id
 * <em>before</em> "Please enter Password&nbsp;..." for an empty password. Applying bean validation here
 * would let {@link SignOnRequest}'s {@code @NotBlank}/{@code @Size} constraints pre-empt that cascade
 * with a generic {@code MethodArgumentNotValidException} (and an arbitrary field order), breaking
 * message parity. Omitting {@code @Valid} guarantees a blank user id or password reaches the service,
 * which throws the correctly-ordered, verbatim {@code ValidationException}. The {@link SignOnRequest}
 * Jakarta constraints remain the documented field contract (the symbolic-map width translation), but
 * the service is the runtime source of truth. This mirrors the other CardDemo controllers.</p>
 *
 * <h2>Error handling &mdash; centralized advice, exceptions propagate</h2>
 * <p>This controller defines <strong>no</strong> {@code @ExceptionHandler} /
 * {@code @RestControllerAdvice} and catches no domain exception. {@link AuthenticationService} throws
 * and this controller lets propagate the typed exceptions translated by the centralized
 * {@code @RestControllerAdvice} in {@code config/WebConfig} ({@code GlobalExceptionHandler}):</p>
 * <ul>
 *   <li>{@code com.cardemo.exception.ValidationException} &rarr; HTTP&nbsp;<strong>400 Bad
 *       Request</strong> &mdash; the ordered blank-field cascade only (empty user id, then empty
 *       password), surfacing the verbatim {@code COSGN00C} messages in the COBOL order.</li>
 *   <li>{@code org.springframework.security.authentication.BadCredentialsException} (a Spring
 *       Security {@code AuthenticationException}) &rarr; HTTP&nbsp;<strong>401 Unauthorized</strong>
 *       &mdash; an <em>unknown user</em> and a <em>wrong password</em> both raise this single
 *       credential-failure type and are rendered with one generic body, so the two outcomes are
 *       indistinguishable to the caller (username-enumeration defense, api-contracts.md&nbsp;&sect;5.1).
 *       {@code COSGN00C} showed two distinct screen messages here; the migrated REST contract
 *       deliberately collapses them to a uniform 401.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>This endpoint is <strong>public</strong>: {@code config/SecurityConfig} permits
 * {@code /api/auth/**} (and actuator) and requires authentication for everything else &mdash; a caller
 * must reach sign-on to obtain a token. No security infrastructure and no method-security annotations
 * are introduced in this thin adapter; the password is never logged or echoed (it is accepted only on
 * the inbound {@link SignOnRequest}, which is {@code WRITE_ONLY}, and is absent from
 * {@link SignOnResponse}).</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL/BMS baseline at commit SHA
 * {@code 27d6c6f}. The COBOL and BMS sources are read-only reference material and are never copied
 * into this repository (AAP&nbsp;&sect;0.7.2).</p>
 *
 * @see AuthenticationService
 * @see SignOnRequest
 * @see SignOnResponse
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * Service owning the authentication action ({@code COSGN00C}): the ordered blank-field validation
     * cascade, the {@code USRSEC} keyed read, the BCrypt verification (C-003) and the post-login
     * routing / token issuance. Injected by constructor (no field {@code @Autowired}).
     */
    private final AuthenticationService authenticationService;

    /**
     * Constructs the controller with the authentication service injected by Spring (constructor
     * injection; the field is {@code final}; no field {@code @Autowired}).
     *
     * @param authenticationService the sign-on / authentication service ({@code COSGN00C})
     */
    public AuthController(final AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Authenticates a user and, on success, issues a session token, reproducing the authentication
     * action of {@code COSGN00C}.
     *
     * <p><strong>Endpoint:</strong> {@code POST /api/auth/signin}.</p>
     *
     * <p>The request body ({@link SignOnRequest}: {@code userId}, {@code password}) is the REST form of
     * the {@code RECEIVE MAP('COSGN0A')} field harvest. It is passed through unchanged to
     * {@link AuthenticationService#signOn(SignOnRequest)}, which performs the entire sign-on flow and
     * returns a populated {@link SignOnResponse} carrying the issued token, the signed-in identity
     * ({@code userId}/{@code userType}) and the post-login routing target ({@code toTranId}/
     * {@code toProgram}). The controller performs no validation of its own so a blank user id or
     * password reaches the service and surfaces the verbatim, correctly-ordered COBOL message rather
     * than a generic framework error (parity, AAP&nbsp;&sect;0.7.2 &mdash; hence no {@code @Valid}).</p>
     *
     * <p>On success the response is <strong>{@code 200 OK}</strong> (a CICS sign-on success is a normal
     * screen transition, not a resource creation, so 200 rather than 201). The failure outcomes are
     * translated centrally by {@code config/WebConfig} ({@code GlobalExceptionHandler}); this method
     * catches nothing and lets the typed exceptions propagate.</p>
     *
     * @param request the sign-on request carrying the entered user id and password
     *                ({@code RECEIVE MAP('COSGN0A')} replacement)
     * @return {@code 200 OK} carrying the populated {@link SignOnResponse} (token + identity + routing)
     * @throws com.cardemo.exception.ValidationException if the user id is empty or the password is
     *         empty (the ordered blank-field cascade, rendered as HTTP&nbsp;400 by
     *         {@code config/WebConfig}); propagated, not caught
     * @throws org.springframework.security.authentication.BadCredentialsException if the user id is
     *         unknown or the password fails verification &mdash; a single credential-failure type
     *         rendered as HTTP&nbsp;401 with a generic body by {@code config/WebConfig}
     *         (username-enumeration defense); propagated, not caught
     */
    // COBOL substitution (AAP §0.7.1): the CICS map exchange and pseudo-conversational return collapse
    // into one stateless POST:
    //   - RECEIVE MAP('COSGN0A') USERID/PASSWD          -> @RequestBody SignOnRequest (Jackson binding)
    //   - SEND MAP / RETURN TRANSID('CC00') COMMAREA    -> 200 OK SignOnResponse carrying a token
    //                                                      (no cookie / HttpSession; client holds state)
    //   - AID keys: ENTER=Sign-on maps to THIS endpoint; F3=Exit has no server endpoint (the client
    //     simply ends the session / discards the token) per the §0.1.2 AID-key rule.
    // Parity (AAP §0.7.2): bind WITHOUT @Valid on purpose. The service runs the ordered, verbatim COBOL
    // blank-field cascade (user id THEN password); @Valid would pre-empt it with a generic
    // MethodArgumentNotValidException and break message order/text parity. Pure delegation only: no
    // business logic, no try/catch, no @ExceptionHandler — domain exceptions propagate to the central
    // GlobalExceptionHandler in config/WebConfig: blank-field ValidationException -> 400, and a single
    // BadCredentialsException -> 401 for BOTH unknown-user and wrong-password (generic body; the COBOL
    // 404/400 distinction is intentionally suppressed per api-contracts.md §5.1 to prevent enumeration).
    @PostMapping("/signin")
    public ResponseEntity<SignOnResponse> signIn(@RequestBody final SignOnRequest request) {
        final SignOnResponse response = authenticationService.signOn(request);
        return ResponseEntity.ok(response);
    }
}
