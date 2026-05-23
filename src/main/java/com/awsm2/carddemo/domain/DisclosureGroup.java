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
 * JPA {@link Entity} mapped to the {@code disclosure_group} lookup table
 * (Flyway migration {@code V007__create_disclosure_group.sql}). This entity
 * is the Java target for the COBOL {@code DIS-GROUP-RECORD} layout defined in
 * {@code app/cpy/CVTRA02Y.cpy} (RECLN = 50 bytes), and replaces the
 * mainframe VSAM KSDS cluster {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS}.
 *
 * <h2>Purpose</h2>
 * <p>Represents a single canonical disclosure-group interest-rate lookup row.
 * The table is the <em>heart of the end-of-day interest-calculation logic</em>:
 * it maps each {@code (account-group-id, transaction-type-code,
 * transaction-category-code)} tuple to an annual interest-rate percentage
 * (APR). Without correct rows, the entire interest-calculation job produces
 * zero or wrong results.</p>
 *
 * <p>The lookup is keyed by a 16-byte composite VSAM key (per the IDCAMS
 * {@code DEFINE CLUSTER KEYS(16, 0)} clause in {@code app/jcl/DISCGRP.jcl}
 * and {@code app/catlg/LISTCAT.txt KEYLEN=16, RKP=0}), which decomposes into
 * three logical key columns whose widths sum exactly to 16 bytes:</p>
 * <pre>
 *     DIS-ACCT-GROUP-ID (PIC X(10), 10 bytes)
 *   + DIS-TRAN-TYPE-CD  (PIC X(02),  2 bytes)
 *   + DIS-TRAN-CAT-CD   (PIC 9(04),  4 bytes)
 *   = 16-byte VSAM composite key
 * </pre>
 *
 * <h2>Critical business rule &mdash; DEFAULT fallback (per AAP &sect;0.4.1, &sect;0.6.1)</h2>
 * <p>The lookup pattern in {@code InterestCalculationService} (Java target
 * for COBOL {@code CBACT04C}) is:</p>
 * <ol>
 *   <li>Find disclosure-group row by
 *       {@code (account.acctGroupId, transaction.tranTypeCd, transaction.tranCatCd)}.</li>
 *   <li>If not found, fall back to the
 *       {@code (literal "DEFAULT", transaction.tranTypeCd, transaction.tranCatCd)}
 *       row &mdash; this guarantees coverage for any account whose specific
 *       group ID is unrecognized.</li>
 *   <li>If the account's group is the promotional {@code "ZEROAPR"} group,
 *       the lookup hits 17 zero-APR rows and the service short-circuits to
 *       a zero interest charge.</li>
 *   <li>Compute monthly interest as
 *       {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2,
 *       RoundingMode.HALF_EVEN)} &mdash; the literal divisor {@code 1200}
 *       is preserved without algebraic simplification per AAP &sect;0.6.1
 *       to match the COBOL source byte-for-byte ({@code rate / 100 = decimal
 *       rate, then / 12 = monthly fraction}).</li>
 * </ol>
 *
 * <h2>Canonical reference values (seeded by V012)</h2>
 * <p>51 reference rows are seeded by {@code V012__seed_disclosure_group.sql}
 * from {@code app/data/ASCII/discgrp.txt}, organized into three blocks of
 * 17 rows each (one row per {@code (type-cd, cat-cd)} tuple):</p>
 * <ul>
 *   <li>{@code "A000000000"} &mdash; the standard operational account
 *       group (a literal fully-numeric-looking string with leading
 *       {@code 'A'}; stored exactly as 10 chars).</li>
 *   <li>{@code "DEFAULT"} &mdash; the fallback group used by
 *       {@code InterestCalculationService} when an account's specific
 *       group lookup misses; stored <em>trimmed</em> of trailing padding
 *       spaces (the COBOL fixture pads it with 3 trailing spaces to fill
 *       the 10-char field, but V012 stores the trimmed 7-char value for
 *       query ergonomics &mdash; VARCHAR(10) tolerates this).</li>
 *   <li>{@code "ZEROAPR"} &mdash; the promotional zero-APR group; all 17
 *       rows have {@code dis_int_rate = 0.00}; stored <em>trimmed</em>.</li>
 * </ul>
 *
 * <h2>Consumers (Java services that read this table)</h2>
 * <ul>
 *   <li>{@code InterestCalculationService} (COBOL {@code CBACT04C})
 *       &mdash; composite-key lookups per
 *       {@code (account.acctGroupId, tran.typeCd, tran.catCd)} with the
 *       DEFAULT-fallback and ZEROAPR short-circuit patterns above.</li>
 *   <li>{@code StatementGenerationService} (COBOL {@code CBSTM03A} /
 *       {@code CBSTM03B}) &mdash; joins to embed the applied APR
 *       percentage onto generated statements alongside per-category
 *       balance totals.</li>
 *   <li>{@code DisclosureGroupRepository} (Spring Data JPA) &mdash;
 *       exposes {@code findById(DisclosureGroupId)} and a derived query
 *       for the {@code "DEFAULT"} fallback lookup; the
 *       {@link EmbeddedId} on this entity mirrors the 3-column composite
 *       PK declared in V007.</li>
 * </ul>
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook:</b> {@code app/cpy/CVTRA02Y.cpy} &mdash;
 *       50-byte fixed-width record layout with a 16-byte composite
 *       {@code DIS-GROUP-KEY} (3 sub-fields), a {@code DIS-INT-RATE
 *       PIC S9(04)V99} rate field, and a trailing 28-byte
 *       {@code FILLER PIC X(28)}. The FILLER has no relational
 *       equivalent and is omitted from this entity.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS} &mdash;
 *       {@code KEYS(16, 0)}, {@code RECORDSIZE(50, 50)},
 *       {@code SHAREOPTIONS(2, 3)}, {@code ERASE}, {@code INDEXED},
 *       {@code REC-TOTAL=51} (per {@code app/jcl/DISCGRP.jcl}:L36-L49
 *       and {@code app/catlg/LISTCAT.txt}).</li>
 *   <li><b>JCL allocation/load:</b>
 *       {@code app/jcl/DISCGRP.jcl} (STEP10 IDCAMS DEFINE CLUSTER,
 *       STEP15 IDCAMS REPRO from {@code DISCGRP.PS}).</li>
 *   <li><b>Golden fixture:</b> {@code app/data/ASCII/discgrp.txt}
 *       (51 fixed-width 50-byte records; positions 23-50 contain the
 *       28-byte literal {@code "0000000000000000000000000000"} for
 *       every row, confirming that the trailing FILLER is padding
 *       rather than data).</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V007__create_disclosure_group.sql}.</li>
 *   <li><b>Seed migration:</b>
 *       {@code src/main/resources/db/migration/V012__seed_disclosure_group.sql}
 *       (51 rows).</li>
 * </ul>
 *
 * <h2>Schema invariants</h2>
 * <ul>
 *   <li><b>Composite primary key</b> &mdash; a 3-column composite
 *       {@code (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)},
 *       mirrored on the Java side by the nested {@link DisclosureGroupId}
 *       {@link Embeddable} class. Hibernate's
 *       {@code spring.jpa.hibernate.ddl-auto: validate} verifies this at
 *       startup against the V007 DDL.</li>
 *   <li><b>No optimistic locking</b> &mdash; this entity has no
 *       {@code @Version} column. The lookup data is static reference
 *       data seeded once by V012 and not updated at runtime; there is
 *       no read-modify-write contention to guard against.</li>
 *   <li><b>No association mappings</b> &mdash; child tables (none in
 *       practice; this is a leaf lookup) and parent tables ({@code tran_type}
 *       V008, {@code tran_category} V009) are referenced via scalar columns
 *       only, not via JPA {@code @ManyToOne} / {@code @OneToMany}
 *       associations. This is a deliberate Minimal Change Clause decision
 *       (AAP &sect;0.7.3): associations are not required by any consumer
 *       service, and avoiding them keeps the JPA mapping byte-faithful to
 *       the COBOL field set without introducing new traversal patterns.</li>
 *   <li><b>No database-level foreign keys</b> &mdash; the COBOL source
 *       enforced referential integrity at the application layer
 *       ({@code CBACT04C} validation), not via VSAM. The Java target
 *       mirrors this convention so {@code InterestCalculationService}
 *       continues to be the single source of truth for the validation
 *       cascade (per AAP &sect;0.7.3 Minimal Change Clause).</li>
 *   <li><b>FILLER omitted</b> &mdash; the trailing 28-byte
 *       {@code FILLER PIC X(28)} from the COBOL record has no relational
 *       counterpart and is not declared as a Java field.</li>
 * </ul>
 *
 * <h2>JPA mapping discipline</h2>
 * <ul>
 *   <li>{@code @Table(name = "disclosure_group")} matches V007 DDL table
 *       name <em>exactly</em> (singular, snake_case).</li>
 *   <li>{@code @EmbeddedId} field is the nested {@link DisclosureGroupId}
 *       {@link Embeddable} value type, which encapsulates the 3-column
 *       composite primary key.</li>
 *   <li>{@code @Column(name = "dis_int_rate", precision = 6, scale = 2,
 *       nullable = false)} on the rate field matches V007's
 *       {@code NUMERIC(6, 2) NOT NULL DEFAULT 0} declaration.</li>
 *   <li>All {@code @Column} {@code nullable}, {@code length}, and
 *       {@code precision} / {@code scale} attributes correspond 1-to-1
 *       with the V007 DDL.</li>
 * </ul>
 *
 * <h2>BigDecimal contract (per AAP &sect;0.6.1)</h2>
 * <ul>
 *   <li>The {@link #disIntRate} field MUST be {@link BigDecimal}
 *       &mdash; <em>never</em> {@code float} or {@code double}. The
 *       PostgreSQL {@code NUMERIC} type is arbitrary-precision and
 *       exactly matches {@link BigDecimal} semantics.</li>
 *   <li>All arithmetic on rate values in {@code InterestCalculationService}
 *       uses {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding)
 *       to match COBOL {@code PIC S9(04)V99} decimal-arithmetic semantics.</li>
 *   <li>The literal divisor {@code 1200} in the monthly-interest formula
 *       {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2,
 *       HALF_EVEN)} is preserved without algebraic simplification
 *       (i.e., not pre-computed to {@code 0.0008333...}) so the Java
 *       output matches the COBOL output byte-for-byte during the
 *       parallel-run validation period.</li>
 * </ul>
 *
 * <h2>Data quality and immutability</h2>
 * <p>Although this entity exposes setters for both fields (per the
 * JavaBean contract required by Spring Data JPA derived queries and
 * Jackson serialization), <b>in practice this table is treated as
 * immutable reference data</b>. The only legitimate write path is the
 * one-time V012 seed migration; runtime services SHOULD only read from
 * this table. The setters exist for the entity-lifecycle plumbing that
 * JPA requires &mdash; not for application-level mutation.</p>
 *
 * <p>The entity name {@code DisclosureGroup} reflects the COBOL group
 * name {@code DIS-GROUP-RECORD} (the {@code DIS-} prefix is expanded
 * to {@code Disclosure} in Java per AAP &sect;0.7.3 refactor discipline).</p>
 *
 * @see com.awsm2.carddemo.domain.Account
 * @see com.awsm2.carddemo.domain.TransactionType
 * @see com.awsm2.carddemo.domain.TransactionCategory
 * @see com.awsm2.carddemo.domain.TransactionCategoryBalance
 * @see com.awsm2.carddemo.domain.DisclosureGroup.DisclosureGroupId
 */
@Entity
@Table(name = "disclosure_group")
public class DisclosureGroup implements Serializable {

    /**
     * Serializable version identifier. Required by {@link Serializable}
     * to ensure stable serialization semantics across persistence-context
     * boundaries, second-level caches, and remote-call boundaries.
     * Incremented only when the entity's serialized form changes in a
     * backward-incompatible way.
     */
    private static final long serialVersionUID = 1L;

    // -------------------------------------------------------------------------
    // Persistent fields
    //
    // Field declarations correspond one-to-one (in order) to the COBOL
    // DIS-GROUP-RECORD layout in app/cpy/CVTRA02Y.cpy:
    //
    //   05  DIS-GROUP-KEY.                       (composite 16-byte key)
    //      10 DIS-ACCT-GROUP-ID PIC X(10).        -> @EmbeddedId field
    //      10 DIS-TRAN-TYPE-CD  PIC X(02).        -> @EmbeddedId field
    //      10 DIS-TRAN-CAT-CD   PIC 9(04).        -> @EmbeddedId field
    //   05  DIS-INT-RATE        PIC S9(04)V99.    -> disIntRate (BigDecimal)
    //   05  FILLER              PIC X(28).        -> OMITTED
    //
    // The single trailing FILLER PIC X(28) (which brings the COBOL record
    // to its declared 50-byte RECORDSIZE) is intentionally omitted; the
    // source fixture's positions 23-50 contain the literal
    // "0000000000000000000000000000" (28 zeros) for every row, confirming
    // that the FILLER is padding rather than data.
    // -------------------------------------------------------------------------

    /**
     * The 16-byte VSAM composite key &mdash; the JPA {@link EmbeddedId}.
     *
     * <p>Encapsulates the three composite-key sub-fields
     * {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)}
     * mapped to the PostgreSQL columns
     * {@code (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)}
     * that form the {@code pk_disclosure_group} composite primary key
     * constraint declared in V007.</p>
     *
     * <p>The 3-column order matches the COBOL byte order
     * ({@code group_id} leftmost, {@code type_cd} middle, {@code cat_cd}
     * rightmost), preserving the implicit lexicographic ordering of the
     * VSAM KSDS for any future queries that want to range-scan within a
     * single group ID.</p>
     */
    // COBOL: CVTRA02Y.cpy:L5-L8 DIS-GROUP-KEY (composite 16 bytes) -- @EmbeddedId
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Annual interest-rate percentage (APR) for the
     * {@code (group, type, category)} tuple identified by {@link #id}.
     *
     * <p>Maps to the COBOL field
     * {@code 05 DIS-INT-RATE PIC S9(04)V99} in
     * {@code app/cpy/CVTRA02Y.cpy} (line 9) and to the V007
     * {@code dis_int_rate NUMERIC(6, 2) NOT NULL DEFAULT 0} column.</p>
     *
     * <p>The COBOL {@code PIC S9(04)V99} clause permits any value in
     * {@code [-9999.99, +9999.99]}; PostgreSQL {@code NUMERIC(6, 2)}
     * faithfully preserves this range with precision = 4 integer digits
     * + 2 fraction digits = 6 total digits. Seeded values are
     * {@code 0.00} (zero-APR rows), {@code 15.00} (standard 15% APR),
     * and {@code 25.00} (penalty / cash-advance 25% APR).</p>
     *
     * <p><b>BigDecimal contract (per AAP &sect;0.6.1):</b> the runtime
     * type is {@link BigDecimal} with scale {@code = 2}. All arithmetic
     * in {@code InterestCalculationService} (COBOL {@code CBACT04C})
     * uses {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding)
     * to match COBOL {@code PIC S9(04)V99} decimal-arithmetic semantics.
     * The canonical monthly-interest formula is:</p>
     * <pre>
     *     interest = balance.multiply(rate)
     *                       .divide(BigDecimal.valueOf(1200),
     *                               2, RoundingMode.HALF_EVEN);
     * </pre>
     * <p>The literal divisor {@code 1200} (= 100 * 12, converting APR
     * percent to monthly fraction) is preserved <em>verbatim</em> per
     * AAP &sect;0.6.1 &mdash; <em>do not</em> algebraically simplify it
     * to {@code 0.0008333...}, because that would diverge from the
     * COBOL source's byte-level output during the parallel-run
     * validation period.</p>
     *
     * <p><b>Never</b> use {@code float} or {@code double} for this
     * column &mdash; PostgreSQL {@code NUMERIC} is arbitrary-precision
     * and exactly matches {@link BigDecimal} semantics; binary
     * floating-point cannot represent decimal fractions like
     * {@code 0.10} exactly and would introduce accumulating drift.</p>
     */
    // COBOL: CVTRA02Y.cpy:L9 DIS-INT-RATE PIC S9(04)V99
    // -- annual interest rate (percentage); BigDecimal precision=6 scale=2
    // Interest calc: balance.multiply(rate).divide(BigDecimal.valueOf(1200),
    //                                              2, RoundingMode.HALF_EVEN)
    @Column(name = "dis_int_rate", nullable = false, precision = 6, scale = 2)
    private BigDecimal disIntRate;

    // COBOL: CVTRA02Y.cpy:L10 FILLER PIC X(28) -- OMITTED
    // (28 trailing bytes that bring the COBOL record to its declared
    // 50-byte RECORDSIZE; positions 23-50 of the source fixture contain
    // the literal "0000000000000000000000000000" (28 zeros), confirming
    // padding rather than data. PostgreSQL has no concept of fixed-width
    // records, so the FILLER has no relational equivalent.)

    // -------------------------------------------------------------------------
    // Constructors
    //
    // Three constructors are exposed:
    //   1) The JPA-required no-arg constructor (used by Hibernate when
    //      hydrating a row read from PostgreSQL into a managed entity).
    //   2) An all-args constructor accepting the @EmbeddedId and rate
    //      (used by services that already have a constructed
    //      DisclosureGroupId).
    //   3) A convenience constructor accepting the three raw key fields
    //      plus the rate (used by tests and the V012 seed migration's
    //      Java-side equivalent constructions in InterestCalculation-
    //      Service unit tests).
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
     * {@link #DisclosureGroup(DisclosureGroupId, BigDecimal)} or
     * {@link #DisclosureGroup(String, String, Integer, BigDecimal)}
     * convenience constructors instead.</p>
     */
    public DisclosureGroup() {
        // Intentionally empty -- field initialization is performed by
        // Hibernate via reflective field/setter access during entity
        // hydration, or by the all-args constructor in application code.
    }

    /**
     * All-args constructor accepting a pre-built {@link DisclosureGroupId}.
     *
     * <p>Used by service code that has already constructed the composite
     * key (e.g., when porting a COBOL {@code DIS-GROUP-KEY} from an
     * external source).</p>
     *
     * @param id         the 3-field composite primary key (must not be
     *                   {@code null}; corresponds to COBOL
     *                   {@code DIS-GROUP-KEY})
     * @param disIntRate the annual interest-rate percentage as a
     *                   {@link BigDecimal} with scale {@code = 2}
     *                   (corresponds to COBOL {@code DIS-INT-RATE})
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal disIntRate) {
        this.id = id;
        this.disIntRate = disIntRate;
    }

    /**
     * Convenience all-args constructor accepting the three raw composite-key
     * fields plus the rate.
     *
     * <p>Equivalent to:
     * {@code new DisclosureGroup(new DisclosureGroupId(group, type, cat), rate)}.
     * Used by tests and any service code that constructs the entity from
     * its primitive key fields without first materializing a
     * {@link DisclosureGroupId} instance.</p>
     *
     * @param disAcctGroupId the 10-character account-group identifier
     *                       (e.g., {@code "A000000000"}, {@code "DEFAULT"},
     *                       {@code "ZEROAPR"}; corresponds to COBOL
     *                       {@code DIS-ACCT-GROUP-ID PIC X(10)})
     * @param disTranTypeCd  the 2-character transaction-type code (e.g.,
     *                       {@code "01"} Purchase, {@code "02"} Payment;
     *                       corresponds to COBOL
     *                       {@code DIS-TRAN-TYPE-CD PIC X(02)})
     * @param disTranCatCd   the 4-digit transaction-category code (1-9999;
     *                       corresponds to COBOL
     *                       {@code DIS-TRAN-CAT-CD PIC 9(04)})
     * @param disIntRate     the annual interest-rate percentage as a
     *                       {@link BigDecimal} with scale {@code = 2}
     *                       (corresponds to COBOL {@code DIS-INT-RATE
     *                       PIC S9(04)V99})
     */
    public DisclosureGroup(String disAcctGroupId,
                           String disTranTypeCd,
                           Integer disTranCatCd,
                           BigDecimal disIntRate) {
        this.id = new DisclosureGroupId(disAcctGroupId, disTranTypeCd, disTranCatCd);
        this.disIntRate = disIntRate;
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
     *         ({@link DisclosureGroupId})
     */
    public DisclosureGroupId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * <p>Although this setter exists to satisfy the JavaBean contract
     * required by JPA, application code SHOULD NOT mutate the primary
     * key of a persisted entity. This table is treated as immutable
     * reference data outside the V012 seed migration.</p>
     *
     * @param id the composite primary key to set (must not be
     *           {@code null} for a persisted entity)
     */
    public void setId(DisclosureGroupId id) {
        this.id = id;
    }

    /**
     * @return the annual interest-rate percentage (APR) as a
     *         {@link BigDecimal} with scale {@code = 2}
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Sets the annual interest-rate percentage.
     *
     * <p>Application code SHOULD NOT mutate rates at runtime; the V012
     * seed migration is the authoritative source. The setter exists for
     * the entity-lifecycle plumbing that JPA requires.</p>
     *
     * @param disIntRate the rate to set ({@link BigDecimal} with scale
     *                   {@code = 2}; never {@code float} or
     *                   {@code double} per AAP &sect;0.6.1)
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    //
    // equals/hashCode follow the JPA recommended contract for entities
    // with an @EmbeddedId composite primary key: based on the EmbeddedId
    // field only. The DisclosureGroupId nested class itself provides
    // value-based equals/hashCode over its three sub-fields, so equality
    // semantics are consistent across both layers.
    //
    // toString is non-sensitive: this table contains only public
    // reference data (codes and rates), so the full content is safe to
    // log per AAP §0.6.6 (PCI-DSS).
    // -------------------------------------------------------------------------

    /**
     * Equality is defined on the {@link EmbeddedId} {@link #id} only,
     * matching the standard JPA entity contract for composite-key
     * entities. Two {@code DisclosureGroup} instances are equal iff they
     * have equal {@link DisclosureGroupId} values (both {@code null} is
     * treated as equal &mdash; common during transient-state comparisons
     * within a test fixture but should not occur in persisted state).
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
        if (!(o instanceof DisclosureGroup)) {
            return false;
        }
        DisclosureGroup that = (DisclosureGroup) o;
        return Objects.equals(id, that.id);
    }

    /**
     * Hash code derived from {@link #id} only, consistent with the
     * {@link #equals(Object)} contract above. Safe for use as a
     * hash-set or hash-map key once {@link #id} has been assigned
     * (which is required before {@code EntityManager.persist} by virtue
     * of the {@code nullable = false} composite-primary-key constraint).
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
     * <p>This entity contains only public reference data &mdash; the
     * 3-field composite-key codes and the APR rate. There is no PII,
     * PAN, or credential material in this table, so the full content
     * is safe to log per AAP &sect;0.6.6 (PCI-DSS).</p>
     *
     * @return a string representation of this entity
     */
    @Override
    public String toString() {
        return "DisclosureGroup{"
                + "id=" + id
                + ", disIntRate=" + disIntRate
                + '}';
    }

    // =========================================================================
    // Composite primary-key class
    // =========================================================================

    /**
     * JPA {@link Embeddable} composite-primary-key class for the
     * {@link DisclosureGroup} entity. Encapsulates the three sub-fields
     * of the 16-byte VSAM composite key
     * {@code DIS-GROUP-KEY} from {@code app/cpy/CVTRA02Y.cpy} lines
     * 5-8, which decompose as:
     * <pre>
     *     DIS-ACCT-GROUP-ID  PIC X(10)   -> disAcctGroupId
     *     DIS-TRAN-TYPE-CD   PIC X(02)   -> disTranTypeCd
     *     DIS-TRAN-CAT-CD    PIC 9(04)   -> disTranCatCd
     * </pre>
     *
     * <p>The field declaration order matches the COBOL byte order, which
     * preserves the implicit lexicographic ordering of the VSAM KSDS for
     * any future range-scan queries within a single account-group ID
     * (none of the COBOL programs in the CardDemo source rely on this
     * ordering &mdash; lookups are always exact-match by composite key
     * &mdash; but the order is preserved for traceability).</p>
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
     * @see DisclosureGroup
     */
    @Embeddable
    public static class DisclosureGroupId implements Serializable {

        /**
         * Serializable version identifier. Required by {@link Serializable}
         * for composite-key classes (Jakarta Persistence specification
         * &sect;2.4) to ensure stable serialization semantics across
         * second-level caches and persistence-context boundaries.
         */
        private static final long serialVersionUID = 1L;

        // ---------------------------------------------------------------------
        // Composite-key sub-fields
        //
        // The three sub-fields below correspond one-to-one (in order) to
        // the COBOL DIS-GROUP-KEY sub-fields in app/cpy/CVTRA02Y.cpy
        // (lines 5-8). Their byte widths sum to exactly 16 bytes
        // (matching the IDCAMS KEYS(16, 0) clause in DISCGRP.jcl and
        // LISTCAT.txt KEYLEN=16, RKP=0):
        //     10 (group_id) + 2 (type_cd) + 4 (cat_cd) = 16 bytes
        // ---------------------------------------------------------------------

        /**
         * 10-character account-group identifier &mdash; the first
         * sub-field of the composite key (bytes 1-10 of the 16-byte
         * VSAM key).
         *
         * <p>Maps to the COBOL field
         * {@code 10 DIS-ACCT-GROUP-ID PIC X(10)} in
         * {@code app/cpy/CVTRA02Y.cpy} (line 6) and to the V007
         * {@code dis_acct_group_id VARCHAR(10) NOT NULL} column.</p>
         *
         * <p>Seeded values (per V012):</p>
         * <ul>
         *   <li>{@code "A000000000"} &mdash; the standard operational
         *       account group (10-char literal with leading {@code 'A'},
         *       stored exactly).</li>
         *   <li>{@code "DEFAULT"} &mdash; the fallback group used by
         *       {@code InterestCalculationService} when an account's
         *       specific group lookup misses. Stored <em>trimmed</em>
         *       of trailing padding spaces &mdash; the COBOL fixture
         *       pads it to 10 chars as {@code "DEFAULT   "}, but the
         *       PostgreSQL {@code VARCHAR(10)} column stores the
         *       trimmed 7-char value for query ergonomics.</li>
         *   <li>{@code "ZEROAPR"} &mdash; the promotional zero-APR
         *       group, also stored <em>trimmed</em>.</li>
         * </ul>
         *
         * <p>{@code VARCHAR(10)} is chosen in V007 over {@code CHAR(10)}
         * so that trimmed values like {@code "DEFAULT"} and
         * {@code "ZEROAPR"} compare exact-equal against the application-
         * layer literals in {@code InterestCalculationService} (which
         * use the trimmed forms). The Java field type is {@link String}
         * with {@code length = 10}.</p>
         */
        // COBOL: CVTRA02Y.cpy:L6 DIS-ACCT-GROUP-ID PIC X(10)
        // -- 10-char alphanumeric group identifier; VARCHAR(10) so trimmed
        // values "DEFAULT" / "ZEROAPR" do not pad-compare against the
        // COBOL fixture's "DEFAULT   " / "ZEROAPR   ".
        @Column(name = "dis_acct_group_id", nullable = false, length = 10)
        private String disAcctGroupId;

        /**
         * 2-character transaction-type code &mdash; the second sub-field
         * of the composite key (bytes 11-12 of the 16-byte VSAM key).
         *
         * <p>Maps to the COBOL field
         * {@code 10 DIS-TRAN-TYPE-CD PIC X(02)} in
         * {@code app/cpy/CVTRA02Y.cpy} (line 7) and to the V007
         * {@code dis_tran_type_cd CHAR(2) NOT NULL} column.</p>
         *
         * <p>The value space is the same as
         * {@code tran_type.tran_type} (V008): {@code "01"} Purchase,
         * {@code "02"} Payment, {@code "03"} Credit, {@code "04"}
         * Authorization, {@code "05"} Refund, {@code "06"} Reversal,
         * {@code "07"} Adjustment.</p>
         *
         * <p>Leading zeros are SIGNIFICANT and MUST be preserved &mdash;
         * the code is always exactly 2 characters, always quoted as a
         * string (NEVER stored as an integer {@code 1} that would lose
         * the leading zero on read). {@code CHAR(2)} (via
         * {@code columnDefinition = "CHAR(2)"}) is used to match the
         * fixed-width semantics of V008 {@code tran_type.tran_type}.</p>
         */
        // COBOL: CVTRA02Y.cpy:L7 DIS-TRAN-TYPE-CD PIC X(02)
        // -- 2-char transaction-type code; CHAR(2) for fixed-width matching
        // with V008 tran_type.tran_type (same value space).
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "dis_tran_type_cd", nullable = false, length = 2,
                columnDefinition = "CHAR(2)")
        private String disTranTypeCd;

        /**
         * 4-digit numeric transaction-category code &mdash; the third
         * sub-field of the composite key (bytes 13-16 of the 16-byte
         * VSAM key, the final 4 bytes of the composite).
         *
         * <p>Maps to the COBOL field
         * {@code 10 DIS-TRAN-CAT-CD PIC 9(04)} in
         * {@code app/cpy/CVTRA02Y.cpy} (line 8) and to the V007
         * {@code dis_tran_cat_cd NUMERIC(4) NOT NULL} column.</p>
         *
         * <p>The value space is the same as
         * {@code tran_category.tran_cat_cd} (V009): a 4-digit numeric
         * code in {@code [0, 9999]}. Stored as an {@link Integer}
         * (mapped to PostgreSQL {@code NUMERIC(4)}) NOT as a zero-padded
         * string; COBOL {@code PIC 9(04) "0001"} becomes the integer
         * {@code 1} (per V012 seed convention &mdash; cat values
         * {@code 1, 2, 3, 4} are seeded).</p>
         *
         * <p>{@code NUMERIC(4)} with precision = 4 and implicit scale = 0
         * stores values exactly in PostgreSQL (no float approximation).
         * The Java field type is {@link Integer} to accommodate any
         * positive value up to {@code 9999} while still rejecting
         * {@code null} via {@code nullable = false}.</p>
         */
        // COBOL: CVTRA02Y.cpy:L8 DIS-TRAN-CAT-CD PIC 9(04)
        // -- 4-digit numeric category code; NUMERIC(4) stores 0..9999 exactly.
        @Column(name = "dis_tran_cat_cd", nullable = false, precision = 4)
        private Integer disTranCatCd;

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
        public DisclosureGroupId() {
            // Intentionally empty -- field initialization is performed
            // by Hibernate via reflective field/setter access during
            // entity hydration, or by the all-args constructor in
            // application code.
        }

        /**
         * All-args constructor for convenient programmatic construction.
         *
         * <p>Used in service code and tests to build a
         * {@code DisclosureGroupId} instance from its three composite-key
         * sub-fields.</p>
         *
         * @param disAcctGroupId the 10-character account-group identifier
         *                       (corresponds to COBOL
         *                       {@code DIS-ACCT-GROUP-ID PIC X(10)};
         *                       must not be {@code null} for a persisted
         *                       entity)
         * @param disTranTypeCd  the 2-character transaction-type code
         *                       (corresponds to COBOL
         *                       {@code DIS-TRAN-TYPE-CD PIC X(02)};
         *                       leading zeros are significant; must not
         *                       be {@code null})
         * @param disTranCatCd   the 4-digit transaction-category code
         *                       (corresponds to COBOL
         *                       {@code DIS-TRAN-CAT-CD PIC 9(04)};
         *                       must not be {@code null})
         */
        public DisclosureGroupId(String disAcctGroupId,
                                 String disTranTypeCd,
                                 Integer disTranCatCd) {
            this.disAcctGroupId = disAcctGroupId;
            this.disTranTypeCd = disTranTypeCd;
            this.disTranCatCd = disTranCatCd;
        }

        // ---------------------------------------------------------------------
        // Accessors (getters and setters)
        //
        // Plain JavaBean accessors, one per composite-key sub-field.
        // Required by Hibernate's property access mode for @EmbeddedId
        // hydration.
        // ---------------------------------------------------------------------

        /**
         * @return the 10-character account-group identifier (first
         *         sub-field of the composite key)
         */
        public String getDisAcctGroupId() {
            return disAcctGroupId;
        }

        /**
         * Sets the account-group identifier sub-field of the composite
         * primary key.
         *
         * <p>Although this setter exists to satisfy the JavaBean
         * contract required by JPA, application code SHOULD NOT mutate
         * the sub-fields of a persisted composite primary key.</p>
         *
         * @param disAcctGroupId the 10-character account-group
         *                       identifier to set (corresponds to COBOL
         *                       {@code DIS-ACCT-GROUP-ID PIC X(10)})
         */
        public void setDisAcctGroupId(String disAcctGroupId) {
            this.disAcctGroupId = disAcctGroupId;
        }

        /**
         * @return the 2-character transaction-type code (second
         *         sub-field of the composite key)
         */
        public String getDisTranTypeCd() {
            return disTranTypeCd;
        }

        /**
         * Sets the transaction-type code sub-field of the composite
         * primary key.
         *
         * <p>Although this setter exists to satisfy the JavaBean
         * contract required by JPA, application code SHOULD NOT mutate
         * the sub-fields of a persisted composite primary key.</p>
         *
         * @param disTranTypeCd the 2-character transaction-type code
         *                      to set (corresponds to COBOL
         *                      {@code DIS-TRAN-TYPE-CD PIC X(02)};
         *                      leading zeros are significant)
         */
        public void setDisTranTypeCd(String disTranTypeCd) {
            this.disTranTypeCd = disTranTypeCd;
        }

        /**
         * @return the 4-digit transaction-category code (third
         *         sub-field of the composite key)
         */
        public Integer getDisTranCatCd() {
            return disTranCatCd;
        }

        /**
         * Sets the transaction-category code sub-field of the composite
         * primary key.
         *
         * <p>Although this setter exists to satisfy the JavaBean
         * contract required by JPA, application code SHOULD NOT mutate
         * the sub-fields of a persisted composite primary key.</p>
         *
         * @param disTranCatCd the 4-digit transaction-category code to
         *                     set (corresponds to COBOL
         *                     {@code DIS-TRAN-CAT-CD PIC 9(04)})
         */
        public void setDisTranCatCd(Integer disTranCatCd) {
            this.disTranCatCd = disTranCatCd;
        }

        // ---------------------------------------------------------------------
        // equals / hashCode / toString
        //
        // For composite-key classes, equals/hashCode MUST be based on
        // all key sub-fields. This is required by the Jakarta Persistence
        // specification §2.4 so that JPA can use the composite-key
        // value as a HashMap key in its persistence-context tracking.
        //
        // toString is non-sensitive: composite-key sub-fields are public
        // reference codes, safe to log per AAP §0.6.6 (PCI-DSS).
        // ---------------------------------------------------------------------

        /**
         * Equality is defined by value: two {@code DisclosureGroupId}
         * instances are equal iff all three sub-fields
         * ({@link #disAcctGroupId}, {@link #disTranTypeCd},
         * {@link #disTranCatCd}) are pair-wise equal (with {@code null}
         * tolerance via {@link Objects#equals}).
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
            if (!(o instanceof DisclosureGroupId)) {
                return false;
            }
            DisclosureGroupId that = (DisclosureGroupId) o;
            return Objects.equals(disAcctGroupId, that.disAcctGroupId)
                    && Objects.equals(disTranTypeCd, that.disTranTypeCd)
                    && Objects.equals(disTranCatCd, that.disTranCatCd);
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
            return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
        }

        /**
         * String representation suitable for log statements and debug
         * output. All three composite-key sub-fields are public
         * reference codes (group ID, transaction-type code,
         * transaction-category code) and contain no PII or PAN, so the
         * full content is safe to log per AAP &sect;0.6.6 (PCI-DSS).
         *
         * @return a string representation of this composite-key instance
         */
        @Override
        public String toString() {
            return "DisclosureGroupId{"
                    + "disAcctGroupId='" + disAcctGroupId + '\''
                    + ", disTranTypeCd='" + disTranTypeCd + '\''
                    + ", disTranCatCd=" + disTranCatCd
                    + '}';
        }
    }
}
