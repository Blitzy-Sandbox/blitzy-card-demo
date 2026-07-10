package com.carddemo.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested record or aggregate cannot be located by its key.
 *
 * <p>This is the Java equivalent of a keyed lookup that returns no row: the
 * batch {@code INVALID KEY} path (for example {@code CBTRN02C} paragraph
 * {@code 1500-B-LOOKUP-ACCT}, which reports "ACCOUNT RECORD NOT FOUND") and the
 * online {@code DFHRESP(NOTFND)} branch (for example the {@code COACTVWC}
 * account-view "... not found ..." message). It is raised at the service or
 * repository boundary, typically via {@code Optional.orElseThrow(...)}.
 *
 * <p>The exception always maps to {@link HttpStatus#NOT_FOUND} at the REST
 * boundary and records {@link FileStatusCode#RECORD_NOT_FOUND} as its
 * originating COBOL file-status code. It carries no state beyond the detail
 * message and optional cause inherited from {@link CardDemoException}.
 */
public class ResourceNotFoundException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with an explicit detail message.
     *
     * @param message the detail message describing what was not found; may be {@code null}
     */
    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, FileStatusCode.RECORD_NOT_FOUND);
    }

    /**
     * Creates an exception with an explicit detail message and a triggering cause.
     *
     * @param message the detail message describing what was not found; may be {@code null}
     * @param cause   the underlying cause; may be {@code null}
     */
    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, HttpStatus.NOT_FOUND, FileStatusCode.RECORD_NOT_FOUND, cause);
    }

    /**
     * Creates an exception whose message is built from the resource type and the
     * key that was searched for, producing text of the form
     * {@code "<resourceType> not found for id: <key>"}.
     *
     * <p>The key is rendered null-safely via {@link String#valueOf(Object)}.
     *
     * @param resourceType the human-readable resource/aggregate name (for example {@code "Account"})
     * @param key          the lookup key that produced no match; may be {@code null}
     */
    public ResourceNotFoundException(String resourceType, Object key) {
        this(resourceType + " not found for id: " + String.valueOf(key));
    }

    /**
     * Static factory mirroring {@link #ResourceNotFoundException(String, Object)}
     * for readable call sites, for example
     * {@code throw ResourceNotFoundException.of("Account", id);}.
     *
     * @param resourceType the human-readable resource/aggregate name (for example {@code "Account"})
     * @param key          the lookup key that produced no match; may be {@code null}
     * @return a new {@code ResourceNotFoundException} describing the missing resource
     */
    public static ResourceNotFoundException of(String resourceType, Object key) {
        return new ResourceNotFoundException(resourceType, key);
    }
}
