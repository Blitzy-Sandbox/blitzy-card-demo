package com.carddemo.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * JPA entity representing a transaction-category balance: the running balance
 * accumulated for a given account / transaction-type / transaction-category
 * combination.
 *
 * <p>Migrated field-by-field from the COBOL copybook {@code app/cpy/CVTRA01Y.cpy}
 * ({@code TRAN-CAT-BAL-RECORD}, record length 50 bytes) at source commit SHA
 * {@code 27d6c6f} (CardDemo v1.0-15-g27d6c6f-68). The record is keyed by the
 * three-part {@code TRAN-CAT-KEY} group and carries a single signed decimal
 * balance; it is mapped to the {@code transaction_category_balance} database
 * table.</p>
 *
 * <p>Original COBOL record layout, preserved verbatim:</p>
 * <pre>
 * 01  TRAN-CAT-BAL-RECORD.                        (RECLN = 50)
 *     05  TRAN-CAT-KEY.                           (composite key, 17 bytes)
 *         10  TRANCAT-ACCT-ID   PIC 9(11).        -&gt; id.trancatAcctId
 *         10  TRANCAT-TYPE-CD   PIC X(02).        -&gt; id.trancatTypeCd
 *         10  TRANCAT-CD        PIC 9(04).        -&gt; id.trancatCd
 *     05  TRAN-CAT-BAL          PIC S9(09)V99.    -&gt; tranCatBal (NUMERIC(11,2))
 *     05  FILLER                PIC X(22).        -&gt; not mapped (trailing padding)
 * </pre>
 *
 * <p>The three key components are not declared here directly; they live in the
 * {@code @Embeddable} {@link TransactionCategoryBalanceId} class and are exposed
 * through the {@link #getId() id} property annotated with {@code @EmbeddedId}.
 * The repository sibling therefore declares
 * {@code JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId>}.</p>
 *
 * <p>Decimal-fidelity note: {@code TRAN-CAT-BAL} is a signed zoned-decimal
 * (COBOL {@code DISPLAY}) money field, {@code PIC S9(09)V99} — nine integer
 * digits, two fraction digits, plus an overpunched sign, occupying 11 bytes. It
 * is mapped to {@link BigDecimal} with {@code precision = 11, scale = 2} (a
 * {@code NUMERIC(11,2)} column) so exact decimal precision and sign are preserved
 * end-to-end; {@code float}/{@code double} are never used for monetary values.
 * Byte accounting: {@code 17 (key) + 11 (balance) + 22 (filler) = 50}.</p>
 *
 * <p>This entity is a passive persistence record: it carries no business logic
 * and defines no relationships. The balance is maintained (posted and rewritten)
 * by the batch transaction-posting pipeline rather than through the online
 * read-then-rewrite screens, so — consistent with the migration's placement of
 * optimistic locking on the Account and Card aggregates only — no {@code @Version}
 * column is defined for this record.</p>
 */
@Entity
@Table(name = "transaction_category_balance")
public class TransactionCategoryBalance {

    /**
     * Composite primary key (COBOL {@code TRAN-CAT-KEY} group): account id,
     * transaction type code, and transaction category code.
     *
     * <p>Backed by the {@code @Embeddable} {@link TransactionCategoryBalanceId},
     * whose fields map to the {@code (trancat_acct_id, trancat_type_cd,
     * trancat_cd)} composite primary key of the table. Because the key columns
     * live in the embedded id, they are intentionally not redeclared here.</p>
     */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Running category balance (COBOL {@code TRAN-CAT-BAL PIC S9(09)V99}).
     *
     * <p>Signed nine-integer-digit, two-fraction-digit zoned decimal mapped to a
     * {@code NUMERIC(11,2)} column. Represented as {@link BigDecimal} to preserve
     * exact decimal precision and sign; never {@code float}/{@code double}.</p>
     */
    @Column(name = "tran_cat_bal", precision = 11, scale = 2)
    private BigDecimal tranCatBal;

    /**
     * Default no-argument constructor required by the JPA specification for
     * entity instantiation via reflection.
     */
    public TransactionCategoryBalance() {
        // Intentionally empty: JPA requires a public/protected no-arg constructor.
    }

    /**
     * Returns the composite primary key of this transaction-category balance.
     *
     * @return the embedded composite key, or {@code null} if unset
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * Sets the composite primary key of this transaction-category balance.
     *
     * @param id the embedded composite key to set
     */
    public void setId(TransactionCategoryBalanceId id) {
        this.id = id;
    }

    /**
     * Returns the running category balance.
     *
     * @return the balance as an exact {@link BigDecimal} (scale 2), or
     *         {@code null} if unset
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * Sets the running category balance.
     *
     * @param tranCatBal the balance to set; expected to carry scale 2 to match
     *                    the {@code NUMERIC(11,2)} column
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }
}
