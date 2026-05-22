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
package com.aws.carddemo.entity;

import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code TRANTYPE} VSAM KSDS file's
 * {@code TRAN-TYPE-RECORD} record described by {@code app/cpy/CVTRA03Y.cpy}.
 *
 * <h2>COBOL Provenance — CVTRA03Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 60-byte record:
 * <pre>
 *   01 TRAN-TYPE-RECORD.
 *      05 TRAN-TYPE                  PIC X(02).   --&gt; {@link #tranType}      (primary key)
 *      05 TRAN-TYPE-DESC             PIC X(50).   --&gt; {@link #tranTypeDesc}
 *      05 FILLER                     PIC X(08).
 * </pre>
 *
 * <h2>Reference Data (Read-Only Catalog)</h2>
 *
 * <p>{@code TRAN-TYPE-RECORD} is reference data — read-only after the
 * initial Flyway seed (no business workflow updates it at runtime). The
 * 7 canonical rows from {@code app/data/ASCII/trantype.txt}:
 *
 * <pre>
 *   01  Purchase
 *   02  Payment
 *   03  Credit
 *   04  Authorization
 *   05  Refund
 *   06  Reversal
 *   07  Adjustment
 * </pre>
 *
 * <p>The 2-character {@code TRAN-TYPE} primary key is referenced by
 * {@code TRAN-TYPE-CD} on {@code TRAN-RECORD} ({@code CVTRA05Y.cpy} →
 * {@link Transaction#getTransactionTypeCode()}); the migration carries
 * the 2-character code byte-for-byte on the transaction record rather
 * than introducing a synthetic surrogate key, preserving the COBOL
 * foreign-key surface unchanged.
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>No monetary fields — this entity carries only the 2-character
 *       type code and a 50-character free-form description. No
 *       {@link java.math.BigDecimal} is needed (AAP §0.10.3 financial
 *       precision rules do not apply to this entity).</li>
 *   <li>No PII fields — only synthetic operational codes (e.g.
 *       {@code "01"}) and descriptive labels (e.g. {@code "Purchase"}).
 *       AAP §0.10.5 PII redaction rules do not constrain this entity's
 *       {@link #toString()} output.</li>
 *   <li>No {@code @Version} field — this is reference data, not subject
 *       to optimistic locking. The Flyway seed populates the table once
 *       and no business workflow mutates rows thereafter.</li>
 * </ul>
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.repository.TransactionTypeRepository} compilation
 * and the {@code TransactionTypeRepositoryIT} integration test suite per
 * AAP §0.5.1. Subsequent migration agents (REFACTOR flavor) will add JPA
 * annotations ({@code @Entity}, {@code @Id}, {@code @Column},
 * {@code @Table(name = "transaction_types")}) and Bean Validation
 * constraints ({@code @Size(min = 2, max = 2)} on {@link #tranType},
 * {@code @Size(max = 50)} on {@link #tranTypeDesc}) once the entity is
 * wired into the Hibernate {@code SessionFactory}. The Flyway scripts
 * under {@code src/main/resources/db/migration/} (also REFACTOR-flavor)
 * will create the {@code transaction_types} table and populate it with
 * the 7 reference rows from {@code app/data/ASCII/trantype.txt}.
 *
 * <h2>Security — toString() Includes All Fields</h2>
 *
 * <p>{@link #toString()} surfaces both fields ({@link #tranType} and
 * {@link #tranTypeDesc}). Neither field carries PII or financial data
 * (the codes are 2-character operational identifiers, and the
 * descriptions are non-sensitive labels such as {@code "Purchase"}). Per
 * AAP §0.10.5 reference-data entities are unrestricted in their
 * diagnostic toString output.
 *
 * @see com.aws.carddemo.repository.TransactionTypeRepository
 * @see Transaction#getTransactionTypeCode()
 */
public class TransactionType {

    /**
     * 2-character {@code TRAN-TYPE} primary key per {@code CVTRA03Y.cpy}
     * ({@code PIC X(02)}). Canonical values from
     * {@code app/data/ASCII/trantype.txt}: {@code "01"} (Purchase),
     * {@code "02"} (Payment), {@code "03"} (Credit), {@code "04"}
     * (Authorization), {@code "05"} (Refund), {@code "06"} (Reversal),
     * {@code "07"} (Adjustment). Stored as a fixed-width 2-character
     * string to preserve the COBOL key format byte-for-byte across
     * VSAM-to-PostgreSQL migration.
     */
    private String tranType;

    /**
     * 50-character {@code TRAN-TYPE-DESC} free-form description per
     * {@code CVTRA03Y.cpy} ({@code PIC X(50)}). Carries the
     * human-readable label corresponding to {@link #tranType}
     * (e.g. {@code "Purchase"} for type {@code "01"}). The COBOL
     * fixed-width field right-pads with spaces; the Java migration
     * stores the trimmed value or the space-padded value verbatim
     * depending on the seed-script {@code RTRIM} policy (REFACTOR-flavor
     * decision).
     */
    private String tranTypeDesc;

    /** Default no-arg constructor (required by JPA reflection-based instantiation). */
    public TransactionType() {
        // intentionally empty
    }

    /** @return the 2-character {@code TRAN-TYPE} primary key */
    public String getTranType() {
        return tranType;
    }

    /** @param tranType the 2-character {@code TRAN-TYPE} primary key */
    public void setTranType(String tranType) {
        this.tranType = tranType;
    }

    /** @return the 50-character {@code TRAN-TYPE-DESC} description */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /** @param tranTypeDesc the 50-character {@code TRAN-TYPE-DESC} description */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Equality is based on the primary key {@link #tranType} alone. JPA-managed
     * entities are considered equal iff they share the same primary key value;
     * the description is deliberately excluded from equality so transient and
     * managed copies of the same logical reference row compare equal even when
     * one carries a space-padded description and the other a trimmed copy.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionType)) {
            return false;
        }
        TransactionType other = (TransactionType) o;
        return Objects.equals(tranType, other.tranType);
    }

    /** Hash by primary key, consistent with {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        return Objects.hash(tranType);
    }

    /**
     * Diagnostic string surfacing both fields. Neither field carries PII
     * or financial data — the type code is a 2-character operational
     * identifier and the description is a non-sensitive label — so AAP
     * §0.10.5 redaction rules do not constrain this output.
     */
    @Override
    public String toString() {
        return "TransactionType{"
                + "tranType='" + tranType + '\''
                + ", tranTypeDesc='" + tranTypeDesc + '\''
                + '}';
    }
}
