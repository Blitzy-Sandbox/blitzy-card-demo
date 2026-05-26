/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports every package exported by
// the java.base module (and the modules it reads). This brings in java.lang.String — the
// type of every BMS output-field component on this record. It also brings in the
// exception classes IllegalArgumentException raised by checkPicLength when a component
// value exceeds its declared BMS PIC X(n) width.
import module java.base;

// Module-import declarations may not import application-defined types; the COBOL
// traceability annotation lives in carddemo-domain and must be brought in by a
// conventional import.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS output record carrying every value sent to the 3270 terminal for the
 * <strong>COTRN00</strong> (Transaction List) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN00.bms} (mapset {@code COTRN00},
 *       map {@code COTRN0A}, size 24x80, FREEKB, ALARM).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN00.CPY} (output group
 *       {@code 01 COTRN0AO REDEFINES COTRN0AI}, lines 374-728, with 59 PIC X
 *       output leaves).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN00C.cbl} (transaction
 *       {@code CT00}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application class. This
 * record is the Java analog of the output view of the BMS symbolic structure
 * {@code COTRN0AO}: it carries the field values written to the terminal by the
 * controller via the equivalent of {@code EXEC CICS SEND MAP} after
 * {@code CoTrn00C.run(...)} returns an outcome of {@code SendMap}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a plain
 * Java carrier built around finalized Java 25 language features only.
 *
 * <h2>Field-for-field translation</h2>
 * <p>The DTO carries <strong>all 59 PIC X output leaves</strong> declared by
 * {@code COTRN0AO} (8 header echoes, 10 row clusters of 5 fields, and the
 * error-message field). This is the raw entry-contract DTO mandated by AAP
 * &sect;0.4.1: every symbolic-map leaf becomes a record component so that the
 * Java code can prove byte-for-byte parity against the COBOL BMS-mapped layout.
 * The {@code *C}, {@code *P}, {@code *H}, {@code *V} suffix fields are BMS
 * attribute bytes (color, programmed symbol, highlight, validation) and are NOT
 * primary leaves; they are not represented on this record and, if needed, will
 * be modeled separately as a parallel attribute carrier.
 *
 * <h2>Transaction-list semantics</h2>
 * <p>COTRN00 is the paginated <em>list / browse</em> screen for transactions. The
 * output side displays:
 * <ol>
 *   <li>A two-line title bar populated from the {@link com.blitzy.carddemo.domain.text.ScreenTitle}
 *       constants (mapped to {@link #title01()} and {@link #title02()}, both
 *       YELLOW).</li>
 *   <li>Static header chrome &mdash; the transaction id, the program name, the
 *       current date in MM/DD/YY format, and the current time in HH:MM:SS format
 *       (mapped to {@link #trnName()}, {@link #pgmName()}, {@link #curDate()},
 *       {@link #curTime()}).</li>
 *   <li>The pagination state &mdash; the current page number, typically formatted
 *       right-justified inside the {@code PAGENUM} field (mapped to
 *       {@link #pageNum()}).</li>
 *   <li>The echoed transaction-id search filter, which on entry is the operator's
 *       typed value and on re-display is preserved across PF7/PF8 paging cycles
 *       (mapped to {@link #trnIdIn()}, GREEN UNDERLINE).</li>
 *   <li>Ten rows of transaction data &mdash; one row per visible page entry &mdash;
 *       each composed of a selection cell, transaction id, formatted date,
 *       description, and signed-decimal amount (mapped to {@link #sel0001()}
 *       through {@link #tAmt010()}).</li>
 *   <li>A single-line error/info message field at row 23, 78 characters wide, BRT
 *       RED on error (mapped to {@link #errMsg()}). The message is empty when no
 *       error has occurred.</li>
 * </ol>
 *
 * <h2>Date display format &mdash; MM/DD/YY (8 characters)</h2>
 * <p>The COTRN00 list displays dates in the short MM/DD/YY format (8 characters),
 * which is <em>different</em> from the YYYY-MM-DD format used by the detail screens
 * COTRN01 (view) and COTRN02 (add). The COBOL {@code POPULATE-TRAN-DATA} paragraph
 * extracts MM/DD/YY by indexing into the source timestamp string
 * {@code TRAN-ORIG-TS PIC X(26)} (format
 * {@code YYYY-MM-DD HH:MM:SS.SSSSSS}) at character offsets 6,7 (month), 9,10 (day),
 * and 3,4 (year). The Java translation in {@code CoTrn00C} performs the same
 * substring extraction; this DTO simply carries the pre-formatted MM/DD/YY string.
 *
 * <h2>Amount display format &mdash; +99999999.99 (12 characters)</h2>
 * <p>The amount column displays a signed amount with leading sign character, eight
 * digits, decimal point, and two fractional digits, totaling 12 characters. The
 * COBOL idiom uses an edited PICTURE clause such as {@code PIC +99999999.99}; the
 * Java translation in {@code CoTrn00C} formats the {@link java.math.BigDecimal}
 * amount via the {@code Decimals} utility (with explicit
 * {@link java.math.RoundingMode}) into the same fixed-width string. This DTO
 * carries only the pre-formatted string &mdash; no {@code BigDecimal}, no
 * {@code double}, no {@code float}, in compliance with AAP &sect;0.7.4.
 *
 * <h2>Null and emptiness semantics</h2>
 * <p>The compact constructor coerces every {@code null} {@link String} component
 * to the empty {@link String} {@code ""}, matching the COBOL SEND-MAP idiom where
 * unfilled BMS {@code PIC X(n)} fields are SPACES, never undefined. This contract
 * matches that of the sibling DTO {@link CoTrn00Input}.
 *
 * <h2>PIC X(n) length validation (CWE-20)</h2>
 * <p>Each {@link String} component is validated against its declared BMS
 * {@code PIC X(n)} on-screen width. Values longer than the declared width are
 * rejected with an {@link IllegalArgumentException} at construction time. This
 * prevents silent hardware truncation in the CICS SEND-MAP layer and satisfies
 * the AAP &sect;0.7.1 Preserve-As-Is contract at the DTO boundary.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final} and
 * accessors are auto-generated; there are no setters and no mutable internal
 * state. The resulting instance is safe to publish across virtual threads (per
 * AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in the
 *       compact constructor (see AAP &sect;0.6.3 / JEP 513).</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} &mdash; date and
 *       time values are carried as preformatted {@link String} fields.</li>
 *   <li>No {@code double} or {@code float} &mdash; the amount field per row is
 *       carried as a preformatted {@link String} (the upstream code converts from
 *       {@link java.math.BigDecimal} via the {@code Decimals} utility).</li>
 *   <li>No preview Java features &mdash; only finalized Java 25 features (records,
 *       JEP 511 module import, JEP 513 flexible constructor bodies).</li>
 * </ul>
 *
 * @see com.blitzy.carddemo.application.transaction.CoTrn00Input
 * @since 1.0.0
 */
@CobolProgram(
        value = "COTRN00",
        sourcePath = "app/bms/COTRN00.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (output side); symbolic copybook COTRN0AO "
                + "REDEFINES COTRN0AI in app/cpy-bms/COTRN00.CPY lines 374-728. "
                + "Field-for-field translation of all 59 PIC X output leaves: 8 "
                + "header echoes, 10 row clusters of 5 fields "
                + "(sel/trnId/tDate/tDesc/tAmt), and the error-message field. "
                + "PIC X(n) widths enforced at construction time. The *C, *P, "
                + "*H, *V suffix fields are BMS attribute bytes and are not "
                + "primary leaves; they are not represented on this DTO."
)
public record CoTrn00Output(

        // ============================================================================
        // Header fields (rows 1-2 of the 24x80 BMS map, plus PAGENUM on row 3).
        // ============================================================================
        String trnName,    // TRNNAMEO  PIC X(4)
        String title01,    // TITLE01O  PIC X(40)
        String curDate,    // CURDATEO  PIC X(8)   — MM/DD/YY
        String pgmName,    // PGMNAMEO  PIC X(8)
        String title02,    // TITLE02O  PIC X(40)
        String curTime,    // CURTIMEO  PIC X(8)   — HH:MM:SS
        String pageNum,    // PAGENUMO  PIC X(8)

        // ============================================================================
        // Transaction-id positioning key — echoed from the input side and
        // re-displayed across PF7/PF8 paging cycles.
        // ============================================================================
        String trnIdIn,    // TRNIDINO  PIC X(16)

        // ============================================================================
        // Row 1 — selection echo + display fields.
        // ============================================================================
        String sel0001,    // SEL0001O  PIC X(1)
        String trnId01,    // TRNID01O  PIC X(16)
        String tDate01,    // TDATE01O  PIC X(8)
        String tDesc01,    // TDESC01O  PIC X(26)
        String tAmt001,    // TAMT001O  PIC X(12)

        // Row 2
        String sel0002,    // SEL0002O  PIC X(1)
        String trnId02,    // TRNID02O  PIC X(16)
        String tDate02,    // TDATE02O  PIC X(8)
        String tDesc02,    // TDESC02O  PIC X(26)
        String tAmt002,    // TAMT002O  PIC X(12)

        // Row 3
        String sel0003,    // SEL0003O  PIC X(1)
        String trnId03,    // TRNID03O  PIC X(16)
        String tDate03,    // TDATE03O  PIC X(8)
        String tDesc03,    // TDESC03O  PIC X(26)
        String tAmt003,    // TAMT003O  PIC X(12)

        // Row 4
        String sel0004,    // SEL0004O  PIC X(1)
        String trnId04,    // TRNID04O  PIC X(16)
        String tDate04,    // TDATE04O  PIC X(8)
        String tDesc04,    // TDESC04O  PIC X(26)
        String tAmt004,    // TAMT004O  PIC X(12)

        // Row 5
        String sel0005,    // SEL0005O  PIC X(1)
        String trnId05,    // TRNID05O  PIC X(16)
        String tDate05,    // TDATE05O  PIC X(8)
        String tDesc05,    // TDESC05O  PIC X(26)
        String tAmt005,    // TAMT005O  PIC X(12)

        // Row 6
        String sel0006,    // SEL0006O  PIC X(1)
        String trnId06,    // TRNID06O  PIC X(16)
        String tDate06,    // TDATE06O  PIC X(8)
        String tDesc06,    // TDESC06O  PIC X(26)
        String tAmt006,    // TAMT006O  PIC X(12)

        // Row 7
        String sel0007,    // SEL0007O  PIC X(1)
        String trnId07,    // TRNID07O  PIC X(16)
        String tDate07,    // TDATE07O  PIC X(8)
        String tDesc07,    // TDESC07O  PIC X(26)
        String tAmt007,    // TAMT007O  PIC X(12)

        // Row 8
        String sel0008,    // SEL0008O  PIC X(1)
        String trnId08,    // TRNID08O  PIC X(16)
        String tDate08,    // TDATE08O  PIC X(8)
        String tDesc08,    // TDESC08O  PIC X(26)
        String tAmt008,    // TAMT008O  PIC X(12)

        // Row 9
        String sel0009,    // SEL0009O  PIC X(1)
        String trnId09,    // TRNID09O  PIC X(16)
        String tDate09,    // TDATE09O  PIC X(8)
        String tDesc09,    // TDESC09O  PIC X(26)
        String tAmt009,    // TAMT009O  PIC X(12)

        // Row 10
        String sel0010,    // SEL0010O  PIC X(1)
        String trnId10,    // TRNID10O  PIC X(16)
        String tDate10,    // TDATE10O  PIC X(8)
        String tDesc10,    // TDESC10O  PIC X(26)
        String tAmt010,    // TAMT010O  PIC X(12)

        // ============================================================================
        // Error-message display (row 23).
        // ============================================================================
        String errMsg      // ERRMSGO   PIC X(78)

) {

    /**
     * The number of selectable rows per COTRN00 page.
     *
     * <p>Mirrors the fixed COBOL constant in {@code COTRN00C} and the static
     * layout of {@code COTRN00.bms}: ten {@code SEL{NN}O / TRNID{NN}O /
     * TDATE{NN}O / TDESC{NN}O / TAMT{NN}O} field clusters in the symbolic
     * copybook {@code COTRN0AO}. The value matches the sibling input DTO's
     * {@link CoTrn00Input#ROWS_PER_PAGE} so that the two sides of the BMS
     * contract remain in lock-step.
     */
    public static final int ROWS_PER_PAGE = 10;

    /**
     * Compact (canonical) constructor.
     *
     * <p>Normalizes every {@link String} component so that a {@code null}
     * reference is converted to the empty {@link String} {@code ""}. This
     * mirrors COBOL SEND-MAP semantics where unfilled BMS {@code PIC X(n)}
     * fields are SPACES, never undefined.
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
     * @throws IllegalArgumentException if any {@link String} component exceeds
     *                                  its declared BMS {@code PIC X(n)} width
     */
    public CoTrn00Output {
        // Header (8)
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

        // PIC X(n) fixed-length validation per app/cpy-bms/COTRN00.CPY lines 374-728.
        checkPicLength("trnName",  trnName,   4);  // TRNNAMEO  PIC X(4)
        checkPicLength("title01",  title01,  40);  // TITLE01O  PIC X(40)
        checkPicLength("curDate",  curDate,   8);  // CURDATEO  PIC X(8)
        checkPicLength("pgmName",  pgmName,   8);  // PGMNAMEO  PIC X(8)
        checkPicLength("title02",  title02,  40);  // TITLE02O  PIC X(40)
        checkPicLength("curTime",  curTime,   8);  // CURTIMEO  PIC X(8)
        checkPicLength("pageNum",  pageNum,   8);  // PAGENUMO  PIC X(8)
        checkPicLength("trnIdIn",  trnIdIn,  16);  // TRNIDINO  PIC X(16)
        checkPicLength("sel0001",  sel0001,   1);  // SEL0001O  PIC X(1)
        checkPicLength("trnId01",  trnId01,  16);  // TRNID01O  PIC X(16)
        checkPicLength("tDate01",  tDate01,   8);  // TDATE01O  PIC X(8)
        checkPicLength("tDesc01",  tDesc01,  26);  // TDESC01O  PIC X(26)
        checkPicLength("tAmt001",  tAmt001,  12);  // TAMT001O  PIC X(12)
        checkPicLength("sel0002",  sel0002,   1);  // SEL0002O  PIC X(1)
        checkPicLength("trnId02",  trnId02,  16);  // TRNID02O  PIC X(16)
        checkPicLength("tDate02",  tDate02,   8);  // TDATE02O  PIC X(8)
        checkPicLength("tDesc02",  tDesc02,  26);  // TDESC02O  PIC X(26)
        checkPicLength("tAmt002",  tAmt002,  12);  // TAMT002O  PIC X(12)
        checkPicLength("sel0003",  sel0003,   1);  // SEL0003O  PIC X(1)
        checkPicLength("trnId03",  trnId03,  16);  // TRNID03O  PIC X(16)
        checkPicLength("tDate03",  tDate03,   8);  // TDATE03O  PIC X(8)
        checkPicLength("tDesc03",  tDesc03,  26);  // TDESC03O  PIC X(26)
        checkPicLength("tAmt003",  tAmt003,  12);  // TAMT003O  PIC X(12)
        checkPicLength("sel0004",  sel0004,   1);  // SEL0004O  PIC X(1)
        checkPicLength("trnId04",  trnId04,  16);  // TRNID04O  PIC X(16)
        checkPicLength("tDate04",  tDate04,   8);  // TDATE04O  PIC X(8)
        checkPicLength("tDesc04",  tDesc04,  26);  // TDESC04O  PIC X(26)
        checkPicLength("tAmt004",  tAmt004,  12);  // TAMT004O  PIC X(12)
        checkPicLength("sel0005",  sel0005,   1);  // SEL0005O  PIC X(1)
        checkPicLength("trnId05",  trnId05,  16);  // TRNID05O  PIC X(16)
        checkPicLength("tDate05",  tDate05,   8);  // TDATE05O  PIC X(8)
        checkPicLength("tDesc05",  tDesc05,  26);  // TDESC05O  PIC X(26)
        checkPicLength("tAmt005",  tAmt005,  12);  // TAMT005O  PIC X(12)
        checkPicLength("sel0006",  sel0006,   1);  // SEL0006O  PIC X(1)
        checkPicLength("trnId06",  trnId06,  16);  // TRNID06O  PIC X(16)
        checkPicLength("tDate06",  tDate06,   8);  // TDATE06O  PIC X(8)
        checkPicLength("tDesc06",  tDesc06,  26);  // TDESC06O  PIC X(26)
        checkPicLength("tAmt006",  tAmt006,  12);  // TAMT006O  PIC X(12)
        checkPicLength("sel0007",  sel0007,   1);  // SEL0007O  PIC X(1)
        checkPicLength("trnId07",  trnId07,  16);  // TRNID07O  PIC X(16)
        checkPicLength("tDate07",  tDate07,   8);  // TDATE07O  PIC X(8)
        checkPicLength("tDesc07",  tDesc07,  26);  // TDESC07O  PIC X(26)
        checkPicLength("tAmt007",  tAmt007,  12);  // TAMT007O  PIC X(12)
        checkPicLength("sel0008",  sel0008,   1);  // SEL0008O  PIC X(1)
        checkPicLength("trnId08",  trnId08,  16);  // TRNID08O  PIC X(16)
        checkPicLength("tDate08",  tDate08,   8);  // TDATE08O  PIC X(8)
        checkPicLength("tDesc08",  tDesc08,  26);  // TDESC08O  PIC X(26)
        checkPicLength("tAmt008",  tAmt008,  12);  // TAMT008O  PIC X(12)
        checkPicLength("sel0009",  sel0009,   1);  // SEL0009O  PIC X(1)
        checkPicLength("trnId09",  trnId09,  16);  // TRNID09O  PIC X(16)
        checkPicLength("tDate09",  tDate09,   8);  // TDATE09O  PIC X(8)
        checkPicLength("tDesc09",  tDesc09,  26);  // TDESC09O  PIC X(26)
        checkPicLength("tAmt009",  tAmt009,  12);  // TAMT009O  PIC X(12)
        checkPicLength("sel0010",  sel0010,   1);  // SEL0010O  PIC X(1)
        checkPicLength("trnId10",  trnId10,  16);  // TRNID10O  PIC X(16)
        checkPicLength("tDate10",  tDate10,   8);  // TDATE10O  PIC X(8)
        checkPicLength("tDesc10",  tDesc10,  26);  // TDESC10O  PIC X(26)
        checkPicLength("tAmt010",  tAmt010,  12);  // TAMT010O  PIC X(12)
        checkPicLength("errMsg",   errMsg,   78);  // ERRMSGO   PIC X(78)
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
     * <p>Enforces the AAP &sect;0.7.1 Preserve-As-Is contract at the DTO
     * boundary (CWE-20 input validation): values longer than the declared BMS
     * width would cause silent hardware truncation in the CICS SEND-MAP layer.
     * Shorter values are accepted unchanged.
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
     * Returns an "empty" output record suitable for the initial COTRN00
     * send-map cycle: every text component is the empty {@link String}.
     *
     * <p>This matches the legacy COBOL idiom for the first program invocation:
     * the controller issues {@code MOVE LOW-VALUES TO COTRN0AO} before
     * populating any header or row fields, then issues {@code EXEC CICS SEND
     * MAP} to paint the empty list screen.
     *
     * @return a fully-blank {@code CoTrn00Output} (never {@code null}) with
     *         all 59 components set to the empty {@link String}
     */
    public static CoTrn00Output empty() {
        return new CoTrn00Output(
                // Header (8)
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
                "");
    }

    /**
     * Returns a copy of this {@code CoTrn00Output} with a new {@link #errMsg()},
     * preserving all other fields unchanged.
     *
     * <p>This helper supports the canonical COBOL "decorate output with error
     * message" idiom &mdash; in the COBOL program, the controller computes the
     * row data and header values into the output map area, then conditionally
     * moves an error message string to {@code ERRMSGO} just before
     * {@code EXEC CICS SEND MAP}. In the Java translation, the controller
     * builds the output record without an error message via the various
     * row/header setters and then, if an error condition occurs, calls
     * {@code output.withErrMsg(msg)} to derive a decorated copy. Because
     * records in finalized Java 25 do not have a built-in {@code with} syntax
     * (per AAP &sect;0.1.2), this method is the hand-written equivalent.
     *
     * <p>The BMS {@code ERRMSGC} color attribute byte (set to RED on error
     * conditions per the {@code ERRMSG ATTRB=(ASKIP,BRT,FSET) COLOR=RED}
     * declaration) is not represented on this DTO; it is a {@code *C} suffix
     * attribute byte rather than a primary BMS leaf, and any future
     * attribute-byte modeling will live on a separate attribute carrier.
     *
     * @param newMsg the new error / informational message text. The value is
     *               coerced to the empty {@link String} {@code ""} if
     *               {@code null} via the compact constructor's normalization.
     *               Must not exceed 78 characters (the BMS {@code PIC X(78)}
     *               width of {@code ERRMSGO}); longer values raise
     *               {@link IllegalArgumentException}.
     * @return a new {@code CoTrn00Output} identical to this one except with
     *         the supplied {@code newMsg}
     * @throws IllegalArgumentException if {@code newMsg.length() > 78}
     */
    public CoTrn00Output withErrMsg(String newMsg) {
        return new CoTrn00Output(
                trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                trnIdIn,
                sel0001, trnId01, tDate01, tDesc01, tAmt001,
                sel0002, trnId02, tDate02, tDesc02, tAmt002,
                sel0003, trnId03, tDate03, tDesc03, tAmt003,
                sel0004, trnId04, tDate04, tDesc04, tAmt004,
                sel0005, trnId05, tDate05, tDesc05, tAmt005,
                sel0006, trnId06, tDate06, tDesc06, tAmt006,
                sel0007, trnId07, tDate07, tDesc07, tAmt007,
                sel0008, trnId08, tDate08, tDesc08, tAmt008,
                sel0009, trnId09, tDate09, tDesc09, tAmt009,
                sel0010, trnId10, tDate10, tDesc10, tAmt010,
                newMsg);
    }
}
