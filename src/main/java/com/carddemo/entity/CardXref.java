/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for the card cross-reference record.
 *
 * <p>Maps the legacy COBOL {@code CARD-XREF-RECORD} (copybook {@code CVACT03Y}
 * at source commit {@code 27d6c6f}, record length 50) to the PostgreSQL
 * {@code card_xref} table. The record links a 16-character card number to its
 * owning customer and account, mirroring the VSAM KSDS that was keyed on the
 * card number with alternate-index paths on customer id and account id.
 *
 * <p>The trailing COBOL {@code FILLER PIC X(14)} is reserved padding and is not
 * persisted. Customer and account links are navigated through repository finder
 * methods rather than JPA relationships.
 */
@Entity
@Table(name = "card_xref")
public class CardXref implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Card-number cross-reference key &mdash; COBOL {@code XREF-CARD-NUM PIC X(16)}.
     * Primary key of {@code card_xref}, stored as a fixed-width {@code CHAR(16)}
     * column.
     */
    @Id
    @Column(name = "xref_card_num", length = 16, columnDefinition = "char(16)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String xrefCardNum;

    /**
     * Owning customer identifier &mdash; COBOL {@code XREF-CUST-ID PIC 9(09)}
     * mapped to a {@code BIGINT} column.
     */
    @Column(name = "xref_cust_id")
    private Long xrefCustId;

    /**
     * Owning account identifier &mdash; COBOL {@code XREF-ACCT-ID PIC 9(11)}
     * mapped to a {@code BIGINT} column.
     */
    @Column(name = "xref_acct_id")
    private Long xrefAcctId;

    /**
     * No-argument constructor required by the JPA provider.
     */
    public CardXref() {
        // No-args constructor required by JPA.
    }

    /**
     * Creates a fully populated cross-reference record.
     *
     * @param xrefCardNum the 16-character card number (primary key)
     * @param xrefCustId  the owning customer identifier
     * @param xrefAcctId  the owning account identifier
     */
    public CardXref(String xrefCardNum, Long xrefCustId, Long xrefAcctId) {
        this.xrefCardNum = xrefCardNum;
        this.xrefCustId = xrefCustId;
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Returns the card-number primary key.
     *
     * @return the 16-character card number
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the card-number primary key.
     *
     * @param xrefCardNum the 16-character card number
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * Returns the owning customer identifier.
     *
     * @return the customer identifier
     */
    public Long getXrefCustId() {
        return xrefCustId;
    }

    /**
     * Sets the owning customer identifier.
     *
     * @param xrefCustId the customer identifier
     */
    public void setXrefCustId(Long xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * Returns the owning account identifier.
     *
     * @return the account identifier
     */
    public Long getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * Sets the owning account identifier.
     *
     * @param xrefAcctId the account identifier
     */
    public void setXrefAcctId(Long xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Equality is based solely on the {@code xrefCardNum} primary key, matching
     * the VSAM KSDS identity semantics of the legacy record.
     *
     * @param o the object to compare with
     * @return {@code true} when both records share the same card number
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardXref other)) {
            return false;
        }
        return Objects.equals(xrefCardNum, other.xrefCardNum);
    }

    /**
     * Hash code derived from the {@code xrefCardNum} primary key, consistent
     * with {@link #equals(Object)}.
     *
     * @return the primary-key hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum);
    }

    /**
     * Returns a concise, human-readable representation of this cross-reference.
     *
     * @return a string describing the card number, customer id, and account id
     */
    @Override
    public String toString() {
        return "CardXref{"
                + "xrefCardNum='" + xrefCardNum + '\''
                + ", xrefCustId=" + xrefCustId
                + ", xrefAcctId=" + xrefAcctId
                + '}';
    }
}
