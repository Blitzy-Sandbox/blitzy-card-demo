package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link TransactionCategoryTypeId} JPA composite key (from COBOL
 * {@code TRAN-CAT-KEY}, copybook {@code CVTRA04Y}): accessor round-trip and
 * {@code equals}/{@code hashCode} value equality over both key fields.
 *
 * <p>{@code TransactionCategoryTypeId} is the migrated Java representation of the leading
 * six bytes of the 60-byte {@code TRAN-CAT-RECORD} group item (source commit
 * {@code 27d6c6f}, read-only reference). Its two components are:</p>
 * <ul>
 *   <li>{@code tranTypeCd} &larr; {@code TRAN-TYPE-CD PIC X(02)} ({@link String});</li>
 *   <li>{@code tranCatCd} &larr; {@code TRAN-CAT-CD PIC 9(04)} ({@link Integer}).</li>
 * </ul>
 *
 * <p>Because the type is used as an {@code @EmbeddedId}, a correct value-based
 * {@link Object#equals(Object) equals}/{@link Object#hashCode() hashCode} contract over
 * <em>both</em> components is mandatory: JPA relies on it for identity-map lookups, dirty
 * checking, and collection membership. These tests are the primary focus and assert
 * reflexivity, symmetry, the equal-values-share-hashCode invariant, inequality on each
 * individual component, and inequality against {@code null} and an unrelated type.</p>
 *
 * <p>This is a plain-POJO test: no Spring context, no persistence, no Testcontainers, and
 * no mocks. Only wrapper types are exercised (no {@code float}/{@code double}), matching
 * the decimal-fidelity constraints of the migration. This two-field key is deliberately
 * distinct from the three-field {@link TransactionCategoryBalanceId}, so every assertion
 * here covers exactly the {@code tranTypeCd} and {@code tranCatCd} pair.</p>
 */
class TransactionCategoryTypeIdTest {

    /**
     * Canonical transaction-type-code sample ({@code TRAN-TYPE-CD PIC X(02)}, two chars).
     */
    private static final String BASE_TRAN_TYPE_CD = "01";

    /**
     * Canonical transaction-category-code sample ({@code TRAN-CAT-CD PIC 9(04)}).
     */
    private static final Integer BASE_TRAN_CAT_CD = 4;

    /**
     * Builds a fresh composite key populated with the canonical base sample values. Each
     * call returns a distinct instance so equality (not identity) is what the
     * {@code equals}/{@code hashCode} tests actually exercise.
     *
     * @return a new {@link TransactionCategoryTypeId} carrying the base sample values
     */
    private static TransactionCategoryTypeId baseKey() {
        return new TransactionCategoryTypeId(BASE_TRAN_TYPE_CD, BASE_TRAN_CAT_CD);
    }

    /**
     * The all-arguments constructor must populate both components in COBOL
     * {@code TRAN-CAT-KEY} field order, and each getter must return that value.
     */
    @Test
    void allArgsConstructorPopulatesBothFields() {
        TransactionCategoryTypeId key = new TransactionCategoryTypeId("01", 4);

        assertThat(key.getTranTypeCd()).isEqualTo("01");
        assertThat(key.getTranCatCd()).isEqualTo(Integer.valueOf(4));
    }

    /**
     * The JPA-mandated no-arg constructor plus the setters must round-trip both
     * components: what is set is exactly what the corresponding getter returns.
     */
    @Test
    void noArgConstructorThenSettersRoundTrip() {
        TransactionCategoryTypeId key = new TransactionCategoryTypeId();

        key.setTranTypeCd("CR");
        key.setTranCatCd(1234);

        assertThat(key.getTranTypeCd()).isEqualTo("CR");
        assertThat(key.getTranCatCd()).isEqualTo(Integer.valueOf(1234));
    }

    /**
     * {@code equals} must be reflexive: an instance always equals itself (this exercises
     * the {@code this == o} short-circuit branch of the implementation).
     */
    @Test
    void equalsIsReflexive() {
        TransactionCategoryTypeId key = baseKey();

        assertThat(key.equals(key)).isTrue();
    }

    /**
     * Two distinct instances holding identical component values must be {@code equals} in
     * both directions (symmetry) and must therefore share the same {@code hashCode}.
     */
    @Test
    void equalsAndHashCodeForEqualValues() {
        TransactionCategoryTypeId first = baseKey();
        TransactionCategoryTypeId second = baseKey();

        // Guard: these must be different objects, so equality is by value, not identity.
        assertThat(first).isNotSameAs(second);

        // Symmetric value equality via the class's own equals implementation.
        assertThat(first.equals(second)).isTrue();
        assertThat(second.equals(first)).isTrue();

        // The equals/hashCode contract: equal keys MUST produce equal hash codes,
        // otherwise JPA persistence-context identity-map lookups break.
        assertThat(first).hasSameHashCodeAs(second);
    }

    /**
     * {@code equals} must reject {@code null} and any unrelated type (here a {@link String}
     * whose text matches the transaction-type code) — exercising the {@code null} check
     * and the {@code getClass()} type guard of the implementation.
     */
    @Test
    void notEqualToNullAndOtherType() {
        TransactionCategoryTypeId key = baseKey();

        assertThat(key.equals(null)).isFalse();
        assertThat(key.equals(BASE_TRAN_TYPE_CD)).isFalse();
    }

    /**
     * Keys differing only on {@code tranTypeCd} (the first key component) must not be
     * equal, confirming that field participates in equality.
     */
    @Test
    void notEqualWhenTranTypeCdDiffers() {
        TransactionCategoryTypeId base = baseKey();
        TransactionCategoryTypeId other = new TransactionCategoryTypeId("02", BASE_TRAN_CAT_CD);

        assertThat(base.equals(other)).isFalse();
    }

    /**
     * Keys differing only on {@code tranCatCd} (the second key component) must not be
     * equal, confirming that field participates in equality.
     */
    @Test
    void notEqualWhenTranCatCdDiffers() {
        TransactionCategoryTypeId base = baseKey();
        TransactionCategoryTypeId other = new TransactionCategoryTypeId(BASE_TRAN_TYPE_CD, 9);

        assertThat(base.equals(other)).isFalse();
    }

    /**
     * {@code hashCode} must be stable across repeated invocations on the same instance
     * whose state has not changed.
     */
    @Test
    void hashCodeStableAcrossCalls() {
        TransactionCategoryTypeId key = baseKey();

        int firstCall = key.hashCode();
        int secondCall = key.hashCode();

        assertThat(secondCall).isEqualTo(firstCall);
    }
}
