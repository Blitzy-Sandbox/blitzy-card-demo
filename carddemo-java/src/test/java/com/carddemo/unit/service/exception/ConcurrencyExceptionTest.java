package com.carddemo.unit.service.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.ConcurrencyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConcurrencyException}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * realizes the optimistic-locking conflict detected by the before/after record-image
 * comparison guarding the dual ACCTDAT+CUSTDAT update in {@code app/cbl/COACTUPC.cbl}
 * (the sole {@code SYNCPOINT ROLLBACK}, L4100) and the card update in
 * {@code app/cbl/COCRDUPC.cbl}. Mapped to JPA {@code @Version}; services throw this on an
 * optimistic-lock conflict (AAP section 0.8.4).</p>
 */
@DisplayName("ConcurrencyException - optimistic-locking conflict (COACTUPC/COCRDUPC)")
class ConcurrencyExceptionTest {

    @Test
    @DisplayName("(String) constructor leaves entity null and has no cause")
    void messageOnly_leavesEntityNull() {
        ConcurrencyException ex = new ConcurrencyException("conflict");

        assertThat(ex.getMessage()).isEqualTo("conflict");
        assertThat(ex.getEntity()).isNull();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("(String, Throwable) constructor keeps the cause and leaves entity null")
    void messageCause_leavesEntityNull() {
        Throwable root = new IllegalStateException("stale version");

        ConcurrencyException ex = new ConcurrencyException("conflict", root);

        assertThat(ex.getMessage()).isEqualTo("conflict");
        assertThat(ex.getCause()).isSameAs(root);
        assertThat(ex.getEntity()).isNull();
    }

    @Test
    @DisplayName("(String, String entity, Throwable) is the canonical optimistic-lock form")
    void canonicalThreeArg_storesEntityAndCause() {
        Throwable root = new IllegalStateException("stale version");

        ConcurrencyException ex =
            new ConcurrencyException("Record changed by some one else. Please review", "Account", root);

        assertThat(ex.getMessage()).isEqualTo("Record changed by some one else. Please review");
        assertThat(ex.getEntity()).isEqualTo("Account");
        assertThat(ex.getCause()).isSameAs(root);
    }

    @Test
    @DisplayName("declares exactly three constructors and no (String, String) two-arg constructor")
    void constructorContract_hasNoTwoArgStringStringConstructor() {
        assertThat(ConcurrencyException.class.getDeclaredConstructors()).hasSize(3);

        assertThatThrownBy(
                () -> ConcurrencyException.class.getConstructor(String.class, String.class))
            .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("is-a CardDemoException and an unchecked RuntimeException")
    void hierarchy_isCardDemoExceptionAndRuntimeException() {
        ConcurrencyException ex = new ConcurrencyException("conflict");

        assertThat(ex).isInstanceOf(CardDemoException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
