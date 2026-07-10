package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link ResourceNotFoundException}, the typed exception that maps
 * the legacy VSAM "record not found" condition (FILE STATUS {@code 23} / CICS
 * {@code NOTFND}) to an HTTP 404 at the REST boundary (source commit
 * {@code 27d6c6f}, AAP &sect;0.8.3). It is raised across the read paths of the
 * account, card, transaction, and user services when a keyed lookup misses.
 *
 * <p>These tests pin the fixed HTTP status ({@link HttpStatus#NOT_FOUND}), the
 * associated {@link FileStatusCode#RECORD_NOT_FOUND}, and the resource-type/key
 * message builders (including the {@code of(...)} factory). They contribute to
 * the CP2 test-coverage gate (Gate 8, JaCoCo &ge; 80%).</p>
 */
@DisplayName("ResourceNotFoundException — HTTP 404 / FILE STATUS 23 mapping")
class ResourceNotFoundExceptionTest {

    @Test
    @DisplayName("is a CardDemoException carrying HTTP 404 and FILE STATUS record-not-found")
    void baseMapping() {
        ResourceNotFoundException ex = new ResourceNotFoundException("no such record");

        assertThat(ex)
                .isInstanceOf(CardDemoException.class)
                .isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("no such record");
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.RECORD_NOT_FOUND);
    }

    @Test
    @DisplayName("preserves a wrapped cause while keeping the 404 / record-not-found mapping")
    void causeCtor() {
        Throwable cause = new IllegalStateException("io");

        ResourceNotFoundException ex = new ResourceNotFoundException("no such record", cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.RECORD_NOT_FOUND);
    }

    @Nested
    @DisplayName("resource-type/key message builders")
    class MessageBuilders {

        @Test
        @DisplayName("the type/key constructor renders '<type> not found for id: <key>'")
        void typeKeyConstructor() {
            ResourceNotFoundException ex = new ResourceNotFoundException("Account", 42);

            assertThat(ex.getMessage()).isEqualTo("Account not found for id: 42");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("the of(...) factory builds the same message and mapping")
        void ofFactory() {
            ResourceNotFoundException ex = ResourceNotFoundException.of("Card", "C001");

            assertThat(ex.getMessage()).isEqualTo("Card not found for id: C001");
            assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.RECORD_NOT_FOUND);
        }

        @Test
        @DisplayName("a null key is rendered as the literal 'null'")
        void nullKey() {
            assertThat(ResourceNotFoundException.of("Account", null).getMessage())
                    .isEqualTo("Account not found for id: null");
        }
    }
}
