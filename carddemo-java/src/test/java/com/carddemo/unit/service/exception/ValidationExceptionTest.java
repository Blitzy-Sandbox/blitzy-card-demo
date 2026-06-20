package com.carddemo.unit.service.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link com.carddemo.exception.ValidationException}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * realizes the field-level input validation failures raised across the CardDemo online
 * programs and the {@code app/cbl/CBTRN02C.cbl} validation cascade (L32-L61). This is the
 * CardDemo {@code ValidationException}; it is intentionally distinct from
 * {@code jakarta.validation.ValidationException}, which is not imported so the simple
 * name resolves to {@code com.carddemo.exception.ValidationException} (AAP section
 * 0.8.4).</p>
 */
@DisplayName("ValidationException - field-level validation failure (CardDemo, not Jakarta)")
class ValidationExceptionTest {

    @Test
    @DisplayName("(String) constructor leaves field null and has no cause")
    void messageOnly_leavesFieldNull() {
        ValidationException ex = new ValidationException("invalid input");

        assertThat(ex.getMessage()).isEqualTo("invalid input");
        assertThat(ex.getField()).isNull();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("(String, Throwable) constructor keeps the cause and leaves field null")
    void messageCause_leavesFieldNull() {
        Throwable root = new IllegalStateException("parse error");

        ValidationException ex = new ValidationException("invalid input", root);

        assertThat(ex.getMessage()).isEqualTo("invalid input");
        assertThat(ex.getCause()).isSameAs(root);
        assertThat(ex.getField()).isNull();
    }

    @Test
    @DisplayName("(String, String field) constructor stores the offending field name")
    void messageAndField_storesField() {
        ValidationException ex = new ValidationException("must be numeric", "ACCT-ID");

        assertThat(ex.getMessage()).isEqualTo("must be numeric");
        assertThat(ex.getField()).isEqualTo("ACCT-ID");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("a null field must be cast to (String) to disambiguate from (String, Throwable)")
    void nullField_requiresStringCast() {
        ValidationException ex = new ValidationException("no field", (String) null);

        assertThat(ex.getMessage()).isEqualTo("no field");
        assertThat(ex.getField()).isNull();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("a null cause must be cast to (Throwable) to disambiguate from (String, String)")
    void nullCause_requiresThrowableCast() {
        ValidationException ex = new ValidationException("no cause", (Throwable) null);

        assertThat(ex.getMessage()).isEqualTo("no cause");
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getField()).isNull();
    }

    @Test
    @DisplayName("is-a CardDemoException and an unchecked RuntimeException")
    void hierarchy_isCardDemoExceptionAndRuntimeException() {
        ValidationException ex = new ValidationException("invalid input");

        assertThat(ex).isInstanceOf(CardDemoException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
