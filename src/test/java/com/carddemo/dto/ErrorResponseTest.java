package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Framework-free unit test for {@link ErrorResponse} — the standardized JSON
 * error body emitted by the global {@code @RestControllerAdvice} exception
 * handler and by {@code SecurityConfig}.
 *
 * <p>{@code ErrorResponse} is the modern replacement for the legacy CardDemo
 * on-screen error plumbing captured by the COBOL message/abend copybooks
 * {@code CSMSG01Y} ({@code CCDA-COMMON-MESSAGES}) and {@code CSMSG02Y}
 * ({@code ABEND-DATA}: code / culprit / reason / message), referenced by source
 * SHA {@code 27d6c6f} and not copied into this repository. These tests pin the
 * <em>wire contract</em> so downstream consumers can rely on a stable payload:
 * the exact top-level key set, the nested {@code FieldError} key set, ISO-8601
 * timestamp serialization, correlation-id round-tripping, leak-free rejected
 * values, and the empty-vs-null {@code fieldErrors} behaviour.</p>
 *
 * <p>The tests use JUnit&nbsp;5 and AssertJ only. They load no Spring context and
 * no Testcontainers; JSON is produced and inspected through the shared,
 * production-mirroring {@link DtoTestSupport#OBJECT_MAPPER} so assertions match
 * exactly what the running application emits.</p>
 */
@DisplayName("ErrorResponse — standardized JSON error body")
class ErrorResponseTest {

    /**
     * A fixed, UTC, zero-nanosecond timestamp. Chosen so a serialize/deserialize
     * cycle yields an {@link OffsetDateTime} that is {@code equals()} to the
     * original (the shared mapper's context time-zone is UTC, so the {@code Z}
     * offset is preserved), keeping the round-trip equality assertions
     * deterministic.
     */
    private static final OffsetDateTime SAMPLE_TIMESTAMP =
            OffsetDateTime.of(2024, 1, 31, 12, 34, 56, 0, ZoneOffset.UTC);

    /**
     * Regex describing an ISO-8601 offset date-time such as
     * {@code 2024-01-31T12:34:56Z} or {@code 2024-01-31T12:34:56.123+02:00}.
     * A leading epoch number or a {@code [y,M,d,...]} array would not match.
     */
    private static final String ISO_8601_OFFSET =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$";

    /** A representative, fully populated instance (every component non-null). */
    private static ErrorResponse fullyPopulated() {
        List<ErrorResponse.FieldError> fieldErrors =
                List.of(ErrorResponse.FieldError.of("amount", "12.34", "must be positive"));
        return new ErrorResponse(
                SAMPLE_TIMESTAMP,
                409,
                "Conflict",
                "OPTIMISTIC_LOCK",
                "Account was modified by another process",
                "/api/accounts/00000000001",
                "corr-abc-123",
                fieldErrors);
    }

    /** Extracts the top-level object keys of a JSON document, in encounter order. */
    private static List<String> topLevelKeys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    // ---------------------------------------------------------------------
    // Phase 1 — JSON shape & round-trip
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("A fully populated ErrorResponse round-trips through JSON to an equal value")
    void fullyPopulatedErrorResponse_roundTripsToEqualValue() {
        ErrorResponse original = fullyPopulated();

        ErrorResponse roundTripped = DtoTestSupport.roundTrip(original, ErrorResponse.class);

        assertThat(roundTripped).isEqualTo(original);
        // Guard the individual components too, so a future record change that keeps
        // equals() but drops a component from the wire is still caught elsewhere.
        assertThat(roundTripped.timestamp()).isEqualTo(SAMPLE_TIMESTAMP);
        assertThat(roundTripped.status()).isEqualTo(409);
        assertThat(roundTripped.fieldErrors()).containsExactlyElementsOf(original.fieldErrors());
    }

    @Test
    @DisplayName("Serialized JSON exposes exactly the eight documented top-level keys")
    void serializedJson_hasExactlyTheEightTopLevelKeys() throws JsonProcessingException {
        String json = DtoTestSupport.toJson(fullyPopulated());

        JsonNode node = DtoTestSupport.OBJECT_MAPPER.readTree(json);

        assertThat(topLevelKeys(node)).containsExactlyInAnyOrder(
                "timestamp", "status", "error", "code", "message", "path", "correlationId", "fieldErrors");
    }

    @Test
    @DisplayName("Nested FieldError serializes with keys field, rejectedValueSummary, message")
    void nestedFieldError_serializesWithExpectedKeys() throws JsonProcessingException {
        ErrorResponse.FieldError fieldError =
                ErrorResponse.FieldError.of("amount", "12.34", "must be positive");

        JsonNode node = DtoTestSupport.OBJECT_MAPPER.readTree(DtoTestSupport.toJson(fieldError));

        assertThat(topLevelKeys(node))
                .containsExactlyInAnyOrder("field", "rejectedValueSummary", "message");
        // And the same nested shape when embedded inside an ErrorResponse payload.
        JsonNode embedded =
                DtoTestSupport.OBJECT_MAPPER.readTree(DtoTestSupport.toJson(fullyPopulated()));
        JsonNode firstFieldError = embedded.get("fieldErrors").get(0);
        assertThat(topLevelKeys(firstFieldError))
                .containsExactlyInAnyOrder("field", "rejectedValueSummary", "message");
    }

    // ---------------------------------------------------------------------
    // Phase 2 — timestamp serialization
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("timestamp serializes as an ISO-8601 string, never an epoch number or array")
    void timestamp_serializesAsIso8601String() throws JsonProcessingException {
        JsonNode node =
                DtoTestSupport.OBJECT_MAPPER.readTree(DtoTestSupport.toJson(fullyPopulated()));
        JsonNode timestamp = node.get("timestamp");

        assertThat(node.has("timestamp")).as("timestamp key must be present").isTrue();
        assertThat(timestamp.isTextual())
                .as("timestamp must serialize as a JSON string (was %s)", timestamp.getNodeType())
                .isTrue();
        assertThat(timestamp.isNumber())
                .as("timestamp must not serialize as an epoch number")
                .isFalse();
        assertThat(timestamp.isArray())
                .as("timestamp must not serialize as a [y,M,d,...] array")
                .isFalse();
        assertThat(timestamp.asText())
                .as("timestamp text must be an ISO-8601 offset date-time")
                .matches(ISO_8601_OFFSET)
                .isEqualTo("2024-01-31T12:34:56Z");
    }

    // ---------------------------------------------------------------------
    // Phase 3 — correlationId
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("correlationId (the MDC key) round-trips unchanged")
    void correlationId_roundTripsUnchanged() throws JsonProcessingException {
        String correlationId = "8f14e45f-ceea-467a-9e1b-2b7f6c0a1234";
        ErrorResponse original = new ErrorResponse(
                SAMPLE_TIMESTAMP, 500, "Internal Server Error", "ABEND",
                "Unexpected failure", "/api/reports", correlationId, List.of());

        ErrorResponse roundTripped = DtoTestSupport.roundTrip(original, ErrorResponse.class);

        assertThat(roundTripped.correlationId()).isEqualTo(correlationId);
        JsonNode node = DtoTestSupport.OBJECT_MAPPER.readTree(DtoTestSupport.toJson(original));
        assertThat(node.get("correlationId").asText()).isEqualTo(correlationId);
    }

    // ---------------------------------------------------------------------
    // Phase 4 — sensitive-data safety of FieldError.rejectedValueSummary
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("rejectedValueSummary for a password field never echoes the raw secret")
    void rejectedValueSummary_forPasswordField_doesNotEchoRawSecret() {
        String rawPassword = "P@ssw0rd-SuperSecret!";

        ErrorResponse.FieldError fieldError =
                ErrorResponse.FieldError.of("password", rawPassword, "size must be between 8 and 64");

        assertThat(fieldError.rejectedValueSummary())
                .as("password value must be redacted, not echoed")
                .isNotNull()
                .isNotEqualTo(rawPassword)
                .doesNotContain(rawPassword)
                .isEqualTo("***");
    }

    @Test
    @DisplayName("rejectedValueSummary for a card field is fully redacted (no PAN digit leaks)")
    void rejectedValueSummary_forCardField_isFullyRedacted() {
        String rawPan = "4111111111111111";

        ErrorResponse.FieldError fieldError =
                ErrorResponse.FieldError.of("cardNumber", rawPan, "invalid card number");
        String summary = fieldError.rejectedValueSummary();

        assertThat(summary)
                .as("a card-named field must be fully redacted")
                .isEqualTo("***")
                .doesNotContain(rawPan)
                .matches("[^0-9]*"); // not a single cleartext digit survives
    }

    @Test
    @DisplayName("rejectedValueSummary for a PAN-like value in a neutral field is masked to last 4")
    void rejectedValueSummary_forNeutralPanLikeField_isMaskedToLast4() {
        String rawPan = "4111111111111111";

        ErrorResponse.FieldError fieldError =
                ErrorResponse.FieldError.of("referenceNumber", rawPan, "must be 11 digits");
        String summary = fieldError.rejectedValueSummary();

        assertThat(summary)
                .as("a long numeric value must be masked, never echoed in full")
                .doesNotContain(rawPan);
        // Only the last four digits may remain visible; the prefix carries no digits.
        DtoTestSupport.assertPanMaskedLast4(summary, "1111");
    }

    // ---------------------------------------------------------------------
    // Phase 5 — empty / null fieldErrors
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("of() factory yields empty fieldErrors ([]) and omits the null code/correlationId keys")
    void ofFactory_emptyFieldErrors_serializesAsEmptyArray_andOmitsNullKeys()
            throws JsonProcessingException {
        ErrorResponse er =
                ErrorResponse.of(404, "Not Found", "Account not found", "/api/accounts/00000000001");

        assertThat(er.fieldErrors()).as("non-validation errors carry an empty list").isNotNull().isEmpty();

        JsonNode node = DtoTestSupport.OBJECT_MAPPER.readTree(DtoTestSupport.toJson(er));
        // fieldErrors is always serialized (never omitted), as an empty array.
        assertThat(node.has("fieldErrors")).isTrue();
        assertThat(node.get("fieldErrors").isArray()).isTrue();
        assertThat(node.get("fieldErrors").size()).isZero();
        // @JsonInclude(NON_NULL): null components disappear from the payload.
        assertThat(node.has("code")).as("null code must be omitted").isFalse();
        assertThat(node.has("correlationId")).as("null correlationId must be omitted").isFalse();
        // Non-null components remain present.
        assertThat(node.has("timestamp")).isTrue();
        assertThat(node.has("status")).isTrue();
        assertThat(node.has("error")).isTrue();
        assertThat(node.has("message")).isTrue();
        assertThat(node.has("path")).isTrue();

        assertThat(DtoTestSupport.roundTrip(er, ErrorResponse.class)).isEqualTo(er);
    }

    @Test
    @DisplayName("null fieldErrors is coerced to an empty, unmodifiable list")
    void nullFieldErrors_isCoercedToEmptyImmutableList() throws JsonProcessingException {
        ErrorResponse er = new ErrorResponse(
                SAMPLE_TIMESTAMP, 500, "Internal Server Error", null, "Boom", "/x", null, null);

        assertThat(er.fieldErrors()).as("null must become an empty list, never null").isNotNull().isEmpty();
        assertThatThrownBy(() -> er.fieldErrors().add(ErrorResponse.FieldError.of("f", "v", "m")))
                .as("the exposed list must be unmodifiable")
                .isInstanceOf(UnsupportedOperationException.class);

        JsonNode node = DtoTestSupport.OBJECT_MAPPER.readTree(DtoTestSupport.toJson(er));
        assertThat(node.has("fieldErrors")).isTrue();
        assertThat(node.get("fieldErrors").isArray()).isTrue();
        assertThat(node.get("fieldErrors").size()).isZero();
    }

    @Test
    @DisplayName("ofValidation() sets the VALIDATION_ERROR code and keeps redacted field errors")
    void ofValidation_setsValidationErrorCode() {
        List<ErrorResponse.FieldError> fieldErrors = List.of(
                ErrorResponse.FieldError.of("amount", "-5.00", "must be positive"),
                ErrorResponse.FieldError.of("password", "short", "size must be between 8 and 64"));

        ErrorResponse er = ErrorResponse.ofValidation(
                400, "Bad Request", "Validation failed", "/api/transactions", fieldErrors);

        assertThat(er.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(er.status()).isEqualTo(400);
        assertThat(er.fieldErrors()).hasSize(2);
        ErrorResponse.FieldError passwordError = er.fieldErrors().stream()
                .filter(fe -> "password".equals(fe.field()))
                .findFirst()
                .orElseThrow();
        assertThat(passwordError.rejectedValueSummary())
                .as("even inside a validation payload the password stays redacted")
                .isEqualTo("***")
                .isNotEqualTo("short");
    }
}
