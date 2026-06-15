package com.carddemo.exception;

/**
 * Optimistic-locking conflict or transactional rollback. Maps the COBOL
 * read-snapshot / compare-before-update concurrency checks in
 * {@code app/cbl/COACTUPC.cbl} and {@code app/cbl/COCRDUPC.cbl} (the
 * {@code 9700-CHECK-CHANGE-IN-REC} before/after image comparison, L4109+, and the
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} / {@code LOCKED-BUT-UPDATE-FAILED} flags) and
 * the dual-record update guarded by the sole {@code EXEC CICS SYNCPOINT ROLLBACK}
 * (COACTUPC L4100). Source commit {@code 27d6c6f}.
 *
 * <p>In the original system, {@code COACTUPC} reads an account record, snapshots its
 * fields into the {@code ACUP-OLD-*} work area, and immediately before the dual
 * {@code REWRITE} of {@code ACCTDAT} + {@code CUSTDAT} re-reads and compares the current
 * record image against the snapshot. A mismatch (another process changed the record)
 * raises {@code DATA-WAS-CHANGED-BEFORE-UPDATE}; a failure of the second customer
 * {@code REWRITE} raises {@code LOCKED-BUT-UPDATE-FAILED} and triggers the program's only
 * {@code SYNCPOINT ROLLBACK}. {@code COCRDUPC} implements the identical
 * read-snapshot/compare pattern for card updates.</p>
 *
 * <p>In Java this surfaces a JPA {@code @Version} optimistic-lock failure; the service
 * layer (for example the account- and card-update services) catches the framework
 * {@code OptimisticLockException} / {@code OptimisticLockingFailureException} and wraps it
 * as the {@code cause} of this exception. The rollback is performed automatically by the
 * Spring {@code @Transactional} boundary when this unchecked exception propagates out of
 * it &mdash; this class does not interact with any transaction or persistence API itself,
 * which keeps it framework-agnostic.</p>
 *
 * @see CardDemoException
 */
public class ConcurrencyException extends CardDemoException {

    /**
     * Serialization version identifier. Declared explicitly to satisfy the zero-warning
     * build gate ({@code javac -Xlint:all -Werror}); the value is fixed at {@code 1L}
     * because this exception carries no version-sensitive serialized state.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Logical name of the domain entity whose update conflicted (for example
     * {@code "Account"} or {@code "Card"}), or {@code null} when the conflicting entity
     * is not known at the throw site. {@link String} is {@link java.io.Serializable}, so
     * no {@code transient} modifier is required.
     */
    private final String entity;

    /**
     * Creates a concurrency exception with the given detail message and no underlying
     * cause or entity context.
     *
     * @param message the detail message describing the optimistic-lock conflict
     */
    public ConcurrencyException(String message) {
        super(message);
        this.entity = null;
    }

    /**
     * Creates a concurrency exception that wraps the framework optimistic-lock failure
     * that caused it, without recording the entity name.
     *
     * @param message the detail message describing the optimistic-lock conflict
     * @param cause   the originating failure (for example a JPA
     *                {@code OptimisticLockException}); may be {@code null}
     */
    public ConcurrencyException(String message, Throwable cause) {
        super(message, cause);
        this.entity = null;
    }

    /**
     * Creates a concurrency exception recording both the conflicting entity name and the
     * framework optimistic-lock failure that caused it. This is the canonical form used by
     * the service layer when wrapping a caught optimistic-lock exception.
     *
     * @param message the detail message describing the optimistic-lock conflict
     * @param entity  the logical entity name involved (for example {@code "Account"} or
     *                {@code "Card"}); may be {@code null}
     * @param cause   the originating failure (for example a JPA
     *                {@code OptimisticLockException}); may be {@code null}
     */
    public ConcurrencyException(String message, String entity, Throwable cause) {
        super(message, cause);
        this.entity = entity;
    }

    /**
     * Returns the logical name of the domain entity whose update conflicted.
     *
     * @return the entity name (for example {@code "Account"} or {@code "Card"}), or
     *         {@code null} if it was not supplied at the throw site
     */
    public String getEntity() {
        return entity;
    }
}
