package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.DisclosureGroup;
import com.carddemo.model.key.DisclosureGroupId;
import com.carddemo.repository.DisclosureGroupRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link DisclosureGroupRepository} against a real PostgreSQL 16 Testcontainer.
 * Verifies the VSAM DISCGRP store re-platforms to PostgreSQL with a 3-part composite key, exact
 * scale-2 interest rates, right-trimmed group ids, and the DEFAULT-group fallback semantics that the
 * interest-calculation service relies on (CBACT04C 1200-GET-INTEREST-RATE).
 */
class DisclosureGroupRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Test
    void seedDataLoadsAllDisclosureGroups() {
        assertThat(disclosureGroupRepository.count()).isEqualTo(51L);
        assertThat(disclosureGroupRepository.findAll()).hasSize(51);
    }

    @Test
    void accountGroupInterestRateIsFifteenPercent() {
        Optional<DisclosureGroup> found =
                disclosureGroupRepository.findById(new DisclosureGroupId("A000000000", "01", 1));

        assertThat(found).isPresent();
        assertThat(found.get().getDisIntRate()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(found.get().getDisIntRate().scale()).isEqualTo(2);
    }

    @Test
    void defaultGroupExistsAndIsStoredRightTrimmed() {
        Optional<DisclosureGroup> found =
                disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "01", 1));

        assertThat(found).isPresent();
        assertThat(found.get().getId().getAccountGroupId()).isEqualTo("DEFAULT");
        assertThat(found.get().getDisIntRate()).isEqualByComparingTo(new BigDecimal("15.00"));
    }

    @Test
    void zeroAprGroupHasZeroInterestRate() {
        Optional<DisclosureGroup> found =
                disclosureGroupRepository.findById(new DisclosureGroupId("ZEROAPR", "01", 1));

        assertThat(found).isPresent();
        assertThat(found.get().getDisIntRate()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void unknownGroupFallsBackToDefaultGroup() {
        DisclosureGroupId ownGroup = new DisclosureGroupId("ZZZZZZZZZZ", "01", 1);
        Optional<DisclosureGroup> direct = disclosureGroupRepository.findById(ownGroup);
        assertThat(direct).isEmpty();

        DisclosureGroupId defaultGroup = new DisclosureGroupId(
                "DEFAULT", ownGroup.getTransactionTypeCode(), ownGroup.getTransactionCategoryCode());
        Optional<DisclosureGroup> viaDefault = disclosureGroupRepository.findById(defaultGroup);

        assertThat(viaDefault).isPresent();
        assertThat(viaDefault.get().getDisIntRate()).isNotNull();
    }

    @Test
    void everyDisclosureGroupHasPopulatedThreePartKeyAndRate() {
        for (DisclosureGroup group : disclosureGroupRepository.findAll()) {
            assertThat(group.getId()).isNotNull();
            assertThat(group.getId().getAccountGroupId()).isNotNull();
            assertThat(group.getId().getTransactionTypeCode()).isNotNull();
            assertThat(group.getId().getTransactionCategoryCode()).isNotNull();
            assertThat(group.getDisIntRate()).isNotNull();
        }
    }
}
