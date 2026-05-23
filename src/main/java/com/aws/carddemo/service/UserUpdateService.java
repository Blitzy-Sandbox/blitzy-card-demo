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
package com.aws.carddemo.service;

import com.aws.carddemo.entity.SecurityUser;
import com.aws.carddemo.repository.UserSecurityRepository;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * User-update service — the Java migration of the 414-line CICS COBOL
 * program {@code app/cbl/COUSR02C.cbl} (TRANID {@code CU02}, the admin-only
 * user-update dispatcher). Modifies an existing {@link SecurityUser} record
 * in the {@code USRSEC} VSAM KSDS replacement (PostgreSQL
 * {@code security_users} table) after enforcing field-level validations
 * inherited from the COBOL baseline, plus a Java-migration-added
 * invalid-user-type reject and JPA optimistic-locking semantics.
 *
 * <h2>COBOL Provenance — COUSR02C.cbl</h2>
 *
 * <p>The COBOL workflow combines the {@code UPDATE-USER-INFO} paragraph
 * (lines 177–245) with two file-access paragraphs:
 * {@code READ-USER-SEC-FILE} (lines 320–353) and
 * {@code UPDATE-USER-SEC-FILE} (lines 358–390). The dispatcher orchestrates:
 *
 * <ol>
 *   <li>Validate {@code USRIDINI OF COUSR2AI} is non-empty (lines 180–185):
 *       {@code 'User ID can NOT be empty...'} reject when blank.</li>
 *   <li>Validate {@code FNAMEI OF COUSR2AI} is non-empty (lines 186–191):
 *       {@code 'First Name can NOT be empty...'} reject when blank.</li>
 *   <li>Validate {@code LNAMEI OF COUSR2AI} is non-empty (lines 192–197):
 *       {@code 'Last Name can NOT be empty...'} reject when blank.</li>
 *   <li>Validate {@code PASSWDI OF COUSR2AI} is non-empty (lines 198–203):
 *       {@code 'Password can NOT be empty...'} reject when blank
 *       <em>— omitted in the Java migration; see Java Migration: newPassword
 *       Semantics below for the divergence rationale</em>.</li>
 *   <li>Validate {@code USRTYPEI OF COUSR2AI} is non-empty (lines 204–209):
 *       {@code 'User Type can NOT be empty...'} reject when blank.</li>
 *   <li>{@code PERFORM READ-USER-SEC-FILE} (line 217) — read the record by
 *       {@code SEC-USR-ID} primary key:
 *       <ul>
 *         <li>{@code DFHRESP(NORMAL)} → continue to the update step.</li>
 *         <li>{@code DFHRESP(NOTFND)} → {@code 'User ID NOT found...'}
 *             reject (lines 340–345).</li>
 *         <li>{@code WHEN OTHER} → {@code 'Unable to lookup User...'}
 *             reject (lines 346–352) — translated in the Java migration
 *             to {@link org.springframework.dao.DataAccessException}
 *             propagation (handled by the controller's exception chain).</li>
 *       </ul></li>
 *   <li>Compare each input field against the loaded record (lines 219–234);
 *       set {@code USR-MODIFIED-YES} when any field differs. The Java
 *       migration delegates this to JPA's dirty-checking semantics — see
 *       Java Migration: Dirty-Check Delegation below.</li>
 *   <li>{@code PERFORM UPDATE-USER-SEC-FILE} (line 237) — issue the
 *       {@code EXEC CICS REWRITE} on the same dataset:
 *       <ul>
 *         <li>{@code DFHRESP(NORMAL)} → success, returning
 *             {@code 'User {id} has been updated ...'} (lines 369–375).</li>
 *         <li>{@code DFHRESP(NOTFND)} → rare-race reject (lines 377–382)
 *             collapsed into the same {@code 'User ID NOT found...'}
 *             branch in the Java migration.</li>
 *         <li>{@code WHEN OTHER} → {@code 'Unable to Update User...'}
 *             reject (lines 383–389) — translated to
 *             {@link org.springframework.dao.DataAccessException}
 *             propagation.</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>Java Migration: Optimistic Locking via JPA @Version</h2>
 *
 * <p>The COBOL READ UPDATE / REWRITE idiom (lines 322–331 + lines 360–366)
 * serialised concurrent updates through CICS file locks: the READ UPDATE
 * acquired an exclusive lock on the record that persisted until the
 * REWRITE released it. The Java migration replaces this with JPA's
 * {@code @Version} optimistic-locking field on {@link SecurityUser}: when
 * the {@code save()} call detects a version mismatch (another transaction
 * incremented the persisted version between this transaction's lookup and
 * save), it raises
 * {@link org.springframework.dao.OptimisticLockingFailureException}. The
 * service does not catch the exception; it propagates to the controller
 * layer's exception-handler chain, which maps it to HTTP 409 Conflict. The
 * observable contract (concurrent updates are detected and one of them
 * fails cleanly) is preserved per AAP §0.10.4 "External interfaces consumed
 * by downstream systems MUST NOT change".
 *
 * <h2>Java Migration: newPassword Semantics</h2>
 *
 * <p>The COBOL workflow at lines 227–230 compares the operator's input
 * {@code PASSWDI} against the loaded {@code SEC-USR-PWD} (a
 * plaintext-vs-plaintext comparison) to decide whether to update the field.
 * With BCrypt the loaded value is a 60-character hash, and the operator's
 * input remains plaintext — there is no symmetric comparison the service
 * can perform without first hashing the input (and the BCrypt salt
 * randomisation means even an identical plaintext produces a different hash,
 * so equality of hashes is the wrong predicate). The Java migration
 * therefore re-purposes the {@link UserUpdateRequest#getNewPassword()}
 * field with the following contract:
 * <ul>
 *   <li>{@code null} or empty → preserve the existing BCrypt hash
 *       (operator did not change the password).</li>
 *   <li>Non-empty → BCrypt-encode and overwrite the persisted hash
 *       (operator entered a new password).</li>
 * </ul>
 *
 * <p>Consequence: the COBOL {@code 'Password can NOT be empty...'} reject
 * at lines 198–203 is intentionally omitted from the Java reject taxonomy
 * because an empty {@code newPassword} is the canonical "no change" signal
 * (a legitimate operator action), not an error. This divergence is
 * documented per AAP §0.10.2 "All deviations from literal COBOL logic must
 * be documented with the original COBOL paragraph name and reason for
 * divergence" (paragraph: {@code UPDATE-USER-INFO} lines 198–203 and 227–230).
 *
 * <h2>Java Migration: Dirty-Check Delegation</h2>
 *
 * <p>The COBOL workflow at lines 219–234 explicitly compares each input
 * field against the loaded record and sets {@code USR-MODIFIED-YES} when
 * any field differs; the {@code USR-MODIFIED-NO} branch at lines 238–242
 * emits the {@code 'Please modify to update ...'} message and skips the
 * REWRITE. The Java migration intentionally omits this check because:
 *
 * <ol>
 *   <li>JPA's {@code save()} is idempotent at the SQL layer when no
 *       managed field has changed — Hibernate's dirty-check sees no diff
 *       and emits no UPDATE statement. The persisted state is identical to
 *       the pre-call state.</li>
 *   <li>Re-running the REST endpoint with identical input is a safe,
 *       idempotent operation in the Java migration; the observable
 *       behaviour for the operator is the same {@code 'User {id} has been
 *       updated ...'} success message regardless of whether any field
 *       actually changed.</li>
 *   <li>The {@code 'Please modify to update ...'} reject relied on the
 *       BMS map's screen-state preservation across reentries; the JSON
 *       REST controller does not preserve state between requests, so the
 *       check would either always fail (the controller has no
 *       loaded-record to compare against) or require an additional
 *       client-supplied "expected previous value" field that adds no
 *       business value.</li>
 * </ol>
 *
 * <p>This divergence is documented per AAP §0.10.2 (paragraph:
 * {@code UPDATE-USER-INFO} lines 238–242).
 *
 * <h2>Validation Order</h2>
 *
 * <p>The service performs validations in the deliberate order documented
 * by the COBOL {@code UPDATE-USER-INFO} cascade:
 * <ol>
 *   <li><b>User ID empty check</b> (COBOL parity, line 180)</li>
 *   <li><b>First name empty check</b> (COBOL parity, line 186)</li>
 *   <li><b>Last name empty check</b> (COBOL parity, line 192)</li>
 *   <li><b>User type empty check</b> (COBOL parity, line 204)</li>
 *   <li><b>User type domain check</b> (Java-migration addition; same
 *       rationale as {@link UserAddService})</li>
 *   <li><b>Repository lookup</b> via
 *       {@link UserSecurityRepository#findById} (COBOL parity:
 *       {@code READ-USER-SEC-FILE} line 217) — {@code Optional.empty()}
 *       yields {@code 'User ID NOT found...'} reject.</li>
 *   <li><b>Field updates</b> on the loaded entity (COBOL parity, lines
 *       219–234). The optional password rehash uses BCrypt encoding per
 *       AAP §0.10.5.</li>
 *   <li><b>Repository save</b> (COBOL parity: {@code UPDATE-USER-SEC-FILE}
 *       line 360). On JPA {@code @Version} mismatch this raises
 *       {@link org.springframework.dao.OptimisticLockingFailureException}
 *       which propagates uncaught.</li>
 *   <li><b>Success response</b> (COBOL parity: lines 372–375) — builds
 *       the {@code 'User {id} has been updated ...'} confirmation.</li>
 * </ol>
 *
 * <p>Each validation short-circuits and returns immediately on failure;
 * the repository is never consulted on any of the empty-field reject
 * paths or the invalid-user-type reject (defence-in-depth verified by
 * the corresponding test suite via {@code verify(repository, never())}
 * assertions).
 *
 * <h2>Constructor Injection (No Spring Stereotype)</h2>
 *
 * <p>This class deliberately omits the {@code @Service} stereotype
 * annotation; subsequent migration agents will add it when the full Spring
 * application context is wired up. For now, the constructor accepts the
 * repository and password-encoder collaborators directly so unit tests can
 * wire a Mockito mock + a real {@link PasswordEncoder} without a Spring
 * context — matching the convention established by
 * {@link AuthenticationService}, {@link UserAddService},
 * {@link UserDeleteService}, and the rest of the service package.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE user-update dispatcher logic (no
 * helpers extracted to other classes; all branches are visible in the
 * single {@link #updateUser(UserUpdateRequest)} entry point). The
 * corresponding {@code UserUpdateServiceTest} exercises every branch via
 * real method calls with a mocked {@link UserSecurityRepository} at the
 * database boundary and a real {@link PasswordEncoder} so the BCrypt
 * encoding contract is actually exercised (rather than mock-stubbed). No
 * business logic (empty checks, user-type domain check, field-update
 * dispatch, BCrypt encoding, response construction) is duplicated inside
 * the test.
 *
 * <h2>Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable service implementation</strong>
 * created to satisfy {@link UserUpdateServiceTest} compilation and the
 * AAP §0.10.1 Require Test Coverage rule. Subsequent migration agents
 * (REFACTOR flavor) will add the {@code @Service} stereotype, JPA
 * {@code @Transactional} demarcation, structured logging hooks, and the
 * REST controller layer that drives this service when the full Spring
 * application context is wired up.
 *
 * @see UserUpdateRequest
 * @see UserUpdateResult
 * @see UserSecurityRepository
 * @see SecurityUser
 * @see PasswordEncoder
 */
@Service
public class UserUpdateService {

    // ---------------------------------------------------------------------
    // Reject messages — verbatim COBOL literals preserved per AAP §0.10.4
    // (Immutable Boundaries: downstream consumers reading the JSON error
    // envelope must see the same textual reason as the COBOL baseline).
    // ---------------------------------------------------------------------

    /**
     * Reject message returned when {@link UserUpdateRequest#getUserId()} is
     * {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR02C.cbl} {@code UPDATE-USER-INFO} (line 182).
     */
    static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Reject message returned when {@link UserUpdateRequest#getFirstName()}
     * is {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR02C.cbl} {@code UPDATE-USER-INFO} (line 188).
     */
    static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /**
     * Reject message returned when {@link UserUpdateRequest#getLastName()}
     * is {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR02C.cbl} {@code UPDATE-USER-INFO} (line 194).
     */
    static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /**
     * Reject message returned when {@link UserUpdateRequest#getUserType()}
     * is {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR02C.cbl} {@code UPDATE-USER-INFO} (line 206).
     */
    static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * Reject message returned when {@link UserUpdateRequest#getUserType()}
     * is not {@code "U"} or {@code "A"}. This is a Java-migration addition
     * with no direct COBOL equivalent (the COBOL BMS map's input attributes
     * implicitly restricted USRTYPEI to single-character entry, but the
     * JSON REST controller layer is reachable independently of any BMS
     * attribute enforcement — defence in depth requires the service to
     * explicitly validate the domain). Anchored on the lower-case word
     * {@code "type"} so the corresponding test assertion remains stable
     * across copy edits. Matches the {@link UserAddService} convention.
     */
    static final String MSG_INVALID_USER_TYPE = "User Type must be 'U' or 'A'...";

    /**
     * Reject message returned when the repository returns
     * {@link Optional#empty()} from
     * {@link UserSecurityRepository#findById(Object)} — verbatim COBOL
     * literal from {@code COUSR02C.cbl} {@code READ-USER-SEC-FILE} (line
     * 342) and {@code UPDATE-USER-SEC-FILE} (line 379). Both COBOL branches
     * share the same literal; the Java migration collapses them into a
     * single reject path (the {@code DFHRESP(NOTFND)} response from a
     * REWRITE without a prior READ is impossible in the JPA model — the
     * findById always precedes the save).
     */
    static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /**
     * Prefix for the success message — verbatim leading literal from the
     * COBOL {@code STRING} construct at {@code COUSR02C.cbl} line 372
     * ({@code STRING 'User ' DELIMITED BY SIZE}). The full message is
     * {@code MSG_UPDATED_PREFIX + userId + MSG_UPDATED_SUFFIX}.
     */
    static final String MSG_UPDATED_PREFIX = "User ";

    /**
     * Suffix for the success message — verbatim trailing literal from the
     * COBOL {@code STRING} construct at {@code COUSR02C.cbl} line 374
     * ({@code ' has been updated ...' DELIMITED BY SIZE}). Includes the
     * leading space and trailing ellipsis from the COBOL literal.
     */
    static final String MSG_UPDATED_SUFFIX = " has been updated ...";

    /**
     * Regular-user type code — COBOL {@code CDEMO-USRTYP-USER} 88-level
     * value. Matches the {@link UserAddService} convention; the constant
     * is package-private to expose it to the corresponding test suite via
     * package-membership access.
     */
    static final String USER_TYPE_REGULAR = "U";

    /**
     * Admin user-type code — COBOL {@code CDEMO-USRTYP-ADMIN} 88-level
     * value.
     */
    static final String USER_TYPE_ADMIN = "A";

    // ---------------------------------------------------------------------
    // Collaborators — JPA repository + password encoder boundary
    // ---------------------------------------------------------------------

    /**
     * JPA repository for {@link SecurityUser} entities — the Java
     * replacement for COBOL {@code EXEC CICS READ DATASET('USRSEC') UPDATE}
     * (used for the pre-update lookup, line 322) and
     * {@code EXEC CICS REWRITE DATASET('USRSEC')} (used for the update,
     * line 360) in {@code app/cbl/COUSR02C.cbl}. Constructor-injected so
     * unit tests can wire a Mockito mock without a Spring context.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * BCrypt password encoder — converts the optional new plaintext password
     * supplied on the {@link UserUpdateRequest} into a 60-character BCrypt
     * hash before persisting (when {@link UserUpdateRequest#getNewPassword()}
     * is non-empty). Constructor-injected so unit tests can supply a real
     * {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}
     * instance (the test suite verifies real BCrypt semantics, not a stubbed
     * encoder, per AAP §0.10.5).
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructs a new {@code UserUpdateService}.
     *
     * @param userSecurityRepository JPA repository for {@link SecurityUser}
     *                               lookups and updates (Java replacement
     *                               for COBOL {@code EXEC CICS READ /
     *                               REWRITE} on {@code USRSEC}). Must not be
     *                               {@code null} — the service does not
     *                               guard against {@code null} collaborators
     *                               because Spring DI would surface the
     *                               misconfiguration at startup; unit tests
     *                               wire a Mockito mock.
     * @param passwordEncoder        password encoder (typically
     *                               {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder})
     *                               used to hash the optional new plaintext
     *                               password supplied by the operator into
     *                               a BCrypt hash before persisting. Must
     *                               not be {@code null}.
     */
    public UserUpdateService(UserSecurityRepository userSecurityRepository,
                             PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Update an existing {@link SecurityUser} record in the {@code USRSEC}
     * replacement after enforcing the COBOL-inherited validation cascade
     * plus the Java-migration-added invalid-user-type reject. Implements
     * the Java equivalent of {@code app/cbl/COUSR02C.cbl}
     * {@code UPDATE-USER-INFO} (lines 177–245) plus the
     * {@code READ-USER-SEC-FILE} (lines 320–353) and
     * {@code UPDATE-USER-SEC-FILE} (lines 358–390) paragraphs it invokes.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Empty user ID check — if {@code request.getUserId()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_USER_ID_EMPTY}. COBOL parity: line 180.</li>
     *   <li>Empty first name check — if {@code request.getFirstName()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_FIRST_NAME_EMPTY}. COBOL parity: line 186.</li>
     *   <li>Empty last name check — if {@code request.getLastName()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_LAST_NAME_EMPTY}. COBOL parity: line 192.</li>
     *   <li>Empty user type check — if {@code request.getUserType()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_USER_TYPE_EMPTY}. COBOL parity: line 204.</li>
     *   <li>User type domain check — if {@code request.getUserType()} is
     *       neither {@code "U"} nor {@code "A"}, reject with
     *       {@link #MSG_INVALID_USER_TYPE}. Java-migration addition.</li>
     *   <li>Repository lookup via {@link UserSecurityRepository#findById}
     *       (COBOL parity: {@code READ-USER-SEC-FILE} line 322). On
     *       {@code Optional.empty()} → reject with
     *       {@link #MSG_USER_NOT_FOUND}.</li>
     *   <li>Update the loaded entity's mutable fields ({@code firstName},
     *       {@code lastName}, {@code userType}). When
     *       {@link UserUpdateRequest#getNewPassword()} is non-empty,
     *       BCrypt-encode it and overwrite the {@code password} field;
     *       otherwise preserve the existing hash (see Java Migration:
     *       newPassword Semantics on the class-level Javadoc).</li>
     *   <li>Persist via {@link UserSecurityRepository#save(Object)} (COBOL
     *       parity: {@code UPDATE-USER-SEC-FILE} line 360). On JPA
     *       {@code @Version} mismatch this raises
     *       {@link org.springframework.dao.OptimisticLockingFailureException}
     *       which propagates uncaught.</li>
     *   <li>Build the success response with the verbatim COBOL
     *       {@code 'User {id} has been updated ...'} message (COBOL
     *       parity: lines 372–375).</li>
     * </ol>
     *
     * <p>Infrastructure errors (database unreachable, network failure,
     * unique-constraint race after the pre-check, etc.) surface as
     * {@link org.springframework.dao.DataAccessException} subclasses thrown
     * by the repository; the service does not catch them, letting the
     * controller layer's exception-handler chain produce the Java
     * equivalent of the COBOL {@code 'Unable to lookup User...'} /
     * {@code 'Unable to Update User...'} response. This mirrors the
     * convention used by {@link UserDeleteService}.
     *
     * @param request the user-update request carrying the target user's
     *                identifier, modified name fields, optional new
     *                plaintext password, modified user type, and JPA
     *                version counter; must not be {@code null} (the
     *                service does not guard against {@code null} — the
     *                controller layer is responsible for producing a
     *                populated request)
     * @return a populated {@link UserUpdateResult} encoding either success
     *         (with the {@code 'User {id} has been updated ...'} message)
     *         or failure (with one of the COBOL-equivalent reject messages)
     * @throws org.springframework.dao.OptimisticLockingFailureException
     *         when the JPA {@code @Version} field on the loaded entity
     *         does not match the persisted version (a concurrent update
     *         happened between this transaction's lookup and save)
     */
    public UserUpdateResult updateUser(UserUpdateRequest request) {
        // Step 1 — Empty user ID check (COBOL parity, line 180).
        // Treats null, empty string, and whitespace-only as equivalent to
        // the COBOL SPACES / LOW-VALUES condition. Performed BEFORE any
        // database access so the repository is never consulted on a
        // malformed request.
        if (isBlank(request.getUserId())) {
            return UserUpdateResult.failure(MSG_USER_ID_EMPTY);
        }

        // Step 2 — Empty first name check (COBOL parity, line 186).
        if (isBlank(request.getFirstName())) {
            return UserUpdateResult.failure(MSG_FIRST_NAME_EMPTY);
        }

        // Step 3 — Empty last name check (COBOL parity, line 192).
        if (isBlank(request.getLastName())) {
            return UserUpdateResult.failure(MSG_LAST_NAME_EMPTY);
        }

        // Step 4 — Empty user type check (COBOL parity, line 204). Note
        // that the COBOL Password empty check at line 198 has no Java
        // equivalent — see "Java Migration: newPassword Semantics" on the
        // class-level Javadoc for the rationale (empty newPassword is the
        // canonical "preserve existing hash" signal, not an error).
        if (isBlank(request.getUserType())) {
            return UserUpdateResult.failure(MSG_USER_TYPE_EMPTY);
        }

        // Step 5 — User type domain check (Java-migration addition).
        // Matches the UserAddService convention; see class-level "Java
        // Migration: Invalid User-Type Reject" rationale on UserAddService.
        // The REST controller layer is reachable independently of any BMS
        // attribute enforcement, so defence in depth requires the service
        // to explicitly validate the domain.
        String userType = request.getUserType();
        if (!USER_TYPE_REGULAR.equals(userType) && !USER_TYPE_ADMIN.equals(userType)) {
            return UserUpdateResult.failure(MSG_INVALID_USER_TYPE);
        }

        // Step 6 — READ USRSEC by SEC-USR-ID primary key (COBOL parity:
        // READ-USER-SEC-FILE line 322). The repository .findById(...)
        // returns Optional.empty() for the DFHRESP(NOTFND) case;
        // Optional.of(user) for DFHRESP(NORMAL). I/O errors (the COBOL
        // WHEN OTHER branch at lines 346–352) propagate as
        // DataAccessException subclasses and are handled by the controller
        // layer's exception-handler chain.
        String userId = request.getUserId();
        Optional<SecurityUser> existingOpt = userSecurityRepository.findById(userId);
        if (existingOpt.isEmpty()) {
            // COBOL: WHEN DFHRESP(NOTFND) → 'User ID NOT found...'
            return UserUpdateResult.failure(MSG_USER_NOT_FOUND);
        }

        SecurityUser entity = existingOpt.get();

        // Step 6b — Optimistic-locking version check (COBOL parity: the
        // before/after-image record comparison in COUSR02C lines 211–217
        // where the program loads the persisted record, compares each
        // field against the screen's "current value" snapshot the
        // operator's edit was based on, and rejects the update with a
        // 'data was changed by another user' message when any field has
        // moved on under the operator's feet).
        //
        // The Java migration carries this guard explicitly: when the
        // request supplies a {@code version} field (the value the
        // operator's edit was based on, loaded earlier by the view
        // endpoint) and that value disagrees with the version on the
        // freshly-loaded entity, raise
        // {@link OptimisticLockingFailureException} BEFORE mutating any
        // field on the entity. The controller layer maps this exception
        // to HTTP 409 Conflict (per UserAdminController's exception
        // handler).
        //
        // Without this explicit check Hibernate's automatic {@code
        // @Version} guard would still fire — but only if the persisted
        // {@code version} column changes between {@code findById(...)}
        // and {@code save(...)} within THIS transaction; the more common
        // case where the request body's version is already stale before
        // the transaction starts (because another operator committed in
        // between the view-load and the update-submit) would silently
        // succeed with Hibernate UPDATE-by-PK because the loaded entity
        // already carries the up-to-date version. The explicit check
        // here ensures the "stale operator edit" path is rejected with
        // the COBOL-equivalent 409 semantics regardless of whether a
        // concurrent transaction fires in this exact instant.
        //
        // The guard is bidirectional-tolerant: when {@code
        // request.getVersion()} is null (legacy client that did not
        // round-trip the version) or {@code entity.getVersion()} is
        // null (entity hydrated outside a JPA flush boundary), the
        // check is skipped — the COBOL baseline did not require a
        // version round-trip and the Java migration must remain
        // backwards-compatible with non-version-aware callers. Once
        // both sides carry a non-null version, mismatch is an error.
        Long requestVersion = request.getVersion();
        Long entityVersion = entity.getVersion();
        if (requestVersion != null
                && entityVersion != null
                && !requestVersion.equals(entityVersion)) {
            throw new OptimisticLockingFailureException(
                    "User " + userId
                            + " was modified by another transaction "
                            + "(request version=" + requestVersion
                            + ", persisted version=" + entityVersion + ")");
        }

        // Step 7 — Apply updates to the loaded entity (COBOL parity: lines
        // 219–234). The COBOL workflow performs per-field comparisons and
        // skips the assignment on unchanged fields; the Java migration
        // assigns unconditionally because (a) Hibernate's dirty-check
        // produces no UPDATE SQL when the assigned value equals the
        // persisted value, and (b) the comparison is symmetric for plain
        // strings (firstName, lastName, userType) but NOT for the
        // BCrypt-hashed password — see the password handling below.
        entity.setFirstName(request.getFirstName());
        entity.setLastName(request.getLastName());
        entity.setUserType(userType);

        // Password handling (Java-migration mechanical divergence; COBOL
        // paragraph: UPDATE-USER-INFO lines 227–230). When newPassword is
        // null or empty, the operator did not change the password — the
        // existing hash is preserved (no setPassword call). When
        // newPassword is non-empty, BCrypt-encode it and overwrite the
        // persisted hash. The encoded value (60-character BCrypt hash) is
        // what gets stored, never the plaintext (AAP §0.10.5).
        String newPassword = request.getNewPassword();
        if (newPassword != null && !newPassword.isEmpty()) {
            entity.setPassword(passwordEncoder.encode(newPassword));
        }

        // Step 8 — Persist via Spring Data JPA. The repository.save(entity)
        // call is the Java equivalent of COBOL EXEC CICS REWRITE DATASET
        // ('USRSEC') FROM (SEC-USER-DATA) RIDFLD (SEC-USR-ID) at line 360.
        // JPA's @Version optimistic-locking check raises
        // OptimisticLockingFailureException on a version mismatch — the
        // service does not catch it, letting it propagate to the controller
        // layer (mapped to HTTP 409 Conflict). Other DataAccessException
        // subclasses (database unreachable, etc.) propagate uncaught too —
        // handled by the controller's exception-handler chain to produce
        // the equivalent of the COBOL 'Unable to Update User...' WHEN
        // OTHER branch (line 386).
        userSecurityRepository.save(entity);

        // Step 9 — Build the success response (COBOL parity: lines 372–375
        // STRING 'User ' SEC-USR-ID ' has been updated ...' INTO WS-MESSAGE).
        // The string concatenation here is the Java equivalent of the COBOL
        // STRING ... DELIMITED BY construct; the resulting message is
        // byte-identical to the COBOL baseline output (modulo SEC-USR-ID's
        // trailing-space trimming, which is unobservable here because the
        // Java field carries the trimmed value).
        return UserUpdateResult.success(MSG_UPDATED_PREFIX + userId + MSG_UPDATED_SUFFIX);
    }

    /**
     * Test whether the supplied string represents the COBOL
     * {@code SPACES OR LOW-VALUES} condition (treats {@code null}, empty,
     * and whitespace-only as equivalent). Centralised here so the four
     * empty-field checks above and the test suite's parity assertions all
     * agree on the same predicate. Matches the {@link UserAddService}
     * convention.
     *
     * @param value the candidate string; may be {@code null}
     * @return {@code true} when the value is {@code null}, empty after
     *         trimming, or contains only whitespace; {@code false}
     *         otherwise
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
