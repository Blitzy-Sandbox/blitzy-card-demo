package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;

/**
 * Customer entity. JPA mapping of the COBOL CUSTOMER-RECORD (canonical copybook
 * CVCUS01Y; CUSTREC is an equivalent variant differing only in the DOB field
 * name), RECLN 500. Re-platforms the VSAM CUSTDAT KSDS onto the customer table.
 */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @Column(name = "cust_id", nullable = false)
    private Long custId;

    @Column(name = "cust_first_name", nullable = false, length = 25)
    private String custFirstName;

    @Column(name = "cust_middle_name", nullable = false, length = 25)
    private String custMiddleName;

    @Column(name = "cust_last_name", nullable = false, length = 25)
    private String custLastName;

    @Column(name = "cust_addr_line_1", nullable = false, length = 50)
    private String custAddrLine1;

    @Column(name = "cust_addr_line_2", nullable = false, length = 50)
    private String custAddrLine2;

    @Column(name = "cust_addr_line_3", nullable = false, length = 50)
    private String custAddrLine3;

    @Column(name = "cust_addr_state_cd", nullable = false, length = 2)
    private String custAddrStateCd;

    @Column(name = "cust_addr_country_cd", nullable = false, length = 3)
    private String custAddrCountryCd;

    @Column(name = "cust_addr_zip", nullable = false, length = 10)
    private String custAddrZip;

    @Column(name = "cust_phone_num_1", nullable = false, length = 15)
    private String custPhoneNum1;

    @Column(name = "cust_phone_num_2", nullable = false, length = 15)
    private String custPhoneNum2;

    @Column(name = "cust_ssn", nullable = false)
    private Long custSsn;

    @Column(name = "cust_govt_issued_id", nullable = false, length = 20)
    private String custGovtIssuedId;

    @Column(name = "cust_dob_yyyy_mm_dd", nullable = false, length = 10)
    private String custDobYyyyMmDd;

    @Column(name = "cust_eft_account_id", nullable = false, length = 10)
    private String custEftAccountId;

    @Column(name = "cust_pri_card_holder_ind", nullable = false, length = 1)
    private String custPriCardHolderInd;

    @Column(name = "cust_fico_credit_score", nullable = false)
    private Integer custFicoCreditScore;

    /**
     * JPA optimistic-locking version (CUSTDAT side of the COACTUPC
     * ACCTDAT+CUSTDAT update). The customer master, like the account master,
     * participates in optimistic concurrency so a stale customer-only rewrite is
     * rejected rather than silently overwriting a concurrent change
     * (AAP 0.8.4 — {@code 9700-CHECK-CHANGE-IN-REC} before/after image compare).
     * Mirrors the {@code @Version} on {@link Account}.
     */
    @Version
    @Column(name = "version")
    private Long version;

    public Customer() {
    }

    public Long getCustId() { return custId; }
    public void setCustId(Long custId) { this.custId = custId; }
    public String getCustFirstName() { return custFirstName; }
    public void setCustFirstName(String v) { this.custFirstName = v; }
    public String getCustMiddleName() { return custMiddleName; }
    public void setCustMiddleName(String v) { this.custMiddleName = v; }
    public String getCustLastName() { return custLastName; }
    public void setCustLastName(String v) { this.custLastName = v; }
    public String getCustAddrLine1() { return custAddrLine1; }
    public void setCustAddrLine1(String v) { this.custAddrLine1 = v; }
    public String getCustAddrLine2() { return custAddrLine2; }
    public void setCustAddrLine2(String v) { this.custAddrLine2 = v; }
    public String getCustAddrLine3() { return custAddrLine3; }
    public void setCustAddrLine3(String v) { this.custAddrLine3 = v; }
    public String getCustAddrStateCd() { return custAddrStateCd; }
    public void setCustAddrStateCd(String v) { this.custAddrStateCd = v; }
    public String getCustAddrCountryCd() { return custAddrCountryCd; }
    public void setCustAddrCountryCd(String v) { this.custAddrCountryCd = v; }
    public String getCustAddrZip() { return custAddrZip; }
    public void setCustAddrZip(String v) { this.custAddrZip = v; }
    public String getCustPhoneNum1() { return custPhoneNum1; }
    public void setCustPhoneNum1(String v) { this.custPhoneNum1 = v; }
    public String getCustPhoneNum2() { return custPhoneNum2; }
    public void setCustPhoneNum2(String v) { this.custPhoneNum2 = v; }
    public Long getCustSsn() { return custSsn; }
    public void setCustSsn(Long custSsn) { this.custSsn = custSsn; }
    public String getCustGovtIssuedId() { return custGovtIssuedId; }
    public void setCustGovtIssuedId(String v) { this.custGovtIssuedId = v; }
    public String getCustDobYyyyMmDd() { return custDobYyyyMmDd; }
    public void setCustDobYyyyMmDd(String v) { this.custDobYyyyMmDd = v; }
    public String getCustEftAccountId() { return custEftAccountId; }
    public void setCustEftAccountId(String v) { this.custEftAccountId = v; }
    public String getCustPriCardHolderInd() { return custPriCardHolderInd; }
    public void setCustPriCardHolderInd(String v) { this.custPriCardHolderInd = v; }
    public Integer getCustFicoCreditScore() { return custFicoCreditScore; }
    public void setCustFicoCreditScore(Integer v) { this.custFicoCreditScore = v; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        Customer customer = (Customer) o;
        return Objects.equals(custId, customer.custId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }
}
