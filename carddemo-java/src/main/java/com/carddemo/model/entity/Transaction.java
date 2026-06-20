package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Transaction entity. JPA mapping of the COBOL TRAN-RECORD (copybook CVTRA05Y,
 * RECLN 350). Re-platforms the VSAM TRANSACT KSDS onto the transaction table.
 */
@Entity
@Table(name = "transaction")
public class Transaction {

    @Id
    @Column(name = "tran_id", nullable = false, length = 16)
    private String tranId;

    @Column(name = "tran_type_cd", nullable = false, length = 2)
    private String tranTypeCd;

    @Column(name = "tran_cat_cd", nullable = false)
    private Integer tranCatCd;

    @Column(name = "tran_source", nullable = false, length = 10)
    private String tranSource;

    @Column(name = "tran_desc", nullable = false, length = 100)
    private String tranDesc;

    @Column(name = "tran_amt", nullable = false, precision = 11, scale = 2)
    private BigDecimal tranAmt;

    @Column(name = "tran_merchant_id", nullable = false)
    private Long tranMerchantId;

    @Column(name = "tran_merchant_name", nullable = false, length = 50)
    private String tranMerchantName;

    @Column(name = "tran_merchant_city", nullable = false, length = 50)
    private String tranMerchantCity;

    @Column(name = "tran_merchant_zip", nullable = false, length = 10)
    private String tranMerchantZip;

    @Column(name = "tran_card_num", nullable = false, length = 16)
    private String tranCardNum;

    @Column(name = "tran_orig_ts", nullable = false, length = 26)
    private String tranOrigTs;

    @Column(name = "tran_proc_ts", nullable = false, length = 26)
    private String tranProcTs;

    public Transaction() {
    }

    public String getTranId() { return tranId; }
    public void setTranId(String tranId) { this.tranId = tranId; }
    public String getTranTypeCd() { return tranTypeCd; }
    public void setTranTypeCd(String v) { this.tranTypeCd = v; }
    public Integer getTranCatCd() { return tranCatCd; }
    public void setTranCatCd(Integer v) { this.tranCatCd = v; }
    public String getTranSource() { return tranSource; }
    public void setTranSource(String v) { this.tranSource = v; }
    public String getTranDesc() { return tranDesc; }
    public void setTranDesc(String v) { this.tranDesc = v; }
    public BigDecimal getTranAmt() { return tranAmt; }
    public void setTranAmt(BigDecimal v) { this.tranAmt = v; }
    public Long getTranMerchantId() { return tranMerchantId; }
    public void setTranMerchantId(Long v) { this.tranMerchantId = v; }
    public String getTranMerchantName() { return tranMerchantName; }
    public void setTranMerchantName(String v) { this.tranMerchantName = v; }
    public String getTranMerchantCity() { return tranMerchantCity; }
    public void setTranMerchantCity(String v) { this.tranMerchantCity = v; }
    public String getTranMerchantZip() { return tranMerchantZip; }
    public void setTranMerchantZip(String v) { this.tranMerchantZip = v; }
    public String getTranCardNum() { return tranCardNum; }
    public void setTranCardNum(String v) { this.tranCardNum = v; }
    public String getTranOrigTs() { return tranOrigTs; }
    public void setTranOrigTs(String v) { this.tranOrigTs = v; }
    public String getTranProcTs() { return tranProcTs; }
    public void setTranProcTs(String v) { this.tranProcTs = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        Transaction that = (Transaction) o;
        return Objects.equals(tranId, that.tranId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }
}
