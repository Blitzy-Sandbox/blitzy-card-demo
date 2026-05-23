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

/**
 * Result DTO for {@link UserUpdateService#updateUser(UserUpdateRequest)} — the
 * Java replacement for the {@code COUSR2AO} BMS-mapped output record's
 * {@code ERRMSGO} message field emitted by {@code app/cbl/COUSR02C.cbl}
 * (TRANID {@code CU02}, the admin-only user-update dispatcher). Encodes either
 * a <em>success</em> outcome carrying the
 * {@code 'User {id} has been updated ...'} confirmation, or a <em>failure</em>
 * outcome carrying one of the COBOL-equivalent reject messages.
 *
 * <h2>COBOL Provenance — COUSR02C.cbl</h2>
 *
 * <p>The COBOL workflow writes one of the following messages to
 * {@code ERRMSGO OF COUSR2AO} via {@code SEND-USRUPD-SCREEN} (lines 266–278)
 * depending on the outcome of {@code UPDATE-USER-INFO} (lines 177–245) plus
 * {@code READ-USER-SEC-FILE} (lines 320–353) plus {@code UPDATE-USER-SEC-FILE}
 * (lines 358–390):
 *
 * <ul>
 *   <li><b>Success</b> — {@code UPDATE-USER-SEC-FILE} returns
 *       {@code DFHRESP(NORMAL)} (line 369):
 *       {@code STRING 'User ' SEC-USR-ID ' has been updated ...'}
 *       (lines 372–375). The {@link #success(String)} factory returns a
 *       success outcome carrying that exact message.</li>
 *   <li><b>User ID empty</b> — {@code USRIDINI = SPACES OR LOW-VALUES} in
 *       {@code UPDATE-USER-INFO} (line 180): {@code 'User ID can NOT be
 *       empty...'}.</li>
 *   <li><b>First name empty</b> — {@code FNAMEI = SPACES OR LOW-VALUES} in
 *       {@code UPDATE-USER-INFO} (line 186): {@code 'First Name can NOT
 *       be empty...'}.</li>
 *   <li><b>Last name empty</b> — {@code LNAMEI = SPACES OR LOW-VALUES} in
 *       {@code UPDATE-USER-INFO} (line 192): {@code 'Last Name can NOT be
 *       empty...'}.</li>
 *   <li><b>User type empty</b> — {@code USRTYPEI = SPACES OR LOW-VALUES} in
 *       {@code UPDATE-USER-INFO} (line 204): {@code 'User Type can NOT be
 *       empty...'}.</li>
 *   <li><b>Invalid user type</b> — Java-migration addition (no COBOL
 *       equivalent — see {@link UserUpdateService} class-level note for
 *       rationale). Rejects when {@code userType} is not {@code "U"} or
 *       {@code "A"}.</li>
 *   <li><b>User not found</b> — {@code READ-USER-SEC-FILE} returns
 *       {@code DFHRESP(NOTFND)} (line 340) or {@code UPDATE-USER-SEC-FILE}
 *       returns {@code DFHRESP(NOTFND)} (line 377): {@code 'User ID NOT
 *       found...'}. The Java migration collapses both COBOL branches into
 *       a single {@code Optional.empty()} from
 *       {@link com.aws.carddemo.repository.UserSecurityRepository#findById}.</li>
 * </ul>
 *
 * <p>The COBOL {@code 'Please modify to update ...'} message at lines 239–240
 * (emitted when none of the input fields differ from the loaded record) is
 * intentionally omitted from this DTO's reject taxonomy. The Java migration
 * delegates the "no change" detection to JPA's dirty-checking semantics:
 * if no field changed, {@code save()} still increments {@code @Version}
 * (Hibernate considers a save() call to be a modification), and the operation
 * succeeds. The observable behaviour difference is documented per AAP §0.10.2
 * "All deviations from literal COBOL logic must be documented with the
 * original COBOL paragraph name and reason for divergence".
 *
 * <p>I/O errors (the COBOL {@code WHEN OTHER} branches at lines 346–352
 * {@code 'Unable to lookup User...'} and lines 383–389 {@code 'Unable to
 * Update User...'}) surface in the Java migration as
 * {@link org.springframework.dao.DataAccessException} subclasses propagated
 * from the repository; the service does not catch them, letting the
 * controller layer's exception-handler chain produce the Java equivalent of
 * the COBOL {@code 'Unable to lookup User...'} / {@code 'Unable to Update
 * User...'} response.
 *
 * <h2>Optimistic Locking — JPA @Version Replacement</h2>
 *
 * <p>The Java migration adds JPA optimistic locking via the
 * {@link com.aws.carddemo.entity.SecurityUser#getVersion()} field — a
 * documented Java-migration addition replacing the COBOL READ UPDATE / REWRITE
 * idiom (which serialised concurrent updates through CICS file locks). On a
 * version conflict, Spring Data JPA raises
 * {@link org.springframework.dao.OptimisticLockingFailureException} from the
 * {@code save()} call; the service does not catch it, letting it propagate
 * to the controller exception-handler chain. Tests use
 * {@code assertThatThrownBy(...).isInstanceOf(OptimisticLockingFailureException.class)}
 * to assert the propagation contract; no {@link UserUpdateResult} is returned
 * on this branch.
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so the invariant between {@link #success} and {@link #message}
 * cannot be violated:
 * <ul>
 *   <li>{@link #success(String)} returns {@code success = true} with the
 *       supplied {@code 'User {id} has been updated ...'} message.</li>
 *   <li>{@link #failure(String)} returns {@code success = false} with one
 *       of the COBOL-equivalent (or Java-migration-added) reject messages.</li>
 * </ul>
 *
 * <p>The constructor is private; no production or test code instantiates
 * this class directly. The {@code success} and {@code message} fields are
 * {@code final} (set once at construction) so the result is effectively
 * immutable after the factory returns it.
 *
 * <h2>Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link UserUpdateService} compilation and the {@code UserUpdateServiceTest}
 * unit test suite. Subsequent migration agents (REFACTOR flavor) may extend
 * this DTO with additional fields (for example, the loaded user record for
 * pre-population on the next screen render) when the full Spring MVC
 * controller layer is wired up.
 *
 * @see UserUpdateService
 * @see UserUpdateRequest
 */
public final class UserUpdateResult {

    /**
     * Whether the update operation succeeded ({@code true}) or was rejected
     * ({@code false}). Drives the controller's HTTP status code mapping:
     * success → 200 OK; validation failure → 400 Bad Request; user not found
     * → 404 Not Found; optimistic-lock conflict propagates as
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * (mapped by the controller layer to HTTP 409 Conflict).
     */
    private final boolean success;

    /**
     * Human-readable outcome message — one of the COBOL-equivalent strings
     * enumerated in the class-level COBOL Provenance section, or the
     * Java-migration-added invalid-user-type reject. Never {@code null}
     * (both factory methods require a non-{@code null} message).
     */
    private final String message;

    /**
     * Private constructor enforcing the factory-method-only construction
     * contract. Both fields are populated exactly once.
     *
     * @param success outcome flag
     * @param message outcome message
     */
    private UserUpdateResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * Builds a success outcome carrying the supplied confirmation message.
     *
     * <p>Used by {@link UserUpdateService#updateUser(UserUpdateRequest)} after
     * the JPA {@code save()} call returns successfully; the canonical message
     * format is {@code 'User {id} has been updated ...'} (mirroring the COBOL
     * {@code STRING} construct at lines 372–375).
     *
     * @param message the success confirmation message; expected non-{@code null}
     * @return a success outcome with {@code isSuccess() == true}
     */
    public static UserUpdateResult success(String message) {
        return new UserUpdateResult(true, message);
    }

    /**
     * Builds a failure outcome carrying the supplied reject message.
     *
     * <p>Used by {@link UserUpdateService#updateUser(UserUpdateRequest)} for
     * all reject branches (user-id empty, first-name empty, last-name empty,
     * user-type empty, invalid user type, and user not found).
     *
     * @param message the rejection message; expected non-{@code null} and to
     *                match one of the COBOL-equivalent literals declared on
     *                {@link UserUpdateService}
     * @return a failure outcome with {@code isSuccess() == false}
     */
    public static UserUpdateResult failure(String message) {
        return new UserUpdateResult(false, message);
    }

    /**
     * @return {@code true} for the success outcome; {@code false} for any of
     *         the reject outcomes (user-id empty, first-name empty,
     *         last-name empty, user-type empty, invalid user type, user not
     *         found)
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return the human-readable outcome message (success confirmation or
     *         rejection reason); never {@code null}
     */
    public String getMessage() {
        return message;
    }
}
