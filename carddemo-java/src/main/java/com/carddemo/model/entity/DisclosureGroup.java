package com.carddemo.model.entity;

import com.carddemo.model.key.DisclosureGroupId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Disclosure group entity. JPA mapping of the COBOL DIS-GROUP-RECORD (copybook
 * CVTRA02Y, RECLN 50). Re-platforms the VSAM DISCGRP KSDS. Carries the interest
 * rate used by interest calculation (with DEFAULT group fallback). Composite key
 * is supplied by DisclosureGroupId.
 */
@Entity
@Table(name = "disclosure_group")
public class DisclosureGroup {

    @EmbeddedId
    private DisclosureGroupId id;

    @Column(name = "dis_int_rate", nullable = false, precision = 6, scale = 2)
    private BigDecimal disIntRate;

    public DisclosureGroup() {
    }

    public DisclosureGroupId getId() { return id; }
    public void setId(DisclosureGroupId id) { this.id = id; }
    public BigDecimal getDisIntRate() { return disIntRate; }
    public void setDisIntRate(BigDecimal disIntRate) { this.disIntRate = disIntRate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        DisclosureGroup that = (DisclosureGroup) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
