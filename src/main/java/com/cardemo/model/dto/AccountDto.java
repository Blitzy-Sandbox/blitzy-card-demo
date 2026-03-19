package com.cardemo.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Size;

/**
 * Account view and update API payload DTO.
 *
 * <p>Consolidates fields from two BMS symbolic maps: COACTVW.CPY (account view screen)
 * and COACTUP.CPY (account update screen). Used by {@code AccountController} for
 * GET/PUT {@code /api/accounts/{id}} endpoints.</p>
 *
 * <p>Contains account fields from CVACT01Y.cpy (ACCTDAT VSAM dataset, 300-byte record)
 * and linked customer summary fields from CVCUS01Y.cpy (CUSTDAT VSAM dataset, 500-byte
 * record). The COBOL programs COACTVWC.cbl (view) and COACTUPC.cbl (update) perform
 * multi-dataset reads across both ACCTDAT and CUSTDAT.</p>
 *
 * <p>All monetary fields use {@link BigDecimal} for exact decimal precision matching
 * COBOL COMP-3 packed decimal semantics (scale 2). Zero float/double substitution.</p>
 *
 * <p>All date fields use {@link LocalDate} replacing COBOL PIC X(10) string-based
 * date representation.</p>
 *
 * <p>All PIC 9(nn) identifier fields use {@link String} for leading-zero preservation.</p>
 */
public class AccountDto {

    // ========================================================================
    // Account identification and status fields
    // ========================================================================

    /**
     * Account identifier — maps to ACCT-ID PIC 9(11) from CVACT01Y.cpy.
     * String type preserves leading zeros in the 11-digit account number.
     */
    @Size(max = 11)
    private String acctId;

    /**
     * Account active status flag — maps to ACCT-ACTIVE-STATUS PIC X(01).
     * Typically 'Y' for active or 'N' for inactive.
     */
    @Size(max = 1)
    private String acctActiveStatus;

    /**
     * Customer identifier — maps to ACCT-CUST-ID PIC 9(09) from CVACT01Y.cpy.
     * Foreign key linking account to the customer record in CUSTDAT.
     * String type preserves leading zeros in the 9-digit customer number.
     */
    @Size(max = 9)
    private String custId;

    // ========================================================================
    // Account monetary fields — BigDecimal for COMP-3 precision (scale 2)
    // ========================================================================

    /**
     * Current account balance — maps to ACCT-CURR-BAL PIC S9(10)V99 COMP-3.
     * Two decimal positions (scale 2). Signed field allows negative balances.
     */
    private BigDecimal acctCurrBal;

    /**
     * Credit limit — maps to ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3.
     * Two decimal positions (scale 2).
     */
    private BigDecimal acctCreditLimit;

    /**
     * Cash credit limit — maps to ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3.
     * Two decimal positions (scale 2).
     */
    private BigDecimal acctCashCreditLimit;

    /**
     * Current cycle credit total — maps to ACCT-CURR-CYC-CREDIT PIC S9(10)V99.
     * Two decimal positions (scale 2).
     */
    private BigDecimal acctCurrCycCredit;

    /**
     * Current cycle debit total — maps to ACCT-CURR-CYC-DEBIT PIC S9(10)V99.
     * Two decimal positions (scale 2).
     */
    private BigDecimal acctCurrCycDebit;

    // ========================================================================
    // Account date fields — LocalDate replacing COBOL PIC X(10) strings
    // ========================================================================

    /**
     * Account open date — maps to ACCT-OPEN-DATE PIC X(10).
     * Format: YYYY-MM-DD in the original COBOL string representation.
     */
    private LocalDate acctOpenDate;

    /**
     * Account expiration date — maps to ACCT-EXPIRAION-DATE PIC X(10).
     * Note: the typo in the COBOL field name (EXPIRAION) is from the original source.
     */
    private LocalDate acctExpDate;

    /** Account reissue date — maps to ACCT-REISSUE-DATE PIC X(10). */
    private LocalDate acctReissueDate;

    // ========================================================================
    // Account group identification
    // ========================================================================

    /** Account group identifier — maps to ACCT-GROUP-ID PIC X(10). */
    @Size(max = 10)
    private String acctGroupId;

    // ========================================================================
    // Customer summary fields (from CUSTDAT via multi-dataset read)
    // ========================================================================

    /** Customer first name — maps to CUST-FIRST-NAME PIC X(25) from CVCUS01Y.cpy. */
    @Size(max = 25)
    private String custFname;

    /** Customer middle name — maps to CUST-MIDDLE-NAME PIC X(25). */
    @Size(max = 25)
    private String custMname;

    /** Customer last name — maps to CUST-LAST-NAME PIC X(25). */
    @Size(max = 25)
    private String custLname;

    /** Customer address line 1 — maps to CUST-ADDR-LINE-1 PIC X(50). */
    @Size(max = 50)
    private String custAddr1;

    /** Customer address line 2 — maps to CUST-ADDR-LINE-2 PIC X(50). */
    @Size(max = 50)
    private String custAddr2;

    /**
     * Customer city — maps to CUST-ADDR-LINE-3 PIC X(50) in CVCUS01Y.cpy.
     * Displayed via ACSCITYI field in BMS maps.
     */
    @Size(max = 50)
    private String custCity;

    /** Customer state code — maps to CUST-ADDR-STATE-CD PIC X(02). US 2-letter abbreviation. */
    @Size(max = 2)
    private String custState;

    /**
     * Customer ZIP code — maps to CUST-ADDR-ZIP PIC X(10) in CVCUS01Y.cpy.
     * Supports both 5-digit (e.g., "30852") and ZIP+4 (e.g., "30852-6716")
     * formats as present in the seed data. The COBOL source field is PIC X(10)
     * which accommodates the full ZIP+4 format; the BMS screen field ACSZIPCI
     * is PIC X(5) but the underlying data store allows up to 10 characters.
     */
    @Size(max = 10)
    private String custZip;

    /** Customer country code — maps to CUST-ADDR-COUNTRY-CD PIC X(03). ISO 3-letter code. */
    @Size(max = 3)
    private String custCountry;

    /** Customer primary phone — maps to CUST-PHONE-NUM-1 PIC X(15), ACSPHN1I PIC X(13). */
    @Size(max = 13)
    private String custPhone1;

    /** Customer secondary phone — maps to CUST-PHONE-NUM-2 PIC X(15), ACSPHN2I PIC X(13). */
    @Size(max = 13)
    private String custPhone2;

    /**
     * Customer Social Security Number — maps to CUST-SSN PIC 9(09) from CVCUS01Y.cpy.
     * BMS map ACSTSSNI PIC X(12) formats with dashes (XXX-XX-XXXX).
     * SECURITY: This field contains PII and is excluded from {@link #toString()}.
     */
    @Size(max = 12)
    private String custSsn;

    /** Customer date of birth — maps to CUST-DOB-YYYY-MM-DD PIC X(10). */
    private LocalDate custDob;

    /** Customer FICO credit score — maps to CUST-FICO-CREDIT-SCORE PIC 9(03). */
    @Size(max = 3)
    private String custFicoScore;

    /** Customer government-issued ID — maps to CUST-GOVT-ISSUED-ID PIC X(20). */
    @Size(max = 20)
    private String custGovtId;

    /** Customer EFT account ID — maps to CUST-EFT-ACCOUNT-ID PIC X(10). */
    @Size(max = 10)
    private String custEftAcct;

    /** Customer profile / primary cardholder flag — maps to CUST-PRI-CARD-HOLDER-IND PIC X(01). */
    @Size(max = 1)
    private String custProfileFlag;

    /** Statement number — maps to ACSTNUMI PIC X(9) in BMS maps. */
    @Size(max = 9)
    private String stmtNum;

    /**
     * JPA optimistic locking version field.
     * Exposes the {@code @Version} field from the Account entity to API consumers,
     * enabling concurrent modification detection per AAP §0.8.4.
     * Clients must include the version from the GET response in PUT requests;
     * a mismatch triggers HTTP 409 Conflict.
     */
    private Integer version;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * No-arguments constructor for framework deserialization (Jackson, Spring MVC).
     */
    public AccountDto() {
    }

    /**
     * All-arguments constructor for programmatic construction with all 30 fields.
     *
     * @param acctId              account identifier (11-digit string)
     * @param acctActiveStatus    active status flag ('Y'/'N')
     * @param custId              linked customer identifier (9-digit string)
     * @param acctCurrBal         current balance (COMP-3, scale 2)
     * @param acctCreditLimit     credit limit (COMP-3, scale 2)
     * @param acctCashCreditLimit cash credit limit (COMP-3, scale 2)
     * @param acctCurrCycCredit   current cycle credit total (scale 2)
     * @param acctCurrCycDebit    current cycle debit total (scale 2)
     * @param acctOpenDate        account open date
     * @param acctExpDate         account expiration date
     * @param acctReissueDate     account reissue date
     * @param acctGroupId         account group identifier
     * @param custFname           customer first name
     * @param custMname           customer middle name
     * @param custLname           customer last name
     * @param custAddr1           customer address line 1
     * @param custAddr2           customer address line 2
     * @param custCity            customer city
     * @param custState           customer state code (2-letter)
     * @param custZip             customer ZIP code (5-digit)
     * @param custCountry         customer country code (3-letter ISO)
     * @param custPhone1          customer primary phone number
     * @param custPhone2          customer secondary phone number
     * @param custSsn             customer Social Security Number (PII)
     * @param custDob             customer date of birth
     * @param custFicoScore       customer FICO credit score (3-digit)
     * @param custGovtId          customer government-issued ID
     * @param custEftAcct         customer EFT account ID
     * @param custProfileFlag     customer profile / primary cardholder flag
     * @param stmtNum             statement number
     */
    public AccountDto(String acctId, String acctActiveStatus, String custId,
                      BigDecimal acctCurrBal, BigDecimal acctCreditLimit,
                      BigDecimal acctCashCreditLimit, BigDecimal acctCurrCycCredit,
                      BigDecimal acctCurrCycDebit, LocalDate acctOpenDate,
                      LocalDate acctExpDate, LocalDate acctReissueDate,
                      String acctGroupId, String custFname, String custMname,
                      String custLname, String custAddr1, String custAddr2,
                      String custCity, String custState, String custZip,
                      String custCountry, String custPhone1, String custPhone2,
                      String custSsn, LocalDate custDob, String custFicoScore,
                      String custGovtId, String custEftAcct, String custProfileFlag,
                      String stmtNum) {
        this.acctId = acctId;
        this.acctActiveStatus = acctActiveStatus;
        this.custId = custId;
        this.acctCurrBal = acctCurrBal;
        this.acctCreditLimit = acctCreditLimit;
        this.acctCashCreditLimit = acctCashCreditLimit;
        this.acctCurrCycCredit = acctCurrCycCredit;
        this.acctCurrCycDebit = acctCurrCycDebit;
        this.acctOpenDate = acctOpenDate;
        this.acctExpDate = acctExpDate;
        this.acctReissueDate = acctReissueDate;
        this.acctGroupId = acctGroupId;
        this.custFname = custFname;
        this.custMname = custMname;
        this.custLname = custLname;
        this.custAddr1 = custAddr1;
        this.custAddr2 = custAddr2;
        this.custCity = custCity;
        this.custState = custState;
        this.custZip = custZip;
        this.custCountry = custCountry;
        this.custPhone1 = custPhone1;
        this.custPhone2 = custPhone2;
        this.custSsn = custSsn;
        this.custDob = custDob;
        this.custFicoScore = custFicoScore;
        this.custGovtId = custGovtId;
        this.custEftAcct = custEftAcct;
        this.custProfileFlag = custProfileFlag;
        this.stmtNum = stmtNum;
    }

    // ========================================================================
    // Getters and Setters — Account fields
    // ========================================================================

    public String getAcctId() {
        return acctId;
    }

    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    public String getCustId() {
        return custId;
    }

    public void setCustId(String custId) {
        this.custId = custId;
    }

    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    public LocalDate getAcctOpenDate() {
        return acctOpenDate;
    }

    public void setAcctOpenDate(LocalDate acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    public LocalDate getAcctExpDate() {
        return acctExpDate;
    }

    public void setAcctExpDate(LocalDate acctExpDate) {
        this.acctExpDate = acctExpDate;
    }

    public LocalDate getAcctReissueDate() {
        return acctReissueDate;
    }

    public void setAcctReissueDate(LocalDate acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    public String getAcctGroupId() {
        return acctGroupId;
    }

    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    // ========================================================================
    // Getters and Setters — Customer summary fields
    // ========================================================================

    public String getCustFname() {
        return custFname;
    }

    public void setCustFname(String custFname) {
        this.custFname = custFname;
    }

    public String getCustMname() {
        return custMname;
    }

    public void setCustMname(String custMname) {
        this.custMname = custMname;
    }

    public String getCustLname() {
        return custLname;
    }

    public void setCustLname(String custLname) {
        this.custLname = custLname;
    }

    public String getCustAddr1() {
        return custAddr1;
    }

    public void setCustAddr1(String custAddr1) {
        this.custAddr1 = custAddr1;
    }

    public String getCustAddr2() {
        return custAddr2;
    }

    public void setCustAddr2(String custAddr2) {
        this.custAddr2 = custAddr2;
    }

    public String getCustCity() {
        return custCity;
    }

    public void setCustCity(String custCity) {
        this.custCity = custCity;
    }

    public String getCustState() {
        return custState;
    }

    public void setCustState(String custState) {
        this.custState = custState;
    }

    public String getCustZip() {
        return custZip;
    }

    public void setCustZip(String custZip) {
        this.custZip = custZip;
    }

    public String getCustCountry() {
        return custCountry;
    }

    public void setCustCountry(String custCountry) {
        this.custCountry = custCountry;
    }

    public String getCustPhone1() {
        return custPhone1;
    }

    public void setCustPhone1(String custPhone1) {
        this.custPhone1 = custPhone1;
    }

    public String getCustPhone2() {
        return custPhone2;
    }

    public void setCustPhone2(String custPhone2) {
        this.custPhone2 = custPhone2;
    }

    public String getCustSsn() {
        return custSsn;
    }

    public void setCustSsn(String custSsn) {
        this.custSsn = custSsn;
    }

    public LocalDate getCustDob() {
        return custDob;
    }

    public void setCustDob(LocalDate custDob) {
        this.custDob = custDob;
    }

    public String getCustFicoScore() {
        return custFicoScore;
    }

    public void setCustFicoScore(String custFicoScore) {
        this.custFicoScore = custFicoScore;
    }

    public String getCustGovtId() {
        return custGovtId;
    }

    public void setCustGovtId(String custGovtId) {
        this.custGovtId = custGovtId;
    }

    public String getCustEftAcct() {
        return custEftAcct;
    }

    public void setCustEftAcct(String custEftAcct) {
        this.custEftAcct = custEftAcct;
    }

    public String getCustProfileFlag() {
        return custProfileFlag;
    }

    public void setCustProfileFlag(String custProfileFlag) {
        this.custProfileFlag = custProfileFlag;
    }

    public String getStmtNum() {
        return stmtNum;
    }

    public void setStmtNum(String stmtNum) {
        this.stmtNum = stmtNum;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    // ========================================================================
    // toString — excludes custSsn for PII security
    // ========================================================================

    /**
     * Returns a string representation of this AccountDto including key identification
     * and account fields. The {@code custSsn} field is intentionally excluded to prevent
     * accidental PII exposure in logs and debug output.
     *
     * @return formatted string with account and customer summary fields (SSN excluded)
     */
    @Override
    public String toString() {
        return "AccountDto{"
                + "acctId='" + acctId + '\''
                + ", acctActiveStatus='" + acctActiveStatus + '\''
                + ", custId='" + custId + '\''
                + ", acctCurrBal=" + acctCurrBal
                + ", acctCreditLimit=" + acctCreditLimit
                + ", acctCashCreditLimit=" + acctCashCreditLimit
                + ", acctCurrCycCredit=" + acctCurrCycCredit
                + ", acctCurrCycDebit=" + acctCurrCycDebit
                + ", acctOpenDate=" + acctOpenDate
                + ", acctExpDate=" + acctExpDate
                + ", acctReissueDate=" + acctReissueDate
                + ", acctGroupId='" + acctGroupId + '\''
                + ", custFname='" + custFname + '\''
                + ", custMname='" + custMname + '\''
                + ", custLname='" + custLname + '\''
                + ", custAddr1='" + custAddr1 + '\''
                + ", custAddr2='" + custAddr2 + '\''
                + ", custCity='" + custCity + '\''
                + ", custState='" + custState + '\''
                + ", custZip='" + custZip + '\''
                + ", custCountry='" + custCountry + '\''
                + ", custPhone1='" + custPhone1 + '\''
                + ", custPhone2='" + custPhone2 + '\''
                + ", custSsn='[REDACTED]'"
                + ", custDob=" + custDob
                + ", custFicoScore='" + custFicoScore + '\''
                + ", custGovtId='" + custGovtId + '\''
                + ", custEftAcct='" + custEftAcct + '\''
                + ", custProfileFlag='" + custProfileFlag + '\''
                + ", stmtNum='" + stmtNum + '\''
                + '}';
    }
}
