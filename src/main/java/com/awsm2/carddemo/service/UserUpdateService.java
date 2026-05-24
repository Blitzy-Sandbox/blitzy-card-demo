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
import com.awsm2.carddemo.dto.UserUpdateDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Admin user-update service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COUSR02C.cbl} (CICS transaction id
 * {@code 'CU02'}, BMS mapset {@code COUSR02}, file {@code 'USRSEC'}).
 *
 * <p>In the COBOL source, {@code COUSR02C.cbl} runs a pseudo-conversational
 * read-modify-write flow against the {@code USRSEC} VSAM KSDS:
 * <ol>
 *   <li>{@code RECEIVE MAP COUSR2A} &mdash; reads operator input
 *       (BMS fields {@code USRIDIN}, {@code FNAME}, {@code LNAME},
 *       {@code PASSWD}, {@code USRTYPE}).</li>
 *   <li>{@code PROCESS-ENTER-KEY} (L143-L172) &mdash; validates that
 *       {@code USRIDIN} is non-empty, then {@code READ-USER-SEC-FILE}
 *       loads the existing {@code SEC-USER-DATA} record keyed by
 *       {@code SEC-USR-ID} and re-displays its fields for editing.</li>
 *   <li>{@code UPDATE-USER-INFO} (L177-L245) &mdash; validates that every
 *       BMS input field is non-empty, re-reads the record, compares each
 *       input against the persisted value, sets {@code USR-MODIFIED-YES}
 *       on any difference, and (if any field changed) calls
 *       {@code UPDATE-USER-SEC-FILE} to issue
 *       {@code EXEC CICS REWRITE DATASET('USRSEC') FROM(SEC-USER-DATA)}.</li>
 *   <li>{@code UPDATE-USER-SEC-FILE} (L358-L390) &mdash; issues the
 *       REWRITE and emits the
 *       {@code "User <id> has been updated ..."} confirmation message
 *       on {@code DFHRESP(NORMAL)}, or "User ID NOT found..." on
 *       {@code DFHRESP(NOTFND)} (FILE STATUS '23').</li>
 * </ol>
 *
 * <p>The Java target reproduces this flow via:
 * <ol>
 *   <li>{@link UserSecurityRepository#findById(Object)} &mdash; replaces
 *       the CICS {@code READ USRSEC ... UPDATE} in
 *       {@code READ-USER-SEC-FILE}; a missing row surfaces as
 *       {@link RecordNotFoundException} (HTTP {@code 404}).</li>
 *   <li>Field-by-field {@code applyEdits} on the loaded
 *       {@link UserSecurity} entity &mdash; preserves the COBOL "apply
 *       only changed fields" semantic at lines L219-L234 of
 *       {@code COUSR02C.cbl}.</li>
 *   <li>{@link PasswordEncoder#encode(CharSequence)} re-hashes the
 *       plaintext password via BCrypt (strength 12) when the caller
 *       supplies a non-blank value &mdash; <b>security upgrade per AAP
 *       &sect;0.1.1 and &sect;0.7.1</b>. The COBOL source stores
 *       plaintext in {@code SEC-USR-PWD PIC X(08)}; the Java target
 *       stores the 60-character BCrypt hash in
 *       {@code user_security.sec_usr_pwd VARCHAR(60)}. When the caller
 *       omits or supplies blank, the existing hash is preserved
 *       (industry-standard REST "leave blank to keep existing" pattern
 *       documented in {@link UserUpdateDto}).</li>
 *   <li>{@link UserSecurityRepository#save(Object)} &mdash; replaces the
 *       CICS {@code REWRITE} in {@code UPDATE-USER-SEC-FILE}; rollback
 *       on any exception via the surrounding {@link Transactional}
 *       boundary (replaces the implicit CICS task-level
 *       {@code SYNCPOINT} on normal return).</li>
 *   <li>{@link AuditLogService#logSecurityEvent} &mdash; emits an
 *       application-level audit event into the OpenSearch
 *       {@code security} index and increments a Micrometer counter
 *       routed to CloudWatch. The audit payload carries the
 *       {@code passwordChanged} boolean flag so security operations can
 *       dashboard credential-rotation events separately from profile
 *       edits; <b>the password value is NEVER included in the
 *       payload</b>, satisfying the PCI-DSS log-hygiene rule in AAP
 *       &sect;0.6.6.</li>
 * </ol>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 traceability)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COUSR02C.cbl}
 *       (CICS TRANID {@code 'CU02'}, file {@code 'USRSEC'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COUSR02.bms} (mapset
 *       {@code COUSR02}, map {@code COUSR2A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COUSR02.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes) &mdash; mapped to JPA
 *       entity {@link UserSecurity}.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} (KEYS(8 0),
 *       RECORDSIZE(80 80)) &mdash; replaced by the PostgreSQL
 *       {@code user_security} table (Flyway
 *       {@code V010__create_user_security.sql}).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COUSR02C.cbl &harr; UserUpdateService</caption>
 *   <tr><th>COBOL paragraph (line range)</th>
 *       <th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (L143-L172) &mdash; userId
 *       presence check + READ-USER-SEC-FILE</td>
 *       <td>userId null/blank guard + normalization +
 *       {@link UserSecurityRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code UPDATE-USER-INFO} (L177-L245) &mdash; non-empty
 *       cascade for FNAME, LNAME, PASSWD, USRTYPE; before-image
 *       comparison; USR-MODIFIED-YES gate</td>
 *       <td>{@link #updateUser(String, UserUpdateDto)} validation
 *       block + applyEdits inline</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE} (L320-L353) &mdash; CICS READ
 *       UPDATE + DFHRESP(NOTFND) branch</td>
 *       <td>{@link UserSecurityRepository#findById(Object)} +
 *       {@link RecordNotFoundException}</td></tr>
 *   <tr><td>{@code UPDATE-USER-SEC-FILE} (L358-L390) &mdash; CICS
 *       REWRITE + DFHRESP(NORMAL) success message</td>
 *       <td>{@link UserSecurityRepository#save(Object)} +
 *       {@link AuditLogService#logSecurityEvent} emission</td></tr>
 *   <tr><td>Implicit CICS task-level SYNCPOINT on RETURN</td>
 *       <td>{@link Transactional @Transactional(rollbackFor =
 *       Exception.class, isolation = Isolation.READ_COMMITTED)}
 *       on {@link #updateUser(String, UserUpdateDto)}</td></tr>
 * </table>
 *
 * <h2>Security &amp; PCI-DSS (AAP &sect;0.1.1, &sect;0.6.6, &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>Password hashing:</b> plaintext from {@link UserUpdateDto#password()}
 *       is encoded via {@link PasswordEncoder#encode(CharSequence)} (BCrypt
 *       strength 12) before assignment to the entity. The plaintext
 *       value never reaches PostgreSQL.</li>
 *   <li><b>Audit log hygiene:</b> the audit payload carries the
 *       {@code passwordChanged} boolean flag but NEVER the password
 *       value (plaintext or hashed).</li>
 *   <li><b>Response payload hygiene:</b> the returned {@link UserUpdateDto}
 *       has its {@code password} component set to {@code null} so
 *       JSON serialization renders as {@code "password": null}. The
 *       BCrypt hash and plaintext value never cross the wire.</li>
 *   <li><b>SLF4J trace hygiene:</b> {@link Logger} statements log only
 *       the user identifier, user type, and {@code passwordChanged}
 *       boolean; the plaintext password and the hash NEVER appear in
 *       any log line per AAP &sect;0.6.6.</li>
 * </ul>
 *
 * <h2>Transactional contract</h2>
 * <p>The {@link #updateUser(String, UserUpdateDto)} method is annotated
 * with {@code @Transactional(rollbackFor = Exception.class,
 * isolation = Isolation.READ_COMMITTED)} so the entire findById +
 * applyEdits + save + audit-emission sequence runs within a single
 * RDS PostgreSQL transaction with the default isolation level. Any
 * exception (including {@link RecordNotFoundException},
 * {@link ValidationException}, or any persistence-layer fault)
 * triggers a rollback of the {@code user_security} row update. The
 * audit emission is asynchronous (via the adapter's {@code @Async}
 * annotation), so its failure cannot delay or roll back the
 * database commit.</p>
 *
 * <h2>Optional password rotation policy</h2>
 * <p>The COBOL source ({@code UPDATE-USER-INFO} L198-L203) requires
 * the {@code PASSWD} BMS field to be non-empty on every update; the
 * Java target intentionally relaxes this rule to support the
 * industry-standard REST "leave blank to keep existing" pattern
 * (per {@link UserUpdateDto} documentation). This is the only
 * behavioral deviation from the COBOL source in this service and is
 * justified by the security upgrade in AAP &sect;0.1.1 (the original
 * plaintext password storage was itself a defect; preserving the
 * "must enter password every time" UX would force operators to
 * choose between (a) constantly retyping the existing password
 * during routine name/type edits, or (b) writing the BCrypt hash
 * back to the entity, breaking authentication).</p>
 *
 * @see UserSecurityRepository
 * @see UserUpdateDto
 * @see PasswordEncoder
 * @see AuditLogService
 */
@Service
public class UserUpdateService {

    /**
     * SLF4J logger routed through Logback + logstash-logback-encoder
     * to CloudWatch Logs per AAP &sect;0.6.6. Used exclusively for
     * structured JSON log lines; never logs password values (plaintext
     * or hashed) per AAP &sect;0.7.1 PCI-DSS discipline.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UserUpdateService.class);

    /**
     * Immutable allowlist of valid {@code SEC-USR-TYPE} values per the
     * COBOL CSUSR01Y.cpy {@code SEC-USR-TYPE PIC X(01)} business rule
     * and the BMS map literal "{@code (A=Admin, U=User)}" at line 154
     * of {@code app/bms/COUSR02.bms}. Used by the userType validation
     * check in {@link #updateUser(String, UserUpdateDto)} and enforced
     * defense-in-depth by the V010 migration
     * {@code chk_user_security_type CHECK (sec_usr_type IN ('A', 'U'))}
     * constraint on the PostgreSQL table.
     */
    private static final Set<String> VALID_USER_TYPES = Set.of("A", "U");

    /**
     * Audit event type emitted to {@link AuditLogService#logSecurityEvent}
     * on every successful user update. Indexed into the OpenSearch
     * {@code security} index per AAP &sect;0.6.6 so security
     * operations can dashboard user-administration activity for
     * compliance retention and fraud investigation.
     */
    private static final String AUDIT_EVENT_TYPE = "USER_UPDATE";

    /**
     * Audit result tag indicating the update completed successfully.
     * Drives the {@code result} dimension on the
     * {@code carddemo.security.events} Micrometer counter so the
     * CloudWatch alarm on {@code result = "FAILURE"} fires only on
     * actual security-relevant failures (not on routine updates).
     */
    private static final String AUDIT_RESULT_SUCCESS = "SUCCESS";

    /**
     * Maximum length of the {@code SEC-USR-ID} key. Matches both the
     * COBOL {@code PIC X(08)} declaration in {@code CSUSR01Y.cpy:L18}
     * and the V010 column definition {@code sec_usr_id VARCHAR(8)}.
     * The Java REST surface has no physical limit and must defend
     * against arbitrarily long path inputs (the BMS field
     * {@code USRIDIN} of length 8 physically truncated terminal input
     * at 8 characters; the REST equivalent is this explicit check).
     */
    private static final int USER_ID_MAX_LENGTH = 8;

    /**
     * Spring Data JPA repository for the {@code user_security} table.
     * Constructor-injected as a {@code final} field per AAP &sect;0.7.1
     * layered architecture rules. Replaces the CICS VSAM file handle
     * used by {@code COUSR02C.cbl} READ-USER-SEC-FILE and
     * UPDATE-USER-SEC-FILE paragraphs.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Spring Security {@link PasswordEncoder} (BCrypt strength 12, see
     * {@code com.awsm2.carddemo.security.BCryptPasswordEncoderBean}).
     * Constructor-injected as a {@code final} field. Used only when
     * the caller supplies a non-blank new password to re-hash before
     * persistence &mdash; preserves the existing stored hash when the
     * password field is omitted.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * PCI-DSS / SOX audit adapter. Constructor-injected as a
     * {@code final} field. Invoked after a successful user update to
     * emit a {@code USER_UPDATE} security event to OpenSearch +
     * CloudWatch. The audit payload carries a {@code passwordChanged}
     * boolean (NEVER the password itself).
     */
    private final AuditLogService auditLogService;

    /**
     * Constructs a new {@code UserUpdateService}. All dependencies are
     * mandatory and validated via {@link Objects#requireNonNull} so a
     * mis-configured Spring context surfaces the error at startup
     * rather than at the first request.
     *
     * @param userSecurityRepository Spring Data JPA repository for the
     *                               {@code user_security} table
     * @param passwordEncoder        Spring Security BCrypt encoder
     *                               ({@code strength = 12}) used to
     *                               re-hash plaintext passwords on
     *                               rotation
     * @param auditLogService        audit adapter for OpenSearch +
     *                               CloudWatch security event emission
     * @throws NullPointerException if any argument is {@code null}
     */
    public UserUpdateService(UserSecurityRepository userSecurityRepository,
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
     * Updates an existing user record in the {@code user_security}
     * table.
     *
     * <p>This is the Java target for the {@code COUSR02C.cbl} read-
     * modify-write flow against the {@code USRSEC} VSAM KSDS. The
     * method normalizes the path-variable {@code userId} via
     * {@code trim().toUpperCase(Locale.US)} to match the upper-case
     * keying of USRSEC records (mirrors the COSGN00C upper-case
     * normalization pattern), then loads the existing
     * {@link UserSecurity} entity by primary key. A missing row
     * raises {@link RecordNotFoundException}, which
     * {@code GlobalExceptionHandler} maps to HTTP {@code 404 Not
     * Found} (preserving COBOL FILE STATUS '23' / DFHRESP(NOTFND)
     * semantics).</p>
     *
     * <p>For each request component that is present (non-{@code null}
     * and non-blank), the method applies the new value to the entity:
     * <ul>
     *   <li>{@link UserUpdateDto#firstName()} &rarr;
     *       {@link UserSecurity#setSecUsrFname(String)} (after
     *       non-blank validation)</li>
     *   <li>{@link UserUpdateDto#lastName()} &rarr;
     *       {@link UserSecurity#setSecUsrLname(String)} (after
     *       non-blank validation)</li>
     *   <li>{@link UserUpdateDto#userType()} &rarr;
     *       {@link UserSecurity#setSecUsrType(String)} (after
     *       non-blank validation and {@link #VALID_USER_TYPES}
     *       allowlist check)</li>
     *   <li>{@link UserUpdateDto#password()} &rarr;
     *       {@link UserSecurity#setSecUsrPwd(String)} with the
     *       value re-hashed via
     *       {@link PasswordEncoder#encode(CharSequence)} (BCrypt
     *       strength 12) &mdash; <b>security upgrade per AAP
     *       &sect;0.1.1 and &sect;0.7.1</b>; the plaintext value
     *       never reaches PostgreSQL.</li>
     * </ul>
     *
     * <p>For each component that is {@code null} or blank, the
     * existing value on the entity is preserved unchanged. This
     * supports the industry-standard REST "partial update" pattern
     * (PATCH-like semantics on a PUT endpoint) documented in
     * {@link UserUpdateDto} and is the only deliberate behavioral
     * deviation from the COBOL source (which required all five BMS
     * fields to be non-empty on every update).</p>
     *
     * <p>The entity is then persisted via
     * {@link UserSecurityRepository#save(Object)} (replaces COBOL
     * {@code EXEC CICS REWRITE} in the
     * {@code UPDATE-USER-SEC-FILE} paragraph), and an audit event of
     * type {@code "USER_UPDATE"} is emitted to OpenSearch +
     * CloudWatch via {@link AuditLogService#logSecurityEvent}. The
     * audit payload carries the {@code passwordChanged} boolean
     * flag so security operations can dashboard credential rotations
     * separately from profile edits; <b>the password value (plaintext
     * or hashed) is NEVER included in the payload</b> per AAP
     * &sect;0.6.6.</p>
     *
     * <p>The method is wrapped in {@code @Transactional(rollbackFor =
     * Exception.class, isolation = Isolation.READ_COMMITTED)} so the
     * findById + applyEdits + save + audit-emission sequence runs
     * atomically within a single RDS PostgreSQL transaction. On any
     * exception, the {@code user_security} row update is rolled
     * back, replicating the COBOL CICS task-level SYNCPOINT /
     * SYNCPOINT ROLLBACK semantics per AAP &sect;0.7.1.</p>
     *
     * <p>The returned {@link UserUpdateDto} has its {@code password}
     * component set to {@code null} so JSON serialization renders as
     * {@code "password": null}. The BCrypt hash and the plaintext
     * password never cross the wire.</p>
     *
     * @param userId  the user ID to update (path variable from
     *                {@code PUT /api/admin/users/{id}}); will be
     *                normalized via {@code trim().toUpperCase(Locale.US)}
     *                before lookup. Must be non-{@code null} and
     *                non-blank; {@link ValidationException} is thrown
     *                otherwise (HTTP {@code 400}).
     * @param request the update request body carrying the modifiable
     *                fields. Must be non-{@code null};
     *                {@link ValidationException} is thrown otherwise.
     *                For each component that is non-{@code null} and
     *                non-blank, the new value is applied to the
     *                entity; for each component that is {@code null}
     *                or blank, the existing value is preserved.
     * @return the persisted user data as a {@link UserUpdateDto} with
     *         the password component set to {@code null} (PCI-DSS
     *         hygiene per AAP &sect;0.6.6)
     * @throws ValidationException     if {@code userId} is blank, if
     *                                 {@code request} is {@code null},
     *                                 if {@code userId} exceeds
     *                                 {@link #USER_ID_MAX_LENGTH}, if
     *                                 any explicitly-provided field
     *                                 ({@code firstName},
     *                                 {@code lastName},
     *                                 {@code userType}, or
     *                                 {@code password}) is blank, or
     *                                 if {@code userType} is provided
     *                                 but not in
     *                                 {@link #VALID_USER_TYPES}
     *                                 (HTTP {@code 400 Bad Request})
     * @throws RecordNotFoundException if no user exists for the
     *                                 normalized {@code userId}
     *                                 (HTTP {@code 404 Not Found} &mdash;
     *                                 mirrors COBOL FILE STATUS '23' /
     *                                 DFHRESP(NOTFND) in the
     *                                 {@code READ-USER-SEC-FILE}
     *                                 paragraph)
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public UserUpdateDto updateUser(String userId, UserUpdateDto request) {

        // -------------------------------------------------------------
        // Step 1: Validate the userId path variable.
        //
        // COBOL: COUSR02C:PROCESS-ENTER-KEY (L145-L155) and
        //        COUSR02C:UPDATE-USER-INFO (L179-L185) — both
        //        cascades guard against empty USRIDIN. The Java
        //        target additionally checks the upper-bound length
        //        against the SEC-USR-ID PIC X(08) layout
        //        (CSUSR01Y.cpy:L18) and the V010 VARCHAR(8) column.
        // -------------------------------------------------------------
        if (userId == null || userId.isBlank()) {
            // COBOL: COUSR02C.cbl L148 — 'User ID can NOT be empty...'
            LOG.warn("UserUpdateService.updateUser rejected: userId is blank");
            throw new ValidationException("User ID can NOT be empty...");
        }

        // COBOL: COUSR02C:VALIDATE-INPUT — normalize userId via
        //        upper-case folding so it matches the upper-case
        //        keying of USRSEC VSAM records (CSUSR01Y.cpy:L18 +
        //        COSGN00C L132-L135 pattern). Locale.US ensures
        //        deterministic ASCII case folding and prevents the
        //        Turkish-locale 'I' -> 'ı' hazard.
        String normalizedId = userId.trim().toUpperCase(Locale.US);

        if (normalizedId.length() > USER_ID_MAX_LENGTH) {
            // The COBOL source does not check this explicitly because
            // the BMS map COUSR2A field USRIDIN is declared LENGTH=8
            // which physically truncates terminal input. The Java
            // REST surface has no such physical limit.
            LOG.warn("UserUpdateService.updateUser rejected: userId length {} exceeds max {}",
                    normalizedId.length(), USER_ID_MAX_LENGTH);
            throw new ValidationException(
                    "User ID exceeds maximum length of " + USER_ID_MAX_LENGTH + " characters");
        }

        // -------------------------------------------------------------
        // Step 2: Validate the request body.
        //
        // COBOL: COUSR02C:UPDATE-INPUT — the BMS map RECEIVE always
        //        yields a populated COUSR2AI structure; the REST
        //        equivalent is a non-null @RequestBody. A null body
        //        signals a malformed or missing JSON payload.
        // -------------------------------------------------------------
        if (request == null) {
            LOG.warn("UserUpdateService.updateUser rejected: request body is null for userId={}",
                    normalizedId);
            throw new ValidationException("Update request body is required");
        }

        // -------------------------------------------------------------
        // Step 3: Validate explicitly-provided fields are non-blank.
        //
        // COBOL: COUSR02C:UPDATE-USER-INFO (L179-L213) — first-error-
        //        wins cascade against SPACES or LOW-VALUES on FNAME,
        //        LNAME, PASSWD, USRTYPE. The COBOL source rejects
        //        blank values; the Java target permits omission
        //        (null), but rejects an EXPLICITLY blank value
        //        (e.g., "   ") so clients cannot use whitespace-only
        //        input to "erase" a field.
        // -------------------------------------------------------------
        if (request.firstName() != null && request.firstName().isBlank()) {
            // COBOL: COUSR02C.cbl L188 — 'First Name can NOT be empty...'
            LOG.warn("UserUpdateService.updateUser rejected: blank firstName for userId={}",
                    normalizedId);
            throw new ValidationException("First Name can NOT be empty...");
        }
        if (request.lastName() != null && request.lastName().isBlank()) {
            // COBOL: COUSR02C.cbl L194 — 'Last Name can NOT be empty...'
            LOG.warn("UserUpdateService.updateUser rejected: blank lastName for userId={}",
                    normalizedId);
            throw new ValidationException("Last Name can NOT be empty...");
        }
        if (request.password() != null && request.password().isBlank()) {
            // COBOL: COUSR02C.cbl L200 — 'Password can NOT be empty...'
            // NOTE: a NULL password means "keep existing"; a BLANK
            //       (whitespace-only) password is rejected.
            LOG.warn("UserUpdateService.updateUser rejected: blank password for userId={}",
                    normalizedId);
            throw new ValidationException("Password can NOT be empty...");
        }
        if (request.userType() != null && request.userType().isBlank()) {
            // COBOL: COUSR02C.cbl L206 — 'User Type can NOT be empty...'
            LOG.warn("UserUpdateService.updateUser rejected: blank userType for userId={}",
                    normalizedId);
            throw new ValidationException("User Type can NOT be empty...");
        }

        // -------------------------------------------------------------
        // Step 4: Validate userType allowlist.
        //
        // BMS map literal '(A=Admin, U=User)' at line 154 of
        // app/bms/COUSR02.bms documents the allowed values. The
        // COBOL source defers enforcement to operator discipline;
        // the Java target enforces the allowlist explicitly to
        // satisfy the V010 chk_user_security_type CHECK constraint
        // (sec_usr_type IN ('A','U')) at the application boundary —
        // surfacing a clean 400 rather than a 500 from PostgreSQL.
        // -------------------------------------------------------------
        if (request.userType() != null
                && !VALID_USER_TYPES.contains(request.userType())) {
            LOG.warn("UserUpdateService.updateUser rejected: invalid userType '{}' for userId={}",
                    request.userType(), normalizedId);
            throw new ValidationException(
                    "User Type must be 'A' (admin) or 'U' (user)");
        }

        // -------------------------------------------------------------
        // Step 5: Load the existing user (replaces COBOL READ).
        //
        // COBOL: COUSR02C:READ-USER-SEC-FILE (L320-L353) —
        //        EXEC CICS READ DATASET('USRSEC') UPDATE
        //        RIDFLD(SEC-USR-ID) ... RESP(WS-RESP-CD).
        //        On DFHRESP(NOTFND) the operator sees
        //        "User ID NOT found..." and re-prompts.
        //
        // Java: orElseThrow surfaces the same NOTFND condition as a
        //       typed RecordNotFoundException; GlobalExceptionHandler
        //       maps it to HTTP 404.
        // -------------------------------------------------------------
        UserSecurity user = userSecurityRepository.findById(normalizedId)
                .orElseThrow(() -> {
                    LOG.warn("UserUpdateService.updateUser: user not found userId={}",
                            normalizedId);
                    return new RecordNotFoundException(
                            "USER_NOT_FOUND",
                            "User ID NOT found...");
                });

        LOG.info("UserUpdateService.updateUser loaded userId={} currentType={}",
                user.getSecUsrId(), user.getSecUsrType());

        // -------------------------------------------------------------
        // Step 6: Apply edits to the loaded entity.
        //
        // COBOL: COUSR02C:UPDATE-USER-INFO (L219-L234) — compares
        //        each BMS input against the persisted value and
        //        copies only changed fields onto SEC-USER-DATA. The
        //        Java target relaxes the "must be non-empty" check
        //        to permit null/omitted fields (preserves existing)
        //        — see AAP §0.1.1 partial-update policy in
        //        UserUpdateDto. Whitespace-only blanks were already
        //        rejected in Step 3.
        // -------------------------------------------------------------
        if (request.firstName() != null && !request.firstName().isBlank()) {
            user.setSecUsrFname(request.firstName().trim());
        }
        if (request.lastName() != null && !request.lastName().isBlank()) {
            user.setSecUsrLname(request.lastName().trim());
        }
        if (request.userType() != null && !request.userType().isBlank()) {
            // The non-blank guard above is redundant given Step 3 but
            // is repeated here for defense-in-depth and to make the
            // intent ("only apply when explicitly supplied")
            // self-evident at the apply site.
            user.setSecUsrType(request.userType().trim());
        }

        // -------------------------------------------------------------
        // Step 7: Re-hash and apply the password (only when supplied).
        //
        // AAP §0.1.1 / §0.7.1 SECURITY UPGRADE: the COBOL source
        // stored plaintext in SEC-USR-PWD PIC X(08); the Java
        // target stores a 60-char BCrypt hash. The plaintext value
        // from the DTO lives in JVM memory only for the duration
        // of the encode() call and is discarded thereafter — never
        // logged, never persisted, never returned.
        //
        // When request.password() is null/blank the existing hash
        // is preserved (industry-standard REST "leave blank to keep
        // existing" pattern; documented in UserUpdateDto).
        // -------------------------------------------------------------
        boolean passwordChanged = false;
        if (request.password() != null && !request.password().isBlank()) {
            // COBOL: COUSR02C:UPDATE-USER-INFO L227-L230 —
            //        MOVE PASSWDI OF COUSR2AI TO SEC-USR-PWD
            //        (stored plaintext in COBOL). The Java target
            //        replaces the plaintext store with a BCrypt
            //        hash before assignment.
            String hashed = passwordEncoder.encode(request.password());
            user.setSecUsrPwd(hashed);
            passwordChanged = true;
        }

        // -------------------------------------------------------------
        // Step 8: Persist the entity (replaces COBOL REWRITE).
        //
        // COBOL: COUSR02C:UPDATE-USER-SEC-FILE (L358-L390) —
        //        EXEC CICS REWRITE DATASET('USRSEC')
        //        FROM(SEC-USER-DATA) ... RESP(WS-RESP-CD).
        //        On DFHRESP(NORMAL): emits "User <id> has been
        //        updated ...". On other RESP: error feedback.
        //
        // Java: save() + flush() forces Hibernate to execute the UPDATE before
        //       emitting the audit success event. With UserSecurity.version
        //       (QA CR-14), a stale concurrent update raises
        //       OptimisticLockingFailureException here, which
        //       GlobalExceptionHandler maps to HTTP 409 instead of silently
        //       reporting success for overwritten data.
        // -------------------------------------------------------------
        UserSecurity saved = userSecurityRepository.save(user);
        userSecurityRepository.flush();

        // -------------------------------------------------------------
        // Step 9: Audit emission (PCI-DSS / SOX per AAP §0.6.6).
        //
        // Replaces COBOL DISPLAY-based audit trails. The audit
        // payload deliberately carries the passwordChanged boolean
        // so security operations can dashboard credential rotations
        // separately from profile edits. The password value
        // (plaintext or hashed) is NEVER included in the payload —
        // doing so would violate the "no credential material in
        // application logs" rule from AAP §0.6.6.
        //
        // The userType is included so admin-account modifications
        // surface in a dedicated alarm dashboard.
        //
        // The logSecurityEvent call is annotated @Async on the
        // adapter so OpenSearch indexing latency cannot delay the
        // database commit; transport-level failures are caught at
        // the adapter boundary and logged at ERROR without
        // re-throwing.
        // -------------------------------------------------------------
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("userId", saved.getSecUsrId());
        auditPayload.put("userType", saved.getSecUsrType());
        auditPayload.put("passwordChanged", passwordChanged);

        auditLogService.logSecurityEvent(
                AUDIT_EVENT_TYPE,
                saved.getSecUsrId(),
                AUDIT_RESULT_SUCCESS,
                null,
                auditPayload,
                null);

        // INFO-level trace of the successful update. NOTE: only the
        // user identifier, user type, and the passwordChanged
        // boolean are logged — never the password (plaintext or
        // hashed), per AAP §0.6.6 log hygiene.
        LOG.info("UserUpdateService.updateUser updated userId={} userType={} passwordChanged={}",
                saved.getSecUsrId(), saved.getSecUsrType(), passwordChanged);

        // -------------------------------------------------------------
        // Step 10: Build the response DTO.
        //
        // COBOL: COUSR02C:UPDATE-USER-SEC-FILE (L372-L375) —
        //        STRING 'User ' SEC-USR-ID
        //               ' has been updated ...' INTO WS-MESSAGE.
        //        The Java response echoes the updated user's display
        //        fields so the caller can render its own confirmation
        //        message.
        //
        // PCI-DSS hygiene: the password component is set to null so
        // JSON serialization renders as "password": null. The BCrypt
        // hash and the plaintext password NEVER cross the wire.
        // -------------------------------------------------------------
        return new UserUpdateDto(
                saved.getSecUsrId(),
                saved.getSecUsrFname(),
                saved.getSecUsrLname(),
                null,
                saved.getSecUsrType());
    }
}
