package com.carddemo.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a write would create a record whose key already exists.
 *
 * <p>This condition corresponds to the legacy CICS {@code DFHRESP(DUPKEY)} /
 * {@code DFHRESP(DUPREC)} responses on {@code EXEC CICS WRITE} in the online
 * add-record programs, and to the batch {@code FILE STATUS '22'} on a keyed
 * write. It is a concrete member of the {@link CardDemoException} hierarchy and
 * always carries {@link HttpStatus#CONFLICT} (HTTP 409) together with the
 * {@link FileStatusCode#DUPLICATE_KEY} file-status origin, so a centralized
 * REST handler can translate it without inspecting the concrete type.
 *
 * <p>Typical usage: a service catches a JPA unique/primary-key constraint
 * violation on {@code save} and rethrows it as this type.
 */
public class DuplicateResourceException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a duplicate-resource exception with an explicit detail message.
     *
     * @param message the detail message; may be {@code null}
     */
    public DuplicateResourceException(String message) {
        super(message, HttpStatus.CONFLICT, FileStatusCode.DUPLICATE_KEY);
    }

    /**
     * Creates a duplicate-resource exception with an explicit detail message and
     * a triggering cause (for example a wrapped constraint-violation exception).
     *
     * @param message the detail message; may be {@code null}
     * @param cause   the underlying cause; may be {@code null}
     */
    public DuplicateResourceException(String message, Throwable cause) {
        super(message, HttpStatus.CONFLICT, FileStatusCode.DUPLICATE_KEY, cause);
    }

    /**
     * Creates a duplicate-resource exception with a message derived from the
     * resource type and the conflicting key, of the form
     * {@code "<resourceType> already exists for id: <key>"}. Both arguments are
     * rendered null-safely via {@link String#valueOf(Object)}.
     *
     * @param resourceType the logical resource type (for example {@code "User"});
     *                     may be {@code null}
     * @param key          the conflicting key value; may be {@code null}
     */
    public DuplicateResourceException(String resourceType, Object key) {
        this(buildMessage(resourceType, key));
    }

    /**
     * Static factory for a duplicate-resource exception describing the resource
     * type and conflicting key.
     *
     * @param resourceType the logical resource type (for example {@code "User"});
     *                     may be {@code null}
     * @param key          the conflicting key value; may be {@code null}
     * @return a new {@code DuplicateResourceException} with a derived message
     */
    public static DuplicateResourceException of(String resourceType, Object key) {
        return new DuplicateResourceException(resourceType, key);
    }

    /**
     * Builds the null-safe detail message for the resource-type/key constructor
     * and factory.
     *
     * @param resourceType the logical resource type; may be {@code null}
     * @param key          the conflicting key value; may be {@code null}
     * @return the composed message
     */
    private static String buildMessage(String resourceType, Object key) {
        return String.valueOf(resourceType) + " already exists for id: " + String.valueOf(key);
    }
}
