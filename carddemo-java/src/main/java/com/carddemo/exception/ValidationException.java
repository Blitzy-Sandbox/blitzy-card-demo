package com.carddemo.exception;

/**
 * Business / field-level validation failure raised by the input-edit logic of the
 * online and batch programs (e.g. the {@code 1500-*} validation paragraphs of
 * {@code app/cbl/CBTRN02C.cbl} and the field edits in {@code COACTUPC}/{@code COCRDUPC}/
 * {@code COTRN02C}/{@code COSGN00C}). Source commit {@code 27d6c6f}.
 *
 * <p>This exception models the data-edit rejections performed <em>before</em> any
 * persistence occurs &mdash; the field-by-field input edits that, in the original COBOL,
 * set {@code WS-VALIDATION-FAIL-REASON} and an associated error message. It optionally
 * carries the name of the offending field via {@link #getField()} (which may be
 * {@code null} when the failure is not attributable to a single field). It is
 * <em>distinct</em> from {@code TransactionPostingException}, which models the numeric
 * posting reject codes 100&ndash;109.</p>
 *
 * <p><strong>Name collision:</strong> this is {@code com.carddemo.exception.ValidationException},
 * NOT {@code jakarta.validation.ValidationException}. A file needing both must fully
 * qualify the Jakarta type.</p>
 *
 * <p><strong>Note:</strong> because both {@code (String, Throwable)} and
 * {@code (String, String)} constructors exist, a call passing a literal {@code null}
 * second argument is ambiguous and will not compile. Use the single-argument
 * constructor, or cast the {@code null} (for example {@code (String) null} to select the
 * field form, or {@code (Throwable) null} to select the cause form).</p>
 */
public class ValidationException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public ValidationException(String message) {
        super(message);
        this.field = null;
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.field = null;
    }

    public ValidationException(String message, String field) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
