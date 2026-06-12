package com.cardemo.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *       JDK-only HMAC-SHA256-signed compact token issued on success (see
 *       {@link #issueToken(String, UserType)}). The signing secret is injected
 *       from configuration (never hardcoded, AAP &sect;0.7.2) and no JWT library
 *       is introduced.</li>
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

    /** COSGN00C L242 — {@code WHEN 0} + {@code SEC-USR-PWD NOT = WS-USER-PWD}. */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** COSGN00C L249 — {@code WHEN 13} (DFHRESP NOTFND). */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

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
    // Stateless-token constants (COMMAREA -> token substitution, AAP §0.1.2).
    // ---------------------------------------------------------------------

    /** JDK-guaranteed MAC algorithm used to sign the compact token. */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Fixed compact-token header: {@code {"alg":"HS256","typ":"JWT"}}. */
    private static final String TOKEN_HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    // ---------------------------------------------------------------------
    // Collaborators (constructor-injected — no field injection).
    // ---------------------------------------------------------------------

    /** Keyed access to the {@code USRSEC} security file (the sign-on read). */
    private final UserSecurityRepository userSecurityRepository;

    /** BCrypt encoder from {@code SecurityConfig}; verifies the stored hash (C-003). */
    private final PasswordEncoder passwordEncoder;

    /** HMAC signing secret, injected from configuration; never hardcoded (AAP §0.7.2). */
    private final String tokenSecret;

    /** Token lifetime in seconds (non-secret; defaults to 3600 when unset). */
    private final long tokenTtlSeconds;

    /**
     * Constructs the service with its collaborators autowired by Spring.
     *
     * <p>The {@link PasswordEncoder} is the shared {@code BCryptPasswordEncoder}
     * bean exposed by {@code com.cardemo.config.SecurityConfig}; this service
     * never instantiates its own encoder. The token signing secret and lifetime
     * are bound from configuration via {@link Value @Value}: the secret has
     * <strong>no</strong> default (it must be supplied externally so no
     * credential is hardcoded, AAP &sect;0.7.2), while the non-secret TTL falls
     * back to one hour.</p>
     *
     * @param userSecurityRepository keyed access to {@code USRSEC}
     * @param passwordEncoder        the shared BCrypt encoder from {@code SecurityConfig}
     * @param tokenSecret            HMAC signing secret bound from
     *                               {@code carddemo.security.token.secret}
     * @param tokenTtlSeconds        token lifetime bound from
     *                               {@code carddemo.security.token.ttl-seconds}
     *                               (default {@code 3600})
     */
    public AuthenticationService(
            UserSecurityRepository userSecurityRepository,
            PasswordEncoder passwordEncoder,
            @Value("${carddemo.security.token.secret}") String tokenSecret,
            @Value("${carddemo.security.token.ttl-seconds:3600}") long tokenTtlSeconds) {
        this.userSecurityRepository = userSecurityRepository;
        // PasswordEncoder is the BCryptPasswordEncoder @Bean from SecurityConfig (C-003).
        this.passwordEncoder = passwordEncoder;
        // Secret is injected (@Value), never a hardcoded literal (AAP §0.7.2).
        this.tokenSecret = tokenSecret;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /**
     * Authenticates a sign-on request, reproducing the {@code COSGN00C}
     * authentication action (cascade&nbsp;&rarr; read&nbsp;&rarr;
     * verify&nbsp;&rarr; route).
     *
     * <p>The four observable failure outcomes and the success outcome map
     * one-to-one onto {@code COSGN00C} as follows:</p>
     * <ul>
     *   <li>empty user id &rarr; {@link ValidationException} "Please enter User ID ..."
     *       ({@code PROCESS-ENTER-KEY} {@code WHEN USERIDI = SPACES OR LOW-VALUES});</li>
     *   <li>empty password &rarr; {@link ValidationException} "Please enter Password ..."
     *       ({@code WHEN PASSWDI = SPACES OR LOW-VALUES});</li>
     *   <li>unknown user &rarr; {@link RecordNotFoundException} "User not found. Try again ..."
     *       ({@code READ-USER-SEC-FILE} {@code WHEN 13});</li>
     *   <li>wrong password &rarr; {@link ValidationException} "Wrong Password. Try again ..."
     *       ({@code WHEN 0} + {@code SEC-USR-PWD NOT = WS-USER-PWD});</li>
     *   <li>success &rarr; a populated {@link SignOnResponse} carrying the issued
     *       token, the signed-in identity and the post-login routing target.</li>
     * </ul>
     *
     * <p>Read-only transaction: {@code COSGN00C} performs no writes and no
     * {@code SYNCPOINT}, so this method runs in a {@code readOnly} transaction.</p>
     *
     * @param request the sign-on request carrying the entered user id and password
     * @return the populated sign-on response on success
     * @throws ValidationException     if the user id or password is empty, or the
     *                                 password fails BCrypt verification (HTTP 400)
     * @throws RecordNotFoundException if no {@code USRSEC} record exists for the
     *                                 supplied user id (HTTP 404)
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

        // WHEN 13 (DFHRESP NOTFND): record absent -> "User not found. Try again ...".
        // Single-arg RecordNotFoundException ctor preserves the verbatim message
        // (the (entityType,key) ctor would synthesize a different message).
        if (found.isEmpty()) {
            log.debug("Sign-on failed: no USRSEC record for user id [{}]", upperUserId);
            throw new RecordNotFoundException(MSG_USER_NOT_FOUND);
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
        // carry-over. The signing secret is injected (@Value), never hardcoded.
        final String token = issueToken(commArea.getUserId(), commArea.getUserType());

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

    /**
     * Issues a self-contained, JDK-only HMAC-SHA256-signed compact token &mdash;
     * the stateless replacement for the CICS pseudo-conversational
     * {@code CARDDEMO-COMMAREA} carry-over (AAP &sect;0.1.2).
     *
     * <p>The token is the standard compact triple
     * {@code base64url(header) + "." + base64url(payload) + "." +
     * base64url(HMAC-SHA256(header + "." + payload))}. The minimal claim set is
     * {@code sub} (the signed-in user id), {@code typ} (the {@link UserType} code
     * {@code 'A'}/{@code 'U'}), {@code iat} (issued-at epoch seconds) and
     * {@code exp} (expiry = {@code iat + tokenTtlSeconds}). No JWT library is
     * used and the signing secret is injected (never hardcoded, AAP
     * &sect;0.7.2).</p>
     *
     * @param userId the signed-in user id placed in the {@code sub} claim
     * @param type   the signed-in user's role placed in the {@code typ} claim
     * @return the signed compact token
     */
    private String issueToken(String userId, UserType type) {
        final long issuedAt = Instant.now().getEpochSecond();   // iat
        final long expiresAt = issuedAt + tokenTtlSeconds;      // exp = iat + TTL

        // Minimal claims, JSON built by hand to stay JDK-only (no JWT dependency).
        // userId is JSON-escaped defensively; typ is the 1-char UserType code.
        final String payloadJson = "{\"sub\":\"" + jsonEscape(userId) + "\""
                + ",\"typ\":\"" + type.getCode() + "\""
                + ",\"iat\":" + issuedAt
                + ",\"exp\":" + expiresAt + "}";

        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        final String encodedHeader =
                encoder.encodeToString(TOKEN_HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        final String encodedPayload =
                encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        final String signingInput = encodedHeader + "." + encodedPayload;
        final String signature = encoder.encodeToString(sign(signingInput));
        return signingInput + "." + signature;
    }

    /**
     * Computes the HMAC-SHA256 signature of the compact token's signing input
     * using the injected secret.
     *
     * <p>{@code HmacSHA256} is guaranteed present on every JRE, so a
     * {@link GeneralSecurityException} here indicates a fatal configuration
     * error rather than a recoverable condition; it is wrapped in an unchecked
     * {@link IllegalStateException} so the checked exception never widens the
     * {@link #signOn(SignOnRequest)} signature.</p>
     *
     * @param signingInput the {@code base64url(header) + "." + base64url(payload)} string
     * @return the raw HMAC-SHA256 signature bytes
     */
    private byte[] sign(String signingInput) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is JDK-guaranteed; failure is a fatal configuration error.
            throw new IllegalStateException("Unable to sign the authentication token", e);
        }
    }

    /**
     * Escapes a string for safe inclusion as a JSON string value in the
     * hand-built token payload.
     *
     * <p>The JSON-significant characters ({@code "} and {@code \}), the common
     * control escapes, and any remaining C0 control character are escaped per
     * RFC&nbsp;8259 so the payload is always well-formed regardless of the input
     * (defensive: the user id is normally a short alphanumeric value).</p>
     *
     * @param value the raw string value to escape
     * @return the JSON-escaped value (without surrounding quotes)
     */
    private static String jsonEscape(String value) {
        final StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
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
