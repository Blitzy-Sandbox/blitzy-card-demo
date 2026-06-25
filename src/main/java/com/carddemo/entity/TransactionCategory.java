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
import java.util.Objects;

/**
 * JPA entity for the transaction-category reference lookup record.
 *
 * <p>Maps the legacy COBOL {@code TRAN-CAT-RECORD} (copybook {@code CVTRA04Y}
 * at source commit {@code 27d6c6f}, record length 60) to the PostgreSQL
 * {@code transaction_category} table. The record associates a transaction
 * type/category key with its human-readable description and is read-only
 * reference data sourced from the {@code trancatg.txt} fixture.
 *
 * <p>The composite VSAM key {@code TRAN-CAT-KEY} (the first six bytes:
 * {@code TRAN-TYPE-CD PIC X(02)} followed by {@code TRAN-CAT-CD PIC 9(04)}) is
 * modelled by the {@link TransactionCategoryId} {@code @Embeddable} and consumed
 * here through {@code @EmbeddedId}; those key columns are therefore declared on
 * the identifier class and are not re-declared on this entity. The trailing
 * COBOL {@code FILLER PIC X(04)} is reserved padding and is not persisted.
 */
@Entity
@Table(name = "transaction_category")
public class TransactionCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key &mdash; COBOL {@code TRAN-CAT-KEY} group
     * ({@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}). Mapped through the
     * {@link TransactionCategoryId} embeddable, which owns the
     * {@code tran_type_cd} and {@code tran_cat_cd} columns of the
     * {@code transaction_category} primary key.
     */
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Category type description &mdash; COBOL {@code TRAN-CAT-TYPE-DESC
     * PIC X(50)}. Free-text reference label mapped to the nullable
     * {@code VARCHAR(50)} column {@code tran_cat_type_desc}.
     */
    @Column(name = "tran_cat_type_desc", length = 50)
    private String tranCatTypeDesc;

    /**
     * No-argument constructor required by the JPA provider.
     */
    public TransactionCategory() {
        // No-args constructor required by JPA.
    }

    /**
     * Creates a fully populated transaction-category record.
     *
     * @param id              the composite key ({@code TRAN-CAT-KEY})
     * @param tranCatTypeDesc the category type description
     *                        ({@code TRAN-CAT-TYPE-DESC})
     */
    public TransactionCategory(TransactionCategoryId id, String tranCatTypeDesc) {
        this.id = id;
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Returns the composite primary key ({@code TRAN-CAT-KEY}).
     *
     * @return the composite key, or {@code null} when unset
     */
    public TransactionCategoryId getId() {
        return id;
    }

    /**
     * Sets the composite primary key ({@code TRAN-CAT-KEY}).
     *
     * @param id the composite key to set
     */
    public void setId(TransactionCategoryId id) {
        this.id = id;
    }

    /**
     * Returns the category type description ({@code TRAN-CAT-TYPE-DESC}).
     *
     * @return the description, or {@code null} when unset
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * Sets the category type description ({@code TRAN-CAT-TYPE-DESC}).
     *
     * @param tranCatTypeDesc the description to set
     */
    public void setTranCatTypeDesc(String tranCatTypeDesc) {
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Equality is based solely on the composite primary key {@code id}, matching
     * the VSAM KSDS identity semantics of the legacy record.
     *
     * @param o the object to compare with
     * @return {@code true} when both records share the same composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategory other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    /**
     * Hash code derived from the composite primary key {@code id}, consistent
     * with {@link #equals(Object)}.
     *
     * @return the primary-key hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Returns a concise, human-readable representation of this category record.
     *
     * @return a string describing the composite key and the type description
     */
    @Override
    public String toString() {
        return "TransactionCategory{"
                + "id=" + id
                + ", tranCatTypeDesc='" + tranCatTypeDesc + '\''
                + '}';
    }
}
