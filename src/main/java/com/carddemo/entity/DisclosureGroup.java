package com.carddemo.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * JPA persistent entity for an interest <strong>disclosure group</strong>.
 *
 * <p>This class is a field-by-field translation of the legacy COBOL copybook
 * {@code app/cpy/CVTRA02Y.cpy} ({@code DIS-GROUP-RECORD}, fixed record length
 * <strong>50</strong> bytes, source commit SHA {@code 27d6c6f}
 * (CardDemo v1.0-15-g27d6c6f-68)). A disclosure group associates a single
 * interest rate with a specific account-group / transaction-type / transaction-category
 * combination and is consumed by the interest-calculation batch job (INTCALC /
 * {@code CBACT04C}) to derive per-category finance charges.</p>
 *
 * <p>Original COBOL record layout, preserved verbatim:</p>
 * <pre>
 * 01  DIS-GROUP-RECORD.                             (RECLN = 50)
 *     05  DIS-GROUP-KEY.
 *        10 DIS-ACCT-GROUP-ID   PIC X(10).   &lt;- bytes 1-10  (composite key)
 *        10 DIS-TRAN-TYPE-CD    PIC X(02).   &lt;- bytes 11-12 (composite key)
 *        10 DIS-TRAN-CAT-CD     PIC 9(04).   &lt;- bytes 13-16 (composite key)
 *     05  DIS-INT-RATE          PIC S9(04)V99.  &lt;- bytes 17-22 (this entity)
 *     05  FILLER                PIC X(28).   &lt;- bytes 23-50 (not mapped)
 * </pre>
 *
 * <h2>Composite primary key</h2>
 * <p>The three-part {@code DIS-GROUP-KEY} group item is modeled as the
 * {@link jakarta.persistence.EmbeddedId @EmbeddedId} {@link #id} of type
 * {@link DisclosureGroupId} (account-group id, transaction-type code, and
 * transaction-category code). The individual key columns are declared once on
 * {@code DisclosureGroupId} and are deliberately <em>not</em> redeclared here.
 * The companion repository therefore declares
 * {@code JpaRepository<DisclosureGroup, DisclosureGroupId>}.</p>
 *
 * <h2>Decimal fidelity</h2>
 * <p>The single non-key data field {@code DIS-INT-RATE} is declared
 * {@code PIC S9(04)V99} and is mapped to {@link java.math.BigDecimal} with
 * {@code precision = 6, scale = 2} (SQL {@code NUMERIC(6,2)}). Note the precision
 * is {@code 6} (four integer digits plus two fractional digits), which is smaller
 * than the account monetary fields; the signed picture is preserved so negative
 * and positive rates round-trip exactly. Floating-point types
 * ({@code float}/{@code double}) are never used for this financial value,
 * honoring the migration decimal-precision rules (Gate 2, &sect;0.8.2).</p>
 *
 * <h2>No optimistic locking</h2>
 * <p>Unlike {@code Account} and {@code Card}, this reference/lookup record is not
 * subject to the read-then-rewrite concurrency pattern, so it intentionally
 * carries no {@link jakarta.persistence.Version @Version} column.</p>
 *
 * <h2>Schema contract</h2>
 * <p>This entity, together with {@link DisclosureGroupId}, is the schema source of
 * truth for the {@code disclosure_group} table. The Flyway migration
 * {@code db/migration/V1__schema.sql} must match these mappings exactly (column
 * names, types, precision/scale, and lengths) because
 * {@code spring.jpa.hibernate.ddl-auto: validate} verifies them at startup:</p>
 * <pre>
 * CREATE TABLE disclosure_group (
 *     dis_acct_group_id VARCHAR(10) NOT NULL,
 *     dis_tran_type_cd  VARCHAR(2)  NOT NULL,
 *     dis_tran_cat_cd   INTEGER     NOT NULL,
 *     dis_int_rate      NUMERIC(6,2),
 *     PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
 * );
 * </pre>
 *
 * <p>The trailing 28-byte {@code FILLER} carries no business data and is
 * intentionally not mapped; trailing padding is handled at the file-I/O boundary.
 * Byte accounting: {@code 10 + 2 + 4 + 6 + 28 = 50}.</p>
 */
@Entity
@Table(name = "disclosure_group")
public class DisclosureGroup {

    /**
     * Composite primary key — COBOL {@code DIS-GROUP-KEY} (record bytes 1-16).
     *
     * <p>Wraps the three natural key components ({@code DIS-ACCT-GROUP-ID},
     * {@code DIS-TRAN-TYPE-CD}, {@code DIS-TRAN-CAT-CD}) as an
     * {@link jakarta.persistence.EmbeddedId @EmbeddedId}. The key is
     * application-assigned; there is no {@code @GeneratedValue}.</p>
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Interest rate for this disclosure group — COBOL
     * {@code DIS-INT-RATE PIC S9(04)V99} (record bytes 17-22).
     *
     * <p>Mapped to a signed {@code NUMERIC(6,2)} value ({@code precision = 6},
     * {@code scale = 2}); never a floating-point type, preserving exact signed
     * decimal semantics.</p>
     */
    @Column(name = "dis_int_rate", precision = 6, scale = 2)
    private BigDecimal disIntRate;

    /**
     * Default no-argument constructor required by the JPA specification for
     * entity instantiation via reflection.
     */
    public DisclosureGroup() {
        // Intentionally empty: JPA requires a public/protected no-arg constructor.
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
     * @return the interest rate as a signed {@link BigDecimal} of scale 2, or
     *         {@code null} if unset
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Sets the interest rate ({@code DIS-INT-RATE}).
     *
     * @param disIntRate the interest rate to set (a signed {@link BigDecimal} of
     *                   scale 2)
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }
}
