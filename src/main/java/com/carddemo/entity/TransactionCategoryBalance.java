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
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity for the transaction category balance record.
 *
 * <p>Maps the legacy COBOL {@code TRAN-CAT-BAL-RECORD} (copybook
 * {@code CVTRA01Y} at source commit {@code 27d6c6f}, record length 50) to the
 * PostgreSQL {@code transaction_category_balance} table. Each row accumulates
 * the running balance for a single (account, transaction-type, category)
 * combination, mirroring the VSAM KSDS that was keyed on the 17-byte
 * {@code TRAN-CAT-KEY} group.
 *
 * <p>The composite primary key is supplied by
 * {@link TransactionCategoryBalanceId} through {@code @EmbeddedId}; the three
 * key columns ({@code acct_id}, {@code type_cd}, {@code cat_cd}) are declared on
 * that embeddable and are intentionally not re-declared here. The trailing
 * COBOL {@code FILLER PIC X(22)} is reserved padding and is never persisted.
 */
@Entity
@Table(name = "transaction_category_balance")
public class TransactionCategoryBalance implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key &mdash; COBOL {@code TRAN-CAT-KEY} group
     * ({@code TRANCAT-ACCT-ID PIC 9(11)}, {@code TRANCAT-TYPE-CD PIC X(02)},
     * {@code TRANCAT-CD PIC 9(04)}). The key columns are mapped on
     * {@link TransactionCategoryBalanceId}.
     */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Running category balance &mdash; COBOL {@code TRAN-CAT-BAL PIC S9(09)V99}.
     * Persisted as {@code NUMERIC(11,2)} and modeled as {@link BigDecimal} with
     * a scale of two so monetary precision matches the legacy packed-decimal
     * field exactly. Floating-point types are never used for monetary values.
     */
    @Column(name = "tran_cat_bal", precision = 11, scale = 2)
    private BigDecimal tranCatBal;

    /**
     * No-argument constructor required by the JPA provider.
     */
    public TransactionCategoryBalance() {
        // No-args constructor required by JPA.
    }

    /**
     * Creates a fully populated transaction category balance record.
     *
     * @param id         the composite primary key
     * @param tranCatBal the running category balance
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal tranCatBal) {
        this.id = id;
        this.tranCatBal = tranCatBal;
    }

    /**
     * Returns the composite primary key.
     *
     * @return the composite key, or {@code null} when unset
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the composite key
     */
    public void setId(TransactionCategoryBalanceId id) {
        this.id = id;
    }

    /**
     * Returns the running category balance.
     *
     * @return the category balance, or {@code null} when unset
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * Sets the running category balance.
     *
     * @param tranCatBal the category balance
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    /**
     * Equality is based solely on the composite primary key {@code id}, which
     * itself compares all three key components ({@code acctId}, {@code typeCd},
     * {@code catCd}).
     *
     * @param o the object to compare with
     * @return {@code true} when both records share the same composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryBalance other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    /**
     * Hash code derived from the composite primary key {@code id}, consistent
     * with {@link #equals(Object)}.
     *
     * @return the composite-key hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Returns a concise, human-readable representation of this record.
     *
     * @return a string describing the composite key and balance
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance{"
                + "id=" + id
                + ", tranCatBal=" + tranCatBal
                + '}';
    }
}
