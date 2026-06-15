package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.key.TransactionCategoryBalanceId;
import com.carddemo.model.key.TransactionCategoryId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionCategoryId} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies the CVTRA04Y TWO-part composite-key (typeCode, categoryCode) equals/hashCode contract per AAP {@code §0.4.3}.
 */
@DisplayName("TransactionCategoryId — 2-part composite key contract (CVTRA04Y)")
class TransactionCategoryIdTest {

    private TransactionCategoryId newKey() {
        return new TransactionCategoryId("01", 5);
    }

    @Test
    @DisplayName("all-args constructor populates both components")
    void allArgsConstructorPopulatesComponents() {
        TransactionCategoryId id = newKey();
        assertThat(id.getTypeCode()).isEqualTo("01");
        assertThat(id.getCategoryCode()).isEqualTo(5);
    }

    @Test
    @DisplayName("equality is reflexive, symmetric and consistent with hashCode")
    void equalityReflexiveSymmetricHashCode() {
        TransactionCategoryId a = newKey();
        TransactionCategoryId b = newKey();
        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("differing any single component breaks equality")
    void differingAnyComponentBreaksEquality() {
        TransactionCategoryId base = newKey();
        assertThat(base).isNotEqualTo(new TransactionCategoryId("99", 5));
        assertThat(base).isNotEqualTo(new TransactionCategoryId("01", 99));
    }

    @Test
    @DisplayName("equals(null), equals(other type) and equals(3-part key) are false")
    void notEqualToNullOrOtherType() {
        TransactionCategoryId base = newKey();
        assertThat(base.equals(null)).isFalse();
        assertThat(base.equals("not-a-key")).isFalse();
        assertThat(base.equals(new TransactionCategoryBalanceId(1L, "01", 5))).isFalse();
    }
}
