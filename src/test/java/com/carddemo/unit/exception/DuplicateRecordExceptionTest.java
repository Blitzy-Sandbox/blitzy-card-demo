package com.carddemo.unit.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import com.carddemo.exception.DuplicateRecordException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit tests for {@link DuplicateRecordException}.
 *
 * <p>{@code DuplicateRecordException} is the typed Java replacement for the legacy COBOL VSAM
 * duplicate-key condition (FILE STATUS {@code 22} / CICS {@code DFHRESP(DUPKEY)} and
 * {@code DFHRESP(DUPREC)}). In {@code app/cbl/COUSR01C.cbl} the add-user path issues an
 * {@code EXEC CICS WRITE} against the USRSEC file and, on a duplicate response, surfaces the
 * message {@code 'User ID already exist...'}; the modernized service layer raises this exception
 * instead, which {@code GlobalExceptionHandler} maps to HTTP 409 Conflict.</p>
 *
 * <p>These tests exercise the exception POJO in complete isolation: no Spring context, no Mockito,
 * and no database, AWS, or network access. They lock down the constructor and getter contract and
 * the {@code serialVersionUID} declaration so the type stays a stable, serializable error class
 * across the codebase. HTTP status mapping is intentionally NOT asserted here; that is the
 * responsibility of the {@code GlobalExceptionHandler} test suite.</p>
 */
class DuplicateRecordExceptionTest {

    @Test
    @DisplayName("Constructor(message) sets the message and leaves cause, entityType, and key null")
    void messageOnlyConstructor() {
        DuplicateRecordException exception = new DuplicateRecordException("dup");

        assertThat(exception.getMessage()).isEqualTo("dup");
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getEntityType()).isNull();
        assertThat(exception.getKey()).isNull();
    }

    @Test
    @DisplayName("Constructor(message, cause) preserves the message and the original cause")
    void messageAndCauseConstructor() {
        IllegalStateException cause = new IllegalStateException("c");

        DuplicateRecordException exception = new DuplicateRecordException("wrapped", cause);

        assertThat(exception.getMessage()).isEqualTo("wrapped");
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getEntityType()).isNull();
        assertThat(exception.getKey()).isNull();
    }

    @Test
    @DisplayName("Constructor(entityType, key) builds the deterministic "
            + "'<entity> already exists: <key>' message (COUSR01C duplicate-key parity)")
    void entityTypeAndKeyConstructorBuildsDeterministicMessage() {
        DuplicateRecordException exception = new DuplicateRecordException("User", "ADMIN001");

        assertThat(exception.getMessage()).isEqualTo("User already exists: ADMIN001");
        assertThat(exception.getEntityType()).isEqualTo("User");
        assertThat(exception.getKey()).isEqualTo("ADMIN001");
    }

    @Test
    @DisplayName("Constructor(entityType, key) stringifies a non-String key via String.valueOf")
    void entityTypeAndKeyStringifiesNonStringKey() {
        DuplicateRecordException exception = new DuplicateRecordException("Account", 11L);

        assertThat(exception.getKey()).isEqualTo("11");
        assertThat(exception.getMessage()).isEqualTo("Account already exists: 11");
        assertThat(exception.getEntityType()).isEqualTo("Account");
    }

    @Test
    @DisplayName("DuplicateRecordException is an unchecked RuntimeException subtype")
    void isRuntimeExceptionSubtype() {
        assertThat(new DuplicateRecordException("x")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("serialVersionUID is a private static final long equal to 1L")
    void serialVersionUidIsPrivateStaticFinalLongOne() throws Exception {
        Field field = DuplicateRecordException.class.getDeclaredField("serialVersionUID");
        field.setAccessible(true);

        assertThat(field.getType()).isEqualTo(long.class);
        assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(field.getLong(null)).isEqualTo(1L);
    }
}
