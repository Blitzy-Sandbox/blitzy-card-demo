package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA composite primary-key class for the {@code disclosure_group} table.
 *
 * <p>This {@link Embeddable} type is the migrated Java representation of the COBOL
 * {@code DIS-GROUP-KEY} group item declared inside copybook {@code app/cpy/CVTRA02Y.cpy}
 * (record {@code DIS-GROUP-RECORD}, fixed length {@code RECLN = 50}, source commit SHA
 * {@code 27d6c6f}). It is intended to be referenced as the {@code @EmbeddedId} of the
 * owning {@code DisclosureGroup} entity and as the identifier type of
 * {@code JpaRepository<DisclosureGroup, DisclosureGroupId>}.</p>
 *
 * <h2>COBOL source mapping</h2>
 * <p>The original 50-byte {@code DIS-GROUP-RECORD} layout is:</p>
 * <pre>
 *   01  DIS-GROUP-RECORD.
 *       05  DIS-GROUP-KEY.
 *          10 DIS-ACCT-GROUP-ID   PIC X(10).   &lt;- bytes 1-10  (this key)
 *          10 DIS-TRAN-TYPE-CD    PIC X(02).   &lt;- bytes 11-12 (this key)
 *          10 DIS-TRAN-CAT-CD     PIC 9(04).   &lt;- bytes 13-16 (this key)
 *       05  DIS-INT-RATE          PIC S9(04)V99.  (belongs to DisclosureGroup entity)
 *       05  FILLER                PIC X(28).
 * </pre>
 * <p>The three fields below reproduce {@code DIS-GROUP-KEY} exactly and occupy the
 * leading 16 bytes of the record ({@code 10 + 2 + 4}); the remaining
 * {@code DIS-INT-RATE} (6 bytes) and {@code FILLER} (28 bytes) are non-key data owned
 * by the {@code DisclosureGroup} entity. Byte accounting: {@code 10 + 2 + 4 + 6 + 28 = 50}.</p>
 *
 * <h2>Type-mapping rationale</h2>
 * <ul>
 *   <li>{@code PIC X(10)} &rarr; {@link String} column {@code VARCHAR(10)}.</li>
 *   <li>{@code PIC X(02)} &rarr; {@link String} column {@code VARCHAR(2)}.</li>
 *   <li>{@code PIC 9(04)} &rarr; {@link Integer} column {@code INTEGER}.</li>
 * </ul>
 * <p>Wrapper types (never primitives) are used so that an unset key component is
 * distinguishable as {@code null}, matching JPA composite-key expectations and the
 * {@code ddl-auto: validate} schema owned by the Flyway {@code V1__schema.sql} migration.</p>
 *
 * <p>This is a natural (business) key: there is no {@code @GeneratedValue} and the class
 * carries no relationships or behavior beyond identity semantics. As mandated for a JPA
 * composite key, {@link #equals(Object)} and {@link #hashCode()} are overridden and cover
 * all three key components.</p>
 */
@Embeddable
public class DisclosureGroupId implements Serializable {

    /**
     * Serialization version identifier. Required because a JPA composite-key class is
     * {@link Serializable}; a fixed value keeps the zero-warning build ({@code -Xlint:all})
     * free of {@code serial} warnings.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Account group identifier — COBOL {@code DIS-ACCT-GROUP-ID PIC X(10)}.
     * First component of {@code DIS-GROUP-KEY} (record bytes 1-10).
     */
    @Column(name = "dis_acct_group_id", length = 10, nullable = false)
    private String disAcctGroupId;

    /**
     * Transaction type code — COBOL {@code DIS-TRAN-TYPE-CD PIC X(02)}.
     * Second component of {@code DIS-GROUP-KEY} (record bytes 11-12).
     */
    @Column(name = "dis_tran_type_cd", length = 2, nullable = false)
    private String disTranTypeCd;

    /**
     * Transaction category code — COBOL {@code DIS-TRAN-CAT-CD PIC 9(04)}.
     * Third component of {@code DIS-GROUP-KEY} (record bytes 13-16). Stored as an
     * {@code INTEGER} column; the numeric picture carries no scale.
     */
    @Column(name = "dis_tran_cat_cd", nullable = false)
    private Integer disTranCatCd;

    /**
     * No-argument constructor required by the JPA specification for embeddable types.
     */
    public DisclosureGroupId() {
        // Intentionally empty: JPA instantiates the key and populates it via field access.
    }

    /**
     * All-arguments constructor mirroring the COBOL {@code DIS-GROUP-KEY} field order.
     *
     * <p>Fields are assigned directly (not through setters) so the constructor does not
     * invoke overridable methods, keeping the class free of {@code this-escape} warnings
     * under {@code -Xlint:all}.</p>
     *
     * @param disAcctGroupId the account group identifier ({@code DIS-ACCT-GROUP-ID})
     * @param disTranTypeCd  the transaction type code ({@code DIS-TRAN-TYPE-CD})
     * @param disTranCatCd   the transaction category code ({@code DIS-TRAN-CAT-CD})
     */
    public DisclosureGroupId(String disAcctGroupId, String disTranTypeCd, Integer disTranCatCd) {
        this.disAcctGroupId = disAcctGroupId;
        this.disTranTypeCd = disTranTypeCd;
        this.disTranCatCd = disTranCatCd;
    }

    /**
     * Returns the account group identifier ({@code DIS-ACCT-GROUP-ID}).
     *
     * @return the account group identifier, or {@code null} if unset
     */
    public String getDisAcctGroupId() {
        return disAcctGroupId;
    }

    /**
     * Sets the account group identifier ({@code DIS-ACCT-GROUP-ID}).
     *
     * @param disAcctGroupId the account group identifier to set
     */
    public void setDisAcctGroupId(String disAcctGroupId) {
        this.disAcctGroupId = disAcctGroupId;
    }

    /**
     * Returns the transaction type code ({@code DIS-TRAN-TYPE-CD}).
     *
     * @return the transaction type code, or {@code null} if unset
     */
    public String getDisTranTypeCd() {
        return disTranTypeCd;
    }

    /**
     * Sets the transaction type code ({@code DIS-TRAN-TYPE-CD}).
     *
     * @param disTranTypeCd the transaction type code to set
     */
    public void setDisTranTypeCd(String disTranTypeCd) {
        this.disTranTypeCd = disTranTypeCd;
    }

    /**
     * Returns the transaction category code ({@code DIS-TRAN-CAT-CD}).
     *
     * @return the transaction category code, or {@code null} if unset
     */
    public Integer getDisTranCatCd() {
        return disTranCatCd;
    }

    /**
     * Sets the transaction category code ({@code DIS-TRAN-CAT-CD}).
     *
     * @param disTranCatCd the transaction category code to set
     */
    public void setDisTranCatCd(Integer disTranCatCd) {
        this.disTranCatCd = disTranCatCd;
    }

    /**
     * Compares this composite key with another for value equality across all three key
     * components, as required for a JPA {@code @EmbeddedId}.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code DisclosureGroupId} with equal
     *         {@code disAcctGroupId}, {@code disTranTypeCd}, and {@code disTranCatCd}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DisclosureGroupId that = (DisclosureGroupId) o;
        return Objects.equals(disAcctGroupId, that.disAcctGroupId)
                && Objects.equals(disTranTypeCd, that.disTranTypeCd)
                && Objects.equals(disTranCatCd, that.disTranCatCd);
    }

    /**
     * Computes a hash code from all three key components, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }
}
