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
 * JPA entity for the {@code transaction_type} reference lookup table, mapping
 * the COBOL {@code TRAN-TYPE-RECORD} structure (copybook {@code CVTRA03Y} @
 * {@code 27d6c6f}, record length 60).
 *
 * <p>The legacy record declares three fields:
 * <pre>
 *   01 TRAN-TYPE-RECORD.
 *      05 TRAN-TYPE       PIC X(02).
 *      05 TRAN-TYPE-DESC  PIC X(50).
 *      05 FILLER          PIC X(08).
 * </pre>
 * The trailing {@code FILLER} occupies record bytes 53-60 purely for the fixed
 * 60-byte VSAM layout and carries no business meaning, so it is intentionally
 * not mapped to a column.
 *
 * <p>The two-character {@code TRAN-TYPE} code is the primary key and is stored
 * as a fixed-width {@code CHAR(2)} column, matching {@code V1__create_schema.sql}
 * so that Hibernate schema validation ({@code ddl-auto: validate}) succeeds.
 * The code is modelled as a plain {@link String} here because this is the
 * lookup table itself; the typed {@code TransactionTypeCode} enum is reserved
 * for the {@code Transaction} fact entity.
 */
@Entity
@Table(name = "transaction_type")
public class TransactionType implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Two-character transaction type code ({@code TRAN-TYPE}, {@code PIC X(02)}),
     * the primary key. Mapped to the fixed-width {@code CHAR(2)} column via
     * {@link JdbcTypeCode}{@code (}{@link SqlTypes#CHAR}{@code )} so that
     * Hibernate schema validation matches the {@code CHAR(2)} column declared in
     * {@code V1__create_schema.sql} (a plain {@link String} otherwise maps to
     * {@code VARCHAR}, which fails {@code ddl-auto: validate} against a
     * {@code CHAR} column).
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type", length = 2)
    private String tranType;

    /**
     * Human-readable transaction type description ({@code TRAN-TYPE-DESC},
     * {@code PIC X(50)}), stored as {@code VARCHAR(50)}.
     */
    @Column(name = "tran_type_desc", length = 50)
    private String tranTypeDesc;

    /**
     * No-argument constructor required by JPA.
     */
    public TransactionType() {
        // Required by the JPA specification for entity instantiation.
    }

    /**
     * Creates a fully populated transaction type.
     *
     * @param tranType     the two-character transaction type code
     * @param tranTypeDesc the transaction type description
     */
    public TransactionType(String tranType, String tranTypeDesc) {
        this.tranType = tranType;
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Returns the two-character transaction type code (primary key).
     *
     * @return the transaction type code
     */
    public String getTranType() {
        return tranType;
    }

    /**
     * Sets the two-character transaction type code (primary key).
     *
     * @param tranType the transaction type code
     */
    public void setTranType(String tranType) {
        this.tranType = tranType;
    }

    /**
     * Returns the transaction type description.
     *
     * @return the transaction type description
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Sets the transaction type description.
     *
     * @param tranTypeDesc the transaction type description
     */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Compares this transaction type to another for equality using the primary
     * key {@code tranType} only, consistent with JPA identity semantics.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code TransactionType} with
     *         an equal {@code tranType}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionType that)) {
            return false;
        }
        return Objects.equals(tranType, that.tranType);
    }

    /**
     * Returns a hash code derived from the primary key {@code tranType} only,
     * consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranType);
    }

    /**
     * Returns a concise, log-friendly representation of this transaction type.
     *
     * @return a string containing the code and description
     */
    @Override
    public String toString() {
        return "TransactionType{tranType='" + tranType
                + "', tranTypeDesc='" + tranTypeDesc + "'}";
    }
}
