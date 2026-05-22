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
 * JPA composite primary-key class for {@link TransactionCategoryBalance} entities —
 * the Java replacement for the 17-byte {@code TRAN-CAT-KEY} composite group
 * declared at the top of the COBOL {@code TRAN-CAT-BAL-RECORD} layout in
 * {@code app/cpy/CVTRA01Y.cpy}.
 *
 * <h2>COBOL Provenance — CVTRA01Y.cpy</h2>
 *
 * <p>The composite key occupies the first 17 bytes of every record in the
 * {@code TCATBAL} VSAM KSDS file:
 * <pre>
 *   05 TRAN-CAT-KEY.
 *      10 TRANCAT-ACCT-ID         PIC 9(11).   --&gt; {@link #trancatAcctId} (11-digit numeric account FK)
 *      10 TRANCAT-TYPE-CD         PIC X(02).   --&gt; {@link #trancatTypeCd} (2-char alphanumeric type code)
 *      10 TRANCAT-CD              PIC 9(04).   --&gt; {@link #trancatCd}     (4-digit numeric category code)
 * </pre>
 *
 * <p>The balance catalog uses a <em>composite</em> primary key because the
 * same {@code (TRANCAT-TYPE-CD, TRANCAT-CD)} pair can repeat across different
 * accounts (every {@code accounts} row has its own family of category
 * balances), and the same {@code (TRANCAT-ACCT-ID, TRANCAT-CD)} pair can
 * repeat across different transaction-type codes (one account can have a
 * purchase balance row and a payment balance row for the same category).
 * The 50 canonical rows in {@code app/data/ASCII/tcatbal.txt} are uniquely
 * identified only by the full 3-tuple — no single field is unique.
 *
 * <h2>Field Types — Why {@link String} for the account FK and type code,
 * {@link Integer} for the category code</h2>
 *
 * <p>{@link #trancatAcctId} is declared as a {@link String} (11-character
 * numeric) to preserve the COBOL {@code PIC 9(11)} zero-padded format
 * byte-for-byte (e.g., {@code "00000000010"}). An {@link Integer} mapping
 * would silently strip the leading zeros that the fixed-width on-disk
 * record carries; the {@link String} mapping preserves the formatting that
 * downstream output files and JCL-equivalent batch jobs must emit verbatim
 * (AAP §0.10.4 immutable boundaries).
 *
 * <p>{@link #trancatTypeCd} is declared as a {@link String} (2-character
 * alphanumeric) to preserve the COBOL {@code PIC X(02)} byte-for-byte
 * format. The canonical values in {@code trancatg.txt} are zero-padded
 * numeric strings ({@code "01"}–{@code "07"}), but the COBOL field is
 * alphanumeric (X), so Java preserves the leading-zero formatting that an
 * {@link Integer} would silently strip.
 *
 * <p>{@link #trancatCd} is declared as an {@link Integer} because the COBOL
 * {@code PIC 9(04)} field is strictly numeric. The canonical seed values
 * in {@code trancatg.txt} ({@code "0001"}, {@code "0002"}, {@code "0003"},
 * …) parse cleanly to integers 1, 2, 3, …. The 4-digit width imposes a
 * maximum value of {@code 9999} (well within {@link Integer#MAX_VALUE});
 * width enforcement is the Flyway DDL's responsibility (e.g.
 * {@code CHECK (trancat_cd BETWEEN 0 AND 9999)}) — not this POJO's.
 *
 * <h2>Serializable Contract</h2>
 *
 * <p>JPA mandates that composite-key classes implement {@link Serializable}
 * (Jakarta Persistence 3.1 §2.4 "Primary Keys and Entity Identity"). The
 * REFACTOR-flavor migration agent will mark this class with the
 * {@code @Embeddable} annotation so Hibernate maps it as a composite key
 * inside the {@link TransactionCategoryBalance} entity. Until that
 * annotation lands, the class compiles and round-trips through the test
 * {@code TestEntityManager.persist()} flow once the parent
 * {@link TransactionCategoryBalance} is itself annotated with
 * {@code @EmbeddedId TransactionCategoryBalanceKey key}.
 *
 * <h2>Equals / HashCode Contract</h2>
 *
 * <p>All three fields participate in {@link #equals(Object)} and
 * {@link #hashCode()} because the key tuple {@code (TRANCAT-ACCT-ID,
 * TRANCAT-TYPE-CD, TRANCAT-CD)} is the row's identity. Hibernate uses
 * these methods extensively (first-level cache lookups, dirty-checking,
 * association deduplication) — getting them wrong yields silently
 * incorrect persistence behaviour.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to
 * satisfy {@link com.aws.carddemo.repository.TransactionCategoryBalanceRepository}
 * compilation and the {@code TransactionCategoryBalanceRepositoryIT}
 * integration test suite per AAP §0.5.1. Subsequent REFACTOR-flavor
 * migration agents will add:
 * <ul>
 *   <li>{@code @Embeddable} JPA annotation on the class so Hibernate can
 *       use it as an {@code @EmbeddedId} target on
 *       {@link TransactionCategoryBalance}.</li>
 *   <li>{@code @Column(name = "trancat_acct_id", length = 11, nullable = false)}
 *       on {@link #trancatAcctId}.</li>
 *   <li>{@code @Column(name = "trancat_type_cd", length = 2, nullable = false)}
 *       on {@link #trancatTypeCd}.</li>
 *   <li>{@code @Column(name = "trancat_cd", nullable = false)} on
 *       {@link #trancatCd} (plus a {@code CHECK (trancat_cd BETWEEN 0 AND
 *       9999)} constraint in {@code V1__schema.sql}).</li>
 *   <li>Bean Validation constraints ({@code @Size(min = 11, max = 11)} on
 *       {@link #trancatAcctId}, {@code @Size(min = 2, max = 2)} on
 *       {@link #trancatTypeCd}, {@code @Min(0)}/{@code @Max(9999)} on
 *       {@link #trancatCd}) if the project policy requires validation at
 *       the entity layer.</li>
 * </ul>
 *
 * <h2>Security</h2>
 *
 * <p>None of the key fields carry PII or financial data:
 * {@link #trancatAcctId} is a synthetic 11-digit account identifier from
 * the fixture data, {@link #trancatTypeCd} is a 2-character operational
 * type code ({@code "01"}–{@code "07"}), and {@link #trancatCd} is a
 * 4-digit reference-data sequence number ({@code 1}–{@code 5}). Per AAP
 * §0.10.5 reference-data keys are unrestricted in their diagnostic
 * {@link #toString()} output.
 *
 * @see TransactionCategoryBalance
 * @see com.aws.carddemo.repository.TransactionCategoryBalanceRepository
 */
public class TransactionCategoryBalanceKey implements Serializable {

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
     * 11-character {@code TRANCAT-ACCT-ID} component of the composite key
     * per {@code CVTRA01Y.cpy} ({@code PIC 9(11)}). Zero-padded numeric
     * string (e.g., {@code "00000000010"}). Each value identifies an
     * {@link Account} row keyed by the same 11-character primary key — the
     * two catalogs share their account identifier so the
     * {@code TRAN-CAT-BAL-RECORD} can be resolved to a parent account
     * via {@link Account#getAccountId()}.
     */
    private String trancatAcctId;

    /**
     * 2-character {@code TRANCAT-TYPE-CD} component of the composite key
     * per {@code CVTRA01Y.cpy} ({@code PIC X(02)}). Canonical values from
     * {@code app/data/ASCII/trantype.txt}: {@code "01"}, {@code "02"},
     * {@code "03"}, {@code "04"}, {@code "05"}, {@code "06"}, {@code "07"}.
     * Each value identifies a {@link TransactionType} reference row keyed
     * by the same 2-character primary key.
     */
    private String trancatTypeCd;

    /**
     * 4-digit numeric {@code TRANCAT-CD} component of the composite key
     * per {@code CVTRA01Y.cpy} ({@code PIC 9(04)}). Canonical values from
     * {@code app/data/ASCII/trancatg.txt} range from {@code 1} to {@code 5}
     * in the current seed (the field width permits up to {@code 9999}).
     * The 4-digit zero-padded representation ({@code "0001"}, {@code "0002"},
     * …) is the on-disk format from the legacy mainframe; in Java the
     * numeric value is preserved without leading zeros and the persistence
     * layer (Flyway DDL + JDBC driver) is responsible for the zero-padded
     * rendering at the storage and presentation boundaries.
     */
    private Integer trancatCd;

    /**
     * Default no-arg constructor required by JPA reflection-based
     * instantiation. Composite-key classes are constructed by Hibernate
     * via {@link Class#newInstance()} or a no-arg
     * {@link java.lang.reflect.Constructor#newInstance(Object...)} call
     * when materialising entities from a {@code SELECT} result set.
     */
    public TransactionCategoryBalanceKey() {
        // intentionally empty
    }

    /**
     * @return the 11-character {@code TRANCAT-ACCT-ID} component of the
     *         composite key
     */
    public String getTrancatAcctId() {
        return trancatAcctId;
    }

    /**
     * @param trancatAcctId the 11-character {@code TRANCAT-ACCT-ID}
     *                      component of the composite key
     */
    public void setTrancatAcctId(String trancatAcctId) {
        this.trancatAcctId = trancatAcctId;
    }

    /**
     * @return the 2-character {@code TRANCAT-TYPE-CD} component of the
     *         composite key
     */
    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    /**
     * @param trancatTypeCd the 2-character {@code TRANCAT-TYPE-CD}
     *                      component of the composite key
     */
    public void setTrancatTypeCd(String trancatTypeCd) {
        this.trancatTypeCd = trancatTypeCd;
    }

    /**
     * @return the 4-digit numeric {@code TRANCAT-CD} component of the
     *         composite key
     */
    public Integer getTrancatCd() {
        return trancatCd;
    }

    /**
     * @param trancatCd the 4-digit numeric {@code TRANCAT-CD} component
     *                  of the composite key
     */
    public void setTrancatCd(Integer trancatCd) {
        this.trancatCd = trancatCd;
    }

    /**
     * Equality is computed from <em>all three</em> components of the composite
     * key, mirroring the COBOL row-identity contract: a
     * {@code TRAN-CAT-BAL-RECORD} row is uniquely identified by the
     * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)} tuple, not by
     * any subset thereof.
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
        if (!(o instanceof TransactionCategoryBalanceKey)) {
            return false;
        }
        TransactionCategoryBalanceKey other = (TransactionCategoryBalanceKey) o;
        return Objects.equals(trancatAcctId, other.trancatAcctId)
                && Objects.equals(trancatTypeCd, other.trancatTypeCd)
                && Objects.equals(trancatCd, other.trancatCd);
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
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }

    /**
     * Diagnostic string surfacing all three key components. None of the
     * fields carries PII or financial data — the account identifier is a
     * synthetic fixture-data value, the type code is a 2-character
     * operational identifier, and the category code is a 4-digit
     * reference-data sequence number — so AAP §0.10.5 redaction rules do
     * not constrain this output.
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalanceKey{"
                + "trancatAcctId='" + trancatAcctId + '\''
                + ", trancatTypeCd='" + trancatTypeCd + '\''
                + ", trancatCd=" + trancatCd
                + '}';
    }
}
