package com.carddemo.unit.service.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.DuplicateRecordException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DuplicateRecordException}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * realizes COBOL FILE STATUS {@code '22'} (duplicate-key) handled in the FILE STATUS
 * clauses of {@code app/cbl/CBTRN02C.cbl} (L32-L61). AAP section 0.8.4 binds
 * {@code '22' -> DuplicateRecordException}.</p>
 */
@DisplayName("DuplicateRecordException - FILE STATUS '22' duplicate-key")
class DuplicateRecordExceptionTest {

    @Test
    @DisplayName("FILE_STATUS constant and getFileStatus() are both \"22\"")
    void fileStatus_is22() {
        DuplicateRecordException ex = new DuplicateRecordException("dupe");

        assertThat(DuplicateRecordException.FILE_STATUS).isEqualTo("22");
        assertThat(ex.getFileStatus()).isEqualTo("22");
    }

    @Test
    @DisplayName("(String) constructor preserves the message and has no cause")
    void messageConstructor_preservesMessage() {
        DuplicateRecordException ex = new DuplicateRecordException("dupe");

        assertThat(ex.getMessage()).isEqualTo("dupe");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("(String, Throwable) constructor preserves the message and the cause")
    void messageCauseConstructor_preservesMessageAndCause() {
        Throwable root = new IllegalStateException("io");

        DuplicateRecordException ex = new DuplicateRecordException("dupe", root);

        assertThat(ex.getMessage()).isEqualTo("dupe");
        assertThat(ex.getCause()).isSameAs(root);
    }

    @Test
    @DisplayName("forKey builds \"<entity> already exists for key: <key>\" and keeps FILE STATUS")
    void forKey_buildsCanonicalMessage() {
        DuplicateRecordException ex = DuplicateRecordException.forKey("Transaction", 5L);

        assertThat(ex.getMessage()).isEqualTo("Transaction already exists for key: 5");
        assertThat(ex.getFileStatus()).isEqualTo("22");
    }

    @Test
    @DisplayName("forKey renders a null key as \"null\" (String.valueOf, null-safe)")
    void forKey_nullKey_isNullSafe() {
        DuplicateRecordException ex = DuplicateRecordException.forKey("Transaction", null);

        assertThat(ex.getMessage()).isEqualTo("Transaction already exists for key: null");
    }

    @Test
    @DisplayName("is-a CardDemoException and an unchecked RuntimeException")
    void hierarchy_isCardDemoExceptionAndRuntimeException() {
        DuplicateRecordException ex = new DuplicateRecordException("dupe");

        assertThat(ex).isInstanceOf(CardDemoException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
