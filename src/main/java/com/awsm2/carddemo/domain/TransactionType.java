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
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code tran_type} lookup table
 * (Flyway migration {@code V008__create_transaction_type.sql}). This entity
 * is the Java target for the COBOL {@code TRAN-TYPE-RECORD} layout defined in
 * {@code app/cpy/CVTRA03Y.cpy} (RECLN = 60 bytes), and replaces the
 * mainframe VSAM KSDS cluster {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS}.
 *
 * <h2>Purpose</h2>
 * <p>Represents a single canonical transaction-type lookup row. The table
 * is a small, read-mostly reference table that maps a 2-character
 * transaction-type code (e.g., {@code "01"}, {@code "02"}, &hellip;)
 * to its human-readable description (e.g., {@code "Purchase"},
 * {@code "Payment"}). Seven rows total are seeded by
 * {@code V013__seed_transaction_type.sql} from
 * {@code app/data/ASCII/trantype.txt}.
 *
 * <h2>Canonical reference values (seeded by V013)</h2>
 * <ul>
 *   <li>{@code "01"} &mdash; Purchase</li>
 *   <li>{@code "02"} &mdash; Payment</li>
 *   <li>{@code "03"} &mdash; Credit</li>
 *   <li>{@code "04"} &mdash; Authorization</li>
 *   <li>{@code "05"} &mdash; Refund</li>
 *   <li>{@code "06"} &mdash; Reversal</li>
 *   <li>{@code "07"} &mdash; Adjustment</li>
 * </ul>
 *
 * <h2>Consumers (Java services that read this table)</h2>
 * <ul>
 *   <li>{@code TransactionPostingService} (COBOL {@code CBTRN02C}) &mdash;
 *       validates the incoming {@code TRAN-TYPE-CD} against this lookup
 *       during the 4-stage validation cascade.</li>
 *   <li>{@code TransactionReportService} (COBOL {@code CBTRN03C}) &mdash;
 *       joins to produce human-readable transaction-type labels on the
 *       generated report.</li>
 *   <li>{@code TransactionAddService} (COBOL {@code COTRN02C}) &mdash;
 *       validates user-supplied {@code TRAN-TYPE-CD} before INSERT into
 *       {@code transactions}.</li>
 *   <li>{@code InterestCalculationService} (COBOL {@code CBACT04C})
 *       &mdash; composite-key lookups against {@code disclosure_group}
 *       include {@code TRAN-TYPE-CD}.</li>
 * </ul>
 *
 * <h2>Logical child tables (FK references at the application layer)</h2>
 * <p>The following child tables reference this lookup via scalar FK
 * columns (named {@code tran_type_cd} or {@code trancat_type_cd}). The
 * database-level FK constraints are intentionally NOT declared here
 * because Flyway lexicographic ordering interleaves consumer migrations
 * (V005 {@code transactions}, V006 {@code tran_cat_bal}, V009
 * {@code tran_category}) around or before this table's V008 migration.
 * Application-layer validation enforces existence consistently with
 * the COBOL business rules of {@code CBTRN02C} / {@code COTRN02C}.</p>
 * <ul>
 *   <li>{@code transactions.tran_type_cd}
 *       (V005; COBOL {@code TRAN-TYPE-CD} in {@code CVTRA05Y.cpy}).</li>
 *   <li>{@code tran_category} composite PK
 *       {@code (tran_type_cd, tran_cat_cd)} (V009; COBOL
 *       {@code TRAN-TYPE-CD} in {@code CVTRA04Y.cpy}).</li>
 *   <li>{@code tran_cat_bal.trancat_type_cd}
 *       (V006; COBOL {@code TRAN-CAT-BAL-TYPE-CD} in
 *       {@code CVTRA01Y.cpy}).</li>
 *   <li>{@code disclosure_group.dis_tran_type_cd}
 *       (V007; COBOL {@code DIS-TRAN-TYPE-CD} in
 *       {@code CVTRA02Y.cpy}).</li>
 * </ul>
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook:</b> {@code app/cpy/CVTRA03Y.cpy} &mdash;
 *       60-byte fixed-width record layout with 2 business fields plus
 *       an 8-byte trailing {@code FILLER PIC X(08)}. The FILLER has
 *       no relational equivalent and is omitted from this entity.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS} &mdash;
 *       {@code KEYS(2,0)}, {@code RECORDSIZE(60,60)},
 *       {@code SHAREOPTIONS(1,4)}, {@code INDEXED},
 *       {@code REC-TOTAL=7} (per {@code app/jcl/TRANTYPE.jcl}:L36-L49
 *       and {@code app/catlg/LISTCAT.txt}).</li>
 *   <li><b>JCL allocation/load:</b>
 *       {@code app/jcl/TRANTYPE.jcl} (STEP10 IDCAMS DEFINE CLUSTER,
 *       STEP15 IDCAMS REPRO from {@code TRANTYPE.PS}).</li>
 *   <li><b>Golden fixture:</b> {@code app/data/ASCII/trantype.txt}
 *       (7 fixed-width 60-byte records; positions 53-60 contain the
 *       8-byte literal {@code "00000000"} representing the COBOL
 *       FILLER &mdash; verified to be padding, not data).</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V008__create_transaction_type.sql}.</li>
 *   <li><b>Seed migration:</b>
 *       {@code src/main/resources/db/migration/V013__seed_transaction_type.sql}.</li>
 * </ul>
 *
 * <h2>Schema invariants</h2>
 * <ul>
 *   <li><b>Simple primary key</b> &mdash; a single {@code CHAR(2)}
 *       column ({@link #tranType}), not a composite key. Hibernate's
 *       {@code spring.jpa.hibernate.ddl-auto: validate} verifies this
 *       at startup against the V008 DDL.</li>
 *   <li><b>No optimistic locking</b> &mdash; this entity has no
 *       {@code @Version} column. The lookup data is static reference
 *       data seeded once by V013 and not updated at runtime; there
 *       is no read-modify-write contention to guard against.</li>
 *   <li><b>No association mappings</b> &mdash; child tables reference
 *       this lookup via scalar FK columns rather than JPA
 *       {@code @OneToMany} / {@code @ManyToOne} associations. This is
 *       a deliberate Minimal Change Clause decision (AAP &sect;0.7.3):
 *       associations are not required by any consumer service, and
 *       avoiding them keeps the JPA mapping byte-faithful to the
 *       COBOL field set without introducing new traversal patterns.</li>
 *   <li><b>FILLER omitted</b> &mdash; the trailing 8-byte
 *       {@code FILLER PIC X(08)} from the COBOL record has no
 *       relational counterpart and is not declared as a Java field.</li>
 * </ul>
 *
 * <h2>JPA mapping discipline</h2>
 * <ul>
 *   <li>{@code @Table(name = "tran_type")} matches V008 DDL table name
 *       <em>exactly</em> (singular, snake_case).</li>
 *   <li>{@code @Column(name = "tran_type")} on the primary-key field
 *       matches V008 column name; {@code columnDefinition = "CHAR(2)"}
 *       preserves the fixed-width semantics declared in V008.</li>
 *   <li>{@code @Column(name = "tran_type_desc", length = 50, nullable = false)}
 *       on the description field matches V008's
 *       {@code VARCHAR(50) NOT NULL} declaration.</li>
 *   <li>All {@code @Column} {@code nullable} and {@code length}
 *       attributes correspond 1-to-1 with the V008 DDL.</li>
 * </ul>
 *
 * <h2>Data quality and immutability</h2>
 * <p>Although this entity exposes setters for both fields (per the
 * JavaBean contract required by Spring Data JPA derived queries and
 * Jackson serialization), <b>in practice this table is treated as
 * immutable reference data</b>. The only legitimate write path is the
 * one-time V013 seed migration; runtime services SHOULD only read
 * from this table. The setters exist for the entity-lifecycle plumbing
 * that JPA requires &mdash; not for application-level mutation.</p>
 *
 * <p>The COBOL field name is {@code TRAN-TYPE} (not {@code TRAN-TYPE-CD});
 * the Java field is therefore named {@link #tranType} (not
 * {@code tranTypeCd}). Child tables that reference this lookup use the
 * {@code TRAN-TYPE-CD} / {@code tran_type_cd} naming convention because
 * the {@code -CD} suffix conventionally indicates a foreign-key code
 * column. This naming is preserved verbatim from the COBOL source per
 * AAP &sect;0.7.3 (refactor discipline).</p>
 *
 * @see com.awsm2.carddemo.domain.Transaction
 * @see com.awsm2.carddemo.domain.TransactionCategory
 * @see com.awsm2.carddemo.domain.TransactionCategoryBalance
 * @see com.awsm2.carddemo.domain.DisclosureGroup
 */
@Entity
@Table(name = "tran_type")
public class TransactionType implements Serializable {

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
    // TRAN-TYPE-RECORD layout in app/cpy/CVTRA03Y.cpy. The single trailing
    // FILLER PIC X(08) is intentionally omitted (no relational equivalent
    // for fixed-width padding).
    // -------------------------------------------------------------------------

    /**
     * 2-character transaction-type code &mdash; the primary key.
     *
     * <p>Maps to the COBOL field
     * {@code 05 TRAN-TYPE PIC X(02)} in
     * {@code app/cpy/CVTRA03Y.cpy} (line 5) and to the V008
     * {@code tran_type CHAR(2) PRIMARY KEY} column.</p>
     *
     * <p>The value is always exactly two characters; leading zeros are
     * SIGNIFICANT and MUST be preserved (e.g., {@code "01"} for
     * Purchase &mdash; never the integer {@code 1}). PostgreSQL
     * {@code CHAR(2)} space-pads on read, but since every value is
     * exactly two characters there is no padding to trim.</p>
     */
    // COBOL: CVTRA03Y.cpy:L5 TRAN-TYPE PIC X(02) -- primary key
    // (CHAR(2) for fixed-width matching with the COBOL/PostgreSQL column)
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type", nullable = false, length = 2, columnDefinition = "CHAR(2)")
    private String tranType;

    /**
     * Human-readable description of the transaction type (e.g.,
     * {@code "Purchase"}, {@code "Payment"}).
     *
     * <p>Maps to the COBOL field
     * {@code 05 TRAN-TYPE-DESC PIC X(50)} in
     * {@code app/cpy/CVTRA03Y.cpy} (line 6) and to the V008
     * {@code tran_type_desc VARCHAR(50) NOT NULL} column.</p>
     *
     * <p>The exact text is preserved verbatim from
     * {@code app/data/ASCII/trantype.txt} by the V013 seed migration;
     * capitalization and meaningful internal whitespace MUST NOT be
     * normalized because regulatory output formats
     * ({@code TransactionReportService} output, statement generation)
     * depend on the exact text.</p>
     */
    // COBOL: CVTRA03Y.cpy:L6 TRAN-TYPE-DESC PIC X(50)
    // -- human-readable description
    @Column(name = "tran_type_desc", nullable = false, length = 50)
    private String tranTypeDesc;

    // COBOL: CVTRA03Y.cpy:L7 FILLER PIC X(08) -- OMITTED
    // (8 trailing bytes that bring the COBOL record to its declared
    // 60-byte RECORDSIZE; positions 53-60 of the source fixture contain
    // the literal "00000000", confirming padding rather than data.
    // PostgreSQL has no concept of fixed-width records, so the FILLER
    // has no relational equivalent.)

    // -------------------------------------------------------------------------
    // Constructors
    //
    // Two constructors are exposed:
    //   1) The JPA-required no-arg constructor (used by Hibernate when
    //      hydrating a row read from PostgreSQL into a managed entity).
    //   2) An all-args constructor for convenient programmatic
    //      construction in service code and tests.
    // -------------------------------------------------------------------------

    /**
     * No-arg constructor required by the JPA specification.
     *
     * <p>Hibernate invokes this constructor reflectively when hydrating
     * a row read from PostgreSQL into a managed entity instance. The
     * persistent fields are subsequently set via the JavaBean setters
     * declared below.</p>
     *
     * <p>Application code SHOULD use the {@link #TransactionType(String, String)}
     * all-args constructor instead.</p>
     */
    public TransactionType() {
        // Intentionally empty -- field initialization is performed by
        // Hibernate via reflective field/setter access during entity
        // hydration, or by the all-args constructor in application code.
    }

    /**
     * All-args constructor for convenient programmatic construction.
     *
     * <p>Used in service code and tests to build a {@code TransactionType}
     * instance from its two persistent fields. No validation is performed
     * here &mdash; the database CHECK / length constraints in V008 are
     * authoritative.</p>
     *
     * @param tranType     the 2-character transaction-type code (primary
     *                     key; e.g., {@code "01"} for Purchase). Must be
     *                     exactly two characters with leading zeros
     *                     preserved.
     * @param tranTypeDesc the human-readable description (max 50
     *                     characters; e.g., {@code "Purchase"})
     */
    public TransactionType(String tranType, String tranTypeDesc) {
        this.tranType = tranType;
        this.tranTypeDesc = tranTypeDesc;
    }

    // -------------------------------------------------------------------------
    // Accessors (getters and setters)
    //
    // Plain JavaBean accessors, one per field. Required by Hibernate's
    // property access mode and consumed by Spring Data JPA derived queries,
    // Jackson serialization at the DTO boundary, and the JPA validation
    // framework.
    // -------------------------------------------------------------------------

    /**
     * @return the 2-character transaction-type code (primary key)
     */
    public String getTranType() {
        return tranType;
    }

    /**
     * Sets the primary-key transaction-type code.
     *
     * <p>Although this setter exists to satisfy the JavaBean contract
     * required by JPA, application code SHOULD NOT mutate the primary
     * key of a persisted entity. This table is treated as immutable
     * reference data outside the V013 seed migration.</p>
     *
     * @param tranType the 2-character transaction-type code to set
     *                 (must be exactly two characters with leading
     *                 zeros preserved)
     */
    public void setTranType(String tranType) {
        this.tranType = tranType;
    }

    /**
     * @return the human-readable description of the transaction type
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Sets the human-readable description.
     *
     * <p>Application code SHOULD NOT mutate descriptions at runtime;
     * the V013 seed migration is the authoritative source.</p>
     *
     * @param tranTypeDesc the description to set (max 50 characters)
     */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    //
    // equals/hashCode follow the JPA recommended contract: based on the
    // primary-key field only. This contract is STABLE across the entity
    // lifecycle states (transient, managed, detached, removed).
    //
    // toString is non-sensitive: this table contains only public reference
    // data (codes and descriptions), so the full content is safe to log.
    // -------------------------------------------------------------------------

    /**
     * Equality is defined on the primary key ({@link #tranType}) only,
     * matching the standard JPA entity contract. Two
     * {@code TransactionType} instances are equal iff they have the
     * same {@code tranType} value (both {@code null} is treated as
     * equal &mdash; common during transient-state comparisons within
     * a test fixture but should not occur in persisted state).
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
        if (!(o instanceof TransactionType)) {
            return false;
        }
        TransactionType that = (TransactionType) o;
        return Objects.equals(tranType, that.tranType);
    }

    /**
     * Hash code derived from {@link #tranType} only, consistent with
     * the {@link #equals(Object)} contract above. Safe for use as a
     * hash-set or hash-map key once {@code tranType} has been
     * assigned (which is required before
     * {@code EntityManager.persist} by virtue of the
     * {@code nullable = false} primary-key constraint).
     *
     * @return the hash-code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranType);
    }

    /**
     * String representation suitable for log statements and debug
     * output.
     *
     * <p>This entity contains only public reference data &mdash; the
     * 2-character transaction-type code and its human-readable
     * description. There is no PII, PAN, or credential material in
     * this table, so the full content is safe to log per AAP
     * &sect;0.6.6 (PCI-DSS).</p>
     *
     * @return a string representation of this entity
     */
    @Override
    public String toString() {
        return "TransactionType{"
                + "tranType='" + tranType + '\''
                + ", tranTypeDesc='" + tranTypeDesc + '\''
                + '}';
    }
}
