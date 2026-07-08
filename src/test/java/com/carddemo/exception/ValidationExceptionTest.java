package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link ValidationException}, the typed exception that maps both
 * online field-level validation failures (CICS SEND MAP re-display) and
 * batch-posting reject conditions to an HTTP 400 at the REST boundary (source
 * commit {@code 27d6c6f}, AAP &sect;0.8.3). It optionally carries a
 * {@link RejectReason} (batch reject classification) and an unmodifiable
 * field&rarr;message map (online multi-field errors).
 *
 * <p>These tests pin the fixed HTTP status ({@link HttpStatus#BAD_REQUEST}), the
 * never-null / unmodifiable / defensively-copied {@code fieldErrors} contract,
 * the nullable {@code rejectReason}, and every constructor variant. They
 * contribute to the CP2 test-coverage gate (Gate 8, JaCoCo &ge; 80%).</p>
 */
@DisplayName("ValidationException — HTTP 400 validation / batch-reject mapping")
class ValidationExceptionTest {

    @Test
    @DisplayName("message-only constructor: HTTP 400, no reject reason, empty non-null field errors")
    void messageOnly() {
        ValidationException ex = new ValidationException("invalid input");

        assertThat(ex).isInstanceOf(CardDemoException.class);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessage()).isEqualTo("invalid input");
        assertThat(ex.getRejectReason()).isNull();
        assertThat(ex.getFieldErrors()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("message + cause constructor: HTTP 400 with an empty (non-null) field-error map")
    void messageAndCause() {
        Throwable cause = new NumberFormatException("nfe");

        ValidationException ex = new ValidationException("invalid input", cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getRejectReason()).isNull();
        assertThat(ex.getFieldErrors()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("reject-reason constructor carries the batch classification")
    void withRejectReason() {
        ValidationException ex = new ValidationException("over limit", RejectReason.OVERLIMIT);

        assertThat(ex.getRejectReason()).isEqualTo(RejectReason.OVERLIMIT);
        assertThat(ex.getFieldErrors()).isEmpty();
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Nested
    @DisplayName("field-errors handling")
    class FieldErrors {

        @Test
        @DisplayName("field-errors constructor records the supplied entries")
        void copiesFieldErrors() {
            Map<String, String> source = new HashMap<>();
            source.put("amount", "must be positive");

            ValidationException ex = new ValidationException("invalid input", source);

            assertThat(ex.getFieldErrors()).containsEntry("amount", "must be positive");
        }

        @Test
        @DisplayName("the field-errors map is defensively copied (later source mutation is ignored)")
        void defensiveCopy() {
            Map<String, String> source = new HashMap<>();
            source.put("a", "1");
            ValidationException ex = new ValidationException("invalid input", source);

            source.put("b", "2");

            assertThat(ex.getFieldErrors()).containsOnlyKeys("a");
        }

        @Test
        @DisplayName("the returned field-errors map is unmodifiable")
        void unmodifiable() {
            ValidationException ex = new ValidationException("invalid input", Map.of("a", "1"));

            assertThatThrownBy(() -> ex.getFieldErrors().put("b", "2"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("the full constructor carries both a reject reason and field errors")
        void fullConstructor() {
            ValidationException ex = new ValidationException(
                    "invalid card", RejectReason.INVALID_CARD_NUMBER, Map.of("cardNumber", "unknown"));

            assertThat(ex.getRejectReason()).isEqualTo(RejectReason.INVALID_CARD_NUMBER);
            assertThat(ex.getFieldErrors()).containsEntry("cardNumber", "unknown");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
