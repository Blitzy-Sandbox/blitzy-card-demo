package com.cardemo.exception;

import java.io.Serial;
import java.util.List;

/**
 * Concrete domain exception signalling that one or more inputs failed a
 * field-level or business-rule validation &mdash; the Java&nbsp;25 mapping of
 * the input-edit / validation logic scattered across the CardDemo online and
 * batch programs.
 *
 * <p>On the legacy mainframe two complementary idioms recorded a validation
 * failure. The batch posting program {@code app/cbl/CBTRN02C.cbl} drove an
 * ordered validation cascade ({@code 1500-VALIDATE-TRAN} and its helpers) that
 * recorded each rejection in a two-part working-storage trailer &mdash; a
 * numeric reason code plus a fixed description:</p>
 *
 * <pre>{@code
 * 01 WS-VALIDATION-TRAILER.
 *    05 WS-VALIDATION-FAIL-REASON      PIC 9(04).
 *    05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
 * ...
 * 1500-VALIDATE-TRAN.
 *     PERFORM 1500-A-LOOKUP-XREF.
 *     IF WS-VALIDATION-FAIL-REASON = 0
 *        PERFORM 1500-B-LOOKUP-ACCT
 *     END-IF
 * *   ADD MORE VALIDATIONS HERE
 *     EXIT.
 * }</pre>
 *
 * <p>The online programs used the companion {@code WS-ERR-FLG} plus
 * message-text idiom to surface a failed {@code RECEIVE MAP} field edit back to
 * the 3270 screen. Both idioms could report a reason and a human-readable
 * description, and the batch cascade was explicitly extensible (the
 * {@code * ADD MORE VALIDATIONS HERE} marker). This exception is the single,
 * idiomatic Java replacement for those <em>general</em> validation rejections:
 * it carries a {@link #getValidationErrors() list of error descriptions}, which
 * preserves the reason/description reporting capability and lets several field
 * failures be aggregated into one response.</p>
 *
 * <h2>Relationship to Jakarta Bean Validation ({@code @Valid})</h2>
 * <p>The migration maps CICS {@code RECEIVE MAP} validation and
 * {@code COPY CSSETATY} onto {@code @Valid} plus field-level Jakarta Bean
 * Validation annotations on the request DTOs. <strong>Annotation-constraint
 * violations are NOT represented by this type:</strong> the framework raises a
 * {@code MethodArgumentNotValidException}, which the centralized
 * {@code @RestControllerAdvice} handles on its own dedicated path. This
 * {@code ValidationException} is the complement used when
 * <em>service-layer / business-rule</em> validation fails &mdash; validation
 * that lives beyond a single annotation constraint (for example the
 * cross-field and lookup edits in the account/card update programs, or the
 * extensible {@code 1500-VALIDATE-TRAN} checks). For that reason this class
 * deliberately imports no {@code jakarta.validation.*} types.</p>
 *
 * <h2>Specific reject conditions have their own subtypes</h2>
 * <p>The more specific reject branches of {@code CBTRN02C.cbl} already map to
 * dedicated exceptions, so they are intentionally <em>not</em> funnelled through
 * this general type: reject codes {@code 100}/{@code 101}/{@code 109}
 * ({@code INVALID KEY} / record absent) map to
 * {@link RecordNotFoundException}, reject code {@code 102}
 * ({@code OVERLIMIT TRANSACTION}) to {@link CreditLimitExceededException}, and
 * reject code {@code 103} ({@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION})
 * to {@link ExpiredCardException}. {@code ValidationException} is the
 * general-purpose remainder for input/business validation that is none of those
 * specific cases (and is not a duplicate or optimistic-lock conflict).</p>
 *
 * <h2>Technology substitution (documented per the Minimal Change Clause)</h2>
 * <p>The behaviour (an input that fails a validation rule is rejected) is
 * preserved exactly; only the mechanism changes. A service or batch validator
 * that replaces the COBOL {@code WS-VALIDATION-FAIL-REASON} /
 * {@code WS-ERR-FLG} bookkeeping throws this exception (optionally carrying the
 * collected error descriptions) instead of moving a numeric reason and text
 * into working storage. Because the type is unchecked (inherited from
 * {@link CardDemoException} &rarr; {@link RuntimeException}), it also triggers a
 * Spring {@code @Transactional} rollback by default. HTTP-status mapping is
 * deliberately <em>not</em> performed here: a centralized
 * {@code @RestControllerAdvice} in the web layer translates this exception to an
 * HTTP&nbsp;<strong>400 Bad Request</strong> response &mdash; the correct status
 * for a request that is syntactically receivable but fails validation &mdash;
 * and can render the aggregated {@link #getValidationErrors() field errors} in
 * the body. Keeping this class free of any web-framework coupling (its only
 * imports are {@code java.*}) preserves its role as a foundational,
 * low-dependency type.</p>
 *
 * <h2>Diagnostic context</h2>
 * <p>The {@link #getValidationErrors() validationErrors} list is the structured
 * counterpart of the COBOL reason descriptions: it is <em>never</em>
 * {@code null} (it defaults to an empty list) and is always an unmodifiable
 * snapshot, so callers cannot mutate the exception's state after construction.
 * The optional {@link #getFieldName() fieldName} captures the single offending
 * input for the convenience single-field constructor and is {@code null}
 * otherwise. No COBOL text is copied into this class; the descriptions are
 * supplied by the migrated validators at the point of failure.</p>
 *
 * <p><strong>Traceability.</strong> Mapped from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f} (authoritative blueprint
 * {@code docs/technical-specifications.md} L457 &mdash;
 * {@code ValidationException.java (Field validation failures)}). The
 * reason/description pattern originates in {@code app/cbl/CBTRN02C.cbl}
 * ({@code WS-VALIDATION-FAIL-REASON} {@code PIC 9(04)} +
 * {@code WS-VALIDATION-FAIL-REASON-DESC} {@code PIC X(76)}, the
 * {@code 1500-VALIDATE-TRAN} cascade) and the online {@code WS-ERR-FLG} field
 * edits. The COBOL source is read-only reference and is never copied into this
 * repository.</p>
 *
 * @see CardDemoException
 * @see #getValidationErrors()
 */
public class ValidationException extends CardDemoException {

    /**
     * Serialization version identifier.
     *
     * <p>{@link Throwable}, and therefore every exception, is
     * {@link java.io.Serializable}. Declaring an explicit
     * {@code serialVersionUID} (annotated with {@link Serial}) pins the
     * serialized form and keeps the Java&nbsp;25 build free of the
     * {@code serial} compiler lint warning, matching the convention of the
     * {@link CardDemoException} base type.</p>
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The collected validation-error descriptions, mirroring the COBOL
     * {@code WS-VALIDATION-FAIL-REASON-DESC} text (and the online
     * {@code WS-ERR-FLG} message) that each rejection recorded.
     *
     * <p><strong>Invariants:</strong> this reference is <em>never</em>
     * {@code null} &mdash; it defaults to {@link List#of() the empty list} when
     * no individual errors are supplied &mdash; and it is always an
     * <em>unmodifiable</em> list (built via {@link List#of()},
     * {@link List#of(Object)} or {@link List#copyOf(java.util.Collection)}), so
     * the exception's state cannot be altered after construction. Carrying a
     * list rather than a single string lets multiple field failures be reported
     * together and rendered as one HTTP&nbsp;400 response body.</p>
     */
    private final List<String> validationErrors;

    /**
     * The name of the single input field that failed validation, or
     * {@code null} when the exception was not created through the
     * {@link #ValidationException(String, String) single-field constructor}.
     *
     * <p>This is a convenience for the common case in which exactly one field
     * is rejected (analogous to an online program flagging one
     * {@code RECEIVE MAP} field via {@code WS-ERR-FLG}); the multi-error and
     * message-only constructors leave it unset.</p>
     */
    private final String fieldName;

    /**
     * Constructs the exception with an explicit detail message, leaving the
     * {@link #getFieldName() fieldName} unset ({@code null}) and the
     * {@link #getValidationErrors() validationErrors} list empty.
     *
     * <p>Use this overload when a caller has already composed a complete,
     * human-readable validation message and does not need the structured
     * collection populated.</p>
     *
     * @param message the human-readable detail message describing the
     *                validation failure, retained for retrieval via
     *                {@link #getMessage()}
     */
    public ValidationException(String message) {
        super(message);
        this.fieldName = null;
        this.validationErrors = List.of();
    }

    /**
     * Constructs the exception with an explicit detail message and an underlying
     * cause, leaving the {@link #getFieldName() fieldName} unset ({@code null})
     * and the {@link #getValidationErrors() validationErrors} list empty.
     *
     * <p>Use this overload to wrap a lower-level failure that surfaced while a
     * validation was being evaluated &mdash; for example a parsing or
     * type-conversion exception thrown while normalising an input &mdash; while
     * preserving its stack trace.</p>
     *
     * <p><strong>Overload note:</strong> this constructor's signature is
     * {@code (String, Throwable)} whereas
     * {@link #ValidationException(String, String)} is {@code (String, String)}.
     * A call site passing a bare {@code null} as the second argument is
     * therefore ambiguous and must cast the {@code null} (for example
     * {@code (Throwable) null}) to select this overload; supplying a real
     * {@link Throwable} reference is unambiguous.</p>
     *
     * @param message the human-readable detail message describing the
     *                validation failure, retained for retrieval via
     *                {@link #getMessage()}
     * @param cause   the underlying cause, retained for retrieval via
     *                {@link #getCause()}; a {@code null} value indicates the
     *                cause is nonexistent or unknown
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.fieldName = null;
        this.validationErrors = List.of();
    }

    /**
     * Constructs the exception for a single offending field, recording both the
     * {@code fieldName} and the message (which also becomes the sole entry of
     * the {@link #getValidationErrors() validationErrors} list).
     *
     * <p>This convenience constructor mirrors an online program flagging exactly
     * one {@code RECEIVE MAP} field via {@code WS-ERR-FLG} with an accompanying
     * message. The supplied {@code message} is used verbatim as the detail
     * message; the offending field is additionally retrievable via
     * {@link #getFieldName()}. If {@code message} is {@code null} the errors
     * list defensively defaults to empty (so it is never {@code null} and the
     * construction never fails).</p>
     *
     * <p><strong>Overload note:</strong> this constructor's signature is
     * {@code (String, String)} whereas
     * {@link #ValidationException(String, Throwable)} is
     * {@code (String, Throwable)} (see that constructor's overload note).</p>
     *
     * @param fieldName the name of the input field that failed validation;
     *                  retrievable via {@link #getFieldName()}
     * @param message   the human-readable detail message describing why the
     *                  field is invalid, retained for retrieval via
     *                  {@link #getMessage()} and stored as the single
     *                  validation error
     */
    public ValidationException(String fieldName, String message) {
        super(message);
        this.fieldName = fieldName;
        this.validationErrors = (message == null) ? List.of() : List.of(message);
    }

    /**
     * Constructs the exception from a collection of individual validation-error
     * descriptions, deriving a concise non-empty summary detail message and
     * storing a defensive, unmodifiable copy of the supplied list.
     *
     * <p>This is the primary constructor for aggregating several field failures
     * into one response &mdash; the modern equivalent of a COBOL validator that
     * accumulated multiple {@code WS-VALIDATION-FAIL-REASON-DESC} entries before
     * rejecting the request. The summary message is derived deterministically
     * (see below) so {@link #getMessage()} is always populated even when the
     * individual errors are inspected separately via
     * {@link #getValidationErrors()}:</p>
     * <ul>
     *   <li>{@code null} or empty list &rarr; {@code "Validation failed"};</li>
     *   <li>exactly one error &rarr;
     *       {@code "Validation failed: <error>"};</li>
     *   <li>multiple errors &rarr;
     *       {@code "Validation failed with <n> errors: <e1>; <e2>; ..."}.</li>
     * </ul>
     *
     * <p>A {@code null} argument is treated as "no individual errors" and yields
     * an empty (but never {@code null}) {@link #getValidationErrors() list};
     * otherwise the list is copied with {@link List#copyOf(java.util.Collection)}
     * so the resulting view is unmodifiable and decoupled from the caller's
     * collection.</p>
     *
     * @param validationErrors the individual validation-error descriptions to
     *                         aggregate; may be {@code null} or empty, and its
     *                         elements are expected to be non-{@code null}
     */
    public ValidationException(List<String> validationErrors) {
        super(buildSummaryMessage(validationErrors));
        this.fieldName = null;
        this.validationErrors = (validationErrors == null) ? List.of() : List.copyOf(validationErrors);
    }

    /**
     * Derives the deterministic summary detail message for the
     * {@link #ValidationException(List) multi-error constructor}.
     *
     * <p>Kept {@code private static} so it can be invoked from within the
     * {@code super(...)} call before instance initialisation completes. The
     * result is guaranteed non-empty for every input, satisfying the contract
     * that a multi-error {@code ValidationException} always carries a meaningful
     * detail message.</p>
     *
     * @param validationErrors the individual error descriptions, possibly
     *                         {@code null} or empty
     * @return a concise, human-readable summary that is never {@code null} or
     *         empty
     */
    private static String buildSummaryMessage(List<String> validationErrors) {
        if (validationErrors == null || validationErrors.isEmpty()) {
            return "Validation failed";
        }
        if (validationErrors.size() == 1) {
            return "Validation failed: " + validationErrors.get(0);
        }
        return "Validation failed with " + validationErrors.size()
                + " errors: " + String.join("; ", validationErrors);
    }

    /**
     * Returns the collected validation-error descriptions as an unmodifiable
     * list.
     *
     * <p>The returned list is <em>never</em> {@code null}: it is empty when the
     * exception was created through a message-only or cause constructor, holds a
     * single entry for the {@link #ValidationException(String, String)
     * single-field constructor}, and holds a defensive copy of the supplied
     * collection for the {@link #ValidationException(List) multi-error
     * constructor}. Attempting to modify the returned list throws
     * {@link UnsupportedOperationException}, guaranteeing the exception's state
     * is immutable.</p>
     *
     * @return the unmodifiable, never-{@code null} list of validation errors
     */
    public List<String> getValidationErrors() {
        return validationErrors;
    }

    /**
     * Returns the name of the single field that failed validation, or
     * {@code null} when this exception was not created through the
     * {@link #ValidationException(String, String) single-field constructor}.
     *
     * @return the offending field name, or {@code null} if unset
     */
    public String getFieldName() {
        return fieldName;
    }
}
