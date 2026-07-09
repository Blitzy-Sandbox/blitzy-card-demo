package com.carddemo.service;

import com.carddemo.dto.SignonRequest;
import com.carddemo.dto.SignonResponse;
import com.carddemo.entity.UserSecurity;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserSecurityRepository;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Sign-on / authentication service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot replacement for the
 * legacy AWS CardDemo signon program {@code COSGN00C} (CICS transaction {@code CC00}; frozen COBOL
 * reference at source commit SHA {@code 27d6c6f}).
 *
 * <p>In the mainframe design, {@code COSGN00C} received the {@code COSGN0A} BMS map, validated that
 * the user id and password fields were present, upper-cased both, read the {@code USRSEC} VSAM file
 * by user id, compared the supplied password against the plaintext {@code SEC-USR-PWD} field of
 * copybook {@code CSUSR01Y}, and finally routed the operator to {@code COADM01C} (administrators,
 * {@code SEC-USR-TYPE = 'A'}) or {@code COMEN01C} (regular users) while carrying the user identity
 * forward in the CICS pseudo-conversational {@code COMMAREA}. This service re-expresses that flow as
 * a stateless authentication step: the file-based {@code USRSEC} store is preserved (RACF is
 * <em>not</em> introduced), the password compare becomes a BCrypt verification, and the
 * {@code COMMAREA} user context is replaced by a signed JWT.</p>
 *
 * <h2>Control-flow mapping ({@code COSGN00C} &rarr; {@link #authenticate(SignonRequest)})</h2>
 * <table>
 *   <caption>COSGN00C paragraph / EVALUATE branch to Java behaviour</caption>
 *   <tr><th>COBOL construct</th><th>Java behaviour</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}: {@code USERIDI = SPACES OR LOW-VALUES}</td>
 *       <td>{@link ValidationException} ({@code 400}) carrying {@link #MSG_ENTER_USERID}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}: {@code PASSWDI = SPACES OR LOW-VALUES}</td>
 *       <td>{@link ValidationException} ({@code 400}) carrying {@link #MSG_ENTER_PASSWORD}</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE}: {@code WHEN 0} &amp; password matches</td>
 *       <td>success &mdash; issue JWT, return {@link SignonResponse} routed by {@code SEC-USR-TYPE}</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE}: {@code WHEN 0} &amp; password mismatch</td>
 *       <td>{@link BadCredentialsException} ({@code 401}) carrying {@link #MSG_WRONG_PASSWORD}</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE}: {@code WHEN 13} (not found)</td>
 *       <td>{@link BadCredentialsException} ({@code 401}) carrying {@link #MSG_USER_NOT_FOUND}</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE}: {@code WHEN OTHER} (unexpected I/O)</td>
 *       <td>{@link DataAccessException} logged with {@link #MSG_UNABLE_VERIFY} and re-thrown ({@code 500})</td></tr>
 * </table>
 *
 * <h2>BCrypt upgrade and case-insensitivity parity (Decision Log D-002 / Constraint C-003)</h2>
 * <p>The legacy comparison {@code IF SEC-USR-PWD = WS-USER-PWD} operated on values that
 * {@code COSGN00C} had already upper-cased ({@code FUNCTION UPPER-CASE} at COBOL L132 and L135), so
 * the plaintext compare was effectively <strong>case-insensitive</strong>. To preserve that exact
 * behaviour after upgrading plaintext passwords to BCrypt hashes, this service upper-cases the
 * presented password (using {@link Locale#ROOT} to avoid locale-dependent case folding) <em>before</em>
 * calling {@link PasswordEncoder#matches(CharSequence, String)}. As a direct consequence, the stored
 * hash must itself be a BCrypt hash of the <strong>upper-cased</strong> password: the Flyway
 * {@code V3__seed_data.sql} seed (and any future user-enrollment path) must hash the upper-cased
 * value, otherwise verification of a correct password would fail. This coordination requirement and
 * the rationale for the BCrypt upgrade are recorded in {@code docs/decision-log.md} (D-002); it is
 * not restated as rationale in code comments.</p>
 *
 * <h2>Security invariants</h2>
 * <ul>
 *   <li>The password, the stored BCrypt hash, and the issued JWT are <strong>never</strong> logged
 *       or otherwise emitted (AAP &sect;0.8.1). Only non-sensitive values (user id, mapped role,
 *       and coarse failure categories) appear in diagnostics.</li>
 *   <li>No server-side or conversational session state is retained &mdash; the issued JWT is the
 *       entire session context (the {@code COMMAREA} replacement).</li>
 *   <li>Both "user not found" and "wrong password" surface as {@link BadCredentialsException}
 *       ({@code 401}); the distinct legacy messages are preserved for parity but the HTTP status is
 *       uniform so the boundary does not disclose which credential was wrong.</li>
 * </ul>
 *
 * <p>The service is stateless and thread-safe: all collaborators are singleton Spring beans injected
 * once at construction and never mutated.</p>
 *
 * @see JwtService
 * @see UserSecurityRepository
 * @see SignonRequest
 * @see SignonResponse
 */
@Service
public class SignonService {

    /**
     * SLF4J logger. Emits only coarse, non-sensitive diagnostics; it never records the password,
     * the stored BCrypt hash, or the issued JWT.
     */
    private static final Logger log = LoggerFactory.getLogger(SignonService.class);

    /**
     * Legacy {@code SEC-USR-TYPE} code identifying an administrator, mirroring the {@code COCOM01Y}
     * 88-level {@code CDEMO-USRTYP-ADMIN VALUE 'A'}. Any other value maps to a regular user, exactly
     * as the COBOL {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...} branch routes every non-admin to the
     * regular menu ({@code COMEN01C}).
     */
    private static final String USER_TYPE_ADMIN = "A";

    /**
     * Prompt shown when the user id is missing. Verbatim from {@code COSGN00C}
     * ({@code 'Please enter User ID ...'}); surfaced as HTTP {@code 400}.
     */
    static final String MSG_ENTER_USERID = "Please enter User ID ...";

    /**
     * Prompt shown when the password is missing. Verbatim from {@code COSGN00C}
     * ({@code 'Please enter Password ...'}); surfaced as HTTP {@code 400}.
     */
    static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /**
     * Message shown when the supplied password does not match. Verbatim from {@code COSGN00C}
     * ({@code 'Wrong Password. Try again ...'}); surfaced as HTTP {@code 401}.
     */
    static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /**
     * Message shown when no {@code USRSEC} record exists for the supplied user id (COBOL
     * {@code WHEN 13}). Verbatim from {@code COSGN00C} ({@code 'User not found. Try again ...'});
     * surfaced as HTTP {@code 401}.
     */
    static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * Message logged when the {@code USRSEC} read fails unexpectedly (COBOL {@code WHEN OTHER}).
     * Verbatim from {@code COSGN00C} ({@code 'Unable to verify the User ...'}); the underlying
     * {@link DataAccessException} is re-thrown and surfaced as HTTP {@code 500}.
     */
    static final String MSG_UNABLE_VERIFY = "Unable to verify the User ...";

    /** Repository over the migrated {@code USRSEC} store (replaces keyed VSAM access). */
    private final UserSecurityRepository userSecurityRepository;

    /** BCrypt encoder used to verify the presented password against the stored hash. */
    private final PasswordEncoder passwordEncoder;

    /** Issues the stateless JWT that replaces the CICS {@code COMMAREA} session. */
    private final JwtService jwtService;

    /**
     * Creates the sign-on service with its collaborators.
     *
     * <p>Constructor injection is used exclusively so the service is explicit about its
     * dependencies and can be instantiated directly in unit tests (with mocked collaborators)
     * without bootstrapping a Spring {@code ApplicationContext}.</p>
     *
     * @param userSecurityRepository the {@code USRSEC} repository (never {@code null})
     * @param passwordEncoder        the BCrypt password encoder bean from {@code SecurityConfig}
     *                               (never {@code null})
     * @param jwtService             the stateless JWT session service (never {@code null})
     */
    public SignonService(UserSecurityRepository userSecurityRepository,
                         PasswordEncoder passwordEncoder,
                         JwtService jwtService) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Authenticates a sign-on request and, on success, issues a JWT-bearing {@link SignonResponse}.
     *
     * <p>This is the structured Java translation of the {@code COSGN00C} {@code PROCESS-ENTER-KEY}
     * and {@code READ-USER-SEC-FILE} paragraphs; see the class-level control-flow table for the
     * exact branch-by-branch mapping. Execution order is preserved: blank-field edits first, then a
     * keyed {@code USRSEC} read, then password verification, then role-based routing.</p>
     *
     * @param request the sign-on request carrying the user id and plaintext password (never
     *                {@code null}); the password is used only in transit and is never logged
     * @return a {@link SignonResponse} containing a freshly issued bearer JWT, the echoed user id,
     *         the user's first and last names, and the mapped role
     *         ({@link SignonResponse#ROLE_ADMIN} or {@link SignonResponse#ROLE_USER})
     * @throws ValidationException     if the user id or password is missing (HTTP {@code 400});
     *                                 carries {@link #MSG_ENTER_USERID} or {@link #MSG_ENTER_PASSWORD}
     * @throws BadCredentialsException if the user is unknown or the password is wrong (HTTP
     *                                 {@code 401}); carries {@link #MSG_USER_NOT_FOUND} or
     *                                 {@link #MSG_WRONG_PASSWORD}
     * @throws DataAccessException     if the {@code USRSEC} read fails unexpectedly (COBOL
     *                                 {@code WHEN OTHER}); logged with {@link #MSG_UNABLE_VERIFY}
     */
    public SignonResponse authenticate(SignonRequest request) {
        // M10-class input-contract guard: a null request body is a broken contract,
        // surfaced as the typed HTTP-400 "enter User ID" edit rather than an
        // unhandled NullPointerException / HTTP 500 on the field dereference below.
        if (request == null) {
            throw new ValidationException(MSG_ENTER_USERID);
        }

        // COSGN00C PROCESS-ENTER-KEY: mandatory-field edits reproduce the BMS "must enter" checks.
        // A blank field is SPACES or LOW-VALUES in the legacy map; here null or whitespace-only.
        if (request.userId() == null || request.userId().isBlank()) {
            throw new ValidationException(MSG_ENTER_USERID);
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new ValidationException(MSG_ENTER_PASSWORD);
        }

        // COBOL L132/L135: FUNCTION UPPER-CASE of both fields. The user id is additionally trimmed
        // so the fixed-width, space-padded key matches the VARCHAR primary key. Locale.ROOT avoids
        // locale-sensitive case folding. Upper-casing the password preserves the legacy
        // case-insensitive compare (see class Javadoc / Decision Log D-002).
        final String userId = request.userId().trim().toUpperCase(Locale.ROOT);
        final String password = request.password().toUpperCase(Locale.ROOT);

        // COSGN00C READ-USER-SEC-FILE: keyed read of USRSEC (RIDFLD WS-USER-ID). WHEN 13 (not found)
        // and WHEN OTHER (unexpected I/O) are handled inside findUser().
        final UserSecurity user = findUser(userId);

        // WHEN 0: IF SEC-USR-PWD = WS-USER-PWD. The plaintext equality becomes a BCrypt verification
        // of the (upper-cased) presented password against the stored hash.
        if (!passwordEncoder.matches(password, user.getSecUsrPwd())) {
            throw new BadCredentialsException(MSG_WRONG_PASSWORD);
        }

        // IF CDEMO-USRTYP-ADMIN -> XCTL COADM01C (admin) ELSE XCTL COMEN01C (regular). The routing
        // target becomes the role claim carried by the stateless JWT.
        final String role = USER_TYPE_ADMIN.equals(user.getSecUsrType())
                ? SignonResponse.ROLE_ADMIN
                : SignonResponse.ROLE_USER;

        // COMMAREA replacement: the session context travels entirely inside the signed token.
        final String token = jwtService.generateToken(userId, role);

        // Audit only non-sensitive identifiers; never the password, hash, or token.
        log.info("Signon successful for userId={}, role={}", userId, role);

        return new SignonResponse(
                token,
                SignonResponse.BEARER,
                userId,
                user.getSecUsrFname(),
                user.getSecUsrLname(),
                role);
    }

    /**
     * Reads the {@code USRSEC} record for the given user id, reproducing the {@code EVALUATE
     * WS-RESP-CD} dispatch of {@code READ-USER-SEC-FILE}.
     *
     * <ul>
     *   <li>{@code WHEN 0} (found) &rarr; returns the record.</li>
     *   <li>{@code WHEN 13} (not found) &rarr; throws {@link BadCredentialsException}
     *       ({@link #MSG_USER_NOT_FOUND}).</li>
     *   <li>{@code WHEN OTHER} (unexpected I/O) &rarr; the {@link DataAccessException} thrown by the
     *       repository is logged with {@link #MSG_UNABLE_VERIFY} (no sensitive detail) and re-thrown
     *       so it surfaces as a {@code 500}. The not-found branch is deliberately kept outside the
     *       {@code try} so a legitimate "user not found" is never misclassified as an I/O error.</li>
     * </ul>
     *
     * @param userId the normalized (trimmed, upper-cased) user id used as the primary key
     * @return the matching {@link UserSecurity} record
     * @throws BadCredentialsException if no record exists for {@code userId}
     * @throws DataAccessException     if the repository read fails unexpectedly
     */
    private UserSecurity findUser(String userId) {
        final Optional<UserSecurity> found;
        try {
            found = userSecurityRepository.findById(userId);
        } catch (DataAccessException ex) {
            // COBOL WHEN OTHER: unexpected USRSEC I/O error. Log a coarse category only (class name,
            // never the message contents or any credential) and let the error surface as a 500.
            log.error("{} (userId={}): {}", MSG_UNABLE_VERIFY, userId, ex.getClass().getSimpleName());
            throw ex;
        }
        // COBOL WHEN 13: no record for this key.
        return found.orElseThrow(() -> new BadCredentialsException(MSG_USER_NOT_FOUND));
    }
}
