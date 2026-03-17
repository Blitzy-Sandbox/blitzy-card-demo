package com.cardemo.model.entity;

import com.cardemo.model.key.DisclosureGroupId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL DISCLOSURE-GROUP-RECORD (50 bytes) from
 * CVTRA02Y.cpy to the PostgreSQL {@code disclosure_groups} table.
 *
 * <p>This entity stores interest rate schedules used during the batch interest
 * calculation process (CBACT04C.cbl). Each record maps a combination of
 * account group ID, transaction type code, and category code to an interest rate.</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <pre>
 * Source: app/cpy/CVTRA02Y.cpy (commit 27d6c6f)
 * Record Length: 50 bytes
 * VSAM Dataset: DISCGRP (KSDS, keyed on DIS-GROUP-KEY)
 *
 *   01  DIS-GROUP-RECORD.
 *       05  DIS-GROUP-KEY.
 *           10  DIS-ACCT-GROUP-ID   PIC X(10).
 *           10  DIS-TRAN-TYPE-CD    PIC X(02).
 *           10  DIS-TRAN-CAT-CD     PIC 9(04).
 *       05  DIS-INT-RATE            PIC S9(04)V99.
 *       05  FILLER                  PIC X(28).
 * </pre>
 *
 * <h3>Key Usage Contexts</h3>
 * <ul>
 *   <li>CBACT04C.cbl — Interest calculation: for each account's category balance,
 *       looks up the interest rate using the account's group ID + type + category.
 *       If no specific group rate exists, falls back to the {@code "DEFAULT"} group.</li>
 *   <li>Interest formula: {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200} with
 *       {@code RoundingMode.HALF_EVEN} (banker's rounding)</li>
 *   <li>Three group categories in seed data: account-specific ({@code "A000000000"}),
 *       default fallback ({@code "DEFAULT"}), and zero-rate ({@code "ZEROAPR"})</li>
 * </ul>
 *
 * <h3>Decimal Precision</h3>
 * <p>The {@code intRate} field uses {@link BigDecimal} with precision=6, scale=2,
 * matching the COBOL PIC S9(04)V99 specification. Zero float/double substitution
 * per AAP decimal precision rules.</p>
 *
 * <p>COBOL source reference: {@code app/cpy/CVTRA02Y.cpy} from commit {@code 27d6c6f}.</p>
 *
 * @see DisclosureGroupId
 * @see TransactionCategoryBalance
 * @see com.cardemo.batch.processors.InterestCalculationProcessor
 */
@Entity
@Table(name = "disclosure_groups")
public class DisclosureGroup {

    /**
     * Composite primary key consisting of group ID, transaction type code,
     * and category code.
     *
     * <p>Maps the COBOL group-level key {@code DIS-GROUP-KEY} which combines
     * {@code DIS-ACCT-GROUP-ID PIC X(10)}, {@code DIS-TRAN-TYPE-CD PIC X(02)},
     * and {@code DIS-TRAN-CAT-CD PIC 9(04)}.</p>
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Interest rate for this disclosure group combination.
     *
     * <p>Maps COBOL {@code DIS-INT-RATE PIC S9(04)V99}. The signed numeric
     * field with 2 decimal places is stored as {@link BigDecimal} with
     * precision 6 and scale 2, matching the DDL {@code NUMERIC(6,2)}.</p>
     *
     * <p>This rate is used in the interest calculation formula:
     * {@code (balance × intRate) / 1200}. The division by 1200 converts
     * the annual percentage rate to a monthly rate.</p>
     *
     * <p>Example values from seed data: 18.50 (standard), 24.99 (cash advance),
     * 0.00 (ZEROAPR promotional).</p>
     */
    @Column(name = "int_rate", precision = 6, scale = 2)
    private BigDecimal intRate;

    /**
     * No-argument constructor required by JPA specification.
     *
     * <p>This constructor is used by the JPA provider (Hibernate) when
     * materializing entity instances from database query results.</p>
     */
    public DisclosureGroup() {
        // JPA-required default constructor
    }

    /**
     * All-argument constructor for programmatic entity creation.
     *
     * @param id      the composite primary key; must not be {@code null}
     * @param intRate the interest rate; must use {@link BigDecimal} for precision
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal intRate) {
        this.id = id;
        this.intRate = intRate;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the composite primary key.
     *
     * @return the composite key containing group ID, type code, and category code;
     *         never {@code null} for persisted entities
     */
    public DisclosureGroupId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the composite key to set; must not be {@code null}
     */
    public void setId(DisclosureGroupId id) {
        this.id = id;
    }

    /**
     * Returns the interest rate.
     *
     * @return the interest rate as {@link BigDecimal}, may be {@code null}
     */
    public BigDecimal getIntRate() {
        return intRate;
    }

    /**
     * Sets the interest rate.
     *
     * @param intRate the interest rate to set; must use {@link BigDecimal} for precision
     */
    public void setIntRate(BigDecimal intRate) {
        this.intRate = intRate;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Compares this disclosure group to another object for equality.
     *
     * <p>Two {@code DisclosureGroup} instances are equal if and only if
     * they have the same composite primary key {@code id} value.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if equal; {@code false} otherwise
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
     * Returns a hash code based on the composite primary key.
     *
     * @return hash code derived from the embedded ID
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Returns a string representation of this disclosure group record.
     *
     * @return a formatted string containing the composite key and interest rate
     */
    @Override
    public String toString() {
        return "DisclosureGroup{" +
                "id=" + id +
                ", intRate=" + intRate +
                '}';
    }
}
