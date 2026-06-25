/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for the CardDemo {@code ACCOUNT-RECORD}.
 *
 * <p>Translated (never copied) from COBOL copybook {@code app/cpy/CVACT01Y.cpy}
 * (record length 300) at source commit SHA {@code 27d6c6f}. Every persistent
 * field maps one-to-one to a column of the {@code accounts} table defined in
 * {@code db/migration/V1__create_schema.sql}; the trailing COBOL
 * {@code FILLER PIC X(178)} carries no data and is intentionally not mapped.</p>
 *
 * <p>The five monetary fields originate from COBOL {@code PIC S9(10)V99}
 * (packed-decimal {@code COMP-3}) and are modelled as {@link BigDecimal} with
 * precision 12 and scale 2; {@code double}/{@code float} are never used so that
 * decimal arithmetic stays exact. Monetary rounding
 * ({@link java.math.RoundingMode#HALF_EVEN}) is applied by the service layer,
 * never by this entity.</p>
 *
 * <p>The {@link #version} field backs JPA optimistic locking, reproducing the
 * legacy {@code 9300-CHECK-CHANGE-IN-REC} re-read-and-compare guard used by the
 * account-update flow. The column name {@code expiraion_date} preserves the
 * misspelling present in the COBOL field {@code ACCT-EXPIRAION-DATE} and in the
 * {@code accounts} table, even though the Java property is correctly spelled
 * {@code expirationDate}.</p>
 */
@Entity
@Table(name = "accounts")
public class Account implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@code ACCT-ID PIC 9(11)} &rarr; primary key {@code acct_id BIGINT}. */
    @Id
    @Column(name = "acct_id")
    private Long acctId;

    /**
     * {@code ACCT-ACTIVE-STATUS PIC X(01)} &rarr; {@code active_status CHAR(1)}.
     * {@link JdbcTypeCode}({@link SqlTypes#CHAR}) pins the JDBC type to
     * {@code CHAR} so Hibernate schema-validation accepts the fixed-length
     * {@code CHAR(1)} column (a plain {@link String} would default to
     * {@code VARCHAR}).
     */
    @Column(name = "active_status", length = 1, columnDefinition = "char(1)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String activeStatus;

    /** {@code ACCT-CURR-BAL PIC S9(10)V99} &rarr; {@code curr_bal NUMERIC(12,2)}. */
    @Column(name = "curr_bal", precision = 12, scale = 2)
    private BigDecimal currBal;

    /** {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} &rarr; {@code credit_limit NUMERIC(12,2)}. */
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /** {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} &rarr; {@code cash_credit_limit NUMERIC(12,2)}. */
    @Column(name = "cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    /** {@code ACCT-OPEN-DATE PIC X(10)} &rarr; {@code open_date VARCHAR(10)}. */
    @Column(name = "open_date", length = 10)
    private String openDate;

    /**
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} &rarr; {@code expiraion_date VARCHAR(10)}.
     * The column name intentionally keeps the COBOL/DB misspelling.
     */
    @Column(name = "expiraion_date", length = 10)
    private String expirationDate;

    /** {@code ACCT-REISSUE-DATE PIC X(10)} &rarr; {@code reissue_date VARCHAR(10)}. */
    @Column(name = "reissue_date", length = 10)
    private String reissueDate;

    /** {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} &rarr; {@code curr_cyc_credit NUMERIC(12,2)}. */
    @Column(name = "curr_cyc_credit", precision = 12, scale = 2)
    private BigDecimal currCycCredit;

    /** {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} &rarr; {@code curr_cyc_debit NUMERIC(12,2)}. */
    @Column(name = "curr_cyc_debit", precision = 12, scale = 2)
    private BigDecimal currCycDebit;

    /** {@code ACCT-ADDR-ZIP PIC X(10)} &rarr; {@code addr_zip VARCHAR(10)}. */
    @Column(name = "addr_zip", length = 10)
    private String addrZip;

    /** {@code ACCT-GROUP-ID PIC X(10)} &rarr; {@code group_id VARCHAR(10)}. */
    @Column(name = "group_id", length = 10)
    private String groupId;

    /** Optimistic-lock version counter &rarr; {@code version BIGINT NOT NULL DEFAULT 0}. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Default no-argument constructor required by the JPA specification.
     */
    public Account() {
        // Intentionally empty: JPA requires an accessible no-argument constructor.
    }

    /**
     * Creates a fully populated account record across all 13 persistent fields.
     *
     * @param acctId          the account identifier (primary key)
     * @param activeStatus    the single-character active-status flag
     * @param currBal         the current balance
     * @param creditLimit     the credit limit
     * @param cashCreditLimit the cash credit limit
     * @param openDate        the account open date ({@code YYYY-MM-DD})
     * @param expirationDate  the account expiration date ({@code YYYY-MM-DD})
     * @param reissueDate     the account reissue date ({@code YYYY-MM-DD})
     * @param currCycCredit   the current-cycle credit total
     * @param currCycDebit    the current-cycle debit total
     * @param addrZip         the address ZIP code
     * @param groupId         the account group identifier
     * @param version         the optimistic-lock version counter
     */
    public Account(Long acctId, String activeStatus, BigDecimal currBal, BigDecimal creditLimit,
                   BigDecimal cashCreditLimit, String openDate, String expirationDate,
                   String reissueDate, BigDecimal currCycCredit, BigDecimal currCycDebit,
                   String addrZip, String groupId, Long version) {
        this.acctId = acctId;
        this.activeStatus = activeStatus;
        this.currBal = currBal;
        this.creditLimit = creditLimit;
        this.cashCreditLimit = cashCreditLimit;
        this.openDate = openDate;
        this.expirationDate = expirationDate;
        this.reissueDate = reissueDate;
        this.currCycCredit = currCycCredit;
        this.currCycDebit = currCycDebit;
        this.addrZip = addrZip;
        this.groupId = groupId;
        this.version = version;
    }

    /**
     * Returns the account identifier (primary key).
     *
     * @return the account identifier
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the account identifier (primary key).
     *
     * @param acctId the account identifier
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the single-character active-status flag.
     *
     * @return the active-status flag
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the single-character active-status flag.
     *
     * @param activeStatus the active-status flag
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the current balance.
     *
     * @return the current balance
     */
    public BigDecimal getCurrBal() {
        return currBal;
    }

    /**
     * Sets the current balance.
     *
     * @param currBal the current balance
     */
    public void setCurrBal(BigDecimal currBal) {
        this.currBal = currBal;
    }

    /**
     * Returns the credit limit.
     *
     * @return the credit limit
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Sets the credit limit.
     *
     * @param creditLimit the credit limit
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * Returns the cash credit limit.
     *
     * @return the cash credit limit
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * Sets the cash credit limit.
     *
     * @param cashCreditLimit the cash credit limit
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * Returns the account open date.
     *
     * @return the account open date
     */
    public String getOpenDate() {
        return openDate;
    }

    /**
     * Sets the account open date.
     *
     * @param openDate the account open date
     */
    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    /**
     * Returns the account expiration date. The backing column
     * {@code expiraion_date} keeps the COBOL/DB misspelling.
     *
     * @return the account expiration date
     */
    public String getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the account expiration date. The backing column
     * {@code expiraion_date} keeps the COBOL/DB misspelling.
     *
     * @param expirationDate the account expiration date
     */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the account reissue date.
     *
     * @return the account reissue date
     */
    public String getReissueDate() {
        return reissueDate;
    }

    /**
     * Sets the account reissue date.
     *
     * @param reissueDate the account reissue date
     */
    public void setReissueDate(String reissueDate) {
        this.reissueDate = reissueDate;
    }

    /**
     * Returns the current-cycle credit total.
     *
     * @return the current-cycle credit total
     */
    public BigDecimal getCurrCycCredit() {
        return currCycCredit;
    }

    /**
     * Sets the current-cycle credit total.
     *
     * @param currCycCredit the current-cycle credit total
     */
    public void setCurrCycCredit(BigDecimal currCycCredit) {
        this.currCycCredit = currCycCredit;
    }

    /**
     * Returns the current-cycle debit total.
     *
     * @return the current-cycle debit total
     */
    public BigDecimal getCurrCycDebit() {
        return currCycDebit;
    }

    /**
     * Sets the current-cycle debit total.
     *
     * @param currCycDebit the current-cycle debit total
     */
    public void setCurrCycDebit(BigDecimal currCycDebit) {
        this.currCycDebit = currCycDebit;
    }

    /**
     * Returns the address ZIP code.
     *
     * @return the address ZIP code
     */
    public String getAddrZip() {
        return addrZip;
    }

    /**
     * Sets the address ZIP code.
     *
     * @param addrZip the address ZIP code
     */
    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    /**
     * Returns the account group identifier.
     *
     * @return the account group identifier
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the account group identifier.
     *
     * @param groupId the account group identifier
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Returns the optimistic-lock version counter.
     *
     * @return the version counter
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-lock version counter. This value is managed by the
     * JPA provider and is exposed for completeness and testing.
     *
     * @param version the version counter
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares two accounts on their identity ({@code acctId}) only, in keeping
     * with JPA entity-equality best practice. The optimistic-lock
     * {@code version} and all other attributes are deliberately excluded.
     *
     * @param o the object to compare with
     * @return {@code true} when both are accounts with an equal, non-null identifier
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Account other = (Account) o;
        return Objects.equals(acctId, other.acctId);
    }

    /**
     * Returns a hash code derived from the identifier ({@code acctId}) only,
     * consistent with {@link #equals(Object)}.
     *
     * @return the identity-based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }
}
