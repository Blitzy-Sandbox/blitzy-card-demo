package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link OptimisticLockConflictException}, the typed exception
 * that preserves the COBOL read-then-rewrite "record changed" behavior of the
 * account (COACTUPC/CAUP) and card (COCRDUPC/CCUP) update programs (source
 * commit {@code 27d6c6f}, AAP &sect;0.8.4). A JPA {@code @Version} conflict is
 * mapped to this exception and surfaced as an HTTP 409 at the REST boundary.
 *
 * <p>These tests pin the {@code DEFAULT_MESSAGE} constant (which mirrors the
 * legacy advisory text), the fixed HTTP status ({@link HttpStatus#CONFLICT}),
 * and every constructor variant. They contribute to the CP2 test-coverage gate
 * (Gate 8, JaCoCo &ge; 80%).</p>
 */
@DisplayName("OptimisticLockConflictException — HTTP 409 optimistic-lock mapping")
class OptimisticLockConflictExceptionTest {

    @Test
    @DisplayName("DEFAULT_MESSAGE mirrors the COBOL 'record changed' advisory verbatim")
    void defaultMessageConstant() {
        assertThat(OptimisticLockConflictException.DEFAULT_MESSAGE)
                .isEqualTo("Record changed by some one else. Please review");
    }

    @Nested
    @DisplayName("default-message constructors")
    class DefaultMessageConstructors {

        @Test
        @DisplayName("no-arg constructor uses the default message and HTTP 409")
        void noArg() {
            OptimisticLockConflictException ex = new OptimisticLockConflictException();

            assertThat(ex).isInstanceOf(CardDemoException.class);
            assertThat(ex.getMessage()).isEqualTo(OptimisticLockConflictException.DEFAULT_MESSAGE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getFileStatusCode()).isNull();
        }

        @Test
        @DisplayName("cause constructor keeps the default message and wraps the cause")
        void causeCtorUsesDefaultMessage() {
            Throwable cause = new IllegalStateException("stale version");

            OptimisticLockConflictException ex = new OptimisticLockConflictException(cause);

            assertThat(ex.getMessage()).isEqualTo(OptimisticLockConflictException.DEFAULT_MESSAGE);
            assertThat(ex.getCause()).isSameAs(cause);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("custom-message constructors")
    class CustomMessageConstructors {

        @Test
        @DisplayName("custom-message constructor overrides the advisory text but keeps HTTP 409")
        void customMessage() {
            OptimisticLockConflictException ex = new OptimisticLockConflictException("account changed");

            assertThat(ex.getMessage()).isEqualTo("account changed");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("custom-message + cause constructor preserves both")
        void customMessageAndCause() {
            Throwable cause = new RuntimeException("optimistic failure");

            OptimisticLockConflictException ex = new OptimisticLockConflictException("card changed", cause);

            assertThat(ex.getMessage()).isEqualTo("card changed");
            assertThat(ex.getCause()).isSameAs(cause);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        }
    }
}
