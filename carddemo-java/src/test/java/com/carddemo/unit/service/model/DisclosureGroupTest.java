package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.DisclosureGroup;
import com.carddemo.model.key.DisclosureGroupId;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DisclosureGroup} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVTRA02Y @EmbeddedId wiring and scale-2 disIntRate (S9(04)V99, compareTo) per AAP {@code §0.8.2}.
 */
@DisplayName("DisclosureGroup entity — CVTRA02Y embedded id, scale-2 interest rate")
class DisclosureGroupTest {

    @Test
    @DisplayName("embedded id and interest rate round-trip; rate preserves scale 2")
    void roundTripWithScaleTwoRate() {
        DisclosureGroupId id = new DisclosureGroupId("A000000000", "01", 5);
        DisclosureGroup grp = new DisclosureGroup();
        grp.setId(id);
        grp.setDisIntRate(new BigDecimal("19.99"));

        assertThat(grp.getId()).isEqualTo(id);
        assertThat(grp.getDisIntRate().scale()).isEqualTo(2);
        assertThat(grp.getDisIntRate()).isEqualByComparingTo("19.99");
    }

    @Test
    @DisplayName("the DEFAULT fallback group is a valid identity; equals(null)/other type are false")
    void identityByEmbeddedIdWithDefaultGroup() {
        DisclosureGroup def = new DisclosureGroup();
        def.setId(new DisclosureGroupId("DEFAULT", "01", 5));
        DisclosureGroup sameDef = new DisclosureGroup();
        sameDef.setId(new DisclosureGroupId("DEFAULT", "01", 5));
        DisclosureGroup other = new DisclosureGroup();
        other.setId(new DisclosureGroupId("A000000000", "01", 5));

        assertThat(def).isEqualTo(sameDef);
        assertThat(def).hasSameHashCodeAs(sameDef);
        assertThat(def).isNotEqualTo(other);
        assertThat(def.equals(null)).isFalse();
        assertThat(def.equals("nope")).isFalse();
    }
}
