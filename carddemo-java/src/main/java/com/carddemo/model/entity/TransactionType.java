package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Transaction type reference entity. JPA mapping of the COBOL TRAN-TYPE-RECORD
 * (copybook CVTRA03Y, RECLN 60). Re-platforms the VSAM TRANTYPE KSDS onto the
 * transaction_type table.
 */
@Entity
@Table(name = "transaction_type")
public class TransactionType {

    @Id
    @Column(name = "tran_type", nullable = false, length = 2)
    private String tranType;

    @Column(name = "tran_type_desc", nullable = false, length = 50)
    private String tranTypeDesc;

    public TransactionType() {
    }

    public String getTranType() { return tranType; }
    public void setTranType(String tranType) { this.tranType = tranType; }
    public String getTranTypeDesc() { return tranTypeDesc; }
    public void setTranTypeDesc(String v) { this.tranTypeDesc = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        TransactionType that = (TransactionType) o;
        return Objects.equals(tranType, that.tranType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tranType);
    }
}
