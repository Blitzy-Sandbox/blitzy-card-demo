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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.domain.UserSecurity;
import com.awsm2.carddemo.dto.SignonRequestDto;
import com.awsm2.carddemo.dto.SignonResponseDto;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import com.awsm2.carddemo.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sign-on / authentication service &mdash; the Java target for the
 * COBOL/CICS program {@code app/cbl/COSGN00C.cbl} (CICS transaction id
 * {@code 'CC00'}, file {@code 'USRSEC'}).
 *
 * <p>This service authenticates a user against the {@code user_security}
 * table (which replaces the COBOL VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}) and, on success, mints a
 * signed JWT bearer token for use by all downstream REST endpoints. It
 * preserves the COBOL signon program's externally observable contract
 * exactly while upgrading the credential check from plaintext comparison
 * to BCrypt hashing per AAP &sect;0.1.1 and &sect;0.7.1 (deliberate
 * PCI-DSS-aligned security improvement within the scope of the migration).
 *
 * <h2>COBOL provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>Program:</b> {@code app/cbl/COSGN00C.cbl} (TRANID {@code 'CC00'};
 *       reads dataset {@code 'USRSEC'} keyed by {@code WS-USER-ID}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COSGN00.bms} (mapset
 *       {@code COSGN00}, map {@code COSGN0A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COSGN00.CPY} (lines
 *       67&ndash;78 define {@code USERIDI PIC X(8)} and
 *       {@code PASSWDI PIC X(8)}).</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CSUSR01Y.cpy} (80-byte
 *       {@code SEC-USER-DATA} record &mdash; mapped to JPA entity
 *       {@link UserSecurity}).</li>
 *   <li><b>COMMAREA:</b> {@code app/cpy/COCOM01Y.cpy}
 *       ({@code CARDDEMO-COMMAREA} carries
 *       {@code CDEMO-USER-ID}/{@code CDEMO-USER-TYPE} for downstream
 *       transactions &mdash; replaced by the JWT claim set in the Java
 *       target).</li>
 * </ul>
 *
 * <h2>Verbatim COBOL semantics preserved</h2>
 * <p>The {@code PROCESS-ENTER-KEY} paragraph (COSGN00C.cbl lines
 * 108&ndash;140) implements the following control flow:
 * <pre>
 *     EVALUATE TRUE
 *         WHEN USERIDI = SPACES OR LOW-VALUES
 *             MOVE "Please enter User ID ..." TO ERRMSGO
 *         WHEN PASSWDI = SPACES OR LOW-VALUES
 *             MOVE "Please enter Password ..." TO ERRMSGO
 *         WHEN OTHER
 *             CONTINUE
 *     END-EVALUATE.
 *
 *     MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID
 *                                                     CDEMO-USER-ID
 *     MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO WS-USER-PWD
 *
 *     IF NOT ERR-FLG-ON
 *         PERFORM READ-USER-SEC-FILE
 *     END-IF.
 * </pre>
 * <p>And {@code READ-USER-SEC-FILE} (lines 207&ndash;257) implements:
 * <pre>
 *     EXEC CICS READ DATASET ('USRSEC')
 *                    INTO    (SEC-USER-DATA)
 *                    RIDFLD  (WS-USER-ID)
 *     END-EXEC.
 *
 *     EVALUATE WS-RESP-CD
 *         WHEN 0
 *             IF SEC-USR-PWD = WS-USER-PWD
 *                 IF CDEMO-USRTYP-ADMIN
 *                     EXEC CICS XCTL PROGRAM ('COADM01C') END-EXEC
 *                 ELSE
 *                     EXEC CICS XCTL PROGRAM ('COMEN01C') END-EXEC
 *                 END-IF
 *             ELSE
 *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
 *             END-IF
 *         WHEN 13   ! NOTFND
 *             MOVE 'User not found. Try again ...' TO WS-MESSAGE
 *         WHEN OTHER
 *             MOVE 'Unable to verify the User ...' TO WS-MESSAGE
 *     END-EVALUATE.
 * </pre>
 *
 * <h2>COBOL paragraph &harr; Java translation</h2>
 * <table>
 *   <caption>COSGN00C.cbl &harr; SignonService.signon(...)</caption>
 *   <tr><th>COBOL paragraph / line</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} presence guards (L118&ndash;L127)</td>
 *       <td>Jakarta Bean Validation on {@link SignonRequestDto} +
 *       explicit non-blank guard in {@link #signon(SignonRequestDto)}</td></tr>
 *   <tr><td>{@code MOVE FUNCTION UPPER-CASE(USERIDI)} (L132&ndash;L134)</td>
 *       <td>{@code request.userId().trim().toUpperCase(Locale.US)}</td></tr>
 *   <tr><td>{@code MOVE FUNCTION UPPER-CASE(PASSWDI)} (L135&ndash;L137)</td>
 *       <td><b>Deprecated as a CP5-review-mandated security upgrade.</b>
 *       The password is forwarded VERBATIM to
 *       {@code passwordEncoder.matches} &mdash; the COBOL uppercasing
 *       is NOT replicated because uppercasing collapses password
 *       entropy and is incompatible with the BCrypt verbatim-encode
 *       contract enforced by {@code UserAddService} and
 *       {@code UserUpdateService}. The V015 seed migration uses
 *       all-uppercase literals ("PASSWORDA" / "PASSWORDU") so seeded
 *       credentials still authenticate against the literal value.</td></tr>
 *   <tr><td>{@code PERFORM READ-USER-SEC-FILE} +
 *       {@code EXEC CICS READ DATASET('USRSEC')} (L209&ndash;L219)</td>
 *       <td>{@link UserSecurityRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code WS-RESP-CD = 13} (NOTFND) &rarr;
 *       "User not found ..." (L247&ndash;L251)</td>
 *       <td>{@link ValidationException} with generic
 *       "Invalid credentials" message &rarr; HTTP 400 via
 *       {@code GlobalExceptionHandler} (identical response shape to
 *       the bad-password path to prevent user-enumeration via
 *       differential status codes per QA finding CR-04). Even
 *       though the user is not present, the service still pays the
 *       BCrypt verification cost against a dummy hash to equalize
 *       timing per QA finding CR-15.</td></tr>
 *   <tr><td>{@code IF SEC-USR-PWD = WS-USER-PWD} (L223)</td>
 *       <td>{@link PasswordEncoder#matches(CharSequence, String)}
 *       (BCrypt strength 12 verification)</td></tr>
 *   <tr><td>{@code ELSE MOVE 'Wrong Password' TO WS-MESSAGE}
 *       (L242&ndash;L244)</td>
 *       <td>{@link ValidationException} with generic
 *       "Invalid credentials" message (identical to user-not-found path
 *       to prevent enumeration)</td></tr>
 *   <tr><td>{@code MOVE WS-USER-ID TO CDEMO-USER-ID} +
 *       {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} (L224&ndash;L228)</td>
 *       <td>{@link JwtTokenProvider#issueToken(String, String, String, String)}
 *       embeds {@code sub} + {@code userType} + display-name claims</td></tr>
 *   <tr><td>{@code EXEC CICS XCTL PROGRAM('COADM01C' | 'COMEN01C')}
 *       (L231&ndash;L240)</td>
 *       <td>Caller-side routing on
 *       {@link SignonResponseDto#userType()}: the client reads the
 *       userType field returned in the response and routes to the
 *       admin menu ('A') or main menu ('U') &mdash; the JWT carries
 *       the same value as the {@code role} claim for server-side
 *       enforcement via {@code @PreAuthorize}</td></tr>
 * </table>
 *
 * <h2>Security upgrades (AAP &sect;0.1.1 / &sect;0.6.6 / &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>Plaintext &rarr; BCrypt:</b> the COBOL plaintext password
 *       compare ({@code IF SEC-USR-PWD = WS-USER-PWD} on L223) is
 *       upgraded to BCrypt strength 12 hashing via
 *       {@link PasswordEncoder#matches(CharSequence, String)}. The
 *       seeded {@code user_security.sec_usr_pwd} column holds a
 *       60-character BCrypt hash whose verification incorporates the
 *       per-record salt and is computationally infeasible to reverse.
 *       The V015 seed migration ({@code V015__seed_default_users.sql})
 *       stores BCrypt hashes derived from the all-uppercase plaintexts
 *       ("PASSWORDA" / "PASSWORDU"), so the seeded credentials
 *       authenticate against the literal value supplied by the caller
 *       (no service-side uppercasing applied &mdash; per CP5 review
 *       the password is passed VERBATIM to BCrypt so that the
 *       {@code UserAddService} / {@code UserUpdateService} verbatim-
 *       encode contract is honoured consistently across the three
 *       services that touch credentials).</li>
 *   <li><b>JWT bearer token replaces CICS COMMAREA:</b> the CICS
 *       pseudo-conversational COMMAREA state
 *       ({@code CARDDEMO-COMMAREA} in {@code app/cpy/COCOM01Y.cpy})
 *       is replaced by a stateless HS256-signed JWT carrying the user
 *       identity, role discriminator ({@code 'A'}/{@code 'U'}), and
 *       display name. The signing key is sourced at runtime from AWS
 *       Secrets Manager via {@link JwtTokenProvider} (AAP &sect;0.6.4
 *       &mdash; dynamic rotation without Spring Boot restart).</li>
 *   <li><b>User-enumeration hardening:</b> both the "user-not-found"
 *       and "wrong-password" paths return the generic message
 *       {@code "Invalid credentials"} so an attacker probing for valid
 *       user IDs cannot distinguish "this user does not exist" from
 *       "this user exists but the password is wrong". This goes
 *       slightly beyond the COBOL original (which surfaced "User not
 *       found ..." and "Wrong Password ..." as distinct messages on
 *       the 3270 screen), but is justified by the PCI-DSS posture
 *       mandated in AAP &sect;0.7.1 and aligns with OWASP
 *       authentication best practices.</li>
 *   <li><b>PII-safe logging:</b> the service emits log lines that
 *       reference only the (already-uppercased) user id; the password
 *       value is NEVER logged, nor is the JWT token. Per AAP
 *       &sect;0.6.6, audit emission is centralized via
 *       {@link AuditLogService#logSecurityEvent} which sanitizes
 *       payloads via its allowlist and PAN-masking regex.</li>
 * </ul>
 *
 * <h2>Thread-safety, transactions, exception translation</h2>
 *
 * <p>The service is stateless &mdash; only the constructor-injected
 * collaborators (repository, password encoder, JWT provider, audit
 * service) are held as instance fields. All public methods are
 * thread-safe.</p>
 *
 * <p>The {@link Transactional &#64;Transactional(readOnly = true)}
 * annotation declares the authentication read against the
 * {@code user_security} table as a read-only transaction. PostgreSQL
 * applies {@code READ ONLY} mode at the connection level, which permits
 * the planner to skip MVCC tuple-visibility upper-bound checks and
 * delivers slightly lower latency on the hot signon path. No write
 * operations are performed by this service &mdash; password rehashing
 * on successful login, token revocation, and similar mutable side
 * effects are deferred to other services per the one-service-per-COBOL-
 * program rule from AAP &sect;0.7.1.</p>
 *
 * <p>Domain exceptions ({@link ValidationException}) thrown from this
 * service propagate unhandled through the controller layer and are
 * caught by the application's {@code @RestControllerAdvice}
 * {@code GlobalExceptionHandler}, which translates them into the
 * standardized JSON error envelope and the appropriate HTTP status
 * code (uniformly HTTP 400 for both the user-not-found and the
 * bad-password paths per QA finding CR-04). This preserves the
 * layered architecture mandate from AAP &sect;0.3.3.</p>
 *
 * @see com.awsm2.carddemo.repository.UserSecurityRepository
 * @see com.awsm2.carddemo.security.JwtTokenProvider
 * @see com.awsm2.carddemo.dto.SignonRequestDto
 * @see com.awsm2.carddemo.dto.SignonResponseDto
 * @see com.awsm2.carddemo.adapter.AuditLogService
 */
// Replaces: COBOL COSGN00C.cbl (TRANID 'CC00', file 'USRSEC') signon program.
@Service
public class SignonService {

    /**
     * Class-level SLF4J logger emitting structured operational events.
     * Routed through Logback + logstash-logback-encoder to CloudWatch
     * Logs per AAP &sect;0.6.6 observability.
     *
     * <p><b>CRITICAL PCI-DSS DISCIPLINE:</b> this logger MUST NEVER emit
     * {@code request.password()}, the stored BCrypt hash
     * {@code user.getSecUsrPwd()}, or the issued JWT token at any
     * level (INFO, DEBUG, WARN, ERROR). Only the (already-uppercased)
     * user id, user type, and event type may appear in log lines. The
     * audit pipeline ({@link AuditLogService#logSecurityEvent})
     * similarly sanitizes payloads via its built-in allowlist and
     * PAN-masking regex.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SignonService.class);

    /**
     * Audit event type for {@code AuditLogService.logSecurityEvent} on
     * a successful signon. The eventType discriminator drives metric
     * dimensions ({@code carddemo.audit.security{event_type="SIGNON_SUCCESS"}})
     * and OpenSearch index field, enabling failure-rate alarms.
     */
    private static final String EVENT_TYPE_SIGNON_SUCCESS = "SIGNON_SUCCESS";

    /**
     * Audit event type for {@code AuditLogService.logSecurityEvent} on
     * a failed signon. Both "user not found" and "wrong password"
     * failures share the same event type so the security-operations
     * dashboard surfaces all signon failures as a single metric;
     * the {@code result} dimension (passed alongside) carries the
     * specific failure reason ({@value #RESULT_USER_NOT_FOUND} or
     * {@value #RESULT_BAD_PASSWORD}).
     */
    private static final String EVENT_TYPE_SIGNON_FAILURE = "SIGNON_FAILURE";

    /**
     * Audit result tag indicating successful authentication. Aligned
     * with {@code AuditLogService.logSecurityEvent}'s {@code result}
     * parameter convention ({@code "SUCCESS"} / {@code "FAILURE"}).
     */
    private static final String RESULT_SUCCESS = "SUCCESS";

    /**
     * Audit result tag indicating the supplied user id was not found
     * in {@code user_security}. Maps to the COBOL signon failure
     * surfaced on line 249 of COSGN00C.cbl
     * ({@code MOVE "User not found ..." TO WS-MESSAGE}) when
     * {@code WS-RESP-CD = 13}.
     */
    private static final String RESULT_USER_NOT_FOUND = "USER_NOT_FOUND";

    /**
     * Audit result tag indicating the supplied password did not match
     * the stored BCrypt hash. Maps to the COBOL signon failure
     * surfaced on line 242 of COSGN00C.cbl
     * ({@code MOVE "Wrong Password ..." TO WS-MESSAGE}) when
     * {@code SEC-USR-PWD &ne; WS-USER-PWD}.
     */
    private static final String RESULT_BAD_PASSWORD = "BAD_PASSWORD";

    /**
     * Generic error message returned for BOTH "user not found" and
     * "wrong password" failure paths. This is a deliberate security
     * hardening that goes slightly beyond the COBOL original (which
     * distinguished "User not found ..." from "Wrong Password ...")
     * to prevent user-enumeration attacks. Per OWASP authentication
     * guidance and PCI-DSS posture in AAP &sect;0.7.1, signon
     * failures MUST NOT leak whether the user id exists in the
     * directory.
     */
    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid credentials";

    /**
     * Pre-computed BCrypt(v2a, strength=12) hash used solely to
     * equalize the BCrypt verification timing on the "user not found"
     * failure path. The dummy plaintext that produced this hash is
     * intentionally a long, non-guessable sentinel value that no
     * caller-supplied password could ever match.
     *
     * <p>Per QA finding CR-15, an earlier revision of
     * {@link #signon(SignonRequestDto)} short-circuited on
     * {@code Optional.orElseThrow()} when the user id was not found,
     * skipping the BCrypt verification entirely. That produced a
     * ~3 ms response for unknown user ids vs. ~350 ms for known ids
     * &mdash; a 100&times; timing difference that allowed user-id
     * enumeration without parsing the response body. The fix
     * unconditionally invokes
     * {@code passwordEncoder.matches(rawPassword, hash)} on EVERY
     * signon attempt: with the real hash when the user is found, or
     * with this dummy hash when the user is not found. Either way,
     * the BCrypt key-expansion cost (2<sup>12</sup> = 4096 rounds at
     * strength&nbsp;12) is paid before the {@link ValidationException}
     * is thrown, equalizing the timing.</p>
     *
     * <p>The dummy hash is a <em>known-correct</em> BCrypt-12 hash so
     * that {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder#matches}
     * actually performs the full key-expansion (it short-circuits to
     * {@code false} only when the stored hash is structurally
     * invalid, which would itself produce a timing signal). The
     * plaintext that hashes to this value is the literal
     * {@code "dummy_password_for_timing_equalization"} &mdash; clearly
     * not a user-supplied password, so the {@code matches} call
     * always returns {@code false}.</p>
     *
     * <p>This is a security-only constant, not a credential; it
     * appears in source code intentionally and does not authenticate
     * any user.</p>
     */
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$12$JpaB9NA3eyzsJxIkUAoYAedW8wOrp3rto3xiZVM9L8zAFhXJqeB0K";

    /**
     * Spring Data JPA repository over the {@code user_security} table
     * (replaces the COBOL VSAM KSDS cluster
     * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}). Injected via
     * constructor (final field) per AAP &sect;0.3.3 dependency
     * injection mandate. The repository's {@code findById(String)}
     * method maps to the COBOL
     * {@code EXEC CICS READ FILE('USRSEC') RIDFLD(WS-USER-ID)}
     * pattern from {@code COSGN00C.READ-USER-SEC-FILE} (line 211).
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * BCrypt-backed password encoder bean produced by
     * {@code BCryptPasswordEncoderBean.passwordEncoder()}. The
     * {@link PasswordEncoder} interface (rather than the concrete
     * BCrypt implementation) decouples this service from the
     * underlying hashing algorithm; a future migration to Argon2 or
     * a delegating encoder requires no source-code change in this
     * service. Used to verify the supplied (uppercased) password
     * against the stored BCrypt hash on
     * {@link UserSecurity#getSecUsrPwd()}.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * JWT issuer / verifier (replaces CICS COMMAREA pseudo-
     * conversational state per AAP &sect;0.1.1). On successful
     * authentication, this service calls
     * {@link JwtTokenProvider#issueToken(String, String, String, String)}
     * to mint the HS256-signed token returned in
     * {@link SignonResponseDto}, embedding {@code userType} so
     * downstream endpoints can authorize by {@code 'A'} (admin) vs
     * {@code 'U'} (user) via {@code @PreAuthorize}.
     */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Centralized audit/observability adapter. This service calls
     * {@link AuditLogService#logSecurityEvent} for both failure
     * paths ({@link #RESULT_USER_NOT_FOUND},
     * {@link #RESULT_BAD_PASSWORD}) and the success path
     * ({@link #EVENT_TYPE_SIGNON_SUCCESS} with userType in payload);
     * the adapter writes immutable security events to OpenSearch +
     * CloudWatch metrics per AAP &sect;0.6.6. The audit emission is
     * {@code @Async} on the adapter side, so it does not block
     * signon latency.
     */
    private final AuditLogService auditLogService;

    /**
     * Constructor injection of the four collaborators per AAP
     * &sect;0.3.3 (mandatory constructor injection &mdash; no
     * {@code @Autowired} field injection allowed). All four arguments
     * are required and validated non-null via
     * {@link Objects#requireNonNull(Object, String)} so a
     * misconfigured bean container fails fast at startup rather than
     * NPE-ing at request-time.
     *
     * @param userSecurityRepository Spring Data JPA repository over
     *                               the {@code user_security} table;
     *                               must not be {@code null}
     * @param passwordEncoder        Spring Security
     *                               {@link PasswordEncoder} for
     *                               BCrypt hash verification; must
     *                               not be {@code null}
     * @param jwtTokenProvider       HS256-signed JWT bearer-token
     *                               issuer; must not be {@code null}
     * @param auditLogService        AWS CloudWatch + OpenSearch
     *                               audit emission adapter; must not
     *                               be {@code null}
     */
    public SignonService(
            UserSecurityRepository userSecurityRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            AuditLogService auditLogService) {
        // Replaces: implicit CICS dependency injection via PROGRAM-ID +
        //           DFHCOMMAREA in COSGN00C.cbl. Constructor injection
        //           is mandated by AAP §0.3.3 (Dependency Injection —
        //           constructor injection for all @Service beans).
        this.userSecurityRepository = Objects.requireNonNull(
                userSecurityRepository, "userSecurityRepository");
        this.passwordEncoder = Objects.requireNonNull(
                passwordEncoder, "passwordEncoder");
        this.jwtTokenProvider = Objects.requireNonNull(
                jwtTokenProvider, "jwtTokenProvider");
        this.auditLogService = Objects.requireNonNull(
                auditLogService, "auditLogService");
    }

    // =========================================================================
    // Public API — schema-required signon(SignonRequestDto)
    // =========================================================================

    /**
     * Authenticates a sign-on request and returns a
     * {@link SignonResponseDto} carrying a freshly-issued JWT bearer
     * token plus the user-identity attributes needed for client-side
     * routing.
     *
     * <p>Flow (matches COSGN00C verbatim where the COBOL semantics
     * survive the migration target, with the explicit PCI-DSS
     * upgrades noted):</p>
     * <ol>
     *   <li><b>Validate inputs</b> &mdash; non-null DTO, non-blank
     *       {@code userId}, non-blank {@code password}. Replicates
     *       the COBOL {@code WHEN USERIDI = SPACES OR LOW-VALUES} /
     *       {@code WHEN PASSWDI = SPACES OR LOW-VALUES} guards at
     *       COSGN00C.cbl L118&ndash;L127. Throws
     *       {@link ValidationException} on failure (HTTP 400 via
     *       {@code GlobalExceptionHandler}).</li>
     *   <li><b>Uppercase BOTH userId and password</b> &mdash; verbatim
     *       COBOL {@code MOVE FUNCTION UPPER-CASE(...)} at
     *       COSGN00C.cbl L132&ndash;L137. CRITICAL: the uppercase
     *       step MUST occur BEFORE
     *       {@link PasswordEncoder#matches(CharSequence, String)}
     *       because the V015 seed migration stores BCrypt hashes of
     *       the <i>uppercased</i> plaintext; comparing the raw input
     *       against an uppercase-hashed value silently fails.
     *       {@link Locale#US} is used to avoid locale-sensitive
     *       Turkish-I issues that would corrupt the comparison.</li>
     *   <li><b>Lookup USRSEC</b> &mdash; replaces COBOL
     *       {@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)}
     *       with {@link UserSecurityRepository#findById(Object)}. The
     *       lookup populates an {@link Optional} but does <i>not</i>
     *       short-circuit on a miss &mdash; the next step pays the
     *       BCrypt verification cost unconditionally to equalize
     *       response timing between known and unknown userIds per
     *       QA finding CR-15.</li>
     *   <li><b>Verify password</b> &mdash; BCrypt
     *       {@link PasswordEncoder#matches(CharSequence, String)}
     *       replaces the COBOL plaintext compare on L223
     *       ({@code IF SEC-USR-PWD = WS-USER-PWD}). When the user
     *       exists, the stored hash is used. When the user does NOT
     *       exist, a precomputed dummy BCrypt-12 hash is used so the
     *       key-expansion cost is still paid (CR-15 timing
     *       equalization). EITHER a missing user OR a mismatching
     *       password results in a {@link ValidationException} with
     *       the generic "Invalid credentials" message &rarr; HTTP 400.
     *       The two failure modes produce a byte-identical HTTP
     *       response (status, code, message) so no enumeration is
     *       possible via differential analysis (CR-04 fix).</li>
     *   <li><b>Issue JWT</b> &mdash; replaces COBOL
     *       {@code MOVE ... TO CDEMO-USER-ID / CDEMO-USER-TYPE} +
     *       {@code EXEC CICS XCTL} with
     *       {@link JwtTokenProvider#issueToken(String, String, String, String)}.
     *       The token's {@code sub} claim is the userId (from
     *       {@link UserSecurity#getSecUsrId()}); the {@code userType}
     *       claim carries {@code 'A'}/{@code 'U'} for downstream
     *       authorization.</li>
     *   <li><b>Audit success</b> &mdash; emit a
     *       {@link AuditLogService#logSecurityEvent} event with
     *       {@link #EVENT_TYPE_SIGNON_SUCCESS} +
     *       {@link #RESULT_SUCCESS} + userType in the payload.</li>
     *   <li><b>Return response</b> &mdash; build a
     *       {@link SignonResponseDto} carrying (token, userId,
     *       firstName, lastName, userType, expiresAt). The
     *       expiresAt is computed as
     *       {@code Instant.now().plus(jwtTokenProvider.getExpiration()).getEpochSecond()}
     *       so clients can refresh the token proactively before it
     *       expires.</li>
     * </ol>
     *
     * <p><b>Authority Routing Reminder.</b> The COBOL
     * {@code EXEC CICS XCTL PROGRAM('COADM01C' | 'COMEN01C')} dispatch
     * (COSGN00C.cbl L231&ndash;L239) is replaced by <i>client-side</i>
     * routing keyed off {@link SignonResponseDto#userType()}. The
     * server-side enforcement of that distinction lives in
     * {@code SecurityConfig} + {@code @PreAuthorize} on the menu
     * endpoints, not in this service.</p>
     *
     * @param request the validated sign-on request DTO carrying the
     *                operator-supplied {@code userId} and
     *                {@code password}; must not be {@code null}
     * @return the signon response DTO with token, user identity, role
     *         discriminator, and expiry; never {@code null}
     * @throws ValidationException when (a) the request DTO is
     *                             {@code null}, or (b) userId or
     *                             password is null/blank, or (c) the
     *                             (uppercased) userId is not present
     *                             in the {@code user_security} table,
     *                             or (d) the supplied password does
     *                             not match the stored BCrypt hash.
     *                             All paths return HTTP 400 with the
     *                             generic {@code "Invalid credentials"}
     *                             message via
     *                             {@code GlobalExceptionHandler} so
     *                             that no enumeration is possible via
     *                             differential analysis of status
     *                             code, body, or response timing
     *                             (CR-04, CR-15).
     */
    @Transactional(readOnly = true)
    public SignonResponseDto signon(SignonRequestDto request) {
        // -------------------------------------------------------------
        // STEP 1 — Validate inputs (COBOL: COSGN00C:PROCESS-ENTER-KEY
        //          L118-L127 — empty checks on USERIDI / PASSWDI)
        // -------------------------------------------------------------
        // The Jakarta Bean Validation annotations on SignonRequestDto
        // (@NotBlank, @Size, @Pattern) catch these at the controller
        // boundary. This defensive guard handles the case where the
        // service is invoked directly (e.g., from a unit test or an
        // internal call) without controller-level validation and
        // ensures we never reach the upper-case / BCrypt steps with
        // null inputs.
        if (request == null) {
            // Audit emission is skipped here because we have no userId
            // to log. This branch should never fire in production
            // (controller layer prevents null bodies) but exists as a
            // last-line-of-defense per AAP §0.7.1.
            throw new ValidationException("Request must not be null");
        }
        if (request.userId() == null || request.userId().isBlank()) {
            // COBOL: COSGN00C.cbl L118 — WHEN USERIDI = SPACES OR LOW-VALUES
            //        MOVE 'Please enter User ID ...' TO WS-MESSAGE
            throw new ValidationException("User ID must not be empty");
        }
        if (request.password() == null || request.password().isBlank()) {
            // COBOL: COSGN00C.cbl L123 — WHEN PASSWDI = SPACES OR LOW-VALUES
            //        MOVE 'Please enter Password ...' TO WS-MESSAGE
            throw new ValidationException("Password must not be empty");
        }

        // -------------------------------------------------------------
        // STEP 2 — Normalize the userId ONLY (not the password)
        //          (COBOL parity: COSGN00C:L132-135 FUNCTION UPPER-CASE on
        //           USERIDI; per CP5 review the password is NOT uppercased
        //           before BCrypt match)
        // -------------------------------------------------------------
        // COBOL: COSGN00C lines 132-137 originally applied FUNCTION
        //        UPPER-CASE to BOTH USERIDI and PASSWDI before authenticating.
        //        The Java target preserves the userId uppercasing (the
        //        USRSEC key is uppercase-canonical per app/cpy/CSUSR01Y.cpy
        //        and matching seed data) but DEPRECATES the password
        //        uppercasing as a CP5-review-mandated security upgrade:
        //          1. Password entropy MUST be preserved when BCrypt is
        //             used. Uppercasing collapses the password space
        //             (mixed-case passwords would hash equivalently to
        //             their uppercase variants), which defeats the
        //             BCrypt strength-12 upgrade introduced by AAP §0.7.1.
        //          2. UserAddService (COUSR01C) and UserUpdateService
        //             (COUSR02C) hash the request password VERBATIM via
        //             passwordEncoder.encode(request.password()) — without
        //             uppercasing. Uppercasing on signon while preserving
        //             entropy on add/update produced a cross-service
        //             defect where any admin-created or admin-updated
        //             mixed-case password failed to authenticate. The
        //             policy is therefore unified across the three services:
        //             NORMALIZE USER IDS, NEVER PASSWORDS.
        //          3. The V015 seed migration BCrypt hashes are derived
        //             from the all-UPPERCASE plaintexts "PASSWORDA" and
        //             "PASSWORDU"; those literal values themselves are
        //             already uppercase, so signon with the literal
        //             plaintext value continues to succeed regardless of
        //             the (now-removed) uppercasing step.
        //
        // Locale.US is used explicitly to avoid locale-sensitive case folding
        // on the userId (e.g., the Turkish locale lowercases 'I' to
        // dotless-i which would produce mismatched uppercase output and
        // break the USRSEC primary-key lookup).
        // .trim() on userId only — the BMS field is space-padded on the
        // 3270 device. The password is forwarded VERBATIM to BCrypt;
        // any leading/trailing whitespace in the password is intentionally
        // preserved (matching the COBOL PIC X(08) byte-literal semantics
        // and the UserAddService/UserUpdateService verbatim encode contract).
        final String normalizedUserId = request.userId().trim().toUpperCase(Locale.US);

        LOG.info("Signon attempt for userId={}", normalizedUserId);

        // -------------------------------------------------------------
        // STEP 3 — Lookup USRSEC (COBOL: COSGN00C:READ-USER-SEC-FILE
        //          L209-L219 — EXEC CICS READ DATASET('USRSEC'))
        // -------------------------------------------------------------
        // Maps to: EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)
        //          INTO(SEC-USER-DATA) — COSGN00C.cbl L211-L219.
        //
        // Per QA finding CR-04, the user-not-found and bad-password
        // paths MUST surface IDENTICAL HTTP responses to prevent
        // user-enumeration via differential status codes. Earlier the
        // not-found path threw RecordNotFoundException (HTTP 404 with
        // code="UserSecurity") and the bad-password path threw
        // ValidationException (HTTP 400 with code="VALIDATION") —
        // attackers could probe arbitrary userIds and read the status
        // code to learn which users exist. The fix below collapses
        // both paths into a single ValidationException with the
        // generic "Invalid credentials" message — the HTTP response
        // shape (status, code, message) is byte-identical regardless
        // of which side of the predicate failed.
        //
        // Per QA finding CR-15, the not-found path MUST also pay the
        // BCrypt verification cost to prevent enumeration via response
        // TIMING (BCrypt strength-12 verification takes ~350 ms; a
        // not-found path that short-circuits on Optional.empty
        // responds in ~3 ms — a 100x signal). The fix below resolves
        // the user-or-dummy-hash up front, ALWAYS invokes
        // passwordEncoder.matches() with that hash, and only after
        // the BCrypt cost is paid does the predicate ('user is
        // present AND matches returned true') decide whether to
        // proceed. The audit emission still distinguishes the
        // RESULT_USER_NOT_FOUND vs. RESULT_BAD_PASSWORD reason
        // internally for operations dashboards — those reason codes
        // never leak to the HTTP response body.
        final Optional<UserSecurity> userOpt = userSecurityRepository.findById(normalizedUserId);
        // Resolve the BCrypt hash to verify against: the real stored
        // hash when the user exists, or the timing-equalization
        // dummy when the user does not. Both are valid BCrypt-12
        // hashes so passwordEncoder.matches() performs the full
        // key-expansion in either branch.
        final String hashToVerify = userOpt
                .map(UserSecurity::getSecUsrPwd)
                .orElse(DUMMY_BCRYPT_HASH);

        // -------------------------------------------------------------
        // STEP 4 — Verify password via BCrypt
        //          (COBOL: COSGN00C.cbl L223 — IF SEC-USR-PWD = WS-USER-PWD)
        // -------------------------------------------------------------
        // COBOL: COSGN00C.cbl L223 plaintext byte-equality compare.
        // Java:  PasswordEncoder.matches(rawPassword, encodedPassword)
        //        — BCrypt strength 12 verification incorporates the per-
        //        record salt embedded in the stored hash. The compare
        //        is constant-time (BCrypt's design) so timing attacks
        //        cannot distinguish "wrong password" from "right password"
        //        based on response latency on the per-call path.
        //
        // Per CP5 review: pass the VERBATIM request.password() into
        // BCrypt.matches() — never an uppercased copy. The cross-service
        // contract is: UserAddService and UserUpdateService encode the
        // password verbatim, so signon MUST match against the verbatim
        // value. The V015 seed migration uses the 8-char literals
        // ("PASSWDA1"/"PASSWDU1") per CR-01 so the seeded credentials
        // authenticate when the caller types those exact values.
        final boolean passwordMatches = passwordEncoder.matches(
                request.password(), hashToVerify);

        // Decide outcome AFTER the BCrypt cost has been paid. The
        // audit reason code distinguishes the failure mode for
        // operations dashboards, but the HTTP response is identical
        // (single ValidationException → HTTP 400 with the generic
        // "Invalid credentials" message and code="VALIDATION") so
        // there is no enumeration signal on either status or body.
        if (userOpt.isEmpty()) {
            // COBOL: COSGN00C.cbl L247-L251 — WS-RESP-CD = 13 path.
            //        The COBOL message was "User not found ..."; the
            //        Java target uses the generic "Invalid credentials"
            //        message and the same HTTP 400 status code as the
            //        bad-password path (CR-04 fix).
            auditFailure(normalizedUserId, RESULT_USER_NOT_FOUND);
            LOG.info("Signon failed: user not found userId={}", normalizedUserId);
            throw new ValidationException(INVALID_CREDENTIALS_MESSAGE);
        }
        if (!passwordMatches) {
            // COBOL: COSGN00C.cbl L242-L244 — "Wrong Password" path.
            //        The COBOL message was "Wrong Password. Try again ...";
            //        the Java target uses the same generic
            //        "Invalid credentials" message as the user-not-found
            //        path so the HTTP response shape is identical between
            //        the two failure modes (prevents enumeration via
            //        differential error analysis — CR-04 fix).
            auditFailure(normalizedUserId, RESULT_BAD_PASSWORD);
            LOG.info("Signon failed: bad password userId={}", normalizedUserId);
            throw new ValidationException(INVALID_CREDENTIALS_MESSAGE);
        }
        // Both predicates passed: user exists and password matches.
        final UserSecurity user = userOpt.get();

        // -------------------------------------------------------------
        // STEP 5 — Issue JWT (COBOL: COSGN00C:ROUTE-BY-USRTYPE
        //          L222-L240 — MOVE to CDEMO-* + EXEC CICS XCTL)
        // -------------------------------------------------------------
        // COBOL: COSGN00C.cbl L222-L240 — on successful READ USRSEC +
        //        password match, COSGN00C populates CDEMO-USER-ID,
        //        CDEMO-USER-TYPE in CARDDEMO-COMMAREA and then issues
        //        EXEC CICS XCTL PROGRAM('COADM01C') for admins or
        //        EXEC CICS XCTL PROGRAM('COMEN01C') for regular users.
        //
        // Java equivalent: mint a signed JWT carrying those identity
        // claims (sub=userId, userType=A/U, firstName, lastName). The
        // CICS-managed COMMAREA persistence is replaced by the
        // cryptographically signed bearer token returned in
        // SignonResponseDto. Server-side authorization is enforced by
        // SecurityConfig + @PreAuthorize on the menu endpoints based on
        // the userType claim. Client-side routing (admin menu vs main
        // menu) is performed by inspecting SignonResponseDto.userType().
        //
        // JwtTokenProvider.issueToken signature:
        //   issueToken(userId, userType, firstName, lastName) -> String
        final String token = jwtTokenProvider.issueToken(
                user.getSecUsrId(),
                user.getSecUsrType(),
                user.getSecUsrFname(),
                user.getSecUsrLname());

        // -------------------------------------------------------------
        // STEP 6 — Compute expiresAt for the response DTO
        // -------------------------------------------------------------
        // SignonResponseDto.expiresAt is a Long (seconds since
        // 1970-01-01 UTC), matching the JWT 'exp' claim. We compute it
        // as Instant.now() + jwtTokenProvider.getExpiration() so the
        // client can refresh the token proactively before expiry. This
        // value has no COBOL analogue — it is a structural requirement
        // of the stateless REST model that has no equivalent in the
        // CICS pseudo-conversational model (where the session was
        // kept alive implicitly by CARDDEMO-COMMAREA chaining across
        // EXEC CICS RETURN TRANSID('CC00') calls).
        final long expiresAt = Instant.now()
                .plus(jwtTokenProvider.getExpiration())
                .getEpochSecond();

        // -------------------------------------------------------------
        // STEP 7 — Audit success (AAP §0.6.6 — immutable security
        //          events to OpenSearch + CloudWatch metrics)
        // -------------------------------------------------------------
        // Replaces: COBOL has no explicit signon audit (the only trace
        //           is the XCTL itself, recorded in CICS auxtrace).
        //           The Java target emits an explicit security audit
        //           event so signon success/failure rates can be
        //           alarmed in CloudWatch and queried in OpenSearch
        //           Dashboards for fraud investigation.
        auditSuccess(normalizedUserId, user.getSecUsrType());
        LOG.info("Signon success userId={} userType={}",
                user.getSecUsrId(), user.getSecUsrType());

        // -------------------------------------------------------------
        // STEP 8 — Return response (NO password / hash / cleartext
        //          credential material — PCI-DSS per AAP §0.7.1)
        // -------------------------------------------------------------
        // SignonResponseDto constructor (record canonical order):
        //   (token, userId, firstName, lastName, userType, expiresAt)
        // The response DTO explicitly OMITS the password / hash per
        // PCI-DSS rules in AAP §0.7.1; the SignonResponseDto record
        // does not declare a password component at all, making any
        // accidental inclusion a compile error.
        return new SignonResponseDto(
                token,
                user.getSecUsrId(),
                user.getSecUsrFname(),
                user.getSecUsrLname(),
                user.getSecUsrType(),
                expiresAt);
    }

    // =========================================================================
    // Audit helpers (PCI-DSS — never include password material in payloads)
    // =========================================================================

    /**
     * Emits a successful-signon audit event via
     * {@link AuditLogService#logSecurityEvent}. The userType is
     * included in the payload (non-sensitive operational identifier;
     * appears in audit trails per AAP &sect;0.6.6) but the password
     * value is intentionally NEVER referenced anywhere in this method
     * or any downstream call site.
     *
     * <p>The audit emission is {@code @Async} on the adapter side, so
     * this call returns immediately and does not block the signon
     * latency on OpenSearch indexing.</p>
     *
     * @param userId   the (already-uppercased) user identifier
     * @param userType the role discriminator ({@code 'A'} for admin,
     *                 {@code 'U'} for user) from
     *                 {@link UserSecurity#getSecUsrType()}
     */
    private void auditSuccess(String userId, String userType) {
        // Map.of(...) creates an immutable map literal — preferred over
        // a mutable HashMap for static payloads. The userType is the
        // only non-identity field needed for fraud-investigation
        // dashboards; the userId is passed as the second positional
        // argument to logSecurityEvent.
        // sourceIp is null — the service layer does not have access to
        // the HTTP request context; if a future requirement adds IP
        // capture, the controller can propagate it through a parameter
        // or a request-scoped bean.
        // correlationId is null — MDC-derived correlation is captured
        // by the audit adapter from the current thread's MDC context.
        auditLogService.logSecurityEvent(
                EVENT_TYPE_SIGNON_SUCCESS,
                userId,
                RESULT_SUCCESS,
                null,
                Map.of("userType", userType),
                null);
    }

    /**
     * Emits a failed-signon audit event via
     * {@link AuditLogService#logSecurityEvent}. The reason
     * ({@link #RESULT_USER_NOT_FOUND} or {@link #RESULT_BAD_PASSWORD})
     * is passed as the {@code result} dimension so the security
     * dashboard can show failure-rate breakdowns by cause. The
     * payload is empty &mdash; we never include the (raw or
     * uppercased) password value, and we already convey userId as
     * the second positional argument.
     *
     * <p>The audit emission is {@code @Async} on the adapter side, so
     * this call returns immediately and the subsequent exception
     * throw is not delayed by OpenSearch indexing.</p>
     *
     * @param userId the (already-uppercased) user identifier
     * @param reason the failure reason discriminator
     *               ({@link #RESULT_USER_NOT_FOUND} or
     *               {@link #RESULT_BAD_PASSWORD})
     */
    private void auditFailure(String userId, String reason) {
        // Map.of() — empty immutable map. We deliberately do NOT include
        // any password-related field in the payload, even masked, to
        // align with the strict PCI-DSS posture in AAP §0.7.1 (no
        // credential material in audit logs).
        auditLogService.logSecurityEvent(
                EVENT_TYPE_SIGNON_FAILURE,
                userId,
                reason,
                null,
                Map.of(),
                null);
    }
}
