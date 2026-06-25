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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Composite primary key for the {@code transaction_category} lookup table,
 * translated from the COBOL {@code TRAN-CAT-KEY} group in copybook
 * {@code CVTRA04Y} (TRAN-CAT-RECORD, RECLN 60) @ 27d6c6f.
 *
 * <p>The legacy VSAM key spans the first six bytes of the record:
 * {@code TRAN-TYPE-CD PIC X(02)} (bytes 1-2) followed by
 * {@code TRAN-CAT-CD PIC 9(04)} (bytes 3-6). The component order is preserved
 * so that this JPA composite key matches the
 * {@code PRIMARY KEY (tran_type_cd, tran_cat_cd)} declared for
 * {@code transaction_category} in {@code V1__create_schema.sql}.
 *
 * <p>This type is consumed through {@code @EmbeddedId} by the
 * {@code TransactionCategory} entity. The {@code tran_type_cd} component is a
 * lookup-table key and intentionally remains a plain {@link String} mapped to
 * {@code CHAR(2)}; the transaction-type enum and its attribute converter apply
 * only to the transaction fact entity, never to this key column.
 */
@Embeddable
public class TransactionCategoryId implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Two-character transaction type code, from COBOL
     * {@code TRAN-TYPE-CD PIC X(02)}. Mapped to the {@code CHAR(2)} column
     * {@code tran_type_cd}. The {@link JdbcTypeCode}{@code (SqlTypes.CHAR)}
     * binding makes the Hibernate {@code validate} schema check expect the
     * JDBC {@code CHAR} type so it matches the PostgreSQL {@code bpchar}
     * column and the application boots cleanly under {@code ddl-auto:
     * validate}; {@code columnDefinition = "char(2)"} additionally fixes the
     * generated DDL width.
     */
    @Column(name = "tran_type_cd", length = 2, columnDefinition = "char(2)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String tranTypeCd;

    /**
     * Four-digit transaction category code, from COBOL
     * {@code TRAN-CAT-CD PIC 9(04)}. Mapped to the {@code INTEGER} column
     * {@code tran_cat_cd}.
     */
    @Column(name = "tran_cat_cd")
    private Integer tranCatCd;

    /**
     * No-argument constructor required by the JPA specification for embeddable
     * identifier types.
     */
    public TransactionCategoryId() {
    }

    /**
     * Creates a fully populated composite key.
     *
     * @param tranTypeCd the two-character transaction type code
     *                   ({@code TRAN-TYPE-CD})
     * @param tranCatCd  the four-digit transaction category code
     *                   ({@code TRAN-CAT-CD})
     */
    public TransactionCategoryId(String tranTypeCd, Integer tranCatCd) {
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the two-character transaction type code ({@code TRAN-TYPE-CD}).
     *
     * @return the transaction type code, or {@code null} when unset
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Sets the two-character transaction type code ({@code TRAN-TYPE-CD}).
     *
     * @param tranTypeCd the transaction type code to set
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the four-digit transaction category code ({@code TRAN-CAT-CD}).
     *
     * @return the transaction category code, or {@code null} when unset
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Sets the four-digit transaction category code ({@code TRAN-CAT-CD}).
     *
     * @param tranCatCd the transaction category code to set
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Compares this composite key with another for value equality across both
     * key components, as required for correct use as a JPA identifier.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code TransactionCategoryId} with
     *         equal {@code tranTypeCd} and {@code tranCatCd}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategoryId that = (TransactionCategoryId) o;
        return Objects.equals(tranTypeCd, that.tranTypeCd)
                && Objects.equals(tranCatCd, that.tranCatCd);
    }

    /**
     * Computes a hash code consistent with {@link #equals(Object)} over both
     * key components.
     *
     * @return the hash code for this composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }

    /**
     * Returns a diagnostic representation of both key components.
     *
     * @return a string containing {@code tranTypeCd} and {@code tranCatCd}
     */
    @Override
    public String toString() {
        return "TransactionCategoryId{tranTypeCd=" + tranTypeCd
                + ", tranCatCd=" + tranCatCd + "}";
    }
}
