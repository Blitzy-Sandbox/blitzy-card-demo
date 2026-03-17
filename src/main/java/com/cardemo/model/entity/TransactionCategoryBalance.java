package com.cardemo.model.entity;

import com.cardemo.model.key.TransactionCategoryBalanceId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL TRANSACTION-CATEGORY-BALANCE-RECORD (50 bytes)
 * from CVTRA01Y.cpy to the PostgreSQL {@code transaction_category_balances} table.
 *
 * <p>This entity tracks the running balance of transactions per account, per
 * transaction type, per category. It is used during interest calculation in the
 * batch pipeline (CBACT04C.cbl) where the balance for each type/category
 * combination is multiplied by the corresponding disclosure group interest rate.</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <pre>
 * Source: app/cpy/CVTRA01Y.cpy (commit 27d6c6f)
 * Record Length: 50 bytes
 * VSAM Dataset: TCATBALF (KSDS, keyed on TRAN-CAT-BAL-KEY)
 *
 *   01  TRAN-CAT-BAL-RECORD.
 *       05  TRAN-CAT-KEY.
 *           10  TRAN-CAT-ACCT-ID     PIC X(11).
 *           10  TRANCAT-TYPE-CD      PIC X(02).
 *           10  TRANCAT-CD           PIC 9(04).
 *       05  TRAN-CAT-BAL             PIC S9(09)V99.
 *       05  FILLER                   PIC X(21).
 * </pre>
 *
 * <h3>Key Usage Contexts</h3>
 * <ul>
 *   <li>CBACT04C.cbl — Interest calculation: reads TCATBALF by account ID,
 *       multiplies balance by disclosure group rate:
 *       {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200}</li>
 *   <li>CBTRN02C.cbl — Transaction posting: updates category balance after
 *       posting each validated transaction</li>
 *   <li>Loaded via Flyway migration from {@code tcatbal.txt} fixture data</li>
 * </ul>
 *
 * <h3>Decimal Precision</h3>
 * <p>The {@code balance} field uses {@link BigDecimal} with precision=11, scale=2,
 * matching the COBOL PIC S9(09)V99 specification. Zero float/double substitution
 * per AAP decimal precision rules.</p>
 *
 * <p>COBOL source reference: {@code app/cpy/CVTRA01Y.cpy} from commit {@code 27d6c6f}.</p>
 *
 * @see TransactionCategoryBalanceId
 * @see com.cardemo.batch.processors.InterestCalculationProcessor
 */
@Entity
@Table(name = "transaction_category_balances")
public class TransactionCategoryBalance {

    /**
     * Composite primary key consisting of account ID, transaction type code,
     * and category code.
     *
     * <p>Maps the COBOL group-level key {@code TRAN-CAT-KEY} which combines
     * {@code TRAN-CAT-ACCT-ID PIC X(11)}, {@code TRANCAT-TYPE-CD PIC X(02)},
     * and {@code TRANCAT-CD PIC 9(04)}.</p>
     */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Running balance for this account/type/category combination.
     *
     * <p>Maps COBOL {@code TRAN-CAT-BAL PIC S9(09)V99}. The signed numeric
     * field with 2 decimal places is stored as {@link BigDecimal} with
     * precision 11 and scale 2, matching the DDL {@code NUMERIC(11,2)}.</p>
     *
     * <p>Used in the interest calculation formula:
     * {@code (balance × disclosureRate) / 1200} with
     * {@code RoundingMode.HALF_EVEN} (banker's rounding).</p>
     */
    @Column(name = "balance", precision = 11, scale = 2)
    private BigDecimal balance;

    /**
     * No-argument constructor required by JPA specification.
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
     * @param id      the composite primary key; must not be {@code null}
     * @param balance the running balance for this category; may be {@code null}
     *                initially, must use {@link BigDecimal} for precision
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal balance) {
        this.id = id;
        this.balance = balance;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the composite primary key.
     *
     * @return the composite key containing account ID, type code, and category code;
     *         never {@code null} for persisted entities
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
     * Returns the running balance for this category.
     *
     * @return the balance as {@link BigDecimal}, may be {@code null}
     */
    public BigDecimal getBalance() {
        return balance;
    }

    /**
     * Sets the running balance for this category.
     *
     * @param balance the balance to set; must use {@link BigDecimal} for precision
     */
    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Compares this transaction category balance to another object for equality.
     *
     * <p>Two {@code TransactionCategoryBalance} instances are equal if and only if
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
        TransactionCategoryBalance that = (TransactionCategoryBalance) o;
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
     * Returns a string representation of this transaction category balance record.
     *
     * @return a formatted string containing the composite key and balance
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance{" +
                "id=" + id +
                ", balance=" + balance +
                '}';
    }
}
