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
 * Composite primary key for the {@code transaction_category_balance} table.
 *
 * <p>Translated from the {@code TRAN-CAT-KEY} group of the COBOL
 * {@code TRAN-CAT-BAL-RECORD} in copybook {@code CVTRA01Y} @ {@code 27d6c6f}.
 * The legacy VSAM KSDS key spans the first 17 bytes of the 50-byte record:
 * {@code TRANCAT-ACCT-ID PIC 9(11)} (bytes 1-11) +
 * {@code TRANCAT-TYPE-CD PIC X(02)} (bytes 12-13) +
 * {@code TRANCAT-CD PIC 9(04)} (bytes 14-17).</p>
 *
 * <p>The field order and column names mirror
 * {@code db/migration/V1__create_schema.sql}
 * ({@code PRIMARY KEY (acct_id, type_cd, cat_cd)}) exactly so that the
 * Hibernate {@code ddl-auto=validate} check passes. On this table the
 * type-code column is {@code type_cd}, a {@code CHAR(2)} ({@code bpchar})
 * column; it is mapped with an explicit {@code char(2)} column definition so
 * the validated type matches PostgreSQL rather than defaulting to
 * {@code varchar}.</p>
 */
@Embeddable
public class TransactionCategoryBalanceId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Account identifier (COBOL {@code TRANCAT-ACCT-ID PIC 9(11)}). */
    @Column(name = "acct_id")
    private Long acctId;

    /** Transaction type code (COBOL {@code TRANCAT-TYPE-CD PIC X(02)}). */
    @Column(name = "type_cd", length = 2, columnDefinition = "char(2)")
    private String typeCd;

    /** Transaction category code (COBOL {@code TRANCAT-CD PIC 9(04)}). */
    @Column(name = "cat_cd")
    private Integer catCd;

    /**
     * Creates an empty key. Required by JPA for instantiation while
     * materializing the owning entity.
     */
    public TransactionCategoryBalanceId() {
    }

    /**
     * Creates a fully populated composite key.
     *
     * @param acctId the account identifier
     * @param typeCd the two-character transaction type code
     * @param catCd  the transaction category code
     */
    public TransactionCategoryBalanceId(Long acctId, String typeCd, Integer catCd) {
        this.acctId = acctId;
        this.typeCd = typeCd;
        this.catCd = catCd;
    }

    /**
     * Returns the account identifier.
     *
     * @return the account identifier
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the account identifier.
     *
     * @param acctId the account identifier
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return the two-character transaction type code
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Sets the transaction type code.
     *
     * @param typeCd the two-character transaction type code
     */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /**
     * Returns the transaction category code.
     *
     * @return the transaction category code
     */
    public Integer getCatCd() {
        return catCd;
    }

    /**
     * Sets the transaction category code.
     *
     * @param catCd the transaction category code
     */
    public void setCatCd(Integer catCd) {
        this.catCd = catCd;
    }

    /**
     * Compares this key with another for equality across all three key
     * components ({@code acctId}, {@code typeCd}, {@code catCd}). A complete
     * comparison is mandatory for correct {@code @EmbeddedId} behavior.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a
     *         {@code TransactionCategoryBalanceId} with equal components
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) o;
        return Objects.equals(acctId, that.acctId)
                && Objects.equals(typeCd, that.typeCd)
                && Objects.equals(catCd, that.catCd);
    }

    /**
     * Computes a hash code over all three key components, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this key
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId, typeCd, catCd);
    }

    /**
     * Returns a diagnostic representation listing the three key components.
     *
     * @return a string representation of this key
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalanceId{"
                + "acctId=" + acctId
                + ", typeCd=" + typeCd
                + ", catCd=" + catCd
                + '}';
    }
}
