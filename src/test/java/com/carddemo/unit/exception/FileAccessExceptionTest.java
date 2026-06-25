package com.carddemo.unit.exception;

import com.carddemo.exception.FileAccessException;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit test for {@link FileAccessException}, the Java realization of the
 * legacy COBOL VSAM {@code FILE STATUS 9x} (implementation-defined logic / I/O error)
 * family. In the reference batch programs the {@code 9x} branch of a file-status check
 * (for example the account-master open/read errors in {@code CBACT01C}, and the
 * posting-engine file errors across the six datasets in {@code CBTRN02C}) routes through
 * {@code 9910-DISPLAY-IO-STATUS} and {@code 9999-ABEND-PROGRAM}; that abnormal-termination
 * condition is precisely what this exception models. {@code GlobalExceptionHandler} maps it
 * to HTTP 500 with a generic body, so the diagnostic {@code operation}/{@code fileStatus}
 * fields are server-side only and are never surfaced to remote callers.
 *
 * <p>Naming note: the system under test is deliberately <strong>not</strong> named
 * {@code DataAccessException}. That avoids a direct clash with Spring's
 * {@code org.springframework.dao.DataAccessException}; consequently this test never imports
 * the Spring type. The suite is intentionally framework-free &mdash; JUnit 5 plus AssertJ
 * only, with no Mockito, no Spring context, and no database, AWS, or network access &mdash;
 * and proves only the POJO contract and the {@code serialVersionUID}. The HTTP-500 /
 * generic-body mapping is asserted separately in {@code GlobalExceptionHandlerTest}.
 */
@DisplayName("FileAccessException — POJO contract and serialVersionUID")
class FileAccessExceptionTest {

    @Test
    @DisplayName("message-only constructor sets the message and leaves cause/operation/fileStatus null")
    void messageOnlyConstructor() {
        FileAccessException exception = new FileAccessException("io failure");

        assertThat(exception.getMessage()).isEqualTo("io failure");
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getOperation()).isNull();
        assertThat(exception.getFileStatus()).isNull();
    }

    @Test
    @DisplayName("message-and-cause constructor preserves the message and the originating cause")
    void messageAndCauseConstructor() {
        Throwable cause = new java.io.IOException("disk");

        FileAccessException exception = new FileAccessException("read failed", cause);

        assertThat(exception.getMessage()).isEqualTo("read failed");
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getOperation()).isNull();
        assertThat(exception.getFileStatus()).isNull();
    }

    @Test
    @DisplayName("operation/status/cause constructor builds the deterministic message and stores both fields")
    void operationStatusCauseConstructorBuildsMessageAndSetsFields() {
        Throwable cause = new RuntimeException("vsam");

        FileAccessException exception = new FileAccessException("READ", "92", cause);

        assertThat(exception.getOperation()).isEqualTo("READ");
        assertThat(exception.getFileStatus()).isEqualTo("92");
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getMessage())
                .isEqualTo("Data access error during READ (status 92)");
    }

    @Test
    @DisplayName("serialVersionUID is a private static final long equal to 1L")
    void serialVersionUidIsPrivateStaticFinalLongOne() throws Exception {
        Field field = FileAccessException.class.getDeclaredField("serialVersionUID");
        field.setAccessible(true);

        int modifiers = field.getModifiers();
        assertThat(field.getType()).isEqualTo(long.class);
        assertThat(Modifier.isPrivate(modifiers)).isTrue();
        assertThat(Modifier.isStatic(modifiers)).isTrue();
        assertThat(Modifier.isFinal(modifiers)).isTrue();
        assertThat(field.getLong(null)).isEqualTo(1L);
    }

    @Test
    @DisplayName("is a RuntimeException subtype so callers are not forced into checked-exception handling")
    void isRuntimeExceptionSubtype() {
        assertThat(new FileAccessException("x")).isInstanceOf(RuntimeException.class);
    }
}
