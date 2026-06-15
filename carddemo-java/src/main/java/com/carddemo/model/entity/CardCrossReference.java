package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Card cross-reference entity. JPA mapping of the COBOL CARD-XREF-RECORD
 * (copybook CVACT03Y, RECLN 50). Provides the Card-to-Account-to-Customer
 * linkage (basis for the CXACAIX alternate index).
 */
@Entity
@Table(name = "card_xref")
public class CardCrossReference {

    @Id
    @Column(name = "xref_card_num", nullable = false, length = 16)
    private String xrefCardNum;

    @Column(name = "xref_cust_id", nullable = false)
    private Long xrefCustId;

    @Column(name = "xref_acct_id", nullable = false)
    private Long xrefAcctId;

    public CardCrossReference() {
    }

    public String getXrefCardNum() { return xrefCardNum; }
    public void setXrefCardNum(String xrefCardNum) { this.xrefCardNum = xrefCardNum; }
    public Long getXrefCustId() { return xrefCustId; }
    public void setXrefCustId(Long xrefCustId) { this.xrefCustId = xrefCustId; }
    public Long getXrefAcctId() { return xrefAcctId; }
    public void setXrefAcctId(Long xrefAcctId) { this.xrefAcctId = xrefAcctId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        CardCrossReference that = (CardCrossReference) o;
        return Objects.equals(xrefCardNum, that.xrefCardNum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum);
    }
}
