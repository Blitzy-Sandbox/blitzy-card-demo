package com.carddemo.exception;

import org.springframework.http.HttpStatus;

/**
 * Fatal file-processing failure in the migrated batch and I/O tier.
 *
 * <p>This unchecked exception is the structured-Java replacement for the COBOL
 * abend paragraphs (for example {@code 9999-ABEND-PROGRAM} in {@code CBTRN02C},
 * and {@code EXEC CICS ABEND ABCODE('9999')} in the online programs). It is
 * raised when an I/O operation reports an unmapped or non-recoverable
 * {@code FILE STATUS} value &mdash; that is, any status that is neither
 * successful completion ({@code '00'}) nor normal end-of-file
 * ({@link FileStatusCode#END_OF_FILE '10'}). End-of-file is handled upstream as
 * a clean reader termination and is never wrapped by this exception.
 *
 * <p>The condition always maps to {@link HttpStatus#INTERNAL_SERVER_ERROR HTTP
 * 500} at the REST boundary and to a fatal batch-step failure in the batch tier.
 *
 * <p>Alongside the inherited detail message, HTTP status, and originating
 * {@link FileStatusCode} (from {@link CardDemoException}), this exception carries
 * the abend context modelled on the legacy {@code CABENDD} ({@code ABEND-DATA})
 * payload: an {@link #getAbendCode() abend code} and the
 * {@link #getCulprit() culprit} program, paragraph, or step that abended.
 *
 * <p>The class is a pure data carrier: it performs no logging and has no side
 * effects (in particular it never terminates the JVM). Structured logging
 * (JSON with an MDC {@code correlationId}) is applied at the
 * {@code GlobalExceptionHandler} and Spring Batch error/skip-listener
 * boundaries, which read {@link #getAbendCode()}, {@link #getCulprit()},
 * {@link #getFileStatusCode()}, {@link #getMessage()}, and {@link #getCause()}.
 */
public class FileProcessingException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * CardDemo abend code (for example {@code "9999"} for online CICS abends or
     * {@code "0999"} for the batch LE abend), modelled on {@code ABEND-CODE}
     * ({@code PIC X(4)}); {@code null} when no abend code is supplied.
     */
    private final String abendCode;

    /**
     * Program, paragraph, or batch step that abended, modelled on
     * {@code ABEND-CULPRIT} ({@code PIC X(8)}); {@code null} when not supplied.
     */
    private final String culprit;

    /**
     * Creates a fatal file-processing exception with a detail message.
     *
     * @param message the detail message; may be {@code null}
     */
    public FileProcessingException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR);
        this.abendCode = null;
        this.culprit = null;
    }

    /**
     * Creates a fatal file-processing exception with a detail message and a
     * triggering cause.
     *
     * @param message the detail message; may be {@code null}
     * @param cause   the underlying cause; may be {@code null}
     */
    public FileProcessingException(String message, Throwable cause) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, cause);
        this.abendCode = null;
        this.culprit = null;
    }

    /**
     * Creates a fatal file-processing exception with a detail message and the
     * originating file-status code.
     *
     * @param message        the detail message; may be {@code null}
     * @param fileStatusCode the originating COBOL file-status code; may be {@code null}
     */
    public FileProcessingException(String message, FileStatusCode fileStatusCode) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, fileStatusCode);
        this.abendCode = null;
        this.culprit = null;
    }

    /**
     * Creates a fatal file-processing exception with a detail message, the
     * originating file-status code, and a triggering cause.
     *
     * @param message        the detail message; may be {@code null}
     * @param fileStatusCode the originating COBOL file-status code; may be {@code null}
     * @param cause          the underlying cause; may be {@code null}
     */
    public FileProcessingException(String message, FileStatusCode fileStatusCode, Throwable cause) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, fileStatusCode, cause);
        this.abendCode = null;
        this.culprit = null;
    }

    /**
     * Creates a fully specified fatal file-processing exception carrying the
     * complete abend context.
     *
     * @param message        the detail message; may be {@code null}
     * @param abendCode      the CardDemo abend code ({@code ABEND-CODE}); may be {@code null}
     * @param culprit        the abending program, paragraph, or step ({@code ABEND-CULPRIT}); may be {@code null}
     * @param fileStatusCode the originating COBOL file-status code; may be {@code null}
     * @param cause          the underlying cause; may be {@code null}
     */
    public FileProcessingException(String message,
                                   String abendCode,
                                   String culprit,
                                   FileStatusCode fileStatusCode,
                                   Throwable cause) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, fileStatusCode, cause);
        this.abendCode = abendCode;
        this.culprit = culprit;
    }

    /**
     * Returns the CardDemo abend code associated with this failure.
     *
     * @return the abend code ({@code ABEND-CODE}), or {@code null} if none was supplied
     */
    public String getAbendCode() {
        return abendCode;
    }

    /**
     * Returns the program, paragraph, or batch step that abended.
     *
     * @return the culprit ({@code ABEND-CULPRIT}), or {@code null} if none was supplied
     */
    public String getCulprit() {
        return culprit;
    }
}
