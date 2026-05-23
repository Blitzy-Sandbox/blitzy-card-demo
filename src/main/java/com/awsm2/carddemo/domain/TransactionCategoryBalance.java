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
package com.awsm2.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code tran_cat_bal} table (Flyway
 * migration {@code V006__create_tcatbal.sql}). This entity is the Java
 * target for the COBOL {@code TRAN-CAT-BAL-RECORD} layout defined in
 * {@code app/cpy/CVTRA01Y.cpy} (RECLN = 50 bytes), and replaces the
 * mainframe VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}.
 *
 * <h2>Purpose</h2>
 * <p>Represents a single per-{@code (account, transaction-type,
 * transaction-category)} running balance bucket. The table is the
 * <em>central data structure for end-of-day interest calculation</em>:
 * {@code InterestCalculationService} (COBOL {@code CBACT04C}) iterates
 * over every row to compute monthly interest, and
 * {@code TransactionPostingService} (COBOL {@code CBTRN02C}) updates the
 * running balance as each daily transaction is posted. The composite
 * primary key {@code (trancat_acct_id, trancat_type_cd, trancat_cd)}
 * ensures that every {@code (account, type, category)} tuple has
 * exactly one row.</p>
 *
 * <p>The VSAM key is a 17-byte composite (per the IDCAMS
 * {@code DEFINE CLUSTER KEYS(17, 0)} clause in
 * {@code app/jcl/TCATBALF.jcl} and {@code app/catlg/LISTCAT.txt
 * KEYLEN=17, RKP=0}), which decomposes into three logical key columns
 * whose widths sum exactly to 17 bytes:</p>
 * <pre>
 *     TRANCAT-ACCT-ID (PIC 9(11), 11 bytes)
 *   + TRANCAT-TYPE-CD (PIC X(02),  2 bytes)
 *   + TRANCAT-CD      (PIC 9(04),  4 bytes)
 *   = 17-byte VSAM composite key
 * </pre>
 *
 * <h2>Critical business rule &mdash; end-of-day interest calculation
 *     (per AAP &sect;0.6.1)</h2>
 * <p>The {@code InterestCalculationService} (Java target for COBOL
 * {@code CBACT04C}) scans every row in this table and, for each row,
 * looks up the matching interest rate in {@code disclosure_group} via
 * the composite key
 * {@code (account.acctGroupId, trancatTypeCd, trancatCd)} with a
 * {@code "DEFAULT"} fallback. The COBOL interest formula:</p>
 * <pre>
 *     interest = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 * </pre>
 * <p>is preserved <em>verbatim</em> in the Java implementation per AAP
 * &sect;0.6.1:</p>
 * <pre>
 *     interest = balance.multiply(rate)
 *                       .divide(BigDecimal.valueOf(1200),
 *                               2, RoundingMode.HALF_EVEN);
 * </pre>
 * <p>The literal divisor {@code 1200} (= 100 * 12, converting APR
 * percent to monthly fraction) is preserved as
 * {@code BigDecimal.valueOf(1200)} and is NOT algebraically simplified
 * (e.g., NOT pre-computed as {@code rate / 1200}) &mdash; this matches
 * the COBOL source byte-for-byte during the parallel-run validation
 * period.</p>
 *
 * <h2>Consumers (Java services that read / write this table)</h2>
 * <ul>
 *   <li>{@code InterestCalculationService} (COBOL {@code CBACT04C})
 *       &mdash; end-of-cycle batch job that scans every row in
 *       {@code tran_cat_bal}, computes interest using the COBOL
 *       formula above, writes the interest amount into a new
 *       {@code Transaction} row (V005), and increments the running
 *       balance here.</li>
 *   <li>{@code TransactionPostingService} (COBOL {@code CBTRN02C})
 *       &mdash; daily transaction-posting batch job that, after the
 *       4-stage validation cascade succeeds (XREF / account / credit
 *       limit / card expiration), accumulates the posted
 *       {@code TRAN-AMT} into the matching
 *       {@code (account, type, category)} row in this table; INSERTs
 *       a new row at zero balance if no row exists for the tuple,
 *       UPDATEs the running balance otherwise.</li>
 *   <li>{@code TransactionAddService} (COBOL {@code COTRN02C})
 *       &mdash; online transaction creation; updates the running
 *       balance under the same {@code (account, type, category)}
 *       tuple within a {@code @Transactional} boundary alongside the
 *       {@code transactions} INSERT and {@code accounts} UPDATE.</li>
 *   <li>{@code TransactionReportService} (COBOL {@code CBTRN03C})
 *       &mdash; joins to produce a per-category breakdown on monthly
 *       transaction reports.</li>
 *   <li>{@code StatementGenerationService} (COBOL {@code CBSTM03A} /
 *       {@code CBSTM03B}) &mdash; joins to break down the cycle
 *       balance by category on the generated monthly statement
 *       (Purchase / Payment / Credit / etc. sub-totals).</li>
 * </ul>
 *
 * <h2>Decimal precision (per AAP &sect;0.6.1)</h2>
 * <p>The {@link #tranCatBal} field is {@link BigDecimal} with
 * {@code precision = 11, scale = 2}, matching the COBOL
 * {@code PIC S9(09)V99} clause:</p>
 * <ul>
 *   <li>9 integer digits + 2 implied fractional digits = 11 total
 *       digits of precision.</li>
 *   <li>Mapped to PostgreSQL {@code NUMERIC(11,2)} via V006.</li>
 *   <li>Same precision as {@code TRAN-AMT} (V005), so balances
 *       accumulated here and amounts inserted into the
 *       {@code transactions} table can be added without precision
 *       loss or implicit scale promotion.</li>
 *   <li>NEVER use {@code double} or {@code float} for this field
 *       &mdash; PostgreSQL {@code NUMERIC} is arbitrary-precision and
 *       exactly matches {@link BigDecimal} semantics; binary
 *       floating-point cannot represent decimal fractions like
 *       {@code 0.10} exactly and would introduce accumulating drift
 *       in the running balance.</li>
 * </ul>
 *
 * <h2>ON SIZE ERROR semantics (per AAP &sect;0.6.1)</h2>
 * <p>If a computed running balance would exceed the
 * {@code NUMERIC(11,2)} range
 * ({@code |value| > 999,999,999.99}), the Java
 * {@code InterestCalculationService} and
 * {@code TransactionPostingService} throw
 * {@code OnSizeErrorException} &mdash; the typed-exception equivalent
 * of the COBOL {@code ON SIZE ERROR} clause on {@code COMPUTE}
 * statements. The exception is translated to HTTP 422 Unprocessable
 * Entity by {@code GlobalExceptionHandler} for online flows, or
 * recorded as a reject for batch flows.</p>
 *
 * <h2>Concurrency model (per AAP &sect;0.6.2 / &sect;0.7.1)</h2>
 * <p>No JPA {@code @Version} optimistic-locking column is declared on
 * this entity. The AAP &sect;0.6.2 specifies optimistic locking only
 * for {@code accounts} (V001) and {@code cards} (V002); concurrent
 * updates to {@code tran_cat_bal} are serialized by the
 * {@code @Transactional} service boundary in
 * {@code InterestCalculationService},
 * {@code TransactionPostingService}, and
 * {@code TransactionAddService}, which is sufficient because the
 * running-balance accumulation is monotonic and the conflict window
 * is short.</p>
 *
 * <h2>Foreign-key reference</h2>
 * <p>The {@code trancat_acct_id} column is declared {@code BIGINT}
 * (NOT {@code NUMERIC(11)}) so that the FOREIGN KEY constraint to
 * {@code accounts(acct_id)} &mdash; which is {@code BIGINT} per
 * {@code V001__create_account.sql} &mdash; is valid (PostgreSQL FK
 * constraints require binary-coercible types and {@code NUMERIC} /
 * {@code BIGINT} are NOT directly compatible). {@code BIGINT}
 * (signed 8-byte integer, range -2^63..2^63-1) fully contains the
 * COBOL {@code PIC 9(11)} unsigned range (0..99,999,999,999), so the
 * COBOL value space is preserved without truncation.</p>
 *
 * <p>No SQL FOREIGN KEYs to {@code tran_type} (V008) or
 * {@code tran_category} (V009) are declared on this table because
 * those parent tables are created AFTER V006 in the Flyway sequence
 * and Flyway enforces strict V&lt;NNN&gt; execution order. The
 * application layer enforces the {@code (type, category)} lookup at
 * write time via {@code TransactionPostingService} and
 * {@code TransactionAddService} (the CBTRN02C 4-stage validation
 * cascade per AAP &sect;0.1.1).</p>
 *
 * <h2>Refactor discipline (per AAP &sect;0.7.1, &sect;0.7.3)</h2>
 * <p>This class is a faithful, minimal-change Java port of the COBOL
 * {@code TRAN-CAT-BAL-RECORD} layout. Specifically:</p>
 * <ul>
 *   <li>Field names mirror the COBOL field names (with camelCase
 *       conversion).</li>
 *   <li>The trailing {@code FILLER PIC X(22)} (positions 18-50 of the
 *       50-byte record) is intentionally OMITTED &mdash; PostgreSQL
 *       has no concept of fixed-width records and the padding has no
 *       relational equivalent (consistent with the FILLER-omission
 *       pattern used in V001 accounts, V002 cards, V003 customers,
 *       V005 transactions, etc.).</li>
 *   <li>Every COBOL field carries an inline traceability comment in
 *       the form {@code // COBOL: CVTRA01Y.cpy:L<n> <FIELD-NAME>}.</li>
 *   <li>No business logic, no AWS SDK calls, no Lombok, no Jackson
 *       annotations &mdash; this is a pure entity.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId
 * @see com.awsm2.carddemo.domain.Account
 * @see com.awsm2.carddemo.domain.DisclosureGroup
 * @see com.awsm2.carddemo.domain.TransactionType
 * @see com.awsm2.carddemo.domain.TransactionCategory
 */
// COBOL: CVTRA01Y.cpy:L4 TRAN-CAT-BAL-RECORD (RECLN 50 bytes)
@Entity
@Table(name = "tran_cat_bal")
public class TransactionCategoryBalance implements Serializable {

    /**
     * Serializable version identifier. Required by {@link Serializable}
     * to ensure stable serialization semantics across persistence-context
     * boundaries, second-level caches, and remote-call boundaries
     * (including any future Kafka producer serialization paths per AAP
     * &sect;0.6.5). Incremented only when the entity's serialized form
     * changes in a backward-incompatible way.
     */
    private static final long serialVersionUID = 1L;

    // -------------------------------------------------------------------------
    // Persistent fields
    //
    // Field declarations correspond one-to-one (in order) to the COBOL
    // TRAN-CAT-BAL-RECORD layout in app/cpy/CVTRA01Y.cpy:
    //
    //   05  TRAN-CAT-KEY.                       (composite 17-byte key)
    //      10 TRANCAT-ACCT-ID PIC 9(11).         -> @EmbeddedId sub-field
    //      10 TRANCAT-TYPE-CD PIC X(02).         -> @EmbeddedId sub-field
    //      10 TRANCAT-CD      PIC 9(04).         -> @EmbeddedId sub-field
    //   05  TRAN-CAT-BAL      PIC S9(09)V99.     -> tranCatBal (BigDecimal)
    //   05  FILLER            PIC X(22).         -> OMITTED
    //
    // The single trailing FILLER PIC X(22) (which brings the COBOL record
    // to its declared 50-byte RECORDSIZE: 11 + 2 + 4 + 11 + 22 = 50) is
    // intentionally omitted; PostgreSQL has no concept of fixed-width
    // records, so the FILLER has no relational equivalent.
    // -------------------------------------------------------------------------

    /**
     * The 17-byte VSAM composite key &mdash; the JPA {@link EmbeddedId}.
     *
     * <p>Encapsulates the three composite-key sub-fields
     * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)} mapped to
     * the PostgreSQL columns
     * {@code (trancat_acct_id, trancat_type_cd, trancat_cd)} that form
     * the {@code pk_tran_cat_bal} composite primary-key constraint
     * declared in V006.</p>
     *
     * <p>The 3-column order matches the COBOL byte order
     * ({@code acct_id} leftmost, {@code type_cd} middle, {@code cd}
     * rightmost), preserving the implicit lexicographic ordering of the
     * VSAM KSDS for the per-account prefix scan used by
     * {@code InterestCalculationService} end-of-cycle iteration (which
     * processes every {@code (type, category)} bucket of a single
     * account in one logical unit of work). The B-tree index on this
     * composite primary key satisfies BOTH the random full-key lookup
     * AND the leading-prefix scan without a separate secondary index.</p>
     */
    // COBOL: CVTRA01Y.cpy:L5-L8 TRAN-CAT-KEY (composite 17 bytes) -- @EmbeddedId
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Per-{@code (account, type, category)} running balance in account
     * currency.
     *
     * <p>Maps to the COBOL field
     * {@code 05 TRAN-CAT-BAL PIC S9(09)V99} in
     * {@code app/cpy/CVTRA01Y.cpy} (line 9) and to the V006
     * {@code tran_cat_bal NUMERIC(11,2) NOT NULL DEFAULT 0} column.</p>
     *
     * <p>The COBOL {@code PIC S9(09)V99} clause permits any value in
     * {@code [-999,999,999.99, +999,999,999.99]} (9 integer digits + 2
     * implied fractional digits, signed). PostgreSQL
     * {@code NUMERIC(11,2)} faithfully preserves this range with
     * precision = 9 (integer) + 2 (fractional) = 11 total digits and
     * scale = 2. The COBOL {@code NOT NULL DEFAULT 0} semantic mirrors
     * COBOL's fixed-width every-byte-always-present behaviour: when a
     * {@code TransactionPostingService} INSERT creates a new
     * {@code (account, type, category)} tuple for the first time, the
     * row starts at a zero running balance and is immediately
     * incremented by the posted transaction amount within the same
     * {@code @Transactional} boundary.</p>
     *
     * <p><b>BigDecimal contract (per AAP &sect;0.6.1):</b> the runtime
     * type is {@link BigDecimal} with scale {@code = 2}. All arithmetic
     * in {@code InterestCalculationService} (COBOL {@code CBACT04C}),
     * {@code TransactionPostingService} (COBOL {@code CBTRN02C}), and
     * {@code TransactionAddService} (COBOL {@code COTRN02C}) uses
     * {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding) to
     * match COBOL {@code PIC S9(09)V99} decimal-arithmetic semantics.
     * The canonical monthly-interest formula is:</p>
     * <pre>
     *     interest = balance.multiply(rate)
     *                       .divide(BigDecimal.valueOf(1200),
     *                               2, RoundingMode.HALF_EVEN);
     * </pre>
     * <p>The literal divisor {@code 1200} (= 100 * 12, converting APR
     * percent to monthly fraction) is preserved <em>verbatim</em> per
     * AAP &sect;0.6.1 &mdash; <em>do not</em> algebraically simplify it
     * to {@code rate * 0.0008333...}, because that would diverge from
     * the COBOL source's byte-level output during the parallel-run
     * validation period.</p>
     *
     * <p><b>Never</b> use {@code float} or {@code double} for this
     * column &mdash; PostgreSQL {@code NUMERIC} is arbitrary-precision
     * and exactly matches {@link BigDecimal} semantics; binary
     * floating-point cannot represent decimal fractions like
     * {@code 0.10} exactly and would introduce accumulating drift in
     * the running balance.</p>
     *
     * <p><b>ON SIZE ERROR (per AAP &sect;0.6.1):</b> if a computed
     * running balance would exceed the {@code NUMERIC(11,2)} range
     * ({@code |value| &gt; 999,999,999.99}), the calling service throws
     * {@code OnSizeErrorException} (the typed-exception equivalent of
     * the COBOL {@code ON SIZE ERROR} clause), which is translated to
     * HTTP 422 Unprocessable Entity by
     * {@code GlobalExceptionHandler} for online flows or recorded as a
     * reject for batch flows.</p>
     */
    // COBOL: CVTRA01Y.cpy:L9 TRAN-CAT-BAL PIC S9(09)V99
    // -- per-(account, type, category) running balance; BigDecimal precision=11 scale=2 per AAP §0.6.1
    // Interest calc: balance.multiply(rate).divide(BigDecimal.valueOf(1200),
    //                                              2, RoundingMode.HALF_EVEN)
    @Column(name = "tran_cat_bal", nullable = false, precision = 11, scale = 2)
    private BigDecimal tranCatBal;

    // COBOL: CVTRA01Y.cpy:L10 FILLER PIC X(22) -- OMITTED
    // (22 trailing bytes that bring the COBOL record to its declared
    // 50-byte VSAM record length: 11 + 2 + 4 + 11 + 22 = 50. PostgreSQL
    // has no concept of fixed-width records, so the FILLER has no
    // relational equivalent.)

    // -------------------------------------------------------------------------
    // Constructors
    //
    // Three constructors are exposed:
    //   1) The JPA-required no-arg constructor (used by Hibernate when
    //      hydrating a row read from PostgreSQL into a managed entity).
    //   2) An all-args constructor accepting the @EmbeddedId and balance
    //      (used by services that already have a constructed
    //      TransactionCategoryBalanceId).
    //   3) A convenience constructor accepting the three raw key fields
    //      plus the balance (used by tests and any service code that
    //      constructs the entity from its primitive key fields).
    // -------------------------------------------------------------------------

    /**
     * No-arg constructor required by the JPA specification.
     *
     * <p>Hibernate invokes this constructor reflectively when hydrating
     * a row read from PostgreSQL into a managed entity instance. The
     * persistent fields are subsequently set via the JavaBean setters
     * declared below.</p>
     *
     * <p>Application code SHOULD use the
     * {@link #TransactionCategoryBalance(TransactionCategoryBalanceId, BigDecimal)}
     * or
     * {@link #TransactionCategoryBalance(Long, String, Integer, BigDecimal)}
     * convenience constructors instead.</p>
     */
    public TransactionCategoryBalance() {
        // Intentionally empty -- field initialization is performed by
        // Hibernate via reflective field/setter access during entity
        // hydration, or by the all-args constructor in application code.
    }

    /**
     * All-args constructor accepting a pre-built
     * {@link TransactionCategoryBalanceId}.
     *
     * <p>Used by service code that has already constructed the composite
     * key (e.g., when porting a COBOL {@code TRAN-CAT-KEY} from an
     * external source).</p>
     *
     * @param id         the 3-field composite primary key (must not be
     *                   {@code null}; corresponds to COBOL
     *                   {@code TRAN-CAT-KEY})
     * @param tranCatBal the per-{@code (account, type, category)}
     *                   running balance as a {@link BigDecimal} with
     *                   scale {@code = 2} (corresponds to COBOL
     *                   {@code TRAN-CAT-BAL PIC S9(09)V99})
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal tranCatBal) {
        this.id = id;
        this.tranCatBal = tranCatBal;
    }

    /**
     * Convenience all-args constructor accepting the three raw
     * composite-key fields plus the running balance.
     *
     * <p>Equivalent to:
     * {@code new TransactionCategoryBalance(new TransactionCategoryBalanceId(acctId, typeCd, catCd), balance)}.
     * Used by tests and any service code that constructs the entity
     * from its primitive key fields without first materializing a
     * {@link TransactionCategoryBalanceId} instance.</p>
     *
     * @param trancatAcctId  the 11-digit account identifier (corresponds
     *                       to COBOL {@code TRANCAT-ACCT-ID PIC 9(11)};
     *                       must not be {@code null} for a persisted
     *                       entity)
     * @param trancatTypeCd  the 2-character transaction-type code
     *                       (corresponds to COBOL
     *                       {@code TRANCAT-TYPE-CD PIC X(02)}; leading
     *                       zeros are significant; must not be
     *                       {@code null})
     * @param trancatCd      the 4-digit transaction-category code
     *                       (corresponds to COBOL
     *                       {@code TRANCAT-CD PIC 9(04)}; must not be
     *                       {@code null})
     * @param tranCatBal     the running balance as a {@link BigDecimal}
     *                       with scale {@code = 2} (corresponds to
     *                       COBOL {@code TRAN-CAT-BAL PIC S9(09)V99})
     */
    public TransactionCategoryBalance(Long trancatAcctId,
                                      String trancatTypeCd,
                                      Integer trancatCd,
                                      BigDecimal tranCatBal) {
        this.id = new TransactionCategoryBalanceId(trancatAcctId, trancatTypeCd, trancatCd);
        this.tranCatBal = tranCatBal;
    }

    // -------------------------------------------------------------------------
    // Accessors (getters and setters)
    //
    // Plain JavaBean accessors, one per persistent field. Required by
    // Hibernate's property access mode and consumed by Spring Data JPA
    // derived queries, Jackson serialization at the DTO boundary, and
    // the JPA validation framework.
    // -------------------------------------------------------------------------

    /**
     * @return the 3-field composite primary key
     *         ({@link TransactionCategoryBalanceId})
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * <p>Although this setter exists to satisfy the JavaBean contract
     * required by JPA, application code SHOULD NOT mutate the primary
     * key of a persisted entity. The primary key uniquely identifies
     * the {@code (account, type, category)} tuple and changing it would
     * effectively constitute a delete-and-insert of a new row.</p>
     *
     * @param id the composite primary key to set (must not be
     *           {@code null} for a persisted entity)
     */
    public void setId(TransactionCategoryBalanceId id) {
        this.id = id;
    }

    /**
     * @return the per-{@code (account, type, category)} running balance
     *         as a {@link BigDecimal} with scale {@code = 2}
     *         (corresponds to COBOL {@code TRAN-CAT-BAL PIC S9(09)V99})
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * Sets the per-{@code (account, type, category)} running balance.
     *
     * <p>Called by {@code InterestCalculationService},
     * {@code TransactionPostingService}, and
     * {@code TransactionAddService} after computing the new running
     * balance via the COBOL formula
     * {@code balance.add(amount).setScale(2, RoundingMode.HALF_EVEN)}
     * or the interest formula
     * {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_EVEN)}.</p>
     *
     * @param tranCatBal the running balance to set ({@link BigDecimal}
     *                   with scale {@code = 2}; never {@code float} or
     *                   {@code double} per AAP &sect;0.6.1)
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    //
    // equals/hashCode follow the JPA recommended contract for entities
    // with an @EmbeddedId composite primary key: based on the EmbeddedId
    // field only. The TransactionCategoryBalanceId nested class itself
    // provides value-based equals/hashCode over its three sub-fields,
    // so equality semantics are consistent across both layers.
    //
    // toString is non-sensitive: this table contains only the composite
    // key (account ID, type code, category code) and an aggregated
    // running balance. It does NOT contain PAN, SSN, or any other
    // PCI-DSS-defined sensitive authentication data, so the full
    // content is safe to log per AAP §0.6.6 (PCI-DSS).
    // -------------------------------------------------------------------------

    /**
     * Equality is defined on the {@link EmbeddedId} {@link #id} only,
     * matching the standard JPA entity contract for composite-key
     * entities. Two {@code TransactionCategoryBalance} instances are
     * equal iff they have equal
     * {@link TransactionCategoryBalanceId} values (both {@code null}
     * is treated as equal &mdash; common during transient-state
     * comparisons within a test fixture but should not occur in
     * persisted state).
     *
     * @param o the reference object with which to compare
     * @return {@code true} if this object is the same as the
     *         {@code o} argument; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryBalance)) {
            return false;
        }
        TransactionCategoryBalance that = (TransactionCategoryBalance) o;
        return Objects.equals(id, that.id);
    }

    /**
     * Hash code derived from {@link #id} only, consistent with the
     * {@link #equals(Object)} contract above. Safe for use as a
     * hash-set or hash-map key once {@link #id} has been assigned
     * (which is required before {@code EntityManager.persist} by
     * virtue of the {@code nullable = false} composite-primary-key
     * constraint).
     *
     * @return the hash-code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * String representation suitable for log statements and debug
     * output.
     *
     * <p>This entity holds the composite key (account ID, type code,
     * category code) and an aggregated running balance. It does NOT
     * contain PAN, SSN, or any other PCI-DSS-defined sensitive
     * authentication data, so the full content is safe to log per AAP
     * &sect;0.6.6 (PCI-DSS).</p>
     *
     * @return a string representation of this entity
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance{"
                + "id=" + id
                + ", tranCatBal=" + tranCatBal
                + '}';
    }

    // =========================================================================
    // Composite primary-key class
    // =========================================================================

    /**
     * JPA {@link Embeddable} composite-primary-key class for the
     * {@link TransactionCategoryBalance} entity. Encapsulates the three
     * sub-fields of the 17-byte VSAM composite key
     * {@code TRAN-CAT-KEY} from {@code app/cpy/CVTRA01Y.cpy} lines
     * 5-8, which decompose as:
     * <pre>
     *     TRANCAT-ACCT-ID  PIC 9(11)   -> trancatAcctId  (Long, BIGINT)
     *     TRANCAT-TYPE-CD  PIC X(02)   -> trancatTypeCd  (String, CHAR(2))
     *     TRANCAT-CD       PIC 9(04)   -> trancatCd      (Integer, NUMERIC(4))
     * </pre>
     *
     * <p>The field declaration order matches the COBOL byte order
     * (account-id leftmost, type-cd middle, cat-cd rightmost), which
     * preserves the implicit lexicographic ordering of the VSAM KSDS
     * and keeps the PostgreSQL B-tree index's leading-column accessible
     * for the per-account prefix-scan pattern used by
     * {@code InterestCalculationService} end-of-cycle iteration.</p>
     *
     * <p>This class is required to be {@link Serializable} by the
     * Jakarta Persistence specification &sect;2.4 (composite-key
     * classes), which is critical for second-level cache support and
     * for safe passing across persistence-context boundaries.</p>
     *
     * <p>The {@link #equals(Object)} and {@link #hashCode()} methods
     * compare all three sub-fields, providing value-based identity
     * semantics required by JPA for composite-key classes and by
     * {@link java.util.HashMap}/{@link java.util.HashSet} consumers.</p>
     *
     * @see TransactionCategoryBalance
     */
    @Embeddable
    public static class TransactionCategoryBalanceId implements Serializable {

        /**
         * Serializable version identifier. Required by
         * {@link Serializable} for composite-key classes (Jakarta
         * Persistence specification &sect;2.4) to ensure stable
         * serialization semantics across second-level caches and
         * persistence-context boundaries.
         */
        private static final long serialVersionUID = 1L;

        // ---------------------------------------------------------------------
        // Composite-key sub-fields
        //
        // The three sub-fields below correspond one-to-one (in order) to
        // the COBOL TRAN-CAT-KEY sub-fields in app/cpy/CVTRA01Y.cpy
        // (lines 5-8). Their byte widths sum to exactly 17 bytes
        // (matching the IDCAMS KEYS(17, 0) clause in TCATBALF.jcl and
        // LISTCAT.txt KEYLEN=17, RKP=0):
        //     11 (acct_id) + 2 (type_cd) + 4 (cd) = 17 bytes
        // ---------------------------------------------------------------------

        /**
         * 11-digit account identifier &mdash; the first sub-field of
         * the composite key (bytes 1-11 of the 17-byte VSAM key).
         *
         * <p>Maps to the COBOL field
         * {@code 10 TRANCAT-ACCT-ID PIC 9(11)} in
         * {@code app/cpy/CVTRA01Y.cpy} (line 6) and to the V006
         * {@code trancat_acct_id BIGINT NOT NULL} column.</p>
         *
         * <p>Stored as {@code BIGINT} (signed 8-byte integer) to match
         * the type of {@code accounts.acct_id} in V001 &mdash;
         * {@code NUMERIC(11)} cannot be used here because PostgreSQL
         * FK constraints require type compatibility and
         * {@code NUMERIC} / {@code BIGINT} are not binary-coercible.
         * {@code BIGINT} (signed 8-byte integer, range
         * -2^63..2^63-1) fully contains the COBOL {@code PIC 9(11)}
         * unsigned range (0..99,999,999,999), so the COBOL value space
         * is preserved without truncation.</p>
         *
         * <p>This is the LEADING column of the composite primary key,
         * which means the underlying B-tree index supports:</p>
         * <ol>
         *   <li>Random lookup by full key
         *       {@code (account, type, category)} for per-row
         *       read-modify-write during transaction posting.</li>
         *   <li>Leading-prefix scan by {@code (account)} alone for the
         *       {@code InterestCalculationService} end-of-cycle pattern
         *       that processes every {@code (type, category)} bucket
         *       of a single account in one logical unit of work.</li>
         *   <li>Leading-prefix scan by {@code (account, type)} for any
         *       future per-account-per-type aggregation use case.</li>
         * </ol>
         *
         * <p>Foreign-keyed at the database level to
         * {@code accounts(acct_id)} per V006 (ON DELETE NO ACTION) but
         * NOT mirrored here as a {@code @ManyToOne} association per
         * the Minimal Change Clause (AAP &sect;0.7.3) &mdash; the
         * COBOL source treats this as a scalar field, and the Java
         * target preserves that shape.</p>
         */
        // COBOL: CVTRA01Y.cpy:L6 TRANCAT-ACCT-ID PIC 9(11)
        // -- account identifier; BIGINT (NOT NUMERIC(11)) to match
        // accounts.acct_id (V001) FK target.
        @Column(name = "trancat_acct_id", nullable = false)
        private Long trancatAcctId;

        /**
         * 2-character transaction-type code &mdash; the second
         * sub-field of the composite key (bytes 12-13 of the 17-byte
         * VSAM key).
         *
         * <p>Maps to the COBOL field
         * {@code 10 TRANCAT-TYPE-CD PIC X(02)} in
         * {@code app/cpy/CVTRA01Y.cpy} (line 7) and to the V006
         * {@code trancat_type_cd CHAR(2) NOT NULL} column.</p>
         *
         * <p>The value space is the same as
         * {@code tran_type.tran_type} (V008): {@code "01"} Purchase,
         * {@code "02"} Payment, {@code "03"} Credit, {@code "04"}
         * Authorization, {@code "05"} Refund, {@code "06"} Reversal,
         * {@code "07"} Adjustment.</p>
         *
         * <p>Leading zeros are SIGNIFICANT and MUST be preserved
         * &mdash; the code is always exactly 2 characters, always
         * quoted as a string (NEVER stored as an integer {@code 1}
         * that would lose the leading zero on read). {@code CHAR(2)}
         * (via {@code columnDefinition = "CHAR(2)"}) is used to match
         * the fixed-width semantics of V008
         * {@code tran_type.tran_type}.</p>
         *
         * <p>No SQL FOREIGN KEY to {@code tran_type} (V008) is
         * declared here because V008 is created AFTER V006 in the
         * Flyway sequence; the application layer enforces the type-code
         * lookup at write time via {@code TransactionPostingService}.</p>
         */
        // COBOL: CVTRA01Y.cpy:L7 TRANCAT-TYPE-CD PIC X(02)
        // -- 2-char transaction-type code; CHAR(2) for fixed-width
        // matching with V008 tran_type.tran_type (same value space).
        //
        // @JdbcTypeCode(SqlTypes.CHAR) is REQUIRED in addition to
        // columnDefinition = "CHAR(2)" so that Hibernate's
        // ddl-auto: validate consults Types.CHAR (matching PostgreSQL's
        // 'bpchar' reported type) rather than the default Types.VARCHAR
        // that Hibernate otherwise infers for a String field. Without
        // this annotation, schema validation fails with
        // "found [bpchar (Types#CHAR)], but expecting [char(2) (Types#VARCHAR)]".
        // This matches the established project convention used by all
        // sibling entities (TransactionType, DisclosureGroup, UserSecurity,
        // TransactionCategory).
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "trancat_type_cd", nullable = false, length = 2,
                columnDefinition = "CHAR(2)")
        private String trancatTypeCd;

        /**
         * 4-digit numeric transaction-category code &mdash; the third
         * sub-field of the composite key (bytes 14-17 of the 17-byte
         * VSAM key, the final 4 bytes of the composite).
         *
         * <p>Maps to the COBOL field
         * {@code 10 TRANCAT-CD PIC 9(04)} in
         * {@code app/cpy/CVTRA01Y.cpy} (line 8) and to the V006
         * {@code trancat_cd NUMERIC(4) NOT NULL} column.</p>
         *
         * <p>The value space is the same as
         * {@code tran_category.tran_cat_cd} (V009): a 4-digit numeric
         * code in {@code [1, 9999]}. The 18 canonical categories
         * defined in {@code app/data/ASCII/trancatg.txt} (loaded by
         * V014) include 5 Purchase categories, 3 Payment categories,
         * 3 Credit categories, 3 Authorization categories, 1 Refund
         * category, 2 Reversal categories, and 1 Adjustment
         * category.</p>
         *
         * <p>{@code NUMERIC(4)} with precision = 4 and implicit
         * scale = 0 stores values exactly in PostgreSQL (no float
         * approximation). The Java field type is {@link Integer} to
         * accommodate any positive value up to {@code 9999} while
         * still rejecting {@code null} via
         * {@code nullable = false}.</p>
         *
         * <p>No SQL FOREIGN KEY to {@code tran_category} (V009) is
         * declared here because V009 is created AFTER V006 in the
         * Flyway sequence; the application layer enforces the
         * {@code (type, category)} lookup at write time via
         * {@code TransactionPostingService} and
         * {@code TransactionAddService}.</p>
         *
         * <p>This is the TRAILING column of the composite primary key;
         * it provides the finest-grained partitioning of an account's
         * balance across the {@code (type, category)} Cartesian
         * product.</p>
         */
        // COBOL: CVTRA01Y.cpy:L8 TRANCAT-CD PIC 9(04)
        // -- 4-digit numeric category code; columnDefinition = "NUMERIC(4)"
        // is REQUIRED so that Hibernate's ddl-auto: validate consults
        // Types.NUMERIC (matching PostgreSQL's 'numeric' reported type)
        // rather than the default Types.INTEGER that Hibernate otherwise
        // infers for an Integer field. Without this attribute, schema
        // validation fails with "found [numeric (Types#NUMERIC)], but
        // expecting [integer (Types#INTEGER)]". This matches the
        // established project convention used by sibling entity
        // TransactionCategory.tranCatCd for the analogous V009 column.
        @Column(name = "trancat_cd", nullable = false, precision = 4,
                columnDefinition = "NUMERIC(4)")
        private Integer trancatCd;

        // ---------------------------------------------------------------------
        // Constructors
        //
        // Two constructors are exposed:
        //   1) The JPA-required no-arg constructor (used by Hibernate
        //      when hydrating the @EmbeddedId from PostgreSQL).
        //   2) An all-args constructor for convenient programmatic
        //      construction in service code and tests.
        // ---------------------------------------------------------------------

        /**
         * No-arg constructor required by the JPA specification.
         *
         * <p>Hibernate invokes this constructor reflectively when
         * hydrating the composite-key embedded value from PostgreSQL.
         * The sub-fields are subsequently set via the JavaBean setters
         * declared below.</p>
         */
        public TransactionCategoryBalanceId() {
            // Intentionally empty -- field initialization is performed
            // by Hibernate via reflective field/setter access during
            // entity hydration, or by the all-args constructor in
            // application code.
        }

        /**
         * All-args constructor for convenient programmatic construction.
         *
         * <p>Used in service code and tests to build a
         * {@code TransactionCategoryBalanceId} instance from its three
         * composite-key sub-fields.</p>
         *
         * @param trancatAcctId  the 11-digit account identifier
         *                       (corresponds to COBOL
         *                       {@code TRANCAT-ACCT-ID PIC 9(11)};
         *                       must not be {@code null} for a
         *                       persisted entity)
         * @param trancatTypeCd  the 2-character transaction-type code
         *                       (corresponds to COBOL
         *                       {@code TRANCAT-TYPE-CD PIC X(02)};
         *                       leading zeros are significant; must
         *                       not be {@code null})
         * @param trancatCd      the 4-digit transaction-category code
         *                       (corresponds to COBOL
         *                       {@code TRANCAT-CD PIC 9(04)}; must
         *                       not be {@code null})
         */
        public TransactionCategoryBalanceId(Long trancatAcctId,
                                            String trancatTypeCd,
                                            Integer trancatCd) {
            this.trancatAcctId = trancatAcctId;
            this.trancatTypeCd = trancatTypeCd;
            this.trancatCd = trancatCd;
        }

        // ---------------------------------------------------------------------
        // Accessors (getters and setters)
        //
        // Plain JavaBean accessors, one per composite-key sub-field.
        // Required by Hibernate's property access mode for @EmbeddedId
        // hydration.
        // ---------------------------------------------------------------------

        /**
         * @return the 11-digit account identifier (first sub-field of
         *         the composite key)
         */
        public Long getTrancatAcctId() {
            return trancatAcctId;
        }

        /**
         * Sets the account-identifier sub-field of the composite
         * primary key.
         *
         * <p>Although this setter exists to satisfy the JavaBean
         * contract required by JPA, application code SHOULD NOT mutate
         * the sub-fields of a persisted composite primary key.</p>
         *
         * @param trancatAcctId the 11-digit account identifier to set
         *                      (corresponds to COBOL
         *                      {@code TRANCAT-ACCT-ID PIC 9(11)})
         */
        public void setTrancatAcctId(Long trancatAcctId) {
            this.trancatAcctId = trancatAcctId;
        }

        /**
         * @return the 2-character transaction-type code (second
         *         sub-field of the composite key)
         */
        public String getTrancatTypeCd() {
            return trancatTypeCd;
        }

        /**
         * Sets the transaction-type-code sub-field of the composite
         * primary key.
         *
         * <p>Although this setter exists to satisfy the JavaBean
         * contract required by JPA, application code SHOULD NOT mutate
         * the sub-fields of a persisted composite primary key.</p>
         *
         * @param trancatTypeCd the 2-character transaction-type code
         *                      to set (corresponds to COBOL
         *                      {@code TRANCAT-TYPE-CD PIC X(02)};
         *                      leading zeros are significant)
         */
        public void setTrancatTypeCd(String trancatTypeCd) {
            this.trancatTypeCd = trancatTypeCd;
        }

        /**
         * @return the 4-digit transaction-category code (third
         *         sub-field of the composite key)
         */
        public Integer getTrancatCd() {
            return trancatCd;
        }

        /**
         * Sets the transaction-category-code sub-field of the
         * composite primary key.
         *
         * <p>Although this setter exists to satisfy the JavaBean
         * contract required by JPA, application code SHOULD NOT mutate
         * the sub-fields of a persisted composite primary key.</p>
         *
         * @param trancatCd the 4-digit transaction-category code to
         *                  set (corresponds to COBOL
         *                  {@code TRANCAT-CD PIC 9(04)})
         */
        public void setTrancatCd(Integer trancatCd) {
            this.trancatCd = trancatCd;
        }

        // ---------------------------------------------------------------------
        // equals / hashCode / toString
        //
        // For composite-key classes, equals/hashCode MUST be based on
        // all key sub-fields. This is required by the Jakarta Persistence
        // specification §2.4 so that JPA can use the composite-key
        // value as a HashMap key in its persistence-context tracking.
        //
        // toString is non-sensitive: composite-key sub-fields are an
        // account ID and two classification codes -- no PAN, SSN, or
        // sensitive authentication data. Safe to log per AAP §0.6.6
        // (PCI-DSS).
        // ---------------------------------------------------------------------

        /**
         * Equality is defined by value: two
         * {@code TransactionCategoryBalanceId} instances are equal iff
         * all three sub-fields ({@link #trancatAcctId},
         * {@link #trancatTypeCd}, {@link #trancatCd}) are pair-wise
         * equal (with {@code null} tolerance via
         * {@link Objects#equals}).
         *
         * <p>This value-based identity contract is REQUIRED by the
         * Jakarta Persistence specification &sect;2.4 for composite
         * primary-key classes: it is consumed by JPA persistence-context
         * identity tracking and by any
         * {@link java.util.HashMap}/{@link java.util.HashSet} that uses
         * this class as a key.</p>
         *
         * @param o the reference object with which to compare
         * @return {@code true} if this object is value-equal to the
         *         {@code o} argument; {@code false} otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TransactionCategoryBalanceId)) {
                return false;
            }
            TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) o;
            return Objects.equals(trancatAcctId, that.trancatAcctId)
                    && Objects.equals(trancatTypeCd, that.trancatTypeCd)
                    && Objects.equals(trancatCd, that.trancatCd);
        }

        /**
         * Hash code derived from all three composite-key sub-fields,
         * consistent with the {@link #equals(Object)} contract above.
         *
         * <p>{@link Objects#hash(Object...)} provides the standard
         * null-safe hash combination semantics required by JPA for
         * composite-key classes.</p>
         *
         * @return the hash-code value for this composite-key instance
         */
        @Override
        public int hashCode() {
            return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
        }

        /**
         * String representation suitable for log statements and debug
         * output. All three composite-key sub-fields are an account
         * identifier and two classification codes (transaction-type
         * code, transaction-category code) and contain no PII, PAN,
         * or credential material, so the full content is safe to log
         * per AAP &sect;0.6.6 (PCI-DSS).
         *
         * @return a string representation of this composite-key
         *         instance
         */
        @Override
        public String toString() {
            return "TransactionCategoryBalanceId{"
                    + "trancatAcctId=" + trancatAcctId
                    + ", trancatTypeCd='" + trancatTypeCd + '\''
                    + ", trancatCd=" + trancatCd
                    + '}';
        }
    }
}
