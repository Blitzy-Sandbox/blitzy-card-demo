package com.cardemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the {@code TransactionCategoryBalance} entity.
 *
 * <p>This class is a direct translation of the COBOL {@code TRAN-CAT-KEY} group-level
 * key definition from {@code app/cpy/CVTRA01Y.cpy} (lines 5–8) in the CardDemo
 * mainframe application (commit {@code 27d6c6f}).</p>
 *
 * <h3>Source COBOL Structure</h3>
 * <pre>{@code
 * 05  TRAN-CAT-KEY.
 *    10 TRANCAT-ACCT-ID     PIC 9(11).
 *    10 TRANCAT-TYPE-CD     PIC X(02).
 *    10 TRANCAT-CD          PIC 9(04).
 * }</pre>
 *
 * <p>The {@code acctId} and {@code typeCode} fields are mapped as {@code String}
 * to preserve COBOL byte-level semantics. The {@code catCode} field is mapped as
 * {@link Short} to match the DDL {@code SMALLINT} column type.</p>
 *
 * <p>This class is used with {@code @EmbeddedId} in the
 * {@code TransactionCategoryBalance} entity. JPA requires that composite key
 * classes implement {@link Serializable} and provide correct
 * {@link #equals(Object)} and {@link #hashCode()} implementations.</p>
 *
 * @see jakarta.persistence.EmbeddedId
 */
@Embeddable
public class TransactionCategoryBalanceId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Account identifier — maps from {@code TRANCAT-ACCT-ID PIC 9(11)}.
     *
     * <p>Stored as {@code String} (not {@code long} or {@code int}) to preserve
     * leading zeros that are significant in the original COBOL record layout.
     * For example, account {@code "00000000001"} must remain 11 characters.</p>
     */
    @Column(name = "acct_id", length = 11, nullable = false)
    private String acctId;

    /**
     * Transaction type code — maps from {@code TRANCAT-TYPE-CD PIC X(02)}.
     *
     * <p>Alphanumeric 2-character code identifying the transaction type.
     * {@code PIC X} indicates alphanumeric content, stored as-is.</p>
     */
    @Column(name = "type_cd", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction category code — maps from {@code TRANCAT-CD PIC 9(04)}.
     *
     * <p>Stored as {@link Short} to match the DDL {@code SMALLINT} column type.</p>
     */
    @Column(name = "cat_cd", nullable = false)
    private Short catCode;

    /**
     * No-args constructor required by the JPA specification for all
     * {@code @Embeddable} composite key classes.
     */
    public TransactionCategoryBalanceId() {
        // JPA requires a public no-args constructor
    }

    /**
     * All-args constructor for programmatic key construction.
     *
     * @param acctId   the 11-character account identifier (leading zeros preserved)
     * @param typeCode the 2-character transaction type code
     * @param catCode  the transaction category code as SMALLINT
     */
    public TransactionCategoryBalanceId(String acctId, String typeCode, Short catCode) {
        this.acctId = acctId;
        this.typeCode = typeCode;
        this.catCode = catCode;
    }

    /**
     * Returns the account identifier.
     *
     * @return the 11-character account ID, including leading zeros
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * Sets the account identifier.
     *
     * @param acctId the 11-character account ID (leading zeros preserved)
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return the 2-character type code
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction type code.
     *
     * @param typeCode the 2-character type code
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction category code.
     *
     * @return the category code as Short
     */
    public Short getCatCode() {
        return catCode;
    }

    /**
     * Sets the transaction category code.
     *
     * @param catCode the category code as SMALLINT
     */
    public void setCatCode(Short catCode) {
        this.catCode = catCode;
    }

    /**
     * Compares this composite key with another object for equality.
     *
     * <p>Two {@code TransactionCategoryBalanceId} instances are equal if and only
     * if all three key fields ({@code acctId}, {@code typeCode}, {@code catCode})
     * are equal. This is critical for JPA entity identity when using
     * {@code @EmbeddedId}.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if all three key fields match; {@code false} otherwise
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
        return Objects.equals(acctId, that.acctId)
                && Objects.equals(typeCode, that.typeCode)
                && Objects.equals(catCode, that.catCode);
    }

    /**
     * Returns a hash code computed from all three composite key fields.
     *
     * <p>Consistent with {@link #equals(Object)}: if two keys are equal,
     * they produce the same hash code.</p>
     *
     * @return the hash code for this composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId, typeCode, catCode);
    }

    /**
     * Returns a human-readable string representation of this composite key.
     *
     * @return a string in the form
     *         {@code TransactionCategoryBalanceId{acctId='...', typeCode='...', catCode=...}}
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalanceId{"
                + "acctId='" + acctId + '\''
                + ", typeCode='" + typeCode + '\''
                + ", catCode=" + catCode
                + '}';
    }
}
