package com.carddemo.model.entity;

import com.carddemo.model.key.TransactionCategoryBalanceId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Transaction category balance entity. JPA mapping of the COBOL
 * TRAN-CAT-BAL-RECORD (copybook CVTRA01Y, RECLN 50). Re-platforms the VSAM
 * TCATBALF KSDS. Composite key is supplied by TransactionCategoryBalanceId.
 */
@Entity
@Table(name = "transaction_category_balance")
public class TransactionCategoryBalance {

    @EmbeddedId
    private TransactionCategoryBalanceId id;

    @Column(name = "tran_cat_bal", nullable = false, precision = 11, scale = 2)
    private BigDecimal tranCatBal;

    public TransactionCategoryBalance() {
    }

    public TransactionCategoryBalanceId getId() { return id; }
    public void setId(TransactionCategoryBalanceId id) { this.id = id; }
    public BigDecimal getTranCatBal() { return tranCatBal; }
    public void setTranCatBal(BigDecimal tranCatBal) { this.tranCatBal = tranCatBal; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        TransactionCategoryBalance that = (TransactionCategoryBalance) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
