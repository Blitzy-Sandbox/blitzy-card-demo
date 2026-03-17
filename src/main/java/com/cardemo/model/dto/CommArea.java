/*
 * CommArea.java — Central Session State DTO (COMMAREA Mapping)
 *
 * Migrated from COBOL source artifact:
 *   - app/cpy/COCOM01Y.cpy (CARDDEMO-COMMAREA, lines 19-44)
 *
 * This DTO is the Java equivalent of the COBOL CARDDEMO-COMMAREA defined in
 * COCOM01Y.cpy. In the original COBOL/CICS application, the COMMAREA is the
 * central mechanism for passing state between pseudo-conversational programs.
 * Every CICS RETURN TRANSID includes the COMMAREA as the session state bridge.
 * All 18 online programs read from and write to this shared data area.
 *
 * In the Java target, CommArea serves as a session-scoped state transfer DTO.
 * It replaces the CICS pseudo-conversational model (RETURN TRANSID COMMAREA)
 * with a programmatic session/context object that can be stored in a JWT claim,
 * HTTP session, or passed between services.
 *
 * COBOL COMMAREA structure (5 groups, 16 fields):
 *   01 CARDDEMO-COMMAREA
 *     05 CDEMO-GENERAL-INFO      → fromTranId, fromProgram, toTranId, toProgram,
 *                                   userId, userType, pgmContext
 *     05 CDEMO-CUSTOMER-INFO     → custId, custFname, custMname, custLname
 *     05 CDEMO-ACCOUNT-INFO      → acctId, acctStatus
 *     05 CDEMO-CARD-INFO         → cardNum
 *     05 CDEMO-MORE-INFO         → lastMap, lastMapset
 *
 * COMMAREA flow in original COBOL:
 *   1. COSGN00C populates userId, userType, routes to menu
 *   2. COMEN01C reads userType, routes to selected program
 *   3. COACTUPC/COCRDUPC/etc read acctId, cardNum, perform operations
 *   4. Every online program chain passes and extends this COMMAREA
 *
 * Consumed by: All 18 online service classes (AuthenticationService,
 *   AccountViewService, AccountUpdateService, CardListService,
 *   CardDetailService, CardUpdateService, TransactionListService,
 *   TransactionDetailService, TransactionAddService, BillPaymentService,
 *   ReportSubmissionService, UserListService, UserAddService,
 *   UserUpdateService, UserDeleteService, MainMenuService,
 *   AdminMenuService)
 *
 * Original COBOL version: CardDemo_v1.0-15-g27d6c6f-68
 */
package com.cardemo.model.dto;

import com.cardemo.model.enums.UserType;

import java.io.Serializable;

/**
 * Central session state DTO replacing the CICS pseudo-conversational COMMAREA.
 *
 * <p>Maps all 16 fields from the COBOL {@code CARDDEMO-COMMAREA} structure
 * defined in {@code COCOM01Y.cpy}. Organized into 5 logical groups matching
 * the COBOL 05-level group items:</p>
 *
 * <ul>
 *   <li><strong>General Info</strong>: routing (tranId/program), authentication
 *       (userId/userType), context</li>
 *   <li><strong>Customer Info</strong>: customer identification and name fields</li>
 *   <li><strong>Account Info</strong>: account identification and status</li>
 *   <li><strong>Card Info</strong>: card number (16-digit PAN)</li>
 *   <li><strong>Map Context</strong>: last BMS map/mapset names for screen
 *       navigation tracking</li>
 * </ul>
 *
 * <p>Implements {@link Serializable} to support HTTP session storage and JWT
 * serialization, replacing the CICS RETURN TRANSID COMMAREA mechanism.</p>
 *
 * <p><strong>PCI Note:</strong> The {@link #toString()} method masks the card
 * number, displaying only the last 4 digits to comply with PCI-DSS
 * requirements.</p>
 *
 * @see UserType
 */
public class CommArea implements Serializable {

    private static final long serialVersionUID = 1L;

    // ========================================================================
    // Constants mapping COBOL 88-level conditions on PGM-CONTEXT
    // ========================================================================

    /**
     * Program context: first entry into a program.
     * Maps COBOL: {@code 88 CDEMO-PGM-ENTER VALUE 0}.
     */
    public static final int PGM_ENTER = 0;

    /**
     * Program context: re-entry into a program (pseudo-conversational return).
     * Maps COBOL: {@code 88 CDEMO-PGM-REENTER VALUE 1}.
     */
    public static final int PGM_REENTER = 1;

    // ========================================================================
    // Group 1: General Information (CDEMO-GENERAL-INFO)
    // ========================================================================

    /**
     * Source transaction ID.
     * Maps COBOL: {@code CDEMO-FROM-TRANID PIC X(04)} — max 4 characters.
     */
    private String fromTranId;

    /**
     * Source program name.
     * Maps COBOL: {@code CDEMO-FROM-PROGRAM PIC X(08)} — max 8 characters.
     */
    private String fromProgram;

    /**
     * Target transaction ID for routing.
     * Maps COBOL: {@code CDEMO-TO-TRANID PIC X(04)} — max 4 characters.
     */
    private String toTranId;

    /**
     * Target program name for routing.
     * Maps COBOL: {@code CDEMO-TO-PROGRAM PIC X(08)} — max 8 characters.
     */
    private String toProgram;

    /**
     * Authenticated user ID.
     * Maps COBOL: {@code CDEMO-USER-ID PIC X(08)} — max 8 characters.
     */
    private String userId;

    /**
     * User role/type (admin or regular user).
     * Maps COBOL: {@code CDEMO-USER-TYPE PIC X(01)} with 88-level conditions:
     * <ul>
     *   <li>{@code CDEMO-USRTYP-ADMIN VALUE 'A'} → {@link UserType#ADMIN}</li>
     *   <li>{@code CDEMO-USRTYP-USER VALUE 'U'} → {@link UserType#USER}</li>
     * </ul>
     */
    private UserType userType;

    /**
     * Program context indicating entry mode.
     * Maps COBOL: {@code CDEMO-PGM-CONTEXT PIC 9(01)} with 88-level conditions:
     * <ul>
     *   <li>{@link #PGM_ENTER} (0) — first entry</li>
     *   <li>{@link #PGM_REENTER} (1) — pseudo-conversational re-entry</li>
     * </ul>
     */
    private int pgmContext;

    // ========================================================================
    // Group 2: Customer Information (CDEMO-CUSTOMER-INFO)
    // ========================================================================

    /**
     * Customer ID (9-digit numeric identifier).
     * Maps COBOL: {@code CDEMO-CUST-ID PIC 9(09)}.
     * Stored as String to preserve leading zeros.
     */
    private String custId;

    /**
     * Customer first name.
     * Maps COBOL: {@code CDEMO-CUST-FNAME PIC X(25)} — max 25 characters.
     */
    private String custFname;

    /**
     * Customer middle name.
     * Maps COBOL: {@code CDEMO-CUST-MNAME PIC X(25)} — max 25 characters.
     */
    private String custMname;

    /**
     * Customer last name.
     * Maps COBOL: {@code CDEMO-CUST-LNAME PIC X(25)} — max 25 characters.
     */
    private String custLname;

    // ========================================================================
    // Group 3: Account Information (CDEMO-ACCOUNT-INFO)
    // ========================================================================

    /**
     * Account ID (11-digit numeric identifier).
     * Maps COBOL: {@code CDEMO-ACCT-ID PIC 9(11)}.
     * Stored as String to preserve leading zeros.
     */
    private String acctId;

    /**
     * Account status code (single character).
     * Maps COBOL: {@code CDEMO-ACCT-STATUS PIC X(01)}.
     */
    private String acctStatus;

    // ========================================================================
    // Group 4: Card Information (CDEMO-CARD-INFO)
    // ========================================================================

    /**
     * Card number (16-digit PAN).
     * Maps COBOL: {@code CDEMO-CARD-NUM PIC 9(16)}.
     * Stored as String to preserve the full 16-digit representation.
     * <p><strong>PCI Note:</strong> This field is masked in {@link #toString()}
     * to show only the last 4 digits.</p>
     */
    private String cardNum;

    // ========================================================================
    // Group 5: Map/Screen Context (CDEMO-MORE-INFO)
    // ========================================================================

    /**
     * Last BMS map name used for screen navigation tracking.
     * Maps COBOL: {@code CDEMO-LAST-MAP PIC X(7)} — max 7 characters.
     */
    private String lastMap;

    /**
     * Last BMS mapset name used for screen navigation tracking.
     * Maps COBOL: {@code CDEMO-LAST-MAPSET PIC X(7)} — max 7 characters.
     */
    private String lastMapset;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * No-argument constructor for framework instantiation and deserialization.
     * All fields are initialized to their Java defaults (null for objects,
     * 0 for int).
     */
    public CommArea() {
        // Default constructor — fields initialized to Java defaults
    }

    /**
     * All-arguments constructor for programmatic creation with all 16 COMMAREA
     * fields.
     *
     * @param fromTranId  source transaction ID (max 4 chars)
     * @param fromProgram source program name (max 8 chars)
     * @param toTranId    target transaction ID (max 4 chars)
     * @param toProgram   target program name (max 8 chars)
     * @param userId      authenticated user ID (max 8 chars)
     * @param userType    user role type ({@link UserType#ADMIN} or
     *                    {@link UserType#USER})
     * @param pgmContext  program context (0=enter, 1=re-enter)
     * @param custId      customer ID (9-digit string)
     * @param custFname   customer first name (max 25 chars)
     * @param custMname   customer middle name (max 25 chars)
     * @param custLname   customer last name (max 25 chars)
     * @param acctId      account ID (11-digit string)
     * @param acctStatus  account status code (1 char)
     * @param cardNum     card number (16-digit PAN)
     * @param lastMap     last BMS map name (max 7 chars)
     * @param lastMapset  last BMS mapset name (max 7 chars)
     */
    public CommArea(String fromTranId, String fromProgram, String toTranId,
                    String toProgram, String userId, UserType userType,
                    int pgmContext, String custId, String custFname,
                    String custMname, String custLname, String acctId,
                    String acctStatus, String cardNum, String lastMap,
                    String lastMapset) {
        this.fromTranId = fromTranId;
        this.fromProgram = fromProgram;
        this.toTranId = toTranId;
        this.toProgram = toProgram;
        this.userId = userId;
        this.userType = userType;
        this.pgmContext = pgmContext;
        this.custId = custId;
        this.custFname = custFname;
        this.custMname = custMname;
        this.custLname = custLname;
        this.acctId = acctId;
        this.acctStatus = acctStatus;
        this.cardNum = cardNum;
        this.lastMap = lastMap;
        this.lastMapset = lastMapset;
    }

    // ========================================================================
    // Getters and Setters — Group 1: General Information
    // ========================================================================

    /**
     * Returns the source transaction ID.
     *
     * @return the source transaction ID, or null if not set
     */
    public String getFromTranId() {
        return fromTranId;
    }

    /**
     * Sets the source transaction ID.
     *
     * @param fromTranId the source transaction ID (max 4 chars)
     */
    public void setFromTranId(String fromTranId) {
        this.fromTranId = fromTranId;
    }

    /**
     * Returns the source program name.
     *
     * @return the source program name, or null if not set
     */
    public String getFromProgram() {
        return fromProgram;
    }

    /**
     * Sets the source program name.
     *
     * @param fromProgram the source program name (max 8 chars)
     */
    public void setFromProgram(String fromProgram) {
        this.fromProgram = fromProgram;
    }

    /**
     * Returns the target transaction ID for routing.
     *
     * @return the target transaction ID, or null if not set
     */
    public String getToTranId() {
        return toTranId;
    }

    /**
     * Sets the target transaction ID for routing.
     *
     * @param toTranId the target transaction ID (max 4 chars)
     */
    public void setToTranId(String toTranId) {
        this.toTranId = toTranId;
    }

    /**
     * Returns the target program name for routing.
     *
     * @return the target program name, or null if not set
     */
    public String getToProgram() {
        return toProgram;
    }

    /**
     * Sets the target program name for routing.
     *
     * @param toProgram the target program name (max 8 chars)
     */
    public void setToProgram(String toProgram) {
        this.toProgram = toProgram;
    }

    /**
     * Returns the authenticated user ID.
     *
     * @return the user ID, or null if not set
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the authenticated user ID.
     *
     * @param userId the user ID (max 8 chars)
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the user role/type.
     *
     * @return the user type enum value, or null if not set
     */
    public UserType getUserType() {
        return userType;
    }

    /**
     * Sets the user role/type.
     *
     * @param userType the user type ({@link UserType#ADMIN} or
     *                 {@link UserType#USER})
     */
    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    /**
     * Returns the program context (entry mode).
     *
     * @return the program context value (0=enter, 1=re-enter)
     */
    public int getPgmContext() {
        return pgmContext;
    }

    /**
     * Sets the program context (entry mode).
     *
     * @param pgmContext the program context value (0=enter, 1=re-enter)
     */
    public void setPgmContext(int pgmContext) {
        this.pgmContext = pgmContext;
    }

    // ========================================================================
    // Getters and Setters — Group 2: Customer Information
    // ========================================================================

    /**
     * Returns the customer ID.
     *
     * @return the 9-digit customer ID string, or null if not set
     */
    public String getCustId() {
        return custId;
    }

    /**
     * Sets the customer ID.
     *
     * @param custId the 9-digit customer ID string
     */
    public void setCustId(String custId) {
        this.custId = custId;
    }

    /**
     * Returns the customer first name.
     *
     * @return the customer first name, or null if not set
     */
    public String getCustFname() {
        return custFname;
    }

    /**
     * Sets the customer first name.
     *
     * @param custFname the customer first name (max 25 chars)
     */
    public void setCustFname(String custFname) {
        this.custFname = custFname;
    }

    /**
     * Returns the customer middle name.
     *
     * @return the customer middle name, or null if not set
     */
    public String getCustMname() {
        return custMname;
    }

    /**
     * Sets the customer middle name.
     *
     * @param custMname the customer middle name (max 25 chars)
     */
    public void setCustMname(String custMname) {
        this.custMname = custMname;
    }

    /**
     * Returns the customer last name.
     *
     * @return the customer last name, or null if not set
     */
    public String getCustLname() {
        return custLname;
    }

    /**
     * Sets the customer last name.
     *
     * @param custLname the customer last name (max 25 chars)
     */
    public void setCustLname(String custLname) {
        this.custLname = custLname;
    }

    // ========================================================================
    // Getters and Setters — Group 3: Account Information
    // ========================================================================

    /**
     * Returns the account ID.
     *
     * @return the 11-digit account ID string, or null if not set
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * Sets the account ID.
     *
     * @param acctId the 11-digit account ID string
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the account status code.
     *
     * @return the single-character account status, or null if not set
     */
    public String getAcctStatus() {
        return acctStatus;
    }

    /**
     * Sets the account status code.
     *
     * @param acctStatus the single-character account status
     */
    public void setAcctStatus(String acctStatus) {
        this.acctStatus = acctStatus;
    }

    // ========================================================================
    // Getters and Setters — Group 4: Card Information
    // ========================================================================

    /**
     * Returns the card number (16-digit PAN).
     *
     * @return the 16-digit card number string, or null if not set
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number (16-digit PAN).
     *
     * @param cardNum the 16-digit card number string
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    // ========================================================================
    // Getters and Setters — Group 5: Map/Screen Context
    // ========================================================================

    /**
     * Returns the last BMS map name.
     *
     * @return the last map name (max 7 chars), or null if not set
     */
    public String getLastMap() {
        return lastMap;
    }

    /**
     * Sets the last BMS map name.
     *
     * @param lastMap the last map name (max 7 chars)
     */
    public void setLastMap(String lastMap) {
        this.lastMap = lastMap;
    }

    /**
     * Returns the last BMS mapset name.
     *
     * @return the last mapset name (max 7 chars), or null if not set
     */
    public String getLastMapset() {
        return lastMapset;
    }

    /**
     * Sets the last BMS mapset name.
     *
     * @param lastMapset the last mapset name (max 7 chars)
     */
    public void setLastMapset(String lastMapset) {
        this.lastMapset = lastMapset;
    }

    // ========================================================================
    // 88-Level Condition Equivalents
    // ========================================================================

    /**
     * Checks if the current user is an administrator.
     * Equivalent to COBOL 88-level condition: {@code IF CDEMO-USRTYP-ADMIN}.
     *
     * @return {@code true} if userType is {@link UserType#ADMIN},
     *         {@code false} otherwise
     */
    public boolean isAdmin() {
        return UserType.ADMIN == this.userType;
    }

    /**
     * Checks if the current user is a regular (non-admin) user.
     * Equivalent to COBOL 88-level condition: {@code IF CDEMO-USRTYP-USER}.
     *
     * @return {@code true} if userType is {@link UserType#USER},
     *         {@code false} otherwise
     */
    public boolean isRegularUser() {
        return UserType.USER == this.userType;
    }

    /**
     * Checks if this is a first-entry program context.
     * Equivalent to COBOL 88-level condition: {@code IF CDEMO-PGM-ENTER}.
     *
     * @return {@code true} if pgmContext equals {@link #PGM_ENTER} (0)
     */
    public boolean isEnterContext() {
        return PGM_ENTER == this.pgmContext;
    }

    /**
     * Checks if this is a re-entry (pseudo-conversational return) context.
     * Equivalent to COBOL 88-level condition: {@code IF CDEMO-PGM-REENTER}.
     *
     * @return {@code true} if pgmContext equals {@link #PGM_REENTER} (1)
     */
    public boolean isReenterContext() {
        return PGM_REENTER == this.pgmContext;
    }

    // ========================================================================
    // toString — with PCI-compliant card number masking
    // ========================================================================

    /**
     * Returns a string representation of this COMMAREA state.
     *
     * <p>Includes routing information, authentication context,
     * customer/account/card identifiers, and map navigation state.
     * The card number is masked for PCI-DSS compliance, showing only
     * the last 4 digits (e.g., "************1234").</p>
     *
     * @return formatted string with all COMMAREA fields
     */
    @Override
    public String toString() {
        return "CommArea{" +
                "fromTranId='" + fromTranId + '\'' +
                ", fromProgram='" + fromProgram + '\'' +
                ", toTranId='" + toTranId + '\'' +
                ", toProgram='" + toProgram + '\'' +
                ", userId='" + userId + '\'' +
                ", userType=" + userType +
                ", pgmContext=" + pgmContext +
                ", custId='" + custId + '\'' +
                ", custFname='" + custFname + '\'' +
                ", custMname='" + custMname + '\'' +
                ", custLname='" + custLname + '\'' +
                ", acctId='" + acctId + '\'' +
                ", acctStatus='" + acctStatus + '\'' +
                ", cardNum='" + maskCardNumber(cardNum) + '\'' +
                ", lastMap='" + lastMap + '\'' +
                ", lastMapset='" + lastMapset + '\'' +
                '}';
    }

    /**
     * Masks a card number for PCI-DSS compliant logging and display.
     *
     * <p>Shows only the last 4 digits, replacing preceding digits with
     * asterisks. Returns "null" for null input and the original value
     * for strings shorter than 4 characters (which would not be valid
     * card numbers).</p>
     *
     * @param number the card number to mask
     * @return the masked card number (e.g., "************1234")
     */
    private static String maskCardNumber(String number) {
        if (number == null) {
            return "null";
        }
        int length = number.length();
        if (length <= 4) {
            return number;
        }
        return "*".repeat(length - 4) + number.substring(length - 4);
    }
}
