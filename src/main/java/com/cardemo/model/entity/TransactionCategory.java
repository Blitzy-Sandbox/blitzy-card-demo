package com.cardemo.model.entity;

import com.cardemo.model.key.TransactionCategoryId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity mapping the COBOL {@code TRAN-CAT-RECORD} (60 bytes) from
 * {@code app/cpy/CVTRA04Y.cpy} to the PostgreSQL {@code tran_cat} table.
 *
 * <p>This is a read-only reference entity that associates a composite key of
 * ({@code type_cd}, {@code cat_cd}) with a human-readable category description.
 * It serves as the classification taxonomy for transactions within each
 * transaction type, used for enriching transaction displays and report
 * generation across both online and batch processing paths.</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <pre>
 * Source: app/cpy/CVTRA04Y.cpy (commit 27d6c6f)
 * Record Length: 60 bytes
 * VSAM Dataset: TRANCATG (KSDS, keyed on TRAN-CAT-KEY)
 *
 *   01  TRAN-CAT-RECORD.
 *       05  TRAN-CAT-KEY.
 *           10  TRAN-TYPE-CD       PIC X(02).
 *           10  TRAN-CAT-CD        PIC 9(04).
 *       05  TRAN-CAT-TYPE-DESC    PIC X(50).
 *       05  FILLER                PIC X(04).
 * </pre>
 *
 * <h3>Field Mapping</h3>
 * <ul>
 *   <li>{@code TRAN-TYPE-CD PIC X(02)} → {@link TransactionCategoryId#getTypeCode()} (CHAR(2))</li>
 *   <li>{@code TRAN-CAT-CD PIC 9(04)} → {@link TransactionCategoryId#getCatCode()} (SMALLINT)</li>
 *   <li>{@code TRAN-CAT-TYPE-DESC PIC X(50)} → {@link #getTranCatTypeDesc()} (VARCHAR(50))</li>
 *   <li>{@code FILLER PIC X(04)} → not mapped (padding only)</li>
 * </ul>
 *
 * <h3>Key Usage Contexts</h3>
 * <ul>
 *   <li>Loaded via Flyway migration V3 from {@code trancatg.txt} ASCII fixture data</li>
 *   <li>Referenced by batch transaction posting (CBTRN02C.cbl) for validation</li>
 *   <li>Referenced by transaction category balance maintenance</li>
 *   <li>Example: type_cd='01', cat_cd=1 → "Regular Sales Draft"</li>
 * </ul>
 *
 * <p>No {@code @Version} annotation is required as this is read-only reference data
 * that does not participate in optimistic concurrency control.</p>
 *
 * @see TransactionCategoryId
 */
@Entity
@Table(name = "transaction_categories")
public class TransactionCategory {

    /**
     * Composite primary key consisting of transaction type code and category code.
     *
     * <p>Maps the COBOL group-level key {@code TRAN-CAT-KEY} which combines
     * {@code TRAN-TYPE-CD PIC X(02)} and {@code TRAN-CAT-CD PIC 9(04)}.
     * Implemented as an {@code @EmbeddedId} referencing {@link TransactionCategoryId}.</p>
     */
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Transaction category type description — maps COBOL {@code TRAN-CAT-TYPE-DESC PIC X(50)}.
     *
     * <p>A human-readable description of the transaction category, up to 50 characters.
     * Used for display enrichment in transaction views, batch report generation,
     * and statement processing. The 4-byte FILLER following this field in the COBOL
     * record is not mapped as it serves only as record padding.</p>
     *
     * <p>Examples: "Regular Sales Draft", "Cash Advance", "Balance Transfer".</p>
     */
    @Column(name = "cat_desc", length = 50)
    private String tranCatTypeDesc;

    /**
     * No-argument constructor required by the JPA specification.
     *
     * <p>This constructor is used by the JPA provider (Hibernate) when
     * materializing entity instances from database query results.</p>
     */
    public TransactionCategory() {
        // JPA-required default constructor
    }

    /**
     * All-argument constructor for programmatic entity creation.
     *
     * @param id              the composite primary key containing type code and category code;
     *                        must not be {@code null}
     * @param tranCatTypeDesc the category type description; up to 50 characters,
     *                        maps COBOL {@code TRAN-CAT-TYPE-DESC PIC X(50)}
     */
    public TransactionCategory(TransactionCategoryId id, String tranCatTypeDesc) {
        this.id = id;
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the composite primary key.
     *
     * @return the composite key containing type code and category code;
     *         never {@code null} for persisted entities
     */
    public TransactionCategoryId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the composite key to set; must not be {@code null}
     */
    public void setId(TransactionCategoryId id) {
        this.id = id;
    }

    /**
     * Returns the transaction category type description.
     *
     * <p>Maps COBOL {@code TRAN-CAT-TYPE-DESC PIC X(50)} — up to 50 characters
     * describing the transaction category within its type.</p>
     *
     * @return the category type description, may be {@code null}
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * Sets the transaction category type description.
     *
     * @param tranCatTypeDesc the description to set; up to 50 characters
     */
    public void setTranCatTypeDesc(String tranCatTypeDesc) {
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Compares this transaction category to another object for equality.
     *
     * <p>Two {@code TransactionCategory} instances are equal if and only if
     * they have the same composite primary key ({@code id}). This is critical
     * for correct JPA entity identity, Hibernate persistence context behavior,
     * first-level cache operations, and collection membership checks.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the objects have equal composite keys; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategory that = (TransactionCategory) o;
        return Objects.equals(id, that.id);
    }

    /**
     * Returns a hash code based on the composite primary key.
     *
     * <p>Consistent with {@link #equals(Object)} — uses only the {@code id}
     * field to ensure the hash code contract is preserved for hash-based
     * collections and JPA identity maps.</p>
     *
     * @return hash code derived from the embedded composite ID
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Returns a string representation of this transaction category record,
     * including the composite key and description for diagnostic and logging purposes.
     *
     * @return a formatted string containing the composite key and description
     */
    @Override
    public String toString() {
        return "TransactionCategory{"
                + "id=" + id
                + ", tranCatTypeDesc='" + tranCatTypeDesc + '\''
                + '}';
    }
}
