package com.carddemo.exception;

/**
 * Hard / unrecoverable I/O or file-access failure — the Java equivalent of the COBOL
 * <em>abend</em> path in {@code app/cbl/CBTRN02C.cbl} (source commit {@code 27d6c6f}).
 *
 * <p>In the original batch posting program a non-success {@code FILE STATUS} returned by an
 * {@code OPEN}/{@code CLOSE}/{@code READ}/{@code WRITE}/{@code REWRITE} operation routed to
 * {@code 9910-DISPLAY-IO-STATUS} (L714 — decodes non-numeric / {@code IO-STAT1 = '9'}
 * statuses by binary conversion) and then to {@code 9999-ABEND-PROGRAM} (L707 —
 * {@code MOVE 999 TO ABCODE}, {@code CALL 'CEE3ABD'}, the Language Environment abend
 * service). Representative call sites include the reject-file write failure
 * ({@code 2500-WRITE-REJECT-REC}, L460), and the transaction-category-balance write and
 * rewrite failures ({@code 2700-A-CREATE-TCATBAL-REC}, L520; {@code 2700-B-UPDATE-TCATBAL-REC},
 * L538). This exception is the idiomatic replacement for that fatal abend path: it is thrown
 * (and allowed to propagate) rather than terminating the JVM.</p>
 *
 * <p>Unlike {@code RecordNotFoundException} (FILE STATUS {@code '23'}) and
 * {@code DuplicateRecordException} (FILE STATUS {@code '22'}), which model expected,
 * business-handled outcomes, {@code FileAccessException} models the catastrophic statuses
 * (the {@code '9x'} / non-numeric codes) that the COBOL program treated as unrecoverable.
 * When the raw 2-byte {@code FILE STATUS} is known it is preserved verbatim via
 * {@link #getFileStatus()} for structured logging and downstream mapping; the value is kept
 * as a {@link String} (not an {@code int}) precisely so non-numeric and {@code 9x} forms are
 * retained without loss.</p>
 *
 * @implNote Because both the {@code (String message, Throwable cause)} and the
 *           {@code (String message, String fileStatus)} constructors exist, invoking the
 *           class with a <em>literal</em> {@code null} as the second argument — for example
 *           {@code new FileAccessException("msg", null)} — is ambiguous under standard Java
 *           overload resolution and will not compile ("reference to FileAccessException is
 *           ambiguous"). Call sites must either use the single-argument constructor or cast
 *           the {@code null}, e.g. {@code new FileAccessException("msg", (String) null)} or
 *           {@code new FileAccessException("msg", (Throwable) null)}. All four constructors
 *           are intentionally retained; the caveat is by design, not a defect.
 *
 * @see CardDemoException
 */
public class FileAccessException extends CardDemoException {

    /** Serialization version identifier (Gate 2: zero-warning build requires an explicit value). */
    private static final long serialVersionUID = 1L;

    /**
     * The raw, 2-byte COBOL {@code FILE STATUS} associated with the failure when known,
     * otherwise {@code null}. Retained as a {@link String} (which is {@link java.io.Serializable})
     * so the exact textual form — including non-numeric and {@code '9x'} codes — is preserved.
     */
    private final String fileStatus;

    /**
     * Creates a file-access failure with a descriptive message and no associated FILE STATUS.
     *
     * @param message the human-readable description of the failure (mirrors the COBOL
     *                {@code DISPLAY 'ERROR ...'} text preceding the abend)
     */
    public FileAccessException(String message) {
        super(message);
        this.fileStatus = null;
    }

    /**
     * Creates a file-access failure with a descriptive message and an underlying cause.
     *
     * @param message the human-readable description of the failure
     * @param cause   the underlying throwable (e.g. an {@link java.io.IOException} or a
     *                JDBC/data-access exception) that triggered this failure
     */
    public FileAccessException(String message, Throwable cause) {
        super(message, cause);
        this.fileStatus = null;
    }

    /**
     * Creates a file-access failure with a descriptive message and the raw 2-byte COBOL
     * {@code FILE STATUS} that produced it.
     *
     * @param message    the human-readable description of the failure
     * @param fileStatus the raw 2-byte COBOL {@code FILE STATUS} code (e.g. {@code "90"}),
     *                   preserved verbatim; may be {@code null}
     */
    public FileAccessException(String message, String fileStatus) {
        super(message);
        this.fileStatus = fileStatus;
    }

    /**
     * Creates a file-access failure with a descriptive message, the raw 2-byte COBOL
     * {@code FILE STATUS}, and an underlying cause.
     *
     * @param message    the human-readable description of the failure
     * @param fileStatus the raw 2-byte COBOL {@code FILE STATUS} code, preserved verbatim;
     *                   may be {@code null}
     * @param cause      the underlying throwable that triggered this failure
     */
    public FileAccessException(String message, String fileStatus, Throwable cause) {
        super(message, cause);
        this.fileStatus = fileStatus;
    }

    /**
     * Returns the raw 2-byte COBOL {@code FILE STATUS} associated with this failure.
     *
     * @return the preserved 2-byte FILE STATUS code, or {@code null} when it was not supplied
     */
    public String getFileStatus() {
        return fileStatus;
    }
}
