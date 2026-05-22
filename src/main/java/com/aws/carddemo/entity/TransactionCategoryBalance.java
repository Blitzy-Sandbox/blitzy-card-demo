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
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code TCATBAL} VSAM KSDS file's
 * {@code TRAN-CAT-BAL-RECORD} record described by
 * {@code app/cpy/CVTRA01Y.cpy} (RECLN 50). Seed data is sourced from
 * {@code app/data/ASCII/tcatbal.txt} (50 rows × 50 bytes = 2500 bytes).
 *
 * <h2>COBOL Provenance — CVTRA01Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 50-byte record:
 * <pre>
 *   01 TRAN-CAT-BAL-RECORD.
 *      05 TRAN-CAT-KEY.
 *         10 TRANCAT-ACCT-ID    PIC 9(11).       --&gt; {@link TransactionCategoryBalanceKey#getTrancatAcctId()}
 *         10 TRANCAT-TYPE-CD    PIC X(02).       --&gt; {@link TransactionCategoryBalanceKey#getTrancatTypeCd()}
 *         10 TRANCAT-CD         PIC 9(04).       --&gt; {@link TransactionCategoryBalanceKey#getTrancatCd()}
 *      05 TRAN-CAT-BAL          PIC S9(09)V99.   --&gt; {@link #tranCatBal} (BigDecimal scale 2)
 *      05 FILLER                PIC X(22).       --&gt; (no Java mapping — padding only)
 * </pre>
 *
 * <h2>CBACT04C — Interest Calculation Update Path</h2>
 *
 * <p>The 50 canonical rows in {@code app/data/ASCII/tcatbal.txt} are
 * read, updated, and rewritten by the COBOL {@code CBACT04C} interest
 * calculator (migrated to
 * {@code com.aws.carddemo.batch.InterestCalculationProcessor} per AAP
 * §0.5.1). For each row the COBOL program:
 * <ol>
 *   <li>READs the {@code TCATBAL} record by composite key.</li>
 *   <li>Looks up the matching {@code DIS-INT-RATE} in the {@code DISCGRP}
 *       catalog (the {@link DiscountGroup} JPA entity) using the
 *       account's {@code ACCT-GROUP-ID} plus the {@code TRANCAT-TYPE-CD}
 *       and {@code TRANCAT-CD} from this row's composite key.</li>
 *   <li>Computes {@code MONTHLY-INTEREST = TRAN-CAT-BAL * DIS-INT-RATE /
 *       1200} with {@code RoundingMode.HALF_EVEN} preserving scale 2
 *       (AAP §0.10.3 financial-precision contract).</li>
 *   <li>Writes a new {@link Transaction} record carrying the computed
 *       interest amount.</li>
 *   <li>REWRITEs the {@code TCATBAL} record with {@code TRAN-CAT-BAL}
 *       set back to {@code 0} (since the interest amount is now
 *       captured in the transaction journal and the category balance
 *       restarts for the next accrual cycle).</li>
 * </ol>
 *
 * <p>Skip conditions:
 * <ul>
 *   <li>{@code ACCT-GROUP-ID = "ZEROAPR   "} — the
 *       {@link DiscountGroup#getDisIntRate()} is {@code 0.00} so the
 *       computed interest is zero and the REWRITE is a no-op.</li>
 *   <li>{@code DISCGRP} lookup returns {@code DFHRESP(NOTFND)} — the
 *       processor re-issues the lookup with {@code "DEFAULT   "} as the
 *       account group, falling back to the catch-all rate.</li>
 * </ul>
 *
 * <h2>Composite Key — Embedded ID</h2>
 *
 * <p>The {@link #key} field carries the composite primary key as a
 * separate {@link TransactionCategoryBalanceKey} value object (the
 * {@code @EmbeddedId} pattern). The composite-key class is the natural
 * Java mapping of the COBOL {@code TRAN-CAT-KEY} group-level item, and
 * keeping it as a discrete value object (rather than three scalar fields
 * on this entity) makes the composite identity surface explicit at the
 * type-system level: methods that operate on a transaction-category
 * balance row accept a single {@link TransactionCategoryBalanceKey}
 * argument rather than a triplet of scalars where call-sites could
 * accidentally swap arguments.
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>The {@link #tranCatBal} field carries the {@code TRAN-CAT-BAL
 * PIC S9(09)V99} signed packed-decimal value. The COBOL field is signed
 * with 9 integer digits + 2 fractional digits (theoretical range
 * {@code -999_999_999.99} to {@code +999_999_999.99}), mapped to a Java
 * {@link BigDecimal} with scale 2 per AAP §0.10.3 ("No float or double
 * used for any monetary value — BigDecimal exclusively"). The
 * REFACTOR-flavor migration agent will add the
 * {@code @Column(name = "tran_cat_bal", precision = 11, scale = 2,
 * nullable = false)} JPA annotation to enforce the scale at the JDBC
 * boundary; the Flyway DDL will create the underlying PostgreSQL column
 * as {@code NUMERIC(11, 2)} (9 integer digits + 2 fractional digits) to
 * mirror the COBOL precision contract.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to
 * satisfy {@link com.aws.carddemo.repository.TransactionCategoryBalanceRepository}
 * compilation and the {@code TransactionCategoryBalanceRepositoryIT}
 * integration test suite per AAP §0.5.1. Subsequent migration agents
 * (REFACTOR flavor) will add JPA annotations ({@code @Entity},
 * {@code @EmbeddedId}, {@code @Column},
 * {@code @Table(name = "transaction_category_balances")}) and Bean
 * Validation constraints ({@code @DecimalMin}/{@code @DecimalMax} on
 * {@link #tranCatBal} to enforce the {@code PIC S9(09)V99} numeric
 * range) once the entity is wired into the Hibernate
 * {@code SessionFactory}. The Flyway scripts under
 * {@code src/main/resources/db/migration/} (also REFACTOR-flavor) will
 * create the {@code transaction_category_balances} table (with the
 * composite primary key {@code (trancat_acct_id CHAR(11),
 * trancat_type_cd CHAR(2), trancat_cd INTEGER)} and the balance column
 * {@code tran_cat_bal NUMERIC(11, 2) NOT NULL}) and populate it with
 * the 50 reference rows from {@code app/data/ASCII/tcatbal.txt}.
 *
 * <h2>Security — toString() Redaction (AAP §0.10.5)</h2>
 *
 * <p>{@link #toString()} surfaces the composite key (via
 * {@link TransactionCategoryBalanceKey#toString()}) but
 * <strong>does NOT include</strong> the monetary {@link #tranCatBal}
 * field. AAP §0.10.5 ("No financial data written to logs at any level")
 * mandates that balance values must not appear in any diagnostic
 * {@code toString()} output that could be captured by a logger or
 * exception stack trace. The redaction is enforced by deliberate
 * omission: callers that need to surface the balance value do so
 * through explicit getter calls in audited code paths, never through
 * the entity's diagnostic representation.
 *
 * @see TransactionCategoryBalanceKey
 * @see com.aws.carddemo.repository.TransactionCategoryBalanceRepository
 */
@Entity
@Table(name = "transaction_category_balances")
public class TransactionCategoryBalance {

    /**
     * Composite primary key holding the
     * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)} 3-tuple.
     * The {@link EmbeddedId} annotation tells Hibernate to materialise
     * the value object from the three key columns of the
     * {@code transaction_category_balances} table on every read and to
     * decompose it back into the three columns on every write.
     */
    @EmbeddedId
    private TransactionCategoryBalanceKey key;

    /**
     * The {@code TRAN-CAT-BAL} balance value per {@code CVTRA01Y.cpy}
     * ({@code PIC S9(09)V99}). Carries the running per-category balance
     * for the parent account, updated by the {@code CBACT04C} interest
     * calculator (migrated to
     * {@code com.aws.carddemo.batch.InterestCalculationProcessor}).
     *
     * <p>The {@link BigDecimal} type and the scale-2 contract are
     * mandated by AAP §0.10.3 ("No float or double used for any monetary
     * value — BigDecimal exclusively"; "BigDecimal rounding mode set to
     * HALF_EVEN (banker's rounding) matching COBOL PICTURE clause
     * precision"). The COBOL {@code PIC S9(09)V99} field allows up to 9
     * integer digits + 2 fractional digits (theoretical range
     * {@code -999_999_999.99} to {@code +999_999_999.99}); the Flyway
     * column type {@code NUMERIC(11, 2)} preserves both the precision
     * and the scale at the storage boundary.
     */
    @Column(name = "tran_cat_bal", precision = 11, scale = 2, nullable = false)
    private BigDecimal tranCatBal;

    /**
     * Default no-arg constructor (required by JPA reflection-based
     * instantiation when Hibernate materialises rows from a query result
     * set).
     */
    public TransactionCategoryBalance() {
        // intentionally empty
    }

    /**
     * @return the composite primary key carrying the
     *         {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)}
     *         3-tuple
     */
    public TransactionCategoryBalanceKey getKey() {
        return key;
    }

    /**
     * @param key the composite primary key carrying the
     *            {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)}
     *            3-tuple
     */
    public void setKey(TransactionCategoryBalanceKey key) {
        this.key = key;
    }

    /**
     * @return the {@code TRAN-CAT-BAL} balance value with scale 2
     *         (BigDecimal — AAP §0.10.3 financial-precision contract)
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * @param tranCatBal the {@code TRAN-CAT-BAL} balance value with
     *                   scale 2 (BigDecimal — AAP §0.10.3
     *                   financial-precision contract)
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    /**
     * Equality is based on the composite primary key {@link #key} alone.
     * JPA-managed entities are considered equal iff they share the same
     * primary key value; the balance is deliberately excluded from
     * equality so transient and managed copies of the same logical
     * reference row compare equal regardless of whether one carries a
     * pre-update balance and the other a post-update balance (the row
     * is the same row, identified by the composite key).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryBalance)) {
            return false;
        }
        TransactionCategoryBalance other = (TransactionCategoryBalance) o;
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
     * Diagnostic string surfacing the composite key only.
     *
     * <p><strong>Security (AAP §0.10.5).</strong> The monetary
     * {@link #tranCatBal} field is deliberately omitted from this output
     * because AAP §0.10.5 ("No financial data written to logs at any
     * level") forbids balance values from appearing in any diagnostic
     * representation that could leak into a logger or stack trace.
     * Callers that need to surface the balance do so through explicit
     * getter calls in audited code paths, never through this entity's
     * diagnostic representation.
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance{"
                + "key=" + key
                + ", tranCatBal=[REDACTED — AAP §0.10.5 no financial data in logs]"
                + '}';
    }
}
