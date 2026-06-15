package com.carddemo.unit.service.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.RecordNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecordNotFoundException}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * realizes COBOL FILE STATUS {@code '23'} (record-not-found) handled in
 * {@code app/cbl/CBTRN02C.cbl} (FILE STATUS clauses L32-L61). AAP section 0.8.4 binds
 * {@code '23' -> RecordNotFoundException}; FILE STATUS {@code '00'} is success and is
 * not an exception.</p>
 */
@DisplayName("RecordNotFoundException - FILE STATUS '23' record-not-found")
class RecordNotFoundExceptionTest {

    @Test
    @DisplayName("FILE_STATUS constant and getFileStatus() are both \"23\"")
    void fileStatus_is23() {
        RecordNotFoundException ex = new RecordNotFoundException("missing");

        assertThat(RecordNotFoundException.FILE_STATUS).isEqualTo("23");
        assertThat(ex.getFileStatus()).isEqualTo("23");
    }

    @Test
    @DisplayName("(String) constructor preserves the message and has no cause")
    void messageConstructor_preservesMessage() {
        RecordNotFoundException ex = new RecordNotFoundException("missing");

        assertThat(ex.getMessage()).isEqualTo("missing");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("(String, Throwable) constructor preserves the message and the cause")
    void messageCauseConstructor_preservesMessageAndCause() {
        Throwable root = new IllegalStateException("io");

        RecordNotFoundException ex = new RecordNotFoundException("missing", root);

        assertThat(ex.getMessage()).isEqualTo("missing");
        assertThat(ex.getCause()).isSameAs(root);
    }

    @Test
    @DisplayName("forKey builds \"<entity> not found for key: <key>\" and keeps FILE STATUS")
    void forKey_buildsCanonicalMessage() {
        RecordNotFoundException ex = RecordNotFoundException.forKey("Account", 123L);

        assertThat(ex.getMessage()).isEqualTo("Account not found for key: 123");
        assertThat(ex.getFileStatus()).isEqualTo("23");
    }

    @Test
    @DisplayName("forKey renders a null key as \"null\" (String.valueOf, null-safe)")
    void forKey_nullKey_isNullSafe() {
        RecordNotFoundException ex = RecordNotFoundException.forKey("Account", null);

        assertThat(ex.getMessage()).isEqualTo("Account not found for key: null");
    }

    @Test
    @DisplayName("is-a CardDemoException and an unchecked RuntimeException")
    void hierarchy_isCardDemoExceptionAndRuntimeException() {
        RecordNotFoundException ex = new RecordNotFoundException("missing");

        assertThat(ex).isInstanceOf(CardDemoException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
