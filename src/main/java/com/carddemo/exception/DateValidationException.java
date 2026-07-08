package com.carddemo.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals that a supplied date value failed parsing or validation.
 *
 * <p>This is the Java replacement for the {@code CSUTLDTC} date-check utility,
 * which wrapped the Language Environment {@code CEEDAYS} callable service: a
 * non-zero severity from {@code CEEDAYS} indicated an invalid date. In the
 * migrated system, {@code DateValidationService} parses and validates date
 * input with {@link java.time.LocalDate} and custom validators; when a value
 * cannot be parsed or is out of range it raises this exception.
 *
 * <p>The condition always maps to {@link HttpStatus#BAD_REQUEST} (HTTP 400) at
 * the REST boundary. The exception is unchecked (it extends
 * {@link CardDemoException}) and is a pure data carrier: it optionally records
 * the offending {@link #getInvalidValue() input value} and the
 * {@link #getExpectedFormat() expected format mask} for diagnostics, and
 * performs no logging or other side effects.
 */
public class DateValidationException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /** The offending date value as received, or {@code null} when not supplied. */
    private final String invalidValue;

    /** The expected date format mask (for example {@code "yyyy-MM-dd"}), or {@code null} when not supplied. */
    private final String expectedFormat;

    /**
     * Creates a date-validation exception with a detail message and no captured
     * offending value or expected format.
     *
     * @param message the detail message; may be {@code null}
     */
    public DateValidationException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
        this.invalidValue = null;
        this.expectedFormat = null;
    }

    /**
     * Creates a date-validation exception with a detail message and a triggering
     * cause, typically a {@code java.time.format.DateTimeParseException} raised
     * while parsing the input.
     *
     * @param message the detail message; may be {@code null}
     * @param cause   the underlying cause; may be {@code null}
     */
    public DateValidationException(String message, Throwable cause) {
        super(message, HttpStatus.BAD_REQUEST, cause);
        this.invalidValue = null;
        this.expectedFormat = null;
    }

    /**
     * Creates a date-validation exception with a detail message and the
     * offending date value.
     *
     * @param message      the detail message; may be {@code null}
     * @param invalidValue the offending date value as received; may be {@code null}
     */
    public DateValidationException(String message, String invalidValue) {
        super(message, HttpStatus.BAD_REQUEST);
        this.invalidValue = invalidValue;
        this.expectedFormat = null;
    }

    /**
     * Creates a date-validation exception with a detail message, the offending
     * date value, and the expected format mask.
     *
     * @param message        the detail message; may be {@code null}
     * @param invalidValue   the offending date value as received; may be {@code null}
     * @param expectedFormat the expected date format mask; may be {@code null}
     */
    public DateValidationException(String message, String invalidValue, String expectedFormat) {
        super(message, HttpStatus.BAD_REQUEST);
        this.invalidValue = invalidValue;
        this.expectedFormat = expectedFormat;
    }

    /**
     * Returns the offending date value that failed validation, when captured.
     *
     * @return the offending date value, or {@code null} if it was not supplied
     */
    public String getInvalidValue() {
        return invalidValue;
    }

    /**
     * Returns the expected date format mask, when captured.
     *
     * @return the expected date format mask, or {@code null} if it was not supplied
     */
    public String getExpectedFormat() {
        return expectedFormat;
    }
}
