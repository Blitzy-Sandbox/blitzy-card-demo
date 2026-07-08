package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * JPA entity representing a CardDemo customer.
 *
 * <p>Field-by-field translation of the COBOL copybook {@code app/cpy/CVCUS01Y.cpy}
 * ({@code CUSTOMER-RECORD}, fixed record length 500 bytes, source SHA {@code 27d6c6f}).
 * Each Java field is the lowerCamelCase form of the full COBOL data name to preserve
 * a 1:1 traceability mapping between the legacy record layout and the relational model.</p>
 *
 * <p>Persistence contract:</p>
 * <ul>
 *   <li>Maps to relational table {@code customer} (Flyway migration {@code V1__schema.sql}).</li>
 *   <li>{@code custId} is the natural primary key ({@code CUST-ID PIC 9(09)}); there is no
 *       {@code @GeneratedValue} because identifiers originate from the migrated source data.</li>
 *   <li>No optimistic-lock {@code @Version} column: customers are not mutated through a
 *       read-then-rewrite path in the migrated scope.</li>
 *   <li>No relationships or derived behavior are declared on this entity; it is a pure
 *       persistent record type (scope guard: no feature expansion).</li>
 * </ul>
 *
 * <p>The trailing COBOL {@code FILLER PIC X(168)} is intentionally not mapped; it is record
 * padding that carries no business data (9+25+25+25+50+50+50+2+3+10+15+15+9+20+10+10+1+3 = 332,
 * plus 168 filler = 500 bytes).</p>
 */
@Entity
@Table(name = "customer")
public class Customer {

    /** {@code CUST-ID PIC 9(09)} — natural primary key. */
    @Id
    @Column(name = "cust_id", nullable = false)
    private Long custId;

    /** {@code CUST-FIRST-NAME PIC X(25)}. */
    @Column(name = "cust_first_name", length = 25)
    private String custFirstName;

    /** {@code CUST-MIDDLE-NAME PIC X(25)}. */
    @Column(name = "cust_middle_name", length = 25)
    private String custMiddleName;

    /** {@code CUST-LAST-NAME PIC X(25)}. */
    @Column(name = "cust_last_name", length = 25)
    private String custLastName;

    /** {@code CUST-ADDR-LINE-1 PIC X(50)}. */
    @Column(name = "cust_addr_line_1", length = 50)
    private String custAddrLine1;

    /** {@code CUST-ADDR-LINE-2 PIC X(50)}. */
    @Column(name = "cust_addr_line_2", length = 50)
    private String custAddrLine2;

    /** {@code CUST-ADDR-LINE-3 PIC X(50)}. */
    @Column(name = "cust_addr_line_3", length = 50)
    private String custAddrLine3;

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    @Column(name = "cust_addr_state_cd", length = 2)
    private String custAddrStateCd;

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    @Column(name = "cust_addr_country_cd", length = 3)
    private String custAddrCountryCd;

    /** {@code CUST-ADDR-ZIP PIC X(10)}. */
    @Column(name = "cust_addr_zip", length = 10)
    private String custAddrZip;

    /** {@code CUST-PHONE-NUM-1 PIC X(15)}. */
    @Column(name = "cust_phone_num_1", length = 15)
    private String custPhoneNum1;

    /** {@code CUST-PHONE-NUM-2 PIC X(15)}. */
    @Column(name = "cust_phone_num_2", length = 15)
    private String custPhoneNum2;

    /** {@code CUST-SSN PIC 9(09)} — stored as BIGINT for 9+ digit numeric fidelity. */
    @Column(name = "cust_ssn")
    private Long custSsn;

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
    @Column(name = "cust_govt_issued_id", length = 20)
    private String custGovtIssuedId;

    /** {@code CUST-DOB-YYYY-MM-DD PIC X(10)} — date of birth mapped to {@link LocalDate}. */
    @Column(name = "cust_dob_yyyy_mm_dd")
    private LocalDate custDobYyyyMmDd;

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    @Column(name = "cust_eft_account_id", length = 10)
    private String custEftAccountId;

    /** {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. */
    @Column(name = "cust_pri_card_holder_ind", length = 1)
    private String custPriCardHolderInd;

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} — stored as INTEGER. */
    @Column(name = "cust_fico_credit_score")
    private Integer custFicoCreditScore;

    /**
     * Default no-argument constructor required by the JPA specification for entity
     * instantiation via reflection.
     */
    public Customer() {
        // No initialization required; JPA populates state via field/property access.
    }

    /**
     * Returns the customer identifier ({@code CUST-ID}).
     *
     * @return the natural primary key
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * Sets the customer identifier ({@code CUST-ID}).
     *
     * @param custId the natural primary key
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

    /**
     * Returns the customer first name ({@code CUST-FIRST-NAME}).
     *
     * @return the first name
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * Sets the customer first name ({@code CUST-FIRST-NAME}).
     *
     * @param custFirstName the first name
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    /**
     * Returns the customer middle name ({@code CUST-MIDDLE-NAME}).
     *
     * @return the middle name
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * Sets the customer middle name ({@code CUST-MIDDLE-NAME}).
     *
     * @param custMiddleName the middle name
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    /**
     * Returns the customer last name ({@code CUST-LAST-NAME}).
     *
     * @return the last name
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * Sets the customer last name ({@code CUST-LAST-NAME}).
     *
     * @param custLastName the last name
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    /**
     * Returns the first address line ({@code CUST-ADDR-LINE-1}).
     *
     * @return address line 1
     */
    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    /**
     * Sets the first address line ({@code CUST-ADDR-LINE-1}).
     *
     * @param custAddrLine1 address line 1
     */
    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = custAddrLine1;
    }

    /**
     * Returns the second address line ({@code CUST-ADDR-LINE-2}).
     *
     * @return address line 2
     */
    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    /**
     * Sets the second address line ({@code CUST-ADDR-LINE-2}).
     *
     * @param custAddrLine2 address line 2
     */
    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = custAddrLine2;
    }

    /**
     * Returns the third address line ({@code CUST-ADDR-LINE-3}).
     *
     * @return address line 3
     */
    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    /**
     * Sets the third address line ({@code CUST-ADDR-LINE-3}).
     *
     * @param custAddrLine3 address line 3
     */
    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = custAddrLine3;
    }

    /**
     * Returns the state code ({@code CUST-ADDR-STATE-CD}).
     *
     * @return the two-character state code
     */
    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    /**
     * Sets the state code ({@code CUST-ADDR-STATE-CD}).
     *
     * @param custAddrStateCd the two-character state code
     */
    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = custAddrStateCd;
    }

    /**
     * Returns the country code ({@code CUST-ADDR-COUNTRY-CD}).
     *
     * @return the three-character country code
     */
    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    /**
     * Sets the country code ({@code CUST-ADDR-COUNTRY-CD}).
     *
     * @param custAddrCountryCd the three-character country code
     */
    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = custAddrCountryCd;
    }

    /**
     * Returns the postal/ZIP code ({@code CUST-ADDR-ZIP}).
     *
     * @return the ZIP code
     */
    public String getCustAddrZip() {
        return custAddrZip;
    }

    /**
     * Sets the postal/ZIP code ({@code CUST-ADDR-ZIP}).
     *
     * @param custAddrZip the ZIP code
     */
    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = custAddrZip;
    }

    /**
     * Returns the primary phone number ({@code CUST-PHONE-NUM-1}).
     *
     * @return the primary phone number
     */
    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    /**
     * Sets the primary phone number ({@code CUST-PHONE-NUM-1}).
     *
     * @param custPhoneNum1 the primary phone number
     */
    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = custPhoneNum1;
    }

    /**
     * Returns the secondary phone number ({@code CUST-PHONE-NUM-2}).
     *
     * @return the secondary phone number
     */
    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    /**
     * Sets the secondary phone number ({@code CUST-PHONE-NUM-2}).
     *
     * @param custPhoneNum2 the secondary phone number
     */
    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = custPhoneNum2;
    }

    /**
     * Returns the social security number ({@code CUST-SSN}).
     *
     * @return the SSN
     */
    public Long getCustSsn() {
        return custSsn;
    }

    /**
     * Sets the social security number ({@code CUST-SSN}).
     *
     * @param custSsn the SSN
     */
    public void setCustSsn(Long custSsn) {
        this.custSsn = custSsn;
    }

    /**
     * Returns the government-issued identifier ({@code CUST-GOVT-ISSUED-ID}).
     *
     * @return the government-issued identifier
     */
    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    /**
     * Sets the government-issued identifier ({@code CUST-GOVT-ISSUED-ID}).
     *
     * @param custGovtIssuedId the government-issued identifier
     */
    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = custGovtIssuedId;
    }

    /**
     * Returns the date of birth ({@code CUST-DOB-YYYY-MM-DD}).
     *
     * @return the date of birth
     */
    public LocalDate getCustDobYyyyMmDd() {
        return custDobYyyyMmDd;
    }

    /**
     * Sets the date of birth ({@code CUST-DOB-YYYY-MM-DD}).
     *
     * @param custDobYyyyMmDd the date of birth
     */
    public void setCustDobYyyyMmDd(LocalDate custDobYyyyMmDd) {
        this.custDobYyyyMmDd = custDobYyyyMmDd;
    }

    /**
     * Returns the EFT account identifier ({@code CUST-EFT-ACCOUNT-ID}).
     *
     * @return the EFT account identifier
     */
    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    /**
     * Sets the EFT account identifier ({@code CUST-EFT-ACCOUNT-ID}).
     *
     * @param custEftAccountId the EFT account identifier
     */
    public void setCustEftAccountId(String custEftAccountId) {
        this.custEftAccountId = custEftAccountId;
    }

    /**
     * Returns the primary card holder indicator ({@code CUST-PRI-CARD-HOLDER-IND}).
     *
     * @return the primary card holder indicator
     */
    public String getCustPriCardHolderInd() {
        return custPriCardHolderInd;
    }

    /**
     * Sets the primary card holder indicator ({@code CUST-PRI-CARD-HOLDER-IND}).
     *
     * @param custPriCardHolderInd the primary card holder indicator
     */
    public void setCustPriCardHolderInd(String custPriCardHolderInd) {
        this.custPriCardHolderInd = custPriCardHolderInd;
    }

    /**
     * Returns the FICO credit score ({@code CUST-FICO-CREDIT-SCORE}).
     *
     * @return the FICO credit score
     */
    public Integer getCustFicoCreditScore() {
        return custFicoCreditScore;
    }

    /**
     * Sets the FICO credit score ({@code CUST-FICO-CREDIT-SCORE}).
     *
     * @param custFicoCreditScore the FICO credit score
     */
    public void setCustFicoCreditScore(Integer custFicoCreditScore) {
        this.custFicoCreditScore = custFicoCreditScore;
    }
}
