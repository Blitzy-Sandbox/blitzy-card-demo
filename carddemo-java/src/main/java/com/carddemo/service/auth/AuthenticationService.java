package com.carddemo.service.auth;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication (sign-on) service &mdash; the Java translation of the CardDemo CICS
 * sign-on program {@code COSGN00C} (CICS transaction {@code CC00}; source commit
 * {@code 27d6c6f}, REFERENCE ONLY &mdash; the COBOL is not copied or transcribed).
 *
 * <p>This service is the authentication entry point of the migrated application. It
 * reproduces the two business paragraphs {@code PROCESS-ENTER-KEY} (COSGN00C L108-140)
 * and {@code READ-USER-SEC-FILE} (COSGN00C L209-257): it validates that a user id and a
 * password were entered, normalizes both to upper case (faithful to the COBOL
 * {@code FUNCTION UPPER-CASE} moves and the upper-case USRSEC key), looks up the USRSEC
 * record by id, verifies the password, and returns the authenticated principal.</p>
 *
 * <p><strong>What is intentionally NOT here.</strong> The COBOL {@code MAIN-PARA}
 * {@code EIBAID}/{@code EIBCALEN} screen dispatch, the PF3 "thank you" path, the
 * invalid-key handling, the {@code RECEIVE}/{@code SEND MAP} screen I/O, and the
 * {@code XCTL} menu routing (ADMIN&rarr;{@code COADM01C}, USER&rarr;{@code COMEN01C}) are
 * CICS pseudo-conversational web-layer concerns. They are deliberately excluded from this
 * stateless service: PF-key/AID handling maps to REST endpoint routing, and no
 * {@code CARDDEMO-COMMAREA} conversational state is held on the server. This service does
 * not issue a JWT and does not route; instead it surfaces the authenticated
 * {@link UserSecurity} so the downstream web/security layer can read
 * {@link UserSecurity#getSecUsrType()} for role-based routing and stateless JWT claims.</p>
 *
 * <p><strong>Security (constraint C-003).</strong> The original COBOL compared the
 * plaintext {@code SEC-USR-PWD} field directly. This migration upgrades that to BCrypt
 * verification through the injected {@link PasswordEncoder} while preserving the original
 * login flow. The raw password, the normalized password, and the stored BCrypt hash are
 * never logged; only the normalized user id and the authentication outcome are recorded.</p>
 *
 * <p>The service is stateless and holds no mutable state, so a single Spring-managed
 * singleton instance is safe to share across concurrent requests.</p>
 */
@Service
public class AuthenticationService {

    /**
     * Logger for authentication outcomes. Records non-sensitive context only (the
     * normalized user id and the success/failure outcome); correlation ids are supplied
     * by the MDC correlation-id filter. Never logs the password or the BCrypt hash.
     */
    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * Validation message for a missing user id. Preserved verbatim from COSGN00C L120
     * ({@code MOVE 'Please enter User ID ...' TO WS-MESSAGE}) for 100% behavioral parity.
     */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /**
     * Validation message for a missing password. Preserved verbatim from COSGN00C L125
     * ({@code MOVE 'Please enter Password ...' TO WS-MESSAGE}) for 100% behavioral parity.
     */
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /**
     * Not-found message for an unknown user id. Preserved verbatim from COSGN00C L249
     * ({@code MOVE 'User not found. Try again ...' TO WS-MESSAGE}, the {@code WHEN 13}
     * branch) for 100% behavioral parity.
     */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * Wrong-password message. Preserved verbatim from COSGN00C L242
     * ({@code MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE}) for 100% behavioral parity.
     */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** Repository for keyed USRSEC lookups (re-platforms {@code EXEC CICS READ DATASET(USRSEC)}). */
    private final UserSecurityRepository userSecurityRepository;

    /** BCrypt password encoder (the {@code SecurityConfig} bean) used to verify the entered password. */
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the authentication service with its two collaborators.
     *
     * @param userSecurityRepository repository for keyed USRSEC lookups
     * @param passwordEncoder        BCrypt encoder used to verify the entered password against the stored hash
     */
    public AuthenticationService(UserSecurityRepository userSecurityRepository,
                                 PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Authenticates a user. Java translation of {@code COSGN00C.PROCESS-ENTER-KEY}
     * followed by {@code READ-USER-SEC-FILE}, preserving the original COBOL evaluation
     * order, normalization, and literal messages exactly.
     *
     * <p>Processing steps (mirroring the COBOL control flow):</p>
     * <ol>
     *   <li>Blank-input checks in COBOL order &mdash; user id first, then password
     *       ({@code EVALUATE TRUE}, COSGN00C L117-130).</li>
     *   <li>Normalization to upper case &mdash; the id is trimmed and upper-cased to form
     *       the USRSEC key, the password is upper-cased (not trimmed)
     *       ({@code FUNCTION UPPER-CASE}, COSGN00C L132-136).</li>
     *   <li>USRSEC lookup by user id ({@code EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID)},
     *       COSGN00C L211-219).</li>
     *   <li>No record found &rarr; {@link RecordNotFoundException} ({@code WHEN 13}, COSGN00C L247-251).</li>
     *   <li>BCrypt password verification ({@code WHEN 0} &rarr; {@code IF SEC-USR-PWD = WS-USER-PWD},
     *       COSGN00C L222-246); mismatch &rarr; {@link ValidationException}.</li>
     *   <li>Success &rarr; return the principal whose {@link UserSecurity#getSecUsrType()}
     *       drives downstream routing and JWT claims ({@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE},
     *       COSGN00C L227).</li>
     * </ol>
     *
     * <p>Annotated {@code @Transactional(readOnly = true)} because the source paragraph only
     * READs USRSEC; the marker is harmless and idiomatic for a read-only lookup.</p>
     *
     * <p>The COBOL {@code WHEN OTHER} branch ("Unable to verify the User ...", COSGN00C
     * L252-256) is not modeled explicitly: an unexpected infrastructure failure surfaces as a
     * Spring {@code DataAccessException} from the repository and propagates to the global
     * exception handler.</p>
     *
     * @param userId      the entered user id (COSGN00 {@code USERIDI}); compared case-insensitively
     * @param rawPassword the entered plaintext password (COSGN00 {@code PASSWDI}); never logged
     * @return the authenticated {@link UserSecurity} principal, carrying the user type
     * @throws ValidationException     if the user id or password is blank, or the password does not match
     * @throws RecordNotFoundException if no USRSEC record exists for the (normalized) user id
     */
    @Transactional(readOnly = true)
    public UserSecurity authenticate(String userId, String rawPassword) {
        // PROCESS-ENTER-KEY: EVALUATE TRUE blank checks; preserve COBOL order (user id, then password).
        if (userId == null || userId.isBlank()) {
            throw new ValidationException(MSG_ENTER_USER_ID);
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new ValidationException(MSG_ENTER_PASSWORD);
        }

        // FUNCTION UPPER-CASE(USERIDI) -> WS-USER-ID (USRSEC key); FUNCTION UPPER-CASE(PASSWDI) -> WS-USER-PWD.
        // Locale.ROOT avoids locale-sensitive casing (e.g. the Turkish dotless-i) for a deterministic key.
        String normalizedUserId = userId.trim().toUpperCase(Locale.ROOT);
        String normalizedPassword = rawPassword.toUpperCase(Locale.ROOT);

        // READ-USER-SEC-FILE: EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID).
        Optional<UserSecurity> found = userSecurityRepository.findBySecUsrId(normalizedUserId);
        if (found.isEmpty()) {
            // WHEN 13 (NOTFND): user id has no USRSEC record.
            log.warn("Authentication failed: user not found for id={}", normalizedUserId);
            throw new RecordNotFoundException(MSG_USER_NOT_FOUND);
        }

        UserSecurity user = found.get();

        // WHEN 0: IF SEC-USR-PWD = WS-USER-PWD -> BCrypt verification (C-003); never compare plaintext.
        if (!passwordEncoder.matches(normalizedPassword, user.getSecUsrPwd())) {
            log.warn("Authentication failed: wrong password for id={}", normalizedUserId);
            throw new ValidationException(MSG_WRONG_PASSWORD);
        }

        // Success: MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE; the type drives downstream routing / JWT claims.
        log.info("Authentication succeeded for id={} type={}", normalizedUserId, user.getSecUsrType());
        return user;
    }
}
