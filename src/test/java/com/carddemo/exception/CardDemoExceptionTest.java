package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Pure, dependency-free unit test for the abstract root exception
 * {@link CardDemoException}.
 *
 * <p>{@code CardDemoException} is {@code abstract} with {@code protected}
 * constructors, so it can be neither instantiated directly nor sub-classed from
 * outside its own package. This test therefore lives in the same
 * {@code com.carddemo.exception} package and defines a minimal, test-local
 * concrete subclass ({@link TestException}) whose only purpose is to reach the
 * protected constructors and assert the base contract:
 * <ul>
 *   <li>the mapped {@link HttpStatus} is propagated and is never {@code null};</li>
 *   <li>the optional {@link FileStatusCode} origin is {@code null} when omitted
 *       and preserved verbatim when supplied (returned directly, never wrapped
 *       in {@link java.util.Optional});</li>
 *   <li>the detail message and triggering cause flow through to
 *       {@link RuntimeException}; and</li>
 *   <li>the type is an unchecked {@link RuntimeException}.</li>
 * </ul>
 *
 * <p>The suite loads no Spring context and uses no database, file, network, or
 * mocking infrastructure &mdash; every assertion is an in-memory object check,
 * so it runs fast and deterministically and contributes to line coverage
 * (JaCoCo, Gate 8). Design rationale for the exception hierarchy lives in
 * {@code docs/decision-log.md}, not in these comments.
 */
@DisplayName("CardDemoException — abstract root exception base contract")
class CardDemoExceptionTest {

    /**
     * Minimal concrete subclass used only to exercise the {@code protected}
     * constructors of the abstract {@link CardDemoException}. It carries no
     * state or behaviour of its own beyond the four constructor delegations and
     * declares {@code serialVersionUID} so the {@code -Xlint:serial} category
     * stays clean (Gate 2, zero-warning build).
     */
    private static final class TestException extends CardDemoException {

        private static final long serialVersionUID = 1L;

        TestException(String message, HttpStatus status) {
            super(message, status);
        }

        TestException(String message, HttpStatus status, Throwable cause) {
            super(message, status, cause);
        }

        TestException(String message, HttpStatus status, FileStatusCode fileStatusCode) {
            super(message, status, fileStatusCode);
        }

        TestException(String message, HttpStatus status, FileStatusCode fileStatusCode, Throwable cause) {
            super(message, status, fileStatusCode, cause);
        }
    }

    // ---------------------------------------------------------------------
    // Phase 2 — Base-contract assertions
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("httpStatus is propagated and is never null")
    void httpStatusIsPropagatedAndNeverNull() {
        CardDemoException ex = new TestException("boom", HttpStatus.I_AM_A_TEAPOT);
        assertThat(ex.getHttpStatus())
                .isNotNull()
                .isEqualTo(HttpStatus.I_AM_A_TEAPOT);
    }

    @Test
    @DisplayName("detail message is propagated through to RuntimeException")
    void messageIsPropagatedToRuntimeException() {
        CardDemoException ex = new TestException("boom", HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("is an unchecked RuntimeException and a CardDemoException")
    void isAnUncheckedCardDemoException() {
        CardDemoException ex = new TestException("x", HttpStatus.BAD_REQUEST);
        assertThat(ex)
                .isInstanceOf(RuntimeException.class)
                .isInstanceOf(CardDemoException.class);
    }

    @Test
    @DisplayName("fileStatusCode is null when not supplied (nullable origin)")
    void fileStatusCodeIsNullWhenNotSupplied() {
        CardDemoException ex = new TestException("boom", HttpStatus.BAD_REQUEST);
        assertThat(ex.getFileStatusCode()).isNull();
    }

    @Test
    @DisplayName("fileStatusCode is preserved verbatim when supplied")
    void fileStatusCodeIsPropagatedWhenSupplied() {
        CardDemoException ex =
                new TestException("nf", HttpStatus.NOT_FOUND, FileStatusCode.RECORD_NOT_FOUND);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.RECORD_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("nf");
        // No cause was supplied through the (message, status, fileStatusCode) constructor.
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("cause is propagated through the (message, status, cause) constructor")
    void causeIsPropagatedThroughThrowableConstructor() {
        IllegalStateException root = new IllegalStateException("root");
        CardDemoException ex = new TestException("wrapped", HttpStatus.INTERNAL_SERVER_ERROR, root);
        assertThat(ex.getCause()).isSameAs(root);
        // This constructor supplies no file-status origin, so it must remain null.
        assertThat(ex.getFileStatusCode()).isNull();
    }

    @Test
    @DisplayName("four-arg constructor sets both fileStatusCode and cause")
    void fourArgConstructorSetsBothFileStatusCodeAndCause() {
        IllegalStateException root = new IllegalStateException("io");
        CardDemoException ex =
                new TestException("both", HttpStatus.CONFLICT, FileStatusCode.DUPLICATE_KEY, root);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
        assertThat(ex.getCause()).isSameAs(root);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("both");
    }

    @Test
    @DisplayName("a null httpStatus is rejected (contract: httpStatus is never null)")
    void nullHttpStatusIsRejected() {
        // The base constructor guards httpStatus with Objects.requireNonNull, so a
        // null status must fail fast rather than yield an exception with no status.
        assertThatThrownBy(() -> new TestException("x", null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------------
    // Phase 3 — Abstractness & serialization sanity (compile-level contract)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("CardDemoException is abstract and cannot be instantiated directly")
    void classIsAbstract() {
        // Documents the compiler-enforced invariant: only concrete subtypes exist.
        assertThat(Modifier.isAbstract(CardDemoException.class.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("serialVersionUID field is declared on the base class")
    void serialVersionUidFieldIsDeclared() {
        // The primary guarantee is the zero-warning -Xlint:serial compile (Gate 2);
        // this reflective check is a light, belt-and-suspenders sanity assertion.
        assertThatCode(() -> CardDemoException.class.getDeclaredField("serialVersionUID"))
                .doesNotThrowAnyException();
    }
}
