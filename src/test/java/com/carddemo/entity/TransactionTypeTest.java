package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link TransactionType} single-key lookup entity
 * (COBOL {@code TRAN-TYPE-RECORD} / copybook {@code CVTRA03Y}).
 *
 * <p>{@code TransactionType} is the Java translation of the 60-byte
 * {@code TRAN-TYPE-RECORD} (source commit {@code 27d6c6f}, read-only reference).
 * It is a static reference/lookup table whose two mapped columns are:</p>
 * <ul>
 *   <li>{@code tranType} &larr; {@code TRAN-TYPE PIC X(02)} &mdash; the single-column
 *       natural key ({@link String});</li>
 *   <li>{@code tranTypeDesc} &larr; {@code TRAN-TYPE-DESC PIC X(50)} ({@link String}).</li>
 * </ul>
 *
 * <p>This entity carries no monetary fields, no {@code @Version} optimistic-lock
 * column, and no database-generated identifier, so the tests focus on simple
 * accessor round-trips rather than value-equality or concurrency semantics. It
 * must not be confused with {@code TransactionCategoryType} (copybook
 * {@code CVTRA04Y}), which uses a two-part composite key.</p>
 *
 * <p>These are plain-POJO tests: no Spring context, no persistence, no
 * Testcontainers, and no mocks. Only {@link String} values are exercised (no
 * {@code float}/{@code double}), consistent with the migration's decimal-fidelity
 * constraints. The sample values {@code "01"}/{@code "Purchase"} mirror the first
 * row of the {@code trantype} reference fixture.</p>
 */
class TransactionTypeTest {

    /**
     * Setting the {@code tranType} natural key ({@code TRAN-TYPE PIC X(02)}) must
     * be observable through its getter unchanged.
     */
    @Test
    @DisplayName("tranType natural key round-trips through setter and getter")
    void naturalKeyRoundTrip() {
        TransactionType type = new TransactionType();

        type.setTranType("01");

        assertThat(type.getTranType()).isEqualTo("01");
    }

    /**
     * Setting the {@code tranTypeDesc} description ({@code TRAN-TYPE-DESC PIC X(50)})
     * must be observable through its getter unchanged.
     */
    @Test
    @DisplayName("tranTypeDesc round-trips through setter and getter")
    void descriptionRoundTrip() {
        TransactionType type = new TransactionType();

        type.setTranTypeDesc("Purchase");

        assertThat(type.getTranTypeDesc()).isEqualTo("Purchase");
    }

    /**
     * Reference-row assembly sanity check: the JPA-mandated no-arg constructor
     * followed by both setters must compose a complete {@code TRAN-TYPE-RECORD}
     * whose fields the getters return independently.
     */
    @Test
    @DisplayName("no-arg constructor plus setters compose a full reference row")
    void noArgConstructorThenSettersComposeRecord() {
        TransactionType type = new TransactionType();

        type.setTranType("01");
        type.setTranTypeDesc("Purchase");

        assertThat(type.getTranType()).isEqualTo("01");
        assertThat(type.getTranTypeDesc()).isEqualTo("Purchase");
    }

    /**
     * A freshly constructed instance has no seeded values, so both accessors must
     * return {@code null} before any setter is invoked.
     */
    @Test
    @DisplayName("freshly constructed instance has null fields")
    void newInstanceDefaultsAreNull() {
        TransactionType type = new TransactionType();

        assertThat(type.getTranType()).isNull();
        assertThat(type.getTranTypeDesc()).isNull();
    }
}
