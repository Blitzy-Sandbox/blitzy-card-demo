package com.carddemo.model.entity;

import com.carddemo.model.key.TransactionCategoryId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Transaction category reference entity. JPA mapping of the COBOL
 * TRAN-CAT-RECORD (copybook CVTRA04Y, RECLN 60). Re-platforms the VSAM TRANCATG
 * KSDS. Composite key is supplied by TransactionCategoryId (type code + category
 * code).
 */
@Entity
@Table(name = "transaction_category")
public class TransactionCategory {

    @EmbeddedId
    private TransactionCategoryId id;

    @Column(name = "tran_cat_type_desc", nullable = false, length = 50)
    private String tranCatTypeDesc;

    public TransactionCategory() {
    }

    public TransactionCategoryId getId() { return id; }
    public void setId(TransactionCategoryId id) { this.id = id; }
    public String getTranCatTypeDesc() { return tranCatTypeDesc; }
    public void setTranCatTypeDesc(String v) { this.tranCatTypeDesc = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        TransactionCategory that = (TransactionCategory) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
