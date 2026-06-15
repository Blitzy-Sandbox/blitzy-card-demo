package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

import com.cardemo.model.key.TransactionCategoryBalanceId;

/**
 * JPA entity mapping the legacy AWS CardDemo transaction-category-balance record
 * onto the PostgreSQL {@code transaction_category_balances} table.
 *
 * <p>This entity is the Java&nbsp;25 / Spring Data JPA replacement for the VSAM
 * KSDS dataset {@code TCATBAL}, whose fixed 50-byte record layout is defined by
 * the COBOL copybook {@code app/cpy/CVTRA01Y.cpy} ({@code 01 TRAN-CAT-BAL-RECORD},
 * record length&nbsp;50). {@code TCATBAL} holds a <em>per-account, per-type,
 * per-category running balance</em>: each row accumulates the balance for one
 * {@code (account, transaction-type, transaction-category)} combination. On the
 * mainframe this record was maintained by the batch program {@code CBTRN02C}
 * (daily transaction posting &mdash; which <em>increments</em> the balance as
 * transactions post) and read by {@code CBACT04C} (interest calculation &mdash;
 * which <em>reads</em> the balance to compute interest). It is therefore a
 * decimal-critical entity: penny-exact parity between the posting and interest
 * paths is mandatory.</p>
 *
 * <h2>Original COBOL layout (CVTRA01Y.cpy &mdash; RECLN 50)</h2>
 * <pre>{@code
 * 01  TRAN-CAT-BAL-RECORD.
 *     05  TRAN-CAT-KEY.
 *         10  TRANCAT-ACCT-ID      PIC 9(11).
 *         10  TRANCAT-TYPE-CD      PIC X(02).
 *         10  TRANCAT-CD           PIC 9(04).
 *     05  TRAN-CAT-BAL             PIC S9(09)V99.
 *     05  FILLER                   PIC X(22).
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA composite key.</strong> The
 *       physically keyed {@code TCATBAL} cluster was addressed by the contiguous
 *       byte string formed from the three {@code TRAN-CAT-KEY} sub-fields
 *       ({@code TRANCAT-ACCT-ID} + {@code TRANCAT-TYPE-CD} + {@code TRANCAT-CD}).
 *       That concatenated physical key is replaced by an explicit, typed JPA
 *       composite key: the {@code @EmbeddedId} {@link #id} of type
 *       {@link TransactionCategoryBalanceId}. The three key components are carried
 *       <strong>solely</strong> by that embedded id and are deliberately
 *       <strong>not</strong> redeclared as separate columns on this entity, so
 *       there is no duplication of the key fields. Keyed reads/writes served by
 *       batch file control on the mainframe are replaced by a Spring Data
 *       {@code TransactionCategoryBalanceRepository}
 *       ({@code JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId>}).</li>
 *   <li><strong>Signed packed/zoned decimal &rarr; {@link BigDecimal}.</strong>
 *       {@code TRAN-CAT-BAL PIC S9(09)V99} is the signed running balance: nine
 *       integer digits plus two fractional digits. It maps to a {@link BigDecimal}
 *       with {@code precision = 11, scale = 2} (9&nbsp;+&nbsp;2&nbsp;=&nbsp;11
 *       total digits). <strong>No {@code float} or {@code double} is used</strong>
 *       (AAP §0.7.3): this balance is incremented during posting
 *       ({@code CBTRN02C}) and read for interest ({@code CBACT04C}), so numeric
 *       comparisons in the service layer must use {@link BigDecimal#compareTo}
 *       (which is scale-insensitive) rather than {@link BigDecimal#equals} (which
 *       is scale-sensitive).</li>
 *   <li><strong>No optimistic-locking column.</strong> Per AAP §0.7.5, JPA
 *       {@code @Version} is applied <strong>only</strong> to {@code Account} and
 *       {@code Card} (the read-update programs {@code COACTUPC} and
 *       {@code COCRDUPC}). The category-balance record is maintained by batch
 *       posting, not by an online read-update snapshot comparison, so this entity
 *       carries <strong>no</strong> {@code @Version} column.</li>
 *   <li><strong>No associations / no Bean Validation.</strong> Per the Minimal
 *       Change Clause this is a pure persistence type: it declares exactly the
 *       embedded key and the single balance field, models no JPA associations
 *       (the account/type/category linkage is expressed only through the key
 *       components), and carries no Jakarta Bean Validation annotations (input
 *       validation lives in the request-DTO layer, AAP §0.4.2).</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(22)} is reserved padding that pads the record to its
 *       50-byte length ({@code 11 + 2 + 4 + 11 + 22 = 50}, counting the eleven
 *       digit positions of the signed balance); it carries no business data and
 *       is intentionally <strong>not</strong> mapped to a column. The 50-byte
 *       record length is documented here for external-contract reference only
 *       (AAP §0.7.2).</li>
 * </ul>
 *
 * <h2>Composite-key &amp; column-name contract</h2>
 * <p>The composite primary key is supplied verbatim by
 * {@link TransactionCategoryBalanceId}, which declares the authoritative physical
 * column names {@code account_id} ({@code BIGINT}), {@code type_code}
 * ({@code VARCHAR(2)}) and {@code category_code} ({@code INTEGER}). This entity
 * adds the single non-key column {@code balance} ({@code NUMERIC(11,2)}). These
 * names and types are authoritative for the data layer: the Flyway
 * {@code V1__create_schema.sql} {@code transaction_category_balances} table must
 * declare the three-part composite primary key plus the {@code balance} column
 * exactly as named here, and the {@code V3} seed (loaded from
 * {@code app/data/ASCII/tcatbal.txt}, 50-byte records) must align with these
 * names, types and scales. The {@code account_id} component is a {@code Long}
 * (PostgreSQL {@code BIGINT}) to match the {@code Account} entity primary key
 * (from {@code ACCT-ID PIC 9(11)}), keeping the cross-reference between balance
 * rows and accounts type-consistent; the two-character {@code type_code} matches
 * {@code transaction_types.type_code}.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see TransactionCategoryBalanceId
 * @see Account
 */
@Entity
@Table(name = "transaction_category_balance")
public class TransactionCategoryBalance {

    /**
     * Composite primary key &mdash; the three-part
     * {@code (account id, transaction-type code, transaction-category code)}
     * identifier.
     *
     * <p>Migrated from the COBOL {@code TRAN-CAT-KEY} group
     * ({@code TRANCAT-ACCT-ID PIC 9(11)} + {@code TRANCAT-TYPE-CD PIC X(02)} +
     * {@code TRANCAT-CD PIC 9(04)}). The three components are carried by the
     * embedded {@link TransactionCategoryBalanceId} and mapped to the
     * {@code account_id}, {@code type_code} and {@code category_code} columns;
     * they are deliberately not redeclared here. This is the entity's sole
     * identifier, so the downstream repository is
     * {@code JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId>}.</p>
     */
    // TRAN-CAT-KEY group (acct 9(11) + type X(02) + cat 9(04)) -> composite key @EmbeddedId
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Signed running balance for this account/type/category combination.
     *
     * <p>Migrated from {@code TRAN-CAT-BAL PIC S9(09)V99}: nine integer digits
     * plus two fractional digits, signed. It is mapped to a {@link BigDecimal}
     * with {@code precision = 11, scale = 2} so the exact decimal scale is
     * preserved. <strong>No {@code float}/{@code double}</strong> is used
     * (AAP §0.7.3); the balance is incremented by daily posting
     * ({@code CBTRN02C}) and read by interest calculation ({@code CBACT04C}), so
     * service-layer comparisons must use {@link BigDecimal#compareTo} rather than
     * {@link BigDecimal#equals}.</p>
     */
    // TRAN-CAT-BAL PIC S9(09)V99 -> signed money/balance -> BigDecimal(precision=11, scale=2) (NUMERIC(11,2))
    @Column(name = "balance", precision = 11, scale = 2)
    private BigDecimal tranCatBal;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate) to
     * instantiate the entity reflectively before populating its fields.
     */
    public TransactionCategoryBalance() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Convenience constructor that fully populates the entity, useful for tests
     * and for seeding category-balance data programmatically.
     *
     * @param id         the composite key ({@code TRAN-CAT-KEY})
     * @param tranCatBal the running balance ({@code TRAN-CAT-BAL})
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal tranCatBal) {
        this.id = id;
        this.tranCatBal = tranCatBal;
    }

    /**
     * Returns the composite primary key ({@code TRAN-CAT-KEY}).
     *
     * @return the composite key, or {@code null} if unset
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * Sets the composite primary key ({@code TRAN-CAT-KEY}).
     *
     * @param id the composite key to set
     */
    public void setId(TransactionCategoryBalanceId id) {
        this.id = id;
    }

    /**
     * Returns the running balance ({@code TRAN-CAT-BAL}).
     *
     * @return the balance, or {@code null} if unset
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * Sets the running balance ({@code TRAN-CAT-BAL}).
     *
     * @param tranCatBal the balance to set (scale 2, no {@code float}/{@code double})
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    /**
     * Identity-based equality keyed on the composite primary key ({@link #id}).
     *
     * <p>Two {@code TransactionCategoryBalance} instances are equal when they are
     * of the exact same class and share the same {@link #id}. The primary key
     * alone defines entity identity; the mutable balance is deliberately excluded
     * so equality stays stable as the balance is updated. Exact-class comparison
     * (rather than {@code instanceof}) is used so a proxy/subclass is not treated
     * as equal to a different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code TransactionCategoryBalance}
     *         with an equal composite key
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
     * Hash code derived solely from the composite key ({@link #id}), consistent
     * with {@link #equals(Object)}.
     *
     * @return the hash code of the composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Diagnostic representation including the composite key and the balance.
     *
     * @return a human-readable description of this category-balance row
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance{"
                + "id=" + id
                + ", tranCatBal=" + tranCatBal
                + '}';
    }
}
