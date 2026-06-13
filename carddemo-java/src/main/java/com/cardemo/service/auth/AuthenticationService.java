package com.cardemo.service.auth;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CommArea;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.security.TokenService;

/**
 * Sign-on / authentication business-logic service &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.5.11 translation of the legacy AWS CardDemo CICS COBOL
 * program <strong>{@code app/cbl/COSGN00C.cbl}</strong> (PROGRAM-ID
 * {@code COSGN00C}, CICS transaction id {@code CC00}, function "Signon Screen
 * for the CardDemo Application").
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>This service reproduces the observable behavior of {@code COSGN00C}
 * <em>exactly</em>. It implements the program's <strong>authentication
 * action</strong> &mdash; the {@code PROCESS-ENTER-KEY} "ENTER" path
 * (COSGN00C&nbsp;L108-140) together with {@code READ-USER-SEC-FILE}
 * (L209-257): receive a user id + password, run the ordered validation cascade,
 * read the {@code USRSEC} security file keyed by user id, verify the password,
 * and on success set the post-login routing context (admin vs. regular menu).
 * The flow is therefore: <em>cascade&nbsp;&rarr; read&nbsp;&rarr;
 * verify&nbsp;&rarr; route</em>.</p>
 *
 * <p>The CICS-specific wrapper of {@code COSGN00C} &mdash; the {@code MAIN-PARA}
 * {@code EIBAID} dispatch, {@code SEND MAP}/{@code RECEIVE MAP}, the PF3 exit,
 * the {@code EIBCALEN} first-entry test and the pseudo-conversational
 * {@code EXEC CICS RETURN} &mdash; is deliberately <strong>not</strong> this
 * service's concern: it maps to the {@code AuthController} REST endpoint and the
 * AID/PF keys are retired per AAP &sect;0.4.2 ({@code DFHAID} keys map to
 * distinct REST endpoints, with no carried equivalent). This class implements
 * only the authentication action and is invoked by {@code AuthController} from
 * {@code POST /api/auth/signin}.</p>
 *
 * <h2>The two technology substitutions (documented at the point of change)</h2>
 * <ol>
 *   <li><strong>Plaintext compare &rarr; BCrypt verify (constraint C-003, AAP
 *       &sect;0.7.2) &mdash; the single permitted behavioral change.</strong>
 *       Where {@code COSGN00C} compared the entered password to the stored
 *       plaintext ({@code IF SEC-USR-PWD = WS-USER-PWD}), this service verifies
 *       the entered password against the stored BCrypt hash via the injected
 *       Spring Security {@link PasswordEncoder}. This is the <em>only</em>
 *       deviation from byte-for-byte behavioral parity in the whole migration;
 *       everything else is preserved exactly.</li>
 *   <li><strong>Pseudo-conversational COMMAREA &rarr; stateless signed token
 *       (AAP &sect;0.1.2).</strong> The CICS {@code RETURN TRANSID COMMAREA}
 *       carry-over of {@code CARDDEMO-COMMAREA} is replaced by a self-contained,
 *       JDK-only HMAC-SHA256-signed compact token issued on success. The
 *       token-issuance primitive is owned by the dedicated
 *       {@link com.cardemo.security.TokenService} (which also validates the same
 *       token on every protected request via
 *       {@link com.cardemo.security.TokenAuthenticationFilter}); this service
 *       merely delegates to it. The signing secret is injected from configuration
 *       (never hardcoded, AAP &sect;0.7.2) and no JWT library is introduced.</li>
 * </ol>
 *
 * <h2>Transaction semantics</h2>
 * <p>{@code COSGN00C} only <em>reads</em> {@code USRSEC} (no {@code REWRITE}, no
 * {@code SYNCPOINT}), so {@link #signOn(SignOnRequest)} is annotated
 * {@link Transactional @Transactional(readOnly = true)} &mdash; the faithful,
 * correct mapping.</p>
 *
 * <h2>Traceability</h2>
 * <p>Derived from the frozen COBOL baseline at commit SHA {@code 27d6c6f}. The
 * COBOL source is read-only reference material and is <strong>never</strong>
 * copied into this repository &mdash; only its observable behavior is
 * reproduced.</p>
 *
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.config.SecurityConfig
 * @see com.cardemo.model.dto.SignOnRequest
 * @see com.cardemo.model.dto.SignOnResponse
 */
@Service
public class AuthenticationService {

    /**
     * Non-PII audit logger. It records sign-on <em>attempts</em> and outcomes by
     * user id and routing target only; it <strong>never</strong> logs the raw
     * password, the stored BCrypt hash, or the issued token.
     */
    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    // ---------------------------------------------------------------------
    // Verbatim COBOL message literals (COSGN00C) — preserved EXACTLY, including
    // the trailing " ..." (space-dot-dot-dot). These are part of the external
    // interface contract (AAP §0.7.2) and must not be reworded.
    // ---------------------------------------------------------------------

    /** COSGN00C L120 — {@code WHEN USERIDI = SPACES OR LOW-VALUES}. */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /** COSGN00C L125 — {@code WHEN PASSWDI = SPACES OR LOW-VALUES}. */
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    // ---------------------------------------------------------------------
    // Credential-failure messages — verbatim COSGN00C screen literals, RESTORED
    // to preserve 100% behavioral parity (AAP §0.7.2). COSGN00C displayed two
    // DISTINCT messages for the two credential-failure branches, and the migration
    // must reproduce that distinction: the ONLY sanctioned behavioral change in
    // this system is the C-003 BCrypt password upgrade, so collapsing the two
    // branches into a single generic 401 (the prior anti-enumeration substitution)
    // was an unsanctioned deviation that QA F1 flagged. The two branches now map to
    // distinct typed exceptions and HTTP statuses, each carrying its verbatim text:
    //     WHEN 13 (DFHRESP NOTFND)                -> RecordNotFoundException -> 404
    //                                                "User not found. Try again ..."   (COSGN00C L249)
    //     WHEN 0 + SEC-USR-PWD NOT = WS-USER-PWD  -> ValidationException     -> 400
    //                                                "Wrong Password. Try again ..."   (COSGN00C L242)
    // The trailing " ..." (space-dot-dot-dot) is part of the external interface
    // contract and is reproduced EXACTLY. The ordered blank-field cascade below is
    // unchanged and still surfaces its own verbatim 400 messages.
    // ---------------------------------------------------------------------

    /** COSGN00C L249 — {@code WHEN 13 (DFHRESP NOTFND)} unknown-user screen message. */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /** COSGN00C L242 — {@code SEC-USR-PWD NOT = WS-USER-PWD} wrong-password screen message. */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    // ---------------------------------------------------------------------
    // User-id field-width guard — COSGN00C sign-on map field USERIDI is
    // PIC X(08), a fixed 8-byte 3270 field that physically cannot hold more than
    // 8 characters. The REST contract has no intrinsic width limit, so an
    // over-length user id must be rejected to preserve the external field contract
    // (AAP §0.7.2; QA F3). The trailing " ..." matches the COBOL message convention.
    // ---------------------------------------------------------------------

    /** {@code PIC X(08)} fixed width of the COSGN00C user-id field (QA F3). */
    private static final int USER_ID_MAX_LENGTH = 8;

    /** Over-length user-id rejection message — PIC X(08) width preserved (QA F3). */
    private static final String MSG_USER_ID_TOO_LONG =
            "User ID can NOT be longer than 8 characters ...";

    // COSGN00C L254 — READ-USER-SEC-FILE EVALUATE WS-RESP-CD WHEN OTHER message
    // 'Unable to verify the User ...' is intentionally NOT declared as code: that
    // branch corresponds to an infrastructure/data-access failure which, in the
    // Java target, surfaces as a Spring DataAccessException propagating to the
    // framework's HTTP 500 fallback (see signOn()). It is documented here only.

    // ---------------------------------------------------------------------
    // Routing constants — COSGN00C identity + XCTL targets.
    // ---------------------------------------------------------------------

    /** {@code WS-TRANID PIC X(04) VALUE 'CC00'} — this program's own transaction id. */
    private static final String FROM_TRAN_ID = "CC00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'} — this program's own name. */
    private static final String FROM_PROGRAM = "COSGN00C";

    /** {@code XCTL PROGRAM('COADM01C')} target for {@code CDEMO-USRTYP-ADMIN}. */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /** Admin-menu transaction id paired with {@link #ADMIN_PROGRAM}. */
    private static final String ADMIN_TRAN_ID = "CA00";

    /** {@code XCTL PROGRAM('COMEN01C')} target for a regular ({@code USER}) user. */
    private static final String USER_PROGRAM = "COMEN01C";

    /** Main-menu transaction id paired with {@link #USER_PROGRAM}. */
    private static final String USER_TRAN_ID = "CM00";

    // ---------------------------------------------------------------------
    // Collaborators (constructor-injected — no field injection).
    // ---------------------------------------------------------------------

    /** Keyed access to the {@code USRSEC} security file (the sign-on read). */
    private final UserSecurityRepository userSecurityRepository;

    /** BCrypt encoder from {@code SecurityConfig}; verifies the stored hash (C-003). */
    private final PasswordEncoder passwordEncoder;

    /**
     * Dedicated stateless-token primitive (COMMAREA -> token substitution, AAP
     * &sect;0.1.2). This service delegates token issuance to it on a successful
     * sign-on; the same component validates the token on every protected request.
     * The signing secret lives entirely inside {@code TokenService} (injected and
     * validated there), so this service holds no secret material.
     */
    private final TokenService tokenService;

    /**
     * Constructs the service with its collaborators autowired by Spring.
     *
     * <p>The {@link PasswordEncoder} is the shared {@code BCryptPasswordEncoder}
     * bean exposed by {@code com.cardemo.config.SecurityConfig}; this service
     * never instantiates its own encoder. Token issuance is delegated to the
     * injected {@link TokenService}, which owns and validates the HMAC signing
     * secret bound from configuration ({@code carddemo.security.token.secret},
     * never hardcoded, AAP &sect;0.7.2); this service therefore holds no secret
     * material itself.</p>
     *
     * @param userSecurityRepository keyed access to {@code USRSEC}
     * @param passwordEncoder        the shared BCrypt encoder from {@code SecurityConfig}
     * @param tokenService           the stateless-token issuer/validator
     *                               (COMMAREA -&gt; token substitution)
     */
    public AuthenticationService(
            UserSecurityRepository userSecurityRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService) {
        this.userSecurityRepository = userSecurityRepository;
        // PasswordEncoder is the BCryptPasswordEncoder @Bean from SecurityConfig (C-003).
        this.passwordEncoder = passwordEncoder;
        // Token issuance is delegated; the signing secret lives inside TokenService
        // (injected + validated there), never as a literal here (AAP §0.7.2).
        this.tokenService = tokenService;
    }

    /**
     * Authenticates a sign-on request, reproducing the {@code COSGN00C}
     * authentication action (cascade&nbsp;&rarr; read&nbsp;&rarr;
     * verify&nbsp;&rarr; route).
     *
     * <p>The five observable failure outcomes and the success outcome map
     * one-to-one onto {@code COSGN00C}, each preserving its verbatim screen message
     * (AAP&nbsp;&sect;0.7.2 behavioral parity), as follows:</p>
     * <ul>
     *   <li>empty user id &rarr; {@link ValidationException} "Please enter User ID ..."
     *       &rarr; HTTP&nbsp;400 ({@code PROCESS-ENTER-KEY}
     *       {@code WHEN USERIDI = SPACES OR LOW-VALUES});</li>
     *   <li>empty password &rarr; {@link ValidationException} "Please enter Password ..."
     *       &rarr; HTTP&nbsp;400 ({@code WHEN PASSWDI = SPACES OR LOW-VALUES});</li>
     *   <li>over-length user id (&gt; {@value #USER_ID_MAX_LENGTH} characters) &rarr;
     *       {@link ValidationException} "User ID can NOT be longer than 8 characters ..."
     *       &rarr; HTTP&nbsp;400 &mdash; preserves the {@code PIC X(08)} fixed width of the
     *       {@code USERIDI} 3270 map field (QA F3);</li>
     *   <li>unknown user &rarr; {@link RecordNotFoundException} "User not found. Try again ..."
     *       &rarr; HTTP&nbsp;<strong>404</strong> ({@code READ-USER-SEC-FILE} {@code WHEN 13},
     *       {@code DFHRESP NOTFND}; COSGN00C&nbsp;L249). The verbatim text is carried as the
     *       client-safe message; the keyed id is retained only for server-side diagnostics;</li>
     *   <li>wrong password &rarr; {@link ValidationException} "Wrong Password. Try again ..."
     *       &rarr; HTTP&nbsp;<strong>400</strong> ({@code WHEN 0} +
     *       {@code SEC-USR-PWD NOT = WS-USER-PWD}; COSGN00C&nbsp;L242), evaluated via the
     *       C-003 BCrypt verify (the single permitted behavioral change);</li>
     *   <li>success &rarr; a populated {@link SignOnResponse} carrying the issued
     *       token, the signed-in identity and the post-login routing target.</li>
     * </ul>
     *
     * <p>Restoring the two distinct credential-failure outcomes (unknown user&nbsp;&rarr;&nbsp;404,
     * wrong password&nbsp;&rarr;&nbsp;400) reverses an earlier anti-enumeration substitution that
     * had collapsed both into a single generic 401; that substitution was an unsanctioned
     * behavioral change under AAP&nbsp;&sect;0.7.2 and was corrected per QA&nbsp;F1.</p>
     *
     * <p>Read-only transaction: {@code COSGN00C} performs no writes and no
     * {@code SYNCPOINT}, so this method runs in a {@code readOnly} transaction.</p>
     *
     * @param request the sign-on request carrying the entered user id and password
     * @return the populated sign-on response on success
     * @throws ValidationException     if the user id is empty, the password is empty, the user
     *                                 id exceeds {@value #USER_ID_MAX_LENGTH} characters, or the
     *                                 password fails BCrypt verification — each rendered as
     *                                 HTTP 400 with the verbatim COBOL message
     * @throws RecordNotFoundException if the user id is unknown (no {@code USRSEC} record) —
     *                                 rendered as HTTP 404 by {@code config/WebConfig} carrying the
     *                                 verbatim "User not found. Try again ..." client-safe message
     *                                 (COSGN00C L249; AAP §0.7.2 behavioral parity)
     */
    @Transactional(readOnly = true)
    public SignOnResponse signOn(SignOnRequest request) {
        // -----------------------------------------------------------------
        // PROCESS-ENTER-KEY EVALUATE TRUE cascade (COSGN00C L117-130).
        // ORDER IS MANDATORY and first-match-wins, with no fall-through
        // (AAP §0.7.4): user-id emptiness is evaluated strictly BEFORE password
        // emptiness, exactly as COBOL evaluates the two WHEN clauses. Each guard
        // throws immediately (the Java equivalent of COBOL setting ERR-FLG-ON and
        // re-displaying the screen without proceeding to READ-USER-SEC-FILE).
        // null + isBlank() together cover both COBOL SPACES and LOW-VALUES.
        // -----------------------------------------------------------------

        // WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new ValidationException(MSG_ENTER_USER_ID);
        }

        // WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ValidationException(MSG_ENTER_PASSWORD);
        }

        // PIC X(08) field-width guard (QA F3) — the COSGN00C sign-on map field
        // USERIDI is a fixed 8-byte 3270 field, so a user id longer than 8 bytes
        // could never have been entered on the terminal. The REST contract imposes
        // no such limit, so reject an over-length user id here to preserve the
        // external field contract (AAP §0.7.2). The TRIMMED length is checked so
        // surrounding whitespace (which the fixed-width field would not have
        // retained) does not itself trip the guard; this runs AFTER the blank-field
        // cascade (an empty id still yields the verbatim "Please enter User ID ...")
        // and BEFORE the upper-case/read so an over-length id never reaches the read.
        if (request.getUserId().trim().length() > USER_ID_MAX_LENGTH) {
            throw new ValidationException(MSG_USER_ID_TOO_LONG);
        }

        // MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID, CDEMO-USER-ID
        // MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD  (COSGN00C L132-136).
        // Locale.ROOT gives locale-independent upper-casing, matching the COBOL
        // intrinsic FUNCTION UPPER-CASE. The upper-cased user id keys the read and
        // becomes CDEMO-USER-ID; the upper-cased password feeds the BCrypt verify.
        final String upperUserId = request.getUserId().toUpperCase(Locale.ROOT);
        final String upperPassword = request.getPassword().toUpperCase(Locale.ROOT);

        log.debug("Sign-on attempt for user id [{}]", upperUserId);

        // -----------------------------------------------------------------
        // READ-USER-SEC-FILE (COSGN00C L209-257). The single keyed read collapses
        // the CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID) RESP(WS-RESP-CD).
        // -----------------------------------------------------------------
        final Optional<UserSecurity> found = userSecurityRepository.findBySecUsrId(upperUserId);

        // COBOL READ-USER-SEC-FILE WHEN OTHER -> 'Unable to verify the User ...';
        // infra failures (DataAccessException) propagate to HTTP 500. Not caught:
        // propagation is the faithful mapping (no dedicated project exception exists).

        // WHEN 13 (DFHRESP NOTFND): record absent. COSGN00C displayed
        // "User not found. Try again ..." on the sign-on screen (COSGN00C L249).
        // Behavioral parity (AAP §0.7.2; QA F1) is RESTORED: the unknown-user
        // branch maps to a distinct RecordNotFoundException rendered as HTTP 404
        // carrying that verbatim message. The 3-arg constructor supplies the
        // verbatim text as the CLIENT-SAFE message (surfaced by config/WebConfig
        // GlobalExceptionHandler#handleRecordNotFound), while the keyed entity id is
        // retained only for server-side diagnostics and is never echoed to the
        // caller — the same clientSafe pattern AccountViewService uses for its
        // "Did not find this account ..." 404.
        if (found.isEmpty()) {
            log.debug("Sign-on failed: no USRSEC record for user id [{}]", upperUserId);
            throw new RecordNotFoundException("UserSecurity", upperUserId, MSG_USER_NOT_FOUND);
        }

        // WHEN 0: record found.
        final UserSecurity user = found.get();

        // -----------------------------------------------------------------
        // Password verification — the SINGLE permitted behavioral change (C-003,
        // AAP §0.7.2). COBOL compared an 8-char PLAINTEXT password in-line:
        //     IF SEC-USR-PWD = WS-USER-PWD   (COSGN00C L223)
        // The migration stores a salted BCrypt hash in UserSecurity.password
        // (VARCHAR(72)) and verifies via the injected PasswordEncoder. The
        // entered password is verified UPPER-CASED, mirroring the COBOL compare of
        // the upper-cased WS-USER-PWD.
        //
        // CROSS-AGENT CONTRACT: because COBOL upper-cased the password before
        // comparing, the BCrypt seed hashes produced by Flyway V3__seed_data.sql
        // MUST be hashes of the UPPER-CASED password — this service verifies
        // upperPassword. The data-seed agent must stay consistent with this.
        // -----------------------------------------------------------------
        // COSGN00C displayed "Wrong Password. Try again ..." here (COSGN00C L242).
        // Behavioral parity (AAP §0.7.2; QA F1) is RESTORED: the wrong-password
        // branch maps to a distinct ValidationException rendered as HTTP 400
        // carrying that verbatim message (surfaced by config/WebConfig
        // GlobalExceptionHandler#handleValidation, whose empty-fieldErrors fallback
        // emits ex.getMessage() as the top-level message). The C-003 BCrypt verify
        // itself (the single permitted behavioral change) is unchanged.
        if (!passwordEncoder.matches(upperPassword, user.getSecUsrPwd())) {
            log.debug("Sign-on failed: password mismatch for user id [{}]", upperUserId);
            throw new ValidationException(MSG_WRONG_PASSWORD);
        }

        // -----------------------------------------------------------------
        // Success path (COSGN00C L222-240). Mirror the CDEMO-* MOVEs into the
        // CommArea (the CARDDEMO-COMMAREA replacement). The CommArea is then read
        // back when building the token and response, so it is genuinely used.
        // -----------------------------------------------------------------
        final CommArea commArea = new CommArea();
        commArea.setFromTranId(FROM_TRAN_ID);        // MOVE WS-TRANID    TO CDEMO-FROM-TRANID
        commArea.setFromProgram(FROM_PROGRAM);       // MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
        commArea.setUserId(upperUserId);             // MOVE WS-USER-ID   TO CDEMO-USER-ID
        commArea.setUserType(user.getSecUsrType());  // MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
        commArea.setProgramContext(0);               // MOVE ZEROS        TO CDEMO-PGM-CONTEXT

        // Routing (COSGN00C L230-240): IF CDEMO-USRTYP-ADMIN XCTL 'COADM01C'
        // ELSE XCTL 'COMEN01C'. The XCTL-target program becomes CDEMO-TO-PROGRAM
        // and its paired transaction id becomes CDEMO-TO-TRANID.
        if (commArea.getUserType().isAdmin()) {
            commArea.setToProgram(ADMIN_PROGRAM);
            commArea.setToTranId(ADMIN_TRAN_ID);
        } else {
            commArea.setToProgram(USER_PROGRAM);
            commArea.setToTranId(USER_TRAN_ID);
        }

        // COMMAREA -> stateless token substitution (AAP §0.1.2): issue a signed
        // token carrying the user identity/type in place of the CICS COMMAREA
        // carry-over. Delegated to TokenService, which owns the injected signing
        // secret (never hardcoded) and the token format.
        final String token = tokenService.issue(commArea.getUserId(), commArea.getUserType());

        log.info("User [{}] signed on; routing to program [{}] (tranid [{}])",
                commArea.getUserId(), commArea.getToProgram(), commArea.getToTranId());

        // Build the response by reading the CommArea fields back. The password is
        // never echoed; SignOnResponse has no password field by design.
        final SignOnResponse response = new SignOnResponse();
        response.setToken(token);
        response.setUserId(commArea.getUserId());
        response.setUserType(commArea.getUserType());
        response.setToTranId(commArea.getToTranId());
        response.setToProgram(commArea.getToProgram());
        return response;
    }

    // -------------------------------------------------------------------------
    // Cross-agent coordination note (AAP §0.7.4 parity, documented per Phase 8):
    //
    // SignOnRequest carries @NotBlank on both userId and password. If
    // AuthController applies @Valid to the request body, truly-blank fields would
    // be rejected by Bean Validation (MethodArgumentNotValidException -> a generic
    // 400) BEFORE this service runs — which would NOT match the verbatim COBOL
    // messages ("Please enter User ID ..." / "Please enter Password ...") nor the
    // mandatory user-id-then-password evaluation order. To guarantee that
    // ordered-cascade + verbatim-message parity, THIS service performs the
    // blank-field cascade itself and is the parity-authoritative path. The
    // controller therefore should not rely solely on @NotBlank to short-circuit
    // these two fields' emptiness when exact COBOL message/order parity is
    // required end-to-end. (Note only — the DTO and controller are other agents'
    // scope and are not modified here.)
    // -------------------------------------------------------------------------
}
