/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by
// the java.base module (and the modules it reads). This brings in java.lang.String — the
// type of every BMS input-field component on this record — and java.util.Objects (used by
// requireNonNull for the AidKey check). It also brings in the exception classes
// IllegalArgumentException and NullPointerException raised by the compact constructor's
// invariant checks.
import module java.base;

// Module-import declarations may not import application-defined types; the COBOL traceability
// annotation lives in carddemo-domain and must be brought in by a conventional import.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input record carrying every value received from the 3270 terminal for the
 * <strong>COTRN00</strong> (Transaction List) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN00.bms} (mapset {@code COTRN00},
 *       map {@code COTRN0A}, size 24x80, FREEKB, ALARM).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN00.CPY} (input group
 *       {@code 01 COTRN0AI}, lines 17-372, with 59 PIC X leaves).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN00C.cbl} (transaction
 *       {@code CT00}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application class. This
 * record is the Java analog of the input view of the BMS symbolic structure
 * {@code COTRN0AI}: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to {@code CoTrn00C.run(...)}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a plain
 * Java carrier built around finalized Java 25 language features only.
 *
 * <h2>Field-for-field translation</h2>
 * <p>The DTO carries <strong>all 59 PIC X input leaves</strong> declared by
 * {@code COTRN0AI} (header echoes, search field, 10 row clusters of 5 fields, and
 * the error-message echo). This is the raw entry-contract DTO mandated by AAP
 * &sect;0.4.1: every symbolic-map leaf becomes a record component so that the
 * Java code can prove byte-for-byte parity against the COBOL BMS-mapped layout.
 * Convenience helpers ({@link #firstSelectedRow()}, {@link #isSearchIdBlank()})
 * are exposed as instance methods on top of the raw fields; a derived projection
 * record (if needed by future consumers) would be a separate type.
 *
 * <h2>Transaction-list semantics</h2>
 * <p>COTRN00 is the paginated <em>list / browse</em> screen for transactions. On
 * entry the operator may:
 * <ol>
 *   <li>Type a transaction identifier into the {@code TRNIDIN} field (mapped to
 *       {@link #trnIdIn()}) and press ENTER to <em>reposition</em> the list at
 *       that key &mdash; equivalent to the COBOL
 *       {@code EXEC CICS STARTBR ... RIDFLD(TRNIDINI)} call.</li>
 *   <li>Type {@code 'S'} (uppercase or lowercase) into one of the ten SEL columns
 *       ({@link #sel0001()} through {@link #sel0010()}) to mark that row for
 *       detailed view. The COBOL {@code PROCESS-ENTER-KEY} paragraph scans the
 *       rows in order and dispatches to {@code COTRN01C} (Transaction View) on
 *       the first match.</li>
 *   <li>Press <strong>PF7</strong> to paginate backward or <strong>PF8</strong> to
 *       paginate forward (mapped to the {@link AidKey} component).</li>
 *   <li>Press <strong>PF3</strong> to return to the main menu ({@code COMEN01C}).</li>
 *   <li>Press <strong>PF4</strong> to clear filters and start a fresh listing.</li>
 * </ol>
 * Any other AID key produces an invalid-key error in the controller.
 *
 * <h2>Null and emptiness semantics</h2>
 * <p>The compact constructor coerces every {@code null} {@link String} component
 * to the empty {@link String} {@code ""}, matching the COBOL RECEIVE-MAP idiom
 * where unfilled BMS {@code PIC X(n)} fields are SPACES, never undefined. The
 * {@link AidKey} component is required (rejected as {@code null} with a
 * {@link NullPointerException} via
 * {@link java.util.Objects#requireNonNull(Object, String)}); the controller
 * always supplies a decoded AID key.
 *
 * <h2>PIC X(n) length validation</h2>
 * <p>The compact constructor enforces every component's declared {@code PIC X(n)}
 * on-screen width. Values longer than the declared width raise an
 * {@link IllegalArgumentException} (CWE-20). Shorter values are accepted unchanged
 * (representing an unfilled field, which arrives as SPACES); empty strings are
 * accepted as the COBOL SPACES idiom.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final} and accessors
 * are auto-generated; there are no setters and no mutable internal state. The
 * resulting instance is safe to publish across virtual threads (per AAP &sect;0.6.6)
 * without synchronization.
 *
 * @see com.blitzy.carddemo.application.transaction.CoTrn00Input.AidKey
 * @see com.blitzy.carddemo.application.transaction.CoTrn00Output
 * @since 1.0.0
 */
@CobolProgram(
        value = "COTRN00",
        sourcePath = "app/bms/COTRN00.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook COTRN0AI "
                + "in app/cpy-bms/COTRN00.CPY lines 17-372. Field-for-field "
                + "translation of all 59 PIC X input leaves: 8 header echoes, "
                + "10 row clusters of 5 fields (sel/trnId/tDate/tDesc/tAmt), "
                + "and the error-message echo. PIC X(n) widths enforced at "
                + "construction time."
)
public record CoTrn00Input(

        // ============================================================================
        // Header echo fields (rows 1-2 of the 24x80 BMS map, plus PAGENUM on row 3).
        // The application class populates these on SEND-MAP; they are echoed back on
        // RECEIVE-MAP. The user does not edit any of these fields directly.
        // ============================================================================
        String trnName,    // TRNNAMEI  PIC X(4)
        String title01,    // TITLE01I  PIC X(40)
        String curDate,    // CURDATEI  PIC X(8)   — MM/DD/YY
        String pgmName,    // PGMNAMEI  PIC X(8)
        String title02,    // TITLE02I  PIC X(40)
        String curTime,    // CURTIMEI  PIC X(8)   — HH:MM:SS
        String pageNum,    // PAGENUMI  PIC X(8)

        // ============================================================================
        // Operator-entered transaction-id positioning key — used by the COBOL
        // PROCESS-ENTER-KEY paragraph as the STARTBR repositioning key.
        // ============================================================================
        String trnIdIn,    // TRNIDINI  PIC X(16)

        // ============================================================================
        // Row 1 — selection flag + display echoes. SELxxxxI is the only editable
        // field per row; the TRNIDxxI / TDATExxI / TDESCxxI / TAMTxxxI display echoes
        // are populated by SEND-MAP and arrive back as SPACES on RECEIVE-MAP. They
        // are retained verbatim per the field-for-field DTO mandate (AAP §0.4.1).
        // ============================================================================
        String sel0001,    // SEL0001I  PIC X(1)
        String trnId01,    // TRNID01I  PIC X(16)
        String tDate01,    // TDATE01I  PIC X(8)
        String tDesc01,    // TDESC01I  PIC X(26)
        String tAmt001,    // TAMT001I  PIC X(12)

        // Row 2
        String sel0002,    // SEL0002I  PIC X(1)
        String trnId02,    // TRNID02I  PIC X(16)
        String tDate02,    // TDATE02I  PIC X(8)
        String tDesc02,    // TDESC02I  PIC X(26)
        String tAmt002,    // TAMT002I  PIC X(12)

        // Row 3
        String sel0003,    // SEL0003I  PIC X(1)
        String trnId03,    // TRNID03I  PIC X(16)
        String tDate03,    // TDATE03I  PIC X(8)
        String tDesc03,    // TDESC03I  PIC X(26)
        String tAmt003,    // TAMT003I  PIC X(12)

        // Row 4
        String sel0004,    // SEL0004I  PIC X(1)
        String trnId04,    // TRNID04I  PIC X(16)
        String tDate04,    // TDATE04I  PIC X(8)
        String tDesc04,    // TDESC04I  PIC X(26)
        String tAmt004,    // TAMT004I  PIC X(12)

        // Row 5
        String sel0005,    // SEL0005I  PIC X(1)
        String trnId05,    // TRNID05I  PIC X(16)
        String tDate05,    // TDATE05I  PIC X(8)
        String tDesc05,    // TDESC05I  PIC X(26)
        String tAmt005,    // TAMT005I  PIC X(12)

        // Row 6
        String sel0006,    // SEL0006I  PIC X(1)
        String trnId06,    // TRNID06I  PIC X(16)
        String tDate06,    // TDATE06I  PIC X(8)
        String tDesc06,    // TDESC06I  PIC X(26)
        String tAmt006,    // TAMT006I  PIC X(12)

        // Row 7
        String sel0007,    // SEL0007I  PIC X(1)
        String trnId07,    // TRNID07I  PIC X(16)
        String tDate07,    // TDATE07I  PIC X(8)
        String tDesc07,    // TDESC07I  PIC X(26)
        String tAmt007,    // TAMT007I  PIC X(12)

        // Row 8
        String sel0008,    // SEL0008I  PIC X(1)
        String trnId08,    // TRNID08I  PIC X(16)
        String tDate08,    // TDATE08I  PIC X(8)
        String tDesc08,    // TDESC08I  PIC X(26)
        String tAmt008,    // TAMT008I  PIC X(12)

        // Row 9
        String sel0009,    // SEL0009I  PIC X(1)
        String trnId09,    // TRNID09I  PIC X(16)
        String tDate09,    // TDATE09I  PIC X(8)
        String tDesc09,    // TDESC09I  PIC X(26)
        String tAmt009,    // TAMT009I  PIC X(12)

        // Row 10
        String sel0010,    // SEL0010I  PIC X(1)
        String trnId10,    // TRNID10I  PIC X(16)
        String tDate10,    // TDATE10I  PIC X(8)
        String tDesc10,    // TDESC10I  PIC X(26)
        String tAmt010,    // TAMT010I  PIC X(12)

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
     * The number of selectable rows per COTRN00 page.
     *
     * <p>Mirrors the fixed COBOL constant in {@code COTRN00C} and the static layout
     * of {@code COTRN00.bms}: ten {@code SEL{NN}} columns (SEL0001 through SEL0010)
     * each backed by a {@code PIC X(1)} input field in the symbolic copybook
     * {@code COTRN0AI}.
     */
    public static final int ROWS_PER_PAGE = 10;

    /**
     * 3270 Attention Identifier (AID) decoded from the {@code EIBAID} byte returned
     * by CICS at RECEIVE-MAP time.
     *
     * <p>The COTRN00 controller dispatches on a five-value subset of this enum:
     * <ul>
     *   <li>{@link #ENTER} &mdash; reposition the list to {@code trnIdIn} if filled,
     *       then process any selection (first {@code 'S'} / {@code 's'} in the
     *       SELxxxx fields) by transferring to {@code COTRN01C}.</li>
     *   <li>{@link #PF3} &mdash; transfer back to the main menu ({@code COMEN01C}).</li>
     *   <li>{@link #PF4} &mdash; clear the search field and selections.</li>
     *   <li>{@link #PF7} &mdash; paginate backward (decrement page number).</li>
     *   <li>{@link #PF8} &mdash; paginate forward (increment page number).</li>
     * </ul>
     * Any other value yields the {@code CCDA-MSG-INVALID-KEY} error in the
     * controller's dispatch switch.
     */
    public enum AidKey {

        /** The user pressed ENTER. */
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

        /** PF3 &mdash; back to main menu ({@code COMEN01C}) on the COTRN00 screen. */
        PF3,

        /** PF4 &mdash; clear filters on the COTRN00 screen. */
        PF4,

        /** PF5. */
        PF5,

        /** PF6. */
        PF6,

        /** PF7 &mdash; paginate backward on the COTRN00 screen. */
        PF7,

        /** PF8 &mdash; paginate forward on the COTRN00 screen. */
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
     * <p>Normalizes every {@link String} component so that a {@code null} reference is
     * converted to the empty {@link String} {@code ""}. This mirrors COBOL
     * RECEIVE-MAP semantics where unfilled BMS {@code PIC X(n)} fields are SPACES,
     * never undefined. The {@link AidKey} component is required: a {@code null}
     * argument throws {@link NullPointerException} via
     * {@link java.util.Objects#requireNonNull(Object, String)} because the legacy
     * COBOL controller always supplies a decoded AID key.
     *
     * <p>Subsequently validates that each {@link String} component does not exceed
     * its declared BMS {@code PIC X(n)} on-screen width: values longer than the
     * declared width raise an {@link IllegalArgumentException} (CWE-20 input
     * validation). Shorter values are accepted unchanged.
     *
     * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong> (finalized in Java
     * 25): normalization and validation statements run before the implicit canonical
     * field-assignment, which is exactly the location for COBOL-style "default to
     * SPACES then validate length" cleansing.
     *
     * @throws NullPointerException     if {@code aidKey} is {@code null}
     * @throws IllegalArgumentException if any {@link String} component exceeds its
     *                                  declared BMS {@code PIC X(n)} width
     */
    public CoTrn00Input {
        // Header echoes (8)
        trnName  = orEmpty(trnName);
        title01  = orEmpty(title01);
        curDate  = orEmpty(curDate);
        pgmName  = orEmpty(pgmName);
        title02  = orEmpty(title02);
        curTime  = orEmpty(curTime);
        pageNum  = orEmpty(pageNum);
        trnIdIn  = orEmpty(trnIdIn);
        // Row 1 (5)
        sel0001  = orEmpty(sel0001);
        trnId01  = orEmpty(trnId01);
        tDate01  = orEmpty(tDate01);
        tDesc01  = orEmpty(tDesc01);
        tAmt001  = orEmpty(tAmt001);
        // Row 2 (5)
        sel0002  = orEmpty(sel0002);
        trnId02  = orEmpty(trnId02);
        tDate02  = orEmpty(tDate02);
        tDesc02  = orEmpty(tDesc02);
        tAmt002  = orEmpty(tAmt002);
        // Row 3 (5)
        sel0003  = orEmpty(sel0003);
        trnId03  = orEmpty(trnId03);
        tDate03  = orEmpty(tDate03);
        tDesc03  = orEmpty(tDesc03);
        tAmt003  = orEmpty(tAmt003);
        // Row 4 (5)
        sel0004  = orEmpty(sel0004);
        trnId04  = orEmpty(trnId04);
        tDate04  = orEmpty(tDate04);
        tDesc04  = orEmpty(tDesc04);
        tAmt004  = orEmpty(tAmt004);
        // Row 5 (5)
        sel0005  = orEmpty(sel0005);
        trnId05  = orEmpty(trnId05);
        tDate05  = orEmpty(tDate05);
        tDesc05  = orEmpty(tDesc05);
        tAmt005  = orEmpty(tAmt005);
        // Row 6 (5)
        sel0006  = orEmpty(sel0006);
        trnId06  = orEmpty(trnId06);
        tDate06  = orEmpty(tDate06);
        tDesc06  = orEmpty(tDesc06);
        tAmt006  = orEmpty(tAmt006);
        // Row 7 (5)
        sel0007  = orEmpty(sel0007);
        trnId07  = orEmpty(trnId07);
        tDate07  = orEmpty(tDate07);
        tDesc07  = orEmpty(tDesc07);
        tAmt007  = orEmpty(tAmt007);
        // Row 8 (5)
        sel0008  = orEmpty(sel0008);
        trnId08  = orEmpty(trnId08);
        tDate08  = orEmpty(tDate08);
        tDesc08  = orEmpty(tDesc08);
        tAmt008  = orEmpty(tAmt008);
        // Row 9 (5)
        sel0009  = orEmpty(sel0009);
        trnId09  = orEmpty(trnId09);
        tDate09  = orEmpty(tDate09);
        tDesc09  = orEmpty(tDesc09);
        tAmt009  = orEmpty(tAmt009);
        // Row 10 (5)
        sel0010  = orEmpty(sel0010);
        trnId10  = orEmpty(trnId10);
        tDate10  = orEmpty(tDate10);
        tDesc10  = orEmpty(tDesc10);
        tAmt010  = orEmpty(tAmt010);
        // Footer (1)
        errMsg   = orEmpty(errMsg);
        // AID key — required, never null
        Objects.requireNonNull(aidKey, "aidKey");

        // PIC X(n) fixed-length validation per app/cpy-bms/COTRN00.CPY lines 17-372.
        checkPicLength("trnName",  trnName,   4);  // TRNNAMEI  PIC X(4)
        checkPicLength("title01",  title01,  40);  // TITLE01I  PIC X(40)
        checkPicLength("curDate",  curDate,   8);  // CURDATEI  PIC X(8)
        checkPicLength("pgmName",  pgmName,   8);  // PGMNAMEI  PIC X(8)
        checkPicLength("title02",  title02,  40);  // TITLE02I  PIC X(40)
        checkPicLength("curTime",  curTime,   8);  // CURTIMEI  PIC X(8)
        checkPicLength("pageNum",  pageNum,   8);  // PAGENUMI  PIC X(8)
        checkPicLength("trnIdIn",  trnIdIn,  16);  // TRNIDINI  PIC X(16)
        checkPicLength("sel0001",  sel0001,   1);  // SEL0001I  PIC X(1)
        checkPicLength("trnId01",  trnId01,  16);  // TRNID01I  PIC X(16)
        checkPicLength("tDate01",  tDate01,   8);  // TDATE01I  PIC X(8)
        checkPicLength("tDesc01",  tDesc01,  26);  // TDESC01I  PIC X(26)
        checkPicLength("tAmt001",  tAmt001,  12);  // TAMT001I  PIC X(12)
        checkPicLength("sel0002",  sel0002,   1);  // SEL0002I  PIC X(1)
        checkPicLength("trnId02",  trnId02,  16);  // TRNID02I  PIC X(16)
        checkPicLength("tDate02",  tDate02,   8);  // TDATE02I  PIC X(8)
        checkPicLength("tDesc02",  tDesc02,  26);  // TDESC02I  PIC X(26)
        checkPicLength("tAmt002",  tAmt002,  12);  // TAMT002I  PIC X(12)
        checkPicLength("sel0003",  sel0003,   1);  // SEL0003I  PIC X(1)
        checkPicLength("trnId03",  trnId03,  16);  // TRNID03I  PIC X(16)
        checkPicLength("tDate03",  tDate03,   8);  // TDATE03I  PIC X(8)
        checkPicLength("tDesc03",  tDesc03,  26);  // TDESC03I  PIC X(26)
        checkPicLength("tAmt003",  tAmt003,  12);  // TAMT003I  PIC X(12)
        checkPicLength("sel0004",  sel0004,   1);  // SEL0004I  PIC X(1)
        checkPicLength("trnId04",  trnId04,  16);  // TRNID04I  PIC X(16)
        checkPicLength("tDate04",  tDate04,   8);  // TDATE04I  PIC X(8)
        checkPicLength("tDesc04",  tDesc04,  26);  // TDESC04I  PIC X(26)
        checkPicLength("tAmt004",  tAmt004,  12);  // TAMT004I  PIC X(12)
        checkPicLength("sel0005",  sel0005,   1);  // SEL0005I  PIC X(1)
        checkPicLength("trnId05",  trnId05,  16);  // TRNID05I  PIC X(16)
        checkPicLength("tDate05",  tDate05,   8);  // TDATE05I  PIC X(8)
        checkPicLength("tDesc05",  tDesc05,  26);  // TDESC05I  PIC X(26)
        checkPicLength("tAmt005",  tAmt005,  12);  // TAMT005I  PIC X(12)
        checkPicLength("sel0006",  sel0006,   1);  // SEL0006I  PIC X(1)
        checkPicLength("trnId06",  trnId06,  16);  // TRNID06I  PIC X(16)
        checkPicLength("tDate06",  tDate06,   8);  // TDATE06I  PIC X(8)
        checkPicLength("tDesc06",  tDesc06,  26);  // TDESC06I  PIC X(26)
        checkPicLength("tAmt006",  tAmt006,  12);  // TAMT006I  PIC X(12)
        checkPicLength("sel0007",  sel0007,   1);  // SEL0007I  PIC X(1)
        checkPicLength("trnId07",  trnId07,  16);  // TRNID07I  PIC X(16)
        checkPicLength("tDate07",  tDate07,   8);  // TDATE07I  PIC X(8)
        checkPicLength("tDesc07",  tDesc07,  26);  // TDESC07I  PIC X(26)
        checkPicLength("tAmt007",  tAmt007,  12);  // TAMT007I  PIC X(12)
        checkPicLength("sel0008",  sel0008,   1);  // SEL0008I  PIC X(1)
        checkPicLength("trnId08",  trnId08,  16);  // TRNID08I  PIC X(16)
        checkPicLength("tDate08",  tDate08,   8);  // TDATE08I  PIC X(8)
        checkPicLength("tDesc08",  tDesc08,  26);  // TDESC08I  PIC X(26)
        checkPicLength("tAmt008",  tAmt008,  12);  // TAMT008I  PIC X(12)
        checkPicLength("sel0009",  sel0009,   1);  // SEL0009I  PIC X(1)
        checkPicLength("trnId09",  trnId09,  16);  // TRNID09I  PIC X(16)
        checkPicLength("tDate09",  tDate09,   8);  // TDATE09I  PIC X(8)
        checkPicLength("tDesc09",  tDesc09,  26);  // TDESC09I  PIC X(26)
        checkPicLength("tAmt009",  tAmt009,  12);  // TAMT009I  PIC X(12)
        checkPicLength("sel0010",  sel0010,   1);  // SEL0010I  PIC X(1)
        checkPicLength("trnId10",  trnId10,  16);  // TRNID10I  PIC X(16)
        checkPicLength("tDate10",  tDate10,   8);  // TDATE10I  PIC X(8)
        checkPicLength("tDesc10",  tDesc10,  26);  // TDESC10I  PIC X(26)
        checkPicLength("tAmt010",  tAmt010,  12);  // TAMT010I  PIC X(12)
        checkPicLength("errMsg",   errMsg,   78);  // ERRMSGI   PIC X(78)
    }

    /**
     * Returns the argument if non-null, or the empty string {@code ""} otherwise.
     *
     * @param s the candidate string (may be {@code null})
     * @return {@code s} if non-null, otherwise {@code ""}
     */
    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * Validates that a {@link String} component does not exceed its declared BMS
     * {@code PIC X(n)} on-screen width.
     *
     * <p>Enforces the AAP &sect;0.7.1 Preserve-As-Is contract at the DTO boundary
     * (CWE-20 input validation): values longer than the declared BMS width would
     * cause silent hardware truncation in the CICS RECEIVE-MAP layer. Shorter
     * values are accepted unchanged.
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
     * Returns an "empty" input record suitable for the first dispatch into the
     * COTRN00 screen: every {@link String} component is the empty string and the
     * {@link AidKey} is {@link AidKey#ENTER}.
     *
     * <p>This matches the legacy COBOL idiom for the first program invocation:
     * {@code DFHCOMMAREA} has zero length (no inbound state), and the controller
     * treats the screen as a fresh listing with no filter and no row selection.
     *
     * @return a fully-blank {@code CoTrn00Input} (never {@code null}) with
     *         {@link AidKey#ENTER}
     */
    public static CoTrn00Input empty() {
        return new CoTrn00Input(
                // Header echoes (8)
                "", "", "", "", "", "", "", "",
                // Row 1 (5)
                "", "", "", "", "",
                // Row 2 (5)
                "", "", "", "", "",
                // Row 3 (5)
                "", "", "", "", "",
                // Row 4 (5)
                "", "", "", "", "",
                // Row 5 (5)
                "", "", "", "", "",
                // Row 6 (5)
                "", "", "", "", "",
                // Row 7 (5)
                "", "", "", "", "",
                // Row 8 (5)
                "", "", "", "", "",
                // Row 9 (5)
                "", "", "", "", "",
                // Row 10 (5)
                "", "", "", "", "",
                // Footer (1)
                "",
                // AID key
                AidKey.ENTER);
    }

    /**
     * Returns the 1-based index of the first row marked {@code "S"} or {@code "s"}
     * in the ten SEL fields, or {@code -1} if no row is selected.
     *
     * <p>Mirrors the COBOL {@code PROCESS-ENTER-KEY} paragraph in
     * {@code app/cbl/COTRN00C.cbl}, which scans {@code SEL0001I} through
     * {@code SEL0010I} in order and dispatches on the first match by moving the
     * row's {@code TRNID} into {@code CDEMO-CT00-TRN-SELECTED} and issuing
     * {@code EXEC CICS XCTL PROGRAM('COTRN01C')}. Subsequent selected rows are
     * <em>ignored</em> &mdash; only the first match drives navigation.
     *
     * <p>The match is exact and case-sensitive in the two-character set
     * {@code {"S", "s"}}.
     *
     * @return a value in {@code [1..ROWS_PER_PAGE]} if a row is selected;
     *         {@code -1} if no row is selected
     */
    public int firstSelectedRow() {
        if (isSelected(sel0001)) return 1;
        if (isSelected(sel0002)) return 2;
        if (isSelected(sel0003)) return 3;
        if (isSelected(sel0004)) return 4;
        if (isSelected(sel0005)) return 5;
        if (isSelected(sel0006)) return 6;
        if (isSelected(sel0007)) return 7;
        if (isSelected(sel0008)) return 8;
        if (isSelected(sel0009)) return 9;
        if (isSelected(sel0010)) return 10;
        return -1;
    }

    /**
     * Returns {@code true} when the selector value matches the COBOL convention
     * for a marked row: exactly {@code "S"} or {@code "s"}. Other strings,
     * including {@code "S "} (with trailing space), are not considered selected.
     *
     * @param sel the selector value
     * @return {@code true} if the value is {@code "S"} or {@code "s"}
     */
    private static boolean isSelected(String sel) {
        return "S".equals(sel) || "s".equals(sel);
    }

    /**
     * Returns {@code true} when {@link #trnIdIn()} is blank &mdash; either the
     * empty {@link String} or a string consisting only of whitespace characters as
     * defined by {@link String#isBlank()}.
     *
     * <p>Used by the COBOL {@code PROCESS-ENTER-KEY} paragraph (and by its Java
     * translation in {@code CoTrn00C}) to decide whether the operator typed a
     * specific transaction-id positioning key (in which case the controller calls
     * {@code STARTBR} at that key) or simply pressed ENTER to refresh the listing
     * from its current position.
     *
     * @return {@code true} when the search id is empty or whitespace-only;
     *         {@code false} otherwise
     */
    public boolean isSearchIdBlank() {
        return trnIdIn.isBlank();
    }
}
