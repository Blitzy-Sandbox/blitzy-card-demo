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
 * <p>In Java this surfaces a JPA {@code @Version} optimistic-lock failure; the service
 * layer (for example {@code AccountUpdateService} and {@code CardUpdateService}) catches
 * the framework {@code OptimisticLockException} / {@code OptimisticLockingFailureException}
 * and wraps it as the {@code cause} of this exception. The rollback is performed
 * automatically by the Spring {@code @Transactional} boundary when this unchecked
 * exception propagates; this type itself stays framework-agnostic and triggers no
 * rollback on its own beyond being unchecked.</p>
 */
public class ConcurrencyException extends CardDemoException {

    /**
     * Serialization version identifier. Required because the superclass chain is
     * {@link java.io.Serializable} (via {@link RuntimeException}); pinning it keeps the
     * {@code -Xlint:all -Werror} build (Gate 2) free of {@code serial} warnings.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Logical name of the entity whose update conflicted (for example {@code "Account"}
     * or {@code "Card"}), or {@code null} when not supplied. {@link String} is itself
     * {@link java.io.Serializable}, so no {@code transient} modifier is required.
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
     * Creates a concurrency exception with the given detail message wrapping the
     * originating cause (typically a framework {@code OptimisticLockException}).
     *
     * @param message the detail message describing the optimistic-lock conflict
     * @param cause   the underlying cause to preserve in the exception chain
     */
    public ConcurrencyException(String message, Throwable cause) {
        super(message, cause);
        this.entity = null;
    }

    /**
     * Creates a concurrency exception with the given detail message, the logical entity
     * name involved, and the originating cause. This is the canonical form used by the
     * service layer when wrapping a caught optimistic-lock failure.
     *
     * @param message the detail message describing the optimistic-lock conflict
     * @param entity  the logical entity name involved (for example {@code "Account"} or
     *                {@code "Card"}), or {@code null} if unknown
     * @param cause   the underlying cause to preserve in the exception chain
     */
    public ConcurrencyException(String message, String entity, Throwable cause) {
        super(message, cause);
        this.entity = entity;
    }

    /**
     * Returns the logical entity name involved in the concurrency conflict.
     *
     * @return the entity name (for example {@code "Account"} or {@code "Card"}), or
     *         {@code null} if none was supplied
     */
    public String getEntity() {
        return entity;
    }
}
