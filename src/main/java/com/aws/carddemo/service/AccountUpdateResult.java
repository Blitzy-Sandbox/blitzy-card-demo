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
 * Result DTO for
 * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)} — the Java
 * replacement for the {@code CACTUPA} BMS-mapped output record's
 * {@code WS-RETURN-MSG} / {@code WS-INFO-MSG} fields emitted by
 * {@code app/cbl/COACTUPC.cbl} (TRANID {@code CAUP}, the account-update
 * dispatcher and the largest CICS program in the CardDemo migration at 4,236
 * lines). Encodes either a <em>success</em> outcome carrying the
 * {@code 'Changes committed to database'} confirmation (COBOL
 * {@code CONFIRM-UPDATE-SUCCESS} at line 475), or a <em>failure</em> outcome
 * carrying one of the COBOL-equivalent reject messages enumerated in the
 * {@link AccountUpdateService} class-level Javadoc.
 *
 * <h2>COBOL Provenance — COACTUPC.cbl Reject Catalogue</h2>
 *
 * <p>The COBOL workflow assigns one of the following messages to
 * {@code WS-RETURN-MSG} depending on the outcome of the validation cascade
 * and the {@code 9600-WRITE-PROCESSING} dual-write flow (lines 3888-4100):
 *
 * <ul>
 *   <li><b>Success</b> — {@code CONFIRM-UPDATE-SUCCESS} (line 475):
 *       {@code 'Changes committed to database'}. The {@link #success(String)}
 *       factory returns a success outcome carrying that exact message.</li>
 *   <li><b>Account not found / lock failed</b> —
 *       {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} (line 517):
 *       {@code 'Could not lock account record for update'}. Mirrors the
 *       COBOL {@code DFHRESP(NOTFND)} response on the
 *       {@code EXEC CICS READ FILE('ACCTDAT') UPDATE} at line 3897.</li>
 *   <li><b>Customer not found / lock failed</b> —
 *       {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} (line 520):
 *       {@code 'Could not lock customer record for update'}. Mirrors the
 *       COBOL {@code DFHRESP(NOTFND)} response on the
 *       {@code EXEC CICS READ FILE('CUSTDAT') UPDATE} at line 3925.</li>
 *   <li><b>Account active status invalid</b> —
 *       {@code ACCT-STATUS-MUST-BE-YES-NO} (line 506):
 *       {@code 'Account Active Status must be Y or N'}.</li>
 *   <li><b>Credit limit invalid</b> — {@code CRED-LIMIT-IS-NOT-VALID}
 *       (line 510): {@code 'Credit Limit is not valid'}.</li>
 *   <li><b>SSN invalid</b> — Java-migration message preserving the COBOL
 *       {@code INVALID-SSN-PART1} 88-level condition at line 121
 *       ({@code VALUES 0, 666, 900 THRU 999}).</li>
 *   <li><b>Phone number invalid</b> — Java-migration message preserving
 *       the COBOL {@code WS-EDIT-US-PHONE-IS-INVALID} 88-level condition
 *       at line 102 ({@code VALUE '000'} on the phone area code).</li>
 *   <li><b>Optimistic-lock conflict</b> —
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line 522):
 *       {@code 'Record changed by some one else. Please review'}. NOT
 *       returned by this DTO — the Java migration propagates
 *       {@link org.springframework.dao.OptimisticLockingFailureException}
 *       uncaught so the controller layer maps it to HTTP 409 Conflict and
 *       so Spring's {@code @Transactional(rollbackFor=Exception.class)}
 *       boundary rolls back both the account and customer writes
 *       atomically (Java parity for COBOL
 *       {@code EXEC CICS SYNCPOINT ROLLBACK} at line 4100).</li>
 * </ul>
 *
 * <p>The COBOL {@code LOCKED-BUT-UPDATE-FAILED} (line 524,
 * {@code 'Update of record failed'}) and {@code INFORM-FAILURE} (line 477,
 * {@code 'Changes unsuccessful. Please try again'}) catch-all messages are
 * not surfaced by this DTO; the Java migration lets data-access exceptions
 * propagate to the controller's exception-handler chain, which produces a
 * generic HTTP 5xx response envelope. This divergence is documented per AAP
 * §0.10.2 (Minimal Change Clause — exception flow restructured to match
 * Spring idioms).
 *
 * <h2>Optimistic Locking + SYNCPOINT ROLLBACK — JPA @Version Replacement</h2>
 *
 * <p>The COBOL {@code 9600-WRITE-PROCESSING} paragraph (lines 3888-4100)
 * acquires CICS file locks on both ACCTDAT and CUSTDAT via
 * {@code EXEC CICS READ UPDATE}, performs the
 * {@code 9700-CHECK-CHANGE-IN-REC} comparison to detect concurrent updates,
 * REWRITEs ACCTDAT, REWRITEs CUSTDAT, and uses
 * {@code EXEC CICS SYNCPOINT ROLLBACK} (line 4100) to undo the account
 * REWRITE if the customer REWRITE fails. The Java migration replaces all of
 * this with:
 * <ul>
 *   <li>JPA {@code @Version} optimistic-locking fields on
 *       {@link com.aws.carddemo.entity.Account} and
 *       {@link com.aws.carddemo.entity.Customer} — concurrent updates raise
 *       {@link org.springframework.dao.OptimisticLockingFailureException}
 *       at {@code save()} time.</li>
 *   <li>{@code @Transactional(rollbackFor = Exception.class)} on
 *       {@link AccountUpdateService#updateAccount} — Spring rolls back both
 *       the account and customer writes atomically if either throws.</li>
 * </ul>
 *
 * <p>Tests verify the optimistic-lock contract via
 * {@code assertThatThrownBy(...).isInstanceOf(OptimisticLockingFailureException.class)};
 * no {@link AccountUpdateResult} is produced on the version-mismatch branch.
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so the invariant between {@link #success} and {@link #message}
 * cannot be violated:
 * <ul>
 *   <li>{@link #success(String)} returns {@code success = true} with the
 *       supplied confirmation message.</li>
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
 * {@link AccountUpdateService} compilation and the
 * {@code AccountUpdateServiceTest} unit test suite. Subsequent migration
 * agents (REFACTOR flavor) may extend this DTO with additional fields (for
 * example, the loaded account and customer records for pre-population on
 * the next screen render) when the full Spring MVC controller layer is
 * wired up.
 *
 * @see AccountUpdateService
 * @see AccountUpdateRequest
 */
public final class AccountUpdateResult {

    /**
     * Whether the update operation succeeded ({@code true}) or was rejected
     * ({@code false}). Drives the controller's HTTP status code mapping:
     * success → 200 OK; validation failure → 400 Bad Request; account or
     * customer not found → 404 Not Found; optimistic-lock conflict
     * propagates as
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * (mapped by the controller layer to HTTP 409 Conflict).
     */
    private final boolean success;

    /**
     * Human-readable outcome message — one of the COBOL-equivalent strings
     * enumerated in the class-level COBOL Provenance section, or the
     * Java-migration-added invalid-SSN / invalid-phone-area-code reject.
     * Never {@code null} (both factory methods require a non-{@code null}
     * message).
     */
    private final String message;

    /**
     * Private constructor enforcing the factory-method-only construction
     * contract. Both fields are populated exactly once.
     *
     * @param success outcome flag
     * @param message outcome message
     */
    private AccountUpdateResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * Builds a success outcome carrying the supplied confirmation message.
     *
     * <p>Used by
     * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)} after
     * the dual JPA {@code save()} calls return successfully; the canonical
     * message is {@code 'Changes committed to database'} (mirroring the
     * COBOL {@code CONFIRM-UPDATE-SUCCESS} 88-level literal at
     * {@code COACTUPC.cbl} line 475).
     *
     * @param message the success confirmation message; expected non-{@code null}
     * @return a success outcome with {@code isSuccess() == true}
     */
    public static AccountUpdateResult success(String message) {
        return new AccountUpdateResult(true, message);
    }

    /**
     * Builds a failure outcome carrying the supplied reject message.
     *
     * <p>Used by
     * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)} for
     * all reject branches (account-not-found, customer-not-found, SSN
     * invalid, phone area code invalid, credit limit invalid, account
     * active status invalid).
     *
     * @param message the rejection message; expected non-{@code null} and to
     *                match one of the COBOL-equivalent literals declared on
     *                {@link AccountUpdateService}
     * @return a failure outcome with {@code isSuccess() == false}
     */
    public static AccountUpdateResult failure(String message) {
        return new AccountUpdateResult(false, message);
    }

    /**
     * @return {@code true} for the success outcome; {@code false} for any of
     *         the reject outcomes
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
