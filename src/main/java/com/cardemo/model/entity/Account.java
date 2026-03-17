package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL ACCOUNT-RECORD (300 bytes) from CVACT01Y.cpy
 * to the PostgreSQL {@code account} table.
 *
 * <p>This is the central entity for financial account data, referenced by nearly
 * every online and batch program in the CardDemo application. The original COBOL
 * record layout defines 12 data fields plus 178 bytes of FILLER padding.</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <pre>
 * Source: app/cpy/CVACT01Y.cpy (commit 27d6c6f)
 * Record Length: 300 bytes
 * VSAM Dataset: ACCTDAT (KSDS, keyed on ACCT-ID)
 * </pre>
 *
 * <h3>Key Usage Contexts</h3>
 * <ul>
 *   <li>COACTUPC.cbl — Account update with SYNCPOINT ROLLBACK and optimistic
 *       concurrency via before/after record image comparison. Mapped to
 *       {@code @Transactional} with {@code @Version} optimistic locking.</li>
 *   <li>COACTVWC.cbl — Account view with multi-dataset join
 *       (ACCTDAT + CUSTDAT + CXACAIX)</li>
 *   <li>COBIL00C.cbl — Bill payment updating ACCT-CURR-BAL</li>
 *   <li>CBTRN02C.cbl — Batch transaction posting with credit limit checks</li>
 *   <li>CBACT04C.cbl — Interest calculation using account group ID for
 *       disclosure group lookup</li>
 *   <li>CBACT01C.cbl — Account file reader utility</li>
 * </ul>
 *
 * <h3>Decimal Precision</h3>
 * <p>All five financial fields use {@link BigDecimal} with precision=12, scale=2,
 * matching the COBOL PIC S9(10)V99 specification. Zero float/double substitution
 * per AAP decimal precision rules.</p>
 *
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cpy/CVACT01Y.cpy">
 *      CVACT01Y.cpy</a>
 */
@Entity
@Table(name = "accounts")
public class Account {

    /**
     * Account identifier — maps ACCT-ID PIC 9(11).
     * Stored as String to preserve leading zeros (e.g., "00000000001").
     * This is the VSAM KSDS primary key for the ACCTDAT dataset.
     */
    @Id
    @Column(name = "acct_id", length = 11, nullable = false)
    private String acctId;

    /**
     * Account active status — maps ACCT-ACTIVE-STATUS PIC X(01).
     * Values: 'Y' (active) or 'N' (inactive).
     */
    @Column(name = "active_status", length = 1)
    private String acctActiveStatus;

    /**
     * Current account balance — maps ACCT-CURR-BAL PIC S9(10)V99.
     * BigDecimal with precision=12, scale=2 preserves exact COBOL packed decimal
     * semantics. Updated by bill payment (COBIL00C) and batch transaction posting
     * (CBTRN02C).
     */
    @Column(name = "curr_bal", precision = 12, scale = 2)
    private BigDecimal acctCurrBal;

    /**
     * Credit limit — maps ACCT-CREDIT-LIMIT PIC S9(10)V99.
     * Used by CBTRN02C for transaction validation (reject code 102 if exceeded).
     */
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal acctCreditLimit;

    /**
     * Cash credit limit — maps ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99.
     * Separate limit for cash advance transactions.
     */
    @Column(name = "cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal acctCashCreditLimit;

    /**
     * Account open date — maps ACCT-OPEN-DATE PIC X(10).
     * COBOL stores as 'YYYY-MM-DD' string; Java uses proper temporal type.
     */
    @Column(name = "open_date")
    private LocalDate acctOpenDate;

    /**
     * Account expiration date — maps ACCT-EXPIRAION-DATE PIC X(10).
     * Note: The COBOL field name contains a typo ("EXPIRAION" instead of
     * "EXPIRATION"). The Java field name corrects this to {@code acctExpDate}.
     * Used by CBTRN02C for expired card validation (reject code 103).
     */
    @Column(name = "expiration_date")
    private LocalDate acctExpDate;

    /**
     * Account reissue date — maps ACCT-REISSUE-DATE PIC X(10).
     * Date when the account card was last reissued.
     */
    @Column(name = "reissue_date")
    private LocalDate acctReissueDate;

    /**
     * Current cycle credit total — maps ACCT-CURR-CYC-CREDIT PIC S9(10)V99.
     * Accumulated credit (payment) amount for the current billing cycle.
     */
    @Column(name = "curr_cyc_credit", precision = 12, scale = 2)
    private BigDecimal acctCurrCycCredit;

    /**
     * Current cycle debit total — maps ACCT-CURR-CYC-DEBIT PIC S9(10)V99.
     * Accumulated debit (charge) amount for the current billing cycle.
     */
    @Column(name = "curr_cyc_debit", precision = 12, scale = 2)
    private BigDecimal acctCurrCycDebit;

    /**
     * Account address ZIP code — maps ACCT-ADDR-ZIP PIC X(10).
     * Used for address validation (links to CSLKPCDY state/ZIP prefix lookup).
     */
    @Column(name = "addr_zip", length = 10)
    private String acctAddrZip;

    /**
     * Account group identifier — maps ACCT-GROUP-ID PIC X(10).
     * Used for disclosure group interest rate lookup in CBACT04C.
     * Links to the DisclosureGroup entity's groupId for rate determination.
     */
    @Column(name = "group_id", length = 10)
    private String acctGroupId;

    // FILLER PIC X(178) — COBOL record padding only, not mapped to any Java field.

    /**
     * JPA optimistic locking version field.
     *
     * <p>Replaces the COBOL optimistic concurrency pattern in COACTUPC.cbl where
     * before/after record images are compared during account updates. On concurrent
     * modification, JPA throws {@code OptimisticLockException}, equivalent to the
     * COBOL snapshot mismatch detection that triggers SYNCPOINT ROLLBACK.</p>
     *
     * <p>Also critical for concurrent account updates during bill payment
     * (COBIL00C.cbl) and batch transaction posting (CBTRN02C.cbl).</p>
     */
    @Version
    @Column(name = "version")
    private Long version;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * No-args constructor required by JPA specification.
     * Hibernate uses this constructor when materializing entity instances
     * from database result sets.
     */
    public Account() {
    }

    /**
     * All-args constructor for programmatic entity creation.
     * Excludes the {@code version} field which is managed automatically by JPA.
     *
     * @param acctId              account identifier (11-char, leading zeros preserved)
     * @param acctActiveStatus    active status ('Y' or 'N')
     * @param acctCurrBal         current balance (precision=12, scale=2)
     * @param acctCreditLimit     credit limit (precision=12, scale=2)
     * @param acctCashCreditLimit cash credit limit (precision=12, scale=2)
     * @param acctOpenDate        account opening date
     * @param acctExpDate         account expiration date
     * @param acctReissueDate     last card reissue date
     * @param acctCurrCycCredit   current cycle credit total (precision=12, scale=2)
     * @param acctCurrCycDebit    current cycle debit total (precision=12, scale=2)
     * @param acctAddrZip         address ZIP code (up to 10 chars)
     * @param acctGroupId         disclosure group identifier (up to 10 chars)
     */
    public Account(String acctId, String acctActiveStatus, BigDecimal acctCurrBal,
                   BigDecimal acctCreditLimit, BigDecimal acctCashCreditLimit,
                   LocalDate acctOpenDate, LocalDate acctExpDate,
                   LocalDate acctReissueDate, BigDecimal acctCurrCycCredit,
                   BigDecimal acctCurrCycDebit, String acctAddrZip,
                   String acctGroupId) {
        this.acctId = acctId;
        this.acctActiveStatus = acctActiveStatus;
        this.acctCurrBal = acctCurrBal;
        this.acctCreditLimit = acctCreditLimit;
        this.acctCashCreditLimit = acctCashCreditLimit;
        this.acctOpenDate = acctOpenDate;
        this.acctExpDate = acctExpDate;
        this.acctReissueDate = acctReissueDate;
        this.acctCurrCycCredit = acctCurrCycCredit;
        this.acctCurrCycDebit = acctCurrCycDebit;
        this.acctAddrZip = acctAddrZip;
        this.acctGroupId = acctGroupId;
    }

    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------

    /**
     * Returns the account identifier.
     *
     * @return 11-character account ID with preserved leading zeros
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * Sets the account identifier.
     *
     * @param acctId 11-character account ID (leading zeros preserved)
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the account active status.
     *
     * @return 'Y' for active, 'N' for inactive
     */
    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    /**
     * Sets the account active status.
     *
     * @param acctActiveStatus 'Y' for active, 'N' for inactive
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    /**
     * Returns the current account balance.
     *
     * @return current balance as BigDecimal with scale=2
     */
    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    /**
     * Sets the current account balance.
     *
     * @param acctCurrBal current balance (BigDecimal, precision=12, scale=2)
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    /**
     * Returns the credit limit.
     *
     * @return credit limit as BigDecimal with scale=2
     */
    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    /**
     * Sets the credit limit.
     *
     * @param acctCreditLimit credit limit (BigDecimal, precision=12, scale=2)
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    /**
     * Returns the cash credit limit.
     *
     * @return cash credit limit as BigDecimal with scale=2
     */
    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    /**
     * Sets the cash credit limit.
     *
     * @param acctCashCreditLimit cash credit limit (BigDecimal, precision=12, scale=2)
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    /**
     * Returns the account opening date.
     *
     * @return account open date
     */
    public LocalDate getAcctOpenDate() {
        return acctOpenDate;
    }

    /**
     * Sets the account opening date.
     *
     * @param acctOpenDate account opening date
     */
    public void setAcctOpenDate(LocalDate acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    /**
     * Returns the account expiration date.
     * Corrects the COBOL typo ACCT-EXPIRAION-DATE.
     *
     * @return account expiration date
     */
    public LocalDate getAcctExpDate() {
        return acctExpDate;
    }

    /**
     * Sets the account expiration date.
     *
     * @param acctExpDate account expiration date
     */
    public void setAcctExpDate(LocalDate acctExpDate) {
        this.acctExpDate = acctExpDate;
    }

    /**
     * Returns the account reissue date.
     *
     * @return date when the account card was last reissued
     */
    public LocalDate getAcctReissueDate() {
        return acctReissueDate;
    }

    /**
     * Sets the account reissue date.
     *
     * @param acctReissueDate date when the account card was last reissued
     */
    public void setAcctReissueDate(LocalDate acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    /**
     * Returns the current cycle credit total.
     *
     * @return accumulated credit amount for the current billing cycle
     */
    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    /**
     * Sets the current cycle credit total.
     *
     * @param acctCurrCycCredit accumulated credit for current billing cycle
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    /**
     * Returns the current cycle debit total.
     *
     * @return accumulated debit amount for the current billing cycle
     */
    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    /**
     * Sets the current cycle debit total.
     *
     * @param acctCurrCycDebit accumulated debit for current billing cycle
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    /**
     * Returns the account address ZIP code.
     *
     * @return ZIP code (up to 10 characters)
     */
    public String getAcctAddrZip() {
        return acctAddrZip;
    }

    /**
     * Sets the account address ZIP code.
     *
     * @param acctAddrZip ZIP code (up to 10 characters)
     */
    public void setAcctAddrZip(String acctAddrZip) {
        this.acctAddrZip = acctAddrZip;
    }

    /**
     * Returns the account group identifier.
     *
     * @return group ID used for disclosure group interest rate lookup
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * Sets the account group identifier.
     *
     * @param acctGroupId group ID for disclosure group interest rate lookup
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    /**
     * Returns the JPA optimistic locking version.
     *
     * @return version number managed by JPA
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the JPA optimistic locking version.
     * Normally managed by JPA; setter provided for testing scenarios.
     *
     * @param version version number
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    // -------------------------------------------------------------------------
    // equals, hashCode, toString
    // -------------------------------------------------------------------------

    /**
     * Entity equality based on the primary key ({@code acctId}) only.
     * The {@code version} field is intentionally excluded from equality comparison
     * to prevent issues with JPA persistence context identity and collections.
     *
     * @param o the object to compare
     * @return true if the objects represent the same account entity
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
     * Hash code based on the primary key ({@code acctId}) only.
     * Consistent with {@link #equals(Object)} contract.
     *
     * @return hash code derived from acctId
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }

    /**
     * String representation including all entity fields for debugging and logging.
     * Financial amounts are rendered with their full BigDecimal representation
     * to aid in precision verification during migration validation.
     *
     * @return formatted string with all field values
     */
    @Override
    public String toString() {
        return "Account{"
                + "acctId='" + acctId + '\''
                + ", acctActiveStatus='" + acctActiveStatus + '\''
                + ", acctCurrBal=" + acctCurrBal
                + ", acctCreditLimit=" + acctCreditLimit
                + ", acctCashCreditLimit=" + acctCashCreditLimit
                + ", acctOpenDate=" + acctOpenDate
                + ", acctExpDate=" + acctExpDate
                + ", acctReissueDate=" + acctReissueDate
                + ", acctCurrCycCredit=" + acctCurrCycCredit
                + ", acctCurrCycDebit=" + acctCurrCycDebit
                + ", acctAddrZip='" + acctAddrZip + '\''
                + ", acctGroupId='" + acctGroupId + '\''
                + ", version=" + version
                + '}';
    }
}
