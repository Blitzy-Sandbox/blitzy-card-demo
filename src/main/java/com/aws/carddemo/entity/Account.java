/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.entity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code ACCTDAT} VSAM KSDS file's
 * {@code ACCOUNT-RECORD} record described by {@code app/cpy/CVACT01Y.cpy}.
 *
 * <h2>COBOL Provenance — CVACT01Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 300-byte record:
 * <pre>
 *   01 ACCOUNT-RECORD.
 *      05 ACCT-ID                  PIC 9(11).     --&gt; {@link #accountId}         (primary key)
 *      05 ACCT-ACTIVE-STATUS       PIC X(01).     --&gt; {@link #activeStatus}
 *      05 ACCT-CURR-BAL            PIC S9(10)V99. --&gt; {@link #currentBalance}    (BigDecimal scale 2)
 *      05 ACCT-CREDIT-LIMIT        PIC S9(10)V99. --&gt; {@link #creditLimit}       (BigDecimal scale 2)
 *      05 ACCT-CASH-CREDIT-LIMIT   PIC S9(10)V99. --&gt; {@link #cashCreditLimit}   (BigDecimal scale 2)
 *      05 ACCT-OPEN-DATE           PIC X(10).     --&gt; {@link #openDate}          (ISO YYYY-MM-DD)
 *      05 ACCT-EXPIRAION-DATE      PIC X(10).     --&gt; {@link #expirationDate}    (ISO YYYY-MM-DD)
 *      05 ACCT-REISSUE-DATE        PIC X(10).     --&gt; {@link #reissueDate}       (ISO YYYY-MM-DD)
 *      05 ACCT-CURR-CYC-CREDIT     PIC S9(10)V99. --&gt; {@link #currentCycleCredit}(BigDecimal scale 2)
 *      05 ACCT-CURR-CYC-DEBIT      PIC S9(10)V99. --&gt; {@link #currentCycleDebit} (BigDecimal scale 2)
 *      05 ACCT-ADDR-ZIP            PIC X(10).     --&gt; {@link #addressZip}
 *      05 ACCT-GROUP-ID            PIC X(10).     --&gt; {@link #groupId}
 *      05 FILLER                   PIC X(178).
 * </pre>
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>{@link #customerId} is added as a denormalised foreign key for direct
 *       customer lookup. In the COBOL workflow {@code COACTVWC.cbl} obtains
 *       the customer ID indirectly from the {@code CARDAIX} cross-reference
 *       read; the Java migration carries the customer ID on the account
 *       record itself for the more common single-account use cases (the
 *       account/customer relationship is 1:1 in the CardDemo dataset).</li>
 *   <li>{@link #version} is a JPA {@code @Version} field for optimistic
 *       locking — the Java replacement for COBOL's before/after-image record
 *       comparison used by {@code COACTUPC.cbl} (account update). Read-only
 *       lookups in {@link com.aws.carddemo.service.AccountViewService} do
 *       not increment the version, so the field is set but never mutated in
 *       this code path.</li>
 *   <li>All monetary fields are {@link BigDecimal} per AAP §0.10.3
 *       ("No float or double used for any monetary value — BigDecimal
 *       exclusively"). Scale-2 preservation matches COBOL
 *       {@code PIC S9(10)V99} (two digits after the implied decimal
 *       point).</li>
 * </ul>
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.service.AccountViewService} compilation and the
 * account-view test suite. Subsequent migration agents (REFACTOR flavor) will
 * add JPA annotations ({@code @Entity}, {@code @Id}, {@code @Column},
 * {@code @Version}), Bean Validation constraints, and a proper equals/hashCode
 * contract once the entity is wired into the Hibernate {@code SessionFactory}.
 *
 * <h2>Security — toString() Excludes Balances</h2>
 *
 * <p>{@link #toString()} explicitly omits {@link #currentBalance},
 * {@link #creditLimit}, {@link #cashCreditLimit}, {@link #currentCycleCredit},
 * and {@link #currentCycleDebit} so the entity cannot leak monetary values into
 * log output. Per AAP §0.10.5 ("No financial data written to logs at any level").
 *
 * @see com.aws.carddemo.service.AccountViewService
 * @see com.aws.carddemo.repository.AccountRepository
 */
public class Account {

    /**
     * 11-character {@code ACCT-ID} primary key per {@code CVACT01Y.cpy}
     * ({@code PIC 9(11)}). Stored as a fixed-width zero-padded numeric string
     * (e.g. {@code "00000000010"}) to preserve the COBOL key format byte-for-byte
     * across VSAM-to-PostgreSQL migration.
     */
    private String accountId;

    /**
     * Single-character {@code ACCT-ACTIVE-STATUS} flag per {@code CVACT01Y.cpy}
     * ({@code PIC X(01)}). Conventionally {@code 'Y'} (active) or {@code 'N'}
     * (inactive); other values are reserved for future use.
     */
    private String activeStatus;

    /**
     * {@code ACCT-CURR-BAL} — current balance per {@code CVACT01Y.cpy}
     * ({@code PIC S9(10)V99}). Always a {@link BigDecimal} with scale 2 per
     * AAP §0.10.3 financial-precision mandate.
     */
    private BigDecimal currentBalance;

    /**
     * {@code ACCT-CREDIT-LIMIT} — total credit limit per {@code CVACT01Y.cpy}
     * ({@code PIC S9(10)V99}). {@link BigDecimal} scale 2.
     */
    private BigDecimal creditLimit;

    /**
     * {@code ACCT-CASH-CREDIT-LIMIT} — cash advance limit per {@code CVACT01Y.cpy}
     * ({@code PIC S9(10)V99}). {@link BigDecimal} scale 2.
     */
    private BigDecimal cashCreditLimit;

    /**
     * {@code ACCT-CURR-CYC-CREDIT} — running credit total for the current
     * billing cycle per {@code CVACT01Y.cpy} ({@code PIC S9(10)V99}).
     * {@link BigDecimal} scale 2.
     */
    private BigDecimal currentCycleCredit;

    /**
     * {@code ACCT-CURR-CYC-DEBIT} — running debit total for the current billing
     * cycle per {@code CVACT01Y.cpy} ({@code PIC S9(10)V99}).
     * {@link BigDecimal} scale 2.
     */
    private BigDecimal currentCycleDebit;

    /**
     * {@code ACCT-OPEN-DATE} — account opening date per {@code CVACT01Y.cpy}
     * ({@code PIC X(10)}). Stored as an ISO-style {@code YYYY-MM-DD} string to
     * preserve the COBOL record format.
     */
    private String openDate;

    /**
     * {@code ACCT-EXPIRAION-DATE} — account expiration date per
     * {@code CVACT01Y.cpy} ({@code PIC X(10)}). The COBOL field name retains
     * the misspelling ({@code EXPIRAION} instead of {@code EXPIRATION}) for
     * source-of-truth fidelity. The Java field is correctly named
     * {@link #expirationDate}; record-layout serialisation handles the COBOL
     * mapping.
     */
    private String expirationDate;

    /**
     * {@code ACCT-REISSUE-DATE} — most recent card reissue date per
     * {@code CVACT01Y.cpy} ({@code PIC X(10)}). ISO-style {@code YYYY-MM-DD}.
     */
    private String reissueDate;

    /**
     * {@code ACCT-ADDR-ZIP} — postal code of the account billing address per
     * {@code CVACT01Y.cpy} ({@code PIC X(10)}). String to preserve leading
     * zeros (e.g. {@code "00501"} is a valid US ZIP).
     */
    private String addressZip;

    /**
     * {@code ACCT-GROUP-ID} — disclosure group identifier per
     * {@code CVACT01Y.cpy} ({@code PIC X(10)}). Used by {@code CBACT04C} (interest
     * calculation) to look up the discount group's APR rate; {@code "DEFAULT   "}
     * triggers the DEFAULT-group fallback path.
     */
    private String groupId;

    /**
     * Java-migration addition — denormalised customer foreign key. Matches
     * {@code CUST-ID PIC 9(09)} from {@code app/cpy/CUSTREC.cpy}; carried on
     * the account record so {@link com.aws.carddemo.service.AccountViewService}
     * can look up the customer directly without re-reading the
     * {@code CARDAIX} cross-reference.
     */
    private String customerId;

    /**
     * JPA optimistic-locking version. Replaces COBOL's before/after-image record
     * comparison used by {@code COACTUPC.cbl}; incremented automatically by
     * Hibernate on each {@code save()} once the {@code @Version} annotation is
     * added by subsequent REFACTOR-flavor agents. Read-only paths in
     * {@link com.aws.carddemo.service.AccountViewService} do not mutate this
     * field.
     */
    private Long version;

    /** Default no-arg constructor (required by JPA reflection-based instantiation). */
    public Account() {
        // intentionally empty
    }

    /** @return the 11-character zero-padded account identifier */
    public String getAccountId() {
        return accountId;
    }

    /** @param accountId the 11-character zero-padded account identifier */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /** @return the single-character active status flag ({@code 'Y'} or {@code 'N'}) */
    public String getActiveStatus() {
        return activeStatus;
    }

    /** @param activeStatus the single-character active status flag */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /** @return the current balance (BigDecimal, scale 2) */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /** @param currentBalance the current balance (BigDecimal, scale 2) */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    /** @return the total credit limit (BigDecimal, scale 2) */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /** @param creditLimit the total credit limit (BigDecimal, scale 2) */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /** @return the cash advance credit limit (BigDecimal, scale 2) */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /** @param cashCreditLimit the cash advance credit limit (BigDecimal, scale 2) */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /** @return the current-cycle credit total (BigDecimal, scale 2) */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /** @param currentCycleCredit the current-cycle credit total (BigDecimal, scale 2) */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit;
    }

    /** @return the current-cycle debit total (BigDecimal, scale 2) */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /** @param currentCycleDebit the current-cycle debit total (BigDecimal, scale 2) */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit;
    }

    /** @return the account opening date (ISO-style {@code YYYY-MM-DD}) */
    public String getOpenDate() {
        return openDate;
    }

    /** @param openDate the account opening date (ISO-style {@code YYYY-MM-DD}) */
    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    /** @return the account expiration date (ISO-style {@code YYYY-MM-DD}) */
    public String getExpirationDate() {
        return expirationDate;
    }

    /** @param expirationDate the account expiration date (ISO-style {@code YYYY-MM-DD}) */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /** @return the most-recent card reissue date (ISO-style {@code YYYY-MM-DD}) */
    public String getReissueDate() {
        return reissueDate;
    }

    /** @param reissueDate the most-recent card reissue date (ISO-style {@code YYYY-MM-DD}) */
    public void setReissueDate(String reissueDate) {
        this.reissueDate = reissueDate;
    }

    /** @return the billing-address postal code (string to preserve leading zeros) */
    public String getAddressZip() {
        return addressZip;
    }

    /** @param addressZip the billing-address postal code */
    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    /** @return the disclosure-group identifier */
    public String getGroupId() {
        return groupId;
    }

    /** @param groupId the disclosure-group identifier */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /** @return the denormalised customer foreign key */
    public String getCustomerId() {
        return customerId;
    }

    /** @param customerId the denormalised customer foreign key */
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    /** @return the JPA optimistic-locking version */
    public Long getVersion() {
        return version;
    }

    /** @param version the JPA optimistic-locking version */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Equality is based on the primary key {@link #accountId} alone. JPA-managed
     * entities are considered equal iff they share the same primary key value;
     * the version, balances, and other mutable state are deliberately excluded
     * from equality so transient and managed copies of the same logical account
     * compare equal.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account)) {
            return false;
        }
        Account other = (Account) o;
        return Objects.equals(accountId, other.accountId);
    }

    /** Hash by primary key, consistent with {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        return Objects.hash(accountId);
    }

    /**
     * Diagnostic string deliberately omitting monetary fields per AAP §0.10.5
     * ("No financial data written to logs at any level"). Exposes only the
     * primary key, active status, customer foreign key, group ID, and version
     * — all non-financial identifiers safe to surface in operational log lines.
     */
    @Override
    public String toString() {
        return "Account{"
                + "accountId='" + accountId + '\''
                + ", activeStatus='" + activeStatus + '\''
                + ", customerId='" + customerId + '\''
                + ", groupId='" + groupId + '\''
                + ", version=" + version
                + '}';
    }
}
