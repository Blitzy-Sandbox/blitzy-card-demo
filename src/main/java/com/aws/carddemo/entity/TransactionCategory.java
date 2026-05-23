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

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code TRANCATG} VSAM KSDS file's
 * {@code TRAN-CAT-RECORD} record described by {@code app/cpy/CVTRA04Y.cpy}.
 *
 * <h2>COBOL Provenance — CVTRA04Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 60-byte record:
 * <pre>
 *   01 TRAN-CAT-RECORD.
 *      05 TRAN-CAT-KEY.
 *         10 TRAN-TYPE-CD           PIC X(02).   --&gt; {@link TransactionCategoryKey#getTranTypeCd()}  (composite key part 1)
 *         10 TRAN-CAT-CD            PIC 9(04).   --&gt; {@link TransactionCategoryKey#getTranCatCd()}   (composite key part 2)
 *      05 TRAN-CAT-TYPE-DESC        PIC X(50).   --&gt; {@link #tranCatTypeDesc}
 *      05 FILLER                    PIC X(04).
 * </pre>
 *
 * <h2>Reference Data (Read-Only Catalog)</h2>
 *
 * <p>{@code TRAN-CAT-RECORD} is reference data — read-only after the
 * initial Flyway seed (no business workflow updates it at runtime). The
 * 18 canonical rows from {@code app/data/ASCII/trancatg.txt} (composite
 * key {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} -&gt; description):
 *
 * <pre>
 *   (01, 0001)  Regular Sales Draft
 *   (01, 0002)  Regular Cash Advance
 *   (01, 0003)  Convenience Check Debit
 *   (01, 0004)  ATM Cash Advance
 *   (01, 0005)  Interest Amount
 *   (02, 0001)  Cash payment
 *   (02, 0002)  Electronic payment
 *   (02, 0003)  Check payment
 *   (03, 0001)  Credit to Account
 *   (03, 0002)  Credit to Purchase balance
 *   (03, 0003)  Credit to Cash balance
 *   (04, 0001)  Zero dollar authorization
 *   (04, 0002)  Online purchase authorization
 *   (04, 0003)  Travel booking authorization
 *   (05, 0001)  Refund credit
 *   (06, 0001)  Fraud reversal
 *   (06, 0002)  Non-fraud reversal
 *   (07, 0001)  Sales draft credit adjustment
 * </pre>
 *
 * <p>The composite key {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} is referenced
 * by {@code TRAN-TYPE-CD + TRAN-CAT-CD} on {@code TRAN-RECORD}
 * ({@code CVTRA05Y.cpy} -&gt; {@link Transaction#getTransactionTypeCode()}
 * and {@link Transaction#getTransactionCategoryCode()}); the migration
 * carries the same 2-character + 4-digit key bytes verbatim on the
 * transaction record rather than introducing a synthetic surrogate
 * key, preserving the COBOL foreign-key surface unchanged.
 *
 * <h2>Composite Key — Embedded ID</h2>
 *
 * <p>The {@link #key} field carries the composite primary key as a
 * separate {@link TransactionCategoryKey} value object (the
 * {@code @EmbeddedId} pattern). The composite-key class is the natural
 * Java mapping of the COBOL {@code TRAN-CAT-KEY} group-level item, and
 * keeping it as a discrete value object (rather than two scalar fields
 * on this entity) makes the composite identity surface explicit at the
 * type-system level: methods that operate on a transaction-category row
 * accept a single {@link TransactionCategoryKey} argument rather than
 * a pair of scalars where call-sites could accidentally swap the type
 * and category codes.
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>No monetary fields — this entity carries only the composite key
 *       and a 50-character free-form description. No
 *       {@link java.math.BigDecimal} is needed (AAP §0.10.3 financial
 *       precision rules do not apply to this entity).</li>
 *   <li>No PII fields — only synthetic operational codes (e.g.
 *       {@code "01"}, {@code 1}) and descriptive labels (e.g.
 *       {@code "Regular Sales Draft"}). AAP §0.10.5 PII redaction rules
 *       do not constrain this entity's {@link #toString()} output.</li>
 *   <li>No {@code @Version} field — this is reference data, not subject
 *       to optimistic locking. The Flyway seed populates the table once
 *       and no business workflow mutates rows thereafter (the AAP
 *       §0.10.1 Require Test Coverage Rule's reference-data IT only
 *       covers the read path; the write path is exercised solely by the
 *       Flyway script lifecycle and by the synthetic-insert tests in
 *       {@code TransactionCategoryRepositoryIT}).</li>
 * </ul>
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.repository.TransactionCategoryRepository} compilation
 * and the {@code TransactionCategoryRepositoryIT} integration test suite per
 * AAP §0.5.1. Subsequent migration agents (REFACTOR flavor) will add JPA
 * annotations ({@code @Entity}, {@code @EmbeddedId}, {@code @Column},
 * {@code @Table(name = "transaction_categories")}) and Bean Validation
 * constraints ({@code @Size(min = 50, max = 50)} or
 * {@code @Size(max = 50)} on {@link #tranCatTypeDesc}) once the entity
 * is wired into the Hibernate {@code SessionFactory}. The Flyway scripts
 * under {@code src/main/resources/db/migration/} (also REFACTOR-flavor)
 * will create the {@code transaction_categories} table (with the
 * composite primary key {@code (tran_type_cd CHAR(2), tran_cat_cd INTEGER)})
 * and populate it with the 18 reference rows from
 * {@code app/data/ASCII/trancatg.txt}.
 *
 * <h2>Security — toString() Includes All Fields</h2>
 *
 * <p>{@link #toString()} surfaces both the composite key (via
 * {@link TransactionCategoryKey#toString()}) and the description. Neither
 * field carries PII or financial data (the codes are 2-character + 4-digit
 * operational identifiers, and the descriptions are non-sensitive labels
 * such as {@code "Regular Sales Draft"}). Per AAP §0.10.5 reference-data
 * entities are unrestricted in their diagnostic {@code toString} output.
 *
 * @see TransactionCategoryKey
 * @see com.aws.carddemo.repository.TransactionCategoryRepository
 * @see Transaction#getTransactionTypeCode()
 * @see Transaction#getTransactionCategoryCode()
 */
@Entity
@Table(name = "transaction_categories")
public class TransactionCategory {

    /**
     * Composite primary key holding the {@code (TRAN-TYPE-CD, TRAN-CAT-CD)}
     * tuple. The {@link EmbeddedId} annotation tells Hibernate to
     * materialise the value object from the two key columns of the
     * {@code transaction_categories} table on every read and to decompose
     * it back into the two columns on every write.
     */
    @EmbeddedId
    private TransactionCategoryKey key;

    /**
     * 50-character {@code TRAN-CAT-TYPE-DESC} free-form description per
     * {@code CVTRA04Y.cpy} ({@code PIC X(50)}). Carries the human-readable
     * label corresponding to {@link #key} (e.g. {@code "Regular Sales Draft"}
     * for {@code (TRAN-TYPE-CD = "01", TRAN-CAT-CD = 1)}). The COBOL
     * fixed-width field right-pads with spaces; the Java migration stores
     * either the trimmed value or the space-padded value verbatim
     * depending on the seed-script {@code RTRIM} policy (REFACTOR-flavor
     * decision — see {@code V3__seed.sql} once that script lands).
     */
    @Column(name = "tran_cat_type_desc", nullable = false, length = 50)
    private String tranCatTypeDesc;

    /**
     * Default no-arg constructor (required by JPA reflection-based
     * instantiation when Hibernate materialises rows from a query result
     * set).
     */
    public TransactionCategory() {
        // intentionally empty
    }

    /**
     * @return the composite primary key carrying the
     *         {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} tuple
     */
    public TransactionCategoryKey getKey() {
        return key;
    }

    /**
     * @param key the composite primary key carrying the
     *            {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} tuple
     */
    public void setKey(TransactionCategoryKey key) {
        this.key = key;
    }

    /**
     * @return the 50-character {@code TRAN-CAT-TYPE-DESC} description
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * @param tranCatTypeDesc the 50-character {@code TRAN-CAT-TYPE-DESC}
     *                        description
     */
    public void setTranCatTypeDesc(String tranCatTypeDesc) {
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Equality is based on the composite primary key {@link #key} alone.
     * JPA-managed entities are considered equal iff they share the same
     * primary key value; the description is deliberately excluded from
     * equality so transient and managed copies of the same logical
     * reference row compare equal even when one carries a space-padded
     * description and the other a trimmed copy.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategory)) {
            return false;
        }
        TransactionCategory other = (TransactionCategory) o;
        return Objects.equals(key, other.key);
    }

    /**
     * Hash by composite primary key, consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    /**
     * Diagnostic string surfacing the composite key and the description.
     * Neither field carries PII or financial data — the key components
     * are operational identifiers and the description is a non-sensitive
     * label — so AAP §0.10.5 redaction rules do not constrain this
     * output.
     */
    @Override
    public String toString() {
        return "TransactionCategory{"
                + "key=" + key
                + ", tranCatTypeDesc='" + tranCatTypeDesc + '\''
                + '}';
    }
}
