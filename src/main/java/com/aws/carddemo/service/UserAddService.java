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

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * User-add service — the Java migration of the 299-line CICS COBOL program
 * {@code app/cbl/COUSR01C.cbl} (TRANID {@code CU01}, the admin-only user-add
 * dispatcher). Inserts a new {@link SecurityUser} record into the
 * {@code USRSEC} VSAM KSDS replacement (PostgreSQL {@code security_users}
 * table) after enforcing five empty-field rejects inherited from the COBOL
 * baseline, plus a duplicate-key reject (COBOL {@code DFHRESP(DUPKEY)}
 * parity) and a Java-migration-added invalid-user-type reject.
 *
 * <h2>COBOL Provenance — COUSR01C.cbl</h2>
 *
 * <p>The COBOL workflow combines the {@code PROCESS-ENTER-KEY} paragraph
 * (lines 115–160) with the {@code WRITE-USER-SEC-FILE} paragraph
 * (lines 238–274). The dispatcher orchestrates:
 *
 * <ol>
 *   <li>Validate {@code FNAMEI OF COUSR1AI} is non-empty (lines 118–123):
 *       {@code 'First Name can NOT be empty...'} reject when blank.</li>
 *   <li>Validate {@code LNAMEI OF COUSR1AI} is non-empty (lines 124–129):
 *       {@code 'Last Name can NOT be empty...'} reject when blank.</li>
 *   <li>Validate {@code USERIDI OF COUSR1AI} is non-empty (lines 130–135):
 *       {@code 'User ID can NOT be empty...'} reject when blank.</li>
 *   <li>Validate {@code PASSWDI OF COUSR1AI} is non-empty (lines 136–141):
 *       {@code 'Password can NOT be empty...'} reject when blank.</li>
 *   <li>Validate {@code USRTYPEI OF COUSR1AI} is non-empty (lines 142–147):
 *       {@code 'User Type can NOT be empty...'} reject when blank.</li>
 *   <li>{@code PERFORM WRITE-USER-SEC-FILE} (line 159) — issue the
 *       {@code EXEC CICS WRITE} on the {@code USRSEC} dataset:
 *       <ul>
 *         <li>{@code DFHRESP(NORMAL)} → success, returning
 *             {@code 'User {id} has been added ...'} (lines 251–259).</li>
 *         <li>{@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} →
 *             {@code 'User ID already exist...'} reject (lines 260–266).</li>
 *         <li>{@code WHEN OTHER} → {@code 'Unable to Add User...'}
 *             reject (lines 267–273) — translated in the Java migration
 *             to {@link org.springframework.dao.DataAccessException}
 *             propagation (handled by the controller's exception chain).</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>Java Migration: BCrypt Hashing of Plaintext Password</h2>
 *
 * <p>The COBOL original stored {@code SEC-USR-PWD PIC X(08)} as plaintext
 * (line 157: {@code MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD}). The Java
 * migration hashes the plaintext via {@code passwordEncoder.encode(password)}
 * before persisting it to the {@link SecurityUser#setPassword(String)} field;
 * the BCrypt hash (60 characters: {@code $2a$10$<22-char-salt><31-char-hash>})
 * is what gets stored, never the plaintext. Per AAP §0.10.5 "No plaintext
 * credentials in any configuration file". The injection of the
 * {@link PasswordEncoder} collaborator (rather than constructing
 * {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}
 * inline) keeps the service decoupled from the encoder implementation and
 * allows the test suite to verify hashing with a real, fast BCrypt instance.
 *
 * <h2>Java Migration: Duplicate Detection via findById Pre-Check</h2>
 *
 * <p>The COBOL workflow relied on the {@code EXEC CICS WRITE} command to
 * surface {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} when the
 * primary-key was already in use; the Java JPA equivalent does not have a
 * single-step "insert-or-fail-on-duplicate" idiom that surfaces the same
 * signal cleanly across all JPA providers and database backends. The Java
 * migration therefore performs an explicit {@code findById} pre-check: if
 * the lookup returns {@code Optional.of(existing)}, the service rejects
 * with the {@link #MSG_USER_ID_ALREADY_EXISTS} message before attempting
 * any save. This is observationally equivalent to the COBOL DUPKEY branch
 * (the operator sees the same message either way) and is documented as a
 * Java-migration mechanical divergence per AAP §0.10.2.
 *
 * <h2>Java Migration: Invalid User-Type Reject</h2>
 *
 * <p>The COBOL original did not enforce that {@code USRTYPEI} is one of
 * {@code "U"} or {@code "A"} because the BMS map's input attributes
 * restricted the operator to single-character entry. The Java migration
 * adds an explicit invalid-user-type reject because the REST controller
 * layer is reachable independently of any BMS attribute enforcement, and
 * an invalid value would otherwise propagate to the
 * {@link SecurityUser#setUserType(String)} field and corrupt downstream
 * authorisation decisions. The reject anchors on the load-bearing
 * {@code "type"} token so the corresponding test assertion is stable across
 * copy edits.
 *
 * <h2>Validation Order</h2>
 *
 * <p>The service performs validations in the deliberate order documented
 * by the COBOL {@code PROCESS-ENTER-KEY} cascade:
 * <ol>
 *   <li><b>First name empty check</b> (COBOL parity, line 118)</li>
 *   <li><b>Last name empty check</b> (COBOL parity, line 124)</li>
 *   <li><b>User ID empty check</b> (COBOL parity, line 130)</li>
 *   <li><b>Password empty check</b> (COBOL parity, line 136)</li>
 *   <li><b>User type empty check</b> (COBOL parity, line 142)</li>
 *   <li><b>User type domain check</b> (Java-migration addition)</li>
 *   <li><b>Repository duplicate-key pre-check</b> (Java-migration mechanical
 *       divergence from COBOL DFHRESP(DUPKEY) — observable behaviour
 *       unchanged)</li>
 *   <li><b>Repository save</b> with BCrypt-hashed password (COBOL parity:
 *       {@code WRITE-USER-SEC-FILE} line 240; password column carries the
 *       BCrypt hash per AAP §0.10.5)</li>
 *   <li><b>Success response</b> (COBOL parity: lines 255–258)</li>
 * </ol>
 *
 * <p>Each validation short-circuits and returns immediately on failure;
 * the repository is never consulted on any of the empty-field reject paths
 * or the invalid-user-type reject (defence-in-depth verified by the
 * corresponding test suite via {@code verify(repository, never())}
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
 * {@link AuthenticationService}, {@link UserListService},
 * {@link UserDeleteService}, and the rest of the service package.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE user-add dispatcher logic (no helpers
 * extracted to other classes; all branches are visible in the single
 * {@link #addUser(UserAddRequest)} entry point). The corresponding
 * {@code UserAddServiceTest} exercises every branch via real method calls
 * with a mocked {@link UserSecurityRepository} at the database boundary
 * and a real {@link PasswordEncoder} so the BCrypt encoding contract is
 * actually exercised (rather than mock-stubbed). No business logic (empty
 * checks, user-type domain check, dispatch, BCrypt encoding, response
 * construction) is duplicated inside the test.
 *
 * @see UserAddRequest
 * @see UserAddResult
 * @see UserSecurityRepository
 * @see SecurityUser
 * @see PasswordEncoder
 */
@Service
public class UserAddService {

    // ---------------------------------------------------------------------
    // Reject messages — verbatim COBOL literals preserved per AAP §0.10.4
    // (Immutable Boundaries: downstream consumers reading the JSON error
    // envelope must see the same textual reason as the COBOL baseline).
    // ---------------------------------------------------------------------

    /**
     * Reject message returned when {@link UserAddRequest#getFirstName()} is
     * {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR01C.cbl} {@code PROCESS-ENTER-KEY} (line 120).
     */
    static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /**
     * Reject message returned when {@link UserAddRequest#getLastName()} is
     * {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR01C.cbl} {@code PROCESS-ENTER-KEY} (line 126).
     */
    static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /**
     * Reject message returned when {@link UserAddRequest#getUserId()} is
     * {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR01C.cbl} {@code PROCESS-ENTER-KEY} (line 132).
     */
    static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Reject message returned when {@link UserAddRequest#getPassword()} is
     * {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR01C.cbl} {@code PROCESS-ENTER-KEY} (line 138).
     */
    static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /**
     * Reject message returned when {@link UserAddRequest#getUserType()} is
     * {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR01C.cbl} {@code PROCESS-ENTER-KEY} (line 144).
     */
    static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * Reject message returned when {@link UserAddRequest#getUserType()} is
     * not {@code "U"} or {@code "A"}. This is a Java-migration addition with
     * no direct COBOL equivalent (see the class-level <em>Java Migration:
     * Invalid User-Type Reject</em> note for the rationale). The literal
     * does not correspond to any COBOL string and is therefore the canonical
     * Java-side phrasing — anchored on the lower-case word {@code "type"}
     * so the corresponding test assertion remains stable across copy edits.
     */
    static final String MSG_INVALID_USER_TYPE = "User Type must be 'U' or 'A'...";

    /**
     * Reject message returned when the {@code findById} pre-check returns
     * {@code Optional.of(existing)} — verbatim COBOL literal from
     * {@code COUSR01C.cbl} {@code WRITE-USER-SEC-FILE} (line 263). The COBOL
     * original surfaced this via {@code DFHRESP(DUPKEY)} on the WRITE; the
     * Java migration short-circuits via the findById pre-check (see
     * class-level <em>Java Migration: Duplicate Detection via findById
     * Pre-Check</em> note).
     */
    static final String MSG_USER_ID_ALREADY_EXISTS = "User ID already exist...";

    /**
     * Prefix for the success message — verbatim leading literal from the
     * COBOL {@code STRING} construct at {@code COUSR01C.cbl} line 255
     * ({@code STRING 'User ' DELIMITED BY SIZE}). The full message is
     * {@code MSG_ADDED_PREFIX + userId + MSG_ADDED_SUFFIX}.
     */
    static final String MSG_ADDED_PREFIX = "User ";

    /**
     * Suffix for the success message — verbatim trailing literal from the
     * COBOL {@code STRING} construct at {@code COUSR01C.cbl} line 257
     * ({@code ' has been added ...' DELIMITED BY SIZE}). Includes the
     * leading space and trailing ellipsis from the COBOL literal.
     */
    static final String MSG_ADDED_SUFFIX = " has been added ...";

    /**
     * Regular-user type code — COBOL {@code CDEMO-USRTYP-USER} 88-level value.
     * Constants are package-private to expose them to the corresponding test
     * suite via package-membership access.
     */
    static final String USER_TYPE_REGULAR = "U";

    /**
     * Admin user-type code — COBOL {@code CDEMO-USRTYP-ADMIN} 88-level value.
     */
    static final String USER_TYPE_ADMIN = "A";

    // ---------------------------------------------------------------------
    // Collaborators — JPA repository + password encoder boundary
    // ---------------------------------------------------------------------

    /**
     * JPA repository for {@link SecurityUser} entities — the Java replacement
     * for COBOL {@code EXEC CICS READ DATASET('USRSEC')} (used for the
     * duplicate-key pre-check) and {@code EXEC CICS WRITE DATASET('USRSEC')}
     * (used for the insert) in {@code app/cbl/COUSR01C.cbl}.
     * Constructor-injected so unit tests can wire a Mockito mock without a
     * Spring context.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * BCrypt password encoder — converts the plaintext password supplied on
     * the {@link UserAddRequest} into a 60-character BCrypt hash before
     * persisting. Constructor-injected so unit tests can supply a real
     * {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}
     * instance (the test suite verifies real BCrypt semantics, not a stubbed
     * encoder, per AAP §0.10.5).
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructs a new {@code UserAddService}.
     *
     * @param userSecurityRepository JPA repository for {@link SecurityUser}
     *                               lookups and inserts (Java replacement
     *                               for COBOL {@code EXEC CICS READ / WRITE}
     *                               on {@code USRSEC}). Must not be
     *                               {@code null} — the service does not
     *                               guard against {@code null} collaborators
     *                               because Spring DI would surface the
     *                               misconfiguration at startup; unit tests
     *                               wire a Mockito mock.
     * @param passwordEncoder        password encoder (typically
     *                               {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder})
     *                               used to hash the plaintext password
     *                               supplied by the operator into a BCrypt
     *                               hash before persisting. Must not be
     *                               {@code null}.
     */
    public UserAddService(UserSecurityRepository userSecurityRepository,
                          PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Add a new {@link SecurityUser} record to the {@code USRSEC} replacement
     * after enforcing the COBOL-inherited validation cascade plus the
     * Java-migration-added duplicate-detection pre-check and invalid-user-type
     * reject. Implements the Java equivalent of {@code app/cbl/COUSR01C.cbl}
     * {@code PROCESS-ENTER-KEY} (lines 115–160) plus the
     * {@code WRITE-USER-SEC-FILE} (lines 238–274) paragraph it invokes.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Empty first name check — if {@code request.getFirstName()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_FIRST_NAME_EMPTY}. COBOL parity: line 118.</li>
     *   <li>Empty last name check — if {@code request.getLastName()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_LAST_NAME_EMPTY}. COBOL parity: line 124.</li>
     *   <li>Empty user ID check — if {@code request.getUserId()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_USER_ID_EMPTY}. COBOL parity: line 130.</li>
     *   <li>Empty password check — if {@code request.getPassword()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_PASSWORD_EMPTY}. COBOL parity: line 136.</li>
     *   <li>Empty user type check — if {@code request.getUserType()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_USER_TYPE_EMPTY}. COBOL parity: line 142.</li>
     *   <li>User type domain check — if {@code request.getUserType()} is
     *       neither {@code "U"} nor {@code "A"}, reject with
     *       {@link #MSG_INVALID_USER_TYPE}. Java-migration addition.</li>
     *   <li>Repository duplicate-key pre-check via
     *       {@link UserSecurityRepository#findById(Object)}. On
     *       {@code Optional.of(existing)} → reject with
     *       {@link #MSG_USER_ID_ALREADY_EXISTS} (COBOL
     *       {@code DFHRESP(DUPKEY)} parity, line 263).</li>
     *   <li>Build a {@link SecurityUser} entity populated from the request
     *       fields, with the {@code password} field set to
     *       {@code passwordEncoder.encode(request.getPassword())} (BCrypt
     *       hash, never plaintext).</li>
     *   <li>Persist via {@link UserSecurityRepository#save(Object)} (COBOL
     *       parity: {@code WRITE-USER-SEC-FILE} line 240).</li>
     *   <li>Build the success response with the verbatim COBOL
     *       {@code 'User {id} has been added ...'} message (COBOL parity:
     *       lines 255–258).</li>
     * </ol>
     *
     * <p>Infrastructure errors (database unreachable, network failure,
     * unique-constraint race after the pre-check, etc.) surface as
     * {@link org.springframework.dao.DataAccessException} subclasses thrown
     * by the repository; the service does not catch them, letting the
     * controller layer's exception-handler chain produce the Java equivalent
     * of the COBOL {@code 'Unable to Add User...'} response. This mirrors
     * the convention used by {@link UserDeleteService}.
     *
     * @param request the user-add request carrying the new user's identifier,
     *                names, plaintext password, and user type; must not be
     *                {@code null} (the service does not guard against
     *                {@code null} — the controller layer is responsible for
     *                producing a populated request)
     * @return a populated {@link UserAddResult} encoding either success
     *         (with the {@code 'User {id} has been added ...'} message) or
     *         failure (with one of the seven COBOL-equivalent or
     *         Java-migration-added reject messages)
     */
    public UserAddResult addUser(UserAddRequest request) {
        // Step 1 — Empty first name check (COBOL parity, line 118).
        // Treats null, empty string, and whitespace-only as equivalent to
        // the COBOL SPACES / LOW-VALUES condition. Performed BEFORE any
        // database access so the repository is never consulted on a
        // malformed request.
        if (isBlank(request.getFirstName())) {
            return UserAddResult.failure(MSG_FIRST_NAME_EMPTY);
        }

        // Step 2 — Empty last name check (COBOL parity, line 124).
        if (isBlank(request.getLastName())) {
            return UserAddResult.failure(MSG_LAST_NAME_EMPTY);
        }

        // Step 3 — Empty user ID check (COBOL parity, line 130).
        if (isBlank(request.getUserId())) {
            return UserAddResult.failure(MSG_USER_ID_EMPTY);
        }

        // Step 4 — Empty password check (COBOL parity, line 136).
        if (isBlank(request.getPassword())) {
            return UserAddResult.failure(MSG_PASSWORD_EMPTY);
        }

        // Step 5 — Empty user type check (COBOL parity, line 142).
        if (isBlank(request.getUserType())) {
            return UserAddResult.failure(MSG_USER_TYPE_EMPTY);
        }

        // Step 6 — User type domain check (Java-migration addition;
        // see class-level "Java Migration: Invalid User-Type Reject" note).
        // The COBOL BMS map's input attributes implicitly restricted
        // USRTYPEI to single-character entry, but the REST controller layer
        // is reachable without that enforcement — defence in depth requires
        // the service to explicitly validate the domain.
        String userType = request.getUserType();
        if (!USER_TYPE_REGULAR.equals(userType) && !USER_TYPE_ADMIN.equals(userType)) {
            return UserAddResult.failure(MSG_INVALID_USER_TYPE);
        }

        // Step 7 — Duplicate-key pre-check (COBOL DFHRESP(DUPKEY) parity
        // line 263, implemented as a JPA findById query). See class-level
        // "Java Migration: Duplicate Detection via findById Pre-Check"
        // note for the rationale. The pre-check eliminates the rare-race
        // between findById and save by relying on the unique-constraint
        // on user_id at the database layer as the final defence — a
        // race-condition INSERT after our pre-check returned empty would
        // throw DataIntegrityViolationException, which propagates out of
        // this method as documented.
        String userId = request.getUserId();
        if (userSecurityRepository.findById(userId).isPresent()) {
            return UserAddResult.failure(MSG_USER_ID_ALREADY_EXISTS);
        }

        // Step 8 — Build the SecurityUser entity with the BCrypt-hashed
        // password. Per AAP §0.10.5 ("No plaintext credentials"), the
        // request's plaintext password leaves this DTO only via
        // passwordEncoder.encode(...); the SecurityUser.password field
        // stores the 60-character BCrypt hash, never the plaintext.
        SecurityUser entity = new SecurityUser();
        entity.setUserId(userId);
        entity.setFirstName(request.getFirstName());
        entity.setLastName(request.getLastName());
        entity.setPassword(passwordEncoder.encode(request.getPassword()));
        entity.setUserType(userType);

        // Step 9 — Persist via Spring Data JPA. The repository.save(entity)
        // call is the Java equivalent of COBOL EXEC CICS WRITE DATASET
        // ('USRSEC') FROM (SEC-USER-DATA) RIDFLD (SEC-USR-ID) at line 240.
        // DataAccessException (database unreachable, unique-constraint
        // race, etc.) propagates uncaught — handled by the controller's
        // exception-handler chain to produce the equivalent of the COBOL
        // 'Unable to Add User...' WHEN OTHER branch (line 270).
        userSecurityRepository.save(entity);

        // Step 10 — Build the success response (COBOL parity: lines 255–258
        // STRING 'User ' SEC-USR-ID ' has been added ...' INTO WS-MESSAGE).
        // The string concatenation here is the Java equivalent of the
        // COBOL STRING ... DELIMITED BY construct; the resulting message
        // is byte-identical to the COBOL baseline output modulo
        // SEC-USR-ID's trailing-space trimming (unobservable here because
        // the Java field carries the trimmed value).
        return UserAddResult.success(MSG_ADDED_PREFIX + userId + MSG_ADDED_SUFFIX);
    }

    /**
     * Test whether the supplied string represents the COBOL
     * {@code SPACES OR LOW-VALUES} condition (treats {@code null}, empty,
     * and whitespace-only as equivalent). Centralised here so the five
     * empty-field checks above and the test suite's parity assertions all
     * agree on the same predicate.
     *
     * @param value the candidate string; may be {@code null}
     * @return {@code true} when the value is {@code null}, empty after
     *         trimming, or contains only whitespace; {@code false} otherwise
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
