package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DisclosureGroupId} JPA composite key (from COBOL
 * {@code DIS-GROUP-KEY}, copybook {@code CVTRA02Y}): accessor round-trip and
 * {@code equals}/{@code hashCode} value equality.
 *
 * <p>{@code DisclosureGroupId} is the migrated Java representation of the 16-byte
 * {@code DIS-GROUP-KEY} group item (source commit {@code 27d6c6f}, read-only
 * reference). Its three components are:</p>
 * <ul>
 *   <li>{@code disAcctGroupId} &larr; {@code DIS-ACCT-GROUP-ID PIC X(10)} ({@link String});</li>
 *   <li>{@code disTranTypeCd} &larr; {@code DIS-TRAN-TYPE-CD PIC X(02)} ({@link String});</li>
 *   <li>{@code disTranCatCd} &larr; {@code DIS-TRAN-CAT-CD PIC 9(04)} ({@link Integer}).</li>
 * </ul>
 *
 * <p>Because the type is used as an {@code @EmbeddedId}, a correct value-based
 * {@link Object#equals(Object) equals}/{@link Object#hashCode() hashCode} contract
 * over <em>all three</em> components is mandatory: JPA relies on it for identity map
 * lookups, dirty checking, and collection membership. These tests are the primary
 * focus and assert reflexivity, symmetry, the equal-values-share-hashCode invariant,
 * inequality on each individual component, and inequality against {@code null} and an
 * unrelated type.</p>
 *
 * <p>This is a plain-POJO test: no Spring context, no persistence, no Testcontainers,
 * and no mocks. Only wrapper types are exercised (no {@code float}/{@code double}),
 * matching the decimal-fidelity constraints of the migration.</p>
 */
class DisclosureGroupIdTest {

    /**
     * Canonical account-group-id sample ({@code DIS-ACCT-GROUP-ID PIC X(10)}, 10 chars).
     */
    private static final String BASE_ACCT_GROUP_ID = "GROUP00001";

    /**
     * Canonical transaction-type-code sample ({@code DIS-TRAN-TYPE-CD PIC X(02)}, 2 chars).
     */
    private static final String BASE_TRAN_TYPE_CD = "01";

    /**
     * Canonical transaction-category-code sample ({@code DIS-TRAN-CAT-CD PIC 9(04)}).
     */
    private static final Integer BASE_TRAN_CAT_CD = 100;

    /**
     * Builds a fresh composite key populated with the canonical base sample values.
     * Each call returns a distinct instance so equality (not identity) is what the
     * {@code equals}/{@code hashCode} tests actually exercise.
     *
     * @return a new {@link DisclosureGroupId} carrying the base sample values
     */
    private static DisclosureGroupId baseKey() {
        return new DisclosureGroupId(BASE_ACCT_GROUP_ID, BASE_TRAN_TYPE_CD, BASE_TRAN_CAT_CD);
    }

    /**
     * The all-arguments constructor must populate every component in COBOL
     * {@code DIS-GROUP-KEY} field order, and each getter must return that value.
     */
    @Test
    void allArgsConstructorPopulatesAllFields() {
        DisclosureGroupId key = new DisclosureGroupId("ZEROACCT01", "01", 100);

        assertThat(key.getDisAcctGroupId()).isEqualTo("ZEROACCT01");
        assertThat(key.getDisTranTypeCd()).isEqualTo("01");
        assertThat(key.getDisTranCatCd()).isEqualTo(Integer.valueOf(100));
    }

    /**
     * The JPA-mandated no-arg constructor plus the setters must round-trip every
     * component: what is set is what the corresponding getter returns.
     */
    @Test
    void noArgConstructorThenSettersRoundTrip() {
        DisclosureGroupId key = new DisclosureGroupId();

        key.setDisAcctGroupId("GROUP00001");
        key.setDisTranTypeCd("DB");
        key.setDisTranCatCd(5);

        assertThat(key.getDisAcctGroupId()).isEqualTo("GROUP00001");
        assertThat(key.getDisTranTypeCd()).isEqualTo("DB");
        assertThat(key.getDisTranCatCd()).isEqualTo(Integer.valueOf(5));
    }

    /**
     * {@code equals} must be reflexive: an instance always equals itself (exercises the
     * {@code this == o} short-circuit branch).
     */
    @Test
    void equalsIsReflexive() {
        DisclosureGroupId key = baseKey();

        assertThat(key.equals(key)).isTrue();
    }

    /**
     * Two distinct instances holding identical component values must be {@code equals}
     * in both directions (symmetry) and must therefore share the same {@code hashCode}.
     */
    @Test
    void equalsAndHashCodeForEqualValues() {
        DisclosureGroupId first = baseKey();
        DisclosureGroupId second = baseKey();

        // Guard: these must be different objects, so equality is by value, not identity.
        assertThat(first).isNotSameAs(second);

        // Symmetric value equality via the class's own equals implementation.
        assertThat(first.equals(second)).isTrue();
        assertThat(second.equals(first)).isTrue();

        // The equals/hashCode contract: equal objects must produce equal hash codes.
        assertThat(first).hasSameHashCodeAs(second);
    }

    /**
     * {@code equals} must reject {@code null} and any unrelated type (here a
     * {@link String} whose text matches the account-group-id) — exercising the
     * {@code null} check and the {@code getClass()} type guard.
     */
    @Test
    void notEqualToNullAndOtherType() {
        DisclosureGroupId key = baseKey();

        assertThat(key.equals(null)).isFalse();
        assertThat(key.equals(BASE_ACCT_GROUP_ID)).isFalse();
    }

    /**
     * Keys differing only on {@code disAcctGroupId} must not be equal.
     */
    @Test
    void notEqualWhenAcctGroupIdDiffers() {
        DisclosureGroupId base = baseKey();
        DisclosureGroupId other = new DisclosureGroupId("GROUP00002", BASE_TRAN_TYPE_CD, BASE_TRAN_CAT_CD);

        assertThat(base.equals(other)).isFalse();
    }

    /**
     * Keys differing only on {@code disTranTypeCd} must not be equal.
     */
    @Test
    void notEqualWhenTranTypeCdDiffers() {
        DisclosureGroupId base = baseKey();
        DisclosureGroupId other = new DisclosureGroupId(BASE_ACCT_GROUP_ID, "02", BASE_TRAN_CAT_CD);

        assertThat(base.equals(other)).isFalse();
    }

    /**
     * Keys differing only on {@code disTranCatCd} must not be equal.
     */
    @Test
    void notEqualWhenTranCatCdDiffers() {
        DisclosureGroupId base = baseKey();
        DisclosureGroupId other = new DisclosureGroupId(BASE_ACCT_GROUP_ID, BASE_TRAN_TYPE_CD, 200);

        assertThat(base.equals(other)).isFalse();
    }

    /**
     * {@code hashCode} must be stable across repeated invocations on the same instance
     * with unchanged state.
     */
    @Test
    void hashCodeStableAcrossCalls() {
        DisclosureGroupId key = baseKey();

        int firstCall = key.hashCode();
        int secondCall = key.hashCode();

        assertThat(secondCall).isEqualTo(firstCall);
    }
}
