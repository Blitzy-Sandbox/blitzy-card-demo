package com.carddemo.unit.service.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.FileAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FileAccessException}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * realizes the file-access failures behind the COBOL FILE STATUS clauses of
 * {@code app/cbl/CBTRN02C.cbl} (L32-L61), carrying an optional two-character FILE STATUS
 * alongside the message (AAP section 0.8.4).</p>
 */
@DisplayName("FileAccessException - file I/O failure carrying an optional FILE STATUS")
class FileAccessExceptionTest {

    @Test
    @DisplayName("(String) constructor leaves fileStatus null and has no cause")
    void messageOnly_leavesFileStatusNull() {
        FileAccessException ex = new FileAccessException("cannot open ACCTFILE");

        assertThat(ex.getMessage()).isEqualTo("cannot open ACCTFILE");
        assertThat(ex.getFileStatus()).isNull();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("(String, Throwable) constructor keeps the cause and leaves fileStatus null")
    void messageCause_leavesFileStatusNull() {
        Throwable root = new IllegalStateException("disk error");

        FileAccessException ex = new FileAccessException("cannot read CARDFILE", root);

        assertThat(ex.getMessage()).isEqualTo("cannot read CARDFILE");
        assertThat(ex.getCause()).isSameAs(root);
        assertThat(ex.getFileStatus()).isNull();
    }

    @Test
    @DisplayName("(String, String fileStatus) constructor stores the FILE STATUS")
    void messageAndFileStatus_storesFileStatus() {
        FileAccessException ex = new FileAccessException("open failed", "35");

        assertThat(ex.getMessage()).isEqualTo("open failed");
        assertThat(ex.getFileStatus()).isEqualTo("35");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("(String, String fileStatus, Throwable) constructor stores FILE STATUS and cause")
    void messageFileStatusCause_storesBoth() {
        Throwable root = new IllegalStateException("disk error");

        FileAccessException ex = new FileAccessException("read failed", "37", root);

        assertThat(ex.getMessage()).isEqualTo("read failed");
        assertThat(ex.getFileStatus()).isEqualTo("37");
        assertThat(ex.getCause()).isSameAs(root);
    }

    @Test
    @DisplayName("a null FILE STATUS must be cast to (String) to disambiguate from (String, Throwable)")
    void nullFileStatus_requiresStringCast() {
        FileAccessException ex = new FileAccessException("no status", (String) null);

        assertThat(ex.getMessage()).isEqualTo("no status");
        assertThat(ex.getFileStatus()).isNull();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("a null cause must be cast to (Throwable) to disambiguate from (String, String)")
    void nullCause_requiresThrowableCast() {
        FileAccessException ex = new FileAccessException("no cause", (Throwable) null);

        assertThat(ex.getMessage()).isEqualTo("no cause");
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getFileStatus()).isNull();
    }

    @Test
    @DisplayName("is-a CardDemoException and an unchecked RuntimeException")
    void hierarchy_isCardDemoExceptionAndRuntimeException() {
        FileAccessException ex = new FileAccessException("io");

        assertThat(ex).isInstanceOf(CardDemoException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
