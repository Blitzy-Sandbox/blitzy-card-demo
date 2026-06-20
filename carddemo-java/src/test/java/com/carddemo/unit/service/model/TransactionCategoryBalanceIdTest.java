package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.key.TransactionCategoryBalanceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionCategoryBalanceId} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies the CVTRA01Y composite-key (accountId, typeCode, categoryCode) equals/hashCode contract per AAP {@code §0.4.3}.
 */
@DisplayName("TransactionCategoryBalanceId — 3-part composite key contract (CVTRA01Y)")
class TransactionCategoryBalanceIdTest {

    private TransactionCategoryBalanceId newKey() {
        return new TransactionCategoryBalanceId(1001L, "01", 5);
    }

    @Test
    @DisplayName("all-args constructor populates all three components")
    void allArgsConstructorPopulatesComponents() {
        TransactionCategoryBalanceId id = newKey();
        assertThat(id.getAccountId()).isEqualTo(1001L);
        assertThat(id.getTypeCode()).isEqualTo("01");
        assertThat(id.getCategoryCode()).isEqualTo(5);
    }

    @Test
    @DisplayName("setters round-trip on the no-arg constructor")
    void settersRoundTrip() {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId();
        id.setAccountId(2002L);
        id.setTypeCode("02");
        id.setCategoryCode(9);
        assertThat(id.getAccountId()).isEqualTo(2002L);
        assertThat(id.getTypeCode()).isEqualTo("02");
        assertThat(id.getCategoryCode()).isEqualTo(9);
    }

    @Test
    @DisplayName("equality is reflexive, symmetric and consistent with hashCode")
    void equalityReflexiveSymmetricHashCode() {
        TransactionCategoryBalanceId a = newKey();
        TransactionCategoryBalanceId b = newKey();
        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("differing any single component breaks equality")
    void differingAnyComponentBreaksEquality() {
        TransactionCategoryBalanceId base = newKey();
        assertThat(base).isNotEqualTo(new TransactionCategoryBalanceId(9999L, "01", 5));
        assertThat(base).isNotEqualTo(new TransactionCategoryBalanceId(1001L, "99", 5));
        assertThat(base).isNotEqualTo(new TransactionCategoryBalanceId(1001L, "01", 99));
    }

    @Test
    @DisplayName("equals(null) and equals(other type) are false")
    void notEqualToNullOrOtherType() {
        TransactionCategoryBalanceId base = newKey();
        assertThat(base.equals(null)).isFalse();
        assertThat(base.equals("not-a-key")).isFalse();
    }
}
