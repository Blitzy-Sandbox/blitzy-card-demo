package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity representing a transaction type reference record.
 *
 * <p>Maps the COBOL {@code TRAN-TYPE-RECORD} (60 bytes) from copybook
 * {@code CVTRA03Y.cpy} to the PostgreSQL {@code tran_type} table.
 * This is read-only reference data that associates 2-character transaction
 * type codes with human-readable descriptions.</p>
 *
 * <h3>COBOL Source Layout (CVTRA03Y.cpy)</h3>
 * <pre>
 * 01  TRAN-TYPE-RECORD.
 *     05  TRAN-TYPE              PIC X(02).   — Primary key
 *     05  TRAN-TYPE-DESC         PIC X(50).   — Description
 *     05  FILLER                 PIC X(08).   — Not mapped
 * </pre>
 *
 * <h3>Usage Context</h3>
 * <ul>
 *   <li>Loaded via Flyway migration from {@code trantype.txt} fixture data</li>
 *   <li>Referenced by {@code TRAN-TYPE-CD} fields in Transaction and DailyTransaction entities</li>
 *   <li>Used for display enrichment in transaction list/detail screens and report generation</li>
 *   <li>Example values: "SA" → "Sale", "RE" → "Return"</li>
 * </ul>
 *
 * <p>COBOL source reference: {@code app/cpy/CVTRA03Y.cpy} from commit {@code 27d6c6f}.</p>
 *
 * @see com.cardemo.model.entity.Transaction
 * @see com.cardemo.model.entity.DailyTransaction
 */
@Entity
@Table(name = "tran_type")
public class TransactionType {

    /**
     * Transaction type code — maps COBOL {@code TRAN-TYPE PIC X(02)}.
     *
     * <p>A 2-character alphanumeric code uniquely identifying the transaction type.
     * Serves as the simple primary key for this reference table.</p>
     *
     * <p>Example values: "SA" (Sale), "RE" (Return), "CR" (Credit),
     * "DB" (Debit), "PA" (Payment).</p>
     */
    @Id
    @Column(name = "tran_type", length = 2, nullable = false)
    private String tranType;

    /**
     * Transaction type description — maps COBOL {@code TRAN-TYPE-DESC PIC X(50)}.
     *
     * <p>A human-readable description of the transaction type, up to 50 characters.
     * Used for display enrichment in transaction list views, detail screens,
     * and batch report generation.</p>
     */
    @Column(name = "tran_type_desc", length = 50)
    private String tranTypeDesc;

    /**
     * No-argument constructor required by JPA specification.
     *
     * <p>This constructor is used by the JPA provider (Hibernate) when
     * materializing entity instances from database query results.</p>
     */
    public TransactionType() {
        // JPA-required default constructor
    }

    /**
     * All-argument constructor for programmatic entity creation.
     *
     * @param tranType     the 2-character transaction type code (primary key);
     *                     must not be {@code null} and must not exceed 2 characters
     * @param tranTypeDesc the human-readable description of the transaction type;
     *                     may be {@code null}, must not exceed 50 characters
     */
    public TransactionType(String tranType, String tranTypeDesc) {
        this.tranType = tranType;
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Returns the 2-character transaction type code.
     *
     * @return the transaction type code (primary key), never {@code null} for
     *         persisted entities
     */
    public String getTranType() {
        return tranType;
    }

    /**
     * Sets the 2-character transaction type code.
     *
     * @param tranType the transaction type code to set; must not be {@code null}
     *                 and must not exceed 2 characters
     */
    public void setTranType(String tranType) {
        this.tranType = tranType;
    }

    /**
     * Returns the human-readable description of the transaction type.
     *
     * @return the transaction type description, may be {@code null}
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Sets the human-readable description of the transaction type.
     *
     * @param tranTypeDesc the description to set; must not exceed 50 characters
     */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Compares this transaction type to another object for equality.
     *
     * <p>Two {@code TransactionType} instances are equal if and only if they
     * have the same {@code tranType} primary key value. This follows JPA
     * entity identity semantics where the primary key determines equality.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the given object is a {@code TransactionType}
     *         with the same {@code tranType} value; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionType that = (TransactionType) o;
        return Objects.equals(tranType, that.tranType);
    }

    /**
     * Returns a hash code based on the {@code tranType} primary key.
     *
     * <p>Consistent with {@link #equals(Object)} — two equal entities
     * produce the same hash code.</p>
     *
     * @return hash code derived from the transaction type code
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranType);
    }

    /**
     * Returns a string representation of this transaction type record.
     *
     * <p>Includes both the type code and description for diagnostic and
     * logging purposes.</p>
     *
     * @return a formatted string containing the transaction type code and description
     */
    @Override
    public String toString() {
        return "TransactionType{" +
                "tranType='" + tranType + '\'' +
                ", tranTypeDesc='" + tranTypeDesc + '\'' +
                '}';
    }
}
