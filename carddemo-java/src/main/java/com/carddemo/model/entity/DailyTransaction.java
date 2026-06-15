package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Daily transaction staging entity. JPA mapping of the COBOL DALYTRAN-RECORD
 * (copybook CVTRA06Y, RECLN 350). Staging store for the daily transaction
 * posting pipeline; mirrors Transaction with DALYTRAN-* fields.
 */
@Entity
@Table(name = "daily_transaction")
public class DailyTransaction {

    @Id
    @Column(name = "dalytran_id", nullable = false, length = 16)
    private String dalytranId;

    @Column(name = "dalytran_type_cd", nullable = false, length = 2)
    private String dalytranTypeCd;

    @Column(name = "dalytran_cat_cd", nullable = false)
    private Integer dalytranCatCd;

    @Column(name = "dalytran_source", nullable = false, length = 10)
    private String dalytranSource;

    @Column(name = "dalytran_desc", nullable = false, length = 100)
    private String dalytranDesc;

    @Column(name = "dalytran_amt", nullable = false, precision = 11, scale = 2)
    private BigDecimal dalytranAmt;

    @Column(name = "dalytran_merchant_id", nullable = false)
    private Long dalytranMerchantId;

    @Column(name = "dalytran_merchant_name", nullable = false, length = 50)
    private String dalytranMerchantName;

    @Column(name = "dalytran_merchant_city", nullable = false, length = 50)
    private String dalytranMerchantCity;

    @Column(name = "dalytran_merchant_zip", nullable = false, length = 10)
    private String dalytranMerchantZip;

    @Column(name = "dalytran_card_num", nullable = false, length = 16)
    private String dalytranCardNum;

    @Column(name = "dalytran_orig_ts", nullable = false, length = 26)
    private String dalytranOrigTs;

    @Column(name = "dalytran_proc_ts", nullable = false, length = 26)
    private String dalytranProcTs;

    public DailyTransaction() {
    }

    public String getDalytranId() { return dalytranId; }
    public void setDalytranId(String v) { this.dalytranId = v; }
    public String getDalytranTypeCd() { return dalytranTypeCd; }
    public void setDalytranTypeCd(String v) { this.dalytranTypeCd = v; }
    public Integer getDalytranCatCd() { return dalytranCatCd; }
    public void setDalytranCatCd(Integer v) { this.dalytranCatCd = v; }
    public String getDalytranSource() { return dalytranSource; }
    public void setDalytranSource(String v) { this.dalytranSource = v; }
    public String getDalytranDesc() { return dalytranDesc; }
    public void setDalytranDesc(String v) { this.dalytranDesc = v; }
    public BigDecimal getDalytranAmt() { return dalytranAmt; }
    public void setDalytranAmt(BigDecimal v) { this.dalytranAmt = v; }
    public Long getDalytranMerchantId() { return dalytranMerchantId; }
    public void setDalytranMerchantId(Long v) { this.dalytranMerchantId = v; }
    public String getDalytranMerchantName() { return dalytranMerchantName; }
    public void setDalytranMerchantName(String v) { this.dalytranMerchantName = v; }
    public String getDalytranMerchantCity() { return dalytranMerchantCity; }
    public void setDalytranMerchantCity(String v) { this.dalytranMerchantCity = v; }
    public String getDalytranMerchantZip() { return dalytranMerchantZip; }
    public void setDalytranMerchantZip(String v) { this.dalytranMerchantZip = v; }
    public String getDalytranCardNum() { return dalytranCardNum; }
    public void setDalytranCardNum(String v) { this.dalytranCardNum = v; }
    public String getDalytranOrigTs() { return dalytranOrigTs; }
    public void setDalytranOrigTs(String v) { this.dalytranOrigTs = v; }
    public String getDalytranProcTs() { return dalytranProcTs; }
    public void setDalytranProcTs(String v) { this.dalytranProcTs = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        DailyTransaction that = (DailyTransaction) o;
        return Objects.equals(dalytranId, that.dalytranId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dalytranId);
    }
}
