package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;

/**
 * Stateless per-request session/context object for the CardDemo application.
 *
 * <p>Java equivalent of the COBOL communication area {@code 01 CARDDEMO-COMMAREA}
 * defined in {@code app/cpy/COCOM01Y.cpy} (source commit {@code 27d6c6f}). In the
 * original z/OS system this structure carried conversational state between
 * pseudo-conversational CICS program invocations. In the migrated application it is
 * a plain, mutable context object populated incrementally over the course of a
 * single request; no conversational state is held on the server.</p>
 *
 * <p>The identity-related fields ({@code userId}, {@code userType}, {@code customerId},
 * {@code accountId}, {@code cardNumber}) also surface as JWT claims, so the same
 * shape is consumed by the authentication/security layer as well as the menu and
 * other domain services.</p>
 *
 * <p>The numeric COBOL identifiers ({@code CDEMO-CUST-ID}, {@code CDEMO-ACCT-ID},
 * {@code CDEMO-CARD-NUM}) are represented as {@link String} to preserve their
 * fixed-width, zero-padded textual form; they are identifiers, not arithmetic
 * operands.</p>
 */
public class CommArea {

    /** Program-context value indicating a first (ENTER) invocation. COBOL {@code 88 CDEMO-PGM-ENTER VALUE 0}. */
    public static final int PGM_ENTER = 0;

    /** Program-context value indicating a re-entry (REENTER) invocation. COBOL {@code 88 CDEMO-PGM-REENTER VALUE 1}. */
    public static final int PGM_REENTER = 1;

    // ----- CDEMO-GENERAL-INFO --------------------------------------------------

    /** Originating transaction id. COBOL {@code CDEMO-FROM-TRANID PIC X(04)}. */
    private String fromTranId;

    /** Originating program name. COBOL {@code CDEMO-FROM-PROGRAM PIC X(08)}. */
    private String fromProgram;

    /** Target transaction id. COBOL {@code CDEMO-TO-TRANID PIC X(04)}. */
    private String toTranId;

    /** Target program name. COBOL {@code CDEMO-TO-PROGRAM PIC X(08)}. */
    private String toProgram;

    /** Signed-on user id. COBOL {@code CDEMO-USER-ID PIC X(08)}. */
    private String userId;

    /** User authorization type. COBOL {@code CDEMO-USER-TYPE PIC X(01)} (88 ADMIN 'A' / USER 'U'). */
    private UserType userType;

    /** Program context flag. COBOL {@code CDEMO-PGM-CONTEXT PIC 9(01)} ({@link #PGM_ENTER}/{@link #PGM_REENTER}). */
    private int pgmContext;

    // ----- CDEMO-CUSTOMER-INFO -------------------------------------------------

    /** Customer id (textual, zero-padded). COBOL {@code CDEMO-CUST-ID PIC 9(09)}. */
    private String customerId;

    /** Customer first name. COBOL {@code CDEMO-CUST-FNAME PIC X(25)}. */
    private String customerFirstName;

    /** Customer middle name. COBOL {@code CDEMO-CUST-MNAME PIC X(25)}. */
    private String customerMiddleName;

    /** Customer last name. COBOL {@code CDEMO-CUST-LNAME PIC X(25)}. */
    private String customerLastName;

    // ----- CDEMO-ACCOUNT-INFO --------------------------------------------------

    /** Account id (textual, zero-padded). COBOL {@code CDEMO-ACCT-ID PIC 9(11)}. */
    private String accountId;

    /** Account status code. COBOL {@code CDEMO-ACCT-STATUS PIC X(01)}. */
    private String accountStatus;

    // ----- CDEMO-CARD-INFO -----------------------------------------------------

    /** Card number (textual, zero-padded). COBOL {@code CDEMO-CARD-NUM PIC 9(16)}. */
    private String cardNumber;

    // ----- CDEMO-MORE-INFO -----------------------------------------------------

    /** Last BMS map name. COBOL {@code CDEMO-LAST-MAP PIC X(7)}. */
    private String lastMap;

    /** Last BMS mapset name. COBOL {@code CDEMO-LAST-MAPSET PIC X(7)}. */
    private String lastMapset;

    /**
     * Creates an empty context. Fields are populated incrementally by the
     * authentication, menu, and domain service layers as a request progresses.
     */
    public CommArea() {
    }

    // ----- Accessors -----------------------------------------------------------

    /**
     * Returns the originating transaction id.
     *
     * @return the from-transaction id, or {@code null} if unset
     */
    public String getFromTranId() {
        return fromTranId;
    }

    /**
     * Sets the originating transaction id.
     *
     * @param fromTranId the from-transaction id
     */
    public void setFromTranId(String fromTranId) {
        this.fromTranId = fromTranId;
    }

    /**
     * Returns the originating program name.
     *
     * @return the from-program name, or {@code null} if unset
     */
    public String getFromProgram() {
        return fromProgram;
    }

    /**
     * Sets the originating program name.
     *
     * @param fromProgram the from-program name
     */
    public void setFromProgram(String fromProgram) {
        this.fromProgram = fromProgram;
    }

    /**
     * Returns the target transaction id.
     *
     * @return the to-transaction id, or {@code null} if unset
     */
    public String getToTranId() {
        return toTranId;
    }

    /**
     * Sets the target transaction id.
     *
     * @param toTranId the to-transaction id
     */
    public void setToTranId(String toTranId) {
        this.toTranId = toTranId;
    }

    /**
     * Returns the target program name.
     *
     * @return the to-program name, or {@code null} if unset
     */
    public String getToProgram() {
        return toProgram;
    }

    /**
     * Sets the target program name.
     *
     * @param toProgram the to-program name
     */
    public void setToProgram(String toProgram) {
        this.toProgram = toProgram;
    }

    /**
     * Returns the signed-on user id.
     *
     * @return the user id, or {@code null} if unset
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the signed-on user id.
     *
     * @param userId the user id
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the user authorization type.
     *
     * @return the user type, or {@code null} if unset
     */
    public UserType getUserType() {
        return userType;
    }

    /**
     * Sets the user authorization type.
     *
     * @param userType the user type
     */
    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    /**
     * Returns the program context flag.
     *
     * @return {@link #PGM_ENTER} for a first invocation or {@link #PGM_REENTER} for a re-entry
     */
    public int getPgmContext() {
        return pgmContext;
    }

    /**
     * Sets the program context flag.
     *
     * @param pgmContext {@link #PGM_ENTER} or {@link #PGM_REENTER}
     */
    public void setPgmContext(int pgmContext) {
        this.pgmContext = pgmContext;
    }

    /**
     * Returns the customer id.
     *
     * @return the textual, zero-padded customer id, or {@code null} if unset
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Sets the customer id.
     *
     * @param customerId the textual, zero-padded customer id
     */
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    /**
     * Returns the customer first name.
     *
     * @return the customer first name, or {@code null} if unset
     */
    public String getCustomerFirstName() {
        return customerFirstName;
    }

    /**
     * Sets the customer first name.
     *
     * @param customerFirstName the customer first name
     */
    public void setCustomerFirstName(String customerFirstName) {
        this.customerFirstName = customerFirstName;
    }

    /**
     * Returns the customer middle name.
     *
     * @return the customer middle name, or {@code null} if unset
     */
    public String getCustomerMiddleName() {
        return customerMiddleName;
    }

    /**
     * Sets the customer middle name.
     *
     * @param customerMiddleName the customer middle name
     */
    public void setCustomerMiddleName(String customerMiddleName) {
        this.customerMiddleName = customerMiddleName;
    }

    /**
     * Returns the customer last name.
     *
     * @return the customer last name, or {@code null} if unset
     */
    public String getCustomerLastName() {
        return customerLastName;
    }

    /**
     * Sets the customer last name.
     *
     * @param customerLastName the customer last name
     */
    public void setCustomerLastName(String customerLastName) {
        this.customerLastName = customerLastName;
    }

    /**
     * Returns the account id.
     *
     * @return the textual, zero-padded account id, or {@code null} if unset
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the account id.
     *
     * @param accountId the textual, zero-padded account id
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the account status code.
     *
     * @return the account status code, or {@code null} if unset
     */
    public String getAccountStatus() {
        return accountStatus;
    }

    /**
     * Sets the account status code.
     *
     * @param accountStatus the account status code
     */
    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    /**
     * Returns the card number.
     *
     * @return the textual, zero-padded card number, or {@code null} if unset
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Sets the card number.
     *
     * @param cardNumber the textual, zero-padded card number
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /**
     * Returns the last BMS map name.
     *
     * @return the last map name, or {@code null} if unset
     */
    public String getLastMap() {
        return lastMap;
    }

    /**
     * Sets the last BMS map name.
     *
     * @param lastMap the last map name
     */
    public void setLastMap(String lastMap) {
        this.lastMap = lastMap;
    }

    /**
     * Returns the last BMS mapset name.
     *
     * @return the last mapset name, or {@code null} if unset
     */
    public String getLastMapset() {
        return lastMapset;
    }

    /**
     * Sets the last BMS mapset name.
     *
     * @param lastMapset the last mapset name
     */
    public void setLastMapset(String lastMapset) {
        this.lastMapset = lastMapset;
    }
}
