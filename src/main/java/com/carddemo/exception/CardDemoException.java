package com.carddemo.exception;

import java.util.Objects;

import org.springframework.http.HttpStatus;

/**
 * Abstract root of the CardDemo typed exception hierarchy.
 *
 * <p>Every domain, I/O, and validation error raised by the migrated CardDemo
 * system is a subclass of this unchecked exception. The class is a pure data
 * carrier: it holds the {@link HttpStatus} that best represents the condition
 * at the REST boundary and, optionally, the {@link FileStatusCode} that records
 * the originating COBOL {@code FILE STATUS} / CICS {@code DFHRESP} value.
 *
 * <p>The {@link #getHttpStatus() httpStatus} lets a centralized
 * {@code @RestControllerAdvice} handler translate an exception into an HTTP
 * response without inspecting concrete types, while the optional
 * {@link #getFileStatusCode() fileStatusCode} preserves the legacy file-status
 * origin for diagnostics and traceability.
 *
 * <p>The class is {@code abstract} and its constructors are {@code protected},
 * so it is never instantiated directly; callers raise one of the concrete
 * subtypes. It performs no logging and has no side effects &mdash; structured
 * logging happens at the handler and batch-listener boundaries.
 */
public abstract class CardDemoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP status representing this error at the REST boundary; never {@code null}. */
    private final HttpStatus httpStatus;

    /** Originating COBOL file-status code, or {@code null} when the error has no file-status origin. */
    private final FileStatusCode fileStatusCode;

    /**
     * Creates an exception with a message and a mapped HTTP status and no
     * file-status origin.
     *
     * @param message    the detail message; may be {@code null}
     * @param httpStatus the HTTP status representing this error; must not be {@code null}
     */
    protected CardDemoException(String message, HttpStatus httpStatus) {
        this(message, httpStatus, (FileStatusCode) null);
    }

    /**
     * Creates an exception with a message, a mapped HTTP status, and a
     * triggering cause, with no file-status origin.
     *
     * @param message    the detail message; may be {@code null}
     * @param httpStatus the HTTP status representing this error; must not be {@code null}
     * @param cause      the underlying cause; may be {@code null}
     */
    protected CardDemoException(String message, HttpStatus httpStatus, Throwable cause) {
        this(message, httpStatus, (FileStatusCode) null, cause);
    }

    /**
     * Creates an exception with a message, a mapped HTTP status, and an
     * originating file-status code.
     *
     * @param message        the detail message; may be {@code null}
     * @param httpStatus     the HTTP status representing this error; must not be {@code null}
     * @param fileStatusCode the originating COBOL file-status code; may be {@code null}
     */
    protected CardDemoException(String message, HttpStatus httpStatus, FileStatusCode fileStatusCode) {
        super(message);
        this.httpStatus = Objects.requireNonNull(httpStatus, "httpStatus");
        this.fileStatusCode = fileStatusCode;
    }

    /**
     * Creates an exception with a message, a mapped HTTP status, an originating
     * file-status code, and a triggering cause.
     *
     * @param message        the detail message; may be {@code null}
     * @param httpStatus     the HTTP status representing this error; must not be {@code null}
     * @param fileStatusCode the originating COBOL file-status code; may be {@code null}
     * @param cause          the underlying cause; may be {@code null}
     */
    protected CardDemoException(String message, HttpStatus httpStatus, FileStatusCode fileStatusCode, Throwable cause) {
        super(message, cause);
        this.httpStatus = Objects.requireNonNull(httpStatus, "httpStatus");
        this.fileStatusCode = fileStatusCode;
    }

    /**
     * Returns the HTTP status that represents this error at the REST boundary.
     *
     * @return the mapped {@link HttpStatus}; never {@code null}
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * Returns the originating COBOL file-status code, when one is present.
     *
     * @return the originating {@link FileStatusCode}, or {@code null} if this
     *         error has no file-status origin
     */
    public FileStatusCode getFileStatusCode() {
        return fileStatusCode;
    }

    /**
     * Returns the stable, machine-readable error code surfaced to API clients as
     * the {@code code} field of the error response.
     *
     * <p>The base implementation derives the code from the originating
     * {@link FileStatusCode} when present (for example {@code RECORD_NOT_FOUND},
     * {@code DUPLICATE_KEY}, {@code PERMANENT_IO_ERROR}), preserving the legacy
     * {@code FILE STATUS} semantics. Concrete subtypes that carry no file-status
     * origin &mdash; where the raw class name would otherwise leak onto the wire
     * &mdash; override this method to return a stable {@code SCREAMING_SNAKE_CASE}
     * code from the documented taxonomy (for example {@code VALIDATION_ERROR}).
     * The method never returns {@code null}: when neither a file-status code nor
     * an override is available it falls back to the simple class name, matching
     * the pre-existing default so no error response is ever emitted without a
     * {@code code}.
     *
     * @return the stable machine-readable error code; never {@code null}
     */
    public String getErrorCode() {
        return (fileStatusCode != null) ? fileStatusCode.name() : getClass().getSimpleName();
    }
}
