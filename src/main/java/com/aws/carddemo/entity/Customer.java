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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code CUSTDAT} VSAM KSDS file's
 * {@code CUSTOMER-RECORD} record described by {@code app/cpy/CUSTREC.cpy}
 * (also published as {@code app/cpy/CVCUS01Y.cpy}).
 *
 * <h2>COBOL Provenance — CUSTREC.cpy / CVCUS01Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 500-byte record:
 * <pre>
 *   01 CUSTOMER-RECORD.
 *      05 CUST-ID                  PIC 9(09).  --&gt; {@link #customerId}        (primary key)
 *      05 CUST-FIRST-NAME          PIC X(25).  --&gt; {@link #firstName}
 *      05 CUST-MIDDLE-NAME         PIC X(25).  --&gt; {@link #middleName}
 *      05 CUST-LAST-NAME           PIC X(25).  --&gt; {@link #lastName}
 *      05 CUST-ADDR-LINE-1         PIC X(50).  --&gt; {@link #addressLine1}
 *      05 CUST-ADDR-LINE-2         PIC X(50).  --&gt; {@link #addressLine2}
 *      05 CUST-ADDR-LINE-3         PIC X(50).  --&gt; {@link #addressLine3}
 *      05 CUST-ADDR-STATE-CD       PIC X(02).  --&gt; {@link #addressStateCode}
 *      05 CUST-ADDR-COUNTRY-CD     PIC X(03).  --&gt; {@link #addressCountryCode}
 *      05 CUST-ADDR-ZIP            PIC X(10).  --&gt; {@link #addressZip}
 *      05 CUST-PHONE-NUM-1         PIC X(15).  --&gt; {@link #phoneNumber1}
 *      05 CUST-PHONE-NUM-2         PIC X(15).  --&gt; {@link #phoneNumber2}
 *      05 CUST-SSN                 PIC 9(09).  --&gt; {@link #ssn}
 *      05 CUST-GOVT-ISSUED-ID      PIC X(20).  --&gt; {@link #governmentIssuedId}
 *      05 CUST-DOB-YYYY-MM-DD      PIC X(10).  --&gt; {@link #dateOfBirth}        (ISO YYYY-MM-DD)
 *      05 CUST-EFT-ACCOUNT-ID      PIC X(10).  --&gt; {@link #eftAccountId}
 *      05 CUST-PRI-CARD-HOLDER-IND PIC X(01).  --&gt; {@link #primaryCardHolderIndicator}
 *      05 CUST-FICO-CREDIT-SCORE   PIC 9(03).  --&gt; {@link #ficoCreditScore}
 *      05 FILLER                   PIC X(168).
 * </pre>
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>{@link #version} is a JPA {@code @Version} field for optimistic locking
 *       — the Java replacement for COBOL's before/after-image record comparison
 *       used by customer-update flows ({@code COACTUPC.cbl} dual-write).
 *       Read-only lookups in
 *       {@link com.aws.carddemo.service.AccountViewService} do not increment
 *       the version, so the field is set but never mutated in this code path.</li>
 *   <li>{@link #ssn} is intentionally stored as a string (not encrypted) at this
 *       stub stage; production deployment will wrap this field with a JPA
 *       attribute converter performing AES-GCM encryption at rest, per the
 *       PII policy that REFACTOR-flavor agents will add.</li>
 *   <li>{@link #ficoCreditScore} is an {@link Integer} (not {@code int}) to
 *       preserve the COBOL {@code PIC 9(03)} field's ability to carry a null
 *       sentinel (LOW-VALUES) for customers whose FICO has not been pulled.</li>
 * </ul>
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.service.AccountViewService} compilation and the
 * account-view test suite. Subsequent migration agents (REFACTOR flavor) will
 * add JPA annotations ({@code @Entity}, {@code @Id}, {@code @Column},
 * {@code @Version}), Bean Validation constraints, an AES-GCM attribute
 * converter for {@link #ssn}, and a proper equals/hashCode contract once the
 * entity is wired into the Hibernate {@code SessionFactory}.
 *
 * <h2>Security — toString() Excludes PII</h2>
 *
 * <p>{@link #toString()} explicitly omits {@link #ssn}, {@link #dateOfBirth},
 * {@link #governmentIssuedId}, {@link #eftAccountId}, {@link #phoneNumber1},
 * {@link #phoneNumber2}, {@link #addressLine1}, {@link #addressLine2}, and
 * {@link #addressLine3} so the entity cannot leak personally identifiable
 * information into log output. Per AAP §0.10.5 ("No financial data written
 * to logs at any level" — extended here to all PII for defence-in-depth).
 *
 * @see com.aws.carddemo.service.AccountViewService
 * @see com.aws.carddemo.repository.CustomerRepository
 */
@Entity
@Table(name = "customers")
public class Customer {

    /**
     * 9-character {@code CUST-ID} primary key per {@code CUSTREC.cpy}
     * ({@code PIC 9(09)}). Zero-padded numeric string
     * (e.g. {@code "000000001"}).
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_id", columnDefinition = "CHAR(9)", nullable = false, length = 9)
    private String customerId;

    /** {@code CUST-FIRST-NAME} per {@code CUSTREC.cpy} ({@code PIC X(25)}). */
    @Column(name = "first_name", length = 25)
    private String firstName;

    /** {@code CUST-MIDDLE-NAME} per {@code CUSTREC.cpy} ({@code PIC X(25)}). */
    @Column(name = "middle_name", length = 25)
    private String middleName;

    /** {@code CUST-LAST-NAME} per {@code CUSTREC.cpy} ({@code PIC X(25)}). */
    @Column(name = "last_name", length = 25)
    private String lastName;

    /** {@code CUST-ADDR-LINE-1} per {@code CUSTREC.cpy} ({@code PIC X(50)}). */
    @Column(name = "addr_line_1", length = 50)
    private String addressLine1;

    /** {@code CUST-ADDR-LINE-2} per {@code CUSTREC.cpy} ({@code PIC X(50)}). */
    @Column(name = "addr_line_2", length = 50)
    private String addressLine2;

    /**
     * {@code CUST-ADDR-LINE-3} per {@code CUSTREC.cpy} ({@code PIC X(50)}).
     * COBOL uses this slot for the city; the field name preserves the
     * source-of-truth label rather than introducing a {@code city} alias.
     */
    @Column(name = "addr_line_3", length = 50)
    private String addressLine3;

    /** {@code CUST-ADDR-STATE-CD} per {@code CUSTREC.cpy} ({@code PIC X(02)}). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "addr_state_cd", columnDefinition = "CHAR(2)", length = 2)
    private String addressStateCode;

    /** {@code CUST-ADDR-COUNTRY-CD} per {@code CUSTREC.cpy} ({@code PIC X(03)}). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "addr_country_cd", columnDefinition = "CHAR(3)", length = 3)
    private String addressCountryCode;

    /** {@code CUST-ADDR-ZIP} per {@code CUSTREC.cpy} ({@code PIC X(10)}). */
    @Column(name = "addr_zip", length = 10)
    private String addressZip;

    /** {@code CUST-PHONE-NUM-1} per {@code CUSTREC.cpy} ({@code PIC X(15)}). */
    @Column(name = "phone_num_1", length = 15)
    private String phoneNumber1;

    /** {@code CUST-PHONE-NUM-2} per {@code CUSTREC.cpy} ({@code PIC X(15)}). */
    @Column(name = "phone_num_2", length = 15)
    private String phoneNumber2;

    /**
     * {@code CUST-SSN} per {@code CUSTREC.cpy} ({@code PIC 9(09)}). Stored as a
     * dash-formatted string ({@code "123-45-6789"}) at the application layer;
     * the JPA attribute converter (added by REFACTOR-flavor agents) will
     * persist the value as AES-GCM ciphertext.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ssn", columnDefinition = "CHAR(9)", length = 9)
    private String ssn;

    /**
     * {@code CUST-GOVT-ISSUED-ID} per {@code CUSTREC.cpy} ({@code PIC X(20)}).
     * Driver's licence number or equivalent government-issued identifier;
     * subject to the same encryption-at-rest policy as {@link #ssn}.
     */
    @Column(name = "govt_issued_id", length = 20)
    private String governmentIssuedId;

    /**
     * {@code CUST-DOB-YYYY-MM-DD} per {@code CVCUS01Y.cpy} ({@code PIC X(10)}).
     * ISO-style {@code YYYY-MM-DD} string so the Java code can parse to
     * {@code LocalDate} without ambiguity. The CUSTREC.cpy variant labels this
     * field {@code CUST-DOB-YYYYMMDD} (no separators); the migration uses the
     * separated form that CVCUS01Y standardised on for ISO compatibility.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dob_yyyy_mm_dd", columnDefinition = "CHAR(10)", length = 10)
    private String dateOfBirth;

    /** {@code CUST-EFT-ACCOUNT-ID} per {@code CUSTREC.cpy} ({@code PIC X(10)}). */
    @Column(name = "eft_account_id", length = 10)
    private String eftAccountId;

    /**
     * {@code CUST-PRI-CARD-HOLDER-IND} per {@code CUSTREC.cpy}
     * ({@code PIC X(01)}). {@code 'Y'} for primary card holder, {@code 'N'}
     * for secondary.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pri_card_holder_ind", columnDefinition = "CHAR(1)", length = 1)
    private String primaryCardHolderIndicator;

    /**
     * {@code CUST-FICO-CREDIT-SCORE} per {@code CUSTREC.cpy} ({@code PIC 9(03)}).
     * Range 300–850; nullable to represent a customer whose FICO has never
     * been pulled (COBOL {@code LOW-VALUES} sentinel).
     */
    @Column(name = "fico_credit_score")
    private Integer ficoCreditScore;

    /**
     * JPA optimistic-locking version. Incremented by Hibernate on each
     * {@code save()}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Default no-arg constructor (required by JPA reflection-based instantiation). */
    public Customer() {
        // intentionally empty
    }

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
    public String getAddressStateCode() {
        return addressStateCode;
    }

    /** @param addressStateCode the 2-character state code */
    public void setAddressStateCode(String addressStateCode) {
        this.addressStateCode = addressStateCode;
    }

    /** @return the 3-character country code */
    public String getAddressCountryCode() {
        return addressCountryCode;
    }

    /** @param addressCountryCode the 3-character country code */
    public void setAddressCountryCode(String addressCountryCode) {
        this.addressCountryCode = addressCountryCode;
    }

    /** @return the postal code (string to preserve leading zeros) */
    public String getAddressZip() {
        return addressZip;
    }

    /** @param addressZip the postal code */
    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    /** @return the primary phone number */
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

    /** @return the Social Security Number (dash-formatted) */
    public String getSsn() {
        return ssn;
    }

    /** @param ssn the Social Security Number (dash-formatted) */
    public void setSsn(String ssn) {
        this.ssn = ssn;
    }

    /** @return the government-issued identifier (e.g. driver's licence) */
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

    /** @param eftAccountId the EFT account identifier (bank account for autopay) */
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

    /** @return the FICO credit score (300–850; nullable) */
    public Integer getFicoCreditScore() {
        return ficoCreditScore;
    }

    /** @param ficoCreditScore the FICO credit score (300–850; nullable) */
    public void setFicoCreditScore(Integer ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
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
     * Equality is based on the primary key {@link #customerId} alone (same
     * rationale as {@link Account#equals(Object)}: JPA-managed entities are
     * considered equal iff they share the same primary key value).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Customer)) {
            return false;
        }
        Customer other = (Customer) o;
        return Objects.equals(customerId, other.customerId);
    }

    /** Hash by primary key, consistent with {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        return Objects.hash(customerId);
    }

    /**
     * Diagnostic string deliberately omitting PII fields (SSN, DOB, government
     * ID, EFT account, phone numbers, address lines) per AAP §0.10.5. Exposes
     * only the primary key, names (already public-facing on the credit card),
     * state/country/zip (postal granularity only), card-holder indicator, and
     * version.
     */
    @Override
    public String toString() {
        return "Customer{"
                + "customerId='" + customerId + '\''
                + ", firstName='" + firstName + '\''
                + ", middleName='" + middleName + '\''
                + ", lastName='" + lastName + '\''
                + ", addressStateCode='" + addressStateCode + '\''
                + ", addressCountryCode='" + addressCountryCode + '\''
                + ", addressZip='" + addressZip + '\''
                + ", primaryCardHolderIndicator='" + primaryCardHolderIndicator + '\''
                + ", version=" + version
                + '}';
    }
}
