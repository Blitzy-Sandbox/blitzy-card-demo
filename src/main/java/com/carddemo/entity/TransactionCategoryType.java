package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * JPA entity representing a transaction-category-type reference/lookup record.
 *
 * <p>Migrated field-by-field from the COBOL copybook {@code app/cpy/CVTRA04Y.cpy}
 * ({@code TRAN-CAT-RECORD}, record length 60 bytes) at source commit SHA
 * {@code 27d6c6f} (CardDemo v1.0-15-g27d6c6f-68). This is a static reference
 * table that supplies a human-readable description for each combination of
 * transaction type and transaction category; it is seeded from the
 * {@code trancatg} ASCII fixture and mapped to the
 * {@code transaction_category_type} database table.</p>
 *
 * <p>Original COBOL record layout, preserved verbatim:</p>
 * <pre>
 * 01  TRAN-CAT-RECORD.                          (RECLN = 60)
 *     05  TRAN-CAT-KEY.
 *         10  TRAN-TYPE-CD       PIC X(02).      -&gt; id.tranTypeCd (key part 1)
 *         10  TRAN-CAT-CD        PIC 9(04).      -&gt; id.tranCatCd  (key part 2)
 *     05  TRAN-CAT-TYPE-DESC     PIC X(50).      -&gt; tranCatTypeDesc
 *     05  FILLER                 PIC X(04).      -&gt; not mapped (trailing padding)
 * </pre>
 *
 * <p>The primary key is the two-part composite {@code TRAN-CAT-KEY}
 * ({@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}), modelled as the
 * {@link EmbeddedId} value object {@link TransactionCategoryTypeId} rather than
 * as individual columns on this class. There is no surrogate or
 * database-generated identifier; the composite is a natural (business) key whose
 * values originate from the seeded reference data. The trailing 4-byte
 * {@code FILLER} carries no business data and is intentionally not mapped. Byte
 * accounting: {@code (2 + 4) + 50 + 4 = 60}.</p>
 *
 * <p>This is the two-part composite-key transaction-category-type lookup and
 * must not be confused with {@code TransactionType} (copybook {@code CVTRA03Y}),
 * which is a single-key ({@code TRAN-TYPE}) lookup.</p>
 *
 * <p><strong>Database contract</strong> (must match the {@code V1__schema.sql}
 * Flyway migration exactly, since Hibernate runs with {@code ddl-auto: validate}):</p>
 * <pre>
 * CREATE TABLE transaction_category_type (
 *     tran_type_cd       VARCHAR(2)  NOT NULL,
 *     tran_cat_cd        INTEGER     NOT NULL,
 *     tran_cat_type_desc VARCHAR(50),
 *     PRIMARY KEY (tran_type_cd, tran_cat_cd)
 * );
 * </pre>
 * The primary-key columns ({@code tran_type_cd}, {@code tran_cat_cd}) are declared
 * on {@link TransactionCategoryTypeId}; only the non-key description column is
 * declared here. This entity carries no {@code @Version} field (reference data is
 * not subject to the optimistic-locking concurrency semantics of the updatable
 * account/card aggregates) and, per scope preservation (Gate 7), introduces no
 * relationships or business behavior.
 */
@Entity
@Table(name = "transaction_category_type")
public class TransactionCategoryType {

    /**
     * Composite primary key (COBOL {@code TRAN-CAT-KEY}), comprising the
     * transaction type code ({@code TRAN-TYPE-CD PIC X(02)}) and the transaction
     * category code ({@code TRAN-CAT-CD PIC 9(04)}).
     *
     * <p>Mapped as an {@link EmbeddedId}; the underlying {@code tran_type_cd} and
     * {@code tran_cat_cd} primary-key columns are defined on
     * {@link TransactionCategoryTypeId} and therefore are not redeclared here.</p>
     */
    @EmbeddedId
    private TransactionCategoryTypeId id;

    /**
     * Human-readable transaction-category-type description
     * (COBOL {@code TRAN-CAT-TYPE-DESC PIC X(50)}).
     *
     * <p>Alphanumeric, fixed width 50 characters; persisted as {@code VARCHAR(50)}
     * in the non-key column {@code tran_cat_type_desc}.</p>
     */
    @Column(name = "tran_cat_type_desc", length = 50)
    private String tranCatTypeDesc;

    /**
     * Default no-argument constructor required by the JPA specification for
     * entity instantiation via reflection.
     */
    public TransactionCategoryType() {
        // Intentionally empty: JPA requires a public/protected no-arg constructor.
    }

    /**
     * Returns the composite primary key ({@code TRAN-CAT-KEY}).
     *
     * @return the composite identifier, or {@code null} if unset
     */
    public TransactionCategoryTypeId getId() {
        return id;
    }

    /**
     * Sets the composite primary key ({@code TRAN-CAT-KEY}).
     *
     * @param id the composite identifier to set
     */
    public void setId(TransactionCategoryTypeId id) {
        this.id = id;
    }

    /**
     * Returns the transaction-category-type description
     * ({@code TRAN-CAT-TYPE-DESC}).
     *
     * @return the category-type description
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * Sets the transaction-category-type description
     * ({@code TRAN-CAT-TYPE-DESC}).
     *
     * @param tranCatTypeDesc the category-type description to set
     */
    public void setTranCatTypeDesc(String tranCatTypeDesc) {
        this.tranCatTypeDesc = tranCatTypeDesc;
    }
}
