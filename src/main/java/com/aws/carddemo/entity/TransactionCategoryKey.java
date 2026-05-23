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
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.util.Objects;

/**
 * JPA composite primary-key class for {@link TransactionCategory} entities —
 * the Java replacement for the 6-byte {@code TRAN-CAT-KEY} composite group
 * declared at the top of the COBOL {@code TRAN-CAT-RECORD} layout in
 * {@code app/cpy/CVTRA04Y.cpy}.
 *
 * <h2>COBOL Provenance — CVTRA04Y.cpy</h2>
 *
 * <p>The composite key occupies the first 6 bytes of every record in the
 * {@code TRANCATG} reference catalog:
 * <pre>
 *   05 TRAN-CAT-KEY.
 *      10 TRAN-TYPE-CD             PIC X(02).   --&gt; {@link #tranTypeCd} (2-char alphanumeric)
 *      10 TRAN-CAT-CD              PIC 9(04).   --&gt; {@link #tranCatCd}  (4-digit numeric)
 * </pre>
 *
 * <p>The category catalog uses a <em>composite</em> primary key (type + cat)
 * because the same numeric category code can repeat across different
 * transaction types (for example {@code 0001} appears under type {@code 01}
 * "Regular Sales Draft", type {@code 02} "Cash payment", type {@code 03}
 * "Credit to Account", type {@code 04} "Zero dollar authorization",
 * type {@code 05} "Refund credit", type {@code 06} "Fraud reversal", and
 * type {@code 07} "Sales draft credit adjustment" in
 * {@code app/data/ASCII/trancatg.txt}). The 18 canonical rows are
 * uniquely identified only by the {@code (TRAN-TYPE-CD, TRAN-CAT-CD)}
 * tuple — neither field alone is unique.
 *
 * <h2>Field Types — Why {@link String} for type and {@link Integer} for cat</h2>
 *
 * <p>{@link #tranTypeCd} is declared as a {@link String} (2-character alphanumeric)
 * to preserve the COBOL {@code PIC X(02)} byte-for-byte format. The canonical
 * values in {@code trancatg.txt} are zero-padded numeric strings
 * ({@code "01"}–{@code "07"}), but the COBOL field is alphanumeric (X), so
 * Java preserves the leading-zero formatting that an {@link Integer} would
 * silently strip.
 *
 * <p>{@link #tranCatCd} is declared as an {@link Integer} because the COBOL
 * {@code PIC 9(04)} field is strictly numeric — Java {@link Integer} is the
 * natural mapping, and the canonical seed values in {@code trancatg.txt}
 * ({@code "0001"}, {@code "0002"}, {@code "0003"}, …) parse cleanly to
 * integers 1, 2, 3, …. The 4-digit width imposes a maximum value of
 * {@code 9999} (well within {@link Integer#MAX_VALUE}); width enforcement
 * is the Flyway DDL's responsibility (e.g. {@code CHECK (tran_cat_cd
 * BETWEEN 0 AND 9999)}) — not this POJO's.
 *
 * <h2>Serializable Contract</h2>
 *
 * <p>JPA mandates that composite-key classes implement {@link Serializable}
 * (Jakarta Persistence 3.1 §2.4 "Primary Keys and Entity Identity"). The
 * REFACTOR-flavor migration agent will mark this class with the
 * {@code @Embeddable} annotation so Hibernate maps it as a composite key
 * inside the {@link TransactionCategory} entity. Until that annotation
 * lands, the class compiles and round-trips through the test
 * {@code TestEntityManager.persist()} flow once the parent {@link TransactionCategory}
 * is itself annotated with {@code @EmbeddedId TransactionCategoryKey key}.
 *
 * <h2>Equals / HashCode Contract</h2>
 *
 * <p>Both fields participate in {@link #equals(Object)} and
 * {@link #hashCode()} because the key tuple {@code (TRAN-TYPE-CD,
 * TRAN-CAT-CD)} is the row's identity. Hibernate uses these methods
 * extensively (first-level cache lookups, dirty-checking, association
 * deduplication) — getting them wrong yields silently incorrect
 * persistence behaviour.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.repository.TransactionCategoryRepository} compilation
 * and the {@code TransactionCategoryRepositoryIT} integration test suite per
 * AAP §0.5.1. Subsequent REFACTOR-flavor migration agents will add:
 * <ul>
 *   <li>{@code @Embeddable} JPA annotation on the class so Hibernate can use it
 *       as an {@code @EmbeddedId} target on {@link TransactionCategory}.</li>
 *   <li>{@code @Column(name = "tran_type_cd", length = 2, nullable = false)} on
 *       {@link #tranTypeCd}.</li>
 *   <li>{@code @Column(name = "tran_cat_cd", nullable = false)} on
 *       {@link #tranCatCd} (plus a {@code CHECK (tran_cat_cd BETWEEN 0 AND
 *       9999)} constraint in {@code V1__schema.sql}).</li>
 *   <li>Bean Validation constraints ({@code @Size(min = 2, max = 2)} on
 *       {@link #tranTypeCd}, {@code @Min(0)}/{@code @Max(9999)} on
 *       {@link #tranCatCd}) if the project policy requires validation at
 *       the entity layer.</li>
 * </ul>
 *
 * <h2>Security</h2>
 *
 * <p>Neither key field carries PII or financial data: {@link #tranTypeCd}
 * is a 2-character operational identifier ({@code "01"}–{@code "07"}) and
 * {@link #tranCatCd} is a 4-digit reference-data sequence number
 * ({@code 1}–{@code 5}). Per AAP §0.10.5 reference-data keys are
 * unrestricted in their diagnostic {@link #toString()} output.
 *
 * @see TransactionCategory
 * @see com.aws.carddemo.repository.TransactionCategoryRepository
 */
@Embeddable
public class TransactionCategoryKey implements Serializable {

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
     * 2-character {@code TRAN-TYPE-CD} component of the composite key per
     * {@code CVTRA04Y.cpy} ({@code PIC X(02)}). Canonical values from
     * {@code app/data/ASCII/trancatg.txt}: {@code "01"}, {@code "02"},
     * {@code "03"}, {@code "04"}, {@code "05"}, {@code "06"}, {@code "07"}.
     * Each value identifies a {@link TransactionType} reference row keyed by
     * the same 2-character primary key — the two catalogs share their type
     * code so the {@code TRAN-CAT-RECORD} can be resolved to a human-readable
     * type label via {@link TransactionType#getTranTypeDesc()}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type_cd", columnDefinition = "CHAR(2)", nullable = false, length = 2)
    private String tranTypeCd;

    /**
     * 4-digit numeric {@code TRAN-CAT-CD} component of the composite key
     * per {@code CVTRA04Y.cpy} ({@code PIC 9(04)}). Canonical values from
     * {@code app/data/ASCII/trancatg.txt} range from {@code 1} to {@code 5}
     * in the current seed (the field width permits up to {@code 9999}).
     * The 4-digit zero-padded representation ({@code "0001"}, {@code "0002"},
     * …) is the on-disk format from the legacy mainframe; in Java the
     * numeric value is preserved without leading zeros and the persistence
     * layer (Flyway DDL + JDBC driver) is responsible for the zero-padded
     * rendering at the storage and presentation boundaries.
     */
    @Column(name = "tran_cat_cd", nullable = false)
    private Integer tranCatCd;

    /**
     * Default no-arg constructor required by JPA reflection-based
     * instantiation. Composite-key classes are constructed by Hibernate
     * via {@link Class#newInstance()} or a no-arg
     * {@link java.lang.reflect.Constructor#newInstance(Object...)} call
     * when materialising entities from a {@code SELECT} result set.
     */
    public TransactionCategoryKey() {
        // intentionally empty
    }

    /**
     * @return the 2-character {@code TRAN-TYPE-CD} component of the composite key
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * @param tranTypeCd the 2-character {@code TRAN-TYPE-CD} component of the
     *                   composite key
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * @return the 4-digit numeric {@code TRAN-CAT-CD} component of the
     *         composite key
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * @param tranCatCd the 4-digit numeric {@code TRAN-CAT-CD} component of
     *                  the composite key
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Equality is computed from <em>both</em> components of the composite
     * key, mirroring the COBOL row-identity contract: a {@code TRAN-CAT-RECORD}
     * row is uniquely identified by the {@code (TRAN-TYPE-CD, TRAN-CAT-CD)}
     * tuple, not by either field alone.
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
        if (!(o instanceof TransactionCategoryKey)) {
            return false;
        }
        TransactionCategoryKey other = (TransactionCategoryKey) o;
        return Objects.equals(tranTypeCd, other.tranTypeCd)
                && Objects.equals(tranCatCd, other.tranCatCd);
    }

    /**
     * Hash computed from both key components, consistent with
     * {@link #equals(Object)}. Two instances that compare equal under
     * {@link #equals(Object)} must produce the same hash code (general
     * Java contract; mandatory for use as a {@link java.util.HashMap} key
     * or in the JPA second-level cache region key).
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }

    /**
     * Diagnostic string surfacing both key components. Neither field carries
     * PII or financial data — the type code is a 2-character operational
     * identifier and the category code is a 4-digit reference-data sequence
     * number — so AAP §0.10.5 redaction rules do not constrain this output.
     */
    @Override
    public String toString() {
        return "TransactionCategoryKey{"
                + "tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd=" + tranCatCd
                + '}';
    }
}
