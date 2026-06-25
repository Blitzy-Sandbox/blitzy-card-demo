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
package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapping the legacy COBOL {@code CUSTOMER-RECORD} (RECLN 500) to the
 * PostgreSQL {@code customers} table.
 *
 * <p>This entity is a faithful translation of copybooks {@code app/cpy/CUSTREC.cpy}
 * and {@code app/cpy/CVCUS01Y.cpy} at source commit SHA {@code 27d6c6f}. Both
 * copybooks describe the identical 500-byte record; the trailing
 * {@code FILLER PIC X(168)} is intentionally not mapped to a column.
 *
 * <p>Field-level notes that preserve behavioral parity with the mainframe record:
 * <ul>
 *   <li>{@code ssn} is persisted as a fixed-width {@code CHAR(9)} {@link String}
 *       (never a numeric type) so leading zeros survive round-trips
 *       (for example {@code 020973888}).</li>
 *   <li>{@code dob} maps {@code CUST-DOB-YYYY-MM-DD} / {@code CUST-DOB-YYYYMMDD}
 *       as a 10-character text field, preserving the legacy date text verbatim.</li>
 *   <li>{@code version} backs JPA optimistic locking, reproducing the COBOL
 *       re-read-and-compare guard paragraph {@code 9300-CHECK-CHANGE-IN-REC}.</li>
 * </ul>
 *
 * <p>Column names, SQL types, and lengths mirror {@code V1__create_schema.sql} table
 * {@code customers} exactly so Hibernate {@code ddl-auto: validate} succeeds at boot.
 */
@Entity
@Table(name = "customers")
public class Customer implements Serializable {

    private static final long serialVersionUID = 1L;

    /** CUST-ID PIC 9(09): primary key, bytes 1-9. Externally assigned, not generated. */
    @Id
    @Column(name = "cust_id")
    private Long custId;

    /** CUST-FIRST-NAME PIC X(25): bytes 10-34. */
    @Column(name = "first_name", length = 25)
    private String firstName;

    /** CUST-MIDDLE-NAME PIC X(25): bytes 35-59. */
    @Column(name = "middle_name", length = 25)
    private String middleName;

    /** CUST-LAST-NAME PIC X(25): bytes 60-84. */
    @Column(name = "last_name", length = 25)
    private String lastName;

    /** CUST-ADDR-LINE-1 PIC X(50): bytes 85-134. */
    @Column(name = "addr_line_1", length = 50)
    private String addrLine1;

    /** CUST-ADDR-LINE-2 PIC X(50): bytes 135-184. */
    @Column(name = "addr_line_2", length = 50)
    private String addrLine2;

    /** CUST-ADDR-LINE-3 PIC X(50): bytes 185-234. */
    @Column(name = "addr_line_3", length = 50)
    private String addrLine3;

    /** CUST-ADDR-STATE-CD PIC X(02): bytes 235-236, fixed-width CHAR(2). */
    @Column(name = "addr_state_cd", length = 2, columnDefinition = "char(2)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String addrStateCd;

    /** CUST-ADDR-COUNTRY-CD PIC X(03): bytes 237-239, fixed-width CHAR(3). */
    @Column(name = "addr_country_cd", length = 3, columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String addrCountryCd;

    /** CUST-ADDR-ZIP PIC X(10): bytes 240-249. */
    @Column(name = "addr_zip", length = 10)
    private String addrZip;

    /** CUST-PHONE-NUM-1 PIC X(15): bytes 250-264. */
    @Column(name = "phone_num_1", length = 15)
    private String phoneNum1;

    /** CUST-PHONE-NUM-2 PIC X(15): bytes 265-279. */
    @Column(name = "phone_num_2", length = 15)
    private String phoneNum2;

    /**
     * CUST-SSN PIC 9(09): bytes 280-288. Stored as fixed-width {@code CHAR(9)}
     * {@link String} (not numeric) to preserve leading zeros exactly.
     */
    @Column(name = "ssn", length = 9, columnDefinition = "char(9)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String ssn;

    /** CUST-GOVT-ISSUED-ID PIC X(20): bytes 289-308. */
    @Column(name = "govt_issued_id", length = 20)
    private String govtIssuedId;

    /** CUST-DOB-YYYY-MM-DD PIC X(10): bytes 309-318, text date preserved verbatim. */
    @Column(name = "dob", length = 10)
    private String dob;

    /** CUST-EFT-ACCOUNT-ID PIC X(10): bytes 319-328. */
    @Column(name = "eft_account_id", length = 10)
    private String eftAccountId;

    /** CUST-PRI-CARD-HOLDER-IND PIC X(01): byte 329, fixed-width CHAR(1). */
    @Column(name = "pri_card_holder_ind", length = 1, columnDefinition = "char(1)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String priCardHolderInd;

    /** CUST-FICO-CREDIT-SCORE PIC 9(03): bytes 330-332, numeric. */
    @Column(name = "fico_credit_score")
    private Integer ficoCreditScore;

    /** Optimistic-lock control column backing JPA {@code @Version}. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * No-argument constructor required by the JPA specification.
     */
    public Customer() {
        // Required by JPA; fields populated by the persistence provider or setters.
    }

    /**
     * All-arguments constructor covering every persistent field, including the
     * optimistic-lock {@code version}.
     *
     * @param custId           customer identifier (primary key)
     * @param firstName        first name
     * @param middleName       middle name
     * @param lastName         last name
     * @param addrLine1        address line 1
     * @param addrLine2        address line 2
     * @param addrLine3        address line 3
     * @param addrStateCd      two-character state code
     * @param addrCountryCd    three-character country code
     * @param addrZip          ZIP/postal code
     * @param phoneNum1        primary phone number
     * @param phoneNum2        secondary phone number
     * @param ssn              nine-character SSN (leading zeros preserved)
     * @param govtIssuedId     government-issued identifier
     * @param dob              date of birth as ten-character text
     * @param eftAccountId     EFT account identifier
     * @param priCardHolderInd primary card-holder indicator
     * @param ficoCreditScore  FICO credit score
     * @param version          optimistic-lock version
     */
    public Customer(Long custId,
                    String firstName,
                    String middleName,
                    String lastName,
                    String addrLine1,
                    String addrLine2,
                    String addrLine3,
                    String addrStateCd,
                    String addrCountryCd,
                    String addrZip,
                    String phoneNum1,
                    String phoneNum2,
                    String ssn,
                    String govtIssuedId,
                    String dob,
                    String eftAccountId,
                    String priCardHolderInd,
                    Integer ficoCreditScore,
                    Long version) {
        this.custId = custId;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.addrLine1 = addrLine1;
        this.addrLine2 = addrLine2;
        this.addrLine3 = addrLine3;
        this.addrStateCd = addrStateCd;
        this.addrCountryCd = addrCountryCd;
        this.addrZip = addrZip;
        this.phoneNum1 = phoneNum1;
        this.phoneNum2 = phoneNum2;
        this.ssn = ssn;
        this.govtIssuedId = govtIssuedId;
        this.dob = dob;
        this.eftAccountId = eftAccountId;
        this.priCardHolderInd = priCardHolderInd;
        this.ficoCreditScore = ficoCreditScore;
        this.version = version;
    }

    public Long getCustId() {
        return custId;
    }

    public void setCustId(Long custId) {
        this.custId = custId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getAddrLine1() {
        return addrLine1;
    }

    public void setAddrLine1(String addrLine1) {
        this.addrLine1 = addrLine1;
    }

    public String getAddrLine2() {
        return addrLine2;
    }

    public void setAddrLine2(String addrLine2) {
        this.addrLine2 = addrLine2;
    }

    public String getAddrLine3() {
        return addrLine3;
    }

    public void setAddrLine3(String addrLine3) {
        this.addrLine3 = addrLine3;
    }

    public String getAddrStateCd() {
        return addrStateCd;
    }

    public void setAddrStateCd(String addrStateCd) {
        this.addrStateCd = addrStateCd;
    }

    public String getAddrCountryCd() {
        return addrCountryCd;
    }

    public void setAddrCountryCd(String addrCountryCd) {
        this.addrCountryCd = addrCountryCd;
    }

    public String getAddrZip() {
        return addrZip;
    }

    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    public String getPhoneNum1() {
        return phoneNum1;
    }

    public void setPhoneNum1(String phoneNum1) {
        this.phoneNum1 = phoneNum1;
    }

    public String getPhoneNum2() {
        return phoneNum2;
    }

    public void setPhoneNum2(String phoneNum2) {
        this.phoneNum2 = phoneNum2;
    }

    public String getSsn() {
        return ssn;
    }

    public void setSsn(String ssn) {
        this.ssn = ssn;
    }

    public String getGovtIssuedId() {
        return govtIssuedId;
    }

    public void setGovtIssuedId(String govtIssuedId) {
        this.govtIssuedId = govtIssuedId;
    }

    public String getDob() {
        return dob;
    }

    public void setDob(String dob) {
        this.dob = dob;
    }

    public String getEftAccountId() {
        return eftAccountId;
    }

    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    public String getPriCardHolderInd() {
        return priCardHolderInd;
    }

    public void setPriCardHolderInd(String priCardHolderInd) {
        this.priCardHolderInd = priCardHolderInd;
    }

    public Integer getFicoCreditScore() {
        return ficoCreditScore;
    }

    public void setFicoCreditScore(Integer ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Equality is defined by the {@code custId} primary key only, consistent with
     * JPA entity-identity semantics.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Customer customer = (Customer) o;
        return Objects.equals(custId, customer.custId);
    }

    /**
     * Hash code derived from the {@code custId} primary key only, mirroring
     * {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }
}
