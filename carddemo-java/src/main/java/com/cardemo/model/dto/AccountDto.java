package com.cardemo.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * REST request/response payload for the CardDemo <strong>Account View</strong>
 * ({@code CAVW}) and <strong>Account Update</strong> ({@code CAUP}) screens.
 *
 * <p>This Data Transfer Object is the Java 25 / Spring Boot 3.x replacement for the
 * two 3270 account maps the legacy system used. On the mainframe those screens were
 * BMS maps {@code COACTVW} (view) and {@code COACTUP} (update); their symbolic maps
 * are defined by the copybooks {@code app/cpy-bms/COACTVW.CPY} ({@code 01 CACTVWAI})
 * and {@code app/cpy-bms/COACTUP.CPY} ({@code 01 CACTUPAI}). The online programs
 * {@code app/cbl/COACTVWC.cbl} and {@code app/cbl/COACTUPC.cbl} populated those
 * symbolic structures, joining the {@code ACCTDAT} account record with the related
 * {@code CUSTDAT} customer record (resolved through the {@code CXACAIX} cross-reference).
 * This DTO consolidates the account fields <em>plus</em> the embedded customer
 * demographic fields that both screens displayed and edited into a single canonical
 * representation.</p>
 *
 * <p>In the migrated system this DTO is bound by {@code controller/AccountController}
 * to {@code GET /api/accounts/{id}} (the view path, translated from {@code COACTVWC})
 * and {@code PUT /api/accounts/{id}} (the update path, translated from
 * {@code COACTUPC}). Jackson serializes/deserializes the JSON body, Jakarta Bean
 * Validation enforces the structural field constraints declared below (the
 * {@code @Valid} gate on the controller method), and the validated payload is handed
 * to {@code service/account/AccountViewService} or
 * {@code service/account/AccountUpdateService}. This DTO is therefore a pure boundary
 * type &mdash; it carries no business logic, no I/O and no static state.</p>
 *
 * <h2>Key insight &mdash; one record at two fidelities</h2>
 * <p>{@code COACTVW} and {@code COACTUP} describe the <em>same</em> account+customer
 * record but at two different fidelities:</p>
 * <ul>
 *   <li>The <strong>view</strong> map ({@code COACTVW}) sends whole-string dates
 *       ({@code PIC X(10)}, {@code yyyy-mm-dd}), a single SSN string
 *       ({@code PIC X(12)}) and single phone strings ({@code PIC X(13)}), and the
 *       money amounts as packed display fields ({@code PIC X(15)}).</li>
 *   <li>The <strong>update</strong> map ({@code COACTUP}) decomposes each date into
 *       year/month/day components ({@code OPNYEAR}/{@code OPNMON}/{@code OPNDAY}, and
 *       likewise {@code EXP*}, {@code RIS*}, {@code DOB*}), splits the SSN into
 *       {@code ACTSSN1}+{@code ACTSSN2}+{@code ACTSSN3} and each phone into
 *       {@code ACSPH1A}+{@code ACSPH1B}+{@code ACSPH1C} (and {@code ACSPH2A/B/C}).</li>
 * </ul>
 * <p>This DTO unifies both into ONE canonical shape using {@link LocalDate} for the
 * four dates and {@link BigDecimal} (scale 2) for the five money amounts. The
 * component (re)assembly performed by the update screen &mdash; stitching
 * {@code year}/{@code month}/{@code day} back into a date, joining the SSN and phone
 * parts &mdash; is the responsibility of the controller/service layer, and date
 * <em>validity</em> checking is performed by {@code service/shared/DateValidationService}
 * (the translation of {@code CSUTLDTC}/{@code CEEDAYS}), <strong>not</strong> by this
 * DTO.</p>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Decimal fidelity (AAP &sect;0.7.3).</strong> Every monetary/balance
 *       field is a {@link BigDecimal}; there is <strong>no</strong> {@code float} or
 *       {@code double} anywhere in this class. Although the screen rendered each
 *       amount as a {@code PIC X(15)} display field, the underlying account record
 *       ({@code app/cpy/CVACT01Y.cpy}) typed them {@code PIC S9(10)V99}, so each is
 *       constrained with {@link Digits @Digits(integer = 10, fraction = 2)} to lock
 *       the ten-integer / two-fraction-digit precision. Callers must compare amounts
 *       with {@link BigDecimal#compareTo(BigDecimal)} rather than the scale-sensitive
 *       {@link BigDecimal#equals(Object)}.</li>
 *   <li><strong>{@code CEEDAYS} dates &rarr; {@link LocalDate} (AAP &sect;0.1.2).</strong>
 *       Open, expiration, reissue and date-of-birth become {@link LocalDate}. Each is
 *       annotated {@link JsonFormat @JsonFormat(pattern = "yyyy-MM-dd")} so the JSON
 *       wire form preserves the exact {@code PIC X(10)} {@code yyyy-mm-dd} contract
 *       the view screen used.</li>
 *   <li><strong>Byte-faithful field lengths (AAP &sect;0.7.1 / &sect;0.7.2).</strong>
 *       Every textual field carries a {@link Size @Size(max = n)} equal to its COBOL
 *       {@code PIC} length, preserving the external-interface width exactly. The
 *       numeric identifier fields additionally carry {@link Pattern @Pattern} (digit
 *       shape) and are kept as fixed-width {@link String}s rather than numeric types
 *       so leading zeros and the original width are never lost.</li>
 * </ul>
 *
 * <h2>Deliberately excluded &mdash; 3270 chrome, control bytes and AID keys (AAP &sect;0.4.2)</h2>
 * <p>The following symbolic-map members are <strong>not</strong> modeled because they
 * are presentation/control artifacts with no REST equivalent:</p>
 * <ul>
 *   <li><strong>Screen chrome (output-only):</strong> {@code TRNNAMEI} ({@code X(4)}),
 *       {@code TITLE01I}/{@code TITLE02I} ({@code X(40)}), {@code CURDATEI}/
 *       {@code CURTIMEI} ({@code X(8)}), {@code PGMNAMEI} ({@code X(8)}).</li>
 *   <li><strong>Message lines (output-only):</strong> {@code INFOMSGI} ({@code X(45)})
 *       and {@code ERRMSGI} ({@code X(78)}) &mdash; in REST these become the
 *       response/error body, not request fields.</li>
 *   <li><strong>Function-key labels:</strong> {@code FKEYSI} ({@code X(21)}),
 *       {@code FKEY05I} ({@code X(7)}) and {@code FKEY12I} ({@code X(10)}) &mdash; PF
 *       keys map onto distinct REST endpoints / controller routing, never a body
 *       field.</li>
 *   <li><strong>BMS per-field control bytes:</strong> the {@code ...L} (length),
 *       {@code ...F}/{@code ...A} (flag/attribute) input bytes and the redefined
 *       {@code ...C}/{@code ...P}/{@code ...H}/{@code ...V} output bytes &mdash; 3270
 *       datastream metadata with no REST analogue.</li>
 * </ul>
 *
 * <p>Consistent with the folder rule, this DTO is a separate API representation and
 * deliberately does <strong>not</strong> import any {@code com.cardemo.model.entity}
 * type; mapping between this DTO and the {@code Account}/{@code Customer} entities is
 * the service layer's responsibility.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is never
 * copied into this repository.</p>
 *
 * @see java.math.BigDecimal
 * @see java.time.LocalDate
 * @see jakarta.validation.constraints.Size
 * @see jakarta.validation.constraints.Digits
 */
public class AccountDto {

    // ---------------------------------------------------------------------
    // Account fields (from app/cpy-bms/COACTVW.CPY / COACTUP.CPY account group)
    // ---------------------------------------------------------------------

    /**
     * The account identifier.
     *
     * <p>Migrated from {@code ACCTSID} (view {@code PIC 99999999999}, update
     * {@code PIC X(11)}): an eleven-character account number. Kept as a fixed-width
     * {@link String} so leading zeros and the exact width are preserved;
     * {@link Pattern @Pattern} enforces a digit-only shape and {@link Size @Size}
     * preserves the eleven-character width.</p>
     */
    // ACCTSID PIC 99999999999 / X(11) -> 11-digit fixed-width account number -> String(11)
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    @Pattern(regexp = "\\d{1,11}", message = "Account ID must be 1 to 11 digits")
    private String accountId;

    /**
     * Account active-status flag.
     *
     * <p>Migrated from {@code ACSTTUS PIC X(1)}: a single-character active flag
     * (typically {@code 'Y'} or {@code 'N'}), modeled as a {@link String} of length
     * one.</p>
     */
    // ACSTTUS PIC X(1) -> single-character active flag (Y/N) -> String(1)
    @Size(max = 1, message = "Account status must be a single character")
    private String accountStatus;

    /**
     * Account open date.
     *
     * <p>Migrated from {@code ADTOPEN} (view {@code PIC X(10)}; update components
     * {@code OPNYEAR}/{@code OPNMON}/{@code OPNDAY}). Canonicalized to a
     * {@link LocalDate}; the update screen's year/month/day components are reassembled
     * by the service/controller layer before binding here.</p>
     */
    // ADTOPEN PIC X(10) (yyyy-mm-dd) / OPNYEAR(4)+OPNMON(2)+OPNDAY(2) -> LocalDate
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate openDate;

    /**
     * Total credit limit for the account.
     *
     * <p>Migrated from {@code ACRDLIM} (screen display {@code PIC X(15)}; underlying
     * {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}). Modeled as a {@link BigDecimal} of
     * scale 2; never {@code float}/{@code double} (AAP &sect;0.7.3).</p>
     */
    // ACRDLIM PIC X(15) display / ACCT-CREDIT-LIMIT PIC S9(10)V99 -> BigDecimal (scale 2)
    @Digits(integer = 10, fraction = 2, message = "Credit limit must have at most 10 integer and 2 fraction digits")
    private BigDecimal creditLimit;

    /**
     * Account expiration date.
     *
     * <p>Migrated from {@code AEXPDT} (view {@code PIC X(10)}; update components
     * {@code EXPYEAR}/{@code EXPMON}/{@code EXPDAY}). Canonicalized to a
     * {@link LocalDate}.</p>
     */
    // AEXPDT PIC X(10) (yyyy-mm-dd) / EXPYEAR(4)+EXPMON(2)+EXPDAY(2) -> LocalDate
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate expirationDate;

    /**
     * Cash-advance credit limit for the account.
     *
     * <p>Migrated from {@code ACSHLIM} (screen display {@code PIC X(15)}; underlying
     * {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}). Modeled as a {@link BigDecimal}
     * of scale 2.</p>
     */
    // ACSHLIM PIC X(15) display / ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 -> BigDecimal (scale 2)
    @Digits(integer = 10, fraction = 2, message = "Cash credit limit must have at most 10 integer and 2 fraction digits")
    private BigDecimal cashCreditLimit;

    /**
     * Account card-reissue date.
     *
     * <p>Migrated from {@code AREISDT} (view {@code PIC X(10)}; update components
     * {@code RISYEAR}/{@code RISMON}/{@code RISDAY}). Canonicalized to a
     * {@link LocalDate}.</p>
     */
    // AREISDT PIC X(10) (yyyy-mm-dd) / RISYEAR(4)+RISMON(2)+RISDAY(2) -> LocalDate
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate reissueDate;

    /**
     * Current account balance.
     *
     * <p>Migrated from {@code ACURBAL} (screen display {@code PIC X(15)}; underlying
     * {@code ACCT-CURR-BAL PIC S9(10)V99}). Modeled as a {@link BigDecimal} of
     * scale 2.</p>
     */
    // ACURBAL PIC X(15) display / ACCT-CURR-BAL PIC S9(10)V99 -> BigDecimal (scale 2)
    @Digits(integer = 10, fraction = 2, message = "Current balance must have at most 10 integer and 2 fraction digits")
    private BigDecimal currentBalance;

    /**
     * Current-cycle credit total.
     *
     * <p>Migrated from {@code ACRCYCR} (screen display {@code PIC X(15)}; underlying
     * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}). Modeled as a {@link BigDecimal} of
     * scale 2.</p>
     */
    // ACRCYCR PIC X(15) display / ACCT-CURR-CYC-CREDIT PIC S9(10)V99 -> BigDecimal (scale 2)
    @Digits(integer = 10, fraction = 2, message = "Current cycle credit must have at most 10 integer and 2 fraction digits")
    private BigDecimal currentCycleCredit;

    /**
     * Account group identifier (used for disclosure-group / interest-rate lookup).
     *
     * <p>Migrated from {@code AADDGRP PIC X(10)}: a fixed ten-character alphanumeric
     * code, modeled as a {@link String} of length ten.</p>
     */
    // AADDGRP PIC X(10) -> fixed 10-char alphanumeric group code -> String(10)
    @Size(max = 10, message = "Account group ID must not exceed 10 characters")
    private String accountGroupId;

    /**
     * Current-cycle debit total.
     *
     * <p>Migrated from {@code ACRCYDB} (screen display {@code PIC X(15)}; underlying
     * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}). Modeled as a {@link BigDecimal} of
     * scale 2.</p>
     */
    // ACRCYDB PIC X(15) display / ACCT-CURR-CYC-DEBIT PIC S9(10)V99 -> BigDecimal (scale 2)
    @Digits(integer = 10, fraction = 2, message = "Current cycle debit must have at most 10 integer and 2 fraction digits")
    private BigDecimal currentCycleDebit;

    // ---------------------------------------------------------------------
    // Embedded customer fields (CUSTDAT record surfaced on the same screens)
    // ---------------------------------------------------------------------

    /**
     * The customer identifier of the account holder.
     *
     * <p>Migrated from {@code ACSTNUM PIC X(9)}: a nine-character customer number.
     * Kept as a fixed-width {@link String}; {@link Pattern @Pattern} enforces a
     * digit-only shape and {@link Size @Size} preserves the nine-character width.</p>
     */
    // ACSTNUM PIC X(9) -> 9-digit fixed-width customer number -> String(9)
    @Size(max = 9, message = "Customer ID must not exceed 9 characters")
    @Pattern(regexp = "\\d{1,9}", message = "Customer ID must be 1 to 9 digits")
    private String customerId;

    /**
     * The account holder's Social Security Number.
     *
     * <p>Migrated from {@code ACSTSSN PIC X(12)} (the update screen splits this into
     * {@code ACTSSN1 X(3)} + {@code ACTSSN2 X(2)} + {@code ACTSSN3 X(4)}). Kept as a
     * single {@link String} preserving the twelve-character contract; the update
     * screen's three components are re-joined by the service/controller layer before
     * binding here.</p>
     *
     * <p><strong>Privacy:</strong> this value is masked by {@link #toString()} so it
     * never leaks into logs or diagnostics.</p>
     */
    // ACSTSSN PIC X(12) / ACTSSN1(3)+ACTSSN2(2)+ACTSSN3(4) -> String(12)
    @Size(max = 12, message = "SSN must not exceed 12 characters")
    private String ssn;

    /**
     * The account holder's date of birth.
     *
     * <p>Migrated from {@code ACSTDOB} (view {@code PIC X(10)}; update components
     * {@code DOBYEAR}/{@code DOBMON}/{@code DOBDAY}). Canonicalized to a
     * {@link LocalDate}; the update screen's year/month/day components are reassembled
     * by the service/controller layer before binding here.</p>
     */
    // ACSTDOB PIC X(10) (yyyy-mm-dd) / DOBYEAR(4)+DOBMON(2)+DOBDAY(2) -> LocalDate
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;

    /**
     * The account holder's FICO credit score.
     *
     * <p>Migrated from {@code ACSTFCO PIC X(3)}: a three-character numeric score.
     * Kept as a fixed-width {@link String} (preserving the on-screen width contract);
     * {@link Digits @Digits(integer = 3, fraction = 0)} enforces a whole-number shape
     * of at most three digits.</p>
     */
    // ACSTFCO PIC X(3) -> 3-digit FICO score -> String(3) (numeric, no fraction)
    @Size(max = 3, message = "FICO score must not exceed 3 characters")
    @Digits(integer = 3, fraction = 0, message = "FICO score must be a whole number of at most 3 digits")
    private String ficoScore;

    /**
     * The account holder's first (given) name.
     *
     * <p>Migrated from {@code ACSFNAM PIC X(25)}: a 25-character name field.</p>
     */
    // ACSFNAM PIC X(25) -> 25-char given name -> String(25)
    @Size(max = 25, message = "First name must not exceed 25 characters")
    private String firstName;

    /**
     * The account holder's middle name.
     *
     * <p>Migrated from {@code ACSMNAM PIC X(25)}: a 25-character name field.</p>
     */
    // ACSMNAM PIC X(25) -> 25-char middle name -> String(25)
    @Size(max = 25, message = "Middle name must not exceed 25 characters")
    private String middleName;

    /**
     * The account holder's last (family) name.
     *
     * <p>Migrated from {@code ACSLNAM PIC X(25)}: a 25-character name field.</p>
     */
    // ACSLNAM PIC X(25) -> 25-char family name -> String(25)
    @Size(max = 25, message = "Last name must not exceed 25 characters")
    private String lastName;

    /**
     * First line of the account holder's mailing address.
     *
     * <p>Migrated from {@code ACSADL1 PIC X(50)}: a 50-character address line.</p>
     */
    // ACSADL1 PIC X(50) -> 50-char address line 1 -> String(50)
    @Size(max = 50, message = "Address line 1 must not exceed 50 characters")
    private String addressLine1;

    /**
     * Second line of the account holder's mailing address.
     *
     * <p>Migrated from {@code ACSADL2 PIC X(50)}: a 50-character address line.</p>
     */
    // ACSADL2 PIC X(50) -> 50-char address line 2 -> String(50)
    @Size(max = 50, message = "Address line 2 must not exceed 50 characters")
    private String addressLine2;

    /**
     * City of the account holder's mailing address.
     *
     * <p>Migrated from {@code ACSCITY PIC X(50)}: a 50-character city field.</p>
     */
    // ACSCITY PIC X(50) -> 50-char city -> String(50)
    @Size(max = 50, message = "City must not exceed 50 characters")
    private String city;

    /**
     * State/province code of the account holder's mailing address.
     *
     * <p>Migrated from {@code ACSSTTE PIC X(2)}: a two-character state code. Value
     * validity (the NANPA/state lookup) is the responsibility of
     * {@code service/shared/ValidationLookupService}, not this DTO.</p>
     */
    // ACSSTTE PIC X(2) -> 2-char state code -> String(2)
    @Size(max = 2, message = "State must not exceed 2 characters")
    private String state;

    /**
     * ZIP/postal code of the account holder's mailing address.
     *
     * <p>Migrated from {@code ACSZIPC PIC X(5)}: a five-character ZIP code.</p>
     */
    // ACSZIPC PIC X(5) -> 5-char ZIP code -> String(5)
    @Size(max = 5, message = "ZIP code must not exceed 5 characters")
    private String zipCode;

    /**
     * Country code of the account holder's mailing address.
     *
     * <p>Migrated from {@code ACSCTRY PIC X(3)}: a three-character country code.</p>
     */
    // ACSCTRY PIC X(3) -> 3-char country code -> String(3)
    @Size(max = 3, message = "Country code must not exceed 3 characters")
    private String countryCode;

    /**
     * The account holder's primary phone number.
     *
     * <p>Migrated from {@code ACSPHN1 PIC X(13)} (the update screen splits this into
     * {@code ACSPH1A X(3)} + {@code ACSPH1B X(3)} + {@code ACSPH1C X(4)}). Kept as a
     * single thirteen-character {@link String}; the update screen's components are
     * re-joined by the service/controller layer before binding here.</p>
     */
    // ACSPHN1 PIC X(13) / ACSPH1A(3)+ACSPH1B(3)+ACSPH1C(4) -> String(13)
    @Size(max = 13, message = "Phone number 1 must not exceed 13 characters")
    private String phoneNumber1;

    /**
     * The account holder's secondary phone number.
     *
     * <p>Migrated from {@code ACSPHN2 PIC X(13)} (the update screen splits this into
     * {@code ACSPH2A X(3)} + {@code ACSPH2B X(3)} + {@code ACSPH2C X(4)}). Kept as a
     * single thirteen-character {@link String}; the update screen's components are
     * re-joined by the service/controller layer before binding here.</p>
     */
    // ACSPHN2 PIC X(13) / ACSPH2A(3)+ACSPH2B(3)+ACSPH2C(4) -> String(13)
    @Size(max = 13, message = "Phone number 2 must not exceed 13 characters")
    private String phoneNumber2;

    /**
     * A government-issued identifier for the account holder.
     *
     * <p>Migrated from {@code ACSGOVT PIC X(20)}: a twenty-character identifier.</p>
     *
     * <p><strong>Privacy:</strong> this value is masked by {@link #toString()} so it
     * never leaks into logs or diagnostics.</p>
     */
    // ACSGOVT PIC X(20) -> 20-char government-issued ID -> String(20)
    @Size(max = 20, message = "Government-issued ID must not exceed 20 characters")
    private String governmentIssuedId;

    /**
     * The account holder's EFT (Electronic Funds Transfer) account identifier.
     *
     * <p>Migrated from {@code ACSEFTC PIC X(10)}: a ten-character EFT account
     * identifier.</p>
     */
    // ACSEFTC PIC X(10) -> 10-char EFT account identifier -> String(10)
    @Size(max = 10, message = "EFT account ID must not exceed 10 characters")
    private String eftAccountId;

    /**
     * Primary card-holder indicator.
     *
     * <p>Migrated from {@code ACSPFLG PIC X(1)}: a single-character flag indicating
     * whether the customer is the primary card holder.</p>
     */
    // ACSPFLG PIC X(1) -> single-character primary card-holder flag -> String(1)
    @Size(max = 1, message = "Primary card-holder indicator must be a single character")
    private String primaryCardHolderIndicator;

    /**
     * Optimistic-locking version token, mirrored from the {@code Account} entity's
     * JPA {@code @Version} column.
     *
     * <p>This field has <strong>no COBOL {@code PIC} origin</strong>: it is the
     * Java&nbsp;25 realization of the {@code COACTUPC} <em>read-before-update</em>
     * concurrency check ({@code 1200-COMPARE-OLD-NEW} / {@code 9700-CHECK-CHANGE-IN-REC}),
     * which compared the record image read at display time against the record on disk at
     * update time and rejected the write if it had changed
     * ({@code DATA-WAS-CHANGED-BEFORE-UPDATE}). The account view/read populates this token;
     * the client echoes it back on the subsequent update; the update service compares it
     * against the current entity version and rejects a <em>stale</em> form &mdash; one
     * loaded before another user's completed update &mdash; with a
     * {@link com.cardemo.exception.ConcurrentModificationException} (HTTP&nbsp;409). It is
     * a server-issued, client-echoed token, so it carries no input-validation constraint;
     * when {@code null} (a client that does not participate in the check) the server-side
     * JPA {@code @Version} remains the safety net (AAP&nbsp;&sect;0.7.5).</p>
     */
    private Long version;

    /**
     * Default no-argument constructor required by the JSON binder (Jackson) to
     * instantiate this DTO reflectively before populating its properties via setters.
     */
    public AccountDto() {
        // Intentionally empty: Jackson instantiates then sets fields via setters.
    }

    // ---------------------------------------------------------------------
    // Accessors -- account fields
    // ---------------------------------------------------------------------

    /**
     * Returns the account identifier ({@code ACCTSID}).
     *
     * @return the eleven-character account identifier, or {@code null} if unset
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the account identifier ({@code ACCTSID}).
     *
     * @param accountId the eleven-character account identifier to set
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the active-status flag ({@code ACSTTUS}).
     *
     * @return the single-character active-status flag, or {@code null} if unset
     */
    public String getAccountStatus() {
        return accountStatus;
    }

    /**
     * Sets the active-status flag ({@code ACSTTUS}).
     *
     * @param accountStatus the single-character active-status flag to set
     */
    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    /**
     * Returns the account open date ({@code ADTOPEN}).
     *
     * @return the open date, or {@code null} if unset
     */
    public LocalDate getOpenDate() {
        return openDate;
    }

    /**
     * Sets the account open date ({@code ADTOPEN}).
     *
     * @param openDate the open date to set
     */
    public void setOpenDate(LocalDate openDate) {
        this.openDate = openDate;
    }

    /**
     * Returns the credit limit ({@code ACRDLIM}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)}, never
     * {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the credit limit, or {@code null} if unset
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Sets the credit limit ({@code ACRDLIM}). This setter neither rounds nor
     * rescales; the caller supplies a scale-2 {@link BigDecimal}.
     *
     * @param creditLimit the credit limit to set
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * Returns the account expiration date ({@code AEXPDT}).
     *
     * @return the expiration date, or {@code null} if unset
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the account expiration date ({@code AEXPDT}).
     *
     * @param expirationDate the expiration date to set
     */
    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the cash-advance credit limit ({@code ACSHLIM}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)}, never
     * {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the cash credit limit, or {@code null} if unset
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * Sets the cash-advance credit limit ({@code ACSHLIM}). This setter neither rounds
     * nor rescales; the caller supplies a scale-2 {@link BigDecimal}.
     *
     * @param cashCreditLimit the cash credit limit to set
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * Returns the account card-reissue date ({@code AREISDT}).
     *
     * @return the reissue date, or {@code null} if unset
     */
    public LocalDate getReissueDate() {
        return reissueDate;
    }

    /**
     * Sets the account card-reissue date ({@code AREISDT}).
     *
     * @param reissueDate the reissue date to set
     */
    public void setReissueDate(LocalDate reissueDate) {
        this.reissueDate = reissueDate;
    }

    /**
     * Returns the current account balance ({@code ACURBAL}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)}, never
     * {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the current balance, or {@code null} if unset
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Sets the current account balance ({@code ACURBAL}). This setter neither rounds
     * nor rescales; the caller supplies a scale-2 {@link BigDecimal}.
     *
     * @param currentBalance the current balance to set
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    /**
     * Returns the current-cycle credit total ({@code ACRCYCR}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)}, never
     * {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the current-cycle credit total, or {@code null} if unset
     */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * Sets the current-cycle credit total ({@code ACRCYCR}). This setter neither
     * rounds nor rescales; the caller supplies a scale-2 {@link BigDecimal}.
     *
     * @param currentCycleCredit the current-cycle credit total to set
     */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit;
    }

    /**
     * Returns the account group identifier ({@code AADDGRP}).
     *
     * @return the ten-character account group identifier, or {@code null} if unset
     */
    public String getAccountGroupId() {
        return accountGroupId;
    }

    /**
     * Sets the account group identifier ({@code AADDGRP}).
     *
     * @param accountGroupId the ten-character account group identifier to set
     */
    public void setAccountGroupId(String accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    /**
     * Returns the current-cycle debit total ({@code ACRCYDB}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)}, never
     * {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the current-cycle debit total, or {@code null} if unset
     */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * Sets the current-cycle debit total ({@code ACRCYDB}). This setter neither rounds
     * nor rescales; the caller supplies a scale-2 {@link BigDecimal}.
     *
     * @param currentCycleDebit the current-cycle debit total to set
     */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit;
    }

    // ---------------------------------------------------------------------
    // Accessors -- embedded customer fields
    // ---------------------------------------------------------------------

    /**
     * Returns the customer identifier ({@code ACSTNUM}).
     *
     * @return the nine-character customer identifier, or {@code null} if unset
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Sets the customer identifier ({@code ACSTNUM}).
     *
     * @param customerId the nine-character customer identifier to set
     */
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    /**
     * Returns the Social Security Number ({@code ACSTSSN}).
     *
     * <p>The raw value is returned for service-layer use; callers must never log it.</p>
     *
     * @return the SSN, or {@code null} if unset
     */
    public String getSsn() {
        return ssn;
    }

    /**
     * Sets the Social Security Number ({@code ACSTSSN}).
     *
     * @param ssn the SSN to set
     */
    public void setSsn(String ssn) {
        this.ssn = ssn;
    }

    /**
     * Returns the date of birth ({@code ACSTDOB}).
     *
     * @return the date of birth, or {@code null} if unset
     */
    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * Sets the date of birth ({@code ACSTDOB}).
     *
     * @param dateOfBirth the date of birth to set
     */
    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    /**
     * Returns the FICO credit score ({@code ACSTFCO}).
     *
     * @return the three-character FICO score, or {@code null} if unset
     */
    public String getFicoScore() {
        return ficoScore;
    }

    /**
     * Sets the FICO credit score ({@code ACSTFCO}).
     *
     * @param ficoScore the three-character FICO score to set
     */
    public void setFicoScore(String ficoScore) {
        this.ficoScore = ficoScore;
    }

    /**
     * Returns the first (given) name ({@code ACSFNAM}).
     *
     * @return the first name, or {@code null} if unset
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the first (given) name ({@code ACSFNAM}).
     *
     * @param firstName the first name to set
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the middle name ({@code ACSMNAM}).
     *
     * @return the middle name, or {@code null} if unset
     */
    public String getMiddleName() {
        return middleName;
    }

    /**
     * Sets the middle name ({@code ACSMNAM}).
     *
     * @param middleName the middle name to set
     */
    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    /**
     * Returns the last (family) name ({@code ACSLNAM}).
     *
     * @return the last name, or {@code null} if unset
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the last (family) name ({@code ACSLNAM}).
     *
     * @param lastName the last name to set
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the first address line ({@code ACSADL1}).
     *
     * @return the first address line, or {@code null} if unset
     */
    public String getAddressLine1() {
        return addressLine1;
    }

    /**
     * Sets the first address line ({@code ACSADL1}).
     *
     * @param addressLine1 the first address line to set
     */
    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    /**
     * Returns the second address line ({@code ACSADL2}).
     *
     * @return the second address line, or {@code null} if unset
     */
    public String getAddressLine2() {
        return addressLine2;
    }

    /**
     * Sets the second address line ({@code ACSADL2}).
     *
     * @param addressLine2 the second address line to set
     */
    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    /**
     * Returns the city ({@code ACSCITY}).
     *
     * @return the city, or {@code null} if unset
     */
    public String getCity() {
        return city;
    }

    /**
     * Sets the city ({@code ACSCITY}).
     *
     * @param city the city to set
     */
    public void setCity(String city) {
        this.city = city;
    }

    /**
     * Returns the state/province code ({@code ACSSTTE}).
     *
     * @return the two-character state code, or {@code null} if unset
     */
    public String getState() {
        return state;
    }

    /**
     * Sets the state/province code ({@code ACSSTTE}).
     *
     * @param state the two-character state code to set
     */
    public void setState(String state) {
        this.state = state;
    }

    /**
     * Returns the ZIP/postal code ({@code ACSZIPC}).
     *
     * @return the five-character ZIP code, or {@code null} if unset
     */
    public String getZipCode() {
        return zipCode;
    }

    /**
     * Sets the ZIP/postal code ({@code ACSZIPC}).
     *
     * @param zipCode the five-character ZIP code to set
     */
    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }

    /**
     * Returns the country code ({@code ACSCTRY}).
     *
     * @return the three-character country code, or {@code null} if unset
     */
    public String getCountryCode() {
        return countryCode;
    }

    /**
     * Sets the country code ({@code ACSCTRY}).
     *
     * @param countryCode the three-character country code to set
     */
    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    /**
     * Returns the primary phone number ({@code ACSPHN1}).
     *
     * @return the thirteen-character primary phone number, or {@code null} if unset
     */
    public String getPhoneNumber1() {
        return phoneNumber1;
    }

    /**
     * Sets the primary phone number ({@code ACSPHN1}).
     *
     * @param phoneNumber1 the thirteen-character primary phone number to set
     */
    public void setPhoneNumber1(String phoneNumber1) {
        this.phoneNumber1 = phoneNumber1;
    }

    /**
     * Returns the secondary phone number ({@code ACSPHN2}).
     *
     * @return the thirteen-character secondary phone number, or {@code null} if unset
     */
    public String getPhoneNumber2() {
        return phoneNumber2;
    }

    /**
     * Sets the secondary phone number ({@code ACSPHN2}).
     *
     * @param phoneNumber2 the thirteen-character secondary phone number to set
     */
    public void setPhoneNumber2(String phoneNumber2) {
        this.phoneNumber2 = phoneNumber2;
    }

    /**
     * Returns the government-issued identifier ({@code ACSGOVT}).
     *
     * <p>The raw value is returned for service-layer use; callers must never log it.</p>
     *
     * @return the twenty-character government-issued identifier, or {@code null} if unset
     */
    public String getGovernmentIssuedId() {
        return governmentIssuedId;
    }

    /**
     * Sets the government-issued identifier ({@code ACSGOVT}).
     *
     * @param governmentIssuedId the twenty-character government-issued identifier to set
     */
    public void setGovernmentIssuedId(String governmentIssuedId) {
        this.governmentIssuedId = governmentIssuedId;
    }

    /**
     * Returns the EFT account identifier ({@code ACSEFTC}).
     *
     * @return the ten-character EFT account identifier, or {@code null} if unset
     */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /**
     * Sets the EFT account identifier ({@code ACSEFTC}).
     *
     * @param eftAccountId the ten-character EFT account identifier to set
     */
    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    /**
     * Returns the primary card-holder indicator ({@code ACSPFLG}).
     *
     * @return the single-character primary card-holder indicator, or {@code null} if unset
     */
    public String getPrimaryCardHolderIndicator() {
        return primaryCardHolderIndicator;
    }

    /**
     * Sets the primary card-holder indicator ({@code ACSPFLG}).
     *
     * @param primaryCardHolderIndicator the single-character primary card-holder indicator to set
     */
    public void setPrimaryCardHolderIndicator(String primaryCardHolderIndicator) {
        this.primaryCardHolderIndicator = primaryCardHolderIndicator;
    }

    /**
     * Returns the optimistic-locking version token (mirrored from the {@code Account}
     * entity {@code @Version}); {@code null} when unset.
     *
     * @return the version token, or {@code null} if unset
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version token. Populated from the entity on read and
     * echoed by the client on update for stale-form detection (AAP&nbsp;&sect;0.7.5).
     *
     * @param version the version token to set
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    // ---------------------------------------------------------------------
    // Object contract
    // ---------------------------------------------------------------------

    /**
     * Identity-based equality keyed on the account identifier ({@link #accountId}).
     *
     * <p>Two {@code AccountDto} instances are equal when they are of the exact same
     * class and share the same {@link #accountId}. The account identifier alone
     * defines the identity of the record this DTO represents; the mutable account and
     * customer attributes are deliberately excluded so equality stays stable across
     * edits. This also avoids comparing the {@link BigDecimal} money fields with the
     * scale-sensitive {@link BigDecimal#equals(Object)} &mdash; numeric equality must
     * use {@link BigDecimal#compareTo(BigDecimal)} (AAP &sect;0.7.3). This mirrors the
     * identity-based contract of the {@code Account} entity. Note that two
     * not-yet-identified instances (both with a {@code null} {@code accountId}) compare
     * equal, exactly as for the entity.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is an {@code AccountDto} with an equal account id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AccountDto that = (AccountDto) o;
        return Objects.equals(accountId, that.accountId);
    }

    /**
     * Hash code derived solely from {@link #accountId}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the account identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId);
    }

    /**
     * Diagnostic representation of this DTO.
     *
     * <p>For safety this representation deliberately <strong>excludes</strong> the five
     * monetary fields (credit limit, cash credit limit, current balance and the
     * current-cycle credit/debit totals) to avoid emitting financial data into logs,
     * and <strong>masks</strong> the two most sensitive identity values &mdash;
     * {@link #ssn} and {@link #governmentIssuedId} &mdash; showing only their presence
     * ({@code "****"}) or absence ({@code "null"}). This extends the security-conscious
     * {@code toString} convention used elsewhere in the model layer.</p>
     *
     * @return a human-readable, non-sensitive description of this account DTO
     */
    @Override
    public String toString() {
        return "AccountDto{"
                + "accountId='" + accountId + '\''
                + ", accountStatus='" + accountStatus + '\''
                + ", openDate=" + openDate
                + ", expirationDate=" + expirationDate
                + ", reissueDate=" + reissueDate
                + ", accountGroupId='" + accountGroupId + '\''
                + ", customerId='" + customerId + '\''
                + ", ssn=" + (ssn == null ? "null" : "****")
                + ", dateOfBirth=" + dateOfBirth
                + ", ficoScore='" + ficoScore + '\''
                + ", firstName='" + firstName + '\''
                + ", middleName='" + middleName + '\''
                + ", lastName='" + lastName + '\''
                + ", addressLine1='" + addressLine1 + '\''
                + ", addressLine2='" + addressLine2 + '\''
                + ", city='" + city + '\''
                + ", state='" + state + '\''
                + ", zipCode='" + zipCode + '\''
                + ", countryCode='" + countryCode + '\''
                + ", phoneNumber1='" + phoneNumber1 + '\''
                + ", phoneNumber2='" + phoneNumber2 + '\''
                + ", governmentIssuedId=" + (governmentIssuedId == null ? "null" : "****")
                + ", eftAccountId='" + eftAccountId + '\''
                + ", primaryCardHolderIndicator='" + primaryCardHolderIndicator + '\''
                + ", version=" + version
                + '}';
    }
}
