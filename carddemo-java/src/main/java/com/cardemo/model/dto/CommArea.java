package com.cardemo.model.dto;

import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Central session / context Data Transfer Object that carries the CardDemo
 * pseudo-conversational state across REST interactions.
 *
 * <p>This DTO is the Java 25 / Spring Boot 3.x replacement for the COBOL
 * communication area {@code 01 CARDDEMO-COMMAREA}, defined by the copybook
 * {@code app/cpy/COCOM01Y.cpy}. On the mainframe that 01-level group was
 * {@code COPY}-included by <em>every one of the 18 online CICS programs</em>
 * ({@code COSGN00C}, {@code COMEN01C}, {@code COACTVWC}, {@code COACTUPC},
 * {@code COCRDLIC}, &hellip;) and was threaded from screen to screen by the
 * pseudo-conversational idiom {@code EXEC CICS RETURN TRANSID(...)
 * COMMAREA(...) END-EXEC}. Between two 3270 interactions CICS held no program
 * state in memory; the entire conversational context &mdash; who is signed in,
 * which transaction/program is handing off to which, and which account / card /
 * customer is currently in focus &mdash; lived <em>only</em> inside this record
 * and was passed back to the program on the next event.</p>
 *
 * <h2>Technology substitution &mdash; CICS COMMAREA &rarr; token / session context (AAP &sect;0.1.2, &sect;0.7.1)</h2>
 * <p>The migrated application is stateless and RESTful, so there is no CICS
 * COMMAREA and no pseudo-conversational {@code RETURN TRANSID}. The
 * deterministic transformation rule
 * {@code CICS RETURN TRANSID COMMAREA -> stateless REST with context
 * propagation} applies: this same context is now carried by
 * <strong>token-based state (a JWT) or a session context</strong> rather than a
 * CICS control block. The shape of the state is preserved exactly &mdash; this
 * class is a faithful, flat mirror of {@code CARDDEMO-COMMAREA} with the same
 * fields and the same field lengths &mdash; but its <em>lifetime</em> changes
 * from "one CICS conversation" to "one authenticated session / token". That
 * single substitution is the only modernization applied here; per the Minimal
 * Change Clause (AAP &sect;0.7.1) no business field is added, removed or
 * reinterpreted. The COBOL {@code COPY COCOM01Y} directive resolves, under the
 * dependency rule in AAP &sect;0.4.2, to {@code import
 * com.cardemo.model.dto.CommArea}.</p>
 *
 * <p>In the migrated system this DTO is consumed by the {@code controller} and
 * {@code service} layers for routing and identity/context propagation: the
 * sign-on flow populates the user identity ({@code userId}, {@code userType}),
 * the menu/navigation flow populates the program-handoff fields
 * ({@code fromTranId}/{@code fromProgram}/{@code toTranId}/{@code toProgram}),
 * and the account/card/transaction flows populate the in-focus
 * customer/account/card. It is a pure boundary type &mdash; it holds no business
 * logic, performs no I/O and keeps no static mutable state. Consistent with the
 * folder rule, it is <strong>not</strong> a JPA entity and deliberately imports
 * <strong>nothing</strong> from {@code com.cardemo.model.entity}; mapping
 * between this context and the persistent entities is the service layer's
 * responsibility.</p>
 *
 * <h2>Original COBOL structure (app/cpy/COCOM01Y.cpy &mdash; {@code 01 CARDDEMO-COMMAREA})</h2>
 * <pre>{@code
 * 01 CARDDEMO-COMMAREA.
 *    05 CDEMO-GENERAL-INFO.
 *       10 CDEMO-FROM-TRANID    PIC X(04).   <-- fromTranId
 *       10 CDEMO-FROM-PROGRAM   PIC X(08).   <-- fromProgram
 *       10 CDEMO-TO-TRANID      PIC X(04).   <-- toTranId
 *       10 CDEMO-TO-PROGRAM     PIC X(08).   <-- toProgram
 *       10 CDEMO-USER-ID        PIC X(08).   <-- userId
 *       10 CDEMO-USER-TYPE      PIC X(01).   <-- userType   (88 'A'/'U')
 *       10 CDEMO-PGM-CONTEXT    PIC 9(01).   <-- programContext (88 0/1)
 *    05 CDEMO-CUSTOMER-INFO.
 *       10 CDEMO-CUST-ID        PIC 9(09).   <-- customerId
 *       10 CDEMO-CUST-FNAME     PIC X(25).   <-- custFirstName
 *       10 CDEMO-CUST-MNAME     PIC X(25).   <-- custMiddleName
 *       10 CDEMO-CUST-LNAME     PIC X(25).   <-- custLastName
 *    05 CDEMO-ACCOUNT-INFO.
 *       10 CDEMO-ACCT-ID        PIC 9(11).   <-- accountId
 *       10 CDEMO-ACCT-STATUS    PIC X(01).   <-- accountStatus
 *    05 CDEMO-CARD-INFO.
 *       10 CDEMO-CARD-NUM       PIC 9(16).   <-- cardNumber
 *    05 CDEMO-MORE-INFO.
 *       10 CDEMO-LAST-MAP       PIC X(7).    <-- lastMap
 *       10 CDEMO-LAST-MAPSET    PIC X(7).    <-- lastMapset
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Byte-faithful field lengths (AAP &sect;0.7.2).</strong> Every
 *       textual field carries a {@link Size @Size(max = n)} equal to its COBOL
 *       {@code PIC X(n)} width, preserving the external-interface width exactly.
 *       {@code cardNumber} additionally carries a {@link Pattern @Pattern} that
 *       enforces a digit-only shape, and the two numeric identifiers carry
 *       {@link Digits @Digits} so their {@code PIC 9(n)} digit-widths are
 *       preserved.</li>
 *   <li><strong>{@code CDEMO-USER-TYPE} &rarr; {@link UserType} enum.</strong>
 *       The 1-byte {@code PIC X(01)} field with condition names
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
 *       {@code 88 CDEMO-USRTYP-USER VALUE 'U'} maps to the shared
 *       {@link UserType} enum (constants {@code ADMIN('A')} / {@code USER('U')})
 *       rather than to a raw {@code String}, giving type-safe role routing while
 *       preserving the byte-exact 'A'/'U' codes through
 *       {@link UserType#getCode()} / {@link UserType#fromCode(String)}.</li>
 *   <li><strong>{@code CDEMO-PGM-CONTEXT} &rarr; {@code int}.</strong> The
 *       1-digit {@code PIC 9(01)} flag with condition names
 *       {@code 88 CDEMO-PGM-ENTER VALUE 0} and
 *       {@code 88 CDEMO-PGM-REENTER VALUE 1} is modeled as a primitive
 *       {@code int} (no new enum is introduced &mdash; that is out of scope per
 *       the AAP). A freshly constructed instance defaults to {@code 0}, which is
 *       exactly the COBOL {@code ENTER} (first-entry) state.</li>
 *   <li><strong>{@code CDEMO-CARD-NUM} kept as {@link String}.</strong> Although
 *       the copybook types it {@code PIC 9(16)}, the 16-digit card number (PAN)
 *       is held as a fixed-width {@link String}, never a numeric type, so leading
 *       zeros and the exact 16-digit width are never lost.</li>
 *   <li><strong>No floating point (AAP &sect;0.7.3).</strong> The communication
 *       area has no {@code COMP-3}/{@code COMP}/{@code PIC ...V99} decimal field,
 *       so there is no {@code float}, {@code double} or {@link java.math.BigDecimal}
 *       anywhere in this DTO.</li>
 * </ul>
 *
 * <h2>Deliberately excluded &mdash; AID / PF keys (AAP &sect;0.4.2)</h2>
 * <p>The COBOL communication area carries <strong>no</strong> AID (attention
 * identifier) or PF-key field &mdash; those lived in the BMS symbolic maps and
 * the CICS {@code DFHAID}/{@code DFHBMSCA} system copybooks, never in
 * {@code COCOM01Y}. Consistent with AAP &sect;0.4.2 (AID keys map to distinct
 * REST endpoints, not to a carried field), none are added here.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see com.cardemo.model.enums.UserType
 * @see jakarta.validation.constraints.Size
 * @see jakarta.validation.constraints.Pattern
 * @see jakarta.validation.constraints.Digits
 */
public class CommArea {

    // ---------------------------------------------------------------------
    // CDEMO-GENERAL-INFO -- routing handoff + signed-in user identity
    // ---------------------------------------------------------------------

    /**
     * Transaction identifier the navigation is coming <em>from</em>.
     *
     * <p>Migrated from {@code CDEMO-FROM-TRANID PIC X(04)} &mdash; the 4-character
     * CICS transaction id (for example {@code CM00}, {@code CAVW}) of the screen
     * that handed control to the current one. In the REST target it records the
     * source route for context propagation.</p>
     */
    // CDEMO-FROM-TRANID PIC X(04) -> 4-char CICS tranid -> String (max length 4)
    @Size(max = 4, message = "From-transaction id must not exceed 4 characters")
    private String fromTranId;

    /**
     * Program name the navigation is coming <em>from</em>.
     *
     * <p>Migrated from {@code CDEMO-FROM-PROGRAM PIC X(08)} &mdash; the
     * 8-character name of the COBOL program (for example {@code COMEN01C}) that
     * handed control to the current one.</p>
     */
    // CDEMO-FROM-PROGRAM PIC X(08) -> 8-char program name -> String (max length 8)
    @Size(max = 8, message = "From-program name must not exceed 8 characters")
    private String fromProgram;

    /**
     * Transaction identifier the navigation is going <em>to</em>.
     *
     * <p>Migrated from {@code CDEMO-TO-TRANID PIC X(04)} &mdash; the 4-character
     * CICS transaction id of the screen that control is being routed to.</p>
     */
    // CDEMO-TO-TRANID PIC X(04) -> 4-char CICS tranid -> String (max length 4)
    @Size(max = 4, message = "To-transaction id must not exceed 4 characters")
    private String toTranId;

    /**
     * Program name the navigation is going <em>to</em>.
     *
     * <p>Migrated from {@code CDEMO-TO-PROGRAM PIC X(08)} &mdash; the 8-character
     * name of the COBOL program control is being routed to.</p>
     */
    // CDEMO-TO-PROGRAM PIC X(08) -> 8-char program name -> String (max length 8)
    @Size(max = 8, message = "To-program name must not exceed 8 characters")
    private String toProgram;

    /**
     * The signed-in user id.
     *
     * <p>Migrated from {@code CDEMO-USER-ID PIC X(08)} &mdash; the 8-character
     * {@code USRSEC} user id established at sign-on ({@code COSGN00C}) and carried
     * through the conversation for authorization and audit.</p>
     */
    // CDEMO-USER-ID PIC X(08) -> 8-char USRSEC user id -> String (max length 8)
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    private String userId;

    /**
     * The signed-in user's role (type).
     *
     * <p>Migrated from {@code CDEMO-USER-TYPE PIC X(01)} with condition names
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'}. The legacy programs branched on
     * these 88-levels to route administrators to the admin menu ({@code COADM01C})
     * and regular users to the main menu ({@code COMEN01C}).</p>
     */
    // COBOL substitution: CDEMO-USER-TYPE PIC X(01) (88 'A'=ADMIN / 'U'=USER)
    //  -> type-safe com.cardemo.model.enums.UserType enum (NOT a raw String / local enum).
    private UserType userType;

    /**
     * Program-context flag distinguishing first entry from re-entry.
     *
     * <p>Migrated from {@code CDEMO-PGM-CONTEXT PIC 9(01)} with condition names
     * {@code 88 CDEMO-PGM-ENTER VALUE 0} and {@code 88 CDEMO-PGM-REENTER VALUE 1}.
     * In the pseudo-conversational model a program inspected this flag on each
     * invocation to decide whether it was being entered for the first time
     * ({@code 0 = ENTER} &mdash; paint the initial screen) or re-entered after the
     * user pressed a key ({@code 1 = REENTER} &mdash; process the input).</p>
     *
     * <p>Modeled as a primitive {@code int} so it is never {@code null}; the
     * default value {@code 0} corresponds to {@code ENTER}, matching the COBOL
     * initialized state. The convenience predicates {@link #isEnter()} and
     * {@link #isReenter()} mirror the two 88-levels.</p>
     */
    // COBOL substitution: CDEMO-PGM-CONTEXT PIC 9(01) (88 0=ENTER / 1=REENTER)
    //  -> primitive int (no enum created; out of scope per AAP). 0 = ENTER, 1 = REENTER.
    private int programContext;

    // ---------------------------------------------------------------------
    // CDEMO-CUSTOMER-INFO -- in-focus customer demographic context
    // ---------------------------------------------------------------------

    /**
     * Identifier of the customer currently in focus.
     *
     * <p>Migrated from {@code CDEMO-CUST-ID PIC 9(09)} &mdash; a 9-digit customer
     * number. Held as a {@link Long} to preserve numeric semantics; the
     * {@link Digits @Digits(integer = 9, fraction = 0)} constraint preserves the
     * 9-digit width of the original {@code PIC 9(09)} field.</p>
     */
    // CDEMO-CUST-ID PIC 9(09) -> 9-digit customer number -> Long (9 integer digits)
    @Digits(integer = 9, fraction = 0, message = "Customer ID must be at most 9 digits")
    private Long customerId;

    /**
     * First name of the customer currently in focus.
     *
     * <p>Migrated from {@code CDEMO-CUST-FNAME PIC X(25)} &mdash; a 25-character
     * alphanumeric name field.</p>
     */
    // CDEMO-CUST-FNAME PIC X(25) -> 25-char first name -> String (max length 25)
    @Size(max = 25, message = "Customer first name must not exceed 25 characters")
    private String custFirstName;

    /**
     * Middle name of the customer currently in focus.
     *
     * <p>Migrated from {@code CDEMO-CUST-MNAME PIC X(25)} &mdash; a 25-character
     * alphanumeric name field.</p>
     */
    // CDEMO-CUST-MNAME PIC X(25) -> 25-char middle name -> String (max length 25)
    @Size(max = 25, message = "Customer middle name must not exceed 25 characters")
    private String custMiddleName;

    /**
     * Last name of the customer currently in focus.
     *
     * <p>Migrated from {@code CDEMO-CUST-LNAME PIC X(25)} &mdash; a 25-character
     * alphanumeric name field.</p>
     */
    // CDEMO-CUST-LNAME PIC X(25) -> 25-char last name -> String (max length 25)
    @Size(max = 25, message = "Customer last name must not exceed 25 characters")
    private String custLastName;

    // ---------------------------------------------------------------------
    // CDEMO-ACCOUNT-INFO -- in-focus account context
    // ---------------------------------------------------------------------

    /**
     * Identifier of the account currently in focus.
     *
     * <p>Migrated from {@code CDEMO-ACCT-ID PIC 9(11)} &mdash; an 11-digit account
     * number. Held as a {@link Long} to preserve numeric semantics; the
     * {@link Digits @Digits(integer = 11, fraction = 0)} constraint preserves the
     * 11-digit width of the original {@code PIC 9(11)} field.</p>
     */
    // CDEMO-ACCT-ID PIC 9(11) -> 11-digit account number -> Long (11 integer digits)
    @Digits(integer = 11, fraction = 0, message = "Account ID must be at most 11 digits")
    private Long accountId;

    /**
     * Status flag of the account currently in focus.
     *
     * <p>Migrated from {@code CDEMO-ACCT-STATUS PIC X(01)} &mdash; a
     * single-character active-status flag (typically {@code 'Y'} or {@code 'N'}),
     * modeled as a {@link String} of length one.</p>
     */
    // CDEMO-ACCT-STATUS PIC X(01) -> single-character status flag -> String (max length 1)
    @Size(max = 1, message = "Account status must be a single character")
    private String accountStatus;

    // ---------------------------------------------------------------------
    // CDEMO-CARD-INFO -- in-focus card context
    // ---------------------------------------------------------------------

    /**
     * Card number (PAN) of the card currently in focus.
     *
     * <p>Migrated from {@code CDEMO-CARD-NUM PIC 9(16)} &mdash; a 16-digit card
     * number. Although the copybook types it numerically, it is held as a
     * fixed-width {@link String} (never a numeric type) so leading zeros and the
     * exact 16-digit width are preserved: {@link Pattern @Pattern} enforces a
     * digit-only shape (0 to 16 digits) and {@link Size @Size} preserves the
     * 16-character width.</p>
     *
     * <p><strong>Privacy:</strong> the card number is a sensitive PAN and is
     * masked by {@link #toString()} so it never leaks into logs or diagnostics.</p>
     */
    // CDEMO-CARD-NUM PIC 9(16) -> 16-digit card number (PAN) -> String (max length 16, digits only)
    @Size(max = 16, message = "Card number must not exceed 16 characters")
    @Pattern(regexp = "\\d{0,16}", message = "Card number must contain only digits (up to 16)")
    private String cardNumber;

    // ---------------------------------------------------------------------
    // CDEMO-MORE-INFO -- last rendered map / mapset (screen-resume context)
    // ---------------------------------------------------------------------

    /**
     * Name of the last BMS map rendered.
     *
     * <p>Migrated from {@code CDEMO-LAST-MAP PIC X(7)} &mdash; the 7-character
     * name of the most recently displayed 3270 map, retained so a program could
     * resume on the correct screen.</p>
     */
    // CDEMO-LAST-MAP PIC X(7) -> 7-char BMS map name -> String (max length 7)
    @Size(max = 7, message = "Last map name must not exceed 7 characters")
    private String lastMap;

    /**
     * Name of the last BMS mapset rendered.
     *
     * <p>Migrated from {@code CDEMO-LAST-MAPSET PIC X(7)} &mdash; the 7-character
     * name of the most recently displayed 3270 mapset.</p>
     */
    // CDEMO-LAST-MAPSET PIC X(7) -> 7-char BMS mapset name -> String (max length 7)
    @Size(max = 7, message = "Last mapset name must not exceed 7 characters")
    private String lastMapset;

    /**
     * Default no-argument constructor required by the JSON binder (Jackson) to
     * instantiate this DTO reflectively before populating its properties via
     * setters. The primitive {@link #programContext} therefore starts at
     * {@code 0} ({@code ENTER}), matching the COBOL initialized state.
     */
    public CommArea() {
        // Intentionally empty: Jackson instantiates then sets fields via setters.
    }

    // ---------------------------------------------------------------------
    // Accessors -- CDEMO-GENERAL-INFO
    // ---------------------------------------------------------------------

    /**
     * Returns the from-transaction id ({@code CDEMO-FROM-TRANID}).
     *
     * @return the source transaction id, or {@code null} if unset
     */
    public String getFromTranId() {
        return fromTranId;
    }

    /**
     * Sets the from-transaction id ({@code CDEMO-FROM-TRANID}).
     *
     * @param fromTranId the source transaction id to set (up to 4 characters)
     */
    public void setFromTranId(String fromTranId) {
        this.fromTranId = fromTranId;
    }

    /**
     * Returns the from-program name ({@code CDEMO-FROM-PROGRAM}).
     *
     * @return the source program name, or {@code null} if unset
     */
    public String getFromProgram() {
        return fromProgram;
    }

    /**
     * Sets the from-program name ({@code CDEMO-FROM-PROGRAM}).
     *
     * @param fromProgram the source program name to set (up to 8 characters)
     */
    public void setFromProgram(String fromProgram) {
        this.fromProgram = fromProgram;
    }

    /**
     * Returns the to-transaction id ({@code CDEMO-TO-TRANID}).
     *
     * @return the target transaction id, or {@code null} if unset
     */
    public String getToTranId() {
        return toTranId;
    }

    /**
     * Sets the to-transaction id ({@code CDEMO-TO-TRANID}).
     *
     * @param toTranId the target transaction id to set (up to 4 characters)
     */
    public void setToTranId(String toTranId) {
        this.toTranId = toTranId;
    }

    /**
     * Returns the to-program name ({@code CDEMO-TO-PROGRAM}).
     *
     * @return the target program name, or {@code null} if unset
     */
    public String getToProgram() {
        return toProgram;
    }

    /**
     * Sets the to-program name ({@code CDEMO-TO-PROGRAM}).
     *
     * @param toProgram the target program name to set (up to 8 characters)
     */
    public void setToProgram(String toProgram) {
        this.toProgram = toProgram;
    }

    /**
     * Returns the signed-in user id ({@code CDEMO-USER-ID}).
     *
     * @return the user id, or {@code null} if unset
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the signed-in user id ({@code CDEMO-USER-ID}).
     *
     * @param userId the user id to set (up to 8 characters)
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the signed-in user's role ({@code CDEMO-USER-TYPE}).
     *
     * @return the {@link UserType}, or {@code null} if unset
     */
    public UserType getUserType() {
        return userType;
    }

    /**
     * Sets the signed-in user's role ({@code CDEMO-USER-TYPE}).
     *
     * @param userType the {@link UserType} to set
     */
    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    /**
     * Returns the program-context flag ({@code CDEMO-PGM-CONTEXT}):
     * {@code 0 = ENTER} (first entry), {@code 1 = REENTER} (re-entry).
     *
     * @return the program-context flag
     */
    public int getProgramContext() {
        return programContext;
    }

    /**
     * Sets the program-context flag ({@code CDEMO-PGM-CONTEXT}):
     * {@code 0 = ENTER} (first entry), {@code 1 = REENTER} (re-entry).
     *
     * @param programContext the program-context flag to set
     */
    public void setProgramContext(int programContext) {
        this.programContext = programContext;
    }

    // ---------------------------------------------------------------------
    // Accessors -- CDEMO-CUSTOMER-INFO
    // ---------------------------------------------------------------------

    /**
     * Returns the in-focus customer id ({@code CDEMO-CUST-ID}).
     *
     * @return the customer id, or {@code null} if unset
     */
    public Long getCustomerId() {
        return customerId;
    }

    /**
     * Sets the in-focus customer id ({@code CDEMO-CUST-ID}).
     *
     * @param customerId the customer id to set (up to 9 digits)
     */
    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    /**
     * Returns the in-focus customer's first name ({@code CDEMO-CUST-FNAME}).
     *
     * @return the customer first name, or {@code null} if unset
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * Sets the in-focus customer's first name ({@code CDEMO-CUST-FNAME}).
     *
     * @param custFirstName the customer first name to set (up to 25 characters)
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    /**
     * Returns the in-focus customer's middle name ({@code CDEMO-CUST-MNAME}).
     *
     * @return the customer middle name, or {@code null} if unset
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * Sets the in-focus customer's middle name ({@code CDEMO-CUST-MNAME}).
     *
     * @param custMiddleName the customer middle name to set (up to 25 characters)
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    /**
     * Returns the in-focus customer's last name ({@code CDEMO-CUST-LNAME}).
     *
     * @return the customer last name, or {@code null} if unset
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * Sets the in-focus customer's last name ({@code CDEMO-CUST-LNAME}).
     *
     * @param custLastName the customer last name to set (up to 25 characters)
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    // ---------------------------------------------------------------------
    // Accessors -- CDEMO-ACCOUNT-INFO
    // ---------------------------------------------------------------------

    /**
     * Returns the in-focus account id ({@code CDEMO-ACCT-ID}).
     *
     * @return the account id, or {@code null} if unset
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Sets the in-focus account id ({@code CDEMO-ACCT-ID}).
     *
     * @param accountId the account id to set (up to 11 digits)
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the in-focus account status flag ({@code CDEMO-ACCT-STATUS}).
     *
     * @return the account status, or {@code null} if unset
     */
    public String getAccountStatus() {
        return accountStatus;
    }

    /**
     * Sets the in-focus account status flag ({@code CDEMO-ACCT-STATUS}).
     *
     * @param accountStatus the single-character account status to set
     */
    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    // ---------------------------------------------------------------------
    // Accessors -- CDEMO-CARD-INFO
    // ---------------------------------------------------------------------

    /**
     * Returns the in-focus card number ({@code CDEMO-CARD-NUM}).
     *
     * <p>The raw 16-digit PAN is returned for the use of the service layer;
     * callers must never log it (see {@link #toString()}, which masks it).</p>
     *
     * @return the card number, or {@code null} if unset
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Sets the in-focus card number ({@code CDEMO-CARD-NUM}).
     *
     * @param cardNumber the 16-digit card number to set
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    // ---------------------------------------------------------------------
    // Accessors -- CDEMO-MORE-INFO
    // ---------------------------------------------------------------------

    /**
     * Returns the last rendered map name ({@code CDEMO-LAST-MAP}).
     *
     * @return the last map name, or {@code null} if unset
     */
    public String getLastMap() {
        return lastMap;
    }

    /**
     * Sets the last rendered map name ({@code CDEMO-LAST-MAP}).
     *
     * @param lastMap the last map name to set (up to 7 characters)
     */
    public void setLastMap(String lastMap) {
        this.lastMap = lastMap;
    }

    /**
     * Returns the last rendered mapset name ({@code CDEMO-LAST-MAPSET}).
     *
     * @return the last mapset name, or {@code null} if unset
     */
    public String getLastMapset() {
        return lastMapset;
    }

    /**
     * Sets the last rendered mapset name ({@code CDEMO-LAST-MAPSET}).
     *
     * @param lastMapset the last mapset name to set (up to 7 characters)
     */
    public void setLastMapset(String lastMapset) {
        this.lastMapset = lastMapset;
    }

    // ---------------------------------------------------------------------
    // Convenience predicates -- mirror the COBOL 88-level condition names.
    // Annotated @JsonIgnore so they are NOT serialized as derived JSON
    // properties: the wire contract stays exactly the 16 CARDDEMO-COMMAREA
    // fields (no phantom "admin"/"enter"/"reenter" properties are added).
    // ---------------------------------------------------------------------

    /**
     * Indicates whether the signed-in user is an administrator.
     *
     * <p>Mirrors the COBOL condition name {@code 88 CDEMO-USRTYP-ADMIN VALUE
     * 'A'}. Null-safe: returns {@code false} when {@link #userType} is unset.</p>
     *
     * @return {@code true} if {@link #userType} is {@link UserType#ADMIN}
     */
    @JsonIgnore
    public boolean isAdmin() {
        return userType == UserType.ADMIN;
    }

    /**
     * Indicates first program entry.
     *
     * <p>Mirrors the COBOL condition name {@code 88 CDEMO-PGM-ENTER VALUE 0}.</p>
     *
     * @return {@code true} if {@link #programContext} equals {@code 0}
     */
    @JsonIgnore
    public boolean isEnter() {
        return programContext == 0;
    }

    /**
     * Indicates program re-entry.
     *
     * <p>Mirrors the COBOL condition name {@code 88 CDEMO-PGM-REENTER VALUE
     * 1}.</p>
     *
     * @return {@code true} if {@link #programContext} equals {@code 1}
     */
    @JsonIgnore
    public boolean isReenter() {
        return programContext == 1;
    }

    // ---------------------------------------------------------------------
    // Object contract
    // ---------------------------------------------------------------------

    /**
     * Value-based equality across all sixteen {@code CARDDEMO-COMMAREA} fields.
     *
     * <p>Unlike the record-backed DTOs (which key equality on a single
     * identifier), this context object has no single natural key: it is a flat
     * snapshot of conversational state, so two instances are equal only when
     * every carried field matches. The enum and primitive fields are compared by
     * value; all reference fields use {@link Objects#equals(Object, Object)} and
     * are therefore {@code null}-safe.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code CommArea} with identical state
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CommArea that = (CommArea) o;
        return programContext == that.programContext
                && Objects.equals(fromTranId, that.fromTranId)
                && Objects.equals(fromProgram, that.fromProgram)
                && Objects.equals(toTranId, that.toTranId)
                && Objects.equals(toProgram, that.toProgram)
                && Objects.equals(userId, that.userId)
                && userType == that.userType
                && Objects.equals(customerId, that.customerId)
                && Objects.equals(custFirstName, that.custFirstName)
                && Objects.equals(custMiddleName, that.custMiddleName)
                && Objects.equals(custLastName, that.custLastName)
                && Objects.equals(accountId, that.accountId)
                && Objects.equals(accountStatus, that.accountStatus)
                && Objects.equals(cardNumber, that.cardNumber)
                && Objects.equals(lastMap, that.lastMap)
                && Objects.equals(lastMapset, that.lastMapset);
    }

    /**
     * Hash code derived from all sixteen fields, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this communication-area context
     */
    @Override
    public int hashCode() {
        return Objects.hash(fromTranId, fromProgram, toTranId, toProgram, userId,
                userType, programContext, customerId, custFirstName, custMiddleName,
                custLastName, accountId, accountStatus, cardNumber, lastMap, lastMapset);
    }

    /**
     * Diagnostic representation of this context.
     *
     * <p>For safety the sensitive card number (PAN) is <strong>masked</strong>
     * &mdash; only its presence ({@code "****"}) or absence ({@code "null"}) is
     * shown, never its digits &mdash; consistent with the security-conscious
     * {@code toString} convention used elsewhere in the model layer. All other
     * routing/identity fields are shown to aid diagnostics.</p>
     *
     * @return a human-readable, PAN-safe description of this context
     */
    @Override
    public String toString() {
        return "CommArea{"
                + "fromTranId='" + fromTranId + '\''
                + ", fromProgram='" + fromProgram + '\''
                + ", toTranId='" + toTranId + '\''
                + ", toProgram='" + toProgram + '\''
                + ", userId='" + userId + '\''
                + ", userType=" + userType
                + ", programContext=" + programContext
                + ", customerId=" + customerId
                + ", custFirstName='" + custFirstName + '\''
                + ", custMiddleName='" + custMiddleName + '\''
                + ", custLastName='" + custLastName + '\''
                + ", accountId=" + accountId
                + ", accountStatus='" + accountStatus + '\''
                + ", cardNumber=" + (cardNumber == null ? "null" : "****")
                + ", lastMap='" + lastMap + '\''
                + ", lastMapset='" + lastMapset + '\''
                + '}';
    }
}
