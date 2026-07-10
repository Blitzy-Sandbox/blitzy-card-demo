package com.carddemo.config;

import org.springframework.http.HttpStatus;

/**
 * Single source of truth for the machine-readable {@code code} and the leak-free human {@code message}
 * of a <em>container / servlet-tier</em> error &mdash; one whose HTTP status originates from the
 * infrastructure (Tomcat connector rejection, servlet {@code /error} dispatch) rather than from a
 * typed {@code exception.CardDemoException} carrying its own domain code.
 *
 * <p>Both container-error renderers use this helper so their {@code dto.ErrorResponse} bodies are
 * byte-identical for the same status: {@link JsonErrorReportValve} (Tomcat pipeline, for
 * connector-level rejections that never reach the servlet) and {@code controller.JsonErrorController}
 * (servlet {@code /error} dispatch). Centralizing the mapping here keeps the two paths from drifting
 * and makes the mapping directly unit-testable without booting a servlet container.</p>
 *
 * <p>The messages are deliberately generic and fixed per status &mdash; they never echo the offending
 * request target, exception text, or any other request-derived value &mdash; so a malformed or
 * hostile request cannot turn the error body into an information-disclosure or reflection vector
 * (AAP &sect;0.3.2 / &sect;0.8.1). Design rationale lives in {@code docs/decision-log.md}
 * (Explainability rule), not in code comments.</p>
 *
 * @see JsonErrorReportValve
 * @see com.carddemo.dto.ErrorResponse
 */
public final class ContainerErrors {

    /** Generic fallback message used when the status is unknown or otherwise unclassified. */
    private static final String MESSAGE_GENERIC = "The request could not be processed.";

    /** Message for a 400-class malformed request (including connector-level URI rejections). */
    private static final String MESSAGE_BAD_REQUEST =
            "The request could not be processed because it was malformed.";

    /** Message for a 404 missing resource. */
    private static final String MESSAGE_NOT_FOUND = "The requested resource was not found.";

    /** Message for a 405 unsupported method. */
    private static final String MESSAGE_METHOD_NOT_ALLOWED =
            "The request method is not supported for this resource.";

    /** Message for a 406 unacceptable representation. */
    private static final String MESSAGE_NOT_ACCEPTABLE = "The requested representation is not available.";

    /** Message for a 415 unsupported media type. */
    private static final String MESSAGE_UNSUPPORTED_MEDIA_TYPE = "The request media type is not supported.";

    /** Message for any 5xx server-side error. */
    private static final String MESSAGE_SERVER_ERROR =
            "An unexpected error occurred while processing the request.";

    private ContainerErrors() {
        // Utility class: no instances.
    }

    /**
     * Derives the stable, machine-readable domain {@code code} for a container error. When the status
     * resolves to a known {@link HttpStatus} its {@code SCREAMING_SNAKE_CASE} name is used (for
     * example {@code BAD_REQUEST}, {@code NOT_FOUND}, {@code METHOD_NOT_ALLOWED}); otherwise a stable
     * {@code HTTP_<code>} token is produced so the field is always well defined.
     *
     * @param statusCode the raw HTTP status code
     * @param status     the resolved {@link HttpStatus}, or {@code null} for a non-standard code
     * @return a non-null, stable domain code
     */
    public static String codeFor(final int statusCode, final HttpStatus status) {
        return (status != null) ? status.name() : "HTTP_" + statusCode;
    }

    /**
     * Returns the fixed, leak-free human-readable {@code message} for a container error status. The
     * text is chosen by status and never incorporates request-derived data.
     *
     * @param status the resolved {@link HttpStatus}, or {@code null} for a non-standard code
     * @return a non-null, generic, safe message
     */
    public static String messageFor(final HttpStatus status) {
        if (status == null) {
            return MESSAGE_GENERIC;
        }
        return switch (status) {
            case BAD_REQUEST -> MESSAGE_BAD_REQUEST;
            case NOT_FOUND -> MESSAGE_NOT_FOUND;
            case METHOD_NOT_ALLOWED -> MESSAGE_METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> MESSAGE_NOT_ACCEPTABLE;
            case UNSUPPORTED_MEDIA_TYPE -> MESSAGE_UNSUPPORTED_MEDIA_TYPE;
            default -> {
                if (status.is5xxServerError()) {
                    yield MESSAGE_SERVER_ERROR;
                }
                if (status.is4xxClientError()) {
                    yield MESSAGE_GENERIC;
                }
                yield status.getReasonPhrase();
            }
        };
    }
}
