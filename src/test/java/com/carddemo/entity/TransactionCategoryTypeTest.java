package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-POJO unit tests for the {@link TransactionCategoryType} JPA entity (the Java
 * translation of COBOL {@code TRAN-CAT-RECORD}, copybook {@code CVTRA04Y}, record length
 * 60, source commit {@code 27d6c6f} — read-only reference, not copied into this
 * repository).
 *
 * <p>{@code TransactionCategoryType} is a static reference/lookup table whose primary key
 * is the two-part composite {@code TRAN-CAT-KEY}
 * ({@code TRAN-TYPE-CD PIC X(02)} + {@code TRAN-CAT-CD PIC 9(04)}), modelled as the
 * {@code @Embeddable} {@link TransactionCategoryTypeId} and exposed through the
 * {@code @EmbeddedId} {@link TransactionCategoryType#getId() id} property, plus a single
 * non-key description column:</p>
 * <ul>
 *   <li><b>Embedded id {@code id}</b> — the two-part {@code TRAN-CAT-KEY} lives in
 *       {@link TransactionCategoryTypeId} and is <em>not</em> re-declared as separate
 *       entity fields, so it is validated only via the embedded id. These tests verify
 *       the composite key round-trips intact through the entity accessor and that each
 *       component ({@code tranTypeCd}, {@code tranCatCd}) surfaces correctly.</li>
 *   <li><b>Description {@code tranCatTypeDesc}</b> — {@code TRAN-CAT-TYPE-DESC PIC X(50)}
 *       maps to a {@link String} persisted as {@code VARCHAR(50)}; its accessor round-trip
 *       is asserted directly.</li>
 * </ul>
 *
 * <p>This entity carries no monetary fields, no {@code @Version} optimistic-lock column,
 * and no database-generated identifier, so the tests focus on the {@code @EmbeddedId}
 * round-trip and simple accessor round-trips rather than decimal fidelity or concurrency
 * semantics. Only {@link String} and {@link Integer} values are exercised (no
 * {@code float}/{@code double}), consistent with the migration's decimal-fidelity
 * constraints (AAP §0.8.2 / Gate 2). This two-part composite-key lookup must not be
 * confused with {@code TransactionType} (copybook {@code CVTRA03Y}), which uses a
 * single-column key.</p>
 *
 * <p>This is a framework-free test: no Spring context, no persistence, no Testcontainers,
 * and no mocks are involved. Every assertion is an in-memory getter/setter round-trip on a
 * plain object instance, so the suite runs fast and deterministically and contributes to
 * line coverage (JaCoCo). The sample values {@code "01"}/{@code 4}/{@code "Regular Sales
 * Draft"} mirror a representative row of the {@code trancatg} reference fixture.</p>
 */
class TransactionCategoryTypeTest {

    // ---------------------------------------------------------------------
    // Embedded-id (@EmbeddedId) round-trip tests
    // ---------------------------------------------------------------------

    /**
     * The composite key set via
     * {@link TransactionCategoryType#setId(TransactionCategoryTypeId)} must round-trip
     * through {@link TransactionCategoryType#getId()} intact — the returned key is
     * value-equal to the one supplied (via the key's {@code equals} contract), and each of
     * its two components ({@code tranTypeCd}, {@code tranCatCd}) surfaces correctly through
     * the entity's embedded id.
     */
    @Test
    @DisplayName("@EmbeddedId round-trips the composite key and exposes both components")
    void embeddedIdRoundTrip() {
        TransactionCategoryType categoryType = new TransactionCategoryType();
        TransactionCategoryTypeId key = new TransactionCategoryTypeId("01", 4);

        categoryType.setId(key);

        TransactionCategoryTypeId actual = categoryType.getId();
        // Value-based equality: the embedded key is returned intact.
        assertThat(actual).isEqualTo(key);
        // Each key component surfaces through the entity's embedded id.
        assertThat(actual.getTranTypeCd()).isEqualTo("01");
        assertThat(actual.getTranCatCd()).isEqualTo(Integer.valueOf(4));
    }

    // ---------------------------------------------------------------------
    // Description (non-key column) round-trip test
    // ---------------------------------------------------------------------

    /**
     * Setting the {@code tranCatTypeDesc} description
     * ({@code TRAN-CAT-TYPE-DESC PIC X(50)}) must be observable through its getter
     * unchanged.
     */
    @Test
    @DisplayName("tranCatTypeDesc round-trips through setter and getter")
    void descriptionRoundTrip() {
        TransactionCategoryType categoryType = new TransactionCategoryType();

        categoryType.setTranCatTypeDesc("Regular Sales Draft");

        assertThat(categoryType.getTranCatTypeDesc()).isEqualTo("Regular Sales Draft");
    }

    // ---------------------------------------------------------------------
    // Record-assembly and default-state tests
    // ---------------------------------------------------------------------

    /**
     * Record-assembly sanity check: with both the embedded id and the description
     * populated, the entity returns the composite key intact and the description
     * unchanged — the two halves of the {@code TRAN-CAT-RECORD} coexist without
     * interfering.
     */
    @Test
    @DisplayName("full record composes an embedded id and a description together")
    void fullRecordCompose() {
        TransactionCategoryType categoryType = new TransactionCategoryType();
        TransactionCategoryTypeId key = new TransactionCategoryTypeId("CR", 99);

        categoryType.setId(key);
        categoryType.setTranCatTypeDesc("Cash Advance");

        assertThat(categoryType.getId()).isEqualTo(key);
        assertThat(categoryType.getTranCatTypeDesc()).isEqualTo("Cash Advance");
    }

    /**
     * A freshly constructed instance has no seeded values, so both the embedded id and the
     * description accessor must return {@code null} before any setter is invoked.
     */
    @Test
    @DisplayName("freshly constructed instance has null id and description")
    void newInstanceDefaultsAreNull() {
        TransactionCategoryType categoryType = new TransactionCategoryType();

        assertThat(categoryType.getId()).isNull();
        assertThat(categoryType.getTranCatTypeDesc()).isNull();
    }
}
