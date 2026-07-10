package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Pure, dependency-free unit test for {@link DuplicateResourceException}, the
 * concrete member of the {@link CardDemoException} hierarchy raised when a write
 * would create a record whose key already exists.
 *
 * <p>The condition mirrors the legacy CICS {@code DFHRESP(DUPKEY)} /
 * {@code DFHRESP(DUPREC)} responses on {@code EXEC CICS WRITE} in the online
 * add-record programs (for example the Add-User and Add-Transaction flows) and
 * the batch {@code FILE STATUS '22'} on a keyed write. The migrated exception
 * always maps to {@link HttpStatus#CONFLICT} (HTTP 409) and carries
 * {@link FileStatusCode#DUPLICATE_KEY}, so a centralized REST handler can
 * translate it without inspecting the concrete type.
 *
 * <p>This suite loads no Spring context and uses no database, file, network, or
 * mocking infrastructure: every assertion is an in-memory object check, so it
 * runs fast and deterministically and contributes to line coverage (JaCoCo,
 * Gate 8). It asserts only members that actually exist on the production class.
 * Design rationale lives in {@code docs/decision-log.md}, not in these comments.
 */
@DisplayName("DuplicateResourceException — HTTP 409 / DUPLICATE_KEY contract")
class DuplicateResourceExceptionTest {

    // ---------------------------------------------------------------------
    // Phase 1 — Core contract (guaranteed members)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("(String) constructor maps to 409 CONFLICT and DUPLICATE_KEY and preserves the message")
    void messageConstructorMapsToConflictWithDuplicateKey() {
        String message = "User USER0001 already exists";
        DuplicateResourceException ex = new DuplicateResourceException(message);

        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
        assertThat(ex.getMessage()).isEqualTo(message);
    }

    @Test
    @DisplayName("is a CardDemoException and an unchecked RuntimeException")
    void messageConstructorIsCardDemoAndRuntimeException() {
        DuplicateResourceException ex =
                new DuplicateResourceException("Transaction 0000000001 already exists");

        assertThat(ex)
                .isInstanceOf(CardDemoException.class)
                .isInstanceOf(RuntimeException.class);
        // The single-argument constructor supplies no triggering cause.
        assertThat(ex.getCause()).isNull();
    }

    // ---------------------------------------------------------------------
    // Phase 2 — Cause and convenience constructors / factory (present members)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("(String, Throwable) constructor preserves the cause and the 409 / DUPLICATE_KEY contract")
    void messageAndCauseConstructorPreservesCauseAndContract() {
        IllegalStateException cause = new IllegalStateException("constraint violation");
        DuplicateResourceException ex =
                new DuplicateResourceException("duplicate primary key", cause);

        // Resolves to the (String, Throwable) overload: the cause flows through verbatim.
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage()).isEqualTo("duplicate primary key");
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
    }

    @Test
    @DisplayName("(String, Object) constructor builds a message from resource type and key and keeps the contract")
    void resourceTypeAndKeyConstructorBuildsMessageAndHonorsContract() {
        // Both arguments are Strings, so this resolves to the (resourceType, key)
        // overload rather than (message, cause). Assert on the tokens, not the exact
        // template wording, so the test stays robust to message formatting changes.
        DuplicateResourceException ex = new DuplicateResourceException("User", "USER0001");

        assertThat(ex.getMessage()).contains("User", "USER0001");
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
    }

    @Test
    @DisplayName("static of(resourceType, key) yields a contract-honoring instance")
    void staticFactoryOfHonorsContract() {
        DuplicateResourceException ex = DuplicateResourceException.of("Transaction", 123L);

        assertThat(ex)
                .isNotNull()
                .isInstanceOf(DuplicateResourceException.class);
        assertThat(ex.getMessage()).contains("Transaction", "123");
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
    }

    @Test
    @DisplayName("static of(...) renders a null key null-safely and stays 409 / DUPLICATE_KEY")
    void factoryRendersNullKeyNullSafely() {
        // The single-signature factory removes overload ambiguity, so a null key
        // exercises the null-safe String.valueOf(...) rendering path in the message.
        DuplicateResourceException ex = DuplicateResourceException.of("Account", null);

        assertThat(ex.getMessage()).contains("Account", "null");
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getFileStatusCode()).isEqualTo(FileStatusCode.DUPLICATE_KEY);
    }
}
