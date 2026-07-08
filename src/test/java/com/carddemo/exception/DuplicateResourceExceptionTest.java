package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link DuplicateResourceException}, the typed exception that
 * maps the legacy VSAM "duplicate key" condition (FILE STATUS {@code 22} / CICS
 * {@code DUPREC}) to an HTTP 409 at the REST boundary (source commit
 * {@code 27d6c6f}, AAP &sect;0.8.3). It is raised on create paths (for example
 * adding a user or a transaction) when the target key already exists.
 *
 * <p>These tests pin the fixed HTTP status ({@link HttpStatus#CONFLICT}), the
 * associated {@link FileStatusCode#DUPLICATE_KEY}, and the resource-type/key
 * message builders (including the {@code of(...)} factory). They contribute to
 * the CP2 test-coverage gate (Gate 8, JaCoCo &ge; 80%).</p>
 */
@DisplayName("DuplicateResourceException — HTTP 409 / FILE STATUS 22 mapping")
class DuplicateResourceExceptionTest {

    @Test
    @DisplayName("is a CardDemoException carrying HTTP 409 and FILE STATUS duplicate-key")
    void baseMapping() {
        DuplicateResourceException ex = new DuplicateResourceException("already there");

        assertThat(ex)
                .isInstanceOf(CardDemoException.class)
                .isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("already there");
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
    }

    @Test
    @DisplayName("preserves a wrapped cause while keeping the 409 / duplicate-key mapping")
    void causeCtor() {
        Throwable cause = new IllegalStateException("io");

        DuplicateResourceException ex = new DuplicateResourceException("already there", cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
    }

    @Nested
    @DisplayName("resource-type/key message builders")
    class MessageBuilders {

        @Test
        @DisplayName("the type/key constructor renders '<type> already exists for id: <key>'")
        void typeKeyConstructor() {
            DuplicateResourceException ex = new DuplicateResourceException("User", "USER0001");

            assertThat(ex.getMessage()).isEqualTo("User already exists for id: USER0001");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("the of(...) factory builds the same message and mapping")
        void ofFactory() {
            DuplicateResourceException ex = DuplicateResourceException.of("Transaction", 900L);

            assertThat(ex.getMessage()).isEqualTo("Transaction already exists for id: 900");
            assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
        }

        @Test
        @DisplayName("a null key is rendered as the literal 'null'")
        void nullKey() {
            assertThat(DuplicateResourceException.of("User", null).getMessage())
                    .isEqualTo("User already exists for id: null");
        }
    }
}
