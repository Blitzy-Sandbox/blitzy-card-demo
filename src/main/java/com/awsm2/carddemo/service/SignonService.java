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
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import com.awsm2.carddemo.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Sign-on / authentication service &mdash; the Java target for the
 * COBOL/CICS program {@code app/cbl/COSGN00C.cbl}
 * (CICS transaction id {@code CC00}).
 *
 * <p>This service authenticates a user against the {@code USRSEC}
 * dataset, returning a signed JWT bearer token on success. In the COBOL
 * source the program performs:
 * <ol>
 *   <li>{@code MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID}
 *       (uppercase user-id);</li>
 *   <li>{@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)};</li>
 *   <li>{@code IF SEC-USR-PWD = WS-USER-PWD} (plaintext compare);</li>
 *   <li>{@code XCTL} to {@code COMEN01C} (user) or {@code COADM01C}
 *       (admin) based on {@code SEC-USR-TYPE}.</li>
 * </ol>
 * In the Java target:
 * <ol>
 *   <li>Both {@code userId} and {@code password} are uppercased via
 *       {@link String#toUpperCase(Locale)} with {@link Locale#US} to
 *       avoid locale-sensitive Turkish-I problems (AAP &sect;0.7.1
 *       Minimal Change Clause: uppercase normalisation is preserved
 *       from the COBOL source);</li>
 *   <li>{@link UserSecurityRepository#findById(Object)} loads the
 *       {@link UserSecurity} record;</li>
 *   <li>{@link PasswordEncoder#matches(CharSequence, String)} compares
 *       the supplied plaintext against the stored BCrypt hash &mdash;
 *       the COBOL plaintext-equality is upgraded to BCrypt per
 *       AAP &sect;0.1.1 / &sect;0.7.1 (a deliberate security
 *       improvement within the scope of PCI-DSS compliance);</li>
 *   <li>{@link JwtTokenProvider#issueToken(String, String, String, String)}
 *       returns a signed JWT containing {@code userId} (subject),
 *       {@code userType} (custom claim), {@code firstName},
 *       {@code lastName} &mdash; replacing the CICS pseudo-conversational
 *       COMMAREA state with a stateless token (AAP &sect;0.1.1).</li>
 * </ol>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COSGN00C.cbl} (CICS TRANID
 *       {@code 'CC00'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COSGN00.bms} (mapset
 *       {@code COSGN00}, map {@code COSGN0A}).</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes) &mdash; mapped to JPA entity
 *       {@link UserSecurity}.</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}
 *       (KEYS(8 0), RECORDSIZE(80 80)) &mdash; replaced by the
 *       PostgreSQL {@code user_security} table created in V010 and
 *       seeded by V015.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COBOL COSGN00C.cbl &harr; SignonService.authenticate(...)</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (user/password presence checks)</td>
 *       <td>Jakarta Bean Validation on {@link SignonRequestDto} +
 *       explicit {@link #ensurePresent(String, String)} guard.</td></tr>
 *   <tr><td>{@code MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID}</td>
 *       <td>{@code userId.toUpperCase(Locale.US)}</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE}
 *       (EXEC CICS READ DATASET('USRSEC'))</td>
 *       <td>{@link UserSecurityRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code FILE STATUS 23} (NOTFND)</td>
 *       <td>{@link RecordNotFoundException} &rarr; HTTP 404 via
 *       {@code GlobalExceptionHandler}</td></tr>
 *   <tr><td>{@code IF SEC-USR-PWD = WS-USER-PWD} (plaintext)</td>
 *       <td>{@link PasswordEncoder#matches(CharSequence, String)}
 *       (BCrypt strength 12)</td></tr>
 *   <tr><td>Password mismatch &rarr; "Wrong Password" message</td>
 *       <td>{@link ValidationException} with field error on
 *       {@code password}</td></tr>
 *   <tr><td>{@code XCTL PROGRAM('COMEN01C' | 'COADM01C')}</td>
 *       <td>Caller-side routing on
 *       {@link SignonResponseDto#userType()} (the controller / client
 *       chooses the next endpoint).</td></tr>
 * </table>
 *
 * <h2>Security upgrades (AAP &sect;0.1.1 / &sect;0.6.6 / &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>Plaintext &rarr; BCrypt:</b> the COBOL plaintext password
 *       compare is upgraded to BCrypt strength 12 hashing. The seeded
 *       {@code user_security.sec_usr_pwd} column holds a 60-character
 *       BCrypt hash whose verification is performed by
 *       {@link PasswordEncoder#matches(CharSequence, String)}.</li>
 *   <li><b>JWT bearer token:</b> the CICS pseudo-conversational
 *       COMMAREA state ({@code CARDDEMO-COMMAREA} in
 *       {@code app/cpy/COCOM01Y.cpy}) is replaced by a stateless
 *       HS256-signed JWT carrying the user identity, role
 *       discriminator ({@code A}/{@code U}), and display name. The
 *       signing key is sourced at runtime from AWS Secrets Manager
 *       via {@link JwtTokenProvider} (AAP &sect;0.6.4).</li>
 *   <li><b>PII-safe logging:</b> the service emits log lines that
 *       reference only the (already-uppercased) user id; the password
 *       value is never logged, nor is the JWT hash.</li>
 * </ul>
 *
 * <h2>Thread-safety, transactions, exception translation</h2>
 *
 * <p>The service is stateless &mdash; only the constructor-injected
 * collaborators are held as instance fields. All public methods are
 * thread-safe.</p>
 *
 * <p>The {@link Transactional &#64;Transactional(readOnly = true)}
 * annotation declares the authentication read against the
 * {@code user_security} table as a read-only transaction. PostgreSQL
 * applies {@code READ ONLY} mode at the connection level, which permits
 * the planner to skip MVCC tuple-visibility upper-bound checks and
 * delivers slightly lower latency on the hot signon path. No
 * write operations are performed by this service.</p>
 *
 * @see com.awsm2.carddemo.repository.UserSecurityRepository
 * @see com.awsm2.carddemo.security.JwtTokenProvider
 * @see com.awsm2.carddemo.dto.SignonRequestDto
 * @see com.awsm2.carddemo.dto.SignonResponseDto
 */
@Service
public class SignonService {

    /** Class-level SLF4J logger &mdash; structured JSON output per AAP &sect;0.6.6. */
    private static final Logger LOG = LoggerFactory.getLogger(SignonService.class);

    /** Audit event name emitted on successful sign-on. */
    private static final String EVENT_SIGNON_SUCCESS = "auth.signon.success";

    /** Audit event name emitted on failed sign-on (any reason). */
    private static final String EVENT_SIGNON_FAILURE = "auth.signon.failure";

    /** Maximum length of the user-id field per CSUSR01Y.cpy SEC-USR-ID PIC X(08). */
    private static final int USER_ID_MAX_LENGTH = 8;

    /** Maximum length of the password field per CSUSR01Y.cpy SEC-USR-PWD PIC X(08). */
    private static final int PASSWORD_MAX_LENGTH = 8;

    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditLogService auditLogService;

    /**
     * Constructor &mdash; Spring supplies the collaborators.
     *
     * @param userSecurityRepository repository over the
     *                               {@code user_security} table; never
     *                               {@code null}
     * @param passwordEncoder        BCrypt-backed password encoder from
     *                               {@code BCryptPasswordEncoderBean};
     *                               never {@code null}
     * @param jwtTokenProvider       issuer of HS256-signed JWT tokens;
     *                               never {@code null}
     * @param auditLogService        async audit-log sink (CloudTrail /
     *                               OpenSearch); never {@code null}
     */
    public SignonService(
            UserSecurityRepository userSecurityRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            AuditLogService auditLogService) {
        this.userSecurityRepository = Objects.requireNonNull(
                userSecurityRepository, "userSecurityRepository");
        this.passwordEncoder = Objects.requireNonNull(
                passwordEncoder, "passwordEncoder");
        this.jwtTokenProvider = Objects.requireNonNull(
                jwtTokenProvider, "jwtTokenProvider");
        this.auditLogService = Objects.requireNonNull(
                auditLogService, "auditLogService");
    }

    /**
     * Authenticate a sign-on request and emit a JWT on success.
     *
     * <p>Replicates the COBOL {@code COSGN00C} {@code PROCESS-ENTER-KEY}
     * paragraph from {@code app/cbl/COSGN00C.cbl}:</p>
     * <pre>
     *     IF USERIDI = SPACES OR LOW-VALUES
     *        MOVE "Please enter User ID ..." TO ERRMSGO
     *     ELSE IF PASSWDI = SPACES OR LOW-VALUES
     *        MOVE "Please enter Password ..." TO ERRMSGO
     *     ELSE
     *        MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID
     *        PERFORM READ-USER-SEC-FILE
     *        IF WS-RESP-CD = 23
     *           MOVE "User not found ..." TO ERRMSGO
     *        ELSE IF SEC-USR-PWD &ne; WS-USER-PWD
     *           MOVE "Wrong Password ..." TO ERRMSGO
     *        ELSE
     *           IF SEC-USR-TYPE = 'A'  XCTL COADM01C
     *           ELSE                   XCTL COMEN01C
     *     END-IF
     * </pre>
     *
     * @param request the validated sign-on request DTO (Jakarta Bean
     *                Validation has already enforced presence and
     *                length constraints at the controller boundary);
     *                never {@code null}
     * @return the signon response DTO with token, user identity, and
     *         role discriminator; never {@code null}
     * @throws ValidationException     when the user-supplied password
     *                                 does not match the stored hash
     *                                 (HTTP 400)
     * @throws RecordNotFoundException when the user-id does not exist
     *                                 in {@code user_security}
     *                                 (HTTP 404; COBOL FILE STATUS 23)
     */
    @Transactional(readOnly = true)
    public SignonResponseDto authenticate(SignonRequestDto request) {
        Objects.requireNonNull(request, "request");

        // -------------------------------------------------------------
        // COBOL: PROCESS-ENTER-KEY presence guards (COSGN00C.cbl L118)
        // -------------------------------------------------------------
        // Although Jakarta Bean Validation on SignonRequestDto already
        // catches @NotBlank, this guard reproduces the source program's
        // double-check defence (the controller may be invoked without
        // validation in unit tests).
        ensurePresent(request.userId(), "userId");
        ensurePresent(request.password(), "password");

        // -------------------------------------------------------------
        // COBOL: MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID
        // -------------------------------------------------------------
        // Locale.US avoids locale-sensitive case folding (Turkish I).
        // Both fields are uppercased to match the COBOL behaviour --
        // SEC-USR-PWD in the legacy USRSEC file is stored uppercase
        // per the JCL seed.
        String userId = request.userId().toUpperCase(Locale.US);
        String password = request.password().toUpperCase(Locale.US);

        // -------------------------------------------------------------
        // COBOL: PERFORM READ-USER-SEC-FILE -> EXEC CICS READ DATASET
        // -------------------------------------------------------------
        UserSecurity user = userSecurityRepository.findById(userId)
                .orElseThrow(() -> {
                    LOG.info("Signon failed: user not found userId={}", userId);
                    auditFailure(userId, "USER_NOT_FOUND");
                    return new RecordNotFoundException(
                            "USER_NOT_FOUND",
                            "User not found");
                });

        // -------------------------------------------------------------
        // COBOL: IF SEC-USR-PWD = WS-USER-PWD (plaintext)
        // Java:  PasswordEncoder.matches(raw, hash) -- BCrypt strength 12
        // -------------------------------------------------------------
        if (!passwordEncoder.matches(password, user.getSecUsrPwd())) {
            LOG.info("Signon failed: password mismatch userId={}", userId);
            auditFailure(userId, "WRONG_PASSWORD");
            throw new ValidationException(
                    "WRONG_PASSWORD",
                    "Wrong Password",
                    List.of(new ValidationException.FieldError(
                            "password", "Wrong password supplied")));
        }

        // -------------------------------------------------------------
        // COBOL: XCTL PROGRAM('COMEN01C' | 'COADM01C') based on
        //        SEC-USR-TYPE = 'A' | 'U'. Java target: emit a JWT and
        //        let the caller route on userType.
        // -------------------------------------------------------------
        String token = jwtTokenProvider.issueToken(
                user.getSecUsrId(),
                user.getSecUsrType(),
                user.getSecUsrFname(),
                user.getSecUsrLname());

        long expiresAt = Instant.now()
                .plus(jwtTokenProvider.getExpiration())
                .getEpochSecond();

        LOG.info("Signon success userId={} userType={}",
                user.getSecUsrId(), user.getSecUsrType());
        auditSuccess(user.getSecUsrId(), user.getSecUsrType());

        return new SignonResponseDto(
                token,
                user.getSecUsrId(),
                user.getSecUsrFname(),
                user.getSecUsrLname(),
                user.getSecUsrType(),
                expiresAt);
    }

    // -----------------------------------------------------------------
    // Defensive-guard helpers (preserves COBOL PROCESS-ENTER-KEY contract)
    // -----------------------------------------------------------------

    /**
     * Ensures the value is non-{@code null}, non-empty, and within
     * the PIC X(08) length limit. Used to defend against bypasses of
     * Jakarta Bean Validation when the service is invoked directly
     * (e.g., in unit tests).
     */
    private static void ensurePresent(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(
                    "MISSING_FIELD",
                    "Please enter " + displayName(fieldName) + " ...",
                    List.of(new ValidationException.FieldError(
                            fieldName, displayName(fieldName) + " is required")));
        }
        int max = "password".equals(fieldName) ? PASSWORD_MAX_LENGTH : USER_ID_MAX_LENGTH;
        if (value.length() > max) {
            throw new ValidationException(
                    "FIELD_TOO_LONG",
                    displayName(fieldName) + " exceeds maximum length",
                    List.of(new ValidationException.FieldError(
                            fieldName,
                            displayName(fieldName) + " must be at most "
                                    + max + " characters")));
        }
    }

    /** Maps an internal field name to its COBOL display label. */
    private static String displayName(String fieldName) {
        return switch (fieldName) {
            case "userId" -> "User ID";
            case "password" -> "Password";
            default -> fieldName;
        };
    }

    // -----------------------------------------------------------------
    // Audit helpers (AAP §0.6.6 -- CloudTrail + OpenSearch sink)
    // -----------------------------------------------------------------

    private void auditSuccess(String userId, String userType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("userType", userType);
        auditLogService.auditEvent(EVENT_SIGNON_SUCCESS, userId, payload);
    }

    private void auditFailure(String userId, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("reason", reason);
        auditLogService.auditEvent(EVENT_SIGNON_FAILURE, userId, payload);
    }
}
