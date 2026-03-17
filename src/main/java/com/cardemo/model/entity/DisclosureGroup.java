package com.cardemo.model.entity;

import com.cardemo.model.key.DisclosureGroupId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL {@code DIS-GROUP-RECORD} (50 bytes) from
 * {@code app/cpy/CVTRA02Y.cpy} to the PostgreSQL {@code dis_group} table.
 *
 * <p>This entity stores interest rate schedules used during the batch interest
 * calculation process (CBACT04C.cbl). Each record maps a combination of
 * account group ID, transaction type code, and transaction category code to
 * an interest rate. The composite key consists of three fields that together
 * form the COBOL {@code DIS-GROUP-KEY}.</p>
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
 *   <li><strong>Interest Calculation (CBACT04C.cbl):</strong> For each account's
 *       category balance, the batch processor looks up the interest rate using the
 *       account's group ID + transaction type code + category code. If no specific
 *       group rate exists (FILE STATUS '23'), the system falls back to the
 *       {@code "DEFAULT   "} group ID (10 chars, space-padded). This DEFAULT
 *       fallback pattern is critical for interest rate resolution.</li>
 *   <li><strong>Interest Formula:</strong>
 *       {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200} with
 *       {@code RoundingMode.HALF_EVEN} (banker's rounding, matching COBOL default).
 *       Division by 1200 converts the annual percentage rate to a monthly rate.</li>
 *   <li><strong>Seed Data Groups:</strong> Three group categories exist: account-specific
 *       (e.g., {@code "A000000000"}), default fallback ({@code "DEFAULT   "}), and
 *       zero-rate promotional ({@code "ZEROAPR   "}).</li>
 * </ul>
 *
 * <h3>Decimal Precision</h3>
 * <p>The {@code disIntRate} field uses {@link BigDecimal} with precision=6, scale=2,
 * exactly matching the COBOL {@code PIC S9(04)V99} specification (signed, 4 integer
 * digits, 2 decimal places). Zero float/double substitution per AAP decimal precision
 * rules (§0.8.2).</p>
 *
 * <p>COBOL source reference: {@code app/cpy/CVTRA02Y.cpy} from commit {@code 27d6c6f}.</p>
 *
 * @see DisclosureGroupId
 */
@Entity
@Table(name = "dis_group")
public class DisclosureGroup {

    /**
     * Composite primary key consisting of group ID, transaction type code,
     * and transaction category code.
     *
     * <p>Maps the COBOL group-level key {@code DIS-GROUP-KEY} which combines
     * {@code DIS-ACCT-GROUP-ID PIC X(10)}, {@code DIS-TRAN-TYPE-CD PIC X(02)},
     * and {@code DIS-TRAN-CAT-CD PIC 9(04)}.</p>
     *
     * <p>The composite key supports the DEFAULT group fallback pattern: when
     * a specific group ID is not found during interest calculation, the system
     * retries with {@code groupId = "DEFAULT   "} (10 characters, space-padded).</p>
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Interest rate for this disclosure group combination.
     *
     * <p>Maps COBOL {@code DIS-INT-RATE PIC S9(04)V99}. The signed numeric
     * field with 4 integer digits and 2 decimal places is stored as
     * {@link BigDecimal} with precision 6 (4+2) and scale 2, matching the
     * DDL column {@code NUMERIC(6,2)}.</p>
     *
     * <p>This rate is used in the interest calculation formula:
     * {@code (balance × disIntRate) / 1200}. The division by 1200 converts
     * the annual percentage rate to a monthly rate. All arithmetic uses
     * {@code BigDecimal} operations with {@code RoundingMode.HALF_EVEN}
     * to preserve COBOL banker's rounding semantics.</p>
     *
     * <p>Example values from seed data: 18.50 (standard purchase rate),
     * 24.99 (cash advance rate), 0.00 (ZEROAPR promotional rate).</p>
     *
     * <p>COBOL FILLER PIC X(28) from the original 50-byte record is NOT
     * mapped — it represents padding only.</p>
     */
    @Column(name = "dis_int_rate", precision = 6, scale = 2)
    private BigDecimal disIntRate;

    /**
     * No-argument constructor required by the JPA specification.
     *
     * <p>This constructor is used by the JPA provider (Hibernate) when
     * materializing entity instances from database query results during
     * disclosure group lookups, including the DEFAULT fallback pattern.</p>
     */
    public DisclosureGroup() {
        // JPA-required default constructor
    }

    /**
     * All-argument constructor for programmatic entity creation.
     *
     * <p>Used during seed data loading (Flyway V3 migration) and in test
     * fixtures for interest calculation verification.</p>
     *
     * @param id         the composite primary key containing group ID, type code,
     *                   and category code; must not be {@code null}
     * @param disIntRate the interest rate as {@link BigDecimal} with scale 2;
     *                   matches COBOL {@code PIC S9(04)V99}
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal disIntRate) {
        this.id = id;
        this.disIntRate = disIntRate;
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
     * Returns the interest rate for this disclosure group combination.
     *
     * <p>The returned {@link BigDecimal} has scale 2, matching the COBOL
     * {@code PIC S9(04)V99} specification. Use this value in the interest
     * calculation formula: {@code (balance × disIntRate) / 1200}.</p>
     *
     * @return the interest rate as {@link BigDecimal}; may be {@code null}
     *         for unpersisted entities
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Sets the interest rate for this disclosure group combination.
     *
     * <p>The value must be a {@link BigDecimal} with scale 2 to match the
     * COBOL {@code PIC S9(04)V99} specification. Do NOT use {@code float}
     * or {@code double} — zero floating-point substitution is enforced.</p>
     *
     * @param disIntRate the interest rate to set; must use {@link BigDecimal}
     *                   for precision preservation
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Compares this disclosure group entity to another object for equality.
     *
     * <p>Two {@code DisclosureGroup} instances are equal if and only if they
     * have the same composite primary key ({@code id}) value. This is critical
     * for JPA entity identity resolution, Hibernate first-level cache lookups,
     * and correct behavior in collections.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the given object represents the same disclosure
     *         group record; {@code false} otherwise
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
     * <p>Consistent with {@link #equals(Object)} — derives the hash
     * exclusively from the embedded {@code id} field.</p>
     *
     * @return hash code derived from the embedded composite ID
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Returns a human-readable string representation of this disclosure group record.
     *
     * <p>Includes the composite key and interest rate for diagnostic logging
     * and debugging purposes.</p>
     *
     * @return a formatted string containing the composite key and interest rate
     */
    @Override
    public String toString() {
        return "DisclosureGroup{"
                + "id=" + id
                + ", disIntRate=" + disIntRate
                + '}';
    }
}
