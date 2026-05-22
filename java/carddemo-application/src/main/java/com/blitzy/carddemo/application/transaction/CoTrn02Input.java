/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports all packages
// exported by the java.base module (and the modules it reads). This brings in
// java.lang.String — the type of every BMS input-field component on this
// record — and java.util.Objects (used by requireNonNull for the AidKey
// check). It also brings in the exception classes IllegalArgumentException
// and NullPointerException raised by the compact constructor's invariant
// checks.
import module java.base;

// Module-import declarations may not import application-defined types; the
// COBOL traceability annotation lives in carddemo-domain and must be brought
// in by a conventional import.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input record carrying every value received from the 3270 terminal for the
 * <strong>COTRN02</strong> (Add Transaction) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN02.bms} (mapset {@code COTRN02},
 *       map {@code COTRN2A}, size 24x80, {@code CTRL=(ALARM,FREEKB)},
 *       {@code EXTATT=YES}, {@code LANG=COBOL}, {@code MODE=INOUT},
 *       {@code STORAGE=AUTO}, {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN02.CPY} (input group
 *       {@code 01 COTRN2AI}, lines 17-144, with 21 PIC X input leaves).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN02C.cbl} (transaction
 *       {@code CT02}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated
 * into <em>entry-contract DTO records</em> on the corresponding application
 * class. This record is the Java analog of the input view of the BMS symbolic
 * structure {@code COTRN2AI}: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to {@code CoTrn02C.run(...)}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a
 * plain Java carrier built around finalized Java 25 language features only.
 *
 * <h2>Field-for-field translation</h2>
 * <p>The DTO carries <strong>all 21 PIC X input leaves</strong> declared by
 * {@code COTRN2AI} (6 header echoes, 13 transaction-detail operator-editable
 * data fields, 1 confirmation field, and the error-message echo). This is the
 * raw entry-contract DTO mandated by AAP &sect;0.4.1: every symbolic-map leaf
 * becomes a record component so that the Java code can prove byte-for-byte
 * parity against the COBOL BMS-mapped layout. Convenience helpers
 * ({@link #isAllBlank()}) are exposed as instance methods on top of the raw
 * fields; a derived projection record (if needed by future consumers) would
 * be a separate type.
 *
 * <h2>Add-transaction semantics</h2>
 * <p>COTRN02 is the <em>add</em> form for transactions. On entry the operator
 * may:
 * <ol>
 *   <li>Type either {@link #accountId()} (ACTIDINI, 11 chars) <em>or</em>
 *       {@link #cardNumber()} (CARDNINI, 16 chars) &mdash; mutually exclusive,
 *       at least one required &mdash; to identify the target account.</li>
 *   <li>Type the transaction details into {@link #typeCode()},
 *       {@link #categoryCode()}, {@link #source()}, {@link #description()},
 *       {@link #amount()}, {@link #origDate()}, {@link #procDate()}, and
 *       merchant fields ({@link #merchantId()}, {@link #merchantName()},
 *       {@link #merchantCity()}, {@link #merchantZip()}).</li>
 *   <li>Type {@code 'Y'} into {@link #confirmation()} (CONFIRMI, 1 char) and
 *       press ENTER to commit the add operation, or {@code 'N'} to abort.</li>
 *   <li>Press <strong>PF3</strong> to return to the calling program (typically
 *       {@code COMEN01C}, the main menu).</li>
 *   <li>Press <strong>PF4</strong> to clear all input fields.</li>
 *   <li>Press <strong>PF5</strong> to copy the last transaction's fields into
 *       the input area.</li>
 * </ol>
 * Any other AID key produces an invalid-key error in the controller.
 *
 * <h2>Null and emptiness semantics</h2>
 * <p>The compact constructor coerces every {@code null} {@link String}
 * component to the empty {@link String} {@code ""}, matching the COBOL
 * RECEIVE-MAP idiom where unfilled BMS {@code PIC X(n)} fields are SPACES,
 * never undefined. The {@link AidKey} component is required (rejected as
 * {@code null} with a {@link NullPointerException} via
 * {@link java.util.Objects#requireNonNull(Object, String)}); the controller
 * always supplies a decoded AID key.
 *
 * <h2>PIC X(n) length validation (CWE-20)</h2>
 * <p>Each {@link String} component is validated against its declared BMS
 * {@code PIC X(n)} on-screen width. Values longer than the declared width are
 * rejected with an {@link IllegalArgumentException} at construction time. This
 * prevents silent hardware truncation in the CICS RECEIVE-MAP layer and
 * satisfies the AAP &sect;0.7.1 Preserve-As-Is contract at the DTO boundary.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final} and
 * accessors are auto-generated; there are no setters and no mutable internal
 * state. The record is therefore safe to share across threads without
 * synchronisation.
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in the
 *       compact constructor (see AAP &sect;0.6.3 / JEP 513).</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} &mdash; date
 *       fields ({@code origDate}, {@code procDate}) are the raw BMS strings;
 *       any date parsing happens inside the application logic via
 *       {@code java.time}.</li>
 *   <li>No {@code double} or {@code float} &mdash; the {@code amount} field
 *       is carried as a raw BMS string (the upstream code converts from
 *       {@link java.math.BigDecimal} via the {@code Decimals} utility).</li>
 *   <li>No preview Java features &mdash; only finalized Java 25 features
 *       (records, JEP 511 module import, JEP 513 flexible constructor bodies).</li>
 * </ul>
 *
 * @see com.blitzy.carddemo.application.transaction.CoTrn02Input.AidKey
 * @see com.blitzy.carddemo.application.transaction.CoTrn02Output
 * @since 1.0.0
 */
@CobolProgram(
        value = "COTRN02",
        sourcePath = "app/bms/COTRN02.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook COTRN2AI "
                + "in app/cpy-bms/COTRN02.CPY lines 17-144. Field-for-field "
                + "translation of all 21 PIC X input leaves: 6 header echoes "
                + "(trnName/title01/curDate/pgmName/title02/curTime), accountId, "
                + "cardNumber, 11 detail (typeCode/categoryCode/source/"
                + "description/amount/origDate/procDate/merchantId/merchantName/"
                + "merchantCity/merchantZip), confirmation, and errMsg. "
                + "PIC X(n) widths enforced at construction time."
)
public record CoTrn02Input(

        // ============================================================================
        // Header echo fields. The application class populates these on SEND-MAP;
        // they are echoed back on RECEIVE-MAP. The user does not edit any of
        // these fields directly. Retained verbatim per the field-for-field DTO
        // mandate (AAP §0.4.1).
        // ============================================================================
        String trnName,        // TRNNAMEI  PIC X(4)
        String title01,        // TITLE01I  PIC X(40)
        String curDate,        // CURDATEI  PIC X(8)
        String pgmName,        // PGMNAMEI  PIC X(8)
        String title02,        // TITLE02I  PIC X(40)
        String curTime,        // CURTIMEI  PIC X(8)

        // ============================================================================
        // Operator-editable data fields. The user types these in the COTRN02
        // (Add Transaction) screen.
        // ============================================================================
        String accountId,      // ACTIDINI  PIC X(11)
        String cardNumber,     // CARDNINI  PIC X(16)
        String typeCode,       // TTYPCDI   PIC X(2)
        String categoryCode,   // TCATCDI   PIC X(4)
        String source,         // TRNSRCI   PIC X(10)
        String description,    // TDESCI    PIC X(60)
        String amount,         // TRNAMTI   PIC X(12)
        String origDate,       // TORIGDTI  PIC X(10)
        String procDate,       // TPROCDTI  PIC X(10)
        String merchantId,     // MIDI      PIC X(9)
        String merchantName,   // MNAMEI    PIC X(30)
        String merchantCity,   // MCITYI    PIC X(25)
        String merchantZip,    // MZIPI     PIC X(10)
        String confirmation,   // CONFIRMI  PIC X(1)

        // ============================================================================
        // Error-message echo (row 24).
        // ============================================================================
        String errMsg,         // ERRMSGI   PIC X(78)

        // ============================================================================
        // AID-key dispatch — how the user submitted the screen.
        // ============================================================================
        AidKey aidKey

) {

    /**
     * The 3270 Attention Identifier (AID) byte decoded into a Java enum.
     *
     * <p>The 3270 protocol identifies which "attention" key the operator
     * pressed to dispatch the screen back to the host via a one-byte AID code
     * carried in the {@code EIBAID} field of the CICS Execute Interface
     * Block. The COTRN02 BMS map's PF-key footer at row 24 documents the
     * four keys actively used by the Add Transaction screen:
     * <blockquote>
     *   {@code ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran.}
     * </blockquote>
     * Any other AID byte produces an invalid-key error reported through
     * {@code ERRMSG} by the controller.
     *
     * <p>This enum mirrors the
     * {@code com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey} sealed
     * hierarchy described by Agent Action Plan &sect;0.6.10, which catalogs
     * the seventeen AID-key 88-level conditions defined in
     * {@code app/cpy/CVCRD01Y.cpy:§CCARD-AID}: ENTER, CLEAR, PA1, PA2, and
     * twelve programmed-function keys (PF1 through PF12). The {@link #OTHER}
     * member is a safety bucket for AID bytes that do not decode to any of
     * the sixteen named keys.
     *
     * <p>Per Agent Action Plan &sect;0.6.10, an enum (as opposed to a sealed
     * interface) is the appropriate representation here because the value
     * space is fully closed, the seventeen members carry no associated
     * payload data, and each operator action produces exactly one AID code
     * &mdash; an {@code enum} is idiomatic.
     */
    public enum AidKey {

        /** The user pressed ENTER &mdash; commit / continue on COTRN02. */
        ENTER,

        /** The user pressed CLEAR (terminal clear key). */
        CLEAR,

        /** The user pressed PA1. */
        PA1,

        /** The user pressed PA2. */
        PA2,

        /** PF1. */
        PF1,

        /** PF2. */
        PF2,

        /** PF3 &mdash; back to the calling program (typically {@code COMEN01C}). */
        PF3,

        /** PF4 &mdash; clear all input fields on the COTRN02 screen. */
        PF4,

        /** PF5 &mdash; copy the last transaction's fields into the input area. */
        PF5,

        /** PF6. */
        PF6,

        /** PF7. */
        PF7,

        /** PF8. */
        PF8,

        /** PF9. */
        PF9,

        /** PF10. */
        PF10,

        /** PF11. */
        PF11,

        /** PF12. */
        PF12,

        /** Any AID byte that does not decode to one of the named keys. */
        OTHER
    }

    /**
     * Compact (canonical) constructor.
     *
     * <p>Normalizes every {@link String} component so that a {@code null}
     * reference is converted to the empty {@link String} {@code ""}. This
     * mirrors COBOL RECEIVE-MAP semantics where unfilled BMS {@code PIC X(n)}
     * fields are SPACES, never undefined. The {@link AidKey} component is
     * required: a {@code null} argument throws {@link NullPointerException}
     * via {@link java.util.Objects#requireNonNull(Object, String)} because
     * the legacy COBOL controller always supplies a decoded AID key.
     *
     * <p>Subsequently validates that each {@link String} component does not
     * exceed its declared BMS {@code PIC X(n)} on-screen width: values longer
     * than the declared width raise an {@link IllegalArgumentException}
     * (CWE-20 input validation). Shorter values are accepted unchanged.
     *
     * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong>
     * (finalized in Java 25): normalization and validation statements run
     * before the implicit canonical field-assignment, which is exactly the
     * location for COBOL-style "default to SPACES then validate length"
     * cleansing.
     *
     * <p><strong>Field-content validation</strong> &mdash; numeric format
     * check on {@code amount}, ISO date parse on {@code origDate} and
     * {@code procDate}, Y/N tri-state check on {@code confirmation},
     * mutual-exclusion check on {@code accountId} vs {@code cardNumber}
     * &mdash; is performed by {@code CoTrn02C.validateInputDataFields()}
     * (the Java translation of the COBOL paragraph of the same name), NOT
     * by this DTO. The DTO is purely a structural carrier per AAP &sect;0.4.1.
     *
     * @throws NullPointerException     if {@code aidKey} is {@code null}
     * @throws IllegalArgumentException if any {@link String} component
     *                                  exceeds its declared BMS
     *                                  {@code PIC X(n)} width
     */
    public CoTrn02Input {
        // Header (6)
        trnName       = orEmpty(trnName);
        title01       = orEmpty(title01);
        curDate       = orEmpty(curDate);
        pgmName       = orEmpty(pgmName);
        title02       = orEmpty(title02);
        curTime       = orEmpty(curTime);
        // Operator-editable detail (13)
        accountId     = orEmpty(accountId);
        cardNumber    = orEmpty(cardNumber);
        typeCode      = orEmpty(typeCode);
        categoryCode  = orEmpty(categoryCode);
        source        = orEmpty(source);
        description   = orEmpty(description);
        amount        = orEmpty(amount);
        origDate      = orEmpty(origDate);
        procDate      = orEmpty(procDate);
        merchantId    = orEmpty(merchantId);
        merchantName  = orEmpty(merchantName);
        merchantCity  = orEmpty(merchantCity);
        merchantZip   = orEmpty(merchantZip);
        // Confirmation (1)
        confirmation  = orEmpty(confirmation);
        // Footer (1)
        errMsg        = orEmpty(errMsg);
        // AID key — required, never null
        Objects.requireNonNull(aidKey, "aidKey");

        // PIC X(n) fixed-length validation per app/cpy-bms/COTRN02.CPY lines 17-144.
        checkPicLength("trnName",      trnName,       4);  // TRNNAMEI PIC X(4)
        checkPicLength("title01",      title01,      40);  // TITLE01I PIC X(40)
        checkPicLength("curDate",      curDate,       8);  // CURDATEI PIC X(8)
        checkPicLength("pgmName",      pgmName,       8);  // PGMNAMEI PIC X(8)
        checkPicLength("title02",      title02,      40);  // TITLE02I PIC X(40)
        checkPicLength("curTime",      curTime,       8);  // CURTIMEI PIC X(8)
        checkPicLength("accountId",    accountId,    11);  // ACTIDINI PIC X(11)
        checkPicLength("cardNumber",   cardNumber,   16);  // CARDNINI PIC X(16)
        checkPicLength("typeCode",     typeCode,      2);  // TTYPCDI  PIC X(2)
        checkPicLength("categoryCode", categoryCode,  4);  // TCATCDI  PIC X(4)
        checkPicLength("source",       source,       10);  // TRNSRCI  PIC X(10)
        checkPicLength("description",  description,  60);  // TDESCI   PIC X(60)
        checkPicLength("amount",       amount,       12);  // TRNAMTI  PIC X(12)
        checkPicLength("origDate",     origDate,     10);  // TORIGDTI PIC X(10)
        checkPicLength("procDate",     procDate,     10);  // TPROCDTI PIC X(10)
        checkPicLength("merchantId",   merchantId,    9);  // MIDI     PIC X(9)
        checkPicLength("merchantName", merchantName, 30);  // MNAMEI   PIC X(30)
        checkPicLength("merchantCity", merchantCity, 25);  // MCITYI   PIC X(25)
        checkPicLength("merchantZip",  merchantZip,  10);  // MZIPI    PIC X(10)
        checkPicLength("confirmation", confirmation,  1);  // CONFIRMI PIC X(1)
        checkPicLength("errMsg",       errMsg,       78);  // ERRMSGI  PIC X(78)
    }

    /**
     * Returns the argument if non-null, or the empty string {@code ""}
     * otherwise.
     *
     * @param s the candidate string (may be {@code null})
     * @return {@code s} if non-null, otherwise {@code ""}
     */
    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * Validates that a {@link String} component does not exceed its declared
     * BMS {@code PIC X(n)} on-screen width.
     *
     * <p>Enforces the AAP &sect;0.7.1 Preserve-As-Is contract at the DTO
     * boundary (CWE-20 input validation): values longer than the declared
     * BMS width would cause silent hardware truncation in the CICS
     * RECEIVE-MAP layer. Shorter values are accepted unchanged.
     *
     * @param name      the component name (used in the exception message)
     * @param value     the component value (never {@code null}: the caller
     *                  guarantees normalization via {@link #orEmpty(String)})
     * @param maxLength the declared BMS {@code PIC X(n)} width
     * @throws IllegalArgumentException if {@code value.length() > maxLength}
     */
    private static void checkPicLength(String name, String value, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " exceeds BMS PIC X(" + maxLength
                            + ") declared length; received length="
                            + value.length() + " value=\"" + value + "\"");
        }
    }

    /**
     * Returns an "empty" input record suitable for the first dispatch into
     * the COTRN02 screen: every {@link String} component is the empty
     * {@link String} and the {@link AidKey} is {@link AidKey#ENTER}.
     *
     * <p>This matches the legacy COBOL idiom for the first program
     * invocation: {@code DFHCOMMAREA} has zero length (no inbound state) or
     * carries no Add-Transaction payload, and the controller treats the
     * screen as a fresh add form with no values yet entered.
     *
     * @return a fully-blank {@code CoTrn02Input} (never {@code null}) with
     *         {@link AidKey#ENTER}
     */
    public static CoTrn02Input empty() {
        return new CoTrn02Input(
                // Header (6)
                "", "", "", "", "", "",
                // Detail (13)
                "", "", "", "", "", "", "", "", "", "", "", "", "",
                // Confirmation (1)
                "",
                // Footer (1)
                "",
                // AID key
                AidKey.ENTER);
    }

    /**
     * Returns {@code true} when every operator-editable string component is
     * blank &mdash; either the empty {@link String} or a string consisting
     * only of whitespace characters as defined by {@link String#isBlank()}.
     *
     * <p>This mirrors the legacy COBOL idiom in {@code COTRN02C}'s
     * {@code INITIALIZE-ALL-FIELDS} (or equivalently
     * {@code RECEIVE-INPUT-SCREEN}) paragraph, which tests every input field
     * for {@code SPACES} or {@code LOW-VALUES} to detect a freshly-cleared
     * screen. The COBOL test {@code IF field = SPACES OR LOW-VALUES} is
     * preserved here as {@code field.isBlank()}, which matches both idioms
     * (whitespace-only or empty).
     *
     * <p>The helper deliberately checks only the fourteen operator-editable
     * fields (accountId, cardNumber, typeCode, categoryCode, source,
     * description, amount, origDate, procDate, merchantId, merchantName,
     * merchantCity, merchantZip, confirmation); the six header echo fields
     * and the errMsg echo are populated by the controller on SEND-MAP and
     * are not part of the "is the screen blank?" check.
     *
     * @return {@code true} when all fourteen operator-editable string
     *         components are blank or whitespace-only; {@code false}
     *         otherwise
     */
    public boolean isAllBlank() {
        return accountId.isBlank()
                && cardNumber.isBlank()
                && typeCode.isBlank()
                && categoryCode.isBlank()
                && source.isBlank()
                && description.isBlank()
                && amount.isBlank()
                && origDate.isBlank()
                && procDate.isBlank()
                && merchantId.isBlank()
                && merchantName.isBlank()
                && merchantCity.isBlank()
                && merchantZip.isBlank()
                && confirmation.isBlank();
    }
}
