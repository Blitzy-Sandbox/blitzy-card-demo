package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping the legacy AWS CardDemo customer record onto the PostgreSQL
 * {@code customer} table.
 *
 * <p>This entity is the Java 25 / Spring Data JPA replacement for the VSAM KSDS
 * dataset {@code CUSTDAT}, whose fixed 500-byte record layout is defined by the
 * COBOL copybook {@code app/cpy/CVCUS01Y.cpy} ({@code 01 CUSTOMER-RECORD},
 * record length 500). On the mainframe this record was read and updated by the
 * online programs {@code COACTVWC} (account view) and {@code COACTUPC} (account
 * update) and consumed by the batch programs {@code CBCUS01C} (customer file
 * read) and {@code CBSTM03A} (statement generation). It is a wide, flat
 * reference record: it carries no monetary (decimal) fields and is not an
 * optimistic-locking target.</p>
 *
 * <h2>Original COBOL layout (CVCUS01Y.cpy &mdash; RECLN 500)</h2>
 * <pre>{@code
 * 01  CUSTOMER-RECORD.
 *     05  CUST-ID                  PIC 9(09).
 *     05  CUST-FIRST-NAME          PIC X(25).
 *     05  CUST-MIDDLE-NAME         PIC X(25).
 *     05  CUST-LAST-NAME           PIC X(25).
 *     05  CUST-ADDR-LINE-1         PIC X(50).
 *     05  CUST-ADDR-LINE-2         PIC X(50).
 *     05  CUST-ADDR-LINE-3         PIC X(50).
 *     05  CUST-ADDR-STATE-CD       PIC X(02).
 *     05  CUST-ADDR-COUNTRY-CD     PIC X(03).
 *     05  CUST-ADDR-ZIP            PIC X(10).
 *     05  CUST-PHONE-NUM-1         PIC X(15).
 *     05  CUST-PHONE-NUM-2         PIC X(15).
 *     05  CUST-SSN                 PIC 9(09).
 *     05  CUST-GOVT-ISSUED-ID      PIC X(20).
 *     05  CUST-DOB-YYYY-MM-DD      PIC X(10).
 *     05  CUST-EFT-ACCOUNT-ID      PIC X(10).
 *     05  CUST-PRI-CARD-HOLDER-IND PIC X(01).
 *     05  CUST-FICO-CREDIT-SCORE   PIC 9(03).
 *     05  FILLER                   PIC X(168).
 * }</pre>
 *
 * <h2>Source-copybook note</h2>
 * <p>Two near-identical copybooks describe this record: {@code CVCUS01Y.cpy}
 * (the primary source used here) and {@code CUSTREC.cpy}. They define the same
 * 500-byte layout in the same field order; the <em>only</em> difference is the
 * date-of-birth field name ({@code CUST-DOB-YYYY-MM-DD} in {@code CVCUS01Y}
 * versus {@code CUST-DOB-YYYYMMDD} in {@code CUSTREC}). Either copybook yields
 * the identical Java mapping below.</p>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA.</strong> The physically keyed
 *       {@code CUSTDAT} cluster (9-byte key) becomes a relational table whose
 *       primary key is {@link #custId}. Keyed reads/updates/inserts are served by
 *       a Spring Data {@code CustomerRepository} (typed
 *       {@code JpaRepository<Customer, Long>}) rather than CICS file control.</li>
 *   <li><strong>{@code CUST-ID PIC 9(09)} &rarr; {@link Long}.</strong> The
 *       customer identifier is mapped to {@link Long} (PostgreSQL {@code BIGINT}),
 *       consistent with the {@link Long}-identifier convention used across the
 *       data model (for example {@code Account.acctId}).</li>
 *   <li><strong>{@code CUST-SSN PIC 9(09)} &rarr; {@link Long} (PostgreSQL
 *       {@code BIGINT}).</strong> The nine-digit Social Security Number is a
 *       {@code PIC 9(09)} numeric field, mapped to {@link Long} to match the
 *       authoritative Flyway {@code V1__create_schema.sql} {@code ssn BIGINT}
 *       column and to stay consistent with the {@link Long}-identifier convention
 *       applied to the equally-typed {@code CUST-ID PIC 9(09)}. The SSN is stored
 *       <em>faithfully, without encryption</em>: the blueprint's prose loosely
 *       mentions "SSN encryption", but AAP §0.7.2 fixes the BCrypt password
 *       upgrade as the <em>single permitted behavioral change</em>, so adding SSN
 *       encryption here would be a forbidden second behavioral change. The value
 *       is therefore persisted as-is; the {@link #toString()} method masks it to
 *       avoid emitting the full SSN into logs.</li>
 *   <li><strong>{@code CUST-FICO-CREDIT-SCORE PIC 9(03)} &rarr; {@link Integer}.</strong>
 *       A numeric credit score in the 300&ndash;850 range; leading zeros carry no
 *       significance, so an integer mapping is exact and appropriate.</li>
 *   <li><strong>Date of birth &rarr; {@link LocalDate}.</strong> The
 *       {@code CUST-DOB-YYYY-MM-DD PIC X(10)} {@code YYYY-MM-DD} text date is
 *       mapped to {@link LocalDate} to match the authoritative
 *       {@code date_of_birth DATE} column in {@code V1}. The {@code YYYY-MM-DD}
 *       external representation is preserved at the DTO/API boundary; date
 *       validation and future-date checks are a separate concern handled by
 *       {@code service.shared.DateValidationService}, not by this persistence
 *       entity.</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(168)} is reserved padding that pads the record to its
 *       500-byte length; it carries no business data and is intentionally
 *       <strong>not</strong> mapped to a column. The 500-byte record length is
 *       documented here for external-contract reference only.</li>
 * </ul>
 *
 * <h2>Column-name contract</h2>
 * <p>The {@link Column} names declared below &mdash; {@code customer_id},
 * {@code first_name}, {@code middle_name}, {@code last_name},
 * {@code address_line_1}, {@code address_line_2}, {@code address_line_3},
 * {@code state_code}, {@code country_code}, {@code zip_code},
 * {@code phone_number_1}, {@code phone_number_2}, {@code ssn},
 * {@code government_issued_id}, {@code date_of_birth}, {@code eft_account_id},
 * {@code primary_card_holder_indicator} and {@code fico_credit_score} &mdash;
 * match the authoritative Flyway {@code V1__create_schema.sql} {@code customer}
 * DDL and the {@code V3__seed_data.sql} seed (loaded from
 * {@code app/data/ASCII/custdata.txt}), with
 * {@code customer_id} as {@code BIGINT}, {@code ssn} as {@code BIGINT},
 * {@code fico_credit_score} as {@code INTEGER} and every remaining column as
 * {@code VARCHAR} of the stated length.</p>
 *
 * <p>Per the Minimal Change Clause this entity is a pure persistence type: it
 * declares exactly the eighteen mapped fields, carries <strong>no</strong>
 * {@code @Version} column (Customer is not an optimistic-locking target &mdash;
 * only {@code Account} and {@code Card} are, AAP §0.7.5), carries no Jakarta
 * Bean Validation annotations (input validation lives in the request DTO layer,
 * AAP §0.4.2), and models no JPA associations &mdash; foreign keys elsewhere are
 * plain scalar columns, mirroring the original VSAM keyed-access pattern.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 */
@Entity
@Table(name = "customer")
public class Customer {

    /**
     * Primary key &mdash; the unique customer identifier.
     *
     * <p>Migrated from {@code CUST-ID PIC 9(09)}: a nine-digit unsigned integer,
     * mapped to {@link Long} (PostgreSQL {@code BIGINT}) for consistency with the
     * {@link Long}-identifier convention used across the data model. The
     * downstream repository is therefore {@code JpaRepository<Customer, Long>}.</p>
     */
    // CUST-ID PIC 9(09) -> 9-digit unsigned integer -> Long (BIGINT)
    @Id
    @Column(name = "customer_id", nullable = false)
    private Long custId;

    /**
     * Customer first (given) name.
     *
     * <p>Migrated from {@code CUST-FIRST-NAME PIC X(25)}: a fixed 25-character
     * alphanumeric field, modelled as a {@link String} of length 25.</p>
     */
    // CUST-FIRST-NAME PIC X(25) -> fixed 25-char alphanumeric -> String(25)
    @Column(name = "first_name", length = 25)
    private String custFirstName;

    /**
     * Customer middle name.
     *
     * <p>Migrated from {@code CUST-MIDDLE-NAME PIC X(25)}: a fixed 25-character
     * alphanumeric field, modelled as a {@link String} of length 25.</p>
     */
    // CUST-MIDDLE-NAME PIC X(25) -> fixed 25-char alphanumeric -> String(25)
    @Column(name = "middle_name", length = 25)
    private String custMiddleName;

    /**
     * Customer last (family) name.
     *
     * <p>Migrated from {@code CUST-LAST-NAME PIC X(25)}: a fixed 25-character
     * alphanumeric field, modelled as a {@link String} of length 25.</p>
     */
    // CUST-LAST-NAME PIC X(25) -> fixed 25-char alphanumeric -> String(25)
    @Column(name = "last_name", length = 25)
    private String custLastName;

    /**
     * First line of the customer mailing address.
     *
     * <p>Migrated from {@code CUST-ADDR-LINE-1 PIC X(50)}: a fixed 50-character
     * alphanumeric field, modelled as a {@link String} of length 50.</p>
     */
    // CUST-ADDR-LINE-1 PIC X(50) -> fixed 50-char alphanumeric -> String(50)
    @Column(name = "address_line_1", length = 50)
    private String custAddrLine1;

    /**
     * Second line of the customer mailing address.
     *
     * <p>Migrated from {@code CUST-ADDR-LINE-2 PIC X(50)}: a fixed 50-character
     * alphanumeric field, modelled as a {@link String} of length 50.</p>
     */
    // CUST-ADDR-LINE-2 PIC X(50) -> fixed 50-char alphanumeric -> String(50)
    @Column(name = "address_line_2", length = 50)
    private String custAddrLine2;

    /**
     * Third line of the customer mailing address.
     *
     * <p>Migrated from {@code CUST-ADDR-LINE-3 PIC X(50)}: a fixed 50-character
     * alphanumeric field, modelled as a {@link String} of length 50.</p>
     */
    // CUST-ADDR-LINE-3 PIC X(50) -> fixed 50-char alphanumeric -> String(50)
    @Column(name = "address_line_3", length = 50)
    private String custAddrLine3;

    /**
     * Two-character state/province code of the customer address.
     *
     * <p>Migrated from {@code CUST-ADDR-STATE-CD PIC X(02)}: a fixed 2-character
     * code, modelled as a {@link String} of length 2.</p>
     */
    // CUST-ADDR-STATE-CD PIC X(02) -> fixed 2-char state code -> String(2)
    @Column(name = "state_code", length = 2)
    private String custAddrStateCd;

    /**
     * Three-character country code of the customer address.
     *
     * <p>Migrated from {@code CUST-ADDR-COUNTRY-CD PIC X(03)}: a fixed 3-character
     * code, modelled as a {@link String} of length 3.</p>
     */
    // CUST-ADDR-COUNTRY-CD PIC X(03) -> fixed 3-char country code -> String(3)
    @Column(name = "country_code", length = 3)
    private String custAddrCountryCd;

    /**
     * Customer address ZIP/postal code.
     *
     * <p>Migrated from {@code CUST-ADDR-ZIP PIC X(10)}: a fixed 10-character
     * alphanumeric field, modelled as a {@link String} of length 10.</p>
     */
    // CUST-ADDR-ZIP PIC X(10) -> fixed 10-char alphanumeric -> String(10)
    @Column(name = "zip_code", length = 10)
    private String custAddrZip;

    /**
     * Primary customer telephone number.
     *
     * <p>Migrated from {@code CUST-PHONE-NUM-1 PIC X(15)}: a fixed 15-character
     * field (formatted phone text), modelled as a {@link String} of length 15.</p>
     */
    // CUST-PHONE-NUM-1 PIC X(15) -> fixed 15-char phone text -> String(15)
    @Column(name = "phone_number_1", length = 15)
    private String custPhoneNum1;

    /**
     * Secondary customer telephone number.
     *
     * <p>Migrated from {@code CUST-PHONE-NUM-2 PIC X(15)}: a fixed 15-character
     * field (formatted phone text), modelled as a {@link String} of length 15.</p>
     */
    // CUST-PHONE-NUM-2 PIC X(15) -> fixed 15-char phone text -> String(15)
    @Column(name = "phone_number_2", length = 15)
    private String custPhoneNum2;

    /**
     * Customer Social Security Number.
     *
     * <p>Migrated from {@code CUST-SSN PIC 9(09)} to a {@link Long} (PostgreSQL
     * {@code BIGINT}), matching the authoritative {@code ssn BIGINT} column in
     * {@code V1__create_schema.sql} and the {@link Long}-identifier convention
     * applied to the equally-typed {@code CUST-ID PIC 9(09)}. Encryption is
     * intentionally omitted: AAP §0.7.2 designates the BCrypt password upgrade as
     * the single permitted behavioral change, so encrypting the SSN would
     * constitute a forbidden second change. The value is stored faithfully;
     * {@link #toString()} masks it so the full SSN is never written to logs.</p>
     */
    // CUST-SSN PIC 9(09) -> 9-digit numeric, NOT encrypted -> Long (BIGINT)
    @Column(name = "ssn")
    private Long custSsn;

    /**
     * Government-issued identification reference (for example a driver's license
     * or passport number).
     *
     * <p>Migrated from {@code CUST-GOVT-ISSUED-ID PIC X(20)}: a fixed 20-character
     * alphanumeric field, modelled as a {@link String} of length 20.</p>
     */
    // CUST-GOVT-ISSUED-ID PIC X(20) -> fixed 20-char alphanumeric -> String(20)
    @Column(name = "government_issued_id", length = 20)
    private String custGovtIssuedId;

    /**
     * Customer date of birth as fixed text.
     *
     * <p>Migrated from {@code CUST-DOB-YYYY-MM-DD PIC X(10)} (a {@code YYYY-MM-DD}
     * text date) to a {@link LocalDate}, matching the authoritative
     * {@code date_of_birth DATE} column in {@code V1__create_schema.sql}. The
     * {@code YYYY-MM-DD} external representation is preserved at the DTO/API
     * boundary; date validation is handled by
     * {@code service.shared.DateValidationService}. (In the alternate copybook
     * {@code CUSTREC.cpy} the same field is named {@code CUST-DOB-YYYYMMDD}.)</p>
     */
    // CUST-DOB-YYYY-MM-DD PIC X(10) 'YYYY-MM-DD' -> LocalDate (V1 date_of_birth DATE)
    @Column(name = "date_of_birth")
    private LocalDate custDobYyyyMmDd;

    /**
     * Electronic funds transfer (EFT) account identifier linked to the customer.
     *
     * <p>Migrated from {@code CUST-EFT-ACCOUNT-ID PIC X(10)}: a fixed 10-character
     * alphanumeric field, modelled as a {@link String} of length 10.</p>
     */
    // CUST-EFT-ACCOUNT-ID PIC X(10) -> fixed 10-char alphanumeric -> String(10)
    @Column(name = "eft_account_id", length = 10)
    private String custEftAccountId;

    /**
     * Primary card-holder indicator flag.
     *
     * <p>Migrated from {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}: a single
     * character (typically {@code 'Y'} or {@code 'N'}), modelled as a
     * {@link String} of length 1.</p>
     */
    // CUST-PRI-CARD-HOLDER-IND PIC X(01) -> single-character flag -> String(1)
    @Column(name = "primary_card_holder_indicator", length = 1)
    private String custPriCardHolderInd;

    /**
     * Customer FICO credit score.
     *
     * <p>Migrated from {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}: a three-digit
     * numeric score (typically 300&ndash;850), mapped to {@link Integer}. Unlike
     * the SSN, no leading-zero significance applies, so an integer mapping is
     * exact.</p>
     */
    // CUST-FICO-CREDIT-SCORE PIC 9(03) -> numeric score 300-850 -> Integer
    @Column(name = "fico_credit_score")
    private Integer custFicoCreditScore;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate)
     * to instantiate the entity reflectively before populating its fields.
     */
    public Customer() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Returns the primary-key customer identifier ({@code CUST-ID}).
     *
     * @return the customer identifier, or {@code null} if unset
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * Sets the primary-key customer identifier ({@code CUST-ID}).
     *
     * @param custId the customer identifier to set
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

    /**
     * Returns the first (given) name ({@code CUST-FIRST-NAME}).
     *
     * @return the first name, or {@code null} if unset
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * Sets the first (given) name ({@code CUST-FIRST-NAME}).
     *
     * @param custFirstName the first name to set
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    /**
     * Returns the middle name ({@code CUST-MIDDLE-NAME}).
     *
     * @return the middle name, or {@code null} if unset
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * Sets the middle name ({@code CUST-MIDDLE-NAME}).
     *
     * @param custMiddleName the middle name to set
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    /**
     * Returns the last (family) name ({@code CUST-LAST-NAME}).
     *
     * @return the last name, or {@code null} if unset
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * Sets the last (family) name ({@code CUST-LAST-NAME}).
     *
     * @param custLastName the last name to set
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    /**
     * Returns the first address line ({@code CUST-ADDR-LINE-1}).
     *
     * @return the first address line, or {@code null} if unset
     */
    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    /**
     * Sets the first address line ({@code CUST-ADDR-LINE-1}).
     *
     * @param custAddrLine1 the first address line to set
     */
    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = custAddrLine1;
    }

    /**
     * Returns the second address line ({@code CUST-ADDR-LINE-2}).
     *
     * @return the second address line, or {@code null} if unset
     */
    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    /**
     * Sets the second address line ({@code CUST-ADDR-LINE-2}).
     *
     * @param custAddrLine2 the second address line to set
     */
    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = custAddrLine2;
    }

    /**
     * Returns the third address line ({@code CUST-ADDR-LINE-3}).
     *
     * @return the third address line, or {@code null} if unset
     */
    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    /**
     * Sets the third address line ({@code CUST-ADDR-LINE-3}).
     *
     * @param custAddrLine3 the third address line to set
     */
    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = custAddrLine3;
    }

    /**
     * Returns the address state/province code ({@code CUST-ADDR-STATE-CD}).
     *
     * @return the 2-character state code, or {@code null} if unset
     */
    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    /**
     * Sets the address state/province code ({@code CUST-ADDR-STATE-CD}).
     *
     * @param custAddrStateCd the 2-character state code to set
     */
    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = custAddrStateCd;
    }

    /**
     * Returns the address country code ({@code CUST-ADDR-COUNTRY-CD}).
     *
     * @return the 3-character country code, or {@code null} if unset
     */
    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    /**
     * Sets the address country code ({@code CUST-ADDR-COUNTRY-CD}).
     *
     * @param custAddrCountryCd the 3-character country code to set
     */
    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = custAddrCountryCd;
    }

    /**
     * Returns the address ZIP/postal code ({@code CUST-ADDR-ZIP}).
     *
     * @return the 10-character address ZIP code, or {@code null} if unset
     */
    public String getCustAddrZip() {
        return custAddrZip;
    }

    /**
     * Sets the address ZIP/postal code ({@code CUST-ADDR-ZIP}).
     *
     * @param custAddrZip the 10-character address ZIP code to set
     */
    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = custAddrZip;
    }

    /**
     * Returns the primary telephone number ({@code CUST-PHONE-NUM-1}).
     *
     * @return the 15-character primary phone number, or {@code null} if unset
     */
    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    /**
     * Sets the primary telephone number ({@code CUST-PHONE-NUM-1}).
     *
     * @param custPhoneNum1 the 15-character primary phone number to set
     */
    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = custPhoneNum1;
    }

    /**
     * Returns the secondary telephone number ({@code CUST-PHONE-NUM-2}).
     *
     * @return the 15-character secondary phone number, or {@code null} if unset
     */
    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    /**
     * Sets the secondary telephone number ({@code CUST-PHONE-NUM-2}).
     *
     * @param custPhoneNum2 the 15-character secondary phone number to set
     */
    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = custPhoneNum2;
    }

    /**
     * Returns the Social Security Number ({@code CUST-SSN}) exactly as stored
     * (nine-digit numeric value, unencrypted).
     *
     * <p>Callers handling the returned value are responsible for protecting it;
     * {@link #toString()} deliberately masks the SSN so it is not exposed in
     * logs.</p>
     *
     * @return the 9-digit SSN, or {@code null} if unset
     */
    public Long getCustSsn() {
        return custSsn;
    }

    /**
     * Sets the Social Security Number ({@code CUST-SSN}). The value is stored
     * faithfully as a numeric value; it is not encrypted (see the class-level
     * note and AAP §0.7.2).
     *
     * @param custSsn the 9-digit SSN to set
     */
    public void setCustSsn(Long custSsn) {
        this.custSsn = custSsn;
    }

    /**
     * Returns the government-issued identification reference
     * ({@code CUST-GOVT-ISSUED-ID}).
     *
     * @return the 20-character government-issued ID, or {@code null} if unset
     */
    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    /**
     * Sets the government-issued identification reference
     * ({@code CUST-GOVT-ISSUED-ID}).
     *
     * @param custGovtIssuedId the 20-character government-issued ID to set
     */
    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = custGovtIssuedId;
    }

    /**
     * Returns the date of birth ({@code CUST-DOB-YYYY-MM-DD}).
     *
     * @return the date of birth, or {@code null} if unset
     */
    public LocalDate getCustDobYyyyMmDd() {
        return custDobYyyyMmDd;
    }

    /**
     * Sets the date of birth ({@code CUST-DOB-YYYY-MM-DD}).
     *
     * @param custDobYyyyMmDd the date of birth to set
     */
    public void setCustDobYyyyMmDd(LocalDate custDobYyyyMmDd) {
        this.custDobYyyyMmDd = custDobYyyyMmDd;
    }

    /**
     * Returns the EFT account identifier ({@code CUST-EFT-ACCOUNT-ID}).
     *
     * @return the 10-character EFT account identifier, or {@code null} if unset
     */
    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    /**
     * Sets the EFT account identifier ({@code CUST-EFT-ACCOUNT-ID}).
     *
     * @param custEftAccountId the 10-character EFT account identifier to set
     */
    public void setCustEftAccountId(String custEftAccountId) {
        this.custEftAccountId = custEftAccountId;
    }

    /**
     * Returns the primary card-holder indicator ({@code CUST-PRI-CARD-HOLDER-IND}).
     *
     * @return the single-character indicator flag, or {@code null} if unset
     */
    public String getCustPriCardHolderInd() {
        return custPriCardHolderInd;
    }

    /**
     * Sets the primary card-holder indicator ({@code CUST-PRI-CARD-HOLDER-IND}).
     *
     * @param custPriCardHolderInd the single-character indicator flag to set
     */
    public void setCustPriCardHolderInd(String custPriCardHolderInd) {
        this.custPriCardHolderInd = custPriCardHolderInd;
    }

    /**
     * Returns the FICO credit score ({@code CUST-FICO-CREDIT-SCORE}).
     *
     * @return the numeric credit score, or {@code null} if unset
     */
    public Integer getCustFicoCreditScore() {
        return custFicoCreditScore;
    }

    /**
     * Sets the FICO credit score ({@code CUST-FICO-CREDIT-SCORE}).
     *
     * @param custFicoCreditScore the numeric credit score to set
     */
    public void setCustFicoCreditScore(Integer custFicoCreditScore) {
        this.custFicoCreditScore = custFicoCreditScore;
    }

    /**
     * Identity-based equality keyed on the customer identifier ({@link #custId}).
     *
     * <p>Two {@code Customer} instances are equal when they are of the exact same
     * class and share the same {@link #custId}. The primary key alone defines
     * entity identity; mutable business columns are deliberately excluded so
     * equality stays stable across updates. Exact-class comparison (rather than
     * {@code instanceof}) is used so a proxy/subclass is not treated as equal to
     * a different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Customer} with an equal id
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
     * Hash code derived solely from {@link #custId}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the customer identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }

    /**
     * Diagnostic representation that intentionally <strong>masks</strong> the
     * Social Security Number to avoid emitting it into logs. The SSN is rendered
     * as {@code [PROTECTED]} when present (and {@code null} when unset); all other
     * descriptive fields are included to keep the representation useful.
     *
     * @return a human-readable description of this customer with the SSN masked
     */
    @Override
    public String toString() {
        return "Customer{"
                + "custId=" + custId
                + ", custFirstName='" + custFirstName + '\''
                + ", custMiddleName='" + custMiddleName + '\''
                + ", custLastName='" + custLastName + '\''
                + ", custAddrLine1='" + custAddrLine1 + '\''
                + ", custAddrLine2='" + custAddrLine2 + '\''
                + ", custAddrLine3='" + custAddrLine3 + '\''
                + ", custAddrStateCd='" + custAddrStateCd + '\''
                + ", custAddrCountryCd='" + custAddrCountryCd + '\''
                + ", custAddrZip='" + custAddrZip + '\''
                + ", custPhoneNum1='" + custPhoneNum1 + '\''
                + ", custPhoneNum2='" + custPhoneNum2 + '\''
                + ", custSsn=" + (custSsn == null ? "null" : "[PROTECTED]")
                + ", custGovtIssuedId='" + custGovtIssuedId + '\''
                + ", custDobYyyyMmDd=" + custDobYyyyMmDd
                + ", custEftAccountId='" + custEftAccountId + '\''
                + ", custPriCardHolderInd='" + custPriCardHolderInd + '\''
                + ", custFicoCreditScore=" + custFicoCreditScore
                + '}';
    }
}
