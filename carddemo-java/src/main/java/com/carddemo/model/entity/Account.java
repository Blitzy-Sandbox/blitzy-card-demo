package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Account entity. JPA mapping of the COBOL ACCOUNT-RECORD (copybook CVACT01Y,
 * RECLN 300). Re-platforms the VSAM ACCTDAT KSDS onto the PostgreSQL account table.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    @Column(name = "acct_active_status", nullable = false, length = 1)
    private String acctActiveStatus;

    @Column(name = "acct_curr_bal", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCurrBal;

    @Column(name = "acct_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCreditLimit;

    @Column(name = "acct_cash_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCashCreditLimit;

    @Column(name = "acct_open_date", nullable = false, length = 10)
    private String acctOpenDate;

    @Column(name = "acct_expiraion_date", nullable = false, length = 10)
    private String acctExpiraionDate;

    @Column(name = "acct_reissue_date", nullable = false, length = 10)
    private String acctReissueDate;

    @Column(name = "acct_curr_cyc_credit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCurrCycCredit;

    @Column(name = "acct_curr_cyc_debit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCurrCycDebit;

    @Column(name = "acct_addr_zip", nullable = false, length = 10)
    private String acctAddrZip;

    @Column(name = "acct_group_id", nullable = false, length = 10)
    private String acctGroupId;

    @Version
    @Column(name = "version")
    private Long version;

    public Account() {
    }

    public Long getAcctId() { return acctId; }
    public void setAcctId(Long acctId) { this.acctId = acctId; }
    public String getAcctActiveStatus() { return acctActiveStatus; }
    public void setAcctActiveStatus(String v) { this.acctActiveStatus = v; }
    public BigDecimal getAcctCurrBal() { return acctCurrBal; }
    public void setAcctCurrBal(BigDecimal v) { this.acctCurrBal = v; }
    public BigDecimal getAcctCreditLimit() { return acctCreditLimit; }
    public void setAcctCreditLimit(BigDecimal v) { this.acctCreditLimit = v; }
    public BigDecimal getAcctCashCreditLimit() { return acctCashCreditLimit; }
    public void setAcctCashCreditLimit(BigDecimal v) { this.acctCashCreditLimit = v; }
    public String getAcctOpenDate() { return acctOpenDate; }
    public void setAcctOpenDate(String v) { this.acctOpenDate = v; }
    public String getAcctExpiraionDate() { return acctExpiraionDate; }
    public void setAcctExpiraionDate(String v) { this.acctExpiraionDate = v; }
    public String getAcctReissueDate() { return acctReissueDate; }
    public void setAcctReissueDate(String v) { this.acctReissueDate = v; }
    public BigDecimal getAcctCurrCycCredit() { return acctCurrCycCredit; }
    public void setAcctCurrCycCredit(BigDecimal v) { this.acctCurrCycCredit = v; }
    public BigDecimal getAcctCurrCycDebit() { return acctCurrCycDebit; }
    public void setAcctCurrCycDebit(BigDecimal v) { this.acctCurrCycDebit = v; }
    public String getAcctAddrZip() { return acctAddrZip; }
    public void setAcctAddrZip(String v) { this.acctAddrZip = v; }
    public String getAcctGroupId() { return acctGroupId; }
    public void setAcctGroupId(String v) { this.acctGroupId = v; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        Account account = (Account) o;
        return Objects.equals(acctId, account.acctId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }
}
