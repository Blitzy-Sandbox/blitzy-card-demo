package com.carddemo.unit.exception;

import com.carddemo.exception.ConcurrentUpdateException;
import jakarta.persistence.OptimisticLockException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// SUT is deliberately named ConcurrentUpdateException (not OptimisticLockException) to avoid a name clash
// with jakarta.persistence.OptimisticLockException, which this test wraps as the underlying cause.
@DisplayName("ConcurrentUpdateException — COBOL re-read-and-compare optimistic-lock parity")
class ConcurrentUpdateExceptionTest {

    /**
     * Byte-exact parity guard against the legacy COBOL 88-level condition
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (COACTUPC.cbl:521-522 / COCRDUPC.cbl:207-208),
     * whose VALUE clause carries the user-facing message verbatim.
     */
    @Test
    @DisplayName("DEFAULT_MESSAGE matches the legacy COBOL text verbatim")
    void defaultMessageMatchesLegacyCobolVerbatim() {
        // Expected literal typed by hand: "some one" is TWO words, capital P in "Please",
        // and there is NO trailing period inside the literal (the COBOL '.' was the statement terminator).
        assertThat(ConcurrentUpdateException.DEFAULT_MESSAGE)
                .isEqualTo("Record changed by some one else. Please review");
        // Reinforcement: the literal must not be "corrected" — no terminating period, "some one" stays two words.
        assertThat(ConcurrentUpdateException.DEFAULT_MESSAGE)
                .doesNotEndWith(".")
                .contains("some one");
    }

    @Test
    @DisplayName("No-arg constructor uses DEFAULT_MESSAGE with null cause and null entityType")
    void noArgConstructorUsesDefaultMessage() {
        ConcurrentUpdateException ex = new ConcurrentUpdateException();
        assertThat(ex.getMessage()).isEqualTo(ConcurrentUpdateException.DEFAULT_MESSAGE);
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getEntityType()).isNull();
    }

    @Test
    @DisplayName("Message constructor overrides the default detail message")
    void messageConstructorOverridesDefault() {
        ConcurrentUpdateException ex = new ConcurrentUpdateException("custom");
        assertThat(ex.getMessage()).isEqualTo("custom");
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getEntityType()).isNull();
    }

    @Test
    @DisplayName("Message-and-cause constructor preserves both the message and the cause")
    void messageAndCauseConstructor() {
        IllegalStateException cause = new IllegalStateException("x");
        ConcurrentUpdateException ex = new ConcurrentUpdateException("custom", cause);
        assertThat(ex.getMessage()).isEqualTo("custom");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getEntityType()).isNull();
    }

    /**
     * Proves the SUT wraps a JPA optimistic-lock failure (the persistence-layer realization of the
     * COBOL re-read-and-compare guard) while still reporting the COBOL parity message.
     */
    @Test
    @DisplayName("Throwable-cause constructor wraps the cause and keeps the COBOL parity message")
    void throwableCauseConstructorWrapsWithDefaultMessage() {
        OptimisticLockException jpa = new OptimisticLockException("stale");
        ConcurrentUpdateException ex = new ConcurrentUpdateException(jpa);
        assertThat(ex.getMessage()).isEqualTo(ConcurrentUpdateException.DEFAULT_MESSAGE);
        assertThat(ex.getCause()).isSameAs(jpa);
        assertThat(ex.getCause()).isInstanceOf(OptimisticLockException.class);
        assertThat(ex.getEntityType()).isNull();
    }

    /**
     * The public {@code forEntity} factory is the API that records the conflicting record type;
     * no public {@code (String entityType, Throwable cause)} constructor exists on the SUT.
     */
    @Test
    @DisplayName("forEntity factory records the entity type and wraps the cause with the parity message")
    void forEntityFactorySetsEntityTypeAndWrapsCause() {
        OptimisticLockException jpa = new OptimisticLockException("stale");
        ConcurrentUpdateException ex = ConcurrentUpdateException.forEntity("Account", jpa);
        assertThat(ex.getEntityType()).isEqualTo("Account");
        assertThat(ex.getMessage()).isEqualTo(ConcurrentUpdateException.DEFAULT_MESSAGE);
        assertThat(ex.getCause()).isSameAs(jpa);
    }

    @Test
    @DisplayName("Is an unchecked RuntimeException subtype")
    void isRuntimeExceptionSubtype() {
        assertThat(new ConcurrentUpdateException()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("serialVersionUID is a private static final long equal to 1L")
    void serialVersionUidIsPrivateStaticFinalLongOne() throws Exception {
        Field f = ConcurrentUpdateException.class.getDeclaredField("serialVersionUID");
        f.setAccessible(true);
        assertThat(f.getType()).isEqualTo(long.class);
        assertThat(Modifier.isPrivate(f.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(f.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(f.getModifiers())).isTrue();
        assertThat(f.getLong(null)).isEqualTo(1L);
    }
}
