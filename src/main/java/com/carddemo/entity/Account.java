package com.carddemo.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * JPA persistent entity for a credit-card <strong>account</strong>.
 *
 * <p>This class is a field-by-field translation of the legacy COBOL copybook
 * {@code app/cpy/CVACT01Y.cpy} ({@code ACCOUNT-RECORD}, fixed record length
 * <strong>300</strong> bytes, source commit SHA {@code 27d6c6f}). It backs the
 * Account View transaction (CAVW / {@code COACTVWC}) and the Account Update
 * transaction (CAUP / {@code COACTUPC}).</p>
 *
 * <h2>Decimal fidelity</h2>
 * <p>Every COBOL monetary field declared {@code PIC S9(10)V99} is mapped to
 * {@link java.math.BigDecimal} with {@code precision = 12, scale = 2}
 * (SQL {@code NUMERIC(12,2)}). Floating-point types ({@code float}/{@code double})
 * are never used for financial values, preserving exact signed decimal
 * semantics per the migration decimal-precision rules.</p>
 *
 * <h2>Optimistic concurrency</h2>
 * <p>{@code COACTUPC} implements a read-then-rewrite update pattern. To preserve
 * that "record changed" concurrency behavior, this entity carries a
 * {@link jakarta.persistence.Version @Version} column ({@link #version}). The
 * version attribute is a database-internal optimistic-lock column only; it is
 * <em>not</em> part of the 300-byte fixed-width record, so byte-equivalent file
 * I/O is unaffected (file writers serialize only the mapped record fields).</p>
 *
 * <h2>Field-name normalization</h2>
 * <p>The COBOL field {@code ACCT-EXPIRAION-DATE} is misspelled in the source
 * copybook. It is normalized here to {@link #acctExpirationDate} (column
 * {@code acct_expiration_date}); this deviation is recorded in
 * {@code docs/traceability-matrix.md}.</p>
 *
 * <h2>Schema contract</h2>
 * <p>This entity is the schema source of truth for the {@code account} table.
 * The Flyway migration {@code db/migration/V1__schema.sql} must match these
 * mappings exactly (column names, types, precision/scale, and lengths) because
 * {@code spring.jpa.hibernate.ddl-auto: validate} verifies them at startup. The
 * natural key {@link #acctId} is application-assigned (no {@code @GeneratedValue}),
 * and the companion repository declares {@code JpaRepository<Account, Long>}.</p>
 *
 * <p>COBOL byte layout (sums to the 300-byte record length):
 * {@code 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + FILLER 178 = 300}.
 * The trailing {@code FILLER PIC X(178)} is intentionally not mapped; trailing
 * padding is handled at the file-I/O boundary.</p>
 */
@Entity
@Table(name = "account")
public class Account {

    /**
     * Account identifier. COBOL {@code ACCT-ID PIC 9(11)}. Application-assigned
     * natural primary key (no {@code @GeneratedValue}).
     */
    @Id
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    /**
     * Active-status indicator. COBOL {@code ACCT-ACTIVE-STATUS PIC X(01)}.
     */
    @Column(name = "acct_active_status", length = 1)
    private String acctActiveStatus;

    /**
     * Current account balance. COBOL {@code ACCT-CURR-BAL PIC S9(10)V99} mapped
     * to a signed {@code NUMERIC(12,2)} value.
     */
    @Column(name = "acct_curr_bal", precision = 12, scale = 2)
    private BigDecimal acctCurrBal;

    /**
     * Total credit limit. COBOL {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} mapped
     * to a signed {@code NUMERIC(12,2)} value.
     */
    @Column(name = "acct_credit_limit", precision = 12, scale = 2)
    private BigDecimal acctCreditLimit;

    /**
     * Cash-advance credit limit. COBOL {@code ACCT-CASH-CREDIT-LIMIT
     * PIC S9(10)V99} mapped to a signed {@code NUMERIC(12,2)} value.
     */
    @Column(name = "acct_cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal acctCashCreditLimit;

    /**
     * Account open date. COBOL {@code ACCT-OPEN-DATE PIC X(10)} (ISO
     * {@code yyyy-MM-dd} text) mapped to {@link java.time.LocalDate}.
     */
    @Column(name = "acct_open_date")
    private LocalDate acctOpenDate;

    /**
     * Account expiration date. COBOL {@code ACCT-EXPIRAION-DATE PIC X(10)}
     * (source misspelling normalized to {@code acctExpirationDate}) mapped to
     * {@link java.time.LocalDate}.
     */
    @Column(name = "acct_expiration_date")
    private LocalDate acctExpirationDate;

    /**
     * Card reissue date. COBOL {@code ACCT-REISSUE-DATE PIC X(10)} mapped to
     * {@link java.time.LocalDate}.
     */
    @Column(name = "acct_reissue_date")
    private LocalDate acctReissueDate;

    /**
     * Current-cycle credit total. COBOL {@code ACCT-CURR-CYC-CREDIT
     * PIC S9(10)V99} mapped to a signed {@code NUMERIC(12,2)} value.
     */
    @Column(name = "acct_curr_cyc_credit", precision = 12, scale = 2)
    private BigDecimal acctCurrCycCredit;

    /**
     * Current-cycle debit total. COBOL {@code ACCT-CURR-CYC-DEBIT
     * PIC S9(10)V99} mapped to a signed {@code NUMERIC(12,2)} value.
     */
    @Column(name = "acct_curr_cyc_debit", precision = 12, scale = 2)
    private BigDecimal acctCurrCycDebit;

    /**
     * Account address ZIP code. COBOL {@code ACCT-ADDR-ZIP PIC X(10)}.
     */
    @Column(name = "acct_addr_zip", length = 10)
    private String acctAddrZip;

    /**
     * Account group identifier. COBOL {@code ACCT-GROUP-ID PIC X(10)}. Retained
     * as a plain scalar (no relationship mapping) to avoid scope expansion.
     */
    @Column(name = "acct_group_id", length = 10)
    private String acctGroupId;

    /**
     * Optimistic-lock version counter managed by JPA. Not part of the 300-byte
     * fixed-width record; used only to reproduce the COBOL read-then-rewrite
     * concurrency semantics of {@code COACTUPC}.
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Default no-argument constructor required by the JPA specification and by
     * the JavaBean accessor contract.
     */
    public Account() {
    }

    /**
     * Returns the application-assigned account identifier.
     *
     * @return the account id (COBOL {@code ACCT-ID}); may be {@code null} before assignment
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the application-assigned account identifier.
     *
     * @param acctId the account id (COBOL {@code ACCT-ID})
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the active-status indicator.
     *
     * @return the active status (COBOL {@code ACCT-ACTIVE-STATUS})
     */
    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    /**
     * Sets the active-status indicator.
     *
     * @param acctActiveStatus the active status (COBOL {@code ACCT-ACTIVE-STATUS})
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    /**
     * Returns the current account balance.
     *
     * @return the current balance (COBOL {@code ACCT-CURR-BAL})
     */
    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    /**
     * Sets the current account balance.
     *
     * @param acctCurrBal the current balance (COBOL {@code ACCT-CURR-BAL})
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    /**
     * Returns the total credit limit.
     *
     * @return the credit limit (COBOL {@code ACCT-CREDIT-LIMIT})
     */
    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    /**
     * Sets the total credit limit.
     *
     * @param acctCreditLimit the credit limit (COBOL {@code ACCT-CREDIT-LIMIT})
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    /**
     * Returns the cash-advance credit limit.
     *
     * @return the cash credit limit (COBOL {@code ACCT-CASH-CREDIT-LIMIT})
     */
    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    /**
     * Sets the cash-advance credit limit.
     *
     * @param acctCashCreditLimit the cash credit limit (COBOL {@code ACCT-CASH-CREDIT-LIMIT})
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    /**
     * Returns the account open date.
     *
     * @return the open date (COBOL {@code ACCT-OPEN-DATE})
     */
    public LocalDate getAcctOpenDate() {
        return acctOpenDate;
    }

    /**
     * Sets the account open date.
     *
     * @param acctOpenDate the open date (COBOL {@code ACCT-OPEN-DATE})
     */
    public void setAcctOpenDate(LocalDate acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    /**
     * Returns the account expiration date.
     *
     * @return the expiration date (COBOL {@code ACCT-EXPIRAION-DATE}, normalized)
     */
    public LocalDate getAcctExpirationDate() {
        return acctExpirationDate;
    }

    /**
     * Sets the account expiration date.
     *
     * @param acctExpirationDate the expiration date (COBOL {@code ACCT-EXPIRAION-DATE}, normalized)
     */
    public void setAcctExpirationDate(LocalDate acctExpirationDate) {
        this.acctExpirationDate = acctExpirationDate;
    }

    /**
     * Returns the card reissue date.
     *
     * @return the reissue date (COBOL {@code ACCT-REISSUE-DATE})
     */
    public LocalDate getAcctReissueDate() {
        return acctReissueDate;
    }

    /**
     * Sets the card reissue date.
     *
     * @param acctReissueDate the reissue date (COBOL {@code ACCT-REISSUE-DATE})
     */
    public void setAcctReissueDate(LocalDate acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    /**
     * Returns the current-cycle credit total.
     *
     * @return the current-cycle credit (COBOL {@code ACCT-CURR-CYC-CREDIT})
     */
    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    /**
     * Sets the current-cycle credit total.
     *
     * @param acctCurrCycCredit the current-cycle credit (COBOL {@code ACCT-CURR-CYC-CREDIT})
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    /**
     * Returns the current-cycle debit total.
     *
     * @return the current-cycle debit (COBOL {@code ACCT-CURR-CYC-DEBIT})
     */
    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    /**
     * Sets the current-cycle debit total.
     *
     * @param acctCurrCycDebit the current-cycle debit (COBOL {@code ACCT-CURR-CYC-DEBIT})
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    /**
     * Returns the account address ZIP code.
     *
     * @return the address ZIP (COBOL {@code ACCT-ADDR-ZIP})
     */
    public String getAcctAddrZip() {
        return acctAddrZip;
    }

    /**
     * Sets the account address ZIP code.
     *
     * @param acctAddrZip the address ZIP (COBOL {@code ACCT-ADDR-ZIP})
     */
    public void setAcctAddrZip(String acctAddrZip) {
        this.acctAddrZip = acctAddrZip;
    }

    /**
     * Returns the account group identifier.
     *
     * @return the group id (COBOL {@code ACCT-GROUP-ID})
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * Sets the account group identifier.
     *
     * @param acctGroupId the group id (COBOL {@code ACCT-GROUP-ID})
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    /**
     * Returns the optimistic-lock version counter.
     *
     * @return the JPA-managed version value; may be {@code null} for a transient instance
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-lock version counter. Normally managed by the JPA
     * provider; a setter is provided for detached-entity merge scenarios.
     *
     * @param version the JPA-managed version value
     */
    public void setVersion(Long version) {
        this.version = version;
    }
}
