package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link DateValidationException}, the typed exception that maps
 * the legacy LE {@code CEEDAYS} date-validation failure of {@code CSUTLDTC} to
 * an HTTP 400 at the REST boundary (source commit {@code 27d6c6f}, AAP
 * &sect;0.5.1). It optionally records the offending input value and the expected
 * format for diagnostics.
 *
 * <p>These tests pin the fixed HTTP status ({@link HttpStatus#BAD_REQUEST}), the
 * nullable {@code invalidValue}/{@code expectedFormat} accessors, and every
 * constructor variant. They contribute to the CP2 test-coverage gate (Gate 8,
 * JaCoCo &ge; 80%).</p>
 */
@DisplayName("DateValidationException — HTTP 400 date-validation mapping")
class DateValidationExceptionTest {

    @Test
    @DisplayName("message-only constructor: HTTP 400 with null diagnostic details")
    void messageOnly() {
        DateValidationException ex = new DateValidationException("invalid date");

        assertThat(ex).isInstanceOf(CardDemoException.class);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessage()).isEqualTo("invalid date");
        assertThat(ex.getInvalidValue()).isNull();
        assertThat(ex.getExpectedFormat()).isNull();
    }

    @Test
    @DisplayName("message + cause constructor preserves the cause and null diagnostic details")
    void messageAndCause() {
        DateTimeParseException cause = new DateTimeParseException("bad", "2024-13-40", 5);

        DateValidationException ex = new DateValidationException("invalid date", cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getInvalidValue()).isNull();
        assertThat(ex.getExpectedFormat()).isNull();
    }

    @Nested
    @DisplayName("diagnostic-detail constructors")
    class DiagnosticDetails {

        @Test
        @DisplayName("invalid-value constructor records the offending input; format stays null")
        void withInvalidValue() {
            DateValidationException ex = new DateValidationException("invalid date", "2024-13-40");

            assertThat(ex.getInvalidValue()).isEqualTo("2024-13-40");
            assertThat(ex.getExpectedFormat()).isNull();
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("invalid-value + expected-format constructor records both diagnostic details")
        void withInvalidValueAndFormat() {
            DateValidationException ex =
                    new DateValidationException("invalid date", "2024-13-40", "yyyy-MM-dd");

            assertThat(ex.getInvalidValue()).isEqualTo("2024-13-40");
            assertThat(ex.getExpectedFormat()).isEqualTo("yyyy-MM-dd");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
