package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

import com.cardemo.model.key.DisclosureGroupId;

/**
 * JPA entity mapping the legacy AWS CardDemo disclosure-group record onto the
 * PostgreSQL {@code disclosure_groups} table.
 *
 * <p>This entity is the Java&nbsp;25 / Spring Data JPA replacement for the VSAM
 * KSDS dataset {@code DISCGRP}, whose fixed 50-byte record layout is defined by
 * the COBOL copybook {@code app/cpy/CVTRA02Y.cpy}
 * ({@code 01 DIS-GROUP-RECORD}, record length&nbsp;50). {@code DISCGRP} holds
 * the <em>interest-rate disclosure groups</em>: each row carries the interest
 * rate that applies to one {@code (account-group, transaction-type,
 * transaction-category)} combination. On the mainframe this reference data was
 * read by the batch interest-calculation program {@code CBACT04C}, which looks
 * up the rate for a category balance &mdash; falling back to a {@code DEFAULT}
 * account-group when no specific group matches &mdash; and then computes
 * interest. It is therefore a decimal-critical entity: the stored rate must be
 * preserved to its exact two-decimal scale so that interest parity with the
 * mainframe is maintained.</p>
 *
 * <h2>Original COBOL layout (CVTRA02Y.cpy &mdash; RECLN 50)</h2>
 * <pre>{@code
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *         10  DIS-ACCT-GROUP-ID    PIC X(10).
 *         10  DIS-TRAN-TYPE-CD     PIC X(02).
 *         10  DIS-TRAN-CAT-CD      PIC 9(04).
 *     05  DIS-INT-RATE             PIC S9(04)V99.
 *     05  FILLER                   PIC X(28).
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA composite key.</strong> The
 *       physically keyed {@code DISCGRP} cluster was addressed by the contiguous
 *       byte string formed from the three {@code DIS-GROUP-KEY} sub-fields
 *       ({@code DIS-ACCT-GROUP-ID} + {@code DIS-TRAN-TYPE-CD} +
 *       {@code DIS-TRAN-CAT-CD}). That concatenated physical key is replaced by
 *       an explicit, typed JPA composite key: the {@code @EmbeddedId} {@link #id}
 *       of type {@link DisclosureGroupId}. The three key components are carried
 *       <strong>solely</strong> by that embedded id and are deliberately
 *       <strong>not</strong> redeclared as separate columns on this entity, so
 *       there is no duplication of the key fields. Keyed reads served by batch
 *       file control on the mainframe are replaced by a Spring Data
 *       {@code DisclosureGroupRepository}
 *       ({@code JpaRepository<DisclosureGroup, DisclosureGroupId>}); the
 *       {@code DEFAULT}-group fallback that {@code CBACT04C} performs is a
 *       service/batch lookup concern (a query by a {@code DEFAULT} group id),
 *       not an entity concern.</li>
 *   <li><strong>Signed packed/zoned decimal &rarr; {@link BigDecimal}.</strong>
 *       {@code DIS-INT-RATE PIC S9(04)V99} is the signed interest rate: four
 *       integer digits plus two fractional digits. It maps to a {@link BigDecimal}
 *       with {@code precision = 6, scale = 2} (4&nbsp;+&nbsp;2&nbsp;=&nbsp;6 total
 *       digits). <strong>No {@code float} or {@code double} is used</strong>
 *       (AAP §0.7.3): this rate feeds the interest formula
 *       {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200}, which {@code CBACT04C}
 *       evaluates with banker's rounding ({@link java.math.RoundingMode#HALF_EVEN}).
 *       That arithmetic &mdash; including the {@code / 1200} divisor, which is
 *       preserved without algebraic rearrangement (AAP §0.7.6) &mdash; lives in
 *       the batch processor; this entity's sole responsibility is to store the
 *       rate faithfully at scale&nbsp;2. Service-layer comparisons of the rate
 *       must use {@link BigDecimal#compareTo} (scale-insensitive) rather than
 *       {@link BigDecimal#equals} (scale-sensitive).</li>
 *   <li><strong>No optimistic-locking column.</strong> Per AAP §0.7.5, JPA
 *       {@code @Version} is applied <strong>only</strong> to {@code Account} and
 *       {@code Card} (the online read-update programs {@code COACTUPC} and
 *       {@code COCRDUPC}). The disclosure-group record is read-only reference
 *       data consumed by batch interest calculation, so this entity carries
 *       <strong>no</strong> {@code @Version} column.</li>
 *   <li><strong>No associations / no Bean Validation.</strong> Per the Minimal
 *       Change Clause this is a pure persistence type: it declares exactly the
 *       embedded key and the single interest-rate field, models no JPA
 *       associations (the account-group/type/category linkage is expressed only
 *       through the key components), and carries no Jakarta Bean Validation
 *       annotations (input validation lives in the request-DTO layer, AAP
 *       §0.4.2).</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(28)} is reserved padding that pads the record to its
 *       50-byte length ({@code 10 + 2 + 4 + 6 + 28 = 50}, counting the six digit
 *       positions of the signed rate); it carries no business data and is
 *       intentionally <strong>not</strong> mapped to a column. The 50-byte record
 *       length is documented here for external-contract reference only
 *       (AAP §0.7.2).</li>
 * </ul>
 *
 * <h2>Composite-key &amp; column-name contract</h2>
 * <p>The composite primary key is supplied verbatim by {@link DisclosureGroupId},
 * which declares the authoritative physical column names {@code group_id}
 * ({@code VARCHAR(10)}), {@code type_code} ({@code VARCHAR(2)}) and
 * {@code category_code} ({@code INTEGER}). This entity adds the single non-key
 * column {@code interest_rate} ({@code NUMERIC(6,2)}). These names and types are
 * authoritative for the data layer: the Flyway {@code V1__create_schema.sql}
 * {@code disclosure_groups} table must declare the three-part composite primary
 * key plus the {@code interest_rate} column exactly as named here, and the
 * {@code V3} seed (loaded from {@code app/data/ASCII/discgrp.txt}, 50-byte
 * records) must align with these names, types and scales.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see DisclosureGroupId
 */
@Entity
@Table(name = "disclosure_groups")
public class DisclosureGroup {

    /**
     * Composite primary key &mdash; the three-part
     * {@code (account-group id, transaction-type code, transaction-category code)}
     * identifier.
     *
     * <p>Migrated from the COBOL {@code DIS-GROUP-KEY} group
     * ({@code DIS-ACCT-GROUP-ID PIC X(10)} + {@code DIS-TRAN-TYPE-CD PIC X(02)} +
     * {@code DIS-TRAN-CAT-CD PIC 9(04)}). The three components are carried by the
     * embedded {@link DisclosureGroupId} and mapped to the {@code group_id},
     * {@code type_code} and {@code category_code} columns; they are deliberately
     * not redeclared here. This is the entity's sole identifier, so the
     * downstream repository is
     * {@code JpaRepository<DisclosureGroup, DisclosureGroupId>}.</p>
     */
    // DIS-GROUP-KEY group (acct-group X(10) + type X(02) + cat 9(04)) -> composite key @EmbeddedId
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Signed interest rate for this account-group/type/category combination.
     *
     * <p>Migrated from {@code DIS-INT-RATE PIC S9(04)V99}: four integer digits
     * plus two fractional digits, signed. It is mapped to a {@link BigDecimal}
     * with {@code precision = 6, scale = 2} so the exact decimal scale is
     * preserved. <strong>No {@code float}/{@code double}</strong> is used
     * (AAP §0.7.3); this is the rate consumed by interest calculation
     * ({@code CBACT04C}) in the formula
     * {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200} with
     * {@link java.math.RoundingMode#HALF_EVEN} rounding. The formula and the
     * {@code DEFAULT}-group fallback belong to the batch layer; this field only
     * stores the rate. Service-layer comparisons must use
     * {@link BigDecimal#compareTo} rather than {@link BigDecimal#equals}.</p>
     */
    // DIS-INT-RATE PIC S9(04)V99 -> signed interest rate -> BigDecimal(precision=6, scale=2) (NUMERIC(6,2))
    @Column(name = "interest_rate", precision = 6, scale = 2)
    private BigDecimal disIntRate;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate) to
     * instantiate the entity reflectively before populating its fields.
     */
    public DisclosureGroup() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Convenience constructor that fully populates the entity, useful for tests
     * and for seeding disclosure-group reference data programmatically.
     *
     * @param id         the composite key ({@code DIS-GROUP-KEY})
     * @param disIntRate the interest rate ({@code DIS-INT-RATE})
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal disIntRate) {
        this.id = id;
        this.disIntRate = disIntRate;
    }

    /**
     * Returns the composite primary key ({@code DIS-GROUP-KEY}).
     *
     * @return the composite key, or {@code null} if unset
     */
    public DisclosureGroupId getId() {
        return id;
    }

    /**
     * Sets the composite primary key ({@code DIS-GROUP-KEY}).
     *
     * @param id the composite key to set
     */
    public void setId(DisclosureGroupId id) {
        this.id = id;
    }

    /**
     * Returns the interest rate ({@code DIS-INT-RATE}).
     *
     * @return the interest rate, or {@code null} if unset
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Sets the interest rate ({@code DIS-INT-RATE}).
     *
     * @param disIntRate the interest rate to set (scale 2, no {@code float}/{@code double})
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    /**
     * Identity-based equality keyed on the composite primary key ({@link #id}).
     *
     * <p>Two {@code DisclosureGroup} instances are equal when they are of the
     * exact same class and share the same {@link #id}. The primary key alone
     * defines entity identity; the interest rate is deliberately excluded so
     * equality stays stable if the rate is updated. Exact-class comparison
     * (rather than {@code instanceof}) is used so a proxy/subclass is not treated
     * as equal to a different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code DisclosureGroup} with an equal
     *         composite key
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
     * Hash code derived solely from the composite key ({@link #id}), consistent
     * with {@link #equals(Object)}.
     *
     * @return the hash code of the composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Diagnostic representation including the composite key and the interest rate.
     *
     * @return a human-readable description of this disclosure-group row
     */
    @Override
    public String toString() {
        return "DisclosureGroup{"
                + "id=" + id
                + ", disIntRate=" + disIntRate
                + '}';
    }
}
