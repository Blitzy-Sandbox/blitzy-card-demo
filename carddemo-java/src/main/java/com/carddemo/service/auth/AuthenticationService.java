package com.carddemo.service.auth;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.UserSecurityRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication (sign-on) service. Java translation of the CardDemo CICS sign-on
 * program {@code COSGN00C} (source commit {@code 27d6c6f}); the COBOL is a behavioral
 * reference only and is not copied.
 *
 * <p>This component reproduces the two business paragraphs of the original program:
 * {@code PROCESS-ENTER-KEY} (the {@code EVALUATE TRUE} blank-input edits and the
 * {@code FUNCTION UPPER-CASE} normalization of the entered user id and password) and
 * {@code READ-USER-SEC-FILE} (the {@code EXEC CICS READ DATASET(USRSEC)} keyed lookup
 * and its {@code WHEN 0 / WHEN 13} response handling). It validates that the user id
 * and password were entered, looks up the {@code USRSEC} record by id, verifies the
 * password with BCrypt, and returns the authenticated principal.</p>
 *
 * <p>The COBOL CICS screen and AID dispatch ({@code MAIN-PARA}), the {@code RECEIVE}/
 * {@code SEND} map I/O, and the {@code XCTL} menu routing are pseudo-conversational
 * presentation concerns handled by the stateless web/security layer, so they are
 * intentionally outside this service. Role-based routing and JWT claims are derived
 * downstream from the surfaced {@link UserSecurity#getSecUsrType()} (admin vs. user);
 * no server-held {@code CARDDEMO-COMMAREA} conversational state is reproduced.</p>
 *
 * <p>The plaintext {@code USRSEC} password of the original system is replaced by a
 * BCrypt-hashed credential (constraint C-003): verification is performed exclusively
 * through {@link PasswordEncoder#matches(CharSequence, String)}, and neither the
 * plaintext password nor the stored hash is ever logged.</p>
 */
@Service
public class AuthenticationService {

    /** Structured-logging logger; emits the normalized user id and outcome only (never the password or hash). */
    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /** Validation message for a missing user id ({@code COSGN00C} "Please enter User ID ..."). */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /** Validation message for a missing password ({@code COSGN00C} "Please enter Password ..."). */
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /** Not-found message for an unknown user id ({@code COSGN00C} {@code WHEN 13} "User not found. Try again ..."). */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /** Mismatch message for a failed password check ({@code COSGN00C} "Wrong Password. Try again ..."). */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** USRSEC keyed store (re-platforms the VSAM {@code USRSEC} KSDS). */
    private final UserSecurityRepository userSecurityRepository;

    /** BCrypt password encoder; the sole {@link PasswordEncoder} bean is provided by {@code config/SecurityConfig}. */
    private final PasswordEncoder passwordEncoder;

    /**
     * Meter registry used to record the {@code carddemo.auth.attempts} counter
     * (Observability rule). Only the bounded, low-cardinality {@code outcome} tag
     * is recorded; the user id, plaintext password, and stored hash are never used
     * as tags or otherwise exposed through telemetry.
     */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the service with its collaborators.
     *
     * @param userSecurityRepository keyed access to the {@code USRSEC} user-security store
     * @param passwordEncoder        BCrypt encoder used to verify the entered password against the stored hash
     * @param meterRegistry          registry for the {@code carddemo.auth.attempts} outcome counter
     */
    public AuthenticationService(UserSecurityRepository userSecurityRepository,
                                 PasswordEncoder passwordEncoder,
                                 MeterRegistry meterRegistry) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Authenticates a user. Translation of {@code COSGN00C.PROCESS-ENTER-KEY} +
     * {@code READ-USER-SEC-FILE}.
     *
     * <p>The COBOL {@code EVALUATE} ordering is preserved exactly: the user id is
     * checked before the password, both inputs are upper-cased (the id is also trimmed,
     * mirroring the {@code USRSEC} upper-case key), the record is read by id, and the
     * BCrypt password is verified only when a record is found.</p>
     *
     * @param userId      the entered user id ({@code COSGN00} {@code USERIDI})
     * @param rawPassword the entered plaintext password ({@code COSGN00} {@code PASSWDI})
     * @return the authenticated {@link UserSecurity} principal, whose
     *         {@link UserSecurity#getSecUsrType()} drives downstream role-based routing
     * @throws ValidationException     if the user id or password is blank, or the password is wrong
     * @throws RecordNotFoundException if no {@code USRSEC} record exists for the user id
     */
    @Transactional(readOnly = true)
    public UserSecurity authenticate(String userId, String rawPassword) {
        // PROCESS-ENTER-KEY: EVALUATE TRUE blank edits (order: user id first, then password)
        if (userId == null || userId.isBlank()) {
            throw new ValidationException(MSG_ENTER_USER_ID);
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new ValidationException(MSG_ENTER_PASSWORD);
        }

        // FUNCTION UPPER-CASE(USERIDI) -> WS-USER-ID (USRSEC key); FUNCTION UPPER-CASE(PASSWDI) -> WS-USER-PWD
        String normalizedUserId = userId.trim().toUpperCase(Locale.ROOT);
        String normalizedPassword = rawPassword.toUpperCase(Locale.ROOT);

        // READ-USER-SEC-FILE: EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID)
        Optional<UserSecurity> found = userSecurityRepository.findBySecUsrId(normalizedUserId);
        if (found.isEmpty()) {
            // WHEN 13 (NOTFND)
            MetricsConfig.authAttempts(meterRegistry, MetricsConfig.OUTCOME_NOT_FOUND).increment();
            log.warn("Authentication failed: user not found for id={}", normalizedUserId);
            throw new RecordNotFoundException(MSG_USER_NOT_FOUND);
        }

        UserSecurity user = found.get();

        // WHEN 0: IF SEC-USR-PWD = WS-USER-PWD -> BCrypt verification (never plaintext)
        if (!passwordEncoder.matches(normalizedPassword, user.getSecUsrPwd())) {
            MetricsConfig.authAttempts(meterRegistry, MetricsConfig.OUTCOME_WRONG_PASSWORD).increment();
            log.warn("Authentication failed: wrong password for id={}", normalizedUserId);
            throw new ValidationException(MSG_WRONG_PASSWORD);
        }

        // Success: MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE; type drives downstream routing/JWT claims
        MetricsConfig.authAttempts(meterRegistry, MetricsConfig.OUTCOME_SUCCESS).increment();
        log.info("Authentication succeeded for id={} type={}", normalizedUserId, user.getSecUsrType());
        return user;
    }
}
