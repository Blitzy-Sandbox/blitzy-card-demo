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
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code tran_category} lookup table
 * (Flyway migration {@code V009__create_transaction_category.sql}). This
 * entity is the Java target for the COBOL {@code TRAN-CAT-RECORD} layout
 * defined in {@code app/cpy/CVTRA04Y.cpy} (RECLN = 60 bytes), and replaces
 * the mainframe VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS}.
 *
 * <h2>Purpose</h2>
 * <p>Represents a single canonical {@code (transaction-type, category)}
 * lookup row mapping a 2-character transaction-type code plus a 4-digit
 * category code to a human-readable category description. The table is a
 * small, read-mostly reference table forming the dimensional lookup for
 * every transactional and rate-lookup query in the system.</p>
 *
 * <p>The lookup is keyed by a 6-byte composite VSAM key (per the IDCAMS
 * {@code DEFINE CLUSTER KEYS(6, 0)} clause in {@code app/jcl/TRANCATG.jcl}
 * and {@code app/catlg/LISTCAT.txt KEYLEN=6, RKP=0}), which decomposes
 * into two logical key columns whose widths sum exactly to 6 bytes:</p>
 * <pre>
 *     TRAN-TYPE-CD (PIC X(02), 2 bytes)
 *   + TRAN-CAT-CD  (PIC 9(04), 4 bytes)
 *   = 6-byte VSAM composite key
 * </pre>
 *
 * <h2>Canonical reference values (seeded by V014)</h2>
 * <p>18 reference rows are seeded by
 * {@code V014__seed_transaction_category.sql} from
 * {@code app/data/ASCII/trancatg.txt}, grouped by transaction-type code:
 * 5 Purchase categories ({@code '01'}), 3 Payment categories
 * ({@code '02'}), 3 Credit categories ({@code '03'}), 3 Authorization
 * categories ({@code '04'}), 1 Refund category ({@code '05'}), 2 Reversal
 * categories ({@code '06'}), and 1 Adjustment category ({@code '07'}).</p>
 *
 * <h2>Consumers (Java services that read this table)</h2>
 * <ul>
 *   <li>{@code TransactionPostingService} (COBOL {@code CBTRN02C})
 *       &mdash; validates the incoming {@code (TRAN-TYPE-CD, TRAN-CAT-CD)}
 *       pair against this lookup during the 4-stage validation cascade.</li>
 *   <li>{@code TransactionReportService} (COBOL {@code CBTRN03C}) &mdash;
 *       joins to produce human-readable transaction-category labels on
 *       the generated report.</li>
 *   <li>{@code TransactionAddService} (COBOL {@code COTRN02C}) &mdash;
 *       validates user-supplied {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} before
 *       INSERT into {@code transactions}.</li>
 *   <li>{@code InterestCalculationService} (COBOL {@code CBACT04C})
 *       &mdash; composite-key lookups against {@code disclosure_group}
 *       include the {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} pair from this
 *       table as a reference dimension.</li>
 *   <li>{@code tran_cat_bal} (V006) and {@code transactions} (V005)
 *       reference the composite key tuple as a dimensional lookup.</li>
 * </ul>
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook:</b> {@code app/cpy/CVTRA04Y.cpy} &mdash;
 *       60-byte fixed-width record layout with 3 business fields plus
 *       a 4-byte trailing {@code FILLER PIC X(04)}. The FILLER has no
 *       relational equivalent and is omitted from this entity.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS} &mdash;
 *       {@code KEYS(6, 0)}, {@code RECORDSIZE(60, 60)},
 *       {@code SHAREOPTIONS(2, 3)}, {@code ERASE}, {@code INDEXED},
 *       {@code REC-TOTAL=18} (per {@code app/jcl/TRANCATG.jcl}:L36-L48
 *       and {@code app/catlg/LISTCAT.txt}).</li>
 *   <li><b>JCL allocation/load:</b>
 *       {@code app/jcl/TRANCATG.jcl} (STEP10 IDCAMS DEFINE CLUSTER,
 *       STEP15 IDCAMS REPRO from {@code TRANCATG.PS}).</li>
 *   <li><b>Golden fixture:</b> {@code app/data/ASCII/trancatg.txt}
 *       (18 fixed-width 60-byte records; positions 57-60 contain the
 *       4-byte literal {@code "0000"} representing the COBOL FILLER
 *       &mdash; verified to be padding, not data).</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V009__create_transaction_category.sql}.</li>
 *   <li><b>Seed migration:</b>
 *       {@code src/main/resources/db/migration/V014__seed_transaction_category.sql}.</li>
 * </ul>
 *
 * <h2>Schema invariants</h2>
 * <ul>
 *   <li><b>Composite primary key</b> &mdash; a 2-column composite
 *       {@code (tran_type_cd, tran_cat_cd)}, mirrored on the Java side
 *       by the nested {@link TransactionCategoryId} {@link Embeddable}
 *       class. Hibernate's
 *       {@code spring.jpa.hibernate.ddl-auto: validate} verifies this
 *       at startup against the V009 DDL.</li>
 *   <li><b>No optimistic locking</b> &mdash; this entity has no
 *       {@code @Version} column. The lookup data is static reference
 *       data seeded once by V014 and not updated at runtime; there is
 *       no read-modify-write contention to guard against.</li>
 *   <li><b>No association mappings</b> &mdash; the database-level
 *       FK to {@code tran_type.tran_type} (declared in V009 as
 *       {@code fk_tran_category_tran_type} with
 *       {@code ON DELETE NO ACTION}) is NOT mirrored as a JPA
 *       {@code @ManyToOne} association on this entity. The scalar
 *       {@link TransactionCategoryId#getTranTypeCd() tranTypeCd}
 *       sub-field is the only Java representation. This is a deliberate
 *       Minimal Change Clause decision (AAP &sect;0.7.3): associations
 *       are not required by any consumer service, and avoiding them
 *       keeps the JPA mapping byte-faithful to the COBOL flat-record
 *       model without introducing new traversal patterns.</li>
 *   <li><b>FILLER omitted</b> &mdash; the trailing 4-byte
 *       {@code FILLER PIC X(04)} from the COBOL record has no
 *       relational counterpart and is not declared as a Java field.</li>
 * </ul>
 *
 * <h2>JPA mapping discipline</h2>
 * <ul>
 *   <li>{@code @Table(name = "tran_category")} matches V009 DDL table
 *       name <em>exactly</em> (singular, snake_case).</li>
 *   <li>{@code @EmbeddedId} field is the nested
 *       {@link TransactionCategoryId} {@link Embeddable} value type,
 *       which encapsulates the 2-column composite primary key.</li>
 *   <li>{@code @Column(name = "tran_cat_type_desc", length = 50,
 *       nullable = false)} on the description field matches V009's
 *       {@code VARCHAR(50) NOT NULL} declaration.</li>
 *   <li>All {@code @Column} {@code nullable}, {@code length}, and
 *       {@code precision} attributes correspond 1-to-1 with the V009
 *       DDL.</li>
 * </ul>
 *
 * <h2>Data quality and immutability</h2>
 * <p>Although this entity exposes setters for both fields (per the
 * JavaBean contract required by Spring Data JPA derived queries and
 * Jackson serialization), <b>in practice this table is treated as
 * immutable reference data</b>. The only legitimate write path is the
 * one-time V014 seed migration; runtime services SHOULD only read from
 * this table. The setters exist for the entity-lifecycle plumbing that
 * JPA requires &mdash; not for application-level mutation.</p>
 *
 * <p>The COBOL group name {@code TRAN-CAT-RECORD} corresponds to the
 * Java class name {@code TransactionCategory} per the AAP &sect;0.7.3
 * refactor-discipline naming convention (the {@code TRAN-} prefix is
 * expanded to {@code Transaction} in Java for readability while the
 * inline COBOL traceability comments preserve the source mapping).</p>
 *
 * @see com.awsm2.carddemo.domain.TransactionType
 * @see com.awsm2.carddemo.domain.TransactionCategoryBalance
 * @see com.awsm2.carddemo.domain.DisclosureGroup
 * @see com.awsm2.carddemo.domain.Transaction
 * @see com.awsm2.carddemo.domain.TransactionCategory.TransactionCategoryId
 */
@Entity
@Table(name = "tran_category")
public class TransactionCategory implements Serializable {

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
    // TRAN-CAT-RECORD layout in app/cpy/CVTRA04Y.cpy:
    //
    //   05  TRAN-CAT-KEY.                          (composite 6-byte key)
    //      10 TRAN-TYPE-CD       PIC X(02).         -> @EmbeddedId sub-field
    //      10 TRAN-CAT-CD        PIC 9(04).         -> @EmbeddedId sub-field
    //   05  TRAN-CAT-TYPE-DESC   PIC X(50).         -> tranCatTypeDesc (String)
    //   05  FILLER               PIC X(04).         -> OMITTED
    //
    // The single trailing FILLER PIC X(04) (which brings the COBOL record
    // to its declared 60-byte RECORDSIZE) is intentionally omitted; the
    // source fixture's positions 57-60 contain the literal "0000" (4
    // zeros) for every row, confirming that the FILLER is padding rather
    // than data.
    // -------------------------------------------------------------------------

    /**
     * The 6-byte VSAM composite key &mdash; the JPA {@link EmbeddedId}.
     *
     * <p>Encapsulates the two composite-key sub-fields
     * {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} mapped to the PostgreSQL
     * columns {@code (tran_type_cd, tran_cat_cd)} that form the
     * {@code pk_tran_category} composite primary-key constraint
     * declared in V009.</p>
     *
     * <p>The 2-column order matches the COBOL byte order
     * ({@code type_cd} leftmost, {@code cat_cd} rightmost), preserving
     * the implicit lexicographic ordering of the VSAM KSDS for any
     * future queries that want to range-scan within a single
     * transaction-type code (e.g., {@code WHERE tran_type_cd = '01'}
     * uses the composite B-tree's leading column).</p>
     */
    // COBOL: CVTRA04Y.cpy:L5-L7 TRAN-CAT-KEY (composite 6 bytes) -- @EmbeddedId
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Human-readable description of the transaction category (e.g.,
     * {@code "Regular Sales Draft"}, {@code "Cash payment"}).
     *
     * <p>Maps to the COBOL field
     * {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)} in
     * {@code app/cpy/CVTRA04Y.cpy} (line 8) and to the V009
     * {@code tran_cat_type_desc VARCHAR(50) NOT NULL} column.</p>
     *
     * <p>The exact text is preserved verbatim from
     * {@code app/data/ASCII/trancatg.txt} by the V014 seed migration;
     * capitalization and meaningful internal whitespace MUST NOT be
     * normalized because regulatory output formats
     * ({@code TransactionReportService} output, statement generation)
     * depend on the exact text. Specifically:</p>
     * <ul>
     *   <li>Type {@code '01'} entries use Title Case (e.g.,
     *       {@code "Regular Sales Draft"}).</li>
     *   <li>Type {@code '02'} entries use lowercase {@code "payment"}
     *       (e.g., {@code "Cash payment"}, NOT {@code "Cash Payment"})
     *       &mdash; this is intentional and reflects the source fixture
     *       per AAP &sect;0.7.1 / &sect;0.7.3.</li>
     *   <li>Types {@code '03'}-{@code '07'} entries follow the source
     *       fixture's mixed-case spelling.</li>
     * </ul>
     *
     * <p>{@code NOT NULL} because the COBOL fixed-width record always
     * has 50 bytes of (potentially space-padded) content &mdash; never
     * {@code null}.</p>
     */
    // COBOL: CVTRA04Y.cpy:L8 TRAN-CAT-TYPE-DESC PIC X(50)
    // -- human-readable description (preserved verbatim from V014 seed data)
    @Column(name = "tran_cat_type_desc", nullable = false, length = 50)
    private String tranCatTypeDesc;

    // COBOL: CVTRA04Y.cpy:L9 FILLER PIC X(04) -- OMITTED
    // (4 trailing bytes that bring the COBOL record to its declared
    // 60-byte RECORDSIZE; positions 57-60 of the source fixture contain
    // the literal "0000", confirming padding rather than data.
    // PostgreSQL has no concept of fixed-width records, so the FILLER
    // has no relational equivalent.)

    // -------------------------------------------------------------------------
    // Constructors
    //
    // Three constructors are exposed:
    //   1) The JPA-required no-arg constructor (used by Hibernate when
    //      hydrating a row read from PostgreSQL into a managed entity).
    //   2) An all-args constructor accepting the @EmbeddedId and
    //      description (used by services that already have a constructed
    //      TransactionCategoryId).
    //   3) A convenience constructor accepting the two raw key fields
    //      plus the description (used by tests and any service code that
    //      constructs the entity from its primitive key fields without
    //      first materializing a TransactionCategoryId instance).
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
     * {@link #TransactionCategory(TransactionCategoryId, String)} or
     * {@link #TransactionCategory(String, Integer, String)} convenience
     * constructors instead.</p>
     */
    public TransactionCategory() {
        // Intentionally empty -- field initialization is performed by
        // Hibernate via reflective field/setter access during entity
        // hydration, or by the all-args constructor in application code.
    }

    /**
     * All-args constructor accepting a pre-built
     * {@link TransactionCategoryId}.
     *
     * <p>Used by service code that has already constructed the composite
     * key (e.g., when porting a COBOL {@code TRAN-CAT-KEY} from an
     * external source).</p>
     *
     * @param id              the 2-field composite primary key (must not
     *                        be {@code null}; corresponds to COBOL
     *                        {@code TRAN-CAT-KEY})
     * @param tranCatTypeDesc the human-readable description (max 50
     *                        characters; corresponds to COBOL
     *                        {@code TRAN-CAT-TYPE-DESC PIC X(50)})
     */
    public TransactionCategory(TransactionCategoryId id, String tranCatTypeDesc) {
        this.id = id;
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Convenience all-args constructor accepting the two raw composite-key
     * fields plus the description.
     *
     * <p>Equivalent to:
     * {@code new TransactionCategory(new TransactionCategoryId(typeCd, catCd), desc)}.
     * Used by tests and any service code that constructs the entity from
     * its primitive key fields without first materializing a
     * {@link TransactionCategoryId} instance.</p>
     *
     * @param tranTypeCd      the 2-character transaction-type code
     *                        (corresponds to COBOL
     *                        {@code TRAN-TYPE-CD PIC X(02)}; leading
     *                        zeros are significant; must not be
     *                        {@code null})
     * @param tranCatCd       the 4-digit transaction-category code
     *                        (corresponds to COBOL
     *                        {@code TRAN-CAT-CD PIC 9(04)}; range
     *                        1..9999; must not be {@code null})
     * @param tranCatTypeDesc the human-readable description (max 50
     *                        characters; corresponds to COBOL
     *                        {@code TRAN-CAT-TYPE-DESC PIC X(50)})
     */
    public TransactionCategory(String tranTypeCd,
                               Integer tranCatCd,
                               String tranCatTypeDesc) {
        this.id = new TransactionCategoryId(tranTypeCd, tranCatCd);
        this.tranCatTypeDesc = tranCatTypeDesc;
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
     * @return the 2-field composite primary key
     *         ({@link TransactionCategoryId})
     */
    public TransactionCategoryId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * <p>Although this setter exists to satisfy the JavaBean contract
     * required by JPA, application code SHOULD NOT mutate the primary
     * key of a persisted entity. This table is treated as immutable
     * reference data outside the V014 seed migration.</p>
     *
     * @param id the composite primary key to set (must not be
     *           {@code null} for a persisted entity)
     */
    public void setId(TransactionCategoryId id) {
        this.id = id;
    }

    /**
     * @return the human-readable description of the transaction category
     *         (max 50 characters, never {@code null})
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * Sets the human-readable description.
     *
     * <p>Application code SHOULD NOT mutate descriptions at runtime;
     * the V014 seed migration is the authoritative source. The setter
     * exists for the entity-lifecycle plumbing that JPA requires.</p>
     *
     * @param tranCatTypeDesc the description to set (max 50 characters)
     */
    public void setTranCatTypeDesc(String tranCatTypeDesc) {
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    //
    // equals/hashCode follow the JPA recommended contract for entities
    // with an @EmbeddedId composite primary key: based on the EmbeddedId
    // field only. The TransactionCategoryId nested class itself provides
    // value-based equals/hashCode over its two sub-fields, so equality
    // semantics are consistent across both layers.
    //
    // toString is non-sensitive: this table contains only public
    // reference data (codes and descriptions), so the full content is
    // safe to log per AAP §0.6.6 (PCI-DSS).
    // -------------------------------------------------------------------------

    /**
     * Equality is defined on the {@link EmbeddedId} {@link #id} only,
     * matching the standard JPA entity contract for composite-key
     * entities. Two {@code TransactionCategory} instances are equal iff
     * they have equal {@link TransactionCategoryId} values (both
     * {@code null} is treated as equal &mdash; common during
     * transient-state comparisons within a test fixture but should not
     * occur in persisted state).
     *
     * @param o the reference object with which to compare
     * @return {@code true} if this object is the same as the {@code o}
     *         argument; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategory)) {
            return false;
        }
        TransactionCategory that = (TransactionCategory) o;
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
     * <p>This entity contains only public reference data &mdash; the
     * 2-field composite-key codes and the category description. There
     * is no PII, PAN, or credential material in this table, so the
     * full content is safe to log per AAP &sect;0.6.6 (PCI-DSS).</p>
     *
     * @return a string representation of this entity
     */
    @Override
    public String toString() {
        return "TransactionCategory{"
                + "id=" + id
                + ", tranCatTypeDesc='" + tranCatTypeDesc + '\''
                + '}';
    }

    // =========================================================================
    // Composite primary-key class
    // =========================================================================

    /**
     * JPA {@link Embeddable} composite-primary-key class for the
     * {@link TransactionCategory} entity. Encapsulates the two
     * sub-fields of the 6-byte VSAM composite key
     * {@code TRAN-CAT-KEY} from {@code app/cpy/CVTRA04Y.cpy} lines
     * 5-7, which decompose as:
     * <pre>
     *     TRAN-TYPE-CD  PIC X(02)   -> tranTypeCd  (String, CHAR(2))
     *     TRAN-CAT-CD   PIC 9(04)   -> tranCatCd   (Integer, NUMERIC(4))
     * </pre>
     *
     * <p>The field declaration order matches the COBOL byte order, which
     * preserves the implicit lexicographic ordering of the VSAM KSDS for
     * any future range-scan queries within a single transaction-type
     * code. None of the COBOL programs in the CardDemo source rely on
     * this ordering (lookups are always exact-match by composite key),
     * but the order is preserved for traceability and to keep the
     * PostgreSQL B-tree index's leading-column accessible for queries
     * like {@code WHERE tran_type_cd = '01'}.</p>
     *
     * <p>This class is required to be {@link Serializable} by the
     * Jakarta Persistence specification &sect;2.4 (composite-key
     * classes), which is critical for second-level cache support and
     * for safe passing across persistence-context boundaries.</p>
     *
     * <p>The {@link #equals(Object)} and {@link #hashCode()} methods
     * compare both sub-fields, providing value-based identity semantics
     * required by JPA for composite-key classes and by
     * {@link java.util.HashMap}/{@link java.util.HashSet} consumers.</p>
     *
     * @see TransactionCategory
     */
    @Embeddable
    public static class TransactionCategoryId implements Serializable {

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
        // The two sub-fields below correspond one-to-one (in order) to
        // the COBOL TRAN-CAT-KEY sub-fields in app/cpy/CVTRA04Y.cpy
        // (lines 5-7). Their byte widths sum to exactly 6 bytes
        // (matching the IDCAMS KEYS(6, 0) clause in TRANCATG.jcl and
        // LISTCAT.txt KEYLEN=6, RKP=0):
        //     2 (type_cd) + 4 (cat_cd) = 6 bytes
        // ---------------------------------------------------------------------

        /**
         * 2-character transaction-type code &mdash; the first sub-field
         * of the composite key (bytes 1-2 of the 6-byte VSAM key).
         *
         * <p>Maps to the COBOL field
         * {@code 10 TRAN-TYPE-CD PIC X(02)} in
         * {@code app/cpy/CVTRA04Y.cpy} (line 6) and to the V009
         * {@code tran_type_cd CHAR(2) NOT NULL} column.</p>
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
         * fixed-width semantics of V009 {@code tran_category.tran_type_cd}
         * and the FK target type {@code tran_type.tran_type CHAR(2)}
         * (V008) exactly &mdash; the matching type is required by
         * PostgreSQL FK semantics, which compare column values using
         * the underlying type.</p>
         *
         * <p>Database-level foreign key:
         * {@code fk_tran_category_tran_type FOREIGN KEY (tran_type_cd)
         * REFERENCES tran_type(tran_type) ON DELETE NO ACTION} is
         * declared in V009 per AAP &sect;0.6.2 for defensive
         * referential integrity. The JPA mapping intentionally does
         * NOT mirror this as a {@code @ManyToOne} association per the
         * Minimal Change Clause (AAP &sect;0.7.3) &mdash; the COBOL
         * source treats this as a scalar field, and the Java target
         * preserves that shape.</p>
         */
        // COBOL: CVTRA04Y.cpy:L6 TRAN-TYPE-CD PIC X(02)
        // -- 2-char transaction-type code; CHAR(2) for fixed-width
        // matching with V008 tran_type.tran_type (FK target).
        //
        // @JdbcTypeCode(SqlTypes.CHAR) is REQUIRED in addition to
        // columnDefinition = "CHAR(2)" so that Hibernate's
        // ddl-auto: validate consults Types.CHAR (matching PostgreSQL's
        // 'bpchar' reported type) rather than the default Types.VARCHAR
        // that Hibernate otherwise infers for a String field. Without
        // this annotation, schema validation fails with
        // "found [bpchar (Types#CHAR)], but expecting [char(2) (Types#VARCHAR)]".
        // This matches the established project convention used by all
        // sibling entities (TransactionType, DisclosureGroup, UserSecurity).
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "tran_type_cd", nullable = false, length = 2,
                columnDefinition = "CHAR(2)")
        private String tranTypeCd;

        /**
         * 4-digit numeric transaction-category code &mdash; the second
         * sub-field of the composite key (bytes 3-6 of the 6-byte VSAM
         * key, the final 4 bytes of the composite).
         *
         * <p>Maps to the COBOL field
         * {@code 10 TRAN-CAT-CD PIC 9(04)} in
         * {@code app/cpy/CVTRA04Y.cpy} (line 7) and to the V009
         * {@code tran_cat_cd NUMERIC(4) NOT NULL} column.</p>
         *
         * <p>4-digit unsigned numeric category code (range 1..9999 per
         * COBOL {@code PIC 9(04)} semantics; the source fixture uses
         * {@code 0001..0005} only). Stored as {@link Integer} mapped to
         * PostgreSQL {@code NUMERIC(4)} &mdash; NOT as a zero-padded
         * string. The COBOL {@code PIC 9(04)} value {@code "0001"}
         * becomes the {@code Integer} {@code 1} in PostgreSQL (e.g.,
         * {@code (tran_type_cd, tran_cat_cd) = ('01', 1)} corresponds to
         * the COBOL VSAM key bytes {@code '010001'}).</p>
         *
         * <p>Application-layer formatting in
         * {@code TransactionReportService} re-pads to 4 digits when
         * emitting regulatory-format output, preserving byte-for-byte
         * parity with the COBOL source.</p>
         *
         * <p>{@code NUMERIC(4)} with {@code precision = 4} and implicit
         * {@code scale = 0} stores values exactly in PostgreSQL (no
         * float approximation). The Java field type is {@link Integer}
         * to accommodate any positive value up to {@code 9999} while
         * still rejecting {@code null} via {@code nullable = false}.</p>
         *
         * <p>{@code columnDefinition = "NUMERIC(4)"} is set explicitly so
         * Hibernate's {@code ddl-auto: validate} compares against the
         * PostgreSQL {@code numeric(4)} type declared in V009 line 165
         * ({@code Types.NUMERIC}) rather than the default
         * {@code Types.INTEGER} that Hibernate would otherwise infer
         * from a {@link Integer} Java field. The V009 migration chose
         * {@code NUMERIC(4)} to mirror the COBOL {@code PIC 9(04)}
         * decimal-display semantics &mdash; faithful to the COBOL
         * source's natural numeric encoding &mdash; while V007 chose
         * the PostgreSQL native {@code INTEGER} type for the analogous
         * {@code dis_tran_cat_cd} column. Both encodings store the same
         * integer value space {@code 1..9999} exactly; the
         * {@code columnDefinition} attribute bridges the entity's Java
         * {@link Integer} type to V009's chosen {@code NUMERIC(4)}
         * column type for schema validation only and has no runtime
         * arithmetic effect.</p>
         */
        // COBOL: CVTRA04Y.cpy:L7 TRAN-CAT-CD PIC 9(04)
        // -- 4-digit numeric category code. Mapped as plain INTEGER per
        // AAP §0.6.1 so the Hibernate JDBC type aligns with V009's
        // INTEGER column (V009:L165) -- both sides report Types.INTEGER
        // after schema validation. This is the SAME decision used by
        // V005, V006, V007, V011 category-code columns; switching to
        // INTEGER eliminates the implicit BigDecimal <-> Integer cast
        // that the previous NUMERIC(4) columnDefinition forced.
        @Column(name = "tran_cat_cd", nullable = false)
        private Integer tranCatCd;

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
        public TransactionCategoryId() {
            // Intentionally empty -- field initialization is performed
            // by Hibernate via reflective field/setter access during
            // entity hydration, or by the all-args constructor in
            // application code.
        }

        /**
         * All-args constructor for convenient programmatic construction.
         *
         * <p>Used in service code and tests to build a
         * {@code TransactionCategoryId} instance from its two
         * composite-key sub-fields.</p>
         *
         * @param tranTypeCd the 2-character transaction-type code
         *                   (corresponds to COBOL
         *                   {@code TRAN-TYPE-CD PIC X(02)}; leading
         *                   zeros are significant; must not be
         *                   {@code null})
         * @param tranCatCd  the 4-digit transaction-category code
         *                   (corresponds to COBOL
         *                   {@code TRAN-CAT-CD PIC 9(04)}; range
         *                   1..9999; must not be {@code null})
         */
        public TransactionCategoryId(String tranTypeCd, Integer tranCatCd) {
            this.tranTypeCd = tranTypeCd;
            this.tranCatCd = tranCatCd;
        }

        // ---------------------------------------------------------------------
        // Accessors (getters and setters)
        //
        // Plain JavaBean accessors, one per composite-key sub-field.
        // Required by Hibernate's property access mode for @EmbeddedId
        // hydration.
        // ---------------------------------------------------------------------

        /**
         * @return the 2-character transaction-type code (first
         *         sub-field of the composite key)
         */
        public String getTranTypeCd() {
            return tranTypeCd;
        }

        /**
         * Sets the transaction-type code sub-field of the composite
         * primary key.
         *
         * <p>Although this setter exists to satisfy the JavaBean
         * contract required by JPA, application code SHOULD NOT mutate
         * the sub-fields of a persisted composite primary key.</p>
         *
         * @param tranTypeCd the 2-character transaction-type code to
         *                   set (corresponds to COBOL
         *                   {@code TRAN-TYPE-CD PIC X(02)}; leading
         *                   zeros are significant)
         */
        public void setTranTypeCd(String tranTypeCd) {
            this.tranTypeCd = tranTypeCd;
        }

        /**
         * @return the 4-digit transaction-category code (second
         *         sub-field of the composite key)
         */
        public Integer getTranCatCd() {
            return tranCatCd;
        }

        /**
         * Sets the transaction-category code sub-field of the composite
         * primary key.
         *
         * <p>Although this setter exists to satisfy the JavaBean
         * contract required by JPA, application code SHOULD NOT mutate
         * the sub-fields of a persisted composite primary key.</p>
         *
         * @param tranCatCd the 4-digit transaction-category code to
         *                  set (corresponds to COBOL
         *                  {@code TRAN-CAT-CD PIC 9(04)})
         */
        public void setTranCatCd(Integer tranCatCd) {
            this.tranCatCd = tranCatCd;
        }

        // ---------------------------------------------------------------------
        // equals / hashCode / toString
        //
        // For composite-key classes, equals/hashCode MUST be based on
        // all key sub-fields. This is required by the Jakarta
        // Persistence specification §2.4 so that JPA can use the
        // composite-key value as a HashMap key in its persistence-
        // context tracking.
        //
        // toString is non-sensitive: composite-key sub-fields are
        // public reference codes, safe to log per AAP §0.6.6 (PCI-DSS).
        // ---------------------------------------------------------------------

        /**
         * Equality is defined by value: two
         * {@code TransactionCategoryId} instances are equal iff both
         * sub-fields ({@link #tranTypeCd}, {@link #tranCatCd}) are
         * pair-wise equal (with {@code null} tolerance via
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
            if (!(o instanceof TransactionCategoryId)) {
                return false;
            }
            TransactionCategoryId that = (TransactionCategoryId) o;
            return Objects.equals(tranTypeCd, that.tranTypeCd)
                    && Objects.equals(tranCatCd, that.tranCatCd);
        }

        /**
         * Hash code derived from both composite-key sub-fields,
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
            return Objects.hash(tranTypeCd, tranCatCd);
        }

        /**
         * String representation suitable for log statements and debug
         * output. Both composite-key sub-fields are public reference
         * codes (transaction-type code, transaction-category code) and
         * contain no PII or PAN, so the full content is safe to log
         * per AAP &sect;0.6.6 (PCI-DSS).
         *
         * @return a string representation of this composite-key
         *         instance
         */
        @Override
        public String toString() {
            return "TransactionCategoryId{"
                    + "tranTypeCd='" + tranTypeCd + '\''
                    + ", tranCatCd=" + tranCatCd
                    + '}';
        }
    }
}
