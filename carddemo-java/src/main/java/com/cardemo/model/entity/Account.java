package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the legacy AWS CardDemo account record onto the PostgreSQL
 * {@code accounts} table.
 *
 * <p>This entity is the Java 25 / Spring Data JPA replacement for the VSAM KSDS
 * dataset {@code ACCTDAT}, whose fixed 300-byte record layout is defined by the
 * COBOL copybook {@code app/cpy/CVACT01Y.cpy} ({@code 01 ACCOUNT-RECORD},
 * record length 300). On the mainframe this record was read and updated by the
 * online programs {@code COACTVWC} (account view) and {@code COACTUPC} (account
 * update) and by the batch programs {@code CBACT01C}, {@code CBACT04C} and
 * {@code CBTRN02C}. It is the foundational entity of the data model: the
 * primary key declared here is referenced by {@code Card}
 * ({@code cardAcctId}), {@code CardCrossReference} ({@code xrefAcctId}) and the
 * composite key {@code TransactionCategoryBalanceId.acctId}.</p>
 *
 * <h2>Original COBOL layout (CVACT01Y.cpy &mdash; RECLN 300)</h2>
 * <pre>{@code
 * 01  ACCOUNT-RECORD.
 *     05  ACCT-ID                 PIC 9(11).
 *     05  ACCT-ACTIVE-STATUS      PIC X(01).
 *     05  ACCT-CURR-BAL           PIC S9(10)V99.
 *     05  ACCT-CREDIT-LIMIT       PIC S9(10)V99.
 *     05  ACCT-CASH-CREDIT-LIMIT  PIC S9(10)V99.
 *     05  ACCT-OPEN-DATE          PIC X(10).
 *     05  ACCT-EXPIRAION-DATE     PIC X(10).
 *     05  ACCT-REISSUE-DATE       PIC X(10).
 *     05  ACCT-CURR-CYC-CREDIT    PIC S9(10)V99.
 *     05  ACCT-CURR-CYC-DEBIT     PIC S9(10)V99.
 *     05  ACCT-ADDR-ZIP           PIC X(10).
 *     05  ACCT-GROUP-ID           PIC X(10).
 *     05  FILLER                  PIC X(178).
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA.</strong> The physically keyed
 *       {@code ACCTDAT} cluster (11-byte key) becomes a relational table whose
 *       primary key is {@link #acctId}. Keyed reads/updates/inserts are served by
 *       a Spring Data {@code AccountRepository} rather than CICS file control.</li>
 *   <li><strong>Sign-overpunch zoned decimal &rarr; {@link BigDecimal}.</strong>
 *       The five {@code PIC S9(10)V99} money fields were stored as zoned-decimal
 *       sign-overpunch text in the source fixtures (for example
 *       {@code 00000001940}{@code &#125;}); the Flyway {@code V3} seed decodes the
 *       overpunch, and this entity simply holds an exact-scale {@link BigDecimal}.
 *       No {@code float}/{@code double} is used anywhere (AAP §0.7.3).</li>
 *   <li><strong>Fixed-text dates &rarr; {@link String}.</strong> The three
 *       {@code PIC X(10)} date fields keep their {@code String} form (text
 *       {@code YYYY-MM-DD}, possibly spaces/zeros in legacy rows) to preserve
 *       byte-level external-interface fidelity (AAP §0.7.2) and avoid parse
 *       failures. Date <em>validation</em> is a separate concern handled by
 *       {@code service.shared.DateValidationService} (using {@code LocalDate}),
 *       not by this persistence entity.</li>
 *   <li><strong>Read-update snapshot comparison &rarr; {@code @Version}.</strong>
 *       {@code COACTUPC} performed an optimistic before/after record-image check
 *       prior to its {@code REWRITE}. That snapshot comparison is replaced by the
 *       JPA {@link Version} column {@link #version}; concurrent modification then
 *       surfaces as an {@code OptimisticLockException}, which
 *       {@code AccountUpdateService} maps to a typed concurrency exception. There
 *       is no corresponding COBOL field for {@link #version}.</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(178)} is reserved padding that pads the record to its
 *       300-byte length; it carries no business data and is intentionally
 *       <strong>not</strong> mapped to a column. The 300-byte record length is
 *       documented here for external-contract reference only.</li>
 * </ul>
 *
 * <h2>Decimal-fidelity contract (AAP §0.7.3)</h2>
 * <p>Each money field is persisted with {@code precision = 12, scale = 2} (ten
 * integer plus two fractional digits). Callers MUST compare balances and limits
 * with {@link BigDecimal#compareTo(BigDecimal)} rather than
 * {@link BigDecimal#equals(Object)}, because {@code equals} is scale-sensitive
 * (for example {@code 1.0} is not {@code equals} to {@code 1.00}). This entity
 * neither rounds nor rescales values; the persisted scale is fixed by the
 * {@code @Column(scale = 2)} mapping.</p>
 *
 * <h2>Primary-key &amp; column-name contract</h2>
 * <p>The {@link Column} names declared below &mdash; {@code account_id},
 * {@code active_status}, {@code current_balance}, {@code credit_limit},
 * {@code cash_credit_limit}, {@code open_date}, {@code expiration_date},
 * {@code reissue_date}, {@code current_cycle_credit},
 * {@code current_cycle_debit}, {@code address_zip}, {@code group_id} and
 * {@code version} &mdash; are authoritative. The Flyway
 * {@code V1__create_schema.sql} {@code accounts} DDL and the
 * {@code AccountRepository} must align with them, with {@code account_id} as
 * {@code BIGINT}. Because {@link #acctId} is migrated from
 * {@code ACCT-ID PIC 9(11)} (eleven digits exceed {@code Integer}'s ~2.1-billion
 * ceiling), its type is {@link Long}; the downstream repository is therefore
 * {@code JpaRepository<Account, Long>}. This resolves the loosely-documented
 * {@code <Account, String>} variant in the blueprint, which is incorrect, and
 * keeps the key type-consistent with
 * {@code TransactionCategoryBalanceId.acctId} (also {@link Long}).</p>
 *
 * <p>Per the Minimal Change Clause this entity is a pure persistence type: it
 * declares exactly the twelve mapped fields plus the {@code @Version} column,
 * carries no Jakarta Bean Validation annotations (input validation lives in the
 * request DTO layer, AAP §0.4.2), and models no JPA associations &mdash; foreign
 * keys to accounts are plain scalar columns on the child entities, mirroring the
 * original VSAM keyed-access pattern (no {@code @OneToMany}).</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see java.math.BigDecimal
 */
@Entity
@Table(name = "accounts")
public class Account {

    /**
     * Primary key &mdash; the unique account identifier.
     *
     * <p>Migrated from {@code ACCT-ID PIC 9(11)}: an eleven-digit unsigned
     * integer. Eleven digits exceed the ~2.1-billion (10-digit) ceiling of
     * {@code Integer}, so {@link Long} (PostgreSQL {@code BIGINT}) is required.
     * This is the authoritative primary-key type for the whole data model.</p>
     */
    // ACCT-ID PIC 9(11) -> 11-digit unsigned integer -> Long (BIGINT)
    @Id
    @Column(name = "account_id", nullable = false)
    private Long acctId;

    /**
     * Account active-status flag.
     *
     * <p>Migrated from {@code ACCT-ACTIVE-STATUS PIC X(01)}: a single character
     * (typically {@code 'Y'} or {@code 'N'}), modelled as a {@link String} of
     * length one. AAP §0.4.1 loosely mentions an "active-status enum", but no
     * such enum exists in {@code model.enums}; per the Minimal Change Clause no
     * out-of-scope enum is invented and the single-character contract is kept.</p>
     */
    // ACCT-ACTIVE-STATUS PIC X(01) -> single-character flag -> String(1)
    @Column(name = "active_status", length = 1)
    private String acctActiveStatus;

    /**
     * Current account balance.
     *
     * <p>Migrated from {@code ACCT-CURR-BAL PIC S9(10)V99}: a signed decimal with
     * ten integer and two fractional digits, mapped to {@link BigDecimal} with
     * {@code precision = 12, scale = 2}.</p>
     */
    // ACCT-CURR-BAL PIC S9(10)V99 -> signed decimal(10,2) -> BigDecimal(12,2)
    @Column(name = "current_balance", precision = 12, scale = 2)
    private BigDecimal acctCurrBal;

    /**
     * Total credit limit for the account.
     *
     * <p>Migrated from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}: a signed decimal
     * mapped to {@link BigDecimal} with {@code precision = 12, scale = 2}.</p>
     */
    // ACCT-CREDIT-LIMIT PIC S9(10)V99 -> signed decimal(10,2) -> BigDecimal(12,2)
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal acctCreditLimit;

    /**
     * Cash-advance credit limit for the account.
     *
     * <p>Migrated from {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}: a signed
     * decimal mapped to {@link BigDecimal} with {@code precision = 12,
     * scale = 2}.</p>
     */
    // ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 -> signed decimal(10,2) -> BigDecimal(12,2)
    @Column(name = "cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal acctCashCreditLimit;

    /**
     * Account open date.
     *
     * <p>Migrated from {@code ACCT-OPEN-DATE PIC X(10)}: a fixed 10-character
     * text date ({@code YYYY-MM-DD}), preserved as a {@link String} for
     * byte-level external-interface fidelity.</p>
     */
    // ACCT-OPEN-DATE PIC X(10) -> fixed 10-char text date (YYYY-MM-DD) -> String(10)
    @Column(name = "open_date", length = 10)
    private String acctOpenDate;

    /**
     * Account expiration date.
     *
     * <p>Migrated from {@code ACCT-EXPIRAION-DATE PIC X(10)} (the COBOL field
     * name misspells "expiration"; the Java field uses the correct spelling
     * while the underlying contract is unchanged). A fixed 10-character text
     * date preserved as a {@link String}.</p>
     */
    // ACCT-EXPIRAION-DATE PIC X(10) -> fixed 10-char text date (YYYY-MM-DD) -> String(10)
    @Column(name = "expiration_date", length = 10)
    private String acctExpirationDate;

    /**
     * Account card-reissue date.
     *
     * <p>Migrated from {@code ACCT-REISSUE-DATE PIC X(10)}: a fixed 10-character
     * text date preserved as a {@link String}.</p>
     */
    // ACCT-REISSUE-DATE PIC X(10) -> fixed 10-char text date (YYYY-MM-DD) -> String(10)
    @Column(name = "reissue_date", length = 10)
    private String acctReissueDate;

    /**
     * Current-cycle credit total.
     *
     * <p>Migrated from {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}: a signed
     * decimal mapped to {@link BigDecimal} with {@code precision = 12,
     * scale = 2}.</p>
     */
    // ACCT-CURR-CYC-CREDIT PIC S9(10)V99 -> signed decimal(10,2) -> BigDecimal(12,2)
    @Column(name = "current_cycle_credit", precision = 12, scale = 2)
    private BigDecimal acctCurrCycCredit;

    /**
     * Current-cycle debit total.
     *
     * <p>Migrated from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}: a signed
     * decimal mapped to {@link BigDecimal} with {@code precision = 12,
     * scale = 2}.</p>
     */
    // ACCT-CURR-CYC-DEBIT PIC S9(10)V99 -> signed decimal(10,2) -> BigDecimal(12,2)
    @Column(name = "current_cycle_debit", precision = 12, scale = 2)
    private BigDecimal acctCurrCycDebit;

    /**
     * Account address ZIP/postal code.
     *
     * <p>Migrated from {@code ACCT-ADDR-ZIP PIC X(10)}: a fixed 10-character
     * alphanumeric field, modelled as a {@link String} of length ten.</p>
     */
    // ACCT-ADDR-ZIP PIC X(10) -> fixed 10-char alphanumeric -> String(10)
    @Column(name = "address_zip", length = 10)
    private String acctAddrZip;

    /**
     * Account group identifier.
     *
     * <p>Migrated from {@code ACCT-GROUP-ID PIC X(10)}: a fixed 10-character
     * alphanumeric field, modelled as a {@link String} of length ten. This is
     * the account-group code that the {@code DisclosureGroup} key
     * ({@code group_id}) joins against conceptually for interest-rate lookup.</p>
     */
    // ACCT-GROUP-ID PIC X(10) -> fixed 10-char alphanumeric -> String(10)
    @Column(name = "group_id", length = 10)
    private String acctGroupId;

    /**
     * Optimistic-locking version counter.
     *
     * <p>This field has <strong>no</strong> COBOL counterpart. It is the JPA
     * replacement for the read-update snapshot comparison that {@code COACTUPC}
     * performed before its {@code REWRITE} (AAP §0.7.5). Hibernate increments the
     * {@code version} column on each update and raises an
     * {@code OptimisticLockException} when a stale copy is written, preserving
     * the original concurrency safeguard with declarative JPA semantics.</p>
     */
    // (no COBOL field) -> JPA optimistic-locking replacement for COACTUPC snapshot compare
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate)
     * to instantiate the entity reflectively before populating its fields.
     */
    public Account() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Returns the primary-key account identifier ({@code ACCT-ID}).
     *
     * @return the account identifier, or {@code null} if unset
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the primary-key account identifier ({@code ACCT-ID}).
     *
     * @param acctId the account identifier to set
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the active-status flag ({@code ACCT-ACTIVE-STATUS}).
     *
     * @return the single-character active-status flag, or {@code null} if unset
     */
    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    /**
     * Sets the active-status flag ({@code ACCT-ACTIVE-STATUS}).
     *
     * @param acctActiveStatus the single-character active-status flag to set
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    /**
     * Returns the current balance ({@code ACCT-CURR-BAL}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)},
     * never {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the current balance, or {@code null} if unset
     */
    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    /**
     * Sets the current balance ({@code ACCT-CURR-BAL}). The value is persisted at
     * scale 2; this setter neither rounds nor rescales.
     *
     * @param acctCurrBal the current balance to set
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    /**
     * Returns the credit limit ({@code ACCT-CREDIT-LIMIT}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)},
     * never {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the credit limit, or {@code null} if unset
     */
    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    /**
     * Sets the credit limit ({@code ACCT-CREDIT-LIMIT}). The value is persisted at
     * scale 2; this setter neither rounds nor rescales.
     *
     * @param acctCreditLimit the credit limit to set
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    /**
     * Returns the cash-advance credit limit ({@code ACCT-CASH-CREDIT-LIMIT}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)},
     * never {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the cash credit limit, or {@code null} if unset
     */
    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    /**
     * Sets the cash-advance credit limit ({@code ACCT-CASH-CREDIT-LIMIT}). The
     * value is persisted at scale 2; this setter neither rounds nor rescales.
     *
     * @param acctCashCreditLimit the cash credit limit to set
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    /**
     * Returns the account open date ({@code ACCT-OPEN-DATE}) as text.
     *
     * @return the 10-character open date, or {@code null} if unset
     */
    public String getAcctOpenDate() {
        return acctOpenDate;
    }

    /**
     * Sets the account open date ({@code ACCT-OPEN-DATE}) as text.
     *
     * @param acctOpenDate the 10-character open date to set
     */
    public void setAcctOpenDate(String acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    /**
     * Returns the account expiration date ({@code ACCT-EXPIRAION-DATE}) as text.
     *
     * @return the 10-character expiration date, or {@code null} if unset
     */
    public String getAcctExpirationDate() {
        return acctExpirationDate;
    }

    /**
     * Sets the account expiration date ({@code ACCT-EXPIRAION-DATE}) as text.
     *
     * @param acctExpirationDate the 10-character expiration date to set
     */
    public void setAcctExpirationDate(String acctExpirationDate) {
        this.acctExpirationDate = acctExpirationDate;
    }

    /**
     * Returns the account reissue date ({@code ACCT-REISSUE-DATE}) as text.
     *
     * @return the 10-character reissue date, or {@code null} if unset
     */
    public String getAcctReissueDate() {
        return acctReissueDate;
    }

    /**
     * Sets the account reissue date ({@code ACCT-REISSUE-DATE}) as text.
     *
     * @param acctReissueDate the 10-character reissue date to set
     */
    public void setAcctReissueDate(String acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    /**
     * Returns the current-cycle credit total ({@code ACCT-CURR-CYC-CREDIT}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)},
     * never {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the current-cycle credit total, or {@code null} if unset
     */
    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    /**
     * Sets the current-cycle credit total ({@code ACCT-CURR-CYC-CREDIT}). The
     * value is persisted at scale 2; this setter neither rounds nor rescales.
     *
     * @param acctCurrCycCredit the current-cycle credit total to set
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    /**
     * Returns the current-cycle debit total ({@code ACCT-CURR-CYC-DEBIT}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)},
     * never {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the current-cycle debit total, or {@code null} if unset
     */
    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    /**
     * Sets the current-cycle debit total ({@code ACCT-CURR-CYC-DEBIT}). The value
     * is persisted at scale 2; this setter neither rounds nor rescales.
     *
     * @param acctCurrCycDebit the current-cycle debit total to set
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    /**
     * Returns the address ZIP/postal code ({@code ACCT-ADDR-ZIP}).
     *
     * @return the 10-character address ZIP code, or {@code null} if unset
     */
    public String getAcctAddrZip() {
        return acctAddrZip;
    }

    /**
     * Sets the address ZIP/postal code ({@code ACCT-ADDR-ZIP}).
     *
     * @param acctAddrZip the 10-character address ZIP code to set
     */
    public void setAcctAddrZip(String acctAddrZip) {
        this.acctAddrZip = acctAddrZip;
    }

    /**
     * Returns the account group identifier ({@code ACCT-GROUP-ID}).
     *
     * @return the 10-character account group identifier, or {@code null} if unset
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * Sets the account group identifier ({@code ACCT-GROUP-ID}).
     *
     * @param acctGroupId the 10-character account group identifier to set
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * <p>This value is managed by the JPA provider; application code typically
     * does not set it directly. A setter is nonetheless provided so the field is
     * a complete JavaBean property.</p>
     *
     * @return the version counter, or {@code null} for a not-yet-persisted entity
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter.
     *
     * <p>Normally managed by the JPA provider; exposed for completeness and for
     * use in tests or detached-entity merges.</p>
     *
     * @param version the version counter to set
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Identity-based equality keyed on the account identifier ({@link #acctId}).
     *
     * <p>Two {@code Account} instances are equal when they are of the exact same
     * class and share the same {@link #acctId}. The primary key alone defines
     * entity identity; mutable business columns are deliberately excluded so
     * equality stays stable across updates. Exact-class comparison (rather than
     * {@code instanceof}) is used so a proxy/subclass is not treated as equal to
     * a different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is an {@code Account} with an equal id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Account account = (Account) o;
        return Objects.equals(acctId, account.acctId);
    }

    /**
     * Hash code derived solely from {@link #acctId}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the account identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }

    /**
     * Diagnostic representation that intentionally <strong>excludes</strong> the
     * monetary fields (balance, credit limits and cycle credit/debit totals) to
     * avoid emitting sensitive financial data into logs. Only the identifier,
     * status, date and grouping fields plus the optimistic-locking version are
     * included.
     *
     * @return a human-readable, non-sensitive description of this account
     */
    @Override
    public String toString() {
        return "Account{"
                + "acctId=" + acctId
                + ", acctActiveStatus='" + acctActiveStatus + '\''
                + ", acctOpenDate='" + acctOpenDate + '\''
                + ", acctExpirationDate='" + acctExpirationDate + '\''
                + ", acctReissueDate='" + acctReissueDate + '\''
                + ", acctAddrZip='" + acctAddrZip + '\''
                + ", acctGroupId='" + acctGroupId + '\''
                + ", version=" + version
                + '}';
    }
}
