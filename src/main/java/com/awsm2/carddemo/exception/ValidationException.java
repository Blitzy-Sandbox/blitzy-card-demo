/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.exception;

import java.util.Collections;
import java.util.List;

/**
 * Thrown when field-level validation fails. Carries optional per-field error
 * details that {@code GlobalExceptionHandler} extracts into the {@code fieldErrors}
 * property of the standardized JSON error envelope.
 *
 * <p><b>HTTP status mapping:</b> Mapped by {@code GlobalExceptionHandler} to
 * <b>HTTP 400 Bad Request</b> per AAP &sect;0.4.1 ("Maps validation failures to
 * 400 Bad Request"). HTTP status mapping is centralized in the
 * {@code GlobalExceptionHandler}; this class deliberately does NOT carry a
 * {@code @ResponseStatus} annotation.</p>
 *
 * <p><b>COBOL provenance:</b> replaces the field validation cascades in:</p>
 * <ul>
 *   <li>{@code app/cbl/COACTUPC.cbl} &mdash; account update field validations
 *       (phone area code lookup against the NANPA registry, state/ZIP-prefix
 *       combination check, date format checks, numeric range checks, SSN
 *       validation, date of birth, open/expiry/reissue date checks).</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} &mdash; transaction-add validations
 *       (TRAN-AMT decimal format, card number format, date format, account ID
 *       presence, transaction type/category presence).</li>
 *   <li>{@code app/cpy/CSLKPCDY.cpy} &mdash; lookup tables for NANPA area
 *       codes, US state/territory abbreviations, and valid state/ZIP-prefix
 *       combinations. Ported to {@code ValidationLookupService} in the Java
 *       target.</li>
 *   <li>{@code app/cpy/CSUTLDPY.cpy} + {@code app/cbl/CSUTLDTC.cbl} &mdash;
 *       date validation cascade originally implemented via the LE
 *       {@code CEEDAYS} service. Ported to {@code DateValidationService} in
 *       the Java target using {@link java.time.LocalDate#parse}.</li>
 * </ul>
 *
 * <p><b>Envelope shape:</b> The exception carries an optional
 * {@code List<FieldError>} of per-field errors. The
 * {@code GlobalExceptionHandler} reads this list (via {@link #getFieldErrors()})
 * and projects it onto the {@code fieldErrors} property of the {@code ApiResponse}
 * envelope shape defined in AAP &sect;0.3.4
 * ({@code {"code":"...","message":"...","fieldErrors":[{"field":"...","message":"..."}]}}).</p>
 *
 * <p><b>Reason code:</b> Defaults to {@link #DEFAULT_REASON_CODE} ({@code "VALIDATION"}).
 * This is a semantic identifier, not a COBOL FILE STATUS code. COBOL transaction
 * posting validation failures use {@code WS-VALIDATION-FAIL-REASON} 4-digit
 * codes (100-109 family documented in {@code app/cbl/CBTRN02C.cbl}); those
 * specific codes are surfaced via {@link CreditLimitExceededException} (102),
 * {@link ExpiredCardException} (103), and related typed exceptions. For general
 * field-level validation across the application, the {@code "VALIDATION"}
 * reason code is canonical.</p>
 *
 * <p><b>Immutability:</b> The field-error list is wrapped with
 * {@link Collections#unmodifiableList(List)} at construction time, so callers
 * cannot mutate the exception's state post-construction. {@link #getFieldErrors()}
 * never returns {@code null} &mdash; it returns {@link Collections#emptyList()}
 * when no field errors were supplied.</p>
 *
 * <p><b>Related case &mdash; Jakarta Bean Validation:</b>
 * {@code MethodArgumentNotValidException} produced by
 * {@code @Valid @RequestBody} DTOs is handled SEPARATELY by
 * {@code GlobalExceptionHandler.handleMethodArgumentNotValid(...)}. That
 * handler produces the same 400 + {@code fieldErrors} envelope shape but
 * extracts errors from Spring's {@code BindingResult} rather than from this
 * exception. Both paths converge on the same envelope shape.</p>
 *
 * <p><b>Usage example:</b></p>
 * <pre>{@code
 * // COBOL: COACTUPC.cbl phone area code validation against CSLKPCDY.cpy NANPA table
 * if (!nanpaAreaCodes.contains(areaCode)) {
 *     throw new ValidationException(
 *         "Invalid NANPA area code",
 *         List.of(new ValidationException.FieldError(
 *             "phoneNumber.areaCode",
 *             "Area code not in NANPA registry")));
 * }
 * }</pre>
 *
 * @see CardDemoException
 * @see com.awsm2.carddemo.validation.ValidationLookupService
 * @see com.awsm2.carddemo.validation.DateValidationService
 */
public class ValidationException extends CardDemoException {

    /**
     * Serialization version identifier. {@link CardDemoException} extends
     * {@link RuntimeException} which implements {@link java.io.Serializable};
     * declaring this constant suppresses the compiler-generated warning and
     * stabilizes the wire format for any future cross-process propagation.
     * The initial value of {@code 1L} reflects this class's first stable layout.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Default reason code for validation failures. Mirrors the semantic
     * identifier scheme described in {@link CardDemoException#getReasonCode()}
     * and is propagated by {@code GlobalExceptionHandler} into the
     * {@code code} property of the standardized JSON error envelope (AAP
     * &sect;0.3.4).
     *
     * <p>This is NOT a COBOL {@code FILE STATUS} code or
     * {@code WS-VALIDATION-FAIL-REASON} numeric code; those COBOL-specific
     * codes are reserved for typed subclasses
     * ({@link CreditLimitExceededException} for code {@code "102"},
     * {@link ExpiredCardException} for code {@code "103"}, etc.).</p>
     */
    public static final String DEFAULT_REASON_CODE = "VALIDATION";

    /**
     * Per-field validation error detail. Each instance describes exactly one
     * field that failed validation, mirroring the {@code fieldErrors} property
     * of the standardized error envelope defined in AAP &sect;0.3.4.
     *
     * <p>This record is intentionally a minimal {@code (field, message)} pair
     * &mdash; the {@code GlobalExceptionHandler} is responsible for translating
     * these instances into the richer {@code ApiResponse.FieldError} envelope
     * elements (which may additionally carry a {@code code} and
     * {@code rejectedValue}). Keeping the exception-side payload minimal
     * decouples the exception type from the wire envelope and avoids accidental
     * leakage of sensitive {@code rejectedValue} content (PCI-DSS, AAP
     * &sect;0.6.6).</p>
     *
     * <p>{@code FieldError} is a Java 16+ {@code record}, so it is implicitly
     * {@code final} and immutable, and provides canonical
     * {@link Object#equals(Object) equals},
     * {@link Object#hashCode() hashCode}, and
     * {@link Object#toString() toString} implementations derived from its
     * components.</p>
     *
     * @param field   the JSON property path that failed validation
     *                (e.g., {@code "phoneNumber.areaCode"},
     *                {@code "address.state"}, {@code "expirationDate"});
     *                must not be {@code null} in practice though the record
     *                does not enforce it &mdash; callers populate this from the
     *                COBOL field name being edited (e.g.,
     *                {@code ACCT-PHONE-NUM-1} or {@code TRAN-CARD-NUM})
     * @param message a human-readable validation message describing why the
     *                field failed (e.g.,
     *                {@code "Area code not in NANPA registry"})
     */
    public record FieldError(String field, String message) {
    }

    /**
     * Immutable list of per-field errors. Wrapped with
     * {@link Collections#unmodifiableList(List)} at construction time so the
     * exception's state cannot be mutated post-construction. Never
     * {@code null}: {@link Collections#emptyList()} substitutes when no errors
     * are supplied.
     */
    private final List<FieldError> fieldErrors;

    /**
     * Constructs a {@code ValidationException} with a message and the
     * {@linkplain #DEFAULT_REASON_CODE default reason code} ({@code "VALIDATION"}),
     * carrying no per-field errors.
     *
     * <p>Use this constructor for whole-object validation failures or for
     * simple field validation where only a message is meaningful (e.g.,
     * {@code throw new ValidationException("Account ID is required");}).</p>
     *
     * @param message a human-readable description of the validation failure;
     *                propagated to {@link CardDemoException}'s message field
     */
    public ValidationException(String message) {
        super(DEFAULT_REASON_CODE, message);
        this.fieldErrors = Collections.emptyList();
    }

    /**
     * Constructs a {@code ValidationException} with a message and a wrapped
     * cause, using the {@linkplain #DEFAULT_REASON_CODE default reason code}
     * ({@code "VALIDATION"}). No per-field errors are attached.
     *
     * <p>Use this constructor when the validation failure is the result of a
     * lower-level exception (e.g., a date parse failure in
     * {@link com.awsm2.carddemo.validation.DateValidationService}) and the
     * underlying stack trace must be preserved for root-cause analysis in
     * CloudWatch and OpenSearch.</p>
     *
     * @param message a human-readable description of the validation failure
     * @param cause   the underlying exception being wrapped; preserves the
     *                stack trace for root-cause analysis. May be {@code null}.
     */
    public ValidationException(String message, Throwable cause) {
        super(DEFAULT_REASON_CODE, message, cause);
        this.fieldErrors = Collections.emptyList();
    }

    /**
     * Constructs a {@code ValidationException} with an explicit reason code
     * and message, carrying no per-field errors.
     *
     * <p>Use this constructor when a domain-specific reason code (other than
     * the generic {@code "VALIDATION"}) must be surfaced to downstream
     * consumers via the {@code ApiResponse.code} field. The reason code is
     * preserved verbatim per AAP &sect;0.7.2 ("Error codes and condition
     * handling surfaced to downstream consumers must be preserved
     * verbatim").</p>
     *
     * @param reasonCode the explicit reason code to surface in the error
     *                   envelope (e.g., a COBOL
     *                   {@code WS-VALIDATION-FAIL-REASON} value or a custom
     *                   semantic identifier); may be {@code null}, in which
     *                   case {@code GlobalExceptionHandler} substitutes a
     *                   default
     * @param message    a human-readable description of the validation failure
     */
    public ValidationException(String reasonCode, String message) {
        super(reasonCode, message);
        this.fieldErrors = Collections.emptyList();
    }

    /**
     * Constructs a {@code ValidationException} with a message and per-field
     * errors, using the {@linkplain #DEFAULT_REASON_CODE default reason code}
     * ({@code "VALIDATION"}).
     *
     * <p>Use this constructor when one or more individual fields failed
     * validation and the consumer needs structured per-field details (e.g., to
     * highlight specific input fields in a calling UI). The supplied list is
     * wrapped with {@link Collections#unmodifiableList(List)} so the
     * exception's state cannot be mutated after construction. A {@code null}
     * argument is normalized to {@link Collections#emptyList()}.</p>
     *
     * @param message     a human-readable description of the validation
     *                    failure
     * @param fieldErrors per-field error details to attach; {@code null} is
     *                    treated as an empty list (never propagated as
     *                    {@code null} on the accessor)
     */
    public ValidationException(String message, List<FieldError> fieldErrors) {
        super(DEFAULT_REASON_CODE, message);
        this.fieldErrors = (fieldErrors != null)
            ? Collections.unmodifiableList(fieldErrors)
            : Collections.emptyList();
    }

    /**
     * Constructs a {@code ValidationException} with an explicit reason code,
     * a message, and per-field errors. This is the most expressive
     * constructor.
     *
     * <p>Use this constructor when a domain-specific reason code AND
     * structured per-field details must be conveyed. The supplied list is
     * wrapped with {@link Collections#unmodifiableList(List)} so the
     * exception's state cannot be mutated after construction. A {@code null}
     * argument is normalized to {@link Collections#emptyList()}.</p>
     *
     * @param reasonCode  the explicit reason code to surface in the error
     *                    envelope; may be {@code null}
     * @param message     a human-readable description of the validation
     *                    failure
     * @param fieldErrors per-field error details to attach; {@code null} is
     *                    treated as an empty list
     */
    public ValidationException(String reasonCode, String message, List<FieldError> fieldErrors) {
        super(reasonCode, message);
        this.fieldErrors = (fieldErrors != null)
            ? Collections.unmodifiableList(fieldErrors)
            : Collections.emptyList();
    }

    /**
     * Returns the per-field validation error details attached to this
     * exception, or an empty list if none were supplied.
     *
     * <p>The returned list is unmodifiable: any attempt to call
     * {@link List#add(Object)}, {@link List#remove(Object)},
     * {@link List#clear()}, or any other mutator on the returned list throws
     * {@link UnsupportedOperationException}. Callers requiring a mutable copy
     * must allocate one explicitly (e.g.,
     * {@code new ArrayList<>(exception.getFieldErrors())}).</p>
     *
     * <p>This accessor is guaranteed to never return {@code null}, simplifying
     * defensive coding in {@code GlobalExceptionHandler} and any other
     * consumer that iterates over the field errors.</p>
     *
     * @return an unmodifiable {@link List} of {@link FieldError} entries
     *         (possibly empty, never {@code null})
     */
    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
