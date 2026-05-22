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
package com.awsm2.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Standardized JSON response envelope for all REST endpoints.
 *
 * <p>Replaces the COBOL paradigm of returning multiple discrete output
 * fields on a BMS map (e.g., {@code ERRMSGO}, {@code INFOMSGO},
 * field-level highlighting attributes). In the Java target, every
 * endpoint returns this envelope &mdash; whether for success or error.
 *
 * <p>Per AAP &sect;0.3.4 the envelope shape is:
 * <pre>{@code
 *   {
 *     "code": "OK" | "VALIDATION_ERROR" | "NOT_FOUND" | ...,
 *     "message": "human-readable summary",
 *     "data": { ... } | null,
 *     "fieldErrors": [ { "field": "...", "code": "...", "message": "..." } ] | null,
 *     "correlationId": "uuid",
 *     "timestamp": "2026-05-20T14:30:45.123Z"
 *   }
 * }</pre>
 *
 * <p>Per AAP &sect;0.7.1, the {@code code} field preserves COBOL
 * {@code RETURN-CODE}/condition-code semantics &mdash; error codes surfaced
 * to downstream consumers must remain identical to source error codes
 * where applicable.
 *
 * <p><b>COBOL Provenance:</b>
 * <ul>
 *   <li>Conceptually replaces BMS error-message fields ({@code ERRMSGO},
 *       red-highlight attributes) across all 17 mapsets in {@code app/bms/}</li>
 *   <li>Pairs with {@code CardDemoException.reasonCode} for end-to-end
 *       traceability (see {@code com.awsm2.carddemo.exception.CardDemoException})</li>
 *   <li>Consumed by {@code com.awsm2.carddemo.exception.GlobalExceptionHandler}
 *       to translate every {@code CardDemoException} subclass into a
 *       standardized HTTP response (400, 404, 409, 422, 500)</li>
 * </ul>
 *
 * <p><b>PCI-DSS guidance (AAP &sect;0.6.6):</b> consumers of {@link FieldError}
 * MUST use {@link FieldError#of(String, String, String)} (the three-argument
 * overload) when surfacing per-field errors for sensitive fields such as
 * {@code password}, {@code cardNumber}, {@code cvv}, or {@code ssn} so that
 * the rejected value does not leak into the response body.
 *
 * <p>This record is immutable; instances may only be created through the
 * canonical constructor or the static factory methods (success,
 * error, errorWithFieldErrors). Jackson serializes the record using the
 * accessor names (which equal the component names), and
 * {@code @JsonInclude(NON_NULL)} suppresses any null-valued components so
 * a successful response omits the {@code fieldErrors} key entirely.
 *
 * @param <T> the type of the {@code data} payload (e.g., {@code AccountViewDto},
 *            {@code TransactionDetailDto}, {@code MainMenuDto}, etc.)
 * @param code           result/error code (see field-level javadoc)
 * @param message        human-readable message
 * @param data           payload (null on error responses)
 * @param fieldErrors    per-field validation errors (null when no validation
 *                       errors)
 * @param correlationId  distributed-tracing correlation identifier
 * @param timestamp      server-side response timestamp (UTC, ISO-8601)
 *
 * @see com.awsm2.carddemo.exception.CardDemoException
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ApiResponse", description = "Standardized response envelope for all endpoints")
public record ApiResponse<T>(

        @Schema(description = "Result/error code. For successful responses, typically \"OK\". "
                + "For error responses, mirrors the COBOL RETURN-CODE / condition code "
                + "(e.g., \"VALIDATION_ERROR\", \"RECORD_NOT_FOUND\", \"DUPLICATE_RECORD\", "
                + "\"CONCURRENT_MODIFICATION\", \"CREDIT_LIMIT_EXCEEDED\", \"EXPIRED_CARD\", "
                + "\"INTERNAL_ERROR\").",
                example = "OK")
        @JsonProperty("code")
        String code,

        @Schema(description = "Human-readable message. For success, may be \"Success\" or a "
                + "brief summary; for error, the typed exception's message.",
                example = "Success")
        @JsonProperty("message")
        String message,

        @Schema(description = "Payload of the response. Null on error responses or "
                + "empty-success operations.",
                nullable = true)
        @JsonProperty("data")
        T data,

        @Schema(description = "Per-field validation errors. Null when code is not "
                + "\"VALIDATION_ERROR\". Sourced from Spring's BindingResult and "
                + "Jakarta Bean Validation results in GlobalExceptionHandler.",
                nullable = true)
        @JsonProperty("fieldErrors")
        List<FieldError> fieldErrors,

        @Schema(description = "Correlation identifier for distributed tracing. Echoes the "
                + "X-Correlation-Id request header if present; otherwise generated "
                + "server-side. Used to correlate CloudWatch logs, OpenSearch entries, "
                + "and CloudTrail events (AAP \u00a70.6.6).",
                example = "b3a4f9e8-1c2d-4e5f-8a7b-9c0d1e2f3a4b",
                nullable = true)
        @JsonProperty("correlationId")
        String correlationId,

        @Schema(description = "Server-side response timestamp (UTC, ISO-8601).",
                example = "2026-05-20T14:30:45.123Z",
                format = "date-time")
        @JsonProperty("timestamp")
        Instant timestamp
) {

    // =====================================================================
    // Factory methods for SUCCESS responses
    // =====================================================================

    /**
     * Wraps a successful payload in the envelope. The resulting envelope has
     * {@code code = "OK"}, {@code message = "Success"}, no field errors, and no
     * correlation identifier. The {@code timestamp} is stamped at invocation
     * time using {@link Instant#now()}.
     *
     * @param <T>  the payload type
     * @param data the response payload; may be {@code null} for empty-success
     *             operations (e.g., a successful DELETE)
     * @return a fully populated success envelope
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "Success", data, null, null, Instant.now());
    }

    /**
     * Wraps a successful payload with an explicit summary message. Use this
     * overload when the controller needs to surface a contextual success
     * message (e.g., "Account created", "Transaction posted").
     *
     * @param <T>     the payload type
     * @param data    the response payload
     * @param message the human-readable success message; must not be
     *                {@code null}
     * @return a populated success envelope with the given message
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>("OK", message, data, null, null, Instant.now());
    }

    /**
     * Wraps a successful payload with an explicit message and correlation
     * identifier. The correlation identifier should match the value of the
     * {@code X-Correlation-Id} request header when present, or be generated
     * server-side otherwise; downstream consumers use the value to stitch
     * together log entries across CloudWatch, OpenSearch, and CloudTrail
     * (AAP &sect;0.6.6).
     *
     * @param <T>           the payload type
     * @param data          the response payload
     * @param message       the human-readable success message
     * @param correlationId the distributed-tracing correlation identifier;
     *                      may be {@code null}
     * @return a populated success envelope with the given message and
     *         correlationId
     */
    public static <T> ApiResponse<T> success(T data, String message, String correlationId) {
        return new ApiResponse<>("OK", message, data, null, correlationId, Instant.now());
    }

    // =====================================================================
    // Factory methods for ERROR responses
    // =====================================================================

    /**
     * Builds an error envelope. Used by
     * {@code com.awsm2.carddemo.exception.GlobalExceptionHandler}.
     *
     * <p>Per AAP &sect;0.7.1, the {@code code} parameter must preserve the COBOL
     * {@code RETURN-CODE} / {@code FILE STATUS} / {@code WS-VALIDATION-FAIL-REASON}
     * value where applicable (e.g., {@code "22"} for DUPKEY, {@code "23"} for
     * NOTFND, {@code "102"} for credit-limit exceeded, {@code "103"} for card
     * expired).
     *
     * @param <T>           the payload type (always {@code null} on error, but
     *                      the generic preserves the signature symmetry with
     *                      success(T) so controllers can declare
     *                      {@code ResponseEntity<ApiResponse<MyDto>>} and use
     *                      a single return type in both branches)
     * @param code          COBOL-aligned error code (e.g., "RECORD_NOT_FOUND")
     * @param message       human-readable message
     * @param correlationId distributed-tracing identifier (may be {@code null})
     * @return error envelope with {@code data} and {@code fieldErrors} set to
     *         {@code null}
     */
    public static <T> ApiResponse<T> error(String code, String message, String correlationId) {
        return new ApiResponse<>(code, message, null, null, correlationId, Instant.now());
    }

    /**
     * Builds an error envelope with field-level validation errors. Used by
     * {@code GlobalExceptionHandler} when handling
     * {@code MethodArgumentNotValidException} (from
     * {@code @Valid @RequestBody} controller arguments) and
     * {@code ConstraintViolationException} (from
     * {@code @Validated} method-parameter constraints).
     *
     * @param <T>           the payload type (always {@code null} on error)
     * @param code          error code (typically {@code "VALIDATION_ERROR"})
     * @param message       human-readable summary
     * @param fieldErrors   list of per-field errors; may be {@code null} but
     *                      callers should normally pass a non-null list when
     *                      using this overload &mdash; if no field errors are
     *                      available, use {@link #error(String, String, String)}
     *                      instead
     * @param correlationId distributed-tracing identifier (may be {@code null})
     * @return error envelope with {@code data} null and {@code fieldErrors} set
     *         to the supplied list
     */
    public static <T> ApiResponse<T> errorWithFieldErrors(
            String code,
            String message,
            List<FieldError> fieldErrors,
            String correlationId) {
        return new ApiResponse<>(code, message, null, fieldErrors, correlationId, Instant.now());
    }

    // =====================================================================
    // Nested FieldError record
    // =====================================================================

    /**
     * Per-field validation error.
     *
     * <p>Populated by {@code GlobalExceptionHandler} from Jakarta Bean
     * Validation {@code jakarta.validation.ConstraintViolation} instances
     * (e.g., from {@code ConstraintViolationException.getConstraintViolations()})
     * or from Spring's {@code org.springframework.validation.FieldError}
     * (e.g., from
     * {@code MethodArgumentNotValidException.getBindingResult().getFieldErrors()}).
     *
     * <p>The {@code rejectedValue} component is typed as {@link Object} because
     * Spring's validation framework may pass numeric, boolean, temporal, or
     * complex values to validation handlers; Jackson serializes any of these
     * via the default {@code ObjectMapper}. Consumers MUST NOT populate
     * {@code rejectedValue} for sensitive fields ({@code password},
     * {@code cardNumber}, {@code cvv}, {@code ssn}) &mdash; use
     * {@link #of(String, String, String)} (the three-argument factory) in
     * those cases to avoid leaking the value into the response body
     * (AAP &sect;0.6.6 PCI-DSS guidance).
     *
     * <p>Like {@link ApiResponse}, this record honors {@code @JsonInclude(NON_NULL)}
     * through the outer record annotation when serialized as part of an
     * {@code ApiResponse}; consumers can also annotate {@code FieldError}
     * directly if used outside that context.
     *
     * @param field         JSON field path (e.g., {@code "accountId"},
     *                      {@code "merchant.zip"})
     * @param code          constraint code (e.g., {@code "NotBlank"},
     *                      {@code "Pattern"}, {@code "Size"})
     * @param message       human-readable explanation
     * @param rejectedValue the rejected value; {@code null} for sensitive
     *                      fields
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "FieldError", description = "Per-field validation error")
    public record FieldError(

            @Schema(description = "JSON field path (e.g., 'accountId', 'merchant.zip').",
                    example = "accountId")
            @JsonProperty("field")
            String field,

            @Schema(description = "Constraint code (e.g., 'NotBlank', 'Pattern', 'Size').",
                    example = "Pattern")
            @JsonProperty("code")
            String code,

            @Schema(description = "Human-readable explanation.",
                    example = "Account ID must be exactly 11 digits")
            @JsonProperty("message")
            String message,

            @Schema(description = "The rejected value (omitted for sensitive fields "
                    + "like password, cardNumber, cvv, ssn).",
                    example = "abc",
                    nullable = true)
            @JsonProperty("rejectedValue")
            Object rejectedValue
    ) {

        /**
         * Convenience factory without {@code rejectedValue}.
         *
         * <p><b>PCI-DSS guidance:</b> use this overload for sensitive fields
         * (e.g., {@code password}, {@code cardNumber}, {@code cvv},
         * {@code ssn}) to avoid leaking the rejected value into the response
         * body and downstream log indexes (AAP &sect;0.6.6).
         *
         * @param field   the JSON field path
         * @param code    the constraint code
         * @param message the human-readable explanation
         * @return a {@code FieldError} with {@code rejectedValue} set to
         *         {@code null}
         */
        public static FieldError of(String field, String code, String message) {
            return new FieldError(field, code, message, null);
        }

        /**
         * Full-constructor convenience factory for non-sensitive fields.
         *
         * @param field         the JSON field path
         * @param code          the constraint code
         * @param message       the human-readable explanation
         * @param rejectedValue the value that failed validation; pass
         *                      {@code null} (or use
         *                      {@link #of(String, String, String)}) for
         *                      sensitive fields
         * @return a fully populated {@code FieldError}
         */
        public static FieldError of(String field, String code, String message, Object rejectedValue) {
            return new FieldError(field, code, message, rejectedValue);
        }
    }
}
