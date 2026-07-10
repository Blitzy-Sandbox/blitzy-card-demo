package com.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Immutable, JSON-serializable error contract returned by the global
 * {@code @RestControllerAdvice} exception handler for every REST error.
 *
 * <p>This DTO is the modern replacement for the legacy CardDemo on-screen error
 * plumbing: the {@code CCARD-ERROR-MSG PIC X(75)} work area (copybook
 * {@code CVCRD01Y}), the common user messages of {@code CSMSG01Y}
 * ({@code CCDA-COMMON-MESSAGES}), and the abend descriptor of {@code CSMSG02Y}
 * ({@code ABEND-DATA}: code / culprit / reason / message). The COBOL
 * {@code FILE STATUS} codes and the seven typed exceptions are mapped by the
 * handler onto {@link #status()}, {@link #error()} and {@link #code()}; the
 * operator message ({@code ERRMSG}/{@code INFOMSG}) becomes {@link #message()}.
 *
 * <p><strong>Security:</strong> {@link #message()} and {@link #fieldErrors()}
 * must never carry stack traces, SQL, secrets, passwords, or unmasked PANs
 * (AAP §0.3.2, §0.8.1). {@link FieldError#of(String, Object, String)} enforces
 * this for rejected values; callers populating {@link #message()} are
 * responsible for supplying an already-safe, human-readable string.
 *
 * <p>The instance is a stateless data holder (no CICS {@code COMMAREA} state).
 * {@code null} components are omitted from the JSON payload
 * ({@link JsonInclude.Include#NON_NULL}); {@link #fieldErrors()} is always a
 * non-null, unmodifiable list and is therefore always serialized (as
 * {@code []} when empty).
 *
 * @param timestamp     server time the error was produced (ISO-8601); serialized
 *                      via {@code jackson-datatype-jsr310}
 * @param status        HTTP status code (e.g. 400, 404, 409, 500)
 * @param error         HTTP reason phrase (e.g. "Bad Request", "Conflict")
 * @param code          application/domain error code derived from the COBOL
 *                      {@code FILE STATUS} status enum or a typed exception
 *                      (e.g. {@code RECORD_NOT_FOUND}, {@code OPTIMISTIC_LOCK},
 *                      {@code VALIDATION_ERROR}); may be {@code null}
 * @param message       human-readable, leak-free message (the modern
 *                      {@code CCARD-ERROR-MSG}/{@code ERRMSG})
 * @param path          request URI that produced the error
 * @param correlationId MDC correlation id tying this response to server
 *                      logs/traces (Observability rule); may be {@code null}
 * @param fieldErrors   per-field validation failures (from Jakarta Validation
 *                      {@code MethodArgumentNotValidException}); never
 *                      {@code null} — empty when the error is not a validation
 *                      failure
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldError> fieldErrors) {

    /**
     * Canonical constructor enforcing a null-safe, immutable {@link #fieldErrors()}.
     * A {@code null} argument becomes an empty list; any other list is defensively
     * copied into an unmodifiable list ({@code null} elements are rejected).
     */
    public ErrorResponse {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Builds a general error response stamped with the current server time and no
     * field errors. Suitable for non-validation failures such as 404 / 409 / 500.
     *
     * @param status  HTTP status code
     * @param error   HTTP reason phrase
     * @param message leak-free, human-readable message
     * @param path    request URI that produced the error
     * @return a new immutable {@code ErrorResponse}
     */
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(
                OffsetDateTime.now(), status, error, null, message, path, null, List.of());
    }

    /**
     * Builds a validation error response (HTTP 400 style) carrying the per-field
     * failures. The domain {@link #code()} is set to {@code "VALIDATION_ERROR"}
     * and the timestamp to the current server time.
     *
     * @param status      HTTP status code (typically 400)
     * @param error       HTTP reason phrase (typically "Bad Request")
     * @param message     leak-free, human-readable summary message
     * @param path        request URI that produced the error
     * @param fieldErrors per-field validation failures; may be {@code null}
     * @return a new immutable {@code ErrorResponse}
     */
    public static ErrorResponse ofValidation(
            int status, String error, String message, String path, List<FieldError> fieldErrors) {
        return new ErrorResponse(
                OffsetDateTime.now(), status, error, "VALIDATION_ERROR", message, path, null, fieldErrors);
    }

    /**
     * A single field-level validation failure.
     *
     * <p>{@code rejectedValueSummary} deliberately holds only a <em>safe</em>
     * summary of the offending value — never the raw input — so that secrets,
     * passwords and full PANs cannot leak into an error payload. Use
     * {@link #of(String, Object, String)} to derive the summary from a raw value.
     *
     * @param field                the offending field (property path)
     * @param rejectedValueSummary a masked/omitted summary of the rejected value;
     *                             may be {@code null} when there is nothing safe
     *                             to show
     * @param message              the validation constraint message
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String rejectedValueSummary, String message) {

        /**
         * Creates a {@code FieldError} whose {@code rejectedValueSummary} is a
         * safe, masked representation of {@code rejectedValue}. Sensitive fields
         * (password, secret, token, card/PAN, cvv, pin) are fully redacted; long
         * digit runs (12+ digits, i.e. PAN-like values) are masked to their last
         * four digits; non-scalar values are reduced to their type name; and any
         * remaining value is length-capped.
         *
         * @param field         the offending field (property path)
         * @param rejectedValue the raw rejected value (may be {@code null})
         * @param message       the validation constraint message
         * @return a {@code FieldError} that cannot leak the raw value
         */
        public static FieldError of(String field, Object rejectedValue, String message) {
            return new FieldError(field, summarize(field, rejectedValue), message);
        }

        /**
         * Derives a leak-free summary of a rejected value. See
         * {@link #of(String, Object, String)} for the masking policy.
         *
         * @param field         the field name, used to detect sensitive properties
         * @param rejectedValue the raw rejected value (never {@code null} is
         *                      required, {@code null} yields {@code null})
         * @return a safe, bounded summary, or {@code null} when there is nothing
         *         to show
         */
        private static String summarize(String field, Object rejectedValue) {
            if (rejectedValue == null) {
                return null;
            }
            String name = (field == null) ? "" : field.toLowerCase(Locale.ROOT);
            if (name.contains("password") || name.contains("passwd")
                    || name.contains("secret") || name.contains("token")
                    || name.contains("card") || name.contains("pan")
                    || name.contains("cvv") || name.contains("cvc")
                    || name.contains("pin")) {
                return "***";
            }
            // Avoid echoing arbitrary object internals: only scalar-like values
            // are summarized; anything else is reduced to its type name.
            if (!(rejectedValue instanceof CharSequence)
                    && !(rejectedValue instanceof Number)
                    && !(rejectedValue instanceof Boolean)
                    && !(rejectedValue instanceof Character)) {
                return rejectedValue.getClass().getSimpleName();
            }
            String raw = String.valueOf(rejectedValue);
            // Mask PAN-like / long numeric identifiers, keeping only the last
            // four digits (a 16-digit card becomes "****1234").
            String digits = raw.replaceAll("\\D", "");
            if (digits.length() >= 12) {
                return "****" + digits.substring(digits.length() - 4);
            }
            // Length-cap any other value to keep the payload bounded.
            if (raw.length() > 40) {
                return raw.substring(0, 37) + "...";
            }
            return raw;
        }
    }
}
