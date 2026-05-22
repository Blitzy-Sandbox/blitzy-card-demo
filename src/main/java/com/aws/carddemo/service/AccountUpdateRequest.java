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
package com.aws.carddemo.service;

import com.aws.carddemo.entity.Account;
import com.aws.carddemo.entity.Customer;

import java.math.BigDecimal;

/**
 * Mutable request DTO for
 * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)} — the Java
 * replacement for the {@code COACTUPC.cbl} input record built from the
 * {@code CACTUPA} BMS map fields plus the {@code ACCT-UPDATE-RECORD} and
 * {@code CUST-UPDATE-RECORD} structures
 * ({@code app/cbl/COACTUPC.cbl} lines 418–456). Carries the operator-supplied
 * account-and-customer dual-update payload from the REST controller layer
 * into the service.
 *
 * <h2>COBOL Provenance — ACCT-UPDATE-RECORD ({@code COACTUPC.cbl} 418–432)</h2>
 *
 * <p>The COBOL account-update record layout this DTO mirrors:
 * <pre>
 *   05 ACCT-UPDATE-RECORD.
 *      15 ACCT-UPDATE-ID                 PIC 9(11).     --&gt; {@link #accountId}
 *      15 ACCT-UPDATE-ACTIVE-STATUS      PIC X(01).     --&gt; {@link #accountActiveStatus}
 *      15 ACCT-UPDATE-CURR-BAL           PIC S9(10)V99. --&gt; {@link #currentBalance}
 *      15 ACCT-UPDATE-CREDIT-LIMIT       PIC S9(10)V99. --&gt; {@link #creditLimit}
 *      15 ACCT-UPDATE-CASH-CREDIT-LIMIT  PIC S9(10)V99. --&gt; {@link #cashCreditLimit}
 *      15 ACCT-UPDATE-OPEN-DATE          PIC X(10).     --&gt; {@link #openDate}
 *      15 ACCT-UPDATE-EXPIRAION-DATE     PIC X(10).     --&gt; {@link #expirationDate}
 *      15 ACCT-UPDATE-REISSUE-DATE       PIC X(10).     --&gt; {@link #reissueDate}
 *      15 ACCT-UPDATE-CURR-CYC-CREDIT    PIC S9(10)V99. --&gt; {@link #currentCycleCredit}
 *      15 ACCT-UPDATE-CURR-CYC-DEBIT     PIC S9(10)V99. --&gt; {@link #currentCycleDebit}
 *      15 ACCT-UPDATE-GROUP-ID           PIC X(10).     --&gt; {@link #groupId}
 * </pre>
 *
 * <h2>COBOL Provenance — CUST-UPDATE-RECORD ({@code COACTUPC.cbl} 434–456)</h2>
 *
 * <p>The COBOL customer-update record layout this DTO also mirrors:
 * <pre>
 *   05 CUST-UPDATE-RECORD.
 *      15 CUST-UPDATE-ID                 PIC 9(09).  --&gt; {@link #customerId}
 *      15 CUST-UPDATE-FIRST-NAME         PIC X(25).  --&gt; {@link #firstName}
 *      15 CUST-UPDATE-MIDDLE-NAME        PIC X(25).  --&gt; {@link #middleName}
 *      15 CUST-UPDATE-LAST-NAME          PIC X(25).  --&gt; {@link #lastName}
 *      15 CUST-UPDATE-ADDR-LINE-1        PIC X(50).  --&gt; {@link #addressLine1}
 *      15 CUST-UPDATE-ADDR-LINE-2        PIC X(50).  --&gt; {@link #addressLine2}
 *      15 CUST-UPDATE-ADDR-LINE-3        PIC X(50).  --&gt; {@link #addressLine3}
 *      15 CUST-UPDATE-ADDR-STATE-CD      PIC X(02).  --&gt; {@link #stateCode}
 *      15 CUST-UPDATE-ADDR-COUNTRY-CD    PIC X(03).  --&gt; {@link #countryCode}
 *      15 CUST-UPDATE-ADDR-ZIP           PIC X(10).  --&gt; {@link #zipCode}
 *      15 CUST-UPDATE-PHONE-NUM-1        PIC X(15).  --&gt; {@link #phoneNumber1}
 *      15 CUST-UPDATE-PHONE-NUM-2        PIC X(15).  --&gt; {@link #phoneNumber2}
 *      15 CUST-UPDATE-SSN                PIC 9(09).  --&gt; {@link #ssn}
 *      15 CUST-UPDATE-GOVT-ISSUED-ID     PIC X(20).  --&gt; {@link #governmentIssuedId}
 *      15 CUST-UPDATE-DOB-YYYY-MM-DD     PIC X(10).  --&gt; {@link #dateOfBirth}
 *      15 CUST-UPDATE-EFT-ACCOUNT-ID     PIC X(10).  --&gt; {@link #eftAccountId}
 *      15 CUST-UPDATE-PRI-CARD-IND       PIC X(01).  --&gt; {@link #primaryCardHolderIndicator}
 *      15 CUST-UPDATE-FICO-CREDIT-SCORE  PIC 9(03).  --&gt; {@link #ficoCreditScore}
 * </pre>
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>{@link #accountVersion} carries the JPA optimistic-locking version
 *       counter loaded from {@link Account#getVersion()} when the controller
 *       served the operator's edit screen. The {@link AccountUpdateService}
 *       does not inspect this field directly; JPA's {@code @Version} contract
 *       on the loaded {@link Account} entity is the actual source of truth.
 *       Carried on the DTO for controller-layer round-trip semantics.</li>
 *   <li>{@link #customerVersion} carries the same optimistic-locking counter
 *       loaded from {@link Customer#getVersion()}; serves the same controller-
 *       layer purpose for the customer dual-write half of the COACTUPC flow.</li>
 *   <li>All monetary fields are {@link BigDecimal} per AAP §0.10.3
 *       ("No float or double used for any monetary value — BigDecimal
 *       exclusively"). Scale-2 preservation matches COBOL
 *       {@code PIC S9(10)V99}.</li>
 * </ul>
 *
 * <h2>Validation Contract</h2>
 *
 * <p>This DTO is a pure value carrier and performs no validation. All field
 * validation is centralised in
 * {@link AccountUpdateService#updateAccount(AccountUpdateRequest)} so the test
 * suite can exercise the validation cascade through a single entry point per
 * AAP §0.10.1 (Require Test Coverage rule — tests call the production service
 * directly). The expected reject paths and their COBOL provenance are
 * enumerated in {@link AccountUpdateResult}'s class-level Javadoc.
 *
 * <h2>Mutability — Setter-Based Construction</h2>
 *
 * <p>The class uses setter-based mutation (rather than a builder or immutable
 * record) so it integrates cleanly with JSON deserialization frameworks
 * (Jackson's default no-arg constructor + setter binding) without additional
 * annotations. This matches the convention used by {@link CardUpdateRequest},
 * {@link UserUpdateRequest}, and the rest of the service-request DTOs in
 * this package.
 *
 * <h2>Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link AccountUpdateService} compilation and the
 * {@code AccountUpdateServiceTest} unit test suite. Subsequent migration
 * agents (REFACTOR flavor) may add Bean Validation constraints
 * ({@code @NotBlank}, {@code @Pattern}, {@code @DecimalMin}) and Jackson
 * annotations once the REST controller layer is wired up.
 *
 * @see AccountUpdateService
 * @see AccountUpdateResult
 * @see Account
 * @see Customer
 */
public class AccountUpdateRequest {

    // -------------------------------------------------------------------
    // Account-master fields (mirrors COBOL ACCT-UPDATE-RECORD, lines 418-432)
    // -------------------------------------------------------------------

    /**
     * 11-character {@code ACCT-UPDATE-ID} per {@code COACTUPC.cbl} line 422
     * ({@code PIC 9(11)}) — the account record's primary key. Zero-padded
     * numeric string (e.g. {@code "00000000010"}).
     */
    private String accountId;

    /**
     * Single-character {@code ACCT-UPDATE-ACTIVE-STATUS} per
     * {@code COACTUPC.cbl} line 423 ({@code PIC X(01)}) — conventionally
     * {@code 'Y'} (active) or {@code 'N'} (inactive). The service rejects
     * any value outside this domain per the COBOL
     * {@code ACCT-STATUS-MUST-BE-YES-NO} reject literal at line 506.
     */
    private String accountActiveStatus;

    /**
     * {@code ACCT-UPDATE-CURR-BAL} per {@code COACTUPC.cbl} line 424
     * ({@code PIC S9(10)V99}). {@link BigDecimal} scale 2 per AAP §0.10.3
     * financial-precision mandate.
     */
    private BigDecimal currentBalance;

    /**
     * {@code ACCT-UPDATE-CREDIT-LIMIT} per {@code COACTUPC.cbl} line 425
     * ({@code PIC S9(10)V99}). {@link BigDecimal} scale 2. The service
     * rejects negative values per the COBOL {@code CRED-LIMIT-IS-NOT-VALID}
     * reject literal at line 510.
     */
    private BigDecimal creditLimit;

    /**
     * {@code ACCT-UPDATE-CASH-CREDIT-LIMIT} per {@code COACTUPC.cbl} line
     * 426 ({@code PIC S9(10)V99}). {@link BigDecimal} scale 2.
     */
    private BigDecimal cashCreditLimit;

    /**
     * {@code ACCT-UPDATE-CURR-CYC-CREDIT} per {@code COACTUPC.cbl} line 430
     * ({@code PIC S9(10)V99}) — running credit total for the current billing
     * cycle. {@link BigDecimal} scale 2.
     */
    private BigDecimal currentCycleCredit;

    /**
     * {@code ACCT-UPDATE-CURR-CYC-DEBIT} per {@code COACTUPC.cbl} line 431
     * ({@code PIC S9(10)V99}) — running debit total for the current billing
     * cycle. {@link BigDecimal} scale 2.
     */
    private BigDecimal currentCycleDebit;

    /**
     * {@code ACCT-UPDATE-OPEN-DATE} per {@code COACTUPC.cbl} line 427
     * ({@code PIC X(10)}) — account opening date in ISO {@code YYYY-MM-DD}
     * format.
     */
    private String openDate;

    /**
     * {@code ACCT-UPDATE-EXPIRAION-DATE} per {@code COACTUPC.cbl} line 428
     * ({@code PIC X(10)}) — account expiration date in ISO {@code YYYY-MM-DD}
     * format. The COBOL field name retains the misspelling
     * ({@code EXPIRAION} instead of {@code EXPIRATION}); the Java field uses
     * the correctly spelled {@code expirationDate}.
     */
    private String expirationDate;

    /**
     * {@code ACCT-UPDATE-REISSUE-DATE} per {@code COACTUPC.cbl} line 429
     * ({@code PIC X(10)}) — most recent card reissue date in ISO
     * {@code YYYY-MM-DD} format.
     */
    private String reissueDate;

    /**
     * {@code ACCT-UPDATE-GROUP-ID} per {@code COACTUPC.cbl} line 432
     * ({@code PIC X(10)}) — disclosure-group identifier used by
     * {@code CBACT04C} (interest calculation) to look up the APR rate;
     * {@code "DEFAULT   "} triggers the DEFAULT-group fallback path.
     */
    private String groupId;

    // -------------------------------------------------------------------
    // Customer-master fields (mirrors COBOL CUST-UPDATE-RECORD, lines 434-456)
    // -------------------------------------------------------------------

    /**
     * 9-character {@code CUST-UPDATE-ID} per {@code COACTUPC.cbl} line 438
     * ({@code PIC 9(09)}) — the customer record's primary key. Zero-padded
     * numeric string (e.g. {@code "000000001"}).
     */
    private String customerId;

    /** {@code CUST-UPDATE-FIRST-NAME} per {@code COACTUPC.cbl} ({@code PIC X(25)}). */
    private String firstName;

    /** {@code CUST-UPDATE-MIDDLE-NAME} per {@code COACTUPC.cbl} ({@code PIC X(25)}). */
    private String middleName;

    /** {@code CUST-UPDATE-LAST-NAME} per {@code COACTUPC.cbl} ({@code PIC X(25)}). */
    private String lastName;

    /** {@code CUST-UPDATE-ADDR-LINE-1} per {@code COACTUPC.cbl} ({@code PIC X(50)}). */
    private String addressLine1;

    /** {@code CUST-UPDATE-ADDR-LINE-2} per {@code COACTUPC.cbl} ({@code PIC X(50)}). */
    private String addressLine2;

    /** {@code CUST-UPDATE-ADDR-LINE-3} per {@code COACTUPC.cbl} ({@code PIC X(50)}); city per COBOL convention. */
    private String addressLine3;

    /** {@code CUST-UPDATE-ADDR-STATE-CD} per {@code COACTUPC.cbl} ({@code PIC X(02)}). */
    private String stateCode;

    /** {@code CUST-UPDATE-ADDR-COUNTRY-CD} per {@code COACTUPC.cbl} ({@code PIC X(03)}). */
    private String countryCode;

    /** {@code CUST-UPDATE-ADDR-ZIP} per {@code COACTUPC.cbl} ({@code PIC X(10)}). */
    private String zipCode;

    /**
     * {@code CUST-UPDATE-PHONE-NUM-1} per {@code COACTUPC.cbl} ({@code PIC
     * X(15)}) — formatted as {@code "(NNN)NNN-NNNN"}. The service rejects
     * any phone whose area-code (first three digits between the parentheses)
     * equals {@code "000"} per the COBOL {@code WS-EDIT-US-PHONE-IS-INVALID}
     * 88-level condition at line 102.
     */
    private String phoneNumber1;

    /** {@code CUST-UPDATE-PHONE-NUM-2} per {@code COACTUPC.cbl} ({@code PIC X(15)}). */
    private String phoneNumber2;

    /**
     * {@code CUST-UPDATE-SSN} per {@code COACTUPC.cbl} ({@code PIC 9(09)}) —
     * the Social Security Number as a 9-digit string. The service rejects
     * any SSN whose first three digits ({@code WS-EDIT-US-SSN-PART1}) equal
     * {@code "000"}, {@code "666"}, or fall in the range {@code "900"} through
     * {@code "999"} per the COBOL {@code INVALID-SSN-PART1} 88-level condition
     * at line 121.
     */
    private String ssn;

    /** {@code CUST-UPDATE-GOVT-ISSUED-ID} per {@code COACTUPC.cbl} ({@code PIC X(20)}). */
    private String governmentIssuedId;

    /** {@code CUST-UPDATE-DOB-YYYY-MM-DD} per {@code COACTUPC.cbl} ({@code PIC X(10)}). */
    private String dateOfBirth;

    /** {@code CUST-UPDATE-EFT-ACCOUNT-ID} per {@code COACTUPC.cbl} ({@code PIC X(10)}). */
    private String eftAccountId;

    /** {@code CUST-UPDATE-PRI-CARD-IND} per {@code COACTUPC.cbl} ({@code PIC X(01)}). */
    private String primaryCardHolderIndicator;

    /** {@code CUST-UPDATE-FICO-CREDIT-SCORE} per {@code COACTUPC.cbl} ({@code PIC 9(03)}). */
    private Integer ficoCreditScore;

    // -------------------------------------------------------------------
    // Java-migration additions — optimistic-locking versions
    // -------------------------------------------------------------------

    /**
     * JPA optimistic-locking version counter loaded from
     * {@link Account#getVersion()} when the controller served the operator's
     * edit screen; may be {@code null}.
     */
    private Long accountVersion;

    /**
     * JPA optimistic-locking version counter loaded from
     * {@link Customer#getVersion()} when the controller served the operator's
     * edit screen; may be {@code null}.
     */
    private Long customerVersion;

    /**
     * Default no-arg constructor — required by Jackson's default
     * deserialization contract and by reflection-based test frameworks.
     */
    public AccountUpdateRequest() {
        // intentionally empty
    }

    // -------------------------------------------------------------------
    // Getters / setters — Account fields
    // -------------------------------------------------------------------

    /** @return the 11-character zero-padded account identifier */
    public String getAccountId() {
        return accountId;
    }

    /** @param accountId the 11-character zero-padded account identifier */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /** @return the single-character active status flag ({@code 'Y'} or {@code 'N'}) */
    public String getAccountActiveStatus() {
        return accountActiveStatus;
    }

    /** @param accountActiveStatus the single-character active status flag */
    public void setAccountActiveStatus(String accountActiveStatus) {
        this.accountActiveStatus = accountActiveStatus;
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

    /** @return the account opening date (ISO {@code YYYY-MM-DD}) */
    public String getOpenDate() {
        return openDate;
    }

    /** @param openDate the account opening date (ISO {@code YYYY-MM-DD}) */
    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    /** @return the account expiration date (ISO {@code YYYY-MM-DD}) */
    public String getExpirationDate() {
        return expirationDate;
    }

    /** @param expirationDate the account expiration date (ISO {@code YYYY-MM-DD}) */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /** @return the most-recent card reissue date (ISO {@code YYYY-MM-DD}) */
    public String getReissueDate() {
        return reissueDate;
    }

    /** @param reissueDate the most-recent card reissue date (ISO {@code YYYY-MM-DD}) */
    public void setReissueDate(String reissueDate) {
        this.reissueDate = reissueDate;
    }

    /** @return the disclosure-group identifier */
    public String getGroupId() {
        return groupId;
    }

    /** @param groupId the disclosure-group identifier */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    // -------------------------------------------------------------------
    // Getters / setters — Customer fields
    // -------------------------------------------------------------------

    /** @return the 9-character zero-padded customer identifier */
    public String getCustomerId() {
        return customerId;
    }

    /** @param customerId the 9-character zero-padded customer identifier */
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    /** @return the customer first name */
    public String getFirstName() {
        return firstName;
    }

    /** @param firstName the customer first name */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /** @return the customer middle name */
    public String getMiddleName() {
        return middleName;
    }

    /** @param middleName the customer middle name */
    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    /** @return the customer last name */
    public String getLastName() {
        return lastName;
    }

    /** @param lastName the customer last name */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /** @return address line 1 (street address) */
    public String getAddressLine1() {
        return addressLine1;
    }

    /** @param addressLine1 address line 1 (street address) */
    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    /** @return address line 2 (apartment / unit / suite) */
    public String getAddressLine2() {
        return addressLine2;
    }

    /** @param addressLine2 address line 2 (apartment / unit / suite) */
    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    /** @return address line 3 (city per COBOL convention) */
    public String getAddressLine3() {
        return addressLine3;
    }

    /** @param addressLine3 address line 3 (city per COBOL convention) */
    public void setAddressLine3(String addressLine3) {
        this.addressLine3 = addressLine3;
    }

    /** @return the 2-character state code */
    public String getStateCode() {
        return stateCode;
    }

    /** @param stateCode the 2-character state code */
    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    /** @return the 3-character country code */
    public String getCountryCode() {
        return countryCode;
    }

    /** @param countryCode the 3-character country code */
    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    /** @return the postal code (string to preserve leading zeros) */
    public String getZipCode() {
        return zipCode;
    }

    /** @param zipCode the postal code (string to preserve leading zeros) */
    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }

    /** @return the primary phone number (formatted as {@code "(NNN)NNN-NNNN"}) */
    public String getPhoneNumber1() {
        return phoneNumber1;
    }

    /** @param phoneNumber1 the primary phone number */
    public void setPhoneNumber1(String phoneNumber1) {
        this.phoneNumber1 = phoneNumber1;
    }

    /** @return the secondary phone number */
    public String getPhoneNumber2() {
        return phoneNumber2;
    }

    /** @param phoneNumber2 the secondary phone number */
    public void setPhoneNumber2(String phoneNumber2) {
        this.phoneNumber2 = phoneNumber2;
    }

    /** @return the Social Security Number (9-digit numeric string) */
    public String getSsn() {
        return ssn;
    }

    /** @param ssn the Social Security Number (9-digit numeric string) */
    public void setSsn(String ssn) {
        this.ssn = ssn;
    }

    /** @return the government-issued identifier */
    public String getGovernmentIssuedId() {
        return governmentIssuedId;
    }

    /** @param governmentIssuedId the government-issued identifier */
    public void setGovernmentIssuedId(String governmentIssuedId) {
        this.governmentIssuedId = governmentIssuedId;
    }

    /** @return the date of birth (ISO {@code YYYY-MM-DD}) */
    public String getDateOfBirth() {
        return dateOfBirth;
    }

    /** @param dateOfBirth the date of birth (ISO {@code YYYY-MM-DD}) */
    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    /** @return the EFT account identifier (bank account for autopay) */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /** @param eftAccountId the EFT account identifier */
    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    /** @return the primary card-holder indicator ({@code 'Y'} or {@code 'N'}) */
    public String getPrimaryCardHolderIndicator() {
        return primaryCardHolderIndicator;
    }

    /** @param primaryCardHolderIndicator the primary card-holder indicator */
    public void setPrimaryCardHolderIndicator(String primaryCardHolderIndicator) {
        this.primaryCardHolderIndicator = primaryCardHolderIndicator;
    }

    /** @return the FICO credit score (300-850; nullable) */
    public Integer getFicoCreditScore() {
        return ficoCreditScore;
    }

    /** @param ficoCreditScore the FICO credit score (300-850; nullable) */
    public void setFicoCreditScore(Integer ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }

    // -------------------------------------------------------------------
    // Getters / setters — Java-migration additions
    // -------------------------------------------------------------------

    /** @return the JPA optimistic-locking version for the account, or {@code null} */
    public Long getAccountVersion() {
        return accountVersion;
    }

    /** @param accountVersion the JPA optimistic-locking version for the account */
    public void setAccountVersion(Long accountVersion) {
        this.accountVersion = accountVersion;
    }

    /** @return the JPA optimistic-locking version for the customer, or {@code null} */
    public Long getCustomerVersion() {
        return customerVersion;
    }

    /** @param customerVersion the JPA optimistic-locking version for the customer */
    public void setCustomerVersion(Long customerVersion) {
        this.customerVersion = customerVersion;
    }
}
