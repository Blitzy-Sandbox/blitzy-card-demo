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

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * User-delete service — the Java migration of the 359-line CICS COBOL
 * program {@code app/cbl/COUSR03C.cbl} (TRANID {@code CU03}, the admin-only
 * user-delete dispatcher). Removes a {@link SecurityUser} record from the
 * {@code USRSEC} VSAM KSDS replacement (PostgreSQL {@code security_users}
 * table) after enforcing two reject paths inherited from the COBOL baseline
 * plus one Java-migration-added safety guard.
 *
 * <h2>COBOL Provenance — COUSR03C.cbl</h2>
 *
 * <p>The COBOL workflow combines the {@code DELETE-USER-INFO} paragraph
 * (lines 174–192) with two file-access paragraphs:
 * {@code READ-USER-SEC-FILE} (lines 267–300) and
 * {@code DELETE-USER-SEC-FILE} (lines 305–336). The dispatcher orchestrates:
 *
 * <ol>
 *   <li>Validate {@code USRIDINI OF COUSR3AI} is non-empty (lines 177–182):
 *       {@code 'User ID can NOT be empty...'} reject when blank.</li>
 *   <li>{@code PERFORM READ-USER-SEC-FILE} (line 190) — read the record by
 *       {@code SEC-USR-ID} primary key:
 *       <ul>
 *         <li>{@code DFHRESP(NORMAL)} → continue to the delete step.</li>
 *         <li>{@code DFHRESP(NOTFND)} → {@code 'User ID NOT found...'}
 *             reject (lines 287–292).</li>
 *         <li>{@code WHEN OTHER} → {@code 'Unable to lookup User...'}
 *             reject (lines 293–299) — translated in the Java migration
 *             to {@link org.springframework.dao.DataAccessException}
 *             propagation (handled by the controller's exception chain).</li>
 *       </ul></li>
 *   <li>{@code PERFORM DELETE-USER-SEC-FILE} (line 191) — issue the
 *       {@code EXEC CICS DELETE} on the same dataset:
 *       <ul>
 *         <li>{@code DFHRESP(NORMAL)} → success, returning
 *             {@code 'User {id} has been deleted ...'} (lines 318–322).</li>
 *         <li>{@code DFHRESP(NOTFND)} → rare-race reject (lines 323–328)
 *             collapsed into the same {@code 'User ID NOT found...'}
 *             branch in the Java migration because the JPA repository's
 *             {@code Optional.empty()} response is identical regardless of
 *             whether the row was missing at lookup time or had been
 *             concurrently deleted.</li>
 *         <li>{@code WHEN OTHER} → {@code 'Unable to Update User...'}
 *             reject (lines 329–335) — translated to
 *             {@link org.springframework.dao.DataAccessException}
 *             propagation.</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>Java Migration: Self-Delete Prevention</h2>
 *
 * <p>The COBOL original does not contain an explicit self-delete check
 * because the operational risk in the mainframe environment was mitigated
 * by separate physical sign-on and operations controls. The Java migration
 * adds an explicit guard: if the target user identifier matches the
 * authenticated operator's identifier, the request is rejected with
 * {@link #MSG_CANNOT_DELETE_SELF} BEFORE any database access. This is a
 * documented Java-migration addition (AAP §0.10.2: "All deviations from
 * literal COBOL logic must be documented with the original COBOL paragraph
 * name and reason for divergence"). Reason: the REST controller layer is
 * reachable independently of the admin menu flow, so defence-in-depth
 * requires the service to enforce the self-delete guard directly. The
 * check happens before all other validations to ensure no database state
 * (existence of the user, etc.) is leaked even via observable
 * latency differences.
 *
 * <h2>Validation Order</h2>
 *
 * <p>The service performs validations in the following deliberate order:
 * <ol>
 *   <li><b>Self-delete check</b> (Java-migration addition) — guards against
 *       the operator deleting their own session before any other work.</li>
 *   <li><b>Empty user ID check</b> (COBOL parity, line 177) — guards
 *       against the operator pressing PF5 without entering a user ID.</li>
 *   <li><b>Repository lookup</b> (COBOL parity, line 190) — the
 *       {@code findById} call that maps {@code Optional.empty()} →
 *       {@code DFHRESP(NOTFND)} reject and {@code Optional.of(user)} →
 *       {@code DFHRESP(NORMAL)} success branch.</li>
 *   <li><b>Repository delete</b> (COBOL parity, lines 307–311) — the
 *       {@code delete(SecurityUser)} call.</li>
 *   <li><b>Success response</b> (COBOL parity, lines 318–321) — builds
 *       the {@code 'User {id} has been deleted ...'} confirmation.</li>
 * </ol>
 *
 * <p>Each validation short-circuits and returns immediately on failure;
 * the repository is never consulted on the self-delete or empty-ID reject
 * paths (defence-in-depth verified by the corresponding test suite via
 * {@code verify(repository, never())} assertions).
 *
 * <h2>Constructor Injection (No Spring Stereotype)</h2>
 *
 * <p>This class deliberately omits the {@code @Service} stereotype
 * annotation; subsequent migration agents will add it when the full Spring
 * application context is wired up. For now, the constructor accepts the
 * repository collaborator directly so unit tests can wire a Mockito mock
 * without a Spring context — matching the convention established by
 * {@link AuthenticationService}, {@link UserListService}, and the rest of
 * the service package.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE user-delete dispatcher logic (no
 * helpers extracted to other classes; all branches are visible in the
 * single {@link #deleteUser(UserDeleteRequest)} entry point). The
 * corresponding {@code UserDeleteServiceTest} exercises every branch via
 * real method calls with a mocked {@link UserSecurityRepository} at the
 * database boundary only — no business logic (self-delete check, empty
 * check, dispatch, response construction) is duplicated inside the test.
 *
 * @see UserDeleteRequest
 * @see UserDeleteResult
 * @see UserSecurityRepository
 * @see SecurityUser
 */
@Service
public class UserDeleteService {

    // ---------------------------------------------------------------------
    // Reject messages — verbatim COBOL literals preserved per AAP §0.10.4
    // (Immutable Boundaries: downstream consumers reading the JSON error
    // envelope must see the same textual reason as the COBOL baseline).
    // ---------------------------------------------------------------------

    /**
     * Reject message returned when {@link UserDeleteRequest#getUserId()} is
     * {@code null}, empty, or blank — verbatim COBOL literal from
     * {@code COUSR03C.cbl} {@code DELETE-USER-INFO} (line 179).
     */
    static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Reject message returned when the repository returns
     * {@code Optional.empty()} from {@link UserSecurityRepository#findById(Object)}
     * — verbatim COBOL literal from {@code COUSR03C.cbl}
     * {@code READ-USER-SEC-FILE} (line 289) and {@code DELETE-USER-SEC-FILE}
     * (line 325). Both COBOL branches share the same literal; the Java
     * migration collapses them into a single reject path.
     */
    static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /**
     * Reject message returned when the operator attempts to delete their own
     * user record. This is a Java-migration addition with no direct COBOL
     * equivalent; see the class-level <em>Java Migration: Self-Delete
     * Prevention</em> note for the rationale. The literal does not
     * correspond to any COBOL string and is therefore the canonical
     * Java-side phrasing — anchored on the lower-case word {@code "own"}
     * so the corresponding test assertion ({@code containsIgnoringCase
     * ("own")}) is stable across copy edits.
     */
    static final String MSG_CANNOT_DELETE_SELF = "Cannot delete your own user record";

    /**
     * Prefix for the success message — verbatim leading literal from the
     * COBOL {@code STRING} construct at {@code COUSR03C.cbl} line 318
     * ({@code STRING 'User ' DELIMITED BY SIZE}). The full message is
     * {@code MSG_DELETED_PREFIX + userId + MSG_DELETED_SUFFIX}.
     */
    static final String MSG_DELETED_PREFIX = "User ";

    /**
     * Suffix for the success message — verbatim trailing literal from the
     * COBOL {@code STRING} construct at {@code COUSR03C.cbl} line 320
     * ({@code ' has been deleted ...' DELIMITED BY SIZE}). Includes the
     * leading space and trailing ellipsis from the COBOL literal.
     */
    static final String MSG_DELETED_SUFFIX = " has been deleted ...";

    // ---------------------------------------------------------------------
    // Collaborator boundary — single JPA repository
    // ---------------------------------------------------------------------

    /**
     * JPA repository for {@link SecurityUser} entities — the Java replacement
     * for COBOL {@code EXEC CICS READ DATASET('USRSEC')} and
     * {@code EXEC CICS DELETE DATASET('USRSEC')} in
     * {@code app/cbl/COUSR03C.cbl}. Constructor-injected so unit tests can
     * wire a Mockito mock without a Spring context.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Constructs a new {@code UserDeleteService}.
     *
     * @param userSecurityRepository JPA repository for {@link SecurityUser}
     *                               lookups and deletes (Java replacement
     *                               for COBOL {@code EXEC CICS READ /
     *                               DELETE} on {@code USRSEC}). Must not be
     *                               {@code null} — the service does not
     *                               guard against {@code null} collaborators
     *                               because Spring DI would surface the
     *                               misconfiguration at startup; unit tests
     *                               wire a Mockito mock.
     */
    public UserDeleteService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Delete a {@link SecurityUser} record from the {@code USRSEC} replacement
     * after enforcing the COBOL-inherited reject paths plus the
     * Java-migration-added self-delete guard. Implements the Java equivalent
     * of {@code app/cbl/COUSR03C.cbl} {@code DELETE-USER-INFO} (lines
     * 174–192) plus the {@code READ-USER-SEC-FILE} (lines 267–300) and
     * {@code DELETE-USER-SEC-FILE} (lines 305–336) paragraphs it invokes.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Self-delete guard — if {@code request.getUserId()} equals
     *       {@code request.getCurrentUserId()} (case-sensitive), reject with
     *       {@link #MSG_CANNOT_DELETE_SELF}. The check happens BEFORE any
     *       database access (no repository call is invoked on a rejected
     *       request), eliminating the timing-side-channel that would
     *       otherwise leak user existence.</li>
     *   <li>Empty user ID check — if {@code request.getUserId()} is
     *       {@code null}, empty, or blank, reject with
     *       {@link #MSG_USER_ID_EMPTY}. COBOL parity: line 177 evaluation
     *       of {@code USRIDINI = SPACES OR LOW-VALUES}.</li>
     *   <li>Repository lookup via
     *       {@link UserSecurityRepository#findById(Object)} (COBOL parity:
     *       {@code READ-USER-SEC-FILE} line 269). On
     *       {@code Optional.empty()} → reject with
     *       {@link #MSG_USER_NOT_FOUND}.</li>
     *   <li>Repository delete via
     *       {@link org.springframework.data.jpa.repository.JpaRepository#delete(Object)}
     *       (COBOL parity: {@code DELETE-USER-SEC-FILE} line 307). The
     *       hydrated entity from step 3 is passed to {@code delete(...)} so
     *       optimistic-locking semantics ({@code @Version} field on
     *       {@link SecurityUser}) apply if the entity has been concurrently
     *       modified.</li>
     *   <li>Build the success response with the verbatim COBOL
     *       {@code 'User {id} has been deleted ...'} message (COBOL parity:
     *       lines 318–321).</li>
     * </ol>
     *
     * <p>Infrastructure errors (database unreachable, network failure,
     * optimistic-locking failure, etc.) surface as
     * {@link org.springframework.dao.DataAccessException} subclasses thrown
     * by the repository; the service does not catch them, letting the
     * controller layer's exception-handler chain produce the Java equivalent
     * of the COBOL {@code 'Unable to lookup User...'} / {@code 'Unable to
     * Update User...'} response. This mirrors the convention used by
     * {@link UserListService} and {@link TransactionDetailService}.
     *
     * @param request the user-delete request carrying the target user
     *                identifier and the authenticated operator identifier;
     *                must not be {@code null} (the service does not guard
     *                against {@code null} — the controller layer is
     *                responsible for producing a populated request)
     * @return a populated {@link UserDeleteResult} encoding either success
     *         (with the {@code 'User {id} has been deleted ...'} message)
     *         or failure (with one of the four COBOL-equivalent reject
     *         messages)
     */
    public UserDeleteResult deleteUser(UserDeleteRequest request) {
        String targetUserId = request.getUserId();
        String currentUserId = request.getCurrentUserId();

        // Step 1 — Self-delete guard (Java-migration addition; see class-level
        // "Java Migration: Self-Delete Prevention" note). Performed BEFORE
        // any database access AND before the empty-ID validation so the
        // reject path is uniform regardless of whether the operator entered
        // a non-blank user ID that happens to match their own. The
        // equality test uses .equals because both fields are String; the
        // null-safe check guards against a malformed request that lacks
        // the session identifier (in which case the self-delete branch
        // cannot fire, falling through to the empty-ID branch below).
        if (currentUserId != null && currentUserId.equals(targetUserId)) {
            return UserDeleteResult.failure(MSG_CANNOT_DELETE_SELF);
        }

        // Step 2 — Empty user ID check (COBOL parity: line 177 evaluation
        // of USRIDINI = SPACES OR LOW-VALUES). Treats null, empty string,
        // and whitespace-only as equivalent to the COBOL SPACES /
        // LOW-VALUES condition. Performed BEFORE any database access so
        // the repository is never consulted on a malformed request.
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            return UserDeleteResult.failure(MSG_USER_ID_EMPTY);
        }

        // Step 3 — READ USRSEC by SEC-USR-ID primary key (COBOL parity:
        // READ-USER-SEC-FILE line 269). The repository .findById(...)
        // returns Optional.empty() for the DFHRESP(NOTFND) case;
        // Optional.of(user) for DFHRESP(NORMAL). I/O errors (the COBOL
        // WHEN OTHER branch) propagate as DataAccessException subclasses
        // and are handled by the controller layer's exception-handler chain,
        // mirroring the COBOL HANDLE ABEND fallback.
        Optional<SecurityUser> userOpt = userSecurityRepository.findById(targetUserId);
        if (userOpt.isEmpty()) {
            // COBOL: WHEN DFHRESP(NOTFND) → 'User ID NOT found...'
            return UserDeleteResult.failure(MSG_USER_NOT_FOUND);
        }

        // Step 4 — DELETE USRSEC (COBOL parity: DELETE-USER-SEC-FILE line
        // 307). Passing the hydrated entity rather than the bare ID
        // engages JPA optimistic-locking semantics if the SecurityUser
        // entity carries an @Version field, providing the Java equivalent
        // of the COBOL before-image/after-image record comparison.
        SecurityUser user = userOpt.get();
        userSecurityRepository.delete(user);

        // Step 5 — Build the success response (COBOL parity: lines 318–321
        // STRING 'User ' SEC-USR-ID ' has been deleted ...' INTO WS-MESSAGE).
        // The string concatenation here is the Java equivalent of the COBOL
        // STRING ... DELIMITED BY construct; the resulting message is byte-
        // identical to the COBOL baseline output (modulo SEC-USR-ID's
        // trailing-space trimming, which is unobservable here because the
        // Java field carries the trimmed value).
        return UserDeleteResult.success(MSG_DELETED_PREFIX + targetUserId + MSG_DELETED_SUFFIX);
    }
}
