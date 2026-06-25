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
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the {@code disclosure_group} table.
 *
 * <p>Translated from the COBOL {@code DIS-GROUP-KEY} group in copybook
 * {@code app/cpy/CVTRA02Y.cpy} (DIS-GROUP-RECORD, RECLN 50) at commit
 * {@code 27d6c6f}. The three key fields preserve the legacy VSAM 16-byte key
 * span (10 + 2 + 4) and their original order:
 *
 * <ul>
 *   <li>{@code DIS-ACCT-GROUP-ID PIC X(10)} &rarr; {@code acct_group_id}</li>
 *   <li>{@code DIS-TRAN-TYPE-CD  PIC X(02)} &rarr; {@code tran_type_cd}</li>
 *   <li>{@code DIS-TRAN-CAT-CD   PIC 9(04)} &rarr; {@code tran_cat_cd}</li>
 * </ul>
 *
 * <p>Annotated {@link Embeddable} for use as a JPA {@code @EmbeddedId} on the
 * {@code DisclosureGroup} entity. Column names, widths, and ordering align with
 * the authoritative {@code V1__create_schema.sql} definition.
 */
@Embeddable
public class DisclosureGroupId implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Account group identifier. Source field {@code DIS-ACCT-GROUP-ID PIC X(10)}.
     * Mapped to a fixed-width {@code CHAR(10)} column; PostgreSQL {@code bpchar}
     * blank-pads on storage and ignores trailing spaces in equality comparison.
     */
    @Column(name = "acct_group_id", length = 10, columnDefinition = "char(10)")
    private String acctGroupId;

    /**
     * Transaction type code. Source field {@code DIS-TRAN-TYPE-CD PIC X(02)}.
     * Mapped to a fixed-width {@code CHAR(2)} column.
     */
    @Column(name = "tran_type_cd", length = 2, columnDefinition = "char(2)")
    private String tranTypeCd;

    /**
     * Transaction category code. Source field {@code DIS-TRAN-CAT-CD PIC 9(04)}.
     * Mapped to an {@code INTEGER} column.
     */
    @Column(name = "tran_cat_cd")
    private Integer tranCatCd;

    /**
     * No-argument constructor required by the JPA specification for embeddable
     * key types.
     */
    public DisclosureGroupId() {
        // Required by JPA; fields are populated via setters or the all-args constructor.
    }

    /**
     * Creates a fully populated disclosure-group composite key.
     *
     * @param acctGroupId the account group identifier (DIS-ACCT-GROUP-ID)
     * @param tranTypeCd  the transaction type code (DIS-TRAN-TYPE-CD)
     * @param tranCatCd   the transaction category code (DIS-TRAN-CAT-CD)
     */
    public DisclosureGroupId(String acctGroupId, String tranTypeCd, Integer tranCatCd) {
        this.acctGroupId = acctGroupId;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the account group identifier.
     *
     * @return the account group identifier
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * Sets the account group identifier.
     *
     * @param acctGroupId the account group identifier
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return the transaction type code
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Sets the transaction type code.
     *
     * @param tranTypeCd the transaction type code
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the transaction category code.
     *
     * @return the transaction category code
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Sets the transaction category code.
     *
     * @param tranCatCd the transaction category code
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Compares this key with another for equality across all three key fields.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code DisclosureGroupId} with
     *         identical account group, transaction type, and transaction
     *         category values
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DisclosureGroupId that = (DisclosureGroupId) o;
        return Objects.equals(acctGroupId, that.acctGroupId)
                && Objects.equals(tranTypeCd, that.tranTypeCd)
                && Objects.equals(tranCatCd, that.tranCatCd);
    }

    /**
     * Computes a hash code consistent with {@link #equals(Object)} over all
     * three key fields.
     *
     * @return the hash code for this key
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctGroupId, tranTypeCd, tranCatCd);
    }

    /**
     * Returns a diagnostic string representation of the three key fields.
     *
     * @return a string containing the account group, transaction type, and
     *         transaction category values
     */
    @Override
    public String toString() {
        return "DisclosureGroupId{"
                + "acctGroupId=" + acctGroupId
                + ", tranTypeCd=" + tranTypeCd
                + ", tranCatCd=" + tranCatCd
                + '}';
    }
}
