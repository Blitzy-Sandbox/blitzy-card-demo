package com.carddemo.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Typed exception for input and record validation failures, always surfaced as
 * HTTP {@code 400 Bad Request}.
 *
 * <p>It represents the two validation flavours of the migrated CardDemo system:
 * <ul>
 *   <li><strong>Online field edits</strong> &mdash; Jakarta Validation constraint
 *       violations on request DTOs and service-layer field checks migrated from
 *       the per-field edits of {@code COACTUPC} (mandatory, format, and range
 *       rules). Multiple field failures are reported together through the
 *       {@link #getFieldErrors() field&rarr;message map}.</li>
 *   <li><strong>Batch posting rejects</strong> &mdash; business-rule failures
 *       migrated from {@code CBTRN02C}'s {@code 1500-VALIDATE-TRAN} (invalid card,
 *       account not found, over-limit, expired account). The originating
 *       classification is carried as an optional {@link #getRejectReason()
 *       RejectReason}.</li>
 * </ul>
 *
 * <p>Both pieces of state are optional and independent: an instance may carry a
 * {@link RejectReason}, a field-error map, both, or neither. The class is an
 * unchecked {@link CardDemoException} and is effectively immutable &mdash; the
 * field-error map is copied defensively on construction and exposed as an
 * unmodifiable view. It performs no logging and has no side effects.
 */
public class ValidationException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * Optional batch-posting reject classification, or {@code null} when the
     * failure is not a batch reject. {@link RejectReason} is an enum and thus
     * serializable, so this field participates in serialization.
     */
    private final RejectReason rejectReason;

    /**
     * Optional, unmodifiable field&rarr;message map for online multi-field
     * validation; never {@code null} for a normally constructed instance (an
     * empty map denotes "no field errors").
     */
    private final transient Map<String, String> fieldErrors;

    /**
     * Creates a validation exception with a detail message and no additional
     * classification.
     *
     * @param message the detail message describing the validation failure; may be {@code null}
     */
    public ValidationException(String message) {
        this(message, null, null);
    }

    /**
     * Creates a validation exception with a detail message and a triggering
     * cause (for example, a wrapped parsing or conversion error).
     *
     * @param message the detail message describing the validation failure; may be {@code null}
     * @param cause   the underlying cause; may be {@code null}
     */
    public ValidationException(String message, Throwable cause) {
        super(message, HttpStatus.BAD_REQUEST, cause);
        this.rejectReason = null;
        this.fieldErrors = Map.of();
    }

    /**
     * Creates a validation exception classified by a batch-posting
     * {@link RejectReason} (for example {@link RejectReason#OVERLIMIT}).
     *
     * @param message      the detail message describing the validation failure; may be {@code null}
     * @param rejectReason the batch reject classification; may be {@code null}
     */
    public ValidationException(String message, RejectReason rejectReason) {
        this(message, rejectReason, null);
    }

    /**
     * Creates a validation exception carrying a set of online field errors. The
     * supplied map is copied defensively, so later mutation of the argument does
     * not affect this exception.
     *
     * @param message     the detail message describing the validation failure; may be {@code null}
     * @param fieldErrors a field&rarr;message map of individual field failures; may be
     *                    {@code null} or empty, in which case no field errors are recorded
     */
    public ValidationException(String message, Map<String, String> fieldErrors) {
        this(message, null, fieldErrors);
    }

    /**
     * Full constructor carrying both a batch-posting {@link RejectReason} and a
     * set of online field errors. The supplied map is copied defensively into an
     * unmodifiable map.
     *
     * @param message      the detail message describing the validation failure; may be {@code null}
     * @param rejectReason the batch reject classification; may be {@code null}
     * @param fieldErrors  a field&rarr;message map of individual field failures; may be
     *                     {@code null} or empty, in which case no field errors are recorded
     */
    public ValidationException(String message, RejectReason rejectReason, Map<String, String> fieldErrors) {
        super(message, HttpStatus.BAD_REQUEST);
        this.rejectReason = rejectReason;
        this.fieldErrors = (fieldErrors == null) ? Map.of() : Map.copyOf(fieldErrors);
    }

    /**
     * Returns the batch-posting reject classification, when one is present.
     *
     * @return the originating {@link RejectReason}, or {@code null} if this
     *         validation failure has no batch reject classification
     */
    public RejectReason getRejectReason() {
        return rejectReason;
    }

    /**
     * Returns the online field errors as an unmodifiable field&rarr;message map.
     *
     * @return an unmodifiable map of field failures; never {@code null} (an empty
     *         map when there are no field errors)
     */
    public Map<String, String> getFieldErrors() {
        return (fieldErrors == null) ? Map.of() : fieldErrors;
    }

    /**
     * Stable machine-readable error code for validation failures. Kept identical
     * to the code the global handler surfaces for Jakarta bean-validation
     * failures so a single code ({@code VALIDATION_ERROR}) represents "validation
     * failed" across both the online field-edit and framework paths.
     */
    public static final String ERROR_CODE = "VALIDATION_ERROR";

    /**
     * Returns {@link #ERROR_CODE} ({@code VALIDATION_ERROR}) rather than the raw
     * class name, so a domain {@link ValidationException} and a Jakarta
     * bean-validation failure present the same stable {@code code} on the wire.
     *
     * @return {@code "VALIDATION_ERROR"}; never {@code null}
     */
    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
