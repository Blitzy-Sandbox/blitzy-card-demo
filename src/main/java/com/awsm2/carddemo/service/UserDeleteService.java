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
import com.awsm2.carddemo.dto.UserDeleteDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Admin user-delete service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COUSR03C.cbl} (CICS transaction id
 * {@code 'CU03'}, file {@code 'USRSEC '}).
 *
 * <p>This service deletes an existing user row from the {@code USRSEC}
 * VSAM KSDS dataset (now the {@code user_security} table on RDS
 * PostgreSQL after Flyway migration {@code V010__create_user_security.sql}).
 * In the COBOL source, {@code COUSR03C.cbl} runs as a two-step
 * pseudo-conversational CICS flow:</p>
 * <ol>
 *   <li>The operator types a {@code USRIDIN} value on map {@code COUSR3A}
 *       and presses {@code ENTER}. {@code PROCESS-ENTER-KEY} validates
 *       that {@code USRIDIN} is non-blank and issues
 *       {@code EXEC CICS READ DATASET('USRSEC') UPDATE} keyed by
 *       {@code SEC-USR-ID}. The retrieved {@code SEC-USR-FNAME},
 *       {@code SEC-USR-LNAME}, and {@code SEC-USR-TYPE} are displayed
 *       and the operator is prompted with
 *       <em>"Press PF5 key to delete this user ..."</em>.</li>
 *   <li>The operator confirms the deletion by pressing {@code PF5} which
 *       routes to {@code DELETE-USER-INFO} and ultimately to
 *       {@code DELETE-USER-SEC-FILE}, where {@code EXEC CICS DELETE
 *       DATASET('USRSEC')} removes the previously-read record. Pressing
 *       {@code PF4} (clear) or {@code PF3} (return) instead abandons the
 *       operation without deleting.</li>
 * </ol>
 *
 * <p>The Java target collapses this two-step terminal flow into a single
 * stateless REST call. The caller (typically {@code UserAdminController}
 * mapping {@code DELETE /api/admin/users/{id}}) supplies the user
 * identifier in the URL path and the binary {@code Y}/{@code N}
 * confirmation flag in the {@link UserDeleteDto#confirm()} component of
 * the request body. The two-arg method signature
 * {@link #deleteUser(String, UserDeleteDto) deleteUser(userId, request)}
 * matches the schema contract declared for this file and decouples the
 * path parameter from the request payload, preserving the
 * idempotent-DELETE REST convention.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 traceability)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COUSR03C.cbl}
 *       (CICS TRANID {@code 'CU03'}, file {@code 'USRSEC  '},
 *       {@code WS-PGMNAME = 'COUSR03C'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COUSR03.bms}
 *       (mapset {@code COUSR03}, map {@code COUSR3A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COUSR03.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes) &mdash; mapped to JPA entity
 *       {@link UserSecurity}.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}
 *       (RECLN 80, KEYS(8 0), {@code SHAREOPTIONS(2 3)},
 *       per {@code app/jcl/DUSRSECJ.jcl}). Replaced by the
 *       {@code user_security} RDS table.</li>
 * </ul>
 *
 * <h2>COBOL paragraph &mdash; Java method mapping</h2>
 * <table>
 *   <caption>COUSR03C.cbl paragraphs &harr; UserDeleteService responsibilities</caption>
 *   <tr><th>COBOL paragraph</th><th>Source line range</th>
 *       <th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (userId presence check)</td>
 *       <td>L142&ndash;L169</td>
 *       <td>{@link #deleteUser(String, UserDeleteDto)} step 1
 *           (userId validation)</td></tr>
 *   <tr><td>{@code DELETE-USER-INFO} (confirmation gate)</td>
 *       <td>L174&ndash;L192</td>
 *       <td>{@link #deleteUser(String, UserDeleteDto)} step 2
 *           (Y/N confirm validation)</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE} (existence verification)</td>
 *       <td>L267&ndash;L300</td>
 *       <td>{@link UserSecurityRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code DELETE-USER-SEC-FILE} (EXEC CICS DELETE)</td>
 *       <td>L305&ndash;L336</td>
 *       <td>{@link UserSecurityRepository#delete(Object)}</td></tr>
 *   <tr><td>{@code SYNCPOINT} (implicit, end of CICS task)</td>
 *       <td>EXEC CICS RETURN at L134&ndash;L137</td>
 *       <td>{@link Transactional @Transactional(rollbackFor =
 *           Exception.class, isolation = READ_COMMITTED)}</td></tr>
 *   <tr><td>{@code DISPLAY 'RESP:' WS-RESP-CD ...} (error trail)</td>
 *       <td>L294, L330</td>
 *       <td>{@link AuditLogService#logSecurityEvent}</td></tr>
 * </table>
 *
 * <h2>Confirmation flag semantics (AAP &sect;0.7.1, &sect;0.7.3)</h2>
 *
 * <p>The {@link UserDeleteDto#confirm()} value is normalized
 * (whitespace-trimmed and uppercase-folded with
 * {@link Locale#US}) and then evaluated against the two valid sentinels:</p>
 * <ul>
 *   <li><b>{@code "Y"}</b> &mdash; proceed with the delete. Replaces the
 *       COBOL {@code WHEN DFHPF5 PERFORM DELETE-USER-INFO} branch
 *       (line 121 of {@code COUSR03C.cbl}).</li>
 *   <li><b>{@code "N"}</b> &mdash; abandon the operation gracefully.
 *       Replaces the COBOL {@code WHEN DFHPF4 PERFORM
 *       CLEAR-CURRENT-SCREEN} branch (line 119); no row is removed and
 *       no audit event is recorded. The service returns a DTO with the
 *       request {@code userId} echoed back and {@code confirm = "N"} so
 *       the caller can render an "operation cancelled" message in the
 *       client UI without re-issuing a request.</li>
 *   <li><b>any other value</b> (or {@code null}) &mdash; rejected via
 *       {@link ValidationException} with message
 *       <em>"Invalid value. Valid values are (Y/N)..."</em>, which
 *       mirrors the COBOL {@code CCDA-MSG-INVALID-KEY} message
 *       surfaced when an unexpected AID key is pressed (the
 *       {@code WHEN OTHER} branch at line 126 of {@code COUSR03C.cbl}
 *       MAIN-PARA).</li>
 * </ul>
 *
 * <h2>Implementation rules satisfied (per the schema agent prompt)</h2>
 * <ol>
 *   <li><b>{@code package com.awsm2.carddemo.service;}</b> &mdash; matches
 *       the layered-architecture target package (AAP &sect;0.3.3).</li>
 *   <li><b>{@code @Service} + {@code @Transactional(rollbackFor =
 *       Exception.class, isolation = Isolation.READ_COMMITTED)}</b>
 *       &mdash; the method runs in a single RDS transaction with ACID
 *       guarantees, replicating COBOL {@code SYNCPOINT} semantics per
 *       AAP &sect;0.7.1. {@code READ_COMMITTED} is the default RDS
 *       PostgreSQL isolation and the level required for this admin
 *       user-delete flow (no read-modify-write race against the same
 *       row from concurrent callers because the
 *       {@code user_security} table is admin-managed and rarely
 *       contended).</li>
 *   <li><b>Constructor injection only</b> &mdash; both collaborators
 *       ({@link UserSecurityRepository}, {@link AuditLogService}) are
 *       injected via the single constructor; no {@code @Autowired}
 *       field injection anywhere in this class.</li>
 *   <li><b>Inline traceability comments</b> &mdash; every meaningful
 *       block carries a {@code // COBOL: COUSR03C:&lt;paragraph&gt;}
 *       comment so a reviewer can trace any Java statement back to its
 *       source paragraph in {@code app/cbl/COUSR03C.cbl}.</li>
 *   <li><b>Uppercase normalization</b> &mdash; both
 *       {@code userId} and {@code confirm} are normalized via
 *       {@code String.toUpperCase(Locale.US)}; this matches the COBOL
 *       upper-case keying of {@code USRSEC} VSAM records and avoids
 *       locale-sensitive surprises (e.g., Turkish locale lower-casing
 *       of {@code 'I'}).</li>
 *   <li><b>Confirmation pattern</b> &mdash; only {@code "Y"} proceeds;
 *       {@code "N"} cancels gracefully; any other value throws a
 *       {@link ValidationException}.</li>
 *   <li><b>Audit log captures {@code userType}</b> &mdash; the
 *       {@link AuditLogService#logSecurityEvent} call records the
 *       deleted user's role discriminator ({@code A} or {@code U})
 *       inside the payload map so security operations can review
 *       admin-vs-user deletion ratios.</li>
 *   <li><b>Response DTO MUST NOT include password/hash</b> &mdash; the
 *       returned {@link UserDeleteDto} contains only the four
 *       non-sensitive display fields ({@code userId}, {@code firstName},
 *       {@code lastName}, {@code userType}) plus the operator-input
 *       {@code confirm} flag; the BCrypt hash held by
 *       {@link UserSecurity#getSecUsrPwd()} is intentionally NOT copied
 *       into the response, satisfying PCI-DSS log-hygiene mandates per
 *       AAP &sect;0.6.6.</li>
 * </ol>
 *
 * <h2>Error mapping (AAP &sect;0.7.1)</h2>
 *
 * <p>Errors are surfaced as typed unchecked exceptions; the
 * {@code GlobalExceptionHandler} ({@code @RestControllerAdvice}) maps
 * them to standardized HTTP responses:</p>
 * <ul>
 *   <li>{@link ValidationException} &rarr; HTTP {@code 400 Bad Request}
 *       (used for blank {@code userId}, missing {@code confirm}, or
 *       invalid {@code confirm} value).</li>
 *   <li>{@link RecordNotFoundException} &rarr; HTTP {@code 404 Not
 *       Found} (used when {@link UserSecurityRepository#findById}
 *       returns {@link java.util.Optional#empty()}, replacing COBOL
 *       {@code DFHRESP(NOTFND)} handling at lines 287&ndash;299 and
 *       323&ndash;328 of {@code COUSR03C.cbl}).</li>
 * </ul>
 *
 * <h2>PCI-DSS / SOX compliance (AAP &sect;0.6.6)</h2>
 *
 * <p>User deletion is a security-significant event &mdash; an audit
 * record is emitted to OpenSearch via
 * {@link AuditLogService#logSecurityEvent} after the database row is
 * removed but BEFORE the transaction commits. Spring's
 * {@code @Async}-dispatched OpenSearch write happens off the
 * transactional thread, so audit emission cannot trigger a rollback
 * (per the {@code AuditLogService} contract, transport failures are
 * logged at {@code ERROR} but never re-thrown). The audit payload
 * carries:</p>
 * <ul>
 *   <li>{@code event_type = "user.deleted"} &mdash; semantic taxonomy
 *       identifier for downstream OpenSearch dashboards.</li>
 *   <li>{@code userId} &mdash; the deleted user's 8-character
 *       identifier.</li>
 *   <li>{@code result = "SUCCESS"} &mdash; the operator outcome
 *       (failed deletes do not reach the audit call because they
 *       throw before this line).</li>
 *   <li>{@code payload.userType} &mdash; the deleted user's role
 *       discriminator ({@code A} or {@code U}) for compliance
 *       reporting on admin-account deletions.</li>
 * </ul>
 *
 * <p>The BCrypt password hash from {@link UserSecurity#getSecUsrPwd()}
 * is NEVER included in the audit payload &mdash; doing so would
 * violate the AAP &sect;0.6.6 "no credential material in application
 * logs" rule.</p>
 *
 * @see UserSecurityRepository
 * @see UserDeleteDto
 * @see AuditLogService
 * @see RecordNotFoundException
 * @see ValidationException
 */
@Service
public class UserDeleteService {

    /**
     * SLF4J logger for this service. All log statements are routed
     * through Logback + {@code logstash-logback-encoder} to CloudWatch
     * Logs per AAP &sect;0.6.6. Per the PCI-DSS log-hygiene rule
     * (AAP &sect;0.6.6), log statements in this class log only
     * non-sensitive identifiers (the {@code userId} and the user's
     * role discriminator) and NEVER the BCrypt password hash.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UserDeleteService.class);

    /**
     * Audit event taxonomy identifier emitted to OpenSearch for a
     * successful user deletion. Stable across releases so downstream
     * security dashboards and compliance reports can pin queries to
     * this exact value.
     */
    private static final String AUDIT_EVENT_TYPE = "user.deleted";

    /**
     * Audit "result" tag value indicating a successful deletion.
     * Mirrors the dimension contract documented on
     * {@link AuditLogService#logSecurityEvent}.
     */
    private static final String AUDIT_RESULT_SUCCESS = "SUCCESS";

    /**
     * Confirmation sentinel signaling that the operator wishes to
     * proceed with the delete. Replaces the COBOL
     * {@code WHEN DFHPF5 PERFORM DELETE-USER-INFO} dispatch (line 121
     * of {@code COUSR03C.cbl}).
     */
    private static final String CONFIRM_YES = "Y";

    /**
     * Confirmation sentinel signaling that the operator wishes to
     * abandon the operation. Replaces the COBOL
     * {@code WHEN DFHPF4 PERFORM CLEAR-CURRENT-SCREEN} dispatch
     * (line 119 of {@code COUSR03C.cbl}); the service returns
     * normally without deleting and without emitting an audit event.
     */
    private static final String CONFIRM_NO = "N";

    /**
     * Maximum permitted length of the {@code userId} path variable,
     * matching the COBOL {@code SEC-USR-ID PIC X(08)} layout in
     * {@code app/cpy/CSUSR01Y.cpy} line 18 and the
     * {@code sec_usr_id VARCHAR(8)} primary-key column declared by
     * Flyway migration {@code V010__create_user_security.sql}.
     */
    private static final int USER_ID_MAX_LENGTH = 8;

    /**
     * Spring Data JPA repository over the {@code user_security} table.
     * Replaces the COBOL {@code USRSEC} VSAM KSDS access verbs
     * ({@code EXEC CICS READ}, {@code EXEC CICS DELETE}) in
     * {@code app/cbl/COUSR03C.cbl}.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Audit adapter wrapping the OpenSearch high-level REST client.
     * Invoked from {@link #deleteUser(String, UserDeleteDto)} after a
     * successful delete to record the security-significant event for
     * fraud investigation and regulatory queries (AAP &sect;0.6.6).
     */
    private final AuditLogService auditLogService;

    /**
     * Constructs the service with explicit constructor injection
     * (no {@code @Autowired} field injection per AAP &sect;0.7.1).
     * Both collaborators are required and validated against
     * {@code null} to fail fast at bean creation if the IoC container
     * misconfigures the wiring.
     *
     * @param userSecurityRepository Spring Data JPA repository for the
     *                               {@code user_security} table; must
     *                               not be {@code null}
     * @param auditLogService        OpenSearch + CloudWatch audit
     *                               adapter; must not be {@code null}
     */
    public UserDeleteService(UserSecurityRepository userSecurityRepository,
                             AuditLogService auditLogService) {
        this.userSecurityRepository = Objects.requireNonNull(
                userSecurityRepository,
                "userSecurityRepository must not be null");
        this.auditLogService = Objects.requireNonNull(
                auditLogService,
                "auditLogService must not be null");
    }

    /**
     * Delete the {@link UserSecurity} row identified by {@code userId}
     * after explicit operator confirmation. Mirrors the
     * confirmation-then-delete semantics of COBOL program
     * {@code app/cbl/COUSR03C.cbl} (CICS TRANID {@code 'CU03'},
     * file {@code 'USRSEC'}).
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Validate that {@code userId} is non-blank and within the
     *       8-character {@code SEC-USR-ID} layout limit. A failure
     *       surfaces as {@link ValidationException} (HTTP 400)
     *       carrying the COBOL message
     *       <em>"User ID can NOT be empty..."</em> (line 147 of
     *       {@code COUSR03C.cbl}).</li>
     *   <li>Validate that the {@code request} body and its
     *       {@link UserDeleteDto#confirm()} component are present.
     *       Both null cases surface as
     *       {@link ValidationException}.</li>
     *   <li>Normalize {@code userId} and {@code confirm} via
     *       {@code String.toUpperCase(Locale.US)}. Locale.US is used
     *       so that the comparison is deterministic across deploy
     *       regions and does not collide with locale-sensitive case
     *       folding (the Turkish-locale {@code 'I' &rarr; '\u0131'}
     *       hazard).</li>
     *   <li>Evaluate the normalized {@code confirm}:
     *     <ul>
     *       <li>{@code "Y"} &rarr; proceed to step 5.</li>
     *       <li>{@code "N"} &rarr; abandon the operation gracefully
     *           and return a DTO echoing {@code userId} with
     *           {@code confirm = "N"} (no read, no delete, no audit
     *           event).</li>
     *       <li>anything else &rarr; throw
     *           {@link ValidationException} with the COBOL message
     *           <em>"Invalid value. Valid values are (Y/N)..."</em>.</li>
     *     </ul>
     *   </li>
     *   <li>Read the persisted {@link UserSecurity} row via
     *       {@link UserSecurityRepository#findById(Object)}. A miss
     *       surfaces as {@link RecordNotFoundException} (HTTP 404)
     *       carrying the COBOL message
     *       <em>"User ID NOT found..."</em> (lines 289 and 325 of
     *       {@code COUSR03C.cbl}).</li>
     *   <li>Issue
     *       {@link UserSecurityRepository#delete(Object)}; Spring
     *       Data translates this to a SQL {@code DELETE FROM
     *       user_security WHERE sec_usr_id = ?}, mirroring the COBOL
     *       {@code EXEC CICS DELETE DATASET('USRSEC')} verb at lines
     *       307&ndash;311 of {@code COUSR03C.cbl}.</li>
     *   <li>Emit a structured security audit event via
     *       {@link AuditLogService#logSecurityEvent} capturing the
     *       deleted user's identifier and role discriminator. The
     *       audit hash is async-dispatched and does NOT block the
     *       transaction commit.</li>
     *   <li>Return a {@link UserDeleteDto} echoing the deleted
     *       user's display fields (firstName, lastName, userType)
     *       with {@code confirm = "Y"} so the caller can render a
     *       success message. The BCrypt password hash from
     *       {@link UserSecurity#getSecUsrPwd()} is NEVER copied into
     *       the response per AAP &sect;0.6.6.</li>
     * </ol>
     *
     * <p>The method is wrapped with
     * {@code @Transactional(rollbackFor = Exception.class,
     * isolation = Isolation.READ_COMMITTED)}: the
     * {@code findById}, the {@code delete}, and the audit invocation
     * run in a single RDS transaction with ACID guarantees. Any
     * exception thrown from any step (including
     * {@link RecordNotFoundException} and {@link ValidationException},
     * which are unchecked) triggers a rollback &mdash; the explicit
     * {@code rollbackFor = Exception.class} extends the rollback
     * envelope to ALL exception types (including checked
     * exceptions), preserving the verbatim COBOL
     * {@code SYNCPOINT ROLLBACK} contract (AAP &sect;0.7.1).</p>
     *
     * @param userId  the 8-character user identifier to delete;
     *                normally supplied as the URL path variable
     *                {@code {id}} on {@code DELETE
     *                /api/admin/users/{id}}. Must be non-blank and
     *                no longer than 8 characters.
     * @param request the request payload carrying the operator's
     *                confirmation flag ({@code Y} or {@code N}). Must
     *                not be {@code null}; its
     *                {@link UserDeleteDto#confirm() confirm()}
     *                component must not be {@code null}.
     * @return a {@link UserDeleteDto} whose four display fields
     *         ({@code userId}, {@code firstName}, {@code lastName},
     *         {@code userType}) reflect the affected user record,
     *         and whose {@code confirm} component is either
     *         {@code "Y"} (delete succeeded) or {@code "N"}
     *         (operation cancelled by the operator).
     * @throws ValidationException     when {@code userId} is blank
     *                                 or too long; or when the
     *                                 {@code request} body or
     *                                 {@code confirm} component is
     *                                 missing; or when
     *                                 {@code confirm} is neither
     *                                 {@code "Y"} nor {@code "N"}
     *                                 after normalization. Mapped to
     *                                 HTTP 400 by
     *                                 {@code GlobalExceptionHandler}.
     * @throws RecordNotFoundException when no user row exists with
     *                                 the supplied {@code userId}.
     *                                 Mapped to HTTP 404 by
     *                                 {@code GlobalExceptionHandler}.
     *                                 Mirrors the COBOL
     *                                 {@code DFHRESP(NOTFND)}
     *                                 branches in
     *                                 {@code READ-USER-SEC-FILE}
     *                                 (line 287) and
     *                                 {@code DELETE-USER-SEC-FILE}
     *                                 (line 323) of
     *                                 {@code app/cbl/COUSR03C.cbl}.
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public UserDeleteDto deleteUser(String userId, UserDeleteDto request) {

        // -------------------------------------------------------------
        // Step 1: Validate userId
        //
        // COBOL: COUSR03C:PROCESS-ENTER-KEY (L142-L154) — guards against
        //        empty USRIDIN. The Java target adds an upper-bound
        //        length check matching the SEC-USR-ID PIC X(08) layout
        //        (CSUSR01Y.cpy:L18) and the V010 VARCHAR(8) column.
        // -------------------------------------------------------------
        if (userId == null || userId.isBlank()) {
            // COBOL: COUSR03C.cbl L147 — "User ID can NOT be empty..."
            LOG.warn("UserDeleteService.deleteUser rejected: userId is blank");
            throw new ValidationException("User ID can NOT be empty...");
        }

        // Normalize the path variable. trim() removes incidental
        // whitespace from the HTTP path mapping; toUpperCase(Locale.US)
        // matches the upper-case keying of USRSEC VSAM records and
        // avoids locale-sensitive case folding (Turkish-locale 'I'
        // hazard). Locale.US is explicit and deterministic across
        // deploy regions.
        String normalizedId = userId.trim().toUpperCase(Locale.US);

        if (normalizedId.length() > USER_ID_MAX_LENGTH) {
            // The COBOL source does not check this explicitly because the
            // BMS map COUSR3A field USRIDIN is declared LENGTH=8 which
            // physically truncates terminal input at 8 characters. The
            // Java REST surface has no such physical limit and must
            // defend against arbitrarily long path inputs.
            LOG.warn("UserDeleteService.deleteUser rejected: userId length {} exceeds max {}",
                    normalizedId.length(), USER_ID_MAX_LENGTH);
            throw new ValidationException(
                    "User ID exceeds maximum length of " + USER_ID_MAX_LENGTH + " characters");
        }

        // -------------------------------------------------------------
        // Step 2: Validate confirmation flag
        //
        // COBOL: COUSR03C:MAIN-PARA EVALUATE EIBAID (L108-L130) —
        //        dispatches to DELETE-USER-INFO only on DFHPF5; any
        //        other AID key triggers CCDA-MSG-INVALID-KEY.
        // COBOL: COUSR03C:DELETE-USER-INFO (L174-L192) — requires
        //        non-empty USRIDIN and then performs the read+delete.
        // -------------------------------------------------------------
        if (request == null) {
            // No request body at all — the REST equivalent of a
            // missing BMS map RECEIVE: cannot determine intent.
            LOG.warn("UserDeleteService.deleteUser rejected: request body is null");
            throw new ValidationException("Confirm flag required (Y/N)");
        }
        if (request.confirm() == null) {
            // Body is present but confirm is null: the same as the
            // COBOL operator pressing ENTER without first pressing PF5
            // (no confirmation supplied).
            LOG.warn("UserDeleteService.deleteUser rejected: confirm flag is null for userId={}",
                    normalizedId);
            throw new ValidationException("Confirm flag required (Y/N)");
        }

        // Normalize the confirm flag. Same Locale.US rationale as for
        // userId above — deterministic ASCII case folding so "y"/"Y"
        // both pass and the comparison cannot be defeated by locale.
        String confirm = request.confirm().trim().toUpperCase(Locale.US);

        // -------------------------------------------------------------
        // Step 3: Branch on the normalized confirm flag.
        //
        // COBOL: COUSR03C:MAIN-PARA (L119) — WHEN DFHPF4 →
        //        CLEAR-CURRENT-SCREEN: graceful cancellation (no
        //        delete). Java equivalent: return early.
        // -------------------------------------------------------------
        if (CONFIRM_NO.equals(confirm)) {
            // Cancelled by operator — short-circuit and return without
            // touching the database. We do NOT emit an audit event for
            // a cancellation; only the successful delete is auditable.
            LOG.info("UserDeleteService.deleteUser cancelled by operator: userId={}",
                    normalizedId);
            return new UserDeleteDto(
                    normalizedId,
                    null,
                    null,
                    null,
                    CONFIRM_NO);
        }

        // COBOL: COUSR03C:MAIN-PARA (L126-L129) — WHEN OTHER →
        //        CCDA-MSG-INVALID-KEY: any unexpected AID key. Java
        //        equivalent: ValidationException.
        if (!CONFIRM_YES.equals(confirm)) {
            LOG.warn("UserDeleteService.deleteUser rejected: invalid confirm value '{}' for userId={}",
                    confirm, normalizedId);
            throw new ValidationException(
                    "Invalid value. Valid values are (Y/N)...");
        }

        // -------------------------------------------------------------
        // Step 4: Load the user record (existence verification).
        //
        // COBOL: COUSR03C:READ-USER-SEC-FILE (L267-L300) —
        //        EXEC CICS READ DATASET('USRSEC') UPDATE
        //        RIDFLD(SEC-USR-ID) ... RESP(WS-RESP-CD).
        //        On DFHRESP(NOTFND) the operator sees
        //        "User ID NOT found..." and re-prompts.
        //
        // Java: orElseThrow surfaces the same NOTFND condition as a
        //       typed RecordNotFoundException; GlobalExceptionHandler
        //       maps it to HTTP 404. The findById call also serves as
        //       the implicit existence check before the delete — a
        //       missing row throws here, before delete() is invoked.
        // -------------------------------------------------------------
        UserSecurity user = userSecurityRepository.findById(normalizedId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "User ID NOT found..."));

        // -------------------------------------------------------------
        // Step 5: Delete the user row.
        //
        // COBOL: COUSR03C:DELETE-USER-SEC-FILE (L305-L336) —
        //        EXEC CICS DELETE DATASET('USRSEC') ... RESP(WS-RESP-CD).
        //        On DFHRESP(NORMAL): emit "User <id> has been deleted ...".
        //        On DFHRESP(NOTFND): "User ID NOT found...".
        //        On other RESP: "Unable to Update User...".
        //
        // Java: userSecurityRepository.delete(user) flushes a SQL
        //       DELETE FROM user_security WHERE sec_usr_id = ?. The
        //       JPA layer raises EmptyResultDataAccessException for
        //       the equivalent of NOTFND, which Spring's
        //       PersistenceExceptionTranslator converts to a
        //       DataAccessException — handled by
        //       GlobalExceptionHandler. We don't have to re-check
        //       NOTFND here because Step 4 already verified
        //       existence within the same transaction (READ_COMMITTED
        //       isolation prevents in-flight phantom inserts from
        //       impacting our view of the row).
        // -------------------------------------------------------------
        userSecurityRepository.delete(user);

        // -------------------------------------------------------------
        // Step 6: Audit emission (PCI-DSS / SOX per AAP §0.6.6).
        //
        // The audit payload deliberately carries the userType so that
        // security operations can dashboard admin-account deletions
        // separately from regular-user deletions. The BCrypt password
        // hash held by user.getSecUsrPwd() is NEVER included — doing
        // so would violate the "no credential material in application
        // logs" rule from AAP §0.6.6.
        //
        // The logSecurityEvent call is annotated @Async on the
        // adapter so OpenSearch indexing latency cannot delay the
        // database commit; transport-level failures are caught at the
        // adapter boundary and logged at ERROR without re-throwing.
        // -------------------------------------------------------------
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("userId", user.getSecUsrId());
        auditPayload.put("userType", user.getSecUsrType());

        auditLogService.logSecurityEvent(
                AUDIT_EVENT_TYPE,
                user.getSecUsrId(),
                AUDIT_RESULT_SUCCESS,
                null,
                auditPayload,
                null);

        // INFO-level trace of the successful delete. NOTE: only the
        // user identifier and role discriminator are logged — never
        // the password hash, per PCI-DSS log hygiene.
        LOG.info("UserDeleteService.deleteUser deleted userId={} userType={}",
                user.getSecUsrId(), user.getSecUsrType());

        // -------------------------------------------------------------
        // Step 7: Build the response DTO.
        //
        // COBOL: COUSR03C:DELETE-USER-SEC-FILE (L318-L321) —
        //        STRING 'User ' SEC-USR-ID ' has been deleted ...'.
        //        The Java response echoes the deleted user's display
        //        fields so the caller (UI / SDK) can render its own
        //        success message; the confirmation flag is set to "Y"
        //        to indicate the delete actually executed (vs. the
        //        cancellation branch above which returns "N").
        //
        // Password / BCrypt hash is intentionally OMITTED from the
        // response per the schema rule "Response DTO MUST NOT include
        // password/hash" and AAP §0.6.6 PCI-DSS posture.
        // -------------------------------------------------------------
        return new UserDeleteDto(
                user.getSecUsrId(),
                user.getSecUsrFname(),
                user.getSecUsrLname(),
                user.getSecUsrType(),
                CONFIRM_YES);
    }
}
