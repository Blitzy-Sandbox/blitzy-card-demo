package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA composite primary-key class for the {@code TransactionCategoryType} entity.
 *
 * <p>This {@link Embeddable} type is the faithful Java translation of the COBOL
 * {@code TRAN-CAT-KEY} group item defined inside the record {@code TRAN-CAT-RECORD}
 * of copybook {@code app/cpy/CVTRA04Y.cpy} (record length 60 bytes, source commit
 * SHA {@code 27d6c6f}). It is intended to be referenced as the
 * {@code @EmbeddedId} of {@code TransactionCategoryType} and as the identifier
 * type of {@code JpaRepository<TransactionCategoryType, TransactionCategoryTypeId>}.
 *
 * <p><strong>COBOL origin (leading 6 bytes of the 60-byte record):</strong>
 * <pre>
 *   05  TRAN-CAT-KEY.
 *       10  TRAN-TYPE-CD    PIC X(02).   &lt;- alphanumeric, 2 bytes  -&gt; {@link #tranTypeCd}
 *       10  TRAN-CAT-CD     PIC 9(04).   &lt;- unsigned numeric, 4 bytes -&gt; {@link #tranCatCd}
 * </pre>
 * The remaining bytes of {@code TRAN-CAT-RECORD} ({@code TRAN-CAT-TYPE-DESC PIC X(50)}
 * and {@code FILLER PIC X(04)}) are non-key data and are modelled on the owning
 * entity, not here.
 *
 * <p><strong>Mapping rules honored:</strong>
 * <ul>
 *   <li>{@code PIC X(02)} maps to a {@link String} persisted as {@code VARCHAR(2)}
 *       (column {@code tran_type_cd}).</li>
 *   <li>{@code PIC 9(04)} maps to an {@link Integer} persisted as {@code INTEGER}
 *       (column {@code tran_cat_cd}). Wrapper types are used so the key participates
 *       cleanly in {@code null}-safe equality and Hibernate identity handling.</li>
 *   <li>This is a natural (business) key: no {@code @GeneratedValue} is applied.</li>
 *   <li>No relationships or behavior are introduced (scope preservation); the type
 *       is a pure value object carrying only key state.</li>
 * </ul>
 *
 * <p><strong>Database contract</strong> (must match the owning table
 * {@code transaction_category_type} in the Flyway {@code V1__schema.sql} migration):
 * <pre>
 *   tran_type_cd  VARCHAR(2)  NOT NULL,
 *   tran_cat_cd   INTEGER     NOT NULL,
 *   PRIMARY KEY (tran_type_cd, tran_cat_cd)
 * </pre>
 *
 * <p>As a JPA composite key this class is {@link Serializable} and provides
 * value-based {@link #equals(Object)} and {@link #hashCode()} implementations that
 * cover both key fields, as mandated by the JPA specification for identifier types.
 */
@Embeddable
public class TransactionCategoryTypeId implements Serializable {

    /**
     * Serialization version identifier. Declared explicitly to satisfy the
     * zero-warning build requirement ({@code -Xlint:all}) for {@link Serializable}
     * types and to guarantee a stable serialized form across JVMs.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Transaction type code — COBOL {@code TRAN-TYPE-CD PIC X(02)}.
     *
     * <p>Alphanumeric, fixed width 2 characters; persisted as {@code VARCHAR(2)}
     * and required (non-null) because it is part of the primary key.
     */
    @Column(name = "tran_type_cd", length = 2, nullable = false)
    private String tranTypeCd;

    /**
     * Transaction category code — COBOL {@code TRAN-CAT-CD PIC 9(04)}.
     *
     * <p>Unsigned numeric, up to 4 digits; persisted as {@code INTEGER} and required
     * (non-null) because it is part of the primary key.
     */
    @Column(name = "tran_cat_cd", nullable = false)
    private Integer tranCatCd;

    /**
     * No-argument constructor required by the JPA specification for
     * {@code @Embeddable} identifier types.
     */
    public TransactionCategoryTypeId() {
        // Intentionally empty: JPA instantiates the key and populates fields reflectively.
    }

    /**
     * All-arguments constructor for programmatic key creation.
     *
     * <p>Fields are assigned directly (rather than through setters) to avoid any
     * {@code this}-escape during construction.
     *
     * @param tranTypeCd the transaction type code ({@code TRAN-TYPE-CD})
     * @param tranCatCd  the transaction category code ({@code TRAN-CAT-CD})
     */
    public TransactionCategoryTypeId(String tranTypeCd, Integer tranCatCd) {
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the transaction type code ({@code TRAN-TYPE-CD}).
     *
     * @return the transaction type code, or {@code null} if unset
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Sets the transaction type code ({@code TRAN-TYPE-CD}).
     *
     * @param tranTypeCd the transaction type code to set
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the transaction category code ({@code TRAN-CAT-CD}).
     *
     * @return the transaction category code, or {@code null} if unset
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Sets the transaction category code ({@code TRAN-CAT-CD}).
     *
     * @param tranCatCd the transaction category code to set
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Value-based equality covering both key fields, as required for a JPA
     * composite identifier. Two instances are equal when they are of the exact
     * same class and both {@code tranTypeCd} and {@code tranCatCd} are equal.
     *
     * @param o the object to compare with
     * @return {@code true} if the given object represents the same composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategoryTypeId that = (TransactionCategoryTypeId) o;
        return Objects.equals(tranTypeCd, that.tranTypeCd)
                && Objects.equals(tranCatCd, that.tranCatCd);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}, derived from both key
     * fields via {@link Objects#hash(Object...)}.
     *
     * @return the hash code for this composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }
}
