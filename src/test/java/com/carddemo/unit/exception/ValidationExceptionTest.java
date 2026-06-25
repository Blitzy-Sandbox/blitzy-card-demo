package com.carddemo.unit.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.exception.ValidationException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit 5 unit test for {@link com.carddemo.exception.ValidationException}, the Java
 * realization of the legacy COBOL programmatic field-edit/validation logic.
 *
 * <p>Behavioral parity references (read-only @ SHA {@code 27d6c6f}): {@code app/cbl/COSGN00C.cbl}
 * performs blank-field edits and surfaces field-specific prompts such as
 * {@code 'Please enter User ID ...'} and {@code 'Please enter Password ...'}; {@code app/cpy/CSSETATY.cpy}
 * highlights each invalid/blank field on screen reentry; and {@code app/cbl/COACTUPC.cbl} performs the
 * broad cross-field account-edit cascade. The modern equivalent of those per-field, ordered prompts is
 * the {@code ValidationException} field-error map.
 *
 * <p>These tests exercise the type as a plain POJO: no Mockito, no Spring context, no database, AWS, or
 * network. The highest-value guarantees verified here are that {@code getFieldErrors()} is never
 * {@code null}, is unmodifiable, preserves insertion order, is decoupled from the caller's map via a
 * defensive copy, and that the {@code serialVersionUID} is declared exactly as the contract requires.
 * HTTP status mapping (400) is intentionally not asserted here; that concern lives in the centralized
 * exception-handler test.
 */
@DisplayName("ValidationException: per-field error map — never-null, unmodifiable, insertion-ordered, defensively copied")
class ValidationExceptionTest {

    @Test
    @DisplayName("message-only constructor: message preserved, no cause, field errors non-null and empty")
    void messageOnlyConstructorHasEmptyNonNullFieldErrors() {
        ValidationException ex = new ValidationException("bad input");

        assertThat(ex.getMessage()).isEqualTo("bad input");
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getFieldErrors()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("message+cause constructor: message and cause preserved, field errors non-null and empty")
    void messageAndCauseConstructor() {
        IllegalArgumentException cause = new IllegalArgumentException("c");

        ValidationException ex = new ValidationException("wrapped failure", cause);

        assertThat(ex.getMessage()).isEqualTo("wrapped failure");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getFieldErrors()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("message+map constructor: copies every per-field entry (COSGN00C parity prompts)")
    void messageAndMapConstructorCopiesEntries() {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put("userId", "Please enter User ID ...");
        fieldErrors.put("password", "Please enter Password ...");

        ValidationException ex = new ValidationException("validation failed", fieldErrors);

        assertThat(ex.getFieldErrors())
                .containsEntry("userId", "Please enter User ID ...")
                .containsEntry("password", "Please enter Password ...")
                .hasSize(2);
    }

    @Test
    @DisplayName("getFieldErrors() is never null and is empty when no per-field detail is supplied")
    void getFieldErrorsIsNeverNull() {
        assertThat(new ValidationException("v").getFieldErrors())
                .isNotNull()
                .isEmpty();
    }

    @Test
    @DisplayName("getFieldErrors() is unmodifiable for both populated and empty maps")
    void getFieldErrorsIsUnmodifiable() {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put("userId", "Please enter User ID ...");
        ValidationException populated = new ValidationException("v", fieldErrors);

        assertThatThrownBy(() -> populated.getFieldErrors().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new ValidationException("v").getFieldErrors().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("field errors preserve the insertion order of the supplied LinkedHashMap")
    void fieldErrorsPreserveInsertionOrder() {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put("c", "inserted first");
        fieldErrors.put("a", "inserted second");
        fieldErrors.put("b", "inserted third");

        ValidationException ex = new ValidationException("ordered", fieldErrors);

        assertThat(ex.getFieldErrors().keySet()).containsExactly("c", "a", "b");
    }

    @Test
    @DisplayName("constructor makes a defensive copy decoupled from the caller's source map")
    void defensiveCopyDecouplesFromSourceMap() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("userId", "Please enter User ID ...");

        ValidationException ex = new ValidationException("v", source);

        // Mutating the original AFTER construction must not leak into the exception's snapshot.
        source.put("password", "added after construction");

        assertThat(ex.getFieldErrors())
                .hasSize(1)
                .containsKey("userId")
                .doesNotContainKey("password");
    }

    @Test
    @DisplayName("serialVersionUID is declared private static final long = 1L")
    void serialVersionUidIsPrivateStaticFinalLongOne() throws Exception {
        Field field = ValidationException.class.getDeclaredField("serialVersionUID");
        field.setAccessible(true);

        assertThat(field.getType()).isEqualTo(long.class);

        int modifiers = field.getModifiers();
        assertThat(Modifier.isPrivate(modifiers)).isTrue();
        assertThat(Modifier.isStatic(modifiers)).isTrue();
        assertThat(Modifier.isFinal(modifiers)).isTrue();

        assertThat(field.getLong(null)).isEqualTo(1L);
    }

    @Test
    @DisplayName("ValidationException is a RuntimeException subtype (unchecked)")
    void isRuntimeExceptionSubtype() {
        assertThat(new ValidationException("x")).isInstanceOf(RuntimeException.class);
    }
}
