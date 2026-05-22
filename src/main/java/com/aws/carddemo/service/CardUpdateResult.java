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
 * Result DTO for {@link CardUpdateService#updateCard(CardUpdateRequest)} —
 * the Java replacement for the {@code CCRDUPA} BMS-mapped output record's
 * {@code WS-RETURN-MSG} / {@code WS-INFO-MSG} fields emitted by
 * {@code app/cbl/COCRDUPC.cbl} (TRANID {@code CCUP}, the card-update
 * dispatcher). Encodes either a <em>success</em> outcome carrying the
 * {@code 'Changes committed to database'} confirmation (COBOL
 * {@code CONFIRM-UPDATE-SUCCESS} at line 169), or a <em>failure</em>
 * outcome carrying one of the COBOL-equivalent reject messages enumerated
 * in the {@link CardUpdateService} class-level Javadoc.
 *
 * <h2>COBOL Provenance — COCRDUPC.cbl Reject Catalogue</h2>
 *
 * <p>The COBOL workflow assigns one of the following messages to
 * {@code WS-RETURN-MSG} depending on the outcome of the validation cascade
 * ({@code 1210-EDIT-ACCOUNT} through {@code 1260-EDIT-EXPIRY-YEAR}) and the
 * downstream READ UPDATE / REWRITE flow ({@code 9200-WRITE-PROCESSING}):
 *
 * <ul>
 *   <li><b>Success</b> — {@code CONFIRM-UPDATE-SUCCESS} (line 169):
 *       {@code 'Changes committed to database'}. The {@link #success(String)}
 *       factory returns a success outcome carrying that exact message.</li>
 *   <li><b>Card number not provided</b> — {@code WS-PROMPT-FOR-CARD}
 *       (line 181): {@code 'Card number not provided'}.</li>
 *   <li><b>Card number not numeric / wrong length</b> —
 *       {@code SEARCHED-CARD-NOT-NUMERIC} (line 196):
 *       {@code 'Card number if supplied must be a 16 digit number'}.</li>
 *   <li><b>Card name not provided</b> — {@code WS-PROMPT-FOR-NAME}
 *       (line 183): {@code 'Card name not provided'}.</li>
 *   <li><b>CVV invalid</b> — Java-migration addition (no direct COBOL
 *       equivalent; the COBOL workflow preserves CVV from the existing
 *       record and does not validate it as an operator input, but the
 *       REST API exposes it as an editable field per the
 *       {@link CardUpdateRequest#getCvvCode()} contract).</li>
 *   <li><b>Card Active Status invalid</b> —
 *       {@code CARD-STATUS-MUST-BE-YES-NO} (line 198):
 *       {@code 'Card Active Status must be Y or N'}.</li>
 *   <li><b>Card expiration date invalid</b> — collapsed from the COBOL
 *       {@code CARD-EXPIRY-MONTH-NOT-VALID} (line 200) and
 *       {@code CARD-EXPIRY-YEAR-NOT-VALID} (line 202) into a single
 *       strict-parse reject in the Java migration.</li>
 *   <li><b>Card not found</b> — {@code COULD-NOT-LOCK-FOR-UPDATE}
 *       (line 205) / {@code DID-NOT-FIND-ACCTCARD-COMBO} (line 203):
 *       {@code 'Did not find cards for this search condition'}.</li>
 *   <li><b>Optimistic-lock conflict</b> —
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line 207):
 *       {@code 'Record changed by some one else. Please review'}. NOT
 *       returned by this DTO — the Java migration propagates
 *       {@link org.springframework.dao.OptimisticLockingFailureException}
 *       uncaught so the controller layer maps it to HTTP 409 Conflict.</li>
 * </ul>
 *
 * <p>The COBOL {@code INFORM-FAILURE} message at line 172
 * ({@code 'Changes unsuccessful. Please try again'}) is the catch-all
 * fallback when the controlled-abort {@code EXEC CICS HANDLE ABEND}
 * (line 1546) traps an irrecoverable error. The Java migration does not
 * use this catch-all because Spring's transactional rollback boundary
 * automatically reverts the persistent state on any
 * {@link org.springframework.dao.DataAccessException}; the controller layer
 * surfaces a HTTP 5xx response with a generic error envelope. This
 * divergence is documented per AAP §0.10.2 (paragraph
 * {@code ABEND-ROUTINE} lines 1546–1552).
 *
 * <h2>Optimistic Locking — JPA @Version Replacement</h2>
 *
 * <p>The COBOL READ UPDATE / REWRITE idiom ({@code 9200-WRITE-PROCESSING}
 * paragraph, lines 1376–1521) serialised concurrent updates through CICS
 * file locks; the {@code CHECK-CHANGE-IN-REC} paragraph compared the
 * displayed before-image of the record against the current persisted state
 * on the way into the REWRITE, setting {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
 * (line 1511) when they differed. The Java migration replaces this with
 * JPA's {@code @Version} optimistic-locking field on
 * {@link com.aws.carddemo.entity.Card}: when the {@code save()} call
 * detects a version mismatch, it raises
 * {@link org.springframework.dao.OptimisticLockingFailureException}. The
 * {@link CardUpdateService} does not catch this exception; it propagates
 * uncaught to the controller layer's exception-handler chain (mapped to
 * HTTP 409 Conflict). Tests verify this contract via
 * {@code assertThatThrownBy(...).isInstanceOf(OptimisticLockingFailureException.class)};
 * no {@link CardUpdateResult} is produced on this branch.
 *
 * <h2>Construction Contract — Factory Methods Only</h2>
 *
 * <p>Construction goes exclusively through one of the two static factory
 * methods so the invariant between {@link #success} and {@link #message}
 * cannot be violated:
 * <ul>
 *   <li>{@link #success(String)} returns {@code success = true} with the
 *       supplied {@code 'Changes committed to database'} message (or any
 *       other success confirmation chosen by the service).</li>
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
 * {@link CardUpdateService} compilation and the
 * {@code CardUpdateServiceTest} unit test suite. Subsequent migration agents
 * (REFACTOR flavor) may extend this DTO with additional fields (for
 * example, the loaded card record for pre-population on the next screen
 * render) when the full Spring MVC controller layer is wired up.
 *
 * @see CardUpdateService
 * @see CardUpdateRequest
 */
public final class CardUpdateResult {

    /**
     * Whether the update operation succeeded ({@code true}) or was rejected
     * ({@code false}). Drives the controller's HTTP status code mapping:
     * success → 200 OK; validation failure → 400 Bad Request; card not found
     * → 404 Not Found; optimistic-lock conflict propagates as
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * (mapped by the controller layer to HTTP 409 Conflict).
     */
    private final boolean success;

    /**
     * Human-readable outcome message — one of the COBOL-equivalent strings
     * enumerated in the class-level COBOL Provenance section, or the
     * Java-migration-added invalid-CVV reject. Never {@code null} (both
     * factory methods require a non-{@code null} message).
     */
    private final String message;

    /**
     * Private constructor enforcing the factory-method-only construction
     * contract. Both fields are populated exactly once.
     *
     * @param success outcome flag
     * @param message outcome message
     */
    private CardUpdateResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * Builds a success outcome carrying the supplied confirmation message.
     *
     * <p>Used by {@link CardUpdateService#updateCard(CardUpdateRequest)}
     * after the JPA {@code save()} call returns successfully; the canonical
     * message is {@code 'Changes committed to database'} (mirroring the
     * COBOL {@code CONFIRM-UPDATE-SUCCESS} 88-level literal at
     * {@code COCRDUPC.cbl} line 169).
     *
     * @param message the success confirmation message; expected non-{@code null}
     * @return a success outcome with {@code isSuccess() == true}
     */
    public static CardUpdateResult success(String message) {
        return new CardUpdateResult(true, message);
    }

    /**
     * Builds a failure outcome carrying the supplied reject message.
     *
     * <p>Used by {@link CardUpdateService#updateCard(CardUpdateRequest)} for
     * all reject branches (card-number invalid, card-not-found, CVV invalid,
     * embossed-name required, expiration-date invalid, active-status invalid).
     *
     * @param message the rejection message; expected non-{@code null} and to
     *                match one of the COBOL-equivalent literals declared on
     *                {@link CardUpdateService}
     * @return a failure outcome with {@code isSuccess() == false}
     */
    public static CardUpdateResult failure(String message) {
        return new CardUpdateResult(false, message);
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
