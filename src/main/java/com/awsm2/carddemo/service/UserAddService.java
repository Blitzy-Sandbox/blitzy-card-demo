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
import com.awsm2.carddemo.dto.UserAddDto;
import com.awsm2.carddemo.exception.DuplicateRecordException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Admin user-add service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COUSR01C.cbl} (CICS transaction id {@code CU01}).
 *
 * <p>This service creates a new user row in the {@code USRSEC} dataset.
 * In the COBOL source, {@code COUSR01C.cbl} performs the pseudo-conversational
 * flow:
 * <ol>
 *   <li>{@code SEND MAP COUSR1A} &mdash; renders a blank entry form with
 *       five operator-editable fields (FNAME, LNAME, USERID, PASSWD,
 *       USRTYPE).</li>
 *   <li>{@code RECEIVE MAP COUSR1A} &mdash; reads operator input into
 *       {@code COUSR1AI}.</li>
 *   <li>{@code PROCESS-ENTER-KEY} (lines 115&ndash;160) &mdash; cascades
 *       through {@code EVALUATE TRUE} checking each field for
 *       {@code SPACES OR LOW-VALUES} in the order
 *       FNAME &rarr; LNAME &rarr; USERID &rarr; PASSWD &rarr; USRTYPE.
 *       The COBOL semantic is FIRST-ERROR-WINS &mdash; the cascade exits
 *       at the first failure and re-sends the map with the failing
 *       field's error message.</li>
 *   <li>{@code WRITE-USER-SEC-FILE} (lines 238&ndash;274) &mdash;
 *       moves the five values into {@code SEC-USER-DATA} and issues
 *       {@code EXEC CICS WRITE FILE('USRSEC') FROM(SEC-USER-DATA)
 *       RIDFLD(SEC-USR-ID)}. A {@code DFHRESP(DUPKEY)} /
 *       {@code DFHRESP(DUPREC)} response (FILE STATUS '22') triggers
 *       the {@code "User ID already exist..."} message.</li>
 * </ol>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COUSR01C.cbl} (CICS TRANID
 *       {@code 'CU01'}, file {@code 'USRSEC'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COUSR01.bms} (mapset
 *       {@code COUSR01}, map {@code COUSR1A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COUSR01.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes) &mdash; mapped to JPA entity
 *       {@link UserSecurity}.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}
 *       (KEYS(8 0), RECORDSIZE(80 80)) &mdash; replaced by the
 *       PostgreSQL {@code user_security} table (Flyway V010).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COUSR01C.cbl &harr; UserAddService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (lines 115&ndash;160)
 *       &mdash; field-presence cascade</td>
 *       <td>{@link #addUser(UserAddDto)} via repeated
 *       {@link #requireNonBlank(String, String)} calls in COBOL field
 *       order</td></tr>
 *   <tr><td>{@code MOVE FUNCTION UPPER-CASE(USERIDI) TO SEC-USR-ID}</td>
 *       <td>{@code request.userId().trim().toUpperCase(Locale.US)}</td></tr>
 *   <tr><td>userType domain check (BMS map literal "(A=Admin, U=User)")</td>
 *       <td>{@link #VALID_USER_TYPES} allowlist + {@link ValidationException}</td></tr>
 *   <tr><td>{@code WRITE-USER-SEC-FILE} &mdash; {@code WHEN DFHRESP(DUPKEY)
 *       WHEN DFHRESP(DUPREC)} (FILE STATUS '22')</td>
 *       <td>{@link UserSecurityRepository#existsById(Object)} &rarr;
 *       {@link DuplicateRecordException} (HTTP 409)</td></tr>
 *   <tr><td>{@code MOVE PASSWDI TO SEC-USR-PWD} (plaintext)</td>
 *       <td>{@link PasswordEncoder#encode(CharSequence)} (BCrypt
 *       strength 12 per AAP &sect;0.1.1 / &sect;0.7.1)</td></tr>
 *   <tr><td>{@code WRITE-USER-SEC-FILE} &mdash; successful
 *       {@code EXEC CICS WRITE} (DFHRESP(NORMAL))</td>
 *       <td>{@link UserSecurityRepository#save(Object)}</td></tr>
 *   <tr><td>CICS transactional boundary (implicit task SYNCPOINT)</td>
 *       <td>{@link Transactional @Transactional(rollbackFor =
 *       Exception.class, isolation = Isolation.READ_COMMITTED)}</td></tr>
 * </table>
 *
 * <h2>SECURITY UPGRADE &mdash; plaintext &rarr; BCrypt (AAP &sect;0.1.1, &sect;0.7.1)</h2>
 *
 * <p>The COBOL source stores the password verbatim in
 * {@code SEC-USR-PWD PIC X(08)} &mdash; 8 plaintext characters
 * fixed-width inside the 80-byte SEC-USER-DATA record. This service
 * <b>hashes the password with BCrypt (cost factor 12)</b> via the
 * injected {@link PasswordEncoder} (configured by
 * {@code com.awsm2.carddemo.security.BCryptPasswordEncoderBean}) before
 * persisting; the stored value is a 60-character BCrypt hash, never
 * the plaintext.
 *
 * <p>This is a deliberate, AAP-mandated security improvement within the
 * scope of the COBOL &rarr; Java migration (AAP &sect;0.1.1
 * "Plaintext password storage in the source USRSEC file must be
 * upgraded to BCrypt hashing in the Java target &mdash; a deliberate
 * security improvement within the scope of PCI-DSS compliance"). The
 * Flyway migration {@code V010__create_user_security.sql} widens
 * {@code sec_usr_pwd} from {@code VARCHAR(8)} to {@code VARCHAR(60)} to
 * fit the BCrypt output:
 * <pre>
 *     $2[abxy]$&lt;cost&gt;$&lt;22-char-salt&gt;&lt;31-char-hash&gt;  = 60 chars total
 * </pre>
 * Verification flows (e.g., {@code SignonService}) call
 * {@link PasswordEncoder#matches(CharSequence, String)} which compares
 * the user-supplied plaintext against the stored hash without ever
 * exposing the plaintext.
 *
 * <h2>User-ID and User-Type normalization</h2>
 *
 * <p>The COBOL source's BMS map declares {@code USERID PIC X(08)} and
 * {@code USRTYPE PIC X(01)} as fixed-length fields with implicit
 * uppercase convention (the COBOL signon program {@code COSGN00C.cbl}
 * uppercases the userId via {@code FUNCTION UPPER-CASE} before the
 * VSAM key lookup). The Java target uppercases BOTH {@code userId} AND
 * {@code userType} using {@link Locale#US} explicitly &mdash; this
 * defeats the locale-sensitive Turkish-locale {@code 'I' &rarr; 'ı'}
 * bug that would silently corrupt the primary-key index in PostgreSQL.
 * Uppercase normalization on this write path is critical because all
 * subsequent {@code SignonService} lookups will normalize the supplied
 * userId the same way; mismatched casing would break authentication.
 *
 * <h2>userType domain constraint</h2>
 *
 * <p>The COBOL source defers userType validation to operator discipline
 * (the {@code COUSR01.bms} map literal {@code (A=Admin, U=User)} at
 * line 150 of the BMS source documents the convention but no COBOL
 * code enforces it). This service applies the binary value-domain
 * check via the {@link #VALID_USER_TYPES} allowlist before save
 * &mdash; consistent with the {@code chk_user_security_type
 * CHECK (sec_usr_type IN ('A', 'U'))} defense-in-depth constraint in
 * Flyway V010 (per AAP &sect;0.4.1 "Spring upgrade").
 *
 * <h2>Validation cascade semantics (matches COBOL EVALUATE TRUE)</h2>
 *
 * <p>The COBOL {@code PROCESS-ENTER-KEY} paragraph uses
 * {@code EVALUATE TRUE} which is a FIRST-MATCH-WINS construct: the
 * cascade exits at the first WHEN clause that matches and re-sends the
 * map with the failing field's error message. This service's
 * {@link #requireNonBlank(String, String)} helper preserves that
 * semantic by throwing on the first failure rather than collecting all
 * errors &mdash; matching COBOL behavior exactly.
 *
 * <h2>Response DTO &mdash; password redaction (PCI-DSS, AAP &sect;0.6.6)</h2>
 *
 * <p>The {@link UserAddDto} carries the password as a WRITE_ONLY field
 * (annotated {@code accessMode = Schema.AccessMode.WRITE_ONLY} on the
 * DTO) &mdash; the request-side plaintext value must NEVER appear on
 * any response payload. This service returns a new
 * {@link UserAddDto} with the password component set to {@code null}
 * so that JSON serialization renders it as {@code "password": null}
 * and the BCrypt hash never crosses the wire.
 *
 * <h2>Audit (PCI-DSS, AAP &sect;0.6.6)</h2>
 *
 * <p>After a successful user creation, this service emits a security
 * audit event via {@link AuditLogService#logSecurityEvent} with event
 * type {@code "USER_ADD"} and outcome {@code "SUCCESS"}. The payload
 * carries the userId and userType but the password is NEVER passed to
 * the audit log (PCI-DSS Requirement 10 + the AAP &sect;0.6.6 PCI-DSS
 * posture mandate that no credential material appears in audit
 * trails). The audit log adapter additionally sanitizes any payload it
 * receives via {@link AuditLogService#sanitizePayload(Map)} as a
 * defense-in-depth measure.
 *
 * @see UserSecurityRepository
 * @see UserAddDto
 * @see PasswordEncoder
 * @see AuditLogService
 */
@Service
public class UserAddService {

    /**
     * SLF4J logger emitting traceability events (user-add attempts,
     * duplicate detections, success indicators). NEVER carries
     * password content &mdash; the password and its hash are
     * explicitly excluded from every log message per the PCI-DSS
     * posture in AAP &sect;0.6.6.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UserAddService.class);

    /**
     * Allowlist of valid {@code userType} values matching the COBOL
     * {@code COUSR01.bms} map literal {@code (A=Admin, U=User)} at
     * line 150 of the BMS source. Used by {@link #addUser(UserAddDto)}
     * to enforce the binary value domain.
     *
     * <ul>
     *   <li>{@code "A"} &mdash; ADMIN: routes to the admin menu
     *       (COBOL {@code COADM01C}; Java {@code MenuController}
     *       {@code GET /api/menu/admin}).</li>
     *   <li>{@code "U"} &mdash; USER: routes to the main menu
     *       (COBOL {@code COMEN01C}; Java {@code MenuController}
     *       {@code GET /api/menu/main}).</li>
     * </ul>
     *
     * <p>{@link Set#of(Object, Object)} returns an immutable set so
     * the allowlist cannot be tampered with at runtime &mdash; an
     * attacker who somehow gained reflection access cannot widen the
     * accepted values to include {@code "S"} (sysadmin) or
     * {@code "*"} (wildcard) without modifying the bytecode.
     */
    private static final Set<String> VALID_USER_TYPES = Set.of("A", "U");

    /**
     * Audit event type discriminator for user-add events. Indexed
     * into the {@code carddemo-security} OpenSearch index by
     * {@link AuditLogService#logSecurityEvent} and exposed as the
     * {@code event_type} tag on the {@code carddemo.audit.security}
     * Micrometer counter (per AAP &sect;0.6.6 audit pipeline).
     */
    private static final String AUDIT_EVENT_TYPE = "USER_ADD";

    /**
     * Audit outcome marker emitted on the success path. Preserved as
     * a metric tag on the {@code carddemo.audit.security} counter so
     * failure-rate alarms (AAP &sect;0.6.6 observability) can filter
     * {@code result=SUCCESS} from {@code result=FAILURE}.
     */
    private static final String AUDIT_RESULT_SUCCESS = "SUCCESS";

    /**
     * Spring Data JPA repository for the {@code user_security} table
     * &mdash; replaces VSAM cluster
     * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}. Used for the
     * duplicate-key pre-check (via {@link UserSecurityRepository#existsById})
     * and the create operation (via {@link UserSecurityRepository#save}).
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Spring Security password encoder (BCrypt strength 12, configured
     * by {@code com.awsm2.carddemo.security.BCryptPasswordEncoderBean}).
     * Used to hash the plaintext password from {@link UserAddDto}
     * before persistence (AAP &sect;0.1.1 / &sect;0.7.1 PCI-DSS
     * upgrade from the COBOL plaintext storage pattern).
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * PCI-DSS / SOX audit adapter that emits a structured security
     * event to OpenSearch + CloudWatch after a successful user
     * creation (AAP &sect;0.6.6). The password value is NEVER passed
     * to the audit log.
     */
    private final AuditLogService auditLogService;

    /**
     * Constructor injection per AAP &sect;0.7.1 ("Dependency
     * injection for loose coupling"). All collaborators must be
     * non-{@code null} &mdash; Spring DI normally guarantees this,
     * but the explicit {@link Objects#requireNonNull(Object, String)}
     * guards protect unit tests, ad-hoc instantiations, and
     * bean-definition errors.
     *
     * @param userSecurityRepository the Spring Data JPA repository
     *                               for {@code user_security}; never
     *                               {@code null}
     * @param passwordEncoder        the BCrypt password encoder
     *                               (strength 12); never {@code null}
     * @param auditLogService        the PCI-DSS audit adapter; never
     *                               {@code null}
     */
    public UserAddService(UserSecurityRepository userSecurityRepository,
                          PasswordEncoder passwordEncoder,
                          AuditLogService auditLogService) {
        this.userSecurityRepository = Objects.requireNonNull(userSecurityRepository,
                "userSecurityRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder,
                "passwordEncoder must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    /**
     * Adds a new user to the {@code user_security} table.
     *
     * <p>Flow (matches COUSR01C PROCESS-ENTER-KEY &rarr;
     * WRITE-USER-SEC-FILE):</p>
     * <ol>
     *   <li>Validate all five inputs non-blank (first-error-wins
     *       cascade matching COBOL {@code EVALUATE TRUE}).</li>
     *   <li>Normalize {@code userId} and {@code userType} to
     *       uppercase using {@link Locale#US}.</li>
     *   <li>Validate {@code userType} &isin; {@code {"A","U"}}.</li>
     *   <li>Pre-check for duplicate user-id via
     *       {@link UserSecurityRepository#existsById(Object)} &mdash;
     *       throws {@link DuplicateRecordException} (HTTP 409) on
     *       conflict (replaces COBOL {@code DFHRESP(DUPKEY)} /
     *       FILE STATUS '22').</li>
     *   <li>BCrypt-hash the password (security upgrade per AAP
     *       &sect;0.1.1).</li>
     *   <li>Build a {@link UserSecurity} entity and persist via
     *       {@link UserSecurityRepository#save(Object)}.</li>
     *   <li>Emit a security audit event via
     *       {@link AuditLogService#logSecurityEvent}; the password is
     *       NEVER passed to the audit log.</li>
     *   <li>Return the persisted DTO with the password component set
     *       to {@code null} so the response payload contains no
     *       credential material.</li>
     * </ol>
     *
     * <p>The method is annotated
     * {@link Transactional @Transactional(rollbackFor = Exception.class,
     * isolation = Isolation.READ_COMMITTED)} so the {@code existsById}
     * + {@code save} + audit sequence runs atomically within a single
     * RDS PostgreSQL transaction with {@code READ_COMMITTED}
     * isolation. On any exception the new {@link UserSecurity} row is
     * rolled back, replicating COBOL {@code SYNCPOINT} /
     * {@code SYNCPOINT ROLLBACK} semantics per AAP &sect;0.7.1
     * transactional integrity rules.
     *
     * @param request the validated add request (operator-supplied
     *                fields from the legacy COUSR1A BMS map)
     * @return a {@link UserAddDto} carrying the persisted userId,
     *         firstName, lastName, and userType with
     *         {@code password = null} (the password component is
     *         redacted before return; the BCrypt hash never crosses
     *         the wire)
     * @throws ValidationException      if any of the five fields is
     *                                  null or blank, or if
     *                                  {@code userType} is not in
     *                                  {@link #VALID_USER_TYPES}
     *                                  (HTTP 400 via
     *                                  {@code GlobalExceptionHandler})
     * @throws DuplicateRecordException if a user with the normalized
     *                                  {@code userId} already exists
     *                                  (HTTP 409 via
     *                                  {@code GlobalExceptionHandler})
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public UserAddDto addUser(UserAddDto request) {
        // Defensive null guard — Spring controllers normally enforce
        // non-null bodies via @Valid @RequestBody, but a service
        // boundary must remain safe under direct invocation (unit
        // tests, programmatic callers, batch importers, etc.).
        Objects.requireNonNull(request, "request must not be null");

        // ============================================================
        // COBOL: COUSR01C:VALIDATE-FIELDS (PROCESS-ENTER-KEY paragraph,
        // lines 115–151 of app/cbl/COUSR01C.cbl)
        // ------------------------------------------------------------
        // COBOL semantic: EVALUATE TRUE WHEN <field> = SPACES OR
        // LOW-VALUES — first-error-wins cascade. Field order in COBOL:
        // FNAME → LNAME → USERID → PASSWD → USRTYPE. Each WHEN clause
        // emits a specific error message and re-sends the map; only
        // the FIRST failing field is reported per request.
        // ============================================================
        requireNonBlank(request.firstName(), "First Name");
        requireNonBlank(request.lastName(), "Last Name");
        requireNonBlank(request.userId(), "User ID");
        requireNonBlank(request.password(), "Password");
        requireNonBlank(request.userType(), "User Type");

        // ============================================================
        // COBOL: MOVE FUNCTION UPPER-CASE(USERIDI) TO SEC-USR-ID
        // ------------------------------------------------------------
        // USRSEC keys are uppercase by convention (COBOL COSGN00C
        // normalizes userId to upper-case before the VSAM READ).
        // Locale.US ensures deterministic ASCII-based case folding —
        // critical to avoid the Turkish-locale 'I' → 'ı' bug that
        // would silently corrupt the primary-key index.
        // ============================================================
        String normalizedUserId = request.userId().trim().toUpperCase(Locale.US);

        // ============================================================
        // userType normalization + domain check
        // ------------------------------------------------------------
        // BMS map literal "(A=Admin, U=User)" at line 150 of
        // app/bms/COUSR01.bms documents the allowed values. The
        // COBOL source defers enforcement to operator discipline;
        // the Java target enforces the allowlist explicitly to
        // satisfy the V010 chk_user_security_type CHECK constraint
        // (sec_usr_type IN ('A','U')) at the application boundary.
        // ============================================================
        String userType = request.userType().trim().toUpperCase(Locale.US);
        if (!VALID_USER_TYPES.contains(userType)) {
            throw new ValidationException(
                    "User Type must be 'A' (admin) or 'U' (user)");
        }

        LOG.info("UserAddService.addUser attempting to create user userId={} userType={}",
                normalizedUserId, userType);

        // ============================================================
        // COBOL: COUSR01C:WRITE-USER-SEC-FILE — DUPKEY pre-check
        // (lines 240–266 of app/cbl/COUSR01C.cbl)
        // ------------------------------------------------------------
        // COBOL semantic: EXEC CICS WRITE returns DFHRESP(DUPKEY) or
        // DFHRESP(DUPREC) when SEC-USR-ID already exists; the program
        // emits "User ID already exist..." to ERRMSGO. The Java
        // target performs an existsById pre-check before save so the
        // 409 response carries a descriptive message without relying
        // on PostgreSQL to flag the unique-constraint violation.
        // ============================================================
        if (userSecurityRepository.existsById(normalizedUserId)) {
            LOG.warn("UserAddService.addUser DUPLICATE detected userId={}", normalizedUserId);
            throw new DuplicateRecordException(
                    "DUPLICATE_USER",
                    "User already exists: " + normalizedUserId);
        }

        // ============================================================
        // AAP §0.7.1 PCI-DSS upgrade: BCrypt-hash the password
        // ------------------------------------------------------------
        // COBOL stored the plaintext password verbatim in
        // SEC-USR-PWD PIC X(08). The Java target stores a 60-char
        // BCrypt hash (strength 12, configured in
        // BCryptPasswordEncoderBean). The plaintext value lives in
        // JVM memory only for the duration of this call and is
        // discarded after the encode invocation — never logged,
        // never persisted, never returned.
        // ============================================================
        String hashedPassword = passwordEncoder.encode(request.password());

        // ============================================================
        // COBOL: WRITE-USER-SEC-FILE — INITIALIZE SEC-USER-DATA +
        // MOVE BMS fields into the record layout (lines 152–159 of
        // PROCESS-ENTER-KEY)
        // ------------------------------------------------------------
        // Translates the five COBOL MOVE statements:
        //   MOVE USERIDI  OF COUSR1AI TO SEC-USR-ID
        //   MOVE FNAMEI   OF COUSR1AI TO SEC-USR-FNAME
        //   MOVE LNAMEI   OF COUSR1AI TO SEC-USR-LNAME
        //   MOVE PASSWDI  OF COUSR1AI TO SEC-USR-PWD   ← BCrypt hash, not plaintext
        //   MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE
        // ============================================================
        UserSecurity user = new UserSecurity();
        user.setSecUsrId(normalizedUserId);
        user.setSecUsrFname(request.firstName().trim());
        user.setSecUsrLname(request.lastName().trim());
        user.setSecUsrPwd(hashedPassword);
        user.setSecUsrType(userType);

        // ============================================================
        // COBOL: WRITE-USER-SEC-FILE — EXEC CICS WRITE DATASET
        // ('USRSEC') FROM(SEC-USER-DATA) (lines 240–248)
        // ------------------------------------------------------------
        // Replaces EXEC CICS WRITE with Spring Data JPA save() + flush().
        // The explicit flush is intentional for QA CR-13: when concurrent
        // POST requests race past the existsById pre-check, the database
        // primary-key violation must be raised inside this try/catch and
        // translated to DuplicateRecordException -> HTTP 409, never bubble
        // out as a raw DataAccessException -> HTTP 500.
        // ============================================================
        UserSecurity saved;
        try {
            saved = userSecurityRepository.save(user);
            userSecurityRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // COBOL: COUSR01C.cbl WRITE-USER-SEC-FILE — WHEN DFHRESP(DUPKEY)
            // / DFHRESP(DUPREC). Concurrent Java inserts can still collide
            // after the pre-check, so translate the DB-layer constraint
            // failure into the same semantic duplicate-user response.
            LOG.warn("UserAddService.addUser duplicate constraint detected userId={}",
                    normalizedUserId);
            throw new DuplicateRecordException(
                    "DUPLICATE_USER",
                    "User already exists: " + normalizedUserId,
                    ex);
        }

        // ============================================================
        // PCI-DSS audit (AAP §0.6.6) — emit USER_ADD security event
        // ------------------------------------------------------------
        // Replaces COBOL DISPLAY-based audit trails. The password is
        // NEVER included in the audit payload (defense-in-depth
        // beyond the AuditLogService.sanitizePayload allowlist that
        // would otherwise drop a "password" key). The userId and
        // userType are operational identifiers, safe to record for
        // fraud investigation and regulatory queries.
        // ============================================================
        Map<String, Object> auditPayload = new HashMap<>();
        auditPayload.put("userId", saved.getSecUsrId());
        auditPayload.put("userType", saved.getSecUsrType());
        auditLogService.logSecurityEvent(
                AUDIT_EVENT_TYPE,
                saved.getSecUsrId(),
                AUDIT_RESULT_SUCCESS,
                null,
                auditPayload,
                null);

        LOG.info("UserAddService.addUser created userId={} userType={}",
                saved.getSecUsrId(), saved.getSecUsrType());

        // ============================================================
        // Response DTO — password redacted (PCI-DSS, AAP §0.6.6)
        // ------------------------------------------------------------
        // The UserAddDto record's password component is set to null
        // so JSON serialization renders it as "password": null. The
        // BCrypt hash NEVER crosses the wire. Note that the DTO's
        // @NotBlank Bean Validation constraint is enforced on the
        // INBOUND request path only; outbound serialization does not
        // re-validate, so null is safe here. The DTO is also annotated
        // accessMode = Schema.AccessMode.WRITE_ONLY at the OpenAPI
        // layer, which signals to clients that the field is
        // request-only.
        // ============================================================
        return new UserAddDto(
                saved.getSecUsrId(),
                saved.getSecUsrFname(),
                saved.getSecUsrLname(),
                null,
                saved.getSecUsrType());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that the supplied value is non-{@code null} and not
     * blank (after trimming whitespace). Throws
     * {@link ValidationException} on failure with a message that
     * matches the COBOL {@code PROCESS-ENTER-KEY} error texts
     * (e.g., {@code "First Name must not be empty"} corresponds to
     * COBOL {@code "First Name can NOT be empty..."}).
     *
     * <p>This helper preserves the COBOL {@code EVALUATE TRUE}
     * first-match-wins semantic: a single failing field raises an
     * exception that exits the cascade. The calling code invokes
     * this helper in the COBOL field order
     * (FNAME &rarr; LNAME &rarr; USERID &rarr; PASSWD &rarr; USRTYPE)
     * so the field reported on the error response matches the
     * field the COBOL program would have flagged.
     *
     * @param v         the field value to check; tolerates
     *                  {@code null}
     * @param fieldName the human-readable field name to embed in the
     *                  error message (e.g., {@code "First Name"},
     *                  {@code "User ID"}); must not be {@code null}
     *                  itself (callers always supply a literal)
     * @throws ValidationException if {@code v} is {@code null} or
     *                             {@link String#trim() trim()}
     *                             produces an empty string
     */
    private static void requireNonBlank(String v, String fieldName) {
        if (v == null || v.trim().isEmpty()) {
            throw new ValidationException(fieldName + " must not be empty");
        }
    }
}
