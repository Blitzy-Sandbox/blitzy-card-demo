package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.key.DisclosureGroupId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DisclosureGroupId} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies the CVTRA02Y composite-key (accountGroupId, transactionTypeCode, transactionCategoryCode)
 * equals/hashCode contract, including the literal DEFAULT group, per AAP {@code §0.8.5}.
 */
@DisplayName("DisclosureGroupId — 3-part composite key contract (CVTRA02Y)")
class DisclosureGroupIdTest {

    private DisclosureGroupId newKey() {
        return new DisclosureGroupId("A000000000", "01", 5);
    }

    @Test
    @DisplayName("all-args constructor populates all three components")
    void allArgsConstructorPopulatesComponents() {
        DisclosureGroupId id = newKey();
        assertThat(id.getAccountGroupId()).isEqualTo("A000000000");
        assertThat(id.getTransactionTypeCode()).isEqualTo("01");
        assertThat(id.getTransactionCategoryCode()).isEqualTo(5);
    }

    @Test
    @DisplayName("the literal DEFAULT fallback group is a valid key component")
    void defaultFallbackGroupSupported() {
        DisclosureGroupId id = new DisclosureGroupId("DEFAULT", "01", 5);
        assertThat(id.getAccountGroupId()).isEqualTo("DEFAULT");
        assertThat(id).isNotEqualTo(newKey());
    }

    @Test
    @DisplayName("equality is reflexive, symmetric and consistent with hashCode")
    void equalityReflexiveSymmetricHashCode() {
        DisclosureGroupId a = newKey();
        DisclosureGroupId b = newKey();
        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("differing any single component breaks equality")
    void differingAnyComponentBreaksEquality() {
        DisclosureGroupId base = newKey();
        assertThat(base).isNotEqualTo(new DisclosureGroupId("B000000000", "01", 5));
        assertThat(base).isNotEqualTo(new DisclosureGroupId("A000000000", "99", 5));
        assertThat(base).isNotEqualTo(new DisclosureGroupId("A000000000", "01", 99));
    }

    @Test
    @DisplayName("equals(null) and equals(other type) are false")
    void notEqualToNullOrOtherType() {
        DisclosureGroupId base = newKey();
        assertThat(base.equals(null)).isFalse();
        assertThat(base.equals("not-a-key")).isFalse();
    }
}
