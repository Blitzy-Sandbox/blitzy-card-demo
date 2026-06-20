package com.carddemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * JPA composite primary key for the transaction category reference data.
 *
 * <p>This embeddable identifier is composed of two components &mdash; the
 * transaction type code and the transaction category code &mdash; and is used
 * as the {@code @EmbeddedId} of the {@code TransactionCategory} entity and as
 * the identifier type of its Spring Data repository.</p>
 *
 * <p>The {@code typeCode} component is a fixed two-character alphanumeric value
 * whose leading zeros are significant, while {@code categoryCode} is a
 * non-negative four-digit integral value.</p>
 *
 * <p>Lineage: AWS CardDemo copybook CVTRA04Y, group item {@code TRAN-CAT-KEY}
 * (source commit {@code 27d6c6f}).</p>
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
