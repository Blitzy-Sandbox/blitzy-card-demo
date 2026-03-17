package com.cardemo.model.entity;

import com.cardemo.model.key.TransactionCategoryBalanceId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL {@code TRAN-CAT-BAL-RECORD} (50 bytes) from
 * {@code app/cpy/CVTRA01Y.cpy} to the PostgreSQL {@code tran_cat_bal} table.
 *
 * <p>This entity tracks the cumulative balance for each combination of account,
 * transaction type, and transaction category. It is a critical component of the
 * batch processing pipeline, referenced by both the interest calculation batch
 * program ({@code CBACT04C.cbl}) and the daily transaction posting batch program
 * ({@code CBTRN02C.cbl}).</p>
 *
 * <h3>Source COBOL Structure</h3>
 * <pre>{@code
 * Source: app/cpy/CVTRA01Y.cpy (commit 27d6c6f)
 * Record Length: 50 bytes
 * VSAM Dataset: TCATBALF (KSDS, keyed on TRAN-CAT-KEY)
 *
 *   01  TRAN-CAT-BAL-RECORD.
 *       05  TRAN-CAT-KEY.
 *           10  TRANCAT-ACCT-ID     PIC 9(11).
 *           10  TRANCAT-TYPE-CD     PIC X(02).
 *           10  TRANCAT-CD          PIC 9(04).
 *       05  TRAN-CAT-BAL            PIC S9(09)V99.
 *       05  FILLER                  PIC X(22).
 * }</pre>
 *
 * <h3>Key Usage Contexts</h3>
 * <ul>
 *   <li>{@code CBACT04C.cbl} — Interest calculation: reads TCATBALF by account ID,
 *       multiplies balance by disclosure group rate using the formula
 *       {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200} with
 *       {@code RoundingMode.HALF_EVEN} (banker's rounding).</li>
 *   <li>{@code CBTRN02C.cbl} — Transaction posting: updates category balance after
 *       posting each validated daily transaction.</li>
 *   <li>Seeded via Flyway migration {@code V3__seed_data.sql} from the
 *       {@code tcatbal.txt} ASCII fixture file.</li>
 * </ul>
 *
 * <h3>Decimal Precision</h3>
 * <p>The {@code tranCatBal} field uses {@link BigDecimal} with precision=11 and
 * scale=2, matching the COBOL {@code PIC S9(09)V99} specification exactly:
 * 9 integer digits + 2 decimal digits = 11 total. Zero float/double substitution
 * is enforced per AAP decimal precision rules (§0.8.2).</p>
 *
 * <p>The 22-byte {@code FILLER} at the end of the COBOL record is padding only
 * and is not mapped to any database column.</p>
 *
 * <p>COBOL source reference: {@code app/cpy/CVTRA01Y.cpy} from commit
 * {@code 27d6c6f}.</p>
 *
 * @see TransactionCategoryBalanceId
 */
@Entity
@Table(name = "tran_cat_bal")
public class TransactionCategoryBalance {

    /**
     * Composite primary key consisting of account ID, transaction type code,
     * and transaction category code.
     *
     * <p>Maps the COBOL group-level key {@code TRAN-CAT-KEY} which combines:
     * <ul>
     *   <li>{@code TRANCAT-ACCT-ID PIC 9(11)} — 11-digit numeric account ID</li>
     *   <li>{@code TRANCAT-TYPE-CD PIC X(02)} — 2-character transaction type code</li>
     *   <li>{@code TRANCAT-CD PIC 9(04)} — 4-digit transaction category code</li>
     * </ul>
     * </p>
     */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Cumulative balance for this account/type/category combination.
     *
     * <p>Maps COBOL {@code TRAN-CAT-BAL PIC S9(09)V99}: a signed numeric field
     * with 9 integer digits and 2 decimal places. Stored as {@link BigDecimal}
     * with {@code precision = 11} (9 + 2) and {@code scale = 2}, matching the
     * DDL {@code NUMERIC(11,2)} column definition.</p>
     *
     * <p>This value is used in the interest calculation formula:
     * {@code (tranCatBal × disclosureRate) / 1200} with
     * {@code RoundingMode.HALF_EVEN} (banker's rounding, matching COBOL default).
     * For comparisons in business logic, always use {@code compareTo()} rather
     * than {@code equals()}, as {@code BigDecimal.equals()} is scale-sensitive.</p>
     */
    @Column(name = "tran_cat_bal", precision = 11, scale = 2)
    private BigDecimal tranCatBal;

    /**
     * No-argument constructor required by the JPA specification.
     *
     * <p>This constructor is used by the JPA provider (Hibernate) when
     * materializing entity instances from database query results.</p>
     */
    public TransactionCategoryBalance() {
        // JPA-required default constructor
    }

    /**
     * All-argument constructor for programmatic entity creation.
     *
     * <p>Enables direct construction with a composite key and balance value,
     * used during batch transaction posting and test fixture setup.</p>
     *
     * @param id         the composite primary key containing account ID,
     *                   transaction type code, and category code; must not be {@code null}
     * @param tranCatBal the cumulative balance for this category combination;
     *                   must use {@link BigDecimal} for precision (never float/double)
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal tranCatBal) {
        this.id = id;
        this.tranCatBal = tranCatBal;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the composite primary key.
     *
     * @return the composite key containing account ID, type code, and category
     *         code; never {@code null} for persisted entities
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the composite key to set; must not be {@code null}
     */
    public void setId(TransactionCategoryBalanceId id) {
        this.id = id;
    }

    /**
     * Returns the cumulative balance for this account/type/category combination.
     *
     * <p>Maps COBOL {@code TRAN-CAT-BAL PIC S9(09)V99}.</p>
     *
     * @return the balance as {@link BigDecimal} with scale 2; may be {@code null}
     *         if not yet initialized
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * Sets the cumulative balance for this account/type/category combination.
     *
     * <p>The value must be a {@link BigDecimal} to preserve COBOL packed decimal
     * precision. Never pass a value derived from {@code float} or {@code double}
     * arithmetic.</p>
     *
     * @param tranCatBal the balance to set; must use {@link BigDecimal} for precision
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Compares this transaction category balance to another object for equality.
     *
     * <p>Two {@code TransactionCategoryBalance} instances are considered equal if
     * and only if they have the same composite primary key ({@code id}). The
     * balance value is intentionally excluded from equality comparison because
     * JPA entity identity is determined solely by the primary key.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code TransactionCategoryBalance}
     *         with the same composite key; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategoryBalance that = (TransactionCategoryBalance) o;
        return Objects.equals(id, that.id);
    }

    /**
     * Returns a hash code based on the composite primary key.
     *
     * <p>Consistent with {@link #equals(Object)}: if two entities are equal
     * (same composite key), they produce the same hash code.</p>
     *
     * @return hash code derived from the embedded ID
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Returns a human-readable string representation of this transaction category
     * balance record, including the composite key and balance value.
     *
     * @return a formatted string in the form
     *         {@code TransactionCategoryBalance{id=..., tranCatBal=...}}
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance{"
                + "id=" + id
                + ", tranCatBal=" + tranCatBal
                + '}';
    }
}
