/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports every package
// exported by the java.base module (and the modules it reads). This brings in
// java.lang.String — the type of every BMS input-field component on this record —
// and java.util.Objects (used by requireNonNull for the AidKey check). It also
// brings in the exception classes IllegalArgumentException and
// NullPointerException raised by the compact constructor's invariant checks.
import module java.base;

// Module-import declarations may not import application-defined types; the COBOL
// traceability annotation lives in carddemo-domain and must be brought in by a
// conventional import.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input record carrying every value received from the 3270 terminal for the
 * <strong>COTRN01</strong> (View Transaction) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN01.bms} (mapset {@code COTRN01},
 *       map {@code COTRN1A}, size 24x80, FREEKB, ALARM).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN01.CPY} (input group
 *       {@code 01 COTRN1AI}, lines 17-144, with 21 PIC X input leaves).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN01C.cbl} (transaction
 *       {@code CT01}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated
 * into <em>entry-contract DTO records</em> on the corresponding application
 * class. This record is the Java analog of the input view of the BMS symbolic
 * structure {@code COTRN1AI}: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to {@code CoTrn01C.run(...)}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a
 * plain Java carrier built around finalized Java 25 language features only.
 *
 * <h2>Field-for-field translation</h2>
 * <p>The DTO carries <strong>all 21 PIC X input leaves</strong> declared by
 * {@code COTRN1AI} (7 header echoes, 13 transaction-detail display echoes, and
 * the error-message echo). This is the raw entry-contract DTO mandated by AAP
 * &sect;0.4.1: every symbolic-map leaf becomes a record component so that the
 * Java code can prove byte-for-byte parity against the COBOL BMS-mapped layout.
 * Convenience helpers ({@link #isTransactionIdBlank()}) are exposed as instance
 * methods on top of the raw fields; a derived projection record (if needed by
 * future consumers) would be a separate type.
 *
 * <h2>Transaction-view semantics</h2>
 * <p>COTRN01 is the <em>view</em> detail screen for a single transaction. On
 * entry the operator may:
 * <ol>
 *   <li>Type a transaction identifier into the {@code TRNIDIN} field (mapped
 *       to {@link #trnIdIn()}) and press ENTER to <em>fetch</em> the matching
 *       transaction record &mdash; equivalent to the COBOL
 *       {@code EXEC CICS READ FILE('TRANSACT') RIDFLD(TRNIDINI)} call.</li>
 *   <li>Press <strong>PF3</strong> to return to the calling program (typically
 *       {@code COMEN01C}, the main menu).</li>
 *   <li>Press <strong>PF4</strong> to clear the {@code TRNIDIN} field and
 *       re-display the screen for another lookup.</li>
 *   <li>Press <strong>PF5</strong> to transfer ({@code XCTL}) to
 *       {@code COTRN00C}, the transaction-browse list screen.</li>
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
 *       fields ({@code tOrigDt}, {@code tProcDt}) are the raw BMS strings;
 *       any date parsing happens inside the application logic via
 *       {@code java.time}.</li>
 *   <li>No {@code double} or {@code float} &mdash; the {@code trnAmt} field
 *       is carried as a raw BMS string (the upstream code converts from
 *       {@link java.math.BigDecimal} via the {@code Decimals} utility).</li>
 *   <li>No preview Java features &mdash; only finalized Java 25 features
 *       (records, JEP 511 module import, JEP 513 flexible constructor bodies).</li>
 * </ul>
 *
 * @see com.blitzy.carddemo.application.transaction.CoTrn01Input.AidKey
 * @see com.blitzy.carddemo.application.transaction.CoTrn01Output
 * @since 1.0.0
 */
@CobolProgram(
        value = "COTRN01",
        sourcePath = "app/bms/COTRN01.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook COTRN1AI "
                + "in app/cpy-bms/COTRN01.CPY lines 17-144. Field-for-field "
                + "translation of all 21 PIC X input leaves: 7 header echoes, "
                + "13 transaction-detail display echoes (trnId, cardNum, "
                + "tTypCd, tCatCd, trnSrc, tDesc, trnAmt, tOrigDt, tProcDt, "
                + "mId, mName, mCity, mZip), and the error-message echo. "
                + "PIC X(n) widths enforced at construction time."
)
public record CoTrn01Input(

        // ============================================================================
        // Header echo fields. The application class populates these on SEND-MAP;
        // they are echoed back on RECEIVE-MAP. The user does not edit any of these
        // fields directly. Retained verbatim per the field-for-field DTO mandate
        // (AAP §0.4.1).
        // ============================================================================
        String trnName,    // TRNNAMEI  PIC X(4)
        String title01,    // TITLE01I  PIC X(40)
        String curDate,    // CURDATEI  PIC X(8)
        String pgmName,    // PGMNAMEI  PIC X(8)
        String title02,    // TITLE02I  PIC X(40)
        String curTime,    // CURTIMEI  PIC X(8)

        // ============================================================================
        // Operator-entered transaction-id lookup key — used by the COBOL
        // PROCESS-ENTER-KEY paragraph as the READ key into the TRANSACT file.
        // ============================================================================
        String trnIdIn,    // TRNIDINI  PIC X(16)

        // ============================================================================
        // Transaction-detail display echoes. The controller populates these on
        // SEND-MAP after a successful READ; they are echoed back on RECEIVE-MAP.
        // The user does not edit any of these fields directly. Retained verbatim
        // per the field-for-field DTO mandate (AAP §0.4.1).
        // ============================================================================
        String trnId,      // TRNIDI    PIC X(16)
        String cardNum,    // CARDNUMI  PIC X(16)
        String tTypCd,     // TTYPCDI   PIC X(2)
        String tCatCd,     // TCATCDI   PIC X(4)
        String trnSrc,     // TRNSRCI   PIC X(10)
        String tDesc,      // TDESCI    PIC X(60)
        String trnAmt,     // TRNAMTI   PIC X(12)
        String tOrigDt,    // TORIGDTI  PIC X(10)
        String tProcDt,    // TPROCDTI  PIC X(10)
        String mId,        // MIDI      PIC X(9)
        String mName,      // MNAMEI    PIC X(30)
        String mCity,      // MCITYI    PIC X(25)
        String mZip,       // MZIPI     PIC X(10)

        // ============================================================================
        // Error-message echo (row 24).
        // ============================================================================
        String errMsg,     // ERRMSGI   PIC X(78)

        // ============================================================================
        // AID-key dispatch — how the user submitted the screen.
        // ============================================================================
        AidKey aidKey

) {

    /**
     * 3270 Attention Identifier (AID) decoded from the {@code EIBAID} byte
     * returned by CICS at {@code RECEIVE-MAP} time.
     *
     * <p>The COTRN01 controller dispatches on a four-value subset of this
     * enum:
     * <ul>
     *   <li>{@link #ENTER} &mdash; fetch the transaction record identified by
     *       {@link CoTrn01Input#trnIdIn()}. If the field is blank the
     *       controller emits {@code "Tran ID can NOT be empty..."} instead of
     *       attempting the {@code READ}.</li>
     *   <li>{@link #PF3} &mdash; return to the calling program (typically
     *       {@code COMEN01C}, the main menu).</li>
     *   <li>{@link #PF4} &mdash; clear the {@code TRNIDIN} field and
     *       re-display the screen for another lookup.</li>
     *   <li>{@link #PF5} &mdash; transfer ({@code XCTL}) to
     *       {@code COTRN00C}, the transaction-browse list screen.</li>
     * </ul>
     * Any other value (including {@link #CLEAR}, {@link #PA1}, {@link #PA2},
     * {@link #PF1}, {@link #PF2}, {@link #PF6}, {@link #PF7}, {@link #PF8},
     * {@link #PF9}, {@link #PF10}, {@link #PF11}, {@link #PF12}, and
     * {@link #OTHER}) yields the {@code CCDA-MSG-INVALID-KEY} error in the
     * controller's dispatch switch and re-displays the screen.
     *
     * <p>The full seventeen-value enum is preserved here (rather than the
     * four-value COTRN01-only subset) so that the future CICS-RECEIVE-MAP
     * adapter can decode {@code EIBAID} once and emit the same enum constant
     * on whichever input DTO it is constructing &mdash; the AID-decoder
     * logic stays uniform across all transaction-package screens.
     *
     * <p>Why an enum rather than a sealed interface? AAP &sect;0.6.10
     * reserves sealed interfaces for COBOL constructs that partition a value
     * space (REDEFINES and 88-level data taxonomies). The AID key is a closed
     * set of opaque dispatch tokens with no payload, which is exactly the
     * case for which a plain {@code enum} is idiomatic.
     */
    public enum AidKey {

        /** The user pressed ENTER &mdash; fetch on the COTRN01 screen. */
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

        /** PF4 &mdash; clear the {@code TRNIDIN} field on the COTRN01 screen. */
        PF4,

        /** PF5 &mdash; XCTL to {@code COTRN00C} (transaction-browse list). */
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
     * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong> (finalized
     * in Java 25): normalization and validation statements run before the
     * implicit canonical field-assignment, which is exactly the location for
     * COBOL-style "default to SPACES then validate length" cleansing.
     *
     * @throws NullPointerException     if {@code aidKey} is {@code null}
     * @throws IllegalArgumentException if any {@link String} component
     *                                  exceeds its declared BMS
     *                                  {@code PIC X(n)} width
     */
    public CoTrn01Input {
        // Header (6)
        trnName  = orEmpty(trnName);
        title01  = orEmpty(title01);
        curDate  = orEmpty(curDate);
        pgmName  = orEmpty(pgmName);
        title02  = orEmpty(title02);
        curTime  = orEmpty(curTime);
        // Lookup key (1)
        trnIdIn  = orEmpty(trnIdIn);
        // Transaction detail (13)
        trnId    = orEmpty(trnId);
        cardNum  = orEmpty(cardNum);
        tTypCd   = orEmpty(tTypCd);
        tCatCd   = orEmpty(tCatCd);
        trnSrc   = orEmpty(trnSrc);
        tDesc    = orEmpty(tDesc);
        trnAmt   = orEmpty(trnAmt);
        tOrigDt  = orEmpty(tOrigDt);
        tProcDt  = orEmpty(tProcDt);
        mId      = orEmpty(mId);
        mName    = orEmpty(mName);
        mCity    = orEmpty(mCity);
        mZip     = orEmpty(mZip);
        // Footer (1)
        errMsg   = orEmpty(errMsg);
        // AID key — required, never null
        Objects.requireNonNull(aidKey, "aidKey");

        // PIC X(n) fixed-length validation per app/cpy-bms/COTRN01.CPY lines 17-144.
        checkPicLength("trnName", trnName,  4);  // TRNNAMEI  PIC X(4)
        checkPicLength("title01", title01, 40);  // TITLE01I  PIC X(40)
        checkPicLength("curDate", curDate,  8);  // CURDATEI  PIC X(8)
        checkPicLength("pgmName", pgmName,  8);  // PGMNAMEI  PIC X(8)
        checkPicLength("title02", title02, 40);  // TITLE02I  PIC X(40)
        checkPicLength("curTime", curTime,  8);  // CURTIMEI  PIC X(8)
        checkPicLength("trnIdIn", trnIdIn, 16);  // TRNIDINI  PIC X(16)
        checkPicLength("trnId",   trnId,   16);  // TRNIDI    PIC X(16)
        checkPicLength("cardNum", cardNum, 16);  // CARDNUMI  PIC X(16)
        checkPicLength("tTypCd",  tTypCd,   2);  // TTYPCDI   PIC X(2)
        checkPicLength("tCatCd",  tCatCd,   4);  // TCATCDI   PIC X(4)
        checkPicLength("trnSrc",  trnSrc,  10);  // TRNSRCI   PIC X(10)
        checkPicLength("tDesc",   tDesc,   60);  // TDESCI    PIC X(60)
        checkPicLength("trnAmt",  trnAmt,  12);  // TRNAMTI   PIC X(12)
        checkPicLength("tOrigDt", tOrigDt, 10);  // TORIGDTI  PIC X(10)
        checkPicLength("tProcDt", tProcDt, 10);  // TPROCDTI  PIC X(10)
        checkPicLength("mId",     mId,      9);  // MIDI      PIC X(9)
        checkPicLength("mName",   mName,   30);  // MNAMEI    PIC X(30)
        checkPicLength("mCity",   mCity,   25);  // MCITYI    PIC X(25)
        checkPicLength("mZip",    mZip,    10);  // MZIPI     PIC X(10)
        checkPicLength("errMsg",  errMsg,  78);  // ERRMSGI   PIC X(78)
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
     * the COTRN01 screen: every {@link String} component is the empty
     * {@link String} and the {@link AidKey} is {@link AidKey#ENTER}.
     *
     * <p>This matches the legacy COBOL idiom for the first program
     * invocation: {@code DFHCOMMAREA} has zero length (no inbound state) or
     * carries no transaction-id payload, and the controller treats the
     * screen as a fresh view with no value yet entered.
     *
     * @return a fully-blank {@code CoTrn01Input} (never {@code null}) with
     *         {@link AidKey#ENTER}
     */
    public static CoTrn01Input empty() {
        return new CoTrn01Input(
                // Header (6)
                "", "", "", "", "", "",
                // Lookup key (1)
                "",
                // Transaction detail (13)
                "", "", "", "", "", "", "", "", "", "", "", "", "",
                // Footer (1)
                "",
                // AID key
                AidKey.ENTER);
    }

    /**
     * Returns {@code true} when {@link #trnIdIn()} is blank &mdash; either
     * the empty {@link String} or a string consisting only of whitespace
     * characters as defined by {@link String#isBlank()}.
     *
     * <p>Used by the COBOL {@code PROCESS-ENTER-KEY} paragraph (and by its
     * Java translation in {@code CoTrn01C}) to detect the
     * "{@code Tran ID can NOT be empty...}" condition. The COBOL test is
     * {@code IF TRNIDINI = SPACES OR LOW-VALUES}, which the Java helper
     * preserves verbatim by treating any whitespace-only string as blank
     * (via {@link String#isBlank()}) and treating a {@code null} reference
     * as blank as well.
     *
     * @return {@code true} when the transaction-id is null, empty, or
     *         whitespace-only; {@code false} otherwise
     */
    public boolean isTransactionIdBlank() {
        return trnIdIn == null || trnIdIn.isBlank();
    }
}
