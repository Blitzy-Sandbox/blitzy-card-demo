package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link TransactionCategoryBalanceId} JPA composite key (the Java
 * translation of COBOL {@code TRAN-CAT-KEY} in copybook {@code CVTRA01Y}): they verify the
 * constructor/accessor round-trip and the value-based {@code equals}/{@code hashCode}
 * contract that JPA mandates for every composite-key type so that persistence-context
 * identity-map lookups resolve correctly. This is a framework-free POJO test: no Spring
 * context, persistence, mocks, or containers are involved.
 */
class TransactionCategoryBalanceIdTest {

    @Test
    @DisplayName("all-args constructor populates all three key fields")
    void allArgsConstructorPopulatesAllFields() {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(1001L, "01", 5);

        assertThat(id.getTrancatAcctId()).isEqualTo(Long.valueOf(1001L));
        assertThat(id.getTrancatTypeCd()).isEqualTo("01");
        assertThat(id.getTrancatCd()).isEqualTo(Integer.valueOf(5));
    }

    @Test
    @DisplayName("no-arg constructor followed by setters round-trips every field")
    void noArgConstructorThenSettersRoundTrip() {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId();

        id.setTrancatAcctId(2002L);
        id.setTrancatTypeCd("CR");
        id.setTrancatCd(99);

        assertThat(id.getTrancatAcctId()).isEqualTo(Long.valueOf(2002L));
        assertThat(id.getTrancatTypeCd()).isEqualTo("CR");
        assertThat(id.getTrancatCd()).isEqualTo(Integer.valueOf(99));
    }

    @Test
    @DisplayName("equals is reflexive: an instance equals itself")
    void equalsIsReflexive() {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(1001L, "01", 5);

        assertThat(id).isEqualTo(id);
    }

    @Test
    @DisplayName("equal field values imply symmetric equals() and an identical hashCode()")
    void equalsAndHashCodeForEqualValues() {
        TransactionCategoryBalanceId first = new TransactionCategoryBalanceId(1001L, "01", 5);
        TransactionCategoryBalanceId second = new TransactionCategoryBalanceId(1001L, "01", 5);

        // Symmetric value equality: a.equals(b) && b.equals(a).
        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(first);
        // Equal keys MUST share a hash code, otherwise JPA identity-map lookups break.
        assertThat(first).hasSameHashCodeAs(second);
    }

    @Test
    @DisplayName("a key is not equal to null nor to an unrelated type")
    void notEqualToNullAndOtherType() {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(1001L, "01", 5);

        assertThat(id).isNotEqualTo(null);
        assertThat(id).isNotEqualTo("someString");
    }

    @Test
    @DisplayName("keys differing only by account id are not equal")
    void notEqualWhenAcctIdDiffers() {
        TransactionCategoryBalanceId base = new TransactionCategoryBalanceId(1001L, "01", 5);
        TransactionCategoryBalanceId other = new TransactionCategoryBalanceId(9999L, "01", 5);

        assertThat(base).isNotEqualTo(other);
    }

    @Test
    @DisplayName("keys differing only by transaction type code are not equal")
    void notEqualWhenTypeCdDiffers() {
        TransactionCategoryBalanceId base = new TransactionCategoryBalanceId(1001L, "01", 5);
        TransactionCategoryBalanceId other = new TransactionCategoryBalanceId(1001L, "CR", 5);

        assertThat(base).isNotEqualTo(other);
    }

    @Test
    @DisplayName("keys differing only by transaction category code are not equal")
    void notEqualWhenCdDiffers() {
        TransactionCategoryBalanceId base = new TransactionCategoryBalanceId(1001L, "01", 5);
        TransactionCategoryBalanceId other = new TransactionCategoryBalanceId(1001L, "01", 7);

        assertThat(base).isNotEqualTo(other);
    }

    @Test
    @DisplayName("hashCode is stable across repeated calls on an unmodified instance")
    void hashCodeStableAcrossCalls() {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(1001L, "01", 5);

        assertThat(id.hashCode()).isEqualTo(id.hashCode());
    }
}
