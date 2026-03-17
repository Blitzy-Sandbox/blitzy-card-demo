package com.cardemo.model.entity;

import com.cardemo.model.key.TransactionCategoryId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity mapping the COBOL TRANSACTION-CATEGORY-RECORD (60 bytes) from
 * CVTRA04Y.cpy to the PostgreSQL {@code transaction_categories} table.
 *
 * <p>This is a read-only reference entity that associates a composite key of
 * (type_cd, cat_cd) with a human-readable category description. It serves as
 * the classification taxonomy for transactions within each transaction type.</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <pre>
 * Source: app/cpy/CVTRA04Y.cpy (commit 27d6c6f)
 * Record Length: 60 bytes
 * VSAM Dataset: TRANCATG (KSDS, keyed on TRAN-CAT-KEY)
 *
 *   01  TRAN-CAT-RECORD.
 *       05  TRAN-CAT-KEY.
 *           10  TRAN-TYPE-CD      PIC X(02).
 *           10  TRAN-CAT-CD       PIC 9(04).
 *       05  TRAN-CAT-DESC         PIC X(50).
 *       05  FILLER                PIC X(04).
 * </pre>
 *
 * <h3>Key Usage Contexts</h3>
 * <ul>
 *   <li>Loaded via Flyway migration from {@code trancatg.txt} fixture data</li>
 *   <li>Referenced by batch transaction posting (CBTRN02C.cbl) for validation</li>
 *   <li>Referenced by transaction category balance maintenance</li>
 *   <li>Foreign key relationship: {@code type_cd} references
 *       {@code transaction_types(type_cd)}</li>
 *   <li>Example: type_cd='01', cat_cd=1 → "Regular Sales Draft"</li>
 * </ul>
 *
 * <p>COBOL source reference: {@code app/cpy/CVTRA04Y.cpy} from commit {@code 27d6c6f}.</p>
 *
 * @see TransactionCategoryId
 * @see TransactionType
 */
@Entity
@Table(name = "transaction_categories")
public class TransactionCategory {

    /**
     * Composite primary key consisting of transaction type code and category code.
     *
     * <p>Maps the COBOL group-level key {@code TRAN-CAT-KEY} which combines
     * {@code TRAN-TYPE-CD PIC X(02)} and {@code TRAN-CAT-CD PIC 9(04)}.</p>
     */
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Category description — maps COBOL {@code TRAN-CAT-DESC PIC X(50)}.
     *
     * <p>A human-readable description of the transaction category, up to
     * 50 characters. Used for display enrichment in transaction views and
     * batch report generation.</p>
     *
     * <p>Examples: "Regular Sales Draft", "Cash Advance", "Balance Transfer".</p>
     */
    @Column(name = "cat_desc", length = 50)
    private String catDesc;

    /**
     * No-argument constructor required by JPA specification.
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
     * @param id      the composite primary key; must not be {@code null}
     * @param catDesc the category description; up to 50 characters, may be {@code null}
     */
    public TransactionCategory(TransactionCategoryId id, String catDesc) {
        this.id = id;
        this.catDesc = catDesc;
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
     * Returns the category description.
     *
     * @return the category description, may be {@code null}
     */
    public String getCatDesc() {
        return catDesc;
    }

    /**
     * Sets the category description.
     *
     * @param catDesc the description to set; up to 50 characters
     */
    public void setCatDesc(String catDesc) {
        this.catDesc = catDesc;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Compares this transaction category to another object for equality.
     *
     * <p>Two {@code TransactionCategory} instances are equal if and only if
     * they have the same composite primary key {@code id} value.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if equal; {@code false} otherwise
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
     * @return hash code derived from the embedded ID
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Returns a string representation of this transaction category record.
     *
     * @return a formatted string containing the composite key and description
     */
    @Override
    public String toString() {
        return "TransactionCategory{" +
                "id=" + id +
                ", catDesc='" + catDesc + '\'' +
                '}';
    }
}
