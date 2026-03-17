package com.cardemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the {@code TransactionCategory} JPA entity.
 *
 * <p>This class is a direct translation of the COBOL {@code TRAN-CAT-KEY} group-level
 * key definition from {@code app/cpy/CVTRA04Y.cpy}:
 * <pre>
 *   05  TRAN-CAT-KEY.
 *      10  TRAN-TYPE-CD       PIC X(02).
 *      10  TRAN-CAT-CD        PIC 9(04).
 * </pre>
 *
 * <p>Both fields are stored as {@link String} to preserve exact COBOL byte-level semantics,
 * including leading zeros in the numeric {@code TRAN-CAT-CD} field ({@code PIC 9(04)}).
 * The total composite key length is 6 bytes (2 + 4).
 *
 * <p>This is the simplest of the three composite key classes in the CardDemo migration.
 * The same {@code typeCode} and {@code catCode} field pattern appears as a subset within
 * {@code TransactionCategoryBalanceId} (which adds {@code acctId}) and
 * {@code DisclosureGroupId} (which adds {@code groupId}). All three key classes share
 * the same snake_case column naming convention derived from the COBOL field names.
 *
 * @see com.cardemo.model.entity.TransactionCategory
 */
@Embeddable
public class TransactionCategoryId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Transaction type code — maps from COBOL {@code TRAN-TYPE-CD PIC X(02)}.
     * A 2-character alphanumeric code identifying the transaction type.
     */
    @Column(name = "tran_type_cd", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction category code — maps from COBOL {@code TRAN-CAT-CD PIC 9(04)}.
     * A 4-character numeric display field stored as {@link String} to preserve
     * leading zeros (e.g., "0001", "0042").
     */
    @Column(name = "tran_cat_cd", length = 4, nullable = false)
    private String catCode;

    /**
     * No-args constructor required by the JPA specification for entity and
     * embeddable instantiation via reflection.
     */
    public TransactionCategoryId() {
        // JPA-required default constructor
    }

    /**
     * All-args constructor for programmatic key creation.
     *
     * @param typeCode the 2-character transaction type code (TRAN-TYPE-CD)
     * @param catCode  the 4-character transaction category code (TRAN-CAT-CD)
     */
    public TransactionCategoryId(String typeCode, String catCode) {
        this.typeCode = typeCode;
        this.catCode = catCode;
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
     * @param typeCode the 2-character type code (TRAN-TYPE-CD)
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction category code.
     *
     * @return the 4-character category code
     */
    public String getCatCode() {
        return catCode;
    }

    /**
     * Sets the transaction category code.
     *
     * @param catCode the 4-character category code (TRAN-CAT-CD)
     */
    public void setCatCode(String catCode) {
        this.catCode = catCode;
    }

    /**
     * Determines equality based on both composite key fields.
     * This implementation is critical for JPA entity identity — the
     * {@code TransactionCategory} entity uses {@code @EmbeddedId} with this class,
     * and the persistence context relies on correct key equality for first-level
     * cache lookups, merge operations, and dirty checking.
     *
     * @param o the object to compare with
     * @return {@code true} if both {@code typeCode} and {@code catCode} are equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategoryId that = (TransactionCategoryId) o;
        return Objects.equals(typeCode, that.typeCode)
                && Objects.equals(catCode, that.catCode);
    }

    /**
     * Computes the hash code from both composite key fields.
     * Consistent with {@link #equals(Object)} — uses the same fields to ensure
     * the hash code contract is preserved for use in hash-based collections
     * and JPA identity maps.
     *
     * @return hash code derived from {@code typeCode} and {@code catCode}
     */
    @Override
    public int hashCode() {
        return Objects.hash(typeCode, catCode);
    }

    /**
     * Returns a human-readable string representation of this composite key,
     * including both field values for diagnostic and logging purposes.
     *
     * @return string in the format {@code TransactionCategoryId{typeCode='XX', catCode='YYYY'}}
     */
    @Override
    public String toString() {
        return "TransactionCategoryId{"
                + "typeCode='" + typeCode + '\''
                + ", catCode='" + catCode + '\''
                + '}';
    }
}
