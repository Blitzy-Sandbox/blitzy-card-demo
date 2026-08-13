package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DatasetIntegrityException}, the refusal a dataset operation raises when it has
 * produced a state the COBOL original cannot produce.
 */
@DisplayName("DatasetIntegrityException - the refusal that stops a commit")
class DatasetIntegrityExceptionTest {
    @Test
    @DisplayName("it is an IllegalStateException, so existing handling covers it")
    void itIsAnIllegalStateException() {
        DatasetIntegrityException refusal =
                new DatasetIntegrityException("a rewrite", "2 rows were replaced");

        assertThat(refusal).isInstanceOf(IllegalStateException.class);
        assertThat(refusal).isInstanceOf(RuntimeException.class);
        assertThat(refusal.getMessage()).isEqualTo("2 rows were replaced");
        assertThat(refusal.operation()).isEqualTo("a rewrite");
    }

    @Test
    @DisplayName("both the operation and the message are required")
    void bothArgumentsAreRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DatasetIntegrityException(null, "a reason"))
                .withMessageContaining("operation name is required");
        assertThatNullPointerException()
                .isThrownBy(() -> new DatasetIntegrityException("a rewrite", null))
                .withMessageContaining("message is required");
    }

    @Test
    @DisplayName("the class is final, so no subclass can widen what a refusal means")
    void theClassIsFinal() {
        assertThat(java.lang.reflect.Modifier.isFinal(DatasetIntegrityException.class.getModifiers()))
                .isTrue();
    }
}
