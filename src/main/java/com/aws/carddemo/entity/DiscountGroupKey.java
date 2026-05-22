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

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA composite primary-key class for {@link DiscountGroup} entities — the
 * Java replacement for the 16-byte {@code DIS-GROUP-KEY} composite group
 * declared at the top of the COBOL {@code DIS-GROUP-RECORD} layout in
 * {@code app/cpy/CVTRA02Y.cpy}.
 *
 * <h2>COBOL Provenance — CVTRA02Y.cpy</h2>
 *
 * <p>The composite key occupies the first 16 bytes of every record in the
 * {@code DISCGRP} VSAM KSDS reference catalog (50-byte RECLN):
 * <pre>
 *   05 DIS-GROUP-KEY.
 *      10 DIS-ACCT-GROUP-ID         PIC X(10).   --&gt; {@link #disAcctGroupId} (10-char alphanumeric)
 *      10 DIS-TRAN-TYPE-CD          PIC X(02).   --&gt; {@link #disTranTypeCd}  (2-char alphanumeric)
 *      10 DIS-TRAN-CAT-CD           PIC 9(04).   --&gt; {@link #disTranCatCd}   (4-digit numeric)
 * </pre>
 *
 * <p>The disclosure-group catalog uses a <em>3-tuple</em> composite primary
 * key because a single account group references multiple interest-rate rows
 * keyed by transaction type and category (e.g. group {@code A000000001}
 * appears under type {@code 01} categories {@code 0001}, {@code 0002},
 * {@code 0003}, {@code 0004} in {@code app/data/ASCII/discgrp.txt}). The
 * 51 canonical rows are uniquely identified only by the
 * {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)} tuple —
 * no two-field subset is unique.
 *
 * <h2>Special-Case Group Identifiers</h2>
 *
 * <p>Two special-case 10-character {@code DIS-ACCT-GROUP-ID} values drive
 * the {@code CBACT04C} interest-calculator branch logic:
 * <ul>
 *   <li>{@code "DEFAULT   "} (7 characters plus 3 trailing spaces, total
 *       10 characters) — the fallback group consulted when an account's
 *       configured disclosure group is not present in the catalog. The
 *       COBOL paragraph {@code 1200-A-GET-DEFAULT-INT-RATE} re-reads the
 *       {@code DISCGRP-FILE} after moving {@code 'DEFAULT'} into
 *       {@code FD-DIS-ACCT-GROUP-ID} (line 437 of {@code CBACT04C.CBL}).</li>
 *   <li>{@code "ZEROAPR   "} (7 characters plus 3 trailing spaces, total
 *       10 characters) — the zero-APR override group; every row keyed by
 *       this group ID carries a {@code DIS-INT-RATE} of {@code 0.00}, so
 *       the {@code IF DIS-INT-RATE NOT = 0} guard inside paragraph
 *       {@code 1300-COMPUTE-INTEREST} skips the interest calculation.</li>
 * </ul>
 *
 * <h2>Field Types — Why {@link String} for ID and Type, {@link Integer} for Cat</h2>
 *
 * <p>{@link #disAcctGroupId} is declared as a {@link String} (10-character
 * alphanumeric) to preserve the COBOL {@code PIC X(10)} byte-for-byte
 * format including the trailing-space padding on the {@code "DEFAULT"} and
 * {@code "ZEROAPR"} sentinels. The canonical {@code A000000001}-style
 * group IDs are zero-padded alphanumeric strings, not numerics — Java
 * {@link String} is the natural mapping.
 *
 * <p>{@link #disTranTypeCd} is declared as a {@link String} (2-character
 * alphanumeric) to preserve the COBOL {@code PIC X(02)} byte-for-byte
 * format. The canonical values in {@code discgrp.txt} are zero-padded
 * numeric strings ({@code "01"}–{@code "07"}), but the COBOL field is
 * alphanumeric (X), so Java preserves the leading-zero formatting that
 * an {@link Integer} would silently strip.
 *
 * <p>{@link #disTranCatCd} is declared as an {@link Integer} because the
 * COBOL {@code PIC 9(04)} field is strictly numeric — Java {@link Integer}
 * is the natural mapping, and the canonical values in {@code discgrp.txt}
 * ({@code "0001"}, {@code "0002"}, {@code "0003"}, …) parse cleanly to
 * integers 1, 2, 3, …. The 4-digit width imposes a maximum value of
 * {@code 9999} (well within {@link Integer#MAX_VALUE}); width enforcement
 * is the Flyway DDL's responsibility (e.g. {@code CHECK (dis_tran_cat_cd
 * BETWEEN 0 AND 9999)}) — not this POJO's.
 *
 * <h2>Serializable Contract</h2>
 *
 * <p>JPA mandates that composite-key classes implement {@link Serializable}
 * (Jakarta Persistence 3.1 §2.4 "Primary Keys and Entity Identity"). The
 * REFACTOR-flavor migration agent will mark this class with the
 * {@code @Embeddable} annotation so Hibernate maps it as a composite key
 * inside the {@link DiscountGroup} entity. Until that annotation lands,
 * the class compiles and round-trips through the test
 * {@code TestEntityManager.persist()} flow once the parent
 * {@link DiscountGroup} is itself annotated with
 * {@code @EmbeddedId DiscountGroupKey key}.
 *
 * <h2>Equals / HashCode Contract</h2>
 *
 * <p>All three fields participate in {@link #equals(Object)} and
 * {@link #hashCode()} because the key tuple
 * {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)} is the
 * row's identity. Hibernate uses these methods extensively (first-level
 * cache lookups, dirty-checking, association deduplication) — getting
 * them wrong yields silently incorrect persistence behaviour.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.repository.DiscountGroupRepository} compilation
 * and the {@code DiscountGroupRepositoryIT} integration test suite per
 * AAP §0.5.1. Subsequent REFACTOR-flavor migration agents will add:
 * <ul>
 *   <li>{@code @Embeddable} JPA annotation on the class so Hibernate can use it
 *       as an {@code @EmbeddedId} target on {@link DiscountGroup}.</li>
 *   <li>{@code @Column(name = "dis_acct_group_id", length = 10, nullable = false)}
 *       on {@link #disAcctGroupId} — {@code CHAR(10)} on the PostgreSQL side
 *       to preserve the trailing-space padding required by the
 *       {@code "DEFAULT   "} / {@code "ZEROAPR   "} sentinels.</li>
 *   <li>{@code @Column(name = "dis_tran_type_cd", length = 2, nullable = false)}
 *       on {@link #disTranTypeCd}.</li>
 *   <li>{@code @Column(name = "dis_tran_cat_cd", nullable = false)} on
 *       {@link #disTranCatCd} (plus a {@code CHECK (dis_tran_cat_cd BETWEEN 0
 *       AND 9999)} constraint in {@code V1__schema.sql}).</li>
 *   <li>Bean Validation constraints ({@code @Size(min = 10, max = 10)} on
 *       {@link #disAcctGroupId}, {@code @Size(min = 2, max = 2)} on
 *       {@link #disTranTypeCd}, {@code @Min(0)}/{@code @Max(9999)} on
 *       {@link #disTranCatCd}) if the project policy requires validation at
 *       the entity layer.</li>
 * </ul>
 *
 * <h2>Security</h2>
 *
 * <p>None of the key fields carry PII or financial data: {@link #disAcctGroupId}
 * is a 10-character operational identifier (canonical {@code A000000001}-style
 * or the sentinel values {@code "DEFAULT   "} / {@code "ZEROAPR   "}),
 * {@link #disTranTypeCd} is a 2-character operational identifier
 * ({@code "01"}–{@code "07"}), and {@link #disTranCatCd} is a 4-digit
 * reference-data sequence number ({@code 1}–{@code 5}). Per AAP §0.10.5
 * reference-data keys are unrestricted in their diagnostic
 * {@link #toString()} output.
 *
 * @see DiscountGroup
 * @see com.aws.carddemo.repository.DiscountGroupRepository
 */
public class DiscountGroupKey implements Serializable {

    /**
     * Serial version UID for {@link Serializable} compliance. The composite-key
     * class is part of the JPA contract surface and may be passed by reference
     * across JVM boundaries (for example in second-level cache eviction
     * messages or in clustered session state) — declaring a stable
     * {@code serialVersionUID} prevents the JVM from synthesising a
     * version-fingerprint hash from the class's bytecode (which would
     * change every time a getter or setter is reformatted).
     */
    private static final long serialVersionUID = 1L;

    /**
     * 10-character {@code DIS-ACCT-GROUP-ID} component of the composite key
     * per {@code CVTRA02Y.cpy} ({@code PIC X(10)}). Canonical values from
     * {@code app/data/ASCII/discgrp.txt} include:
     * <ul>
     *   <li>{@code "A000000001"}–{@code "A000000007"} — account-specific
     *       disclosure groups, zero-padded alphanumeric identifiers.</li>
     *   <li>{@code "DEFAULT   "} — sentinel for the catch-all fallback
     *       group (7 characters of {@code "DEFAULT"} plus 3 trailing spaces).</li>
     *   <li>{@code "ZEROAPR   "} — sentinel for the zero-APR override group
     *       (7 characters of {@code "ZEROAPR"} plus 3 trailing spaces).</li>
     * </ul>
     * The 10-character width is significant for the sentinel values: the
     * COBOL {@code PIC X(10)} field is space-padded to its declared width,
     * so the Java side must preserve the trailing spaces verbatim so that
     * {@code findById} lookups match the on-disk storage byte-for-byte.
     */
    private String disAcctGroupId;

    /**
     * 2-character {@code DIS-TRAN-TYPE-CD} component of the composite key
     * per {@code CVTRA02Y.cpy} ({@code PIC X(02)}). Canonical values from
     * {@code app/data/ASCII/discgrp.txt}: {@code "01"}–{@code "07"} (the
     * same transaction-type codes used by {@code TransactionType} and
     * {@code TransactionCategoryKey#tranTypeCd}). Each value identifies a
     * {@link TransactionType} reference row keyed by the same 2-character
     * primary key — the discount-group catalog shares its type code with
     * the transaction-type catalog so the interest-rate row can be
     * resolved to a human-readable type label via
     * {@link TransactionType#getTranTypeDesc()}.
     */
    private String disTranTypeCd;

    /**
     * 4-digit numeric {@code DIS-TRAN-CAT-CD} component of the composite key
     * per {@code CVTRA02Y.cpy} ({@code PIC 9(04)}). Canonical values from
     * {@code app/data/ASCII/discgrp.txt} range from {@code 1} to {@code 5}
     * in the current seed (the field width permits up to {@code 9999}).
     * The 4-digit zero-padded representation ({@code "0001"}, {@code "0002"},
     * …) is the on-disk format from the legacy mainframe; in Java the
     * numeric value is preserved without leading zeros and the persistence
     * layer (Flyway DDL + JDBC driver) is responsible for the zero-padded
     * rendering at the storage and presentation boundaries.
     */
    private Integer disTranCatCd;

    /**
     * Default no-arg constructor required by JPA reflection-based
     * instantiation. Composite-key classes are constructed by Hibernate
     * via {@link Class#newInstance()} or a no-arg
     * {@link java.lang.reflect.Constructor#newInstance(Object...)} call
     * when materialising entities from a {@code SELECT} result set.
     */
    public DiscountGroupKey() {
        // intentionally empty
    }

    /**
     * @return the 10-character {@code DIS-ACCT-GROUP-ID} component of the
     *         composite key
     */
    public String getDisAcctGroupId() {
        return disAcctGroupId;
    }

    /**
     * @param disAcctGroupId the 10-character {@code DIS-ACCT-GROUP-ID}
     *                       component of the composite key
     */
    public void setDisAcctGroupId(String disAcctGroupId) {
        this.disAcctGroupId = disAcctGroupId;
    }

    /**
     * @return the 2-character {@code DIS-TRAN-TYPE-CD} component of the
     *         composite key
     */
    public String getDisTranTypeCd() {
        return disTranTypeCd;
    }

    /**
     * @param disTranTypeCd the 2-character {@code DIS-TRAN-TYPE-CD}
     *                      component of the composite key
     */
    public void setDisTranTypeCd(String disTranTypeCd) {
        this.disTranTypeCd = disTranTypeCd;
    }

    /**
     * @return the 4-digit numeric {@code DIS-TRAN-CAT-CD} component of
     *         the composite key
     */
    public Integer getDisTranCatCd() {
        return disTranCatCd;
    }

    /**
     * @param disTranCatCd the 4-digit numeric {@code DIS-TRAN-CAT-CD}
     *                     component of the composite key
     */
    public void setDisTranCatCd(Integer disTranCatCd) {
        this.disTranCatCd = disTranCatCd;
    }

    /**
     * Equality is computed from <em>all three</em> components of the
     * composite key, mirroring the COBOL row-identity contract: a
     * {@code DIS-GROUP-RECORD} row is uniquely identified by the
     * {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)} tuple,
     * not by any subset of the fields.
     *
     * <p>Hibernate relies on this method during first-level cache lookups,
     * dirty-checking, and association deduplication. A class-cast guard
     * via {@code instanceof} keeps the method polymorphism-safe against
     * proxies and subclasses Hibernate may synthesise at runtime.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiscountGroupKey)) {
            return false;
        }
        DiscountGroupKey other = (DiscountGroupKey) o;
        return Objects.equals(disAcctGroupId, other.disAcctGroupId)
                && Objects.equals(disTranTypeCd, other.disTranTypeCd)
                && Objects.equals(disTranCatCd, other.disTranCatCd);
    }

    /**
     * Hash computed from all three key components, consistent with
     * {@link #equals(Object)}. Two instances that compare equal under
     * {@link #equals(Object)} must produce the same hash code (general
     * Java contract; mandatory for use as a {@link java.util.HashMap} key
     * or in the JPA second-level cache region key).
     */
    @Override
    public int hashCode() {
        return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }

    /**
     * Diagnostic string surfacing all three key components. None of the
     * fields carry PII or financial data — the account-group ID is an
     * operational identifier, the type code is a 2-character operational
     * identifier, and the category code is a 4-digit reference-data
     * sequence number — so AAP §0.10.5 redaction rules do not constrain
     * this output.
     */
    @Override
    public String toString() {
        return "DiscountGroupKey{"
                + "disAcctGroupId='" + disAcctGroupId + '\''
                + ", disTranTypeCd='" + disTranTypeCd + '\''
                + ", disTranCatCd=" + disTranCatCd
                + '}';
    }
}
