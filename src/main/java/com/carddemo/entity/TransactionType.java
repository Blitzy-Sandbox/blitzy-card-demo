package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity representing a transaction-type reference/lookup record.
 *
 * <p>Migrated field-by-field from the COBOL copybook {@code app/cpy/CVTRA03Y.cpy}
 * ({@code TRAN-TYPE-RECORD}, record length 60 bytes) at source commit SHA
 * {@code 27d6c6f} (CardDemo v1.0-15-g27d6c6f-68). This is a static reference
 * table seeded from the {@code trantype} ASCII fixture and mapped to the
 * {@code transaction_type} database table.</p>
 *
 * <p>Original COBOL record layout, preserved verbatim:</p>
 * <pre>
 * 01  TRAN-TYPE-RECORD.                    (RECLN = 60)
 *     05  TRAN-TYPE          PIC X(02).    -&gt; tranType     (natural key)
 *     05  TRAN-TYPE-DESC     PIC X(50).    -&gt; tranTypeDesc
 *     05  FILLER             PIC X(08).    -&gt; not mapped (trailing padding)
 * </pre>
 *
 * <p>The two-character {@code TRAN-TYPE} code is the single-column natural
 * primary key; there is no surrogate or database-generated identifier. The
 * trailing 8-byte {@code FILLER} carries no business data and is intentionally
 * not mapped. Byte accounting: {@code 2 + 50 + 8 = 60}.</p>
 *
 * <p>This entity is the single-key transaction-type lookup and must not be
 * confused with {@code TransactionCategoryType} (copybook {@code CVTRA04Y}),
 * which uses a composite key.</p>
 */
@Entity
@Table(name = "transaction_type")
public class TransactionType {

    /**
     * Two-character transaction-type code (COBOL {@code TRAN-TYPE PIC X(02)}).
     *
     * <p>Serves as the natural primary key. It is not database-generated; the
     * value originates from the seeded reference data.</p>
     */
    @Id
    @Column(name = "tran_type", length = 2, nullable = false)
    private String tranType;

    /**
     * Human-readable transaction-type description
     * (COBOL {@code TRAN-TYPE-DESC PIC X(50)}).
     */
    @Column(name = "tran_type_desc", length = 50)
    private String tranTypeDesc;

    /**
     * Default no-argument constructor required by the JPA specification for
     * entity instantiation via reflection.
     */
    public TransactionType() {
        // Intentionally empty: JPA requires a public/protected no-arg constructor.
    }

    /**
     * Returns the two-character transaction-type code (natural key).
     *
     * @return the transaction-type code
     */
    public String getTranType() {
        return tranType;
    }

    /**
     * Sets the two-character transaction-type code (natural key).
     *
     * @param tranType the transaction-type code to set
     */
    public void setTranType(String tranType) {
        this.tranType = tranType;
    }

    /**
     * Returns the transaction-type description.
     *
     * @return the transaction-type description
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Sets the transaction-type description.
     *
     * @param tranTypeDesc the transaction-type description to set
     */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }
}
