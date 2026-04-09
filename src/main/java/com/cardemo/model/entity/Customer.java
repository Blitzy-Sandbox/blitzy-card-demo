package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL CUSTOMER-RECORD (500 bytes) from CVCUS01Y.cpy and CUSTREC.cpy
 * to the PostgreSQL 'customer' table.
 *
 * <p>Two COBOL copybooks define this record:
 * <ul>
 *   <li>{@code CVCUS01Y.cpy} — primary definition used by online programs (COACTVWC, COACTUPC)</li>
 *   <li>{@code CUSTREC.cpy} — alternative definition used by batch programs (CBCUS01C)</li>
 * </ul>
 * Both are structurally identical (500 bytes) except for minor field name differences
 * (e.g., CUST-DOB-YYYY-MM-DD vs CUST-DOB-YYYYMMDD).
 *
 * <p>Field mapping preserves exact COBOL PIC clause semantics:
 * <ul>
 *   <li>PIC 9(nn) identifiers → String (preserves leading zeros)</li>
 *   <li>PIC X(nn) alphanumeric → String with matching length constraints</li>
 *   <li>PIC X(10) date → {@link LocalDate}</li>
 *   <li>PIC 9(03) FICO score → Integer (display-only, no decimal positions)</li>
 *   <li>168-byte FILLER — not mapped</li>
 * </ul>
 *
 * <p>No {@code @Version} is needed — customer records do not use the optimistic locking
 * pattern that Account and Card entities require.
 *
 * <p><strong>PII Warning:</strong> The {@code custSsn} field contains sensitive personally
 * identifiable information (SSN). It is masked in {@link #toString()} to prevent accidental
 * exposure in logs.
 *
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cpy/CVCUS01Y.cpy">CVCUS01Y.cpy</a>
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cpy/CUSTREC.cpy">CUSTREC.cpy</a>
 */
@Entity
@Table(name = "customers")
public class Customer {

    // -------------------------------------------------------------------------
    // Primary Key — CUST-ID PIC 9(09)
    // -------------------------------------------------------------------------

    /**
     * Customer identifier — maps COBOL {@code CUST-ID PIC 9(09)}.
     * Stored as String to preserve leading zeros (e.g., "000000001").
     */
    @Id
    @Column(name = "cust_id", length = 9, nullable = false)
    private String custId;

    // -------------------------------------------------------------------------
    // Name Fields
    // -------------------------------------------------------------------------

    /** Customer first name — maps COBOL {@code CUST-FIRST-NAME PIC X(25)}. */
    @Column(name = "first_name", length = 25)
    private String custFirstName;

    /** Customer middle name — maps COBOL {@code CUST-MIDDLE-NAME PIC X(25)}. */
    @Column(name = "middle_name", length = 25)
    private String custMiddleName;

    /** Customer last name — maps COBOL {@code CUST-LAST-NAME PIC X(25)}. */
    @Column(name = "last_name", length = 25)
    private String custLastName;

    // -------------------------------------------------------------------------
    // Address Fields
    // -------------------------------------------------------------------------

    /** Address line 1 — maps COBOL {@code CUST-ADDR-LINE-1 PIC X(50)}. */
    @Column(name = "addr_line_1", length = 50)
    private String custAddrLine1;

    /** Address line 2 — maps COBOL {@code CUST-ADDR-LINE-2 PIC X(50)}. */
    @Column(name = "addr_line_2", length = 50)
    private String custAddrLine2;

    /** Address line 3 — maps COBOL {@code CUST-ADDR-LINE-3 PIC X(50)}. */
    @Column(name = "addr_line_3", length = 50)
    private String custAddrLine3;

    /**
     * US state abbreviation — maps COBOL {@code CUST-ADDR-STATE-CD PIC X(02)}.
     * Validated against US state abbreviation lookup table (from CSLKPCDY.cpy).
     */
    @Column(name = "addr_state_cd", columnDefinition = "CHAR(2)")
    @JdbcTypeCode(Types.CHAR)
    private String custAddrStateCd;

    /** Country code — maps COBOL {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    @Column(name = "addr_country_cd", columnDefinition = "CHAR(3)")
    @JdbcTypeCode(Types.CHAR)
    private String custAddrCountryCd;

    /**
     * ZIP code — maps COBOL {@code CUST-ADDR-ZIP PIC X(10)}.
     * Validated against state/ZIP prefix combination table (from CSLKPCDY.cpy).
     */
    @Column(name = "addr_zip", length = 10)
    private String custAddrZip;

    // -------------------------------------------------------------------------
    // Contact Fields
    // -------------------------------------------------------------------------

    /**
     * Primary phone number — maps COBOL {@code CUST-PHONE-NUM-1 PIC X(15)}.
     * Area code validated against NANPA lookup table (from CSLKPCDY.cpy).
     */
    @Column(name = "phone_num_1", length = 15)
    private String custPhoneNum1;

    /** Secondary phone number — maps COBOL {@code CUST-PHONE-NUM-2 PIC X(15)}. */
    @Column(name = "phone_num_2", length = 15)
    private String custPhoneNum2;

    // -------------------------------------------------------------------------
    // Sensitive / Identity Fields
    // -------------------------------------------------------------------------

    /**
     * Social Security Number — maps COBOL {@code CUST-SSN PIC 9(09)}.
     * <strong>SENSITIVE PII</strong> — masked in {@link #toString()} to show only last 4 digits.
     * Stored as String to preserve leading zeros (e.g., "012345678").
     */
    @Column(name = "ssn", length = 9)
    private String custSsn;

    /** Government-issued ID — maps COBOL {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
    @Column(name = "govt_issued_id", length = 20)
    private String custGovtIssuedId;

    // -------------------------------------------------------------------------
    // Date Field
    // -------------------------------------------------------------------------

    /**
     * Date of birth — maps COBOL {@code CUST-DOB-YYYY-MM-DD PIC X(10)} (CVCUS01Y)
     * and {@code CUST-DOB-YYYYMMDD PIC X(10)} (CUSTREC).
     * Both copybooks define this as a 10-character date field; stored as {@link LocalDate}.
     */
    @Column(name = "dob")
    private LocalDate custDob;

    // -------------------------------------------------------------------------
    // Other Fields
    // -------------------------------------------------------------------------

    /** EFT account identifier — maps COBOL {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    @Column(name = "eft_account_id", length = 10)
    private String custEftAccountId;

    /**
     * Primary card holder indicator — maps COBOL {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}.
     * Typically 'Y' or 'N'.
     */
    @Column(name = "pri_card_holder_ind", columnDefinition = "CHAR(1)")
    @JdbcTypeCode(Types.CHAR)
    private String custPriCardHolderInd;

    /**
     * FICO credit score — maps COBOL {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
     * Display-only 3-digit score (range 300-850). No decimal positions, no sign —
     * Short matches DDL SMALLINT for this non-financial, non-calculation field.
     */
    @Column(name = "fico_credit_score")
    private Short custFicoCreditScore;

    // FILLER PIC X(168) — not mapped (padding only)

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * No-args constructor required by JPA specification.
     */
    public Customer() {
        // JPA requires a no-args constructor
    }

    /**
     * All-args constructor for programmatic entity creation.
     *
     * @param custId               customer identifier (9 digits, leading zeros preserved)
     * @param custFirstName        first name (up to 25 chars)
     * @param custMiddleName       middle name (up to 25 chars)
     * @param custLastName         last name (up to 25 chars)
     * @param custAddrLine1        address line 1 (up to 50 chars)
     * @param custAddrLine2        address line 2 (up to 50 chars)
     * @param custAddrLine3        address line 3 (up to 50 chars)
     * @param custAddrStateCd      US state abbreviation (2 chars)
     * @param custAddrCountryCd    country code (3 chars)
     * @param custAddrZip          ZIP code (up to 10 chars)
     * @param custPhoneNum1        primary phone number (up to 15 chars)
     * @param custPhoneNum2        secondary phone number (up to 15 chars)
     * @param custSsn              Social Security Number (9 digits, PII)
     * @param custGovtIssuedId     government-issued ID (up to 20 chars)
     * @param custDob              date of birth
     * @param custEftAccountId     EFT account identifier (up to 10 chars)
     * @param custPriCardHolderInd primary card holder indicator ('Y'/'N')
     * @param custFicoCreditScore  FICO credit score (300-850)
     */
    public Customer(String custId, String custFirstName, String custMiddleName,
                    String custLastName, String custAddrLine1, String custAddrLine2,
                    String custAddrLine3, String custAddrStateCd, String custAddrCountryCd,
                    String custAddrZip, String custPhoneNum1, String custPhoneNum2,
                    String custSsn, String custGovtIssuedId, LocalDate custDob,
                    String custEftAccountId, String custPriCardHolderInd,
                    Short custFicoCreditScore) {
        this.custId = custId;
        this.custFirstName = custFirstName;
        this.custMiddleName = custMiddleName;
        this.custLastName = custLastName;
        this.custAddrLine1 = custAddrLine1;
        this.custAddrLine2 = custAddrLine2;
        this.custAddrLine3 = custAddrLine3;
        this.custAddrStateCd = custAddrStateCd;
        this.custAddrCountryCd = custAddrCountryCd;
        this.custAddrZip = custAddrZip;
        this.custPhoneNum1 = custPhoneNum1;
        this.custPhoneNum2 = custPhoneNum2;
        this.custSsn = custSsn;
        this.custGovtIssuedId = custGovtIssuedId;
        this.custDob = custDob;
        this.custEftAccountId = custEftAccountId;
        this.custPriCardHolderInd = custPriCardHolderInd;
        this.custFicoCreditScore = custFicoCreditScore;
    }

    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------

    public String getCustId() {
        return custId;
    }

    public void setCustId(String custId) {
        this.custId = custId;
    }

    public String getCustFirstName() {
        return custFirstName;
    }

    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    public String getCustMiddleName() {
        return custMiddleName;
    }

    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    public String getCustLastName() {
        return custLastName;
    }

    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = custAddrLine1;
    }

    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = custAddrLine2;
    }

    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = custAddrLine3;
    }

    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = custAddrStateCd;
    }

    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = custAddrCountryCd;
    }

    public String getCustAddrZip() {
        return custAddrZip;
    }

    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = custAddrZip;
    }

    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = custPhoneNum1;
    }

    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = custPhoneNum2;
    }

    public String getCustSsn() {
        return custSsn;
    }

    public void setCustSsn(String custSsn) {
        this.custSsn = custSsn;
    }

    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = custGovtIssuedId;
    }

    public LocalDate getCustDob() {
        return custDob;
    }

    public void setCustDob(LocalDate custDob) {
        this.custDob = custDob;
    }

    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    public void setCustEftAccountId(String custEftAccountId) {
        this.custEftAccountId = custEftAccountId;
    }

    public String getCustPriCardHolderInd() {
        return custPriCardHolderInd;
    }

    public void setCustPriCardHolderInd(String custPriCardHolderInd) {
        this.custPriCardHolderInd = custPriCardHolderInd;
    }

    public Short getCustFicoCreditScore() {
        return custFicoCreditScore;
    }

    public void setCustFicoCreditScore(Short custFicoCreditScore) {
        this.custFicoCreditScore = custFicoCreditScore;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode — based on custId (primary key) only
    // -------------------------------------------------------------------------

    /**
     * Compares by primary key ({@code custId}) for JPA entity identity.
     * Consistent with Hibernate persistence context and first-level cache behavior.
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
     * Hash code based on primary key ({@code custId}) for consistent behavior
     * in collections, sets, and Hibernate persistence context.
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }

    // -------------------------------------------------------------------------
    // toString — SSN is masked for PII protection
    // -------------------------------------------------------------------------

    /**
     * Returns a string representation including all fields.
     * <strong>SSN is masked</strong> to show only the last 4 digits (e.g., "*****1234")
     * to prevent accidental PII exposure in logs and debug output.
     */
    @Override
    public String toString() {
        return "Customer{" +
                "custId='" + custId + '\'' +
                ", custFirstName='" + custFirstName + '\'' +
                ", custMiddleName='" + custMiddleName + '\'' +
                ", custLastName='" + custLastName + '\'' +
                ", custAddrLine1='" + custAddrLine1 + '\'' +
                ", custAddrLine2='" + custAddrLine2 + '\'' +
                ", custAddrLine3='" + custAddrLine3 + '\'' +
                ", custAddrStateCd='" + custAddrStateCd + '\'' +
                ", custAddrCountryCd='" + custAddrCountryCd + '\'' +
                ", custAddrZip='" + custAddrZip + '\'' +
                ", custPhoneNum1='" + custPhoneNum1 + '\'' +
                ", custPhoneNum2='" + custPhoneNum2 + '\'' +
                ", custSsn='" + maskSsn(custSsn) + '\'' +
                ", custGovtIssuedId='" + custGovtIssuedId + '\'' +
                ", custDob=" + custDob +
                ", custEftAccountId='" + custEftAccountId + '\'' +
                ", custPriCardHolderInd='" + custPriCardHolderInd + '\'' +
                ", custFicoCreditScore=" + custFicoCreditScore +
                '}';
    }

    /**
     * Masks an SSN string to show only the last 4 digits for PII protection.
     * Returns "*****NNNN" where NNNN are the last 4 digits, or "***MASKED***"
     * if the SSN is null or too short.
     *
     * @param ssn the raw SSN string (expected 9 characters)
     * @return masked SSN showing only last 4 digits
     */
    private static String maskSsn(String ssn) {
        if (ssn == null || ssn.length() < 4) {
            return "***MASKED***";
        }
        return "*****" + ssn.substring(ssn.length() - 4);
    }
}
