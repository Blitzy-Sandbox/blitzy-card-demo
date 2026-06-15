package com.carddemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * JPA composite primary-key for the transaction-category reference table.
 *
 * <p>Maps the COBOL {@code TRAN-CAT-KEY} group of {@code TRAN-CAT-RECORD}
 * (copybook {@code CVTRA04Y}, source commit {@code 27d6c6f}). The key has
 * exactly two components in declaration order:</p>
 *
 * <ul>
 *   <li>{@code typeCode} &mdash; {@code TRAN-TYPE-CD PIC X(02)}, a 2-character
 *       alphanumeric transaction-type code (leading zeros are significant,
 *       e.g. {@code "01"}).</li>
 *   <li>{@code categoryCode} &mdash; {@code TRAN-CAT-CD PIC 9(04)}, a 4-digit
 *       numeric category code with no decimal positions.</li>
 * </ul>
 *
 * <p>Embedded into {@code com.carddemo.model.entity.TransactionCategory} via
 * {@code @EmbeddedId}. The non-key {@code TRAN-CAT-TYPE-DESC} description and
 * trailing filler fields are intentionally excluded from this key class.</p>
 */
@Embeddable
public class TransactionCategoryId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "tran_type_cd", nullable = false, length = 2)
    private String typeCode;

    @Column(name = "tran_cat_cd", nullable = false)
    private Integer categoryCode;

    public TransactionCategoryId() {
    }

    public TransactionCategoryId(String typeCode, Integer categoryCode) {
        this.typeCode = typeCode;
        this.categoryCode = categoryCode;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public Integer getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

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
                && Objects.equals(categoryCode, that.categoryCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeCode, categoryCode);
    }

    @Override
    public String toString() {
        return "TransactionCategoryId{"
                + "typeCode='" + typeCode + '\''
                + ", categoryCode=" + categoryCode
                + '}';
    }
}
