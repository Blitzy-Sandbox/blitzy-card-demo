/*
 * ValidationException.java
 *
 * Field-level validation failure exception for the CardDemo application.
 *
 * This exception aggregates multiple field-level validation errors into a single
 * throwable, enabling structured error reporting for REST API responses. It maps
 * directly to the field-by-field validation patterns found in the original COBOL
 * programs, where each field is validated individually with a specific error message.
 *
 * COBOL Traceability:
 * - CSLKPCDY.cpy: NANPA area code validation (300+ codes), US state abbreviation
 *   validation (51 codes), and state/ZIP prefix cross-validation tables
 * - CSUTLDTC.cbl: Date validation subprogram wrapping LE CEEDAYS with severity
 *   codes and result text for each validation outcome
 * - CSUTLDPY.cpy + CSUTLDWY.cpy: Date validation parameters and work areas
 *   defining the calling interface with format masks and result areas
 * - COACTUPC.cbl (paragraph 9700-CHECK-CHANGE-IN-REC, lines 4109+): Field-by-field
 *   comparison and validation of 15+ fields including ACCT-ACTIVE-STATUS,
 *   ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, CUST-FIRST-NAME, CUST-ADDR-STATE-CD,
 *   CUST-ADDR-ZIP, CUST-PHONE-NUM-1/2, CUST-SSN, CUST-FICO-CREDIT-SCORE
 * - COCRDUPC.cbl: Card update field validation with similar per-field error messages
 * - COTRN02C.cbl: Transaction add validation including date and amount checks
 *
 * Design Decision (Decision Log D-EXCEPT-VALID):
 * Uses an inner record FieldError to carry per-field validation details rather than
 * a flat message string, because:
 * 1. COBOL validation paragraphs validate each field individually with specific
 *    error messages — the Java equivalent must preserve this granularity
 * 2. Spring @ControllerAdvice can extract getFieldErrors() to produce structured
 *    JSON responses with per-field error details for API consumers
 * 3. Multiple validation failures can be aggregated in a single exception throw,
 *    matching the COBOL pattern of setting multiple error flags before returning
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.exception;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Exception representing one or more field-level validation failures.
 *
 * <p>This exception is thrown when input data fails validation rules ported from
 * the original COBOL validation tables and subprograms. It supports aggregating
 * multiple field-level errors into a single exception for structured error reporting.</p>
 *
 * <h3>Validation Sources (COBOL Traceability)</h3>
 * <ul>
 *   <li>NANPA area code validation — 300+ valid area codes (← CSLKPCDY.cpy)</li>
 *   <li>US state abbreviation validation — 51 state/territory codes (← CSLKPCDY.cpy)</li>
 *   <li>State/ZIP prefix cross-validation (← CSLKPCDY.cpy)</li>
 *   <li>Date format validation — YYYY-MM-DD via CEEDAYS equivalent (← CSUTLDTC.cbl)</li>
 *   <li>FICO score range validation — 300 to 850 (← COACTUPC.cbl)</li>
 *   <li>Phone number format with NANPA area code check (← COACTUPC.cbl)</li>
 *   <li>Account/card field validation — 15+ fields per update (← COACTUPC.cbl, COCRDUPC.cbl)</li>
 * </ul>
 *
 * <h3>HTTP Mapping</h3>
 * <p>Maps to HTTP 400 (Bad Request) in REST controllers via {@code @ControllerAdvice}.
 * The error handler extracts {@link #getFieldErrors()} and returns a structured JSON
 * response with per-field error details.</p>
 *
 * <h3>Usage Examples</h3>
 * <pre>{@code
 * // Single field validation failure
 * throw ValidationException.of("ficoScore", "250",
 *     "FICO score must be between 300 and 850");
 *
 * // Multiple field validation failures
 * List<ValidationException.FieldError> errors = new ArrayList<>();
 * errors.add(new ValidationException.FieldError("stateCode", "XX",
 *     "Invalid US state abbreviation"));
 * errors.add(new ValidationException.FieldError("zipCode", "00000",
 *     "ZIP code prefix does not match state"));
 * throw new ValidationException(errors);
 *
 * // Simple message-only validation failure
 * throw new ValidationException("Account data validation failed");
 * }</pre>
 *
 * @see CardDemoException
 * @see FieldError
 */
public class ValidationException extends CardDemoException {

    /**
     * Serial version UID for serialization compatibility.
     * Inherits Serializable from CardDemoException → RuntimeException → Throwable.
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Application-level error code for validation failures.
     *
     * <p>Used when constructing this exception with field errors to provide
     * a consistent error code in API responses and structured logging.
     * Maps to the "VALID" category in the CardDemo error code taxonomy.</p>
     */
    public static final String VALIDATION_ERROR_CODE = "VALID";

    /**
     * Represents a single field-level validation error.
     *
     * <p>Each instance captures the field that failed validation, the value that was
     * rejected, and a human-readable error message. This record is immutable and
     * thread-safe.</p>
     *
     * <p>The {@code rejectedValue} is stored as a {@code String} for safe display
     * regardless of the original field type. It may be {@code null} when the
     * validation failure is due to a missing required field.</p>
     *
     * <h4>COBOL Field Validation Mapping</h4>
     * <table>
     *   <caption>Example field validation mappings from COBOL source</caption>
     *   <tr><th>Field Name</th><th>COBOL Source</th><th>Validation Rule</th></tr>
     *   <tr><td>ficoScore</td><td>CUST-FICO-CREDIT-SCORE</td><td>Range 300-850</td></tr>
     *   <tr><td>stateCode</td><td>CUST-ADDR-STATE-CD</td><td>Valid US state abbreviation</td></tr>
     *   <tr><td>zipCode</td><td>CUST-ADDR-ZIP</td><td>ZIP prefix matches state</td></tr>
     *   <tr><td>phoneAreaCode</td><td>CUST-PHONE-NUM-1(1:3)</td><td>Valid NANPA area code</td></tr>
     *   <tr><td>openDate</td><td>ACCT-OPEN-DATE</td><td>Valid CCYYMMDD date</td></tr>
     * </table>
     *
     * @param fieldName     the name of the field that failed validation; must not be
     *                      {@code null} or blank
     * @param rejectedValue the value that was rejected, represented as a {@code String}
     *                      for display; may be {@code null} for missing required fields
     * @param message       the human-readable validation error message; must not be
     *                      {@code null} or blank
     */
    public record FieldError(String fieldName, String rejectedValue, String message)
            implements Serializable {

        /**
         * Serial version UID for serialization compatibility of the FieldError record.
         */
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Compact constructor that validates required parameters.
         *
         * <p>Ensures that {@code fieldName} and {@code message} are non-null and
         * non-blank. The {@code rejectedValue} is allowed to be {@code null} to
         * represent missing required fields.</p>
         *
         * @throws IllegalArgumentException if {@code fieldName} is null or blank
         * @throws IllegalArgumentException if {@code message} is null or blank
         */
        public FieldError {
            if (fieldName == null || fieldName.isBlank()) {
                throw new IllegalArgumentException(
                        "fieldName must not be null or blank");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        "message must not be null or blank");
            }
        }
    }

    /**
     * The list of field-level validation errors associated with this exception.
     *
     * <p>This list is always non-null. It may be empty when the exception is
     * constructed with only a message string (no specific field errors).</p>
     *
     * <p>Declared as {@link ArrayList} rather than {@link List} to satisfy
     * serialization requirements — {@code ArrayList} implements {@code Serializable}
     * while the {@code List} interface does not. The getter method
     * {@link #getFieldErrors()} returns an unmodifiable view to prevent external
     * mutation.</p>
     */
    private final ArrayList<FieldError> fieldErrors;

    /**
     * Constructs a new {@code ValidationException} with the specified detail message
     * and no field-level errors.
     *
     * <p>Uses the default error code from {@link CardDemoException} and an empty
     * field errors list. Suitable for general validation failures where individual
     * field details are not available or not applicable.</p>
     *
     * <p>Example: {@code throw new ValidationException("Account data is invalid");}</p>
     *
     * @param message the detail message describing the validation failure
     */
    public ValidationException(String message) {
        super(message);
        this.fieldErrors = new ArrayList<>();
    }

    /**
     * Constructs a new {@code ValidationException} with the specified detail message
     * and a list of field-level validation errors.
     *
     * <p>Uses the {@link #VALIDATION_ERROR_CODE} ("VALID") as the error code for
     * structured API error responses. The provided field errors list is defensively
     * copied to prevent external mutation.</p>
     *
     * <p>This is the primary constructor for multi-field validation failures, such as
     * the 15+ field validation in COACTUPC.cbl paragraph 9700-CHECK-CHANGE-IN-REC.</p>
     *
     * @param message     the detail message describing the overall validation failure
     * @param fieldErrors the list of individual field validation errors; may be
     *                    {@code null} or empty (treated as no field errors)
     */
    public ValidationException(String message, List<FieldError> fieldErrors) {
        super(message, VALIDATION_ERROR_CODE);
        this.fieldErrors = toArrayList(fieldErrors);
    }

    /**
     * Constructs a new {@code ValidationException} from a list of field-level
     * validation errors, automatically generating a summary message.
     *
     * <p>The summary message follows the pattern:</p>
     * <ul>
     *   <li>No errors: "Validation failed"</li>
     *   <li>One error: "Validation failed for 1 field: {fieldName}"</li>
     *   <li>Multiple errors: "Validation failed for {n} field(s)"</li>
     * </ul>
     *
     * <p>Uses the {@link #VALIDATION_ERROR_CODE} ("VALID") as the error code.</p>
     *
     * @param fieldErrors the list of individual field validation errors; may be
     *                    {@code null} or empty
     */
    public ValidationException(List<FieldError> fieldErrors) {
        super(buildSummaryMessage(fieldErrors), VALIDATION_ERROR_CODE);
        this.fieldErrors = toArrayList(fieldErrors);
    }

    /**
     * Returns the list of field-level validation errors associated with this exception.
     *
     * <p>The returned list is unmodifiable. Callers cannot add, remove, or replace
     * elements. This guarantees the exception's internal state remains consistent
     * throughout its lifecycle, matching the immutable nature of COBOL working storage
     * areas after validation completes.</p>
     *
     * <p>The list may be empty if the exception was constructed with only a message
     * string (no specific field errors were identified).</p>
     *
     * @return an unmodifiable list of field-level validation errors, never {@code null}
     */
    public List<FieldError> getFieldErrors() {
        if (fieldErrors.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(fieldErrors);
    }

    /**
     * Creates a {@code ValidationException} for a single field validation failure.
     *
     * <p>This is a convenience factory method for the common case where a single
     * field fails validation. It constructs a {@link FieldError} and wraps it in
     * a new {@code ValidationException}.</p>
     *
     * <p>Example usage (mapping COBOL FICO score validation):</p>
     * <pre>{@code
     * // COBOL: IF CUST-FICO-CREDIT-SCORE < 300 OR > 850
     * if (ficoScore < 300 || ficoScore > 850) {
     *     throw ValidationException.of("ficoScore", String.valueOf(ficoScore),
     *         "FICO score must be between 300 and 850");
     * }
     * }</pre>
     *
     * @param fieldName     the name of the field that failed validation; must not be
     *                      {@code null} or blank
     * @param rejectedValue the value that was rejected, as a {@code String}; may be
     *                      {@code null} for missing required fields
     * @param message       the human-readable validation error message; must not be
     *                      {@code null} or blank
     * @return a new {@code ValidationException} containing the single field error
     * @throws IllegalArgumentException if {@code fieldName} or {@code message} is
     *                                  null or blank
     */
    public static ValidationException of(String fieldName, String rejectedValue,
                                         String message) {
        FieldError error = new FieldError(fieldName, rejectedValue, message);
        return new ValidationException(message, List.of(error));
    }

    /**
     * Builds a summary message from the provided list of field errors.
     *
     * <p>Generates a human-readable summary that indicates the number of fields
     * that failed validation. For single-field failures, the field name is included
     * in the message for quick identification.</p>
     *
     * @param fieldErrors the list of field errors to summarize; may be {@code null}
     * @return a summary message string, never {@code null}
     */
    private static String buildSummaryMessage(List<FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return "Validation failed";
        }
        int count = fieldErrors.size();
        if (count == 1) {
            return "Validation failed for 1 field: " + fieldErrors.getFirst().fieldName();
        }
        return "Validation failed for " + count + " field(s)";
    }

    /**
     * Creates a defensive {@link ArrayList} copy of the provided field errors list.
     *
     * <p>If the input list is {@code null} or empty, returns an empty
     * {@link ArrayList}. Otherwise, creates a new {@link ArrayList} copy to
     * prevent external mutation of the exception's internal state.</p>
     *
     * <p>An {@link ArrayList} is used rather than an immutable wrapper to satisfy
     * serialization requirements — the field's declared type must implement
     * {@link java.io.Serializable} for zero-warning compilation with
     * {@code -Xlint:serial}.</p>
     *
     * @param fieldErrors the list to copy; may be {@code null}
     * @return a new {@code ArrayList} containing the field errors, never {@code null}
     */
    private static ArrayList<FieldError> toArrayList(List<FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(fieldErrors);
    }

    /**
     * Returns a string representation of this validation exception including the
     * error code, number of field errors, and the exception message.
     *
     * <p>Format: {@code ValidationException[errorCode=VALID, fieldErrors=N]: message}</p>
     *
     * @return a descriptive string representation of this exception
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName());
        sb.append("[errorCode=").append(getErrorCode());
        sb.append(", fieldErrors=").append(fieldErrors.size());
        sb.append("]: ").append(getMessage());
        return sb.toString();
    }
}
