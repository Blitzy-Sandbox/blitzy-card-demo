package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;

/**
 * Stateless per-request session/context object. Java equivalent of the COBOL
 * {@code CARDDEMO-COMMAREA} group item defined in copybook {@code COCOM01Y}
 * (source commit {@code 27d6c6f}).
 *
 * <p>On the mainframe this structure carried conversational state between the
 * pseudo-conversational CICS program invocations of CardDemo. In the migrated
 * application it is a plain, mutable context object populated incrementally as a
 * request flows through the service layers (for example: identity after
 * authentication, then the selected account, then the selected card). Its
 * identity-related fields ({@code userId}, {@code userType}, {@code customerId},
 * {@code accountId}, {@code cardNumber}) also surface as JWT claims, so no
 * server-held conversational state is retained between requests.</p>
 *
 * <p>This is a JavaBean-style data carrier (no-argument constructor plus
 * getters and setters); it intentionally carries no JPA, Bean Validation, or
 * other framework annotations and depends only on {@link UserType}.</p>
 */
public class CommArea {

    /** Program-context code for an initial screen entry (COBOL {@code CDEMO-PGM-ENTER VALUE 0}). */
    public static final int PGM_ENTER = 0;

    /** Program-context code for a screen re-entry (COBOL {@code CDEMO-PGM-REENTER VALUE 1}). */
    public static final int PGM_REENTER = 1;

    // --- CDEMO-GENERAL-INFO ---
    private String fromTranId;          // CDEMO-FROM-TRANID  PIC X(04)
    private String fromProgram;         // CDEMO-FROM-PROGRAM PIC X(08)
    private String toTranId;            // CDEMO-TO-TRANID    PIC X(04)
    private String toProgram;           // CDEMO-TO-PROGRAM   PIC X(08)
    private String userId;              // CDEMO-USER-ID      PIC X(08)
    private UserType userType;          // CDEMO-USER-TYPE    PIC X(01) 88 'A'/'U'
    private int pgmContext;             // CDEMO-PGM-CONTEXT  PIC 9(01) 88 0/1

    // --- CDEMO-CUSTOMER-INFO ---
    private String customerId;          // CDEMO-CUST-ID      PIC 9(09)
    private String customerFirstName;   // CDEMO-CUST-FNAME   PIC X(25)
    private String customerMiddleName;  // CDEMO-CUST-MNAME   PIC X(25)
    private String customerLastName;    // CDEMO-CUST-LNAME   PIC X(25)

    // --- CDEMO-ACCOUNT-INFO ---
    private String accountId;           // CDEMO-ACCT-ID      PIC 9(11)
    private String accountStatus;       // CDEMO-ACCT-STATUS  PIC X(01)

    // --- CDEMO-CARD-INFO ---
    private String cardNumber;          // CDEMO-CARD-NUM     PIC 9(16)

    // --- CDEMO-MORE-INFO ---
    private String lastMap;             // CDEMO-LAST-MAP     PIC X(7)
    private String lastMapset;          // CDEMO-LAST-MAPSET  PIC X(7)

    /**
     * Creates an empty context. Fields are populated incrementally by the
     * service layers (and by JSON deserialization) as a request progresses.
     */
    public CommArea() {
    }

    // ---------------------------------------------------------------------
    // CDEMO-GENERAL-INFO accessors
    // ---------------------------------------------------------------------

    /**
     * Returns the originating transaction identifier (COBOL {@code CDEMO-FROM-TRANID}).
     *
     * @return the from-transaction id, or {@code null} if unset
     */
    public String getFromTranId() {
        return fromTranId;
    }

    /**
     * Sets the originating transaction identifier (COBOL {@code CDEMO-FROM-TRANID}).
     *
     * @param fromTranId the from-transaction id
     */
    public void setFromTranId(String fromTranId) {
        this.fromTranId = fromTranId;
    }

    /**
     * Returns the originating program name (COBOL {@code CDEMO-FROM-PROGRAM}).
     *
     * @return the from-program name, or {@code null} if unset
     */
    public String getFromProgram() {
        return fromProgram;
    }

    /**
     * Sets the originating program name (COBOL {@code CDEMO-FROM-PROGRAM}).
     *
     * @param fromProgram the from-program name
     */
    public void setFromProgram(String fromProgram) {
        this.fromProgram = fromProgram;
    }

    /**
     * Returns the target transaction identifier (COBOL {@code CDEMO-TO-TRANID}).
     *
     * @return the to-transaction id, or {@code null} if unset
     */
    public String getToTranId() {
        return toTranId;
    }

    /**
     * Sets the target transaction identifier (COBOL {@code CDEMO-TO-TRANID}).
     *
     * @param toTranId the to-transaction id
     */
    public void setToTranId(String toTranId) {
        this.toTranId = toTranId;
    }

    /**
     * Returns the target program name (COBOL {@code CDEMO-TO-PROGRAM}).
     *
     * @return the to-program name, or {@code null} if unset
     */
    public String getToProgram() {
        return toProgram;
    }

    /**
     * Sets the target program name (COBOL {@code CDEMO-TO-PROGRAM}).
     *
     * @param toProgram the to-program name
     */
    public void setToProgram(String toProgram) {
        this.toProgram = toProgram;
    }

    /**
     * Returns the authenticated user identifier (COBOL {@code CDEMO-USER-ID}).
     *
     * @return the user id, or {@code null} if unset
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the authenticated user identifier (COBOL {@code CDEMO-USER-ID}).
     *
     * @param userId the user id
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the user authorization type (COBOL {@code CDEMO-USER-TYPE},
     * {@code 88 CDEMO-USRTYP-ADMIN 'A'} / {@code 88 CDEMO-USRTYP-USER 'U'}).
     *
     * @return the {@link UserType}, or {@code null} if unset
     */
    public UserType getUserType() {
        return userType;
    }

    /**
     * Sets the user authorization type (COBOL {@code CDEMO-USER-TYPE}).
     *
     * @param userType the {@link UserType}
     */
    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    /**
     * Returns the program-context flag (COBOL {@code CDEMO-PGM-CONTEXT}).
     *
     * @return {@link #PGM_ENTER} for initial entry or {@link #PGM_REENTER} for re-entry
     */
    public int getPgmContext() {
        return pgmContext;
    }

    /**
     * Sets the program-context flag (COBOL {@code CDEMO-PGM-CONTEXT}).
     *
     * @param pgmContext {@link #PGM_ENTER} (0) for initial entry or {@link #PGM_REENTER} (1) for re-entry
     */
    public void setPgmContext(int pgmContext) {
        this.pgmContext = pgmContext;
    }

    // ---------------------------------------------------------------------
    // CDEMO-CUSTOMER-INFO accessors
    // ---------------------------------------------------------------------

    /**
     * Returns the customer identifier (COBOL {@code CDEMO-CUST-ID PIC 9(09)}),
     * preserved as a fixed-width, zero-padded string rather than a numeric type.
     *
     * @return the customer id, or {@code null} if unset
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Sets the customer identifier (COBOL {@code CDEMO-CUST-ID PIC 9(09)}).
     *
     * @param customerId the customer id (fixed-width, zero-padded representation)
     */
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    /**
     * Returns the customer first name (COBOL {@code CDEMO-CUST-FNAME}).
     *
     * @return the customer first name, or {@code null} if unset
     */
    public String getCustomerFirstName() {
        return customerFirstName;
    }

    /**
     * Sets the customer first name (COBOL {@code CDEMO-CUST-FNAME}).
     *
     * @param customerFirstName the customer first name
     */
    public void setCustomerFirstName(String customerFirstName) {
        this.customerFirstName = customerFirstName;
    }

    /**
     * Returns the customer middle name (COBOL {@code CDEMO-CUST-MNAME}).
     *
     * @return the customer middle name, or {@code null} if unset
     */
    public String getCustomerMiddleName() {
        return customerMiddleName;
    }

    /**
     * Sets the customer middle name (COBOL {@code CDEMO-CUST-MNAME}).
     *
     * @param customerMiddleName the customer middle name
     */
    public void setCustomerMiddleName(String customerMiddleName) {
        this.customerMiddleName = customerMiddleName;
    }

    /**
     * Returns the customer last name (COBOL {@code CDEMO-CUST-LNAME}).
     *
     * @return the customer last name, or {@code null} if unset
     */
    public String getCustomerLastName() {
        return customerLastName;
    }

    /**
     * Sets the customer last name (COBOL {@code CDEMO-CUST-LNAME}).
     *
     * @param customerLastName the customer last name
     */
    public void setCustomerLastName(String customerLastName) {
        this.customerLastName = customerLastName;
    }

    // ---------------------------------------------------------------------
    // CDEMO-ACCOUNT-INFO accessors
    // ---------------------------------------------------------------------

    /**
     * Returns the account identifier (COBOL {@code CDEMO-ACCT-ID PIC 9(11)}),
     * preserved as a fixed-width, zero-padded string rather than a numeric type.
     *
     * @return the account id, or {@code null} if unset
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the account identifier (COBOL {@code CDEMO-ACCT-ID PIC 9(11)}).
     *
     * @param accountId the account id (fixed-width, zero-padded representation)
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the account status code (COBOL {@code CDEMO-ACCT-STATUS}).
     *
     * @return the account status, or {@code null} if unset
     */
    public String getAccountStatus() {
        return accountStatus;
    }

    /**
     * Sets the account status code (COBOL {@code CDEMO-ACCT-STATUS}).
     *
     * @param accountStatus the account status
     */
    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    // ---------------------------------------------------------------------
    // CDEMO-CARD-INFO accessors
    // ---------------------------------------------------------------------

    /**
     * Returns the card number (COBOL {@code CDEMO-CARD-NUM PIC 9(16)}),
     * preserved as a fixed-width, zero-padded string rather than a numeric type.
     *
     * @return the card number, or {@code null} if unset
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Sets the card number (COBOL {@code CDEMO-CARD-NUM PIC 9(16)}).
     *
     * @param cardNumber the card number (fixed-width, zero-padded representation)
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    // ---------------------------------------------------------------------
    // CDEMO-MORE-INFO accessors
    // ---------------------------------------------------------------------

    /**
     * Returns the last map name (COBOL {@code CDEMO-LAST-MAP}).
     *
     * @return the last map name, or {@code null} if unset
     */
    public String getLastMap() {
        return lastMap;
    }

    /**
     * Sets the last map name (COBOL {@code CDEMO-LAST-MAP}).
     *
     * @param lastMap the last map name
     */
    public void setLastMap(String lastMap) {
        this.lastMap = lastMap;
    }

    /**
     * Returns the last mapset name (COBOL {@code CDEMO-LAST-MAPSET}).
     *
     * @return the last mapset name, or {@code null} if unset
     */
    public String getLastMapset() {
        return lastMapset;
    }

    /**
     * Sets the last mapset name (COBOL {@code CDEMO-LAST-MAPSET}).
     *
     * @param lastMapset the last mapset name
     */
    public void setLastMapset(String lastMapset) {
        this.lastMapset = lastMapset;
    }
}
