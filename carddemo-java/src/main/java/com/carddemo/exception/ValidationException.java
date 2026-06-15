package com.carddemo.exception;

/**
 * Business / field-level validation failure raised by the input-edit logic of the
 * online and batch programs.
 *
 * <p>This exception models the data-edit rejections that occur <em>before</em> any
 * persistence is attempted &mdash; for example the {@code 1500-*} validation paragraphs
 * of {@code app/cbl/CBTRN02C.cbl} (notably {@code 1500-VALIDATE-TRAN},
 * {@code 1500-A-LOOKUP-XREF}, {@code 1500-B-LOOKUP-ACCT}) that edit a daily-transaction
 * record prior to posting, and the field-by-field input edits performed by the online
 * update programs ({@code COACTUPC}, {@code COCRDUPC}, {@code COTRN02C}, {@code COSGN00C})
 * that set screen error messages. Source commit {@code 27d6c6f}.</p>
 *
 * <p>It is distinct from {@code TransactionPostingException}, which models the numeric
 * posting reject codes 100&ndash;109 produced during the posting step itself.</p>
 *
 * <p>The exception optionally carries the {@linkplain #getField() name of the offending
 * field}; when the failure is not specific to a single field the value is {@code null}.</p>
 *
 * <p><strong>Name collision:</strong> this is {@code com.carddemo.exception.ValidationException},
 * <em>not</em> {@code jakarta.validation.ValidationException}. They are unrelated types that
 * merely share a simple name. Any source file that needs <em>both</em> types must
 * fully-qualify the Jakarta one (this class is never renamed &mdash; {@code ValidationException}
 * is the name fixed by the design).</p>
 *
 * <p><strong>Constructor-overload caveat:</strong> because both {@code (String, Throwable)}
 * and {@code (String, String)} constructors exist, a call passing a <em>literal</em>
 * {@code null} as the second argument &mdash; e.g. {@code new ValidationException("msg", null)}
 * &mdash; is ambiguous and will <em>not</em> compile ("reference to ValidationException is
 * ambiguous"). Call sites must either use the single-argument constructor or cast the
 * {@code null}: {@code (String) null} selects the field form and {@code (Throwable) null}
 * selects the cause form.</p>
 *
 * @see CardDemoException
 */
public class ValidationException extends CardDemoException {

    /** Serialization version identifier (Gate&nbsp;2 zero-warning build requires it). */
    private static final long serialVersionUID = 1L;

    /**
     * Name of the field that failed validation, or {@code null} when the failure is not
     * specific to a single field. {@link String} is {@link java.io.Serializable}, so this
     * field needs no {@code transient} modifier.
     */
    private final String field;

    /**
     * Creates a validation exception with the supplied message and no associated field.
     *
     * @param message the human-readable validation error message
     */
    public ValidationException(String message) {
        super(message);
        this.field = null;
    }

    /**
     * Creates a validation exception with the supplied message and underlying cause; the
     * associated field is left unset ({@code null}).
     *
     * <p>To pass a {@code null} cause explicitly, cast it &mdash; {@code (Throwable) null}
     * &mdash; to disambiguate from the {@code (String, String)} constructor.</p>
     *
     * @param message the human-readable validation error message
     * @param cause   the underlying cause of this validation failure
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.field = null;
    }

    /**
     * Creates a field-specific validation exception, recording the name of the field that
     * failed validation.
     *
     * <p>To pass a {@code null} field name explicitly, cast it &mdash; {@code (String) null}
     * &mdash; to disambiguate from the {@code (String, Throwable)} constructor.</p>
     *
     * @param message the human-readable validation error message
     * @param field   the name of the offending field (may be {@code null})
     */
    public ValidationException(String message, String field) {
        super(message);
        this.field = field;
    }

    /**
     * Returns the name of the field that failed validation.
     *
     * @return the offending field name, or {@code null} when the failure is not
     *         specific to a single field
     */
    public String getField() {
        return field;
    }
}
