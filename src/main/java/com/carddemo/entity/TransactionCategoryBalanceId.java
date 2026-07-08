package com.carddemo.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * JPA composite primary-key type for the {@code TransactionCategoryBalance} entity.
 *
 * <p>This class is the Java translation of the COBOL {@code TRAN-CAT-KEY} group item
 * declared inside copybook {@code app/cpy/CVTRA01Y.cpy} (record {@code TRAN-CAT-BAL-RECORD},
 * record length 50, source commit {@code 27d6c6f}). The group item occupies the leading
 * 17 bytes of the fixed-width record:</p>
 *
 * <pre>
 *   05  TRAN-CAT-KEY.
 *       10 TRANCAT-ACCT-ID   PIC 9(11).   (11 bytes)
 *       10 TRANCAT-TYPE-CD   PIC X(02).   ( 2 bytes)
 *       10 TRANCAT-CD        PIC 9(04).   ( 4 bytes)
 * </pre>
 *
 * <p>It is used as the {@code @EmbeddedId} of {@code TransactionCategoryBalance} and as the
 * identifier type of {@code JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId>}.
 * The three fields map to the composite primary key of the {@code transaction_category_balance}
 * table:</p>
 *
 * <pre>
 *   PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
 * </pre>
 *
 * <p>All fields use nullable wrapper types so that a partially populated key remains
 * null-safe rather than defaulting to a spurious zero/empty value. The key is a natural,
 * application-assigned key (no generation strategy). Column names and types are aligned with
 * the Flyway {@code V1__schema.sql} contract; because Hibernate runs with
 * {@code ddl-auto: validate}, this class describes but does not drive the schema.</p>
 *
 * <p>As required for any JPA composite key, the class is {@link Serializable} and provides a
 * value-based {@link #equals(Object)} / {@link #hashCode()} pair covering every key field.</p>
 */
@Embeddable
public class TransactionCategoryBalanceId implements Serializable {

    /**
     * Serialization version identifier. Declared explicitly (rather than relying on a
     * compiler-computed value) to satisfy the JPA composite-key contract and to keep the
     * build warning-free under {@code -Xlint:all} (the {@code serial} category).
     */
    private static final long serialVersionUID = 1L;

    /**
     * Account identifier component of the key.
     * <p>Maps COBOL {@code TRANCAT-ACCT-ID PIC 9(11)} to a {@code BIGINT} column.</p>
     */
    @Column(name = "trancat_acct_id", nullable = false)
    private Long trancatAcctId;

    /**
     * Transaction type-code component of the key.
     * <p>Maps COBOL {@code TRANCAT-TYPE-CD PIC X(02)} to a {@code VARCHAR(2)} column.</p>
     */
    @Column(name = "trancat_type_cd", length = 2, nullable = false)
    private String trancatTypeCd;

    /**
     * Transaction category-code component of the key.
     * <p>Maps COBOL {@code TRANCAT-CD PIC 9(04)} to an {@code INTEGER} column.</p>
     */
    @Column(name = "trancat_cd", nullable = false)
    private Integer trancatCd;

    /**
     * No-argument constructor required by the JPA specification for embeddable key types.
     */
    public TransactionCategoryBalanceId() {
        // Intentionally empty: JPA instantiates the key via this constructor and then
        // populates each field through its setter / field access.
    }

    /**
     * All-arguments constructor for programmatic key creation (e.g. repository lookups).
     *
     * <p>Fields are assigned directly rather than through setters so the constructor never
     * exposes {@code this} to an overridable method, keeping the build free of the
     * {@code this-escape} lint warning.</p>
     *
     * @param trancatAcctId the account identifier (COBOL {@code TRANCAT-ACCT-ID})
     * @param trancatTypeCd the transaction type code (COBOL {@code TRANCAT-TYPE-CD})
     * @param trancatCd     the transaction category code (COBOL {@code TRANCAT-CD})
     */
    public TransactionCategoryBalanceId(Long trancatAcctId, String trancatTypeCd, Integer trancatCd) {
        this.trancatAcctId = trancatAcctId;
        this.trancatTypeCd = trancatTypeCd;
        this.trancatCd = trancatCd;
    }

    /**
     * Returns the account-identifier key component.
     *
     * @return the account identifier, or {@code null} if unset
     */
    public Long getTrancatAcctId() {
        return trancatAcctId;
    }

    /**
     * Sets the account-identifier key component.
     *
     * @param trancatAcctId the account identifier to set
     */
    public void setTrancatAcctId(Long trancatAcctId) {
        this.trancatAcctId = trancatAcctId;
    }

    /**
     * Returns the transaction type-code key component.
     *
     * @return the transaction type code, or {@code null} if unset
     */
    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    /**
     * Sets the transaction type-code key component.
     *
     * @param trancatTypeCd the transaction type code to set
     */
    public void setTrancatTypeCd(String trancatTypeCd) {
        this.trancatTypeCd = trancatTypeCd;
    }

    /**
     * Returns the transaction category-code key component.
     *
     * @return the transaction category code, or {@code null} if unset
     */
    public Integer getTrancatCd() {
        return trancatCd;
    }

    /**
     * Sets the transaction category-code key component.
     *
     * @param trancatCd the transaction category code to set
     */
    public void setTrancatCd(Integer trancatCd) {
        this.trancatCd = trancatCd;
    }

    /**
     * Value-based equality across all three key components, as mandated for a JPA composite key.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code TransactionCategoryBalanceId} whose
     *         account id, type code, and category code all equal this key's
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) o;
        return Objects.equals(trancatAcctId, that.trancatAcctId)
                && Objects.equals(trancatTypeCd, that.trancatTypeCd)
                && Objects.equals(trancatCd, that.trancatCd);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}, derived from all three key components.
     *
     * @return the hash code for this key
     */
    @Override
    public int hashCode() {
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }

    /**
     * Diagnostic string representation of the key components. Provided for logging only;
     * it carries no business behavior.
     *
     * @return a human-readable rendering of the three key fields
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalanceId{"
                + "trancatAcctId=" + trancatAcctId
                + ", trancatTypeCd='" + trancatTypeCd + '\''
                + ", trancatCd=" + trancatCd
                + '}';
    }
}
