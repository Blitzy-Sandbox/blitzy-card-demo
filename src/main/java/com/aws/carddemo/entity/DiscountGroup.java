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

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code DISCGRP} VSAM KSDS file's
 * {@code DIS-GROUP-RECORD} record described by {@code app/cpy/CVTRA02Y.cpy}.
 *
 * <h2>COBOL Provenance — CVTRA02Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 50-byte record:
 * <pre>
 *   01 DIS-GROUP-RECORD.
 *      05 DIS-GROUP-KEY.
 *         10 DIS-ACCT-GROUP-ID      PIC X(10).      --&gt; {@link DiscountGroupKey#getDisAcctGroupId()}
 *         10 DIS-TRAN-TYPE-CD       PIC X(02).      --&gt; {@link DiscountGroupKey#getDisTranTypeCd()}
 *         10 DIS-TRAN-CAT-CD        PIC 9(04).      --&gt; {@link DiscountGroupKey#getDisTranCatCd()}
 *      05 DIS-INT-RATE              PIC S9(04)V99.  --&gt; {@link #disIntRate}
 *      05 FILLER                    PIC X(28).
 * </pre>
 *
 * <h2>Reference Data (Read-Only Catalog)</h2>
 *
 * <p>{@code DIS-GROUP-RECORD} is reference data — read-only after the
 * initial Flyway seed (no business workflow updates it at runtime). The
 * 51 canonical rows from {@code app/data/ASCII/discgrp.txt} are accessed
 * by the migrated {@code InterestCalculationProcessor} (CBACT04C
 * migration) once per account-balance bucket: the processor looks up the
 * {@code DIS-INT-RATE} keyed by the
 * {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)} tuple,
 * then applies the formula {@code (balance × rate) / 1200} with
 * {@link java.math.RoundingMode#HALF_EVEN} rounding to scale 2 per AAP
 * §0.10.3 Financial Precision.
 *
 * <h2>Special-Case Group Identifiers (CBACT04C branch logic)</h2>
 *
 * <p>Two 10-character {@code DIS-ACCT-GROUP-ID} sentinel values drive the
 * interest-calculator branch logic. Both appear as catalog rows in
 * {@code discgrp.txt}:
 * <ul>
 *   <li>{@code "DEFAULT   "} (7 chars plus 3 trailing spaces) — fallback
 *       group consulted when an account's configured disclosure group is
 *       not present in the catalog. The COBOL paragraph
 *       {@code 1200-A-GET-DEFAULT-INT-RATE} re-reads the
 *       {@code DISCGRP-FILE} after moving {@code 'DEFAULT'} into
 *       {@code FD-DIS-ACCT-GROUP-ID} (line 437 of {@code CBACT04C.CBL}).</li>
 *   <li>{@code "ZEROAPR   "} (7 chars plus 3 trailing spaces) — zero-APR
 *       override group; every row keyed by this group ID carries a
 *       {@code DIS-INT-RATE} of {@code 0.00}, so the
 *       {@code IF DIS-INT-RATE NOT = 0} guard inside paragraph
 *       {@code 1300-COMPUTE-INTEREST} skips the interest calculation.</li>
 * </ul>
 *
 * <p>The lookup logic itself lives in {@code InterestCalculationProcessor}
 * (REFACTOR-flavor) and is exercised end-to-end by
 * {@code InterestCalculationProcessorTest} (AAP §0.5.1). This entity only
 * carries the row data; AAP §0.10.1 Require Test Coverage Rule forbids
 * embedding the fallback/skip business logic in either the entity or the
 * repository.
 *
 * <h2>Composite Key — Embedded ID</h2>
 *
 * <p>The {@link #key} field carries the composite primary key as a
 * separate {@link DiscountGroupKey} value object (the {@code @EmbeddedId}
 * pattern). The composite-key class is the natural Java mapping of the
 * COBOL {@code DIS-GROUP-KEY} group-level item, and keeping it as a
 * discrete value object (rather than three scalar fields on this entity)
 * makes the composite identity surface explicit at the type-system level:
 * methods that operate on a discount-group row accept a single
 * {@link DiscountGroupKey} argument rather than a three-tuple of scalars
 * where call-sites could accidentally swap the field order.
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>{@link #disIntRate} is a {@link BigDecimal} at scale 2 per AAP
 *       §0.10.3 ("No float or double used for any monetary value —
 *       BigDecimal exclusively"; "BigDecimal rounding mode set to
 *       HALF_EVEN matching COBOL PICTURE clause precision"). The
 *       {@code PIC S9(04)V99} field maps to {@code NUMERIC(6,2)} in
 *       PostgreSQL.</li>
 *   <li>No PII fields — only synthetic operational codes (e.g.
 *       {@code "A000000001"}, {@code "01"}, {@code 1}) and a numeric
 *       interest rate. AAP §0.10.5 PII redaction rules do not constrain
 *       this entity's {@link #toString()} output.</li>
 *   <li>No {@code @Version} field — this is reference data, not subject
 *       to optimistic locking. The Flyway seed populates the table once
 *       and no business workflow mutates rows thereafter (the AAP
 *       §0.10.1 Require Test Coverage Rule's reference-data IT only
 *       covers the read path; the write path is exercised solely by the
 *       Flyway script lifecycle and by the synthetic-insert tests in
 *       {@code DiscountGroupRepositoryIT}).</li>
 * </ul>
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.repository.DiscountGroupRepository} compilation
 * and the {@code DiscountGroupRepositoryIT} integration test suite per
 * AAP §0.5.1. Subsequent migration agents (REFACTOR flavor) will add JPA
 * annotations ({@code @Entity}, {@code @EmbeddedId}, {@code @Column},
 * {@code @Table(name = "discount_groups")}) and Bean Validation
 * constraints ({@code @DecimalMax("9999.99")} on {@link #disIntRate})
 * once the entity is wired into the Hibernate {@code SessionFactory}. The
 * Flyway scripts under {@code src/main/resources/db/migration/} (also
 * REFACTOR-flavor) will create the {@code discount_groups} table (with
 * the composite primary key {@code (dis_acct_group_id CHAR(10),
 * dis_tran_type_cd CHAR(2), dis_tran_cat_cd INTEGER)} and the
 * {@code dis_int_rate NUMERIC(6,2)} column) and populate it with the 51
 * reference rows from {@code app/data/ASCII/discgrp.txt}.
 *
 * <h2>Security — toString() Includes All Fields</h2>
 *
 * <p>{@link #toString()} surfaces both the composite key (via
 * {@link DiscountGroupKey#toString()}) and the interest rate. Neither
 * field carries PII or per-account financial data (the codes are
 * operational identifiers, and the rate is a public-policy interest
 * percentage applied uniformly to every account matching the key
 * tuple). Per AAP §0.10.5 reference-data entities are unrestricted in
 * their diagnostic {@code toString} output.
 *
 * @see DiscountGroupKey
 * @see com.aws.carddemo.repository.DiscountGroupRepository
 */
public class DiscountGroup {

    /**
     * Composite primary key holding the
     * {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)} tuple.
     * The REFACTOR-flavor migration agent will annotate this field with
     * {@code @EmbeddedId} once the persistence wiring lands; Hibernate
     * will then materialise the value object from the three key columns
     * of the {@code discount_groups} table on every read and decompose it
     * back into the three columns on every write.
     */
    private DiscountGroupKey key;

    /**
     * {@code DIS-INT-RATE} interest-rate field per {@code CVTRA02Y.cpy}
     * ({@code PIC S9(04)V99}). The COBOL signed packed-decimal field is
     * mapped to a Java {@link BigDecimal} at scale 2 (AAP §0.10.3) so
     * the {@link java.math.RoundingMode#HALF_EVEN} banker's rounding
     * required for interest calculations preserves bit-perfect parity
     * with the COBOL baseline.
     *
     * <p>Values from {@code app/data/ASCII/discgrp.txt} range from
     * {@code 0.00} (zero-APR rows) through {@code 25.00} (the
     * canonical maximum observed in the seed); the {@code PIC S9(04)V99}
     * field width allows up to {@code ±9999.99}. The scale is exactly
     * 2 — even a value of {@code 0.00} retains its scale (rather than
     * collapsing to {@code 0}), and the {@code DiscountGroupRepositoryIT}
     * asserts this scale-preservation invariant per AAP §0.10.3.
     *
     * <p>The corresponding PostgreSQL column type
     * {@code NUMERIC(6,2)} accommodates the full COBOL value range; the
     * Flyway DDL must declare it that way to honour the COBOL
     * {@code PIC S9(04)V99} contract byte-for-byte.
     */
    private BigDecimal disIntRate;

    /**
     * Default no-arg constructor (required by JPA reflection-based
     * instantiation when Hibernate materialises rows from a query result
     * set).
     */
    public DiscountGroup() {
        // intentionally empty
    }

    /**
     * @return the composite primary key carrying the
     *         {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD,
     *         DIS-TRAN-CAT-CD)} tuple
     */
    public DiscountGroupKey getKey() {
        return key;
    }

    /**
     * @param key the composite primary key carrying the
     *            {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD,
     *            DIS-TRAN-CAT-CD)} tuple
     */
    public void setKey(DiscountGroupKey key) {
        this.key = key;
    }

    /**
     * @return the {@code DIS-INT-RATE} interest-rate value as a
     *         {@link BigDecimal} at scale 2 (AAP §0.10.3)
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * @param disIntRate the {@code DIS-INT-RATE} interest-rate value;
     *                   callers must supply a {@link BigDecimal} at
     *                   scale 2 (AAP §0.10.3) — passing a value at a
     *                   different scale is permitted at the entity layer
     *                   but the Flyway-defined column type
     *                   ({@code NUMERIC(6,2)}) will coerce the persisted
     *                   value to scale 2 on write, so callers should
     *                   pre-round to HALF_EVEN before persisting to
     *                   avoid surprise truncation behaviour
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    /**
     * Equality is based on the composite primary key {@link #key} alone.
     * JPA-managed entities are considered equal iff they share the same
     * primary key value; the interest rate is deliberately excluded from
     * equality so transient and managed copies of the same logical
     * reference row compare equal even when one carries a freshly
     * computed rate and the other a previously persisted rate (for
     * example during a refresh-cycle dirty-check inside Hibernate's
     * first-level cache).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiscountGroup)) {
            return false;
        }
        DiscountGroup other = (DiscountGroup) o;
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
     * Diagnostic string surfacing the composite key and the interest rate.
     * Neither field carries PII or per-account financial data — the key
     * components are operational identifiers and the rate is a
     * public-policy interest percentage applied uniformly to every
     * account matching the key tuple — so AAP §0.10.5 redaction rules
     * do not constrain this output.
     */
    @Override
    public String toString() {
        return "DiscountGroup{"
                + "key=" + key
                + ", disIntRate=" + disIntRate
                + '}';
    }
}
