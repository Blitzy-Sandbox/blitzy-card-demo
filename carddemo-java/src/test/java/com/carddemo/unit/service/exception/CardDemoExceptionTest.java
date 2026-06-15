package com.carddemo.unit.service.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.exception.CardDemoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CardDemoException}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * the exception hierarchy is the Java realization of the COBOL FILE STATUS and abend
 * handling in {@code app/cbl/CBTRN02C.cbl} (FILE STATUS clauses L32-L61) and the error
 * paths across the online and batch programs. {@code CardDemoException} is the unchecked
 * base type that lets Spring roll back transactions on any failure (AAP sections 0.7
 * and 0.8.4).</p>
 */
@DisplayName("CardDemoException - unchecked base of the CardDemo exception hierarchy")
class CardDemoExceptionTest {

    @Test
    @DisplayName("(String) constructor preserves the message and has no cause")
    void messageConstructor_preservesMessage_andHasNoCause() {
        CardDemoException ex = new CardDemoException("boom");

        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("(String, Throwable) constructor preserves the message and the cause")
    void messageCauseConstructor_preservesMessageAndCause() {
        Throwable root = new IllegalStateException("root");

        CardDemoException ex = new CardDemoException("boom", root);

        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isSameAs(root);
    }

    @Test
    @DisplayName("is an unchecked RuntimeException (Spring rolls back by default)")
    void isUncheckedRuntimeException() {
        CardDemoException ex = new CardDemoException("boom");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
