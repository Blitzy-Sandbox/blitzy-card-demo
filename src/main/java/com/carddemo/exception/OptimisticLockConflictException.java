package com.carddemo.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a record was changed by another actor between the time it was read
 * and the time an update was attempted &mdash; an optimistic-locking (version)
 * conflict.
 *
 * <p>Always maps to {@link HttpStatus#CONFLICT} (HTTP 409). When the conflict is
 * detected by the persistence layer, the originating failure is supplied as the
 * {@linkplain #getCause() cause}; callers that do not have a cause use the
 * no-argument or message-only constructors. The {@link #DEFAULT_MESSAGE} carries
 * the user-visible conflict text.
 */
public class OptimisticLockConflictException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /** User-visible message reported for an optimistic-locking conflict. */
    public static final String DEFAULT_MESSAGE = "Record changed by some one else. Please review";

    /**
     * Creates a conflict with the {@link #DEFAULT_MESSAGE} and HTTP 409 status.
     */
    public OptimisticLockConflictException() {
        super(DEFAULT_MESSAGE, HttpStatus.CONFLICT);
    }

    /**
     * Creates a conflict with the {@link #DEFAULT_MESSAGE} and HTTP 409 status,
     * wrapping the failure that triggered it.
     *
     * @param cause the underlying failure; may be {@code null}
     */
    public OptimisticLockConflictException(Throwable cause) {
        super(DEFAULT_MESSAGE, HttpStatus.CONFLICT, cause);
    }

    /**
     * Creates a conflict with a caller-supplied message and HTTP 409 status.
     *
     * @param message the detail message; may be {@code null}
     */
    public OptimisticLockConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }

    /**
     * Creates a conflict with a caller-supplied message and HTTP 409 status,
     * wrapping the failure that triggered it.
     *
     * @param message the detail message; may be {@code null}
     * @param cause   the underlying failure; may be {@code null}
     */
    public OptimisticLockConflictException(String message, Throwable cause) {
        super(message, HttpStatus.CONFLICT, cause);
    }

    /** Stable machine-readable error code for optimistic-locking (version) conflicts. */
    public static final String ERROR_CODE = "OPTIMISTIC_LOCK_CONFLICT";

    /**
     * Returns {@link #ERROR_CODE} ({@code OPTIMISTIC_LOCK_CONFLICT}) rather than the
     * raw class name, giving the 409 "record changed" conflict a stable {@code code}
     * on the wire (preserving the COBOL read-then-rewrite "record changed" behaviour).
     *
     * @return {@code "OPTIMISTIC_LOCK_CONFLICT"}; never {@code null}
     */
    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
