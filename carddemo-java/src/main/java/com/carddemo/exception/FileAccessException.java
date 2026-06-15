package com.carddemo.exception;

/**
 * Hard, unrecoverable I/O or file-access failure — the Java equivalent of the COBOL
 * <em>abend</em> path in {@code app/cbl/CBTRN02C.cbl} (source commit {@code 27d6c6f}).
 *
 * <p>Whereas {@code RecordNotFoundException} (FILE STATUS {@code '23'}) and
 * {@code DuplicateRecordException} (FILE STATUS {@code '22'}) model expected,
 * business-handled statuses, this type models the catastrophic statuses that the
 * original program treated as fatal: a failed {@code OPEN}, {@code CLOSE},
 * {@code READ}, {@code WRITE}, or {@code REWRITE}, and any {@code '9x'} or
 * non-numeric FILE STATUS. In the COBOL source such a status drove
 * {@code 9910-DISPLAY-IO-STATUS} (L714, which decodes non-numeric / {@code IO-STAT1 = '9'}
 * codes by binary conversion) followed by {@code 9999-ABEND-PROGRAM} (L707,
 * {@code MOVE 999 TO ABCODE} then {@code CALL 'CEE3ABD'}, the Language Environment
 * abend service). Representative call sites include the reject-file write failure
 * ({@code 2500-WRITE-REJECT-REC}), the transaction-category-balance write/rewrite
 * failures ({@code 2700-A-CREATE-TCATBAL-REC} / {@code 2700-B-UPDATE-TCATBAL-REC}),
 * and the transaction-file write failure ({@code 2900-WRITE-TRANSACTION-FILE}).</p>
 *
 * <p>The idiomatic Java replacement for that abend is simply to throw this unchecked
 * exception; the runtime is <strong>not</strong> halted via {@code System.exit} or
 * {@code Runtime}. When the originating 2-byte FILE STATUS is known it is preserved
 * verbatim (as a {@code String}, to retain non-numeric and {@code '9x'} forms) and is
 * available through {@link #getFileStatus()} for logging and downstream mapping.</p>
 *
 * @implNote Because both the {@code (String, Throwable)} and {@code (String, String)}
 *           constructors exist, a call that passes a <em>literal</em> {@code null} as the
 *           second argument — for example {@code new FileAccessException("msg", null)} —
 *           is ambiguous under standard Java overload resolution and will not compile
 *           ("reference to FileAccessException is ambiguous"). Call sites must either use
 *           the single-argument constructor or cast the {@code null}, for example
 *           {@code new FileAccessException("msg", (String) null)} or
 *           {@code new FileAccessException("msg", (Throwable) null)}.
 */
public class FileAccessException extends CardDemoException {

    /** Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * The raw, 2-byte COBOL FILE STATUS associated with this failure when known,
     * otherwise {@code null}. Kept as a {@code String} (never an {@code int}) so the
     * exact 2-byte form — including non-numeric and {@code '9x'} codes — is preserved
     * for logging and downstream mapping.
     */
    private final String fileStatus;

    /**
     * Creates a file-access failure with the supplied detail message and no associated
     * FILE STATUS (the status is recorded as {@code null}).
     *
     * @param message the detail message describing the failure
     */
    public FileAccessException(String message) {
        super(message);
        this.fileStatus = null;
    }

    /**
     * Creates a file-access failure with the supplied detail message and underlying
     * cause; no FILE STATUS is associated (it is recorded as {@code null}).
     *
     * @param message the detail message describing the failure
     * @param cause   the underlying cause (for example, a {@code java.io.IOException})
     */
    public FileAccessException(String message, Throwable cause) {
        super(message, cause);
        this.fileStatus = null;
    }

    /**
     * Creates a file-access failure with the supplied detail message and the raw 2-byte
     * COBOL FILE STATUS that triggered it.
     *
     * @param message    the detail message describing the failure
     * @param fileStatus the raw 2-byte FILE STATUS (for example {@code "92"}), or
     *                   {@code null} if unknown
     */
    public FileAccessException(String message, String fileStatus) {
        super(message);
        this.fileStatus = fileStatus;
    }

    /**
     * Creates a file-access failure with the supplied detail message, the raw 2-byte
     * COBOL FILE STATUS that triggered it, and the underlying cause.
     *
     * @param message    the detail message describing the failure
     * @param fileStatus the raw 2-byte FILE STATUS (for example {@code "92"}), or
     *                   {@code null} if unknown
     * @param cause      the underlying cause (for example, a {@code java.io.IOException})
     */
    public FileAccessException(String message, String fileStatus, Throwable cause) {
        super(message, cause);
        this.fileStatus = fileStatus;
    }

    /**
     * Returns the raw 2-byte COBOL FILE STATUS associated with this failure.
     *
     * @return the 2-byte FILE STATUS string, or {@code null} if none was supplied
     */
    public String getFileStatus() {
        return fileStatus;
    }
}
