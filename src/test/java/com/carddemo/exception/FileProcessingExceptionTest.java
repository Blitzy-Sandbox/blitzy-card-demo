package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link FileProcessingException}, the typed exception that maps
 * unrecoverable batch file-I/O failures — the legacy {@code 9999-ABEND-PROGRAM}
 * / {@code 9910-DISPLAY-IO-STATUS} paths of the posting and print programs
 * (for example {@code CBTRN02C}) — to an HTTP 500 at the REST boundary (source
 * commit {@code 27d6c6f}, AAP &sect;0.8.3). It optionally records the abend
 * code, the offending file (culprit), and the originating {@link FileStatusCode}.
 *
 * <p>These tests pin the fixed HTTP status
 * ({@link HttpStatus#INTERNAL_SERVER_ERROR}), the nullable
 * {@code abendCode}/{@code culprit} accessors, the optional
 * {@link FileStatusCode}, and every constructor variant including the full
 * ABEND constructor. They contribute to the CP2 test-coverage gate (Gate 8,
 * JaCoCo &ge; 80%).</p>
 */
@DisplayName("FileProcessingException — HTTP 500 batch-abend mapping")
class FileProcessingExceptionTest {

    @Test
    @DisplayName("message-only constructor: HTTP 500 with null file-status and abend details")
    void messageOnly() {
        FileProcessingException ex = new FileProcessingException("file read failed");

        assertThat(ex).isInstanceOf(CardDemoException.class);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ex.getMessage()).isEqualTo("file read failed");
        assertThat(ex.getFileStatusCode()).isNull();
        assertThat(ex.getAbendCode()).isNull();
        assertThat(ex.getCulprit()).isNull();
    }

    @Test
    @DisplayName("message + cause constructor preserves the cause and the 500 mapping")
    void messageAndCause() {
        IOException cause = new IOException("disk error");

        FileProcessingException ex = new FileProcessingException("file read failed", cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ex.getAbendCode()).isNull();
        assertThat(ex.getCulprit()).isNull();
    }

    @Nested
    @DisplayName("file-status constructors")
    class FileStatusConstructors {

        @Test
        @DisplayName("file-status constructor preserves the legacy FILE STATUS code")
        void withFileStatus() {
            FileProcessingException ex =
                    new FileProcessingException("dataset missing", FileStatusCode.RECORD_NOT_FOUND);

            assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.RECORD_NOT_FOUND);
            assertThat(ex.getAbendCode()).isNull();
            assertThat(ex.getCulprit()).isNull();
        }

        @Test
        @DisplayName("file-status + cause constructor preserves both")
        void withFileStatusAndCause() {
            IOException cause = new IOException("disk error");

            FileProcessingException ex =
                    new FileProcessingException("dup key", FileStatusCode.DUPLICATE_KEY, cause);

            assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
            assertThat(ex.getCause()).isSameAs(cause);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("full ABEND constructor (9999-ABEND-PROGRAM parity)")
    class FullAbendConstructor {

        @Test
        @DisplayName("records the abend code, culprit, file status, and cause")
        void allFields() {
            IOException cause = new IOException("disk error");

            FileProcessingException ex = new FileProcessingException(
                    "abnormal termination", "S0C7", "CBTRN02C", FileStatusCode.RECORD_NOT_FOUND, cause);

            assertThat(ex.getMessage()).isEqualTo("abnormal termination");
            assertThat(ex.getAbendCode()).isEqualTo("S0C7");
            assertThat(ex.getCulprit()).isEqualTo("CBTRN02C");
            assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.RECORD_NOT_FOUND);
            assertThat(ex.getCause()).isSameAs(cause);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
