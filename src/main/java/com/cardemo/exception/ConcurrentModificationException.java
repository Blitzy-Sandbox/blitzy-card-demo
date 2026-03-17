/*
 * ConcurrentModificationException.java
 *
 * Custom exception for optimistic concurrency control failures in the CardDemo
 * application. Maps the COBOL CICS READ UPDATE snapshot mismatch pattern to a
 * Java exception that wraps JPA OptimisticLockException (from @Version mismatch).
 *
 * COBOL Traceability:
 * - COACTUPC.cbl (Account Update — 4,236 lines, most complex program):
 *   - LOCKED-BUT-UPDATE-FAILED flag (line 523): Set when EXEC CICS REWRITE
 *     returns a non-NORMAL response code after acquiring a record lock.
 *   - DATA-WAS-CHANGED-BEFORE-UPDATE flag (lines 521-522): Set in paragraph
 *     9700-CHECK-CHANGE-IN-REC (line 4109) when before/after field comparison
 *     across 15+ fields detects concurrent modification — the COBOL equivalent
 *     of optimistic locking.
 *   - Account REWRITE failure (lines 4065-4081): After EXEC CICS REWRITE
 *     FILE(ACCTFILENAME), if response is not DFHRESP(NORMAL), sets
 *     LOCKED-BUT-UPDATE-FAILED and exits.
 *   - Customer REWRITE + SYNCPOINT ROLLBACK (lines 4085-4103): After EXEC CICS
 *     REWRITE FILE(CUSTFILENAME), if response is not DFHRESP(NORMAL), sets
 *     LOCKED-BUT-UPDATE-FAILED, executes SYNCPOINT ROLLBACK to undo the
 *     preceding account update, then exits.
 *   - Error display (line 2609): WHEN LOCKED-BUT-UPDATE-FAILED displays
 *     'Update of record failed' to the 3270 terminal.
 *
 * - COCRDUPC.cbl (Card Update — optimistic concurrency):
 *   - Read with lock check (line 1441): Verifies DFHRESP(NORMAL) for READ UPDATE.
 *   - Change detection (line 1453-1457): PERFORM 9300-CHECK-CHANGE-IN-REC
 *     compares before/after field values; if DATA-WAS-CHANGED-BEFORE-UPDATE,
 *     exits without writing.
 *   - Rewrite check (lines 1477-1492): After EXEC CICS REWRITE FILE(CARDFILENAME),
 *     if response is not DFHRESP(NORMAL), sets LOCKED-BUT-UPDATE-FAILED.
 *
 * Java Mapping (AAP §0.8.4):
 * - The COBOL before/after record comparison maps to JPA @Version annotation on
 *   entity classes. When a concurrent update increments the version, Hibernate
 *   throws OptimisticLockException, which is caught and wrapped in this exception.
 * - The SYNCPOINT ROLLBACK in COACTUPC.cbl (line 4100) maps to Spring
 *   @Transactional rollback — since this exception extends RuntimeException
 *   (via CardDemoException), the transaction automatically rolls back.
 * - Maps to HTTP 409 (Conflict) in REST controllers via @ControllerAdvice.
 *
 * IMPORTANT: This is com.cardemo.exception.ConcurrentModificationException,
 * intentionally distinct from java.util.ConcurrentModificationException.
 * The fully-qualified package name prevents any ambiguity. Files importing this
 * class must use the com.cardemo.exception package, not java.util.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.exception;

/**
 * Exception thrown when an entity record has been modified by another transaction
 * between the initial read and the attempted write, indicating an optimistic
 * concurrency control failure.
 *
 * <p>This exception is the Java equivalent of the COBOL CICS pattern where a
 * {@code READ UPDATE} acquires a record lock, and the subsequent {@code REWRITE}
 * either fails because the record was modified (snapshot mismatch) or because
 * the lock could not be maintained. In the source COBOL programs:</p>
 *
 * <ul>
 *   <li><strong>COACTUPC.cbl</strong> — Sets {@code LOCKED-BUT-UPDATE-FAILED}
 *       (line 523) and triggers {@code SYNCPOINT ROLLBACK} (line 4100) when the
 *       dual account+customer update fails partway through.</li>
 *   <li><strong>COCRDUPC.cbl</strong> — Sets {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
 *       (paragraph 9300-CHECK-CHANGE-IN-REC) when before/after field comparison
 *       detects that another user modified the card record.</li>
 * </ul>
 *
 * <h3>Usage Context</h3>
 * <ul>
 *   <li>{@code AccountUpdateService} — Wraps JPA {@code OptimisticLockException}
 *       when {@code @Version} mismatch occurs during account/customer update.
 *       The enclosing {@code @Transactional} method automatically rolls back.</li>
 *   <li>{@code CardUpdateService} — Wraps JPA {@code OptimisticLockException}
 *       or Spring {@code ObjectOptimisticLockingFailureException} when card
 *       record version conflict is detected during update.</li>
 *   <li>{@code @ControllerAdvice} — Maps this exception to HTTP 409 (Conflict)
 *       with a structured error response including the entity name and ID.</li>
 * </ul>
 *
 * <h3>Transaction Rollback Behavior</h3>
 * <p>Because this class extends {@link CardDemoException} which extends
 * {@link RuntimeException}, Spring's {@code @Transactional} will automatically
 * roll back the current transaction when this exception is thrown. This preserves
 * the COBOL {@code SYNCPOINT ROLLBACK} semantics from COACTUPC.cbl line 4100,
 * ensuring that partial updates (e.g., account updated but customer update failed)
 * are atomically reversed.</p>
 *
 * @see CardDemoException
 * @see jakarta.persistence.OptimisticLockException
 * @see org.springframework.orm.ObjectOptimisticLockingFailureException
 */
public class ConcurrentModificationException extends CardDemoException {

    /**
     * Serial version UID for serialization compatibility.
     * Follows the same pattern as the base {@link CardDemoException} class.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Application-level error code identifying concurrent modification errors.
     *
     * <p>This code maps to the CICS record locking pattern where
     * {@code LOCKED-BUT-UPDATE-FAILED} or {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
     * flags are set in the COBOL programs. Used for structured logging and
     * API error responses.</p>
     */
    private static final String LOCK_ERROR_CODE = "LOCK";

    /**
     * The name of the entity type involved in the concurrent modification conflict.
     *
     * <p>Corresponds to the COBOL file name in the CICS REWRITE operation:
     * "Account" maps to ACCTFILENAME, "Customer" maps to CUSTFILENAME,
     * "Card" maps to CARDFILENAME.</p>
     *
     * <p>May be {@code null} when the exception is created with the simple
     * message constructor or the cause-wrapping constructor.</p>
     */
    private final String entityName;

    /**
     * The primary key or identifier of the specific entity record that was
     * concurrently modified by another transaction.
     *
     * <p>Corresponds to the COBOL RIDFLD (Record Identification Field) used
     * in the CICS READ/REWRITE operations. For accounts, this is the account ID;
     * for cards, this is the card number.</p>
     *
     * <p>Declared as {@link Object} to accommodate various key types (String,
     * Long, composite keys). May be {@code null} when the exception is created
     * with the simple message constructor or the cause-wrapping constructor.</p>
     *
     * <p>Marked {@code transient} because {@link Object} does not implement
     * {@link java.io.Serializable}. This field is used for immediate error
     * reporting and structured API responses at the point of exception creation,
     * not for cross-JVM serialization. In practice, exceptions in a Spring Boot
     * REST application are caught by {@code @ControllerAdvice} and converted to
     * HTTP error responses within the same JVM — serialization is not required.</p>
     */
    private final transient Object entityId;

    /**
     * Constructs a new {@code ConcurrentModificationException} with the specified
     * detail message.
     *
     * <p>Creates the exception without entity identification metadata. The error
     * code defaults to {@link CardDemoException#DEFAULT_ERROR_CODE}. Suitable for
     * generic concurrent modification errors where the specific entity is not
     * known or not relevant to the caller.</p>
     *
     * <p>COBOL equivalent: Setting {@code LOCKED-BUT-UPDATE-FAILED} with a generic
     * error message ('Update of record failed') without identifying which specific
     * VSAM record caused the conflict.</p>
     *
     * @param message the detail message describing the concurrency conflict
     */
    public ConcurrentModificationException(String message) {
        super(message);
        this.entityName = null;
        this.entityId = null;
    }

    /**
     * Constructs a new {@code ConcurrentModificationException} identifying the
     * specific entity and record that experienced the concurrent modification conflict.
     *
     * <p>Automatically constructs a descriptive message in the format:
     * "{entityName} with id {entityId} was modified by another transaction".
     * Sets the error code to "LOCK" to categorize this as a concurrent modification
     * error in API responses and structured logging.</p>
     *
     * <p>COBOL equivalent: The combination of {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
     * flag detection in paragraph {@code 9700-CHECK-CHANGE-IN-REC} (COACTUPC.cbl)
     * or {@code 9300-CHECK-CHANGE-IN-REC} (COCRDUPC.cbl), where the specific
     * VSAM file and key are known from the context of the REWRITE operation.</p>
     *
     * <p>Example usage in AccountUpdateService:</p>
     * <pre>
     * throw new ConcurrentModificationException("Account", accountId);
     * // Produces: "Account with id 00000000001 was modified by another transaction"
     * </pre>
     *
     * @param entityName the name of the entity type (e.g., "Account", "Card", "Customer")
     * @param entityId   the primary key or identifier of the contested record
     */
    public ConcurrentModificationException(String entityName, Object entityId) {
        super(
            entityName + " with id " + entityId + " was modified by another transaction",
            LOCK_ERROR_CODE
        );
        this.entityName = entityName;
        this.entityId = entityId;
    }

    /**
     * Constructs a new {@code ConcurrentModificationException} wrapping an underlying
     * cause, typically a JPA {@code OptimisticLockException} or Spring
     * {@code ObjectOptimisticLockingFailureException}.
     *
     * <p>This constructor is the primary mechanism for translating JPA's optimistic
     * locking failure into the CardDemo exception hierarchy. When Hibernate detects
     * a {@code @Version} field mismatch during flush, it throws
     * {@code OptimisticLockException}; service-layer code catches this and wraps it
     * in a {@code ConcurrentModificationException} for uniform error handling.</p>
     *
     * <p>COBOL equivalent: The CICS REWRITE response check that detects a
     * non-NORMAL response code (COACTUPC.cbl lines 4076-4081, COCRDUPC.cbl lines
     * 1488-1492), where the underlying CICS infrastructure reports the lock/version
     * conflict and the COBOL program sets {@code LOCKED-BUT-UPDATE-FAILED}.</p>
     *
     * <p>Example usage in AccountUpdateService:</p>
     * <pre>
     * try {
     *     accountRepository.save(account);
     * } catch (OptimisticLockException ex) {
     *     throw new ConcurrentModificationException(
     *         "Account " + accountId + " was concurrently modified", ex);
     * }
     * </pre>
     *
     * @param message the detail message describing the concurrency conflict
     * @param cause   the underlying exception (typically {@code OptimisticLockException}
     *                or {@code ObjectOptimisticLockingFailureException})
     */
    public ConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
        this.entityName = null;
        this.entityId = null;
    }

    /**
     * Returns the name of the entity type involved in the concurrent modification
     * conflict, if available.
     *
     * <p>Maps to the COBOL CICS file name in the REWRITE operation context:
     * "Account" for ACCTFILENAME, "Customer" for CUSTFILENAME, "Card" for
     * CARDFILENAME.</p>
     *
     * @return the entity type name (e.g., "Account", "Card", "Customer"),
     *         or {@code null} if the entity name was not specified at construction
     */
    public String getEntityName() {
        return entityName;
    }

    /**
     * Returns the primary key or identifier of the entity record that was
     * concurrently modified, if available.
     *
     * <p>Maps to the COBOL RIDFLD (Record Identification Field) in the CICS
     * READ/REWRITE operations. The type is {@link Object} to accommodate various
     * key types used across CardDemo entities (String account IDs, String card
     * numbers, composite keys).</p>
     *
     * @return the entity identifier, or {@code null} if the entity ID was not
     *         specified at construction
     */
    public Object getEntityId() {
        return entityId;
    }
}
