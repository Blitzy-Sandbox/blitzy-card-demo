/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity for the CardDemo {@code DIS-GROUP-RECORD} disclosure-group reference data.
 *
 * <p>Translated (never copied) from COBOL copybook {@code app/cpy/CVTRA02Y.cpy}
 * (DIS-GROUP-RECORD, record length 50) at source commit SHA {@code 27d6c6f}. The
 * record maps to the {@code disclosure_group} table defined in
 * {@code db/migration/V1__create_schema.sql}; the trailing COBOL
 * {@code FILLER PIC X(28)} carries no data and is intentionally not mapped.</p>
 *
 * <p>The three-part business key ({@code DIS-ACCT-GROUP-ID}, {@code DIS-TRAN-TYPE-CD},
 * {@code DIS-TRAN-CAT-CD}) is modelled by the {@link DisclosureGroupId} composite
 * key through {@link EmbeddedId}; those columns are declared once on the key class
 * and are never re-declared on this entity.</p>
 *
 * <p>{@link #disIntRate} originates from COBOL {@code DIS-INT-RATE PIC S9(04)V99}
 * and is modelled as a {@link BigDecimal} with precision 6 and scale 2 so the
 * disclosure interest rate is stored exactly; {@code double}/{@code float} are
 * never used. This rate feeds the batch interest formula
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (program {@code CBACT04C}); the
 * monetary rounding ({@link java.math.RoundingMode#HALF_EVEN}) is applied by the
 * batch processor, never by this entity, which only persists the value.</p>
 *
 * <p>Disclosure-group rows are immutable reference data, so the entity carries no
 * optimistic-locking {@code @Version} field and no JPA relationships.</p>
 */
@Entity
@Table(name = "disclosure_group")
public class DisclosureGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key spanning {@code DIS-ACCT-GROUP-ID PIC X(10)},
     * {@code DIS-TRAN-TYPE-CD PIC X(02)} and {@code DIS-TRAN-CAT-CD PIC 9(04)}.
     * The individual key columns ({@code acct_group_id}, {@code tran_type_cd},
     * {@code tran_cat_cd}) are mapped on {@link DisclosureGroupId}.
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * {@code DIS-INT-RATE PIC S9(04)V99} &rarr; {@code dis_int_rate NUMERIC(6,2)}.
     * The disclosure interest rate, stored exactly as a {@link BigDecimal}
     * (precision 6, scale 2). Nullable, matching the {@code V1} schema column.
     */
    @Column(name = "dis_int_rate", precision = 6, scale = 2)
    private BigDecimal disIntRate;

    /**
     * No-argument constructor required by the JPA specification.
     */
    public DisclosureGroup() {
        // Required by JPA; fields are populated via setters or the all-args constructor.
    }

    /**
     * Creates a fully populated disclosure-group row.
     *
     * @param id         the composite primary key (account group, transaction
     *                   type, and transaction category codes)
     * @param disIntRate the disclosure interest rate (DIS-INT-RATE)
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal disIntRate) {
        this.id = id;
        this.disIntRate = disIntRate;
    }

    /**
     * Returns the composite primary key.
     *
     * @return the composite primary key
     */
    public DisclosureGroupId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the composite primary key
     */
    public void setId(DisclosureGroupId id) {
        this.id = id;
    }

    /**
     * Returns the disclosure interest rate.
     *
     * @return the disclosure interest rate
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Sets the disclosure interest rate.
     *
     * @param disIntRate the disclosure interest rate
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    /**
     * Compares this disclosure group with another for equality on the composite
     * primary key only, consistent with JPA entity-identity semantics.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code DisclosureGroup} with an equal
     *         {@link #id}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DisclosureGroup that = (DisclosureGroup) o;
        return Objects.equals(id, that.id);
    }

    /**
     * Computes a hash code over the composite primary key only, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code derived from {@link #id}
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Returns a diagnostic string representation of this disclosure group.
     *
     * @return a string containing the composite key and the disclosure interest rate
     */
    @Override
    public String toString() {
        return "DisclosureGroup{"
                + "id=" + id
                + ", disIntRate=" + disIntRate
                + '}';
    }
}
