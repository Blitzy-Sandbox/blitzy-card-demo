/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by
// the java.base module (and by the modules it reads). This brings in:
//   * java.lang.String                   -- the type of twenty-one record components
//                                           carrying BMS display field values (title1,
//                                           title2, transactionName, programName,
//                                           currentDate, currentTime, accountId,
//                                           cardNumber, typeCode, categoryCode, source,
//                                           description, amount, origDate, procDate,
//                                           merchantId, merchantName, merchantCity,
//                                           merchantZip, confirmation, errMsg);
//   * java.util.Objects                  -- the source of Objects.requireNonNull(...)
//                                           invoked twenty-two times inside the compact
//                                           constructor per AAP §0.6.3 / JEP 513 to
//                                           enforce non-null contracts before the
//                                           canonical field assignment runs;
//   * java.lang.NullPointerException     -- raised (via Objects.requireNonNull) on any
//                                           null component, with the component name as
//                                           the exception detail message for diagnosis
//                                           per the COBOL VALIDATE-INPUT idiom;
//   * java.lang.Enum facilities          -- the supertype of the nested FieldColor
//                                           enumeration and the source of its auto-
//                                           generated values()/valueOf() utilities.
//
// The file schema's external_imports list specifies exactly one entry -- the java.base
// module -- and the internal_imports list is empty; no other import is permitted or
// required on this file. The single "import module java.base;" line replaces the
// verbose pair of "import java.lang.String;" (implicit in every compilation unit) and
// "import java.util.Objects;", and aligns the file with the AAP §0.6.7 / §0.7.3 mandate
// to use Module Import Declarations finalized in Java 25.
import module java.base;

// Module-import declarations may not import application-defined types; the COBOL
// traceability annotation lives in carddemo-domain and must be brought in by a
// conventional import.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS output record carrying every value sent to the 3270 terminal for the
 * <strong>COTRN02</strong> (Add Transaction) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN02.bms} (mapset {@code COTRN02},
 *       map {@code COTRN2A}, size 24x80, {@code CTRL=(ALARM,FREEKB)},
 *       {@code EXTATT=YES}, {@code LANG=COBOL}, {@code MODE=INOUT},
 *       {@code STORAGE=AUTO}, {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN02.CPY} (output group
 *       {@code 01 COTRN2AO REDEFINES COTRN2AI} containing the six header echoes
 *       {@code TRNNAMEO}, {@code TITLE01O}, {@code CURDATEO}, {@code PGMNAMEO},
 *       {@code TITLE02O}, {@code CURTIMEO}; the operator-input echoes
 *       {@code ACTIDINO} and {@code CARDNINO}; the twelve transaction-detail
 *       leaves {@code TTYPCDO}, {@code TCATCDO}, {@code TRNSRCO},
 *       {@code TDESCO}, {@code TRNAMTO}, {@code TORIGDTO}, {@code TPROCDTO},
 *       {@code MIDO}, {@code MNAMEO}, {@code MCITYO}, {@code MZIPO}, and
 *       {@code CONFIRMO}; and the {@code ERRMSGO} status line plus its color
 *       attribute byte {@code ERRMSGC}).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN02C.cbl} (transaction
 *       {@code CT02}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application class. This
 * record is the Java analog of the output view of the BMS symbolic structure
 * {@code COTRN2AO}: it carries the field values written to the terminal by the
 * controller via the equivalent of {@code EXEC CICS SEND MAP} after
 * {@code CoTrn02C.run(...)} returns an outcome of {@code SendMap}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a plain
 * Java carrier built around finalized Java 25 language features only (records,
 * sealed types, pattern matching, JEP 511 module imports, JEP 513 flexible
 * constructor bodies).
 *
 * <h2>Add-Transaction semantics</h2>
 * <p>COTRN02 is the <em>add / data entry</em> screen for a single new transaction.
 * Unlike COTRN01 (View Transaction), which is mostly {@code ATTRB=(ASKIP,...)}
 * (read-only), COTRN02 has fourteen operator-editable input fields
 * ({@code ATTRB=(FSET,NORM,UNPROT)} GREEN UNDERLINE). On a fresh entry, the
 * controller initialises the screen via {@link #empty()} (the equivalent of
 * {@code MOVE LOW-VALUES TO COTRN2AO}), populates the six header echoes, and
 * issues the equivalent of {@code EXEC CICS SEND MAP}. After
 * {@code EXEC CICS RECEIVE MAP} the controller validates the operator input;
 * on validation failure it copies the offending values back to the
 * corresponding output fields and decorates the record via
 * {@link #withErrMsg(String, FieldColor)} with a RED error message; on
 * successful {@code WRITE} to the {@code TRANSACT} VSAM file, the controller
 * decorates the record with a GREEN success message that includes the assigned
 * transaction id.
 *
 * <h2>Dynamic ERRMSGC color &mdash; two values in COTRN02 rather than one</h2>
 * <p>Unlike the COTRN00/COTRN01 output DTOs which only ever flip
 * {@link FieldColor#RED} vs {@link FieldColor#DEFAULT}, the COTRN02 controller
 * legitimately flips {@code ERRMSGC} between THREE values:
 * <ul>
 *   <li>{@link FieldColor#RED} when an input-validation error or VSAM
 *       {@code DUPREC}/{@code NOTOPEN} condition is detected,</li>
 *   <li>{@link FieldColor#GREEN} when the {@code WRITE TRANSACT-FILE} succeeds
 *       (per the COBOL idiom {@code MOVE GREEN TO ERRMSGC} inside the
 *       {@code WRITE-TRANSACT-FILE NORMAL} branch),</li>
 *   <li>{@link FieldColor#DEFAULT} for the initial blank-screen send-map cycle
 *       when no message is being emitted.</li>
 * </ul>
 * The {@link #errMsgColor()} component carries the active runtime value; the
 * other per-field {@code "C"}-suffixed attribute bytes in the symbolic
 * copybook ({@code TRNNAMEC}, {@code TITLE01C}, {@code CURDATEC},
 * {@code PGMNAMEC}, {@code TITLE02C}, {@code CURTIMEC}, {@code ACTIDINC},
 * {@code CARDNINC}, ..., {@code CONFIRMC}) are never modified at runtime by
 * the COBOL controller &mdash; they keep their BMS-compile-time {@code COLOR=}
 * values &mdash; so they are not materialised as record components in the Java
 * translation, in keeping with the AAP &sect;0.7.1 minimal-change principle.
 *
 * <h2>Component inventory (22 components, one per output BMS leaf plus the
 * dynamic ERRMSGC color byte)</h2>
 * <p>The output side of the COTRN02 symbolic copybook declares twenty-one
 * {@code "O"}-suffixed leaves &mdash; one per {@code DFHMDF} field on the map.
 * The twenty-second record component, {@link #errMsgColor()}, models the
 * dynamic color-attribute byte {@code ERRMSGC}.
 *
 * <ol>
 *   <li>A two-line title bar populated from the
 *       {@link com.blitzy.carddemo.domain.text.ScreenTitle} constants (mapped to
 *       {@link #title1()} and {@link #title2()}, both YELLOW at compile time).</li>
 *   <li>Static header chrome &mdash; the transaction code ({@link #transactionName()},
 *       e.g. {@code "CT02"}), the program name ({@link #programName()},
 *       e.g. {@code "COTRN02C"}), the current date in MM/DD/YY format
 *       ({@link #currentDate()}), and the current time in HH:MM:SS format
 *       ({@link #currentTime()}).</li>
 *   <li>Two operator-echo input echoes &mdash; the account id
 *       ({@link #accountId()}, 11 chars at row 6, col 21) and the card number
 *       ({@link #cardNumber()}, 16 chars at row 6, col 55). The operator may
 *       enter either one (or both) on a fresh entry; the controller's lookup
 *       logic prefers the account id when both are present.</li>
 *   <li>Twelve transaction-data display/echo fields populated from operator
 *       input on prior cycles or from the controller's "copy last transaction"
 *       (PF5) function: type code ({@link #typeCode()}), category code
 *       ({@link #categoryCode()}), source ({@link #source()}), description
 *       ({@link #description()}), amount ({@link #amount()}, signed decimal
 *       formatted as {@code -99999999.99}), origination date
 *       ({@link #origDate()}, YYYY-MM-DD), processing date ({@link #procDate()},
 *       YYYY-MM-DD), merchant id ({@link #merchantId()}), merchant name
 *       ({@link #merchantName()}), merchant city ({@link #merchantCity()}),
 *       merchant ZIP ({@link #merchantZip()}), and the Y/N confirmation
 *       indicator ({@link #confirmation()}, 1 char at row 21, col 63).</li>
 *   <li>A single-line error/info message at row 23, 78 characters wide,
 *       declared BRT RED at BMS-compile time. The message is empty (the empty
 *       {@link String}) when no error or success indicator has been emitted;
 *       the {@link #errMsgColor()} attribute byte flips between
 *       {@link FieldColor#RED}, {@link FieldColor#GREEN}, and
 *       {@link FieldColor#DEFAULT} as described above.</li>
 * </ol>
 *
 * <h2>BMS field-by-field mapping (per agent prompt Phase 2 syntactic checklist)</h2>
 * <table border="1">
 * <caption>BMS-to-record component mapping</caption>
 *   <thead>
 *     <tr><th>BMS field</th><th>BMS attrs / position</th><th>Record component</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code TITLE01}</td><td>YELLOW, 40 chars, (1,21)</td>
 *         <td>{@link #title1()}</td></tr>
 *     <tr><td>{@code TITLE02}</td><td>YELLOW, 40 chars, (2,21)</td>
 *         <td>{@link #title2()}</td></tr>
 *     <tr><td>{@code TRNNAME}</td><td>BLUE FSET, 4 chars, (1,7)</td>
 *         <td>{@link #transactionName()}</td></tr>
 *     <tr><td>{@code PGMNAME}</td><td>BLUE FSET, 8 chars, (2,7)</td>
 *         <td>{@link #programName()}</td></tr>
 *     <tr><td>{@code CURDATE}</td><td>BLUE FSET, 8 chars (mm/dd/yy), (1,71)</td>
 *         <td>{@link #currentDate()}</td></tr>
 *     <tr><td>{@code CURTIME}</td><td>BLUE FSET, 8 chars (hh:mm:ss), (2,71)</td>
 *         <td>{@link #currentTime()}</td></tr>
 *     <tr><td>{@code ACTIDIN}</td><td>UNPROT FSET IC GREEN UNDERLINE, 11 chars, (6,21)</td>
 *         <td>{@link #accountId()}</td></tr>
 *     <tr><td>{@code CARDNIN}</td><td>UNPROT FSET GREEN UNDERLINE, 16 chars, (6,55)</td>
 *         <td>{@link #cardNumber()}</td></tr>
 *     <tr><td>{@code TTYPCD}</td><td>UNPROT FSET GREEN UNDERLINE, 2 chars, (10,15)</td>
 *         <td>{@link #typeCode()}</td></tr>
 *     <tr><td>{@code TCATCD}</td><td>UNPROT FSET GREEN UNDERLINE, 4 chars, (10,36)</td>
 *         <td>{@link #categoryCode()}</td></tr>
 *     <tr><td>{@code TRNSRC}</td><td>UNPROT FSET GREEN UNDERLINE, 10 chars, (10,54)</td>
 *         <td>{@link #source()}</td></tr>
 *     <tr><td>{@code TDESC}</td><td>UNPROT FSET GREEN UNDERLINE, 60 chars, (12,19)</td>
 *         <td>{@link #description()}</td></tr>
 *     <tr><td>{@code TRNAMT}</td><td>UNPROT FSET GREEN UNDERLINE, 12 chars (-99999999.99), (14,14)</td>
 *         <td>{@link #amount()}</td></tr>
 *     <tr><td>{@code TORIGDT}</td><td>UNPROT FSET GREEN UNDERLINE, 10 chars (YYYY-MM-DD), (14,42)</td>
 *         <td>{@link #origDate()}</td></tr>
 *     <tr><td>{@code TPROCDT}</td><td>UNPROT FSET GREEN UNDERLINE, 10 chars (YYYY-MM-DD), (14,68)</td>
 *         <td>{@link #procDate()}</td></tr>
 *     <tr><td>{@code MID}</td><td>UNPROT FSET GREEN UNDERLINE, 9 chars, (16,19)</td>
 *         <td>{@link #merchantId()}</td></tr>
 *     <tr><td>{@code MNAME}</td><td>UNPROT FSET GREEN UNDERLINE, 30 chars, (16,48)</td>
 *         <td>{@link #merchantName()}</td></tr>
 *     <tr><td>{@code MCITY}</td><td>UNPROT FSET GREEN UNDERLINE, 25 chars, (18,21)</td>
 *         <td>{@link #merchantCity()}</td></tr>
 *     <tr><td>{@code MZIP}</td><td>UNPROT FSET GREEN UNDERLINE, 10 chars, (18,67)</td>
 *         <td>{@link #merchantZip()}</td></tr>
 *     <tr><td>{@code CONFIRM}</td><td>UNPROT FSET GREEN UNDERLINE, 1 char, (21,63)</td>
 *         <td>{@link #confirmation()}</td></tr>
 *     <tr><td>{@code ERRMSG}</td><td>RED BRT FSET ASKIP, 78 chars, (23,1)</td>
 *         <td>{@link #errMsg()}</td></tr>
 *     <tr><td>{@code ERRMSGC}</td><td>color attribute byte (dynamic)</td>
 *         <td>{@link #errMsgColor()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Why three hint texts are <em>not</em> components</h2>
 * <p>The BMS map declares three literal hint texts that are <em>not</em> part of
 * the symbolic copybook and are therefore <em>not</em> record components:
 * <ul>
 *   <li>Row 15, col 13: {@code '(-99999999.99)'} (format hint for {@code TRNAMT})</li>
 *   <li>Row 15, col 41: {@code '(YYYY-MM-DD)'}   (format hint for {@code TORIGDT})</li>
 *   <li>Row 15, col 67: {@code '(YYYY-MM-DD)'}   (format hint for {@code TPROCDT})</li>
 * </ul>
 * These appear in the BMS source as static {@code INITIAL=} values on
 * {@code DFHMDF} entries with no field label, so they have no symbolic-map
 * counterpart and no runtime mutability. They render unchanged on every
 * {@code SEND MAP} and are therefore omitted from this DTO.
 *
 * <h2>Null vs empty discipline</h2>
 * <p>This record is <em>strict</em> about nulls: the compact constructor rejects
 * any {@code null} component with a {@link NullPointerException} via
 * {@link java.util.Objects#requireNonNull(Object, String)}. An empty
 * {@link String} ({@code ""}) is <em>permitted</em> for every text component and
 * represents the BMS-level "no value to render" state &mdash; the legacy COBOL
 * idiom is {@code MOVE SPACES TO field-O} or {@code MOVE LOW-VALUES TO group},
 * both of which the Java translation normalises to the empty string. The
 * {@link FieldColor} component must also be non-null; the dedicated sentinel
 * {@link FieldColor#DEFAULT} means "no color override &mdash; use the BMS
 * compile-time {@code COLOR=} value".
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final} and
 * accessors are auto-generated; there are no setters and no mutable internal
 * state. The record is therefore safe to share across threads without
 * synchronisation. Combined with the virtual-thread fan-out mandated by AAP
 * &sect;0.6.6 for I/O-bound parallelism and the {@link java.lang.ScopedValue}
 * propagation of batch-run context mandated by JEP 506 in the same section,
 * sharing this DTO across worker threads carries no risk.
 *
 * <h2>Cross-screen consistency with sibling output DTOs</h2>
 * <p>The {@link FieldColor} enum below is intentionally identical (same nine
 * values in the same order) to the {@code FieldColor} enums on the sibling
 * {@code CoTrn00Output} (Transaction List) and {@code CoTrn01Output}
 * (View Transaction) records. This uniformity lets the future CICS-SEND-MAP
 * adapter use a single {@code FieldColor}-to-BMS-attribute-byte mapping
 * function across the entire transaction sub-package, and lets the controllers
 * share a common error-decoration idiom (the {@link #withErrMsg(String,
 * FieldColor)} helper below has the same shape on every transaction output
 * DTO).
 *
 * <h2>Non-goals (faithful to AAP &sect;0.7.4 "explicitly forbidden")</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in the
 *       compact constructor (see AAP &sect;0.6.3 / JEP 513).</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} &mdash; the date
 *       components ({@link #origDate()}, {@link #procDate()}, {@link #currentDate()},
 *       {@link #currentTime()}) are pre-formatted display {@link String}s; any
 *       parsing or formatting is performed elsewhere via {@code java.time}.</li>
 *   <li>No {@code double} or {@code float} &mdash; the monetary {@link #amount()}
 *       component is a pre-formatted display {@link String}; the underlying
 *       arithmetic uses {@link java.math.BigDecimal} via the
 *       {@code com.blitzy.carddemo.domain.util.Decimals} facade per AAP
 *       &sect;0.6.1.</li>
 *   <li>No preview Java features &mdash; only finalized Java 25 features are
 *       used (records, JEP 511 module import, JEP 513 flexible constructor
 *       bodies).</li>
 * </ul>
 *
 * @param title1            {@code TITLE01} (line-1 right title bar, 40 chars,
 *                          YELLOW). Must not be {@code null}; empty string is
 *                          accepted and represents the legacy COBOL
 *                          {@code MOVE LOW-VALUES} initial state.
 * @param title2            {@code TITLE02} (line-2 right title bar, 40 chars,
 *                          YELLOW). Must not be {@code null}; empty string is
 *                          accepted.
 * @param transactionName   {@code TRNNAME} (line-1 left transaction code header,
 *                          4 chars, BLUE FSET). Must not be {@code null}; empty
 *                          string is accepted.
 * @param programName       {@code PGMNAME} (line-2 left program-id header, 8
 *                          chars, BLUE FSET). Must not be {@code null}; empty
 *                          string is accepted.
 * @param currentDate       {@code CURDATE} (line-1 right date in MM/DD/YY, 8
 *                          chars, BLUE FSET). Must not be {@code null}; empty
 *                          string is accepted.
 * @param currentTime       {@code CURTIME} (line-2 right time in HH:MM:SS, 8
 *                          chars, BLUE FSET). Must not be {@code null}; empty
 *                          string is accepted.
 * @param accountId         {@code ACTIDIN} echo (the operator-typed account id,
 *                          11 chars, GREEN UNDERLINE UNPROT FSET IC). Must not
 *                          be {@code null}; empty string means "no value typed
 *                          yet".
 * @param cardNumber        {@code CARDNIN} echo (the operator-typed card
 *                          number, 16 chars, GREEN UNDERLINE UNPROT FSET).
 *                          Must not be {@code null}; empty string means "no
 *                          value typed yet". Per AAP &sect;0.7.2 / PCI, full
 *                          PAN is displayed on the BMS screen exactly as in
 *                          the legacy COBOL program, but the application class
 *                          is responsible for masking the PAN in any log
 *                          output (mask all but the last four digits).
 * @param typeCode          {@code TTYPCD} display field (2 chars, GREEN
 *                          UNDERLINE UNPROT FSET). Must not be {@code null};
 *                          empty string is accepted.
 * @param categoryCode      {@code TCATCD} display field (4 chars, GREEN
 *                          UNDERLINE UNPROT FSET). Must not be {@code null};
 *                          empty string is accepted.
 * @param source            {@code TRNSRC} display field (10 chars, GREEN
 *                          UNDERLINE UNPROT FSET). Must not be {@code null};
 *                          empty string is accepted.
 * @param description       {@code TDESC} display field (60 chars, GREEN
 *                          UNDERLINE UNPROT FSET). Must not be {@code null};
 *                          empty string is accepted.
 * @param amount            {@code TRNAMT} display field (12 chars, GREEN
 *                          UNDERLINE UNPROT FSET, format {@code -99999999.99}).
 *                          The Java-side controller formats a
 *                          {@link java.math.BigDecimal} value into this string
 *                          via the {@code Decimals} utility (AAP &sect;0.6.1)
 *                          before constructing this record. Must not be
 *                          {@code null}; empty string is accepted.
 * @param origDate          {@code TORIGDT} display field (10 chars, GREEN
 *                          UNDERLINE UNPROT FSET, format YYYY-MM-DD). Must not
 *                          be {@code null}; empty string is accepted.
 * @param procDate          {@code TPROCDT} display field (10 chars, GREEN
 *                          UNDERLINE UNPROT FSET, format YYYY-MM-DD). Must not
 *                          be {@code null}; empty string is accepted.
 * @param merchantId        {@code MID} display field (9 chars, GREEN UNDERLINE
 *                          UNPROT FSET). Must not be {@code null}; empty
 *                          string is accepted.
 * @param merchantName      {@code MNAME} display field (30 chars, GREEN
 *                          UNDERLINE UNPROT FSET). Must not be {@code null};
 *                          empty string is accepted.
 * @param merchantCity      {@code MCITY} display field (25 chars, GREEN
 *                          UNDERLINE UNPROT FSET). Must not be {@code null};
 *                          empty string is accepted.
 * @param merchantZip       {@code MZIP} display field (10 chars, GREEN
 *                          UNDERLINE UNPROT FSET). Must not be {@code null};
 *                          empty string is accepted.
 * @param confirmation      {@code CONFIRM} display field (1 char, GREEN
 *                          UNDERLINE UNPROT FSET, Y/N/blank). Echoes the
 *                          operator's typed confirmation indicator on
 *                          subsequent send-map cycles. Must not be
 *                          {@code null}; empty string is accepted and
 *                          represents the initial unconfirmed state.
 * @param errMsg            {@code ERRMSG} status line (78 chars, RED BRT FSET
 *                          ASKIP at row 23, column 1). Must not be {@code null};
 *                          empty string means "no error or success indicator
 *                          is being emitted".
 * @param errMsgColor       {@code ERRMSGC} attribute byte. Set to
 *                          {@link FieldColor#RED} when the controller emits a
 *                          validation or IO error, {@link FieldColor#GREEN}
 *                          when the controller emits a success indicator
 *                          (after a successful {@code WRITE TRANSACT-FILE}),
 *                          and {@link FieldColor#DEFAULT} when no message is
 *                          being emitted.
 *
 * @see CoTrn02Input
 * @see com.blitzy.carddemo.application.transaction.CoTrn02Output.FieldColor
 * @since 1.0.0
 */
@CobolProgram(
        value = "COTRN02",
        sourcePath = "app/bms/COTRN02.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (output side); symbolic copybook "
                + "COTRN2AO REDEFINES COTRN2AI in app/cpy-bms/COTRN02.CPY "
                + "lines 152-272. Field-for-field translation of all 21 PIC "
                + "X output leaves: 6 header echoes (TRNNAMEO/TITLE01O/"
                + "CURDATEO/PGMNAMEO/TITLE02O/CURTIMEO), ACTIDINO, CARDNINO, "
                + "13 detail (TTYPCDO/TCATCDO/TRNSRCO/TDESCO/TRNAMTO/"
                + "TORIGDTO/TPROCDTO/MIDO/MNAMEO/MCITYO/MZIPO), CONFIRMO, "
                + "and ERRMSGO. Semantic component names retained from "
                + "initial creation; each component is documented with its "
                + "BMS source field. PIC X(n) widths enforced at "
                + "construction time."
)
public record CoTrn02Output(
        String title1,
        String title2,
        String transactionName,
        String programName,
        String currentDate,
        String currentTime,
        String accountId,
        String cardNumber,
        String typeCode,
        String categoryCode,
        String source,
        String description,
        String amount,
        String origDate,
        String procDate,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String confirmation,
        String errMsg,
        FieldColor errMsgColor) {

    /**
     * BMS field-color attribute, used by {@link #errMsgColor()} to model the
     * dynamic {@code ERRMSGC} attribute byte and (by convention shared with the
     * sibling DTOs {@code CoTrn00Output} and {@code CoTrn01Output}) any other
     * runtime color override the controller may wish to apply.
     *
     * <p>The nine values map one-for-one to the eight BMS-supported colors
     * (NEUTRAL, BLUE, GREEN, YELLOW, RED, TURQUOISE, PINK, WHITE) plus a single
     * sentinel value {@link #DEFAULT} which means "no override &mdash; honour
     * the BMS-compile-time {@code COLOR=} setting on the field". The
     * BMS-compile-time setting for {@code ERRMSG} is {@code COLOR=RED} (see
     * {@code app/bms/COTRN02.bms}); the COTRN02 controller flips
     * {@link #errMsgColor()} between {@link #RED} (validation/IO error),
     * {@link #GREEN} (successful {@code WRITE TRANSACT-FILE}), and
     * {@link #DEFAULT} (no message). The other six values exist for cross-DTO
     * consistency &mdash; the enum is intentionally identical to the enums on
     * the sibling output DTOs so that a single colour-to-attribute-byte
     * mapping function can be reused across the entire transaction
     * sub-package.
     *
     * <p>Why an enum rather than a sealed interface? AAP &sect;0.6.10 reserves
     * sealed interfaces for COBOL constructs that partition a value space
     * (REDEFINES and 88-level data taxonomies). The BMS color attribute is a
     * closed set of opaque labels with no payload, which is exactly the case
     * for which a plain {@code enum} is idiomatic.
     */
    public enum FieldColor {

        /**
         * No color override &mdash; the BMS map's compile-time {@code COLOR=}
         * setting is used. The default value for fields that do not need
         * dynamic coloring; also the {@link FieldColor} carried in
         * {@link #errMsgColor()} when {@link #errMsg()} is empty.
         */
        DEFAULT,

        /** CICS-default white/cream tone (BMS {@code COLOR=NEUTRAL}). */
        NEUTRAL,

        /** Blue (BMS {@code COLOR=BLUE}). */
        BLUE,

        /**
         * Green (BMS {@code COLOR=GREEN}) &mdash; used for the
         * {@link CoTrn02Output#errMsgColor() error-message field} on
         * <em>success</em> conditions per the COBOL idiom
         * {@code MOVE GREEN TO ERRMSGC} inside the
         * {@code WRITE-TRANSACT-FILE NORMAL} branch of
         * {@code app/cbl/COTRN02C.cbl}.
         */
        GREEN,

        /** Yellow (BMS {@code COLOR=YELLOW}). */
        YELLOW,

        /**
         * Red (BMS {@code COLOR=RED}) &mdash; used for the
         * {@link CoTrn02Output#errMsgColor() error-message field} on error
         * conditions per the BMS map's
         * {@code ERRMSG ATTRB=(ASKIP,BRT,FSET) COLOR=RED} declaration. This
         * is the BMS-compile-time default and is the value emitted on every
         * validation failure or VSAM I/O error in {@code COTRN02C}.
         */
        RED,

        /** Turquoise (BMS {@code COLOR=TURQUOISE}). */
        TURQUOISE,

        /** Pink (BMS {@code COLOR=PINK}). */
        PINK,

        /** White (BMS {@code COLOR=WHITE}). */
        WHITE
    }

    /**
     * Compact (canonical) constructor.
     *
     * <p>Enforces a single invariant on every constructed instance:
     * <strong>non-null components</strong>. Each of the twenty-two components
     * must be non-null. A {@code null} argument is rejected with a
     * {@link NullPointerException} carrying the offending component name. The
     * COBOL idiom (SEND-MAP fields are always SPACES, never undefined)
     * translates to the Java discipline of carrying the empty {@link String}
     * for "no value" rather than {@code null}. The {@link FieldColor} sentinel
     * {@link FieldColor#DEFAULT} plays the same role for the attribute-byte
     * component.
     *
     * <p>Unlike the sibling {@code CoTrn00Output} record &mdash; which also
     * enforces a fixed list length for its {@code rows} component &mdash; this
     * record has no collection components and therefore no length invariant.
     * Every component is a {@link String} or the {@link FieldColor} enum, and
     * all of them are independent.
     *
     * <p>This constructor takes advantage of <strong>JEP 513 Flexible
     * Constructor Bodies</strong> (finalized in Java 25): each validation
     * statement runs before the implicit canonical field-assignment, which is
     * exactly the place to capture COBOL-style "validate before bind"
     * semantics described in AAP &sect;0.6.3.
     *
     * @throws NullPointerException if any component is {@code null}, with the
     *                              component name as the exception detail
     *                              message
     */
    public CoTrn02Output {
        Objects.requireNonNull(title1, "title1");
        Objects.requireNonNull(title2, "title2");
        Objects.requireNonNull(transactionName, "transactionName");
        Objects.requireNonNull(programName, "programName");
        Objects.requireNonNull(currentDate, "currentDate");
        Objects.requireNonNull(currentTime, "currentTime");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(cardNumber, "cardNumber");
        Objects.requireNonNull(typeCode, "typeCode");
        Objects.requireNonNull(categoryCode, "categoryCode");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(origDate, "origDate");
        Objects.requireNonNull(procDate, "procDate");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(merchantName, "merchantName");
        Objects.requireNonNull(merchantCity, "merchantCity");
        Objects.requireNonNull(merchantZip, "merchantZip");
        Objects.requireNonNull(confirmation, "confirmation");
        Objects.requireNonNull(errMsg, "errMsg");
        Objects.requireNonNull(errMsgColor, "errMsgColor");

        // PIC X(n) fixed-length validation per app/cpy-bms/COTRN02.CPY lines 152-272
        // (output side). Values longer than the declared BMS width would cause
        // silent hardware truncation in the CICS SEND-MAP layer (CWE-20).
        checkPicLength("transactionName", transactionName,  4);  // TRNNAMEO PIC X(4)
        checkPicLength("title1",          title1,          40);  // TITLE01O PIC X(40)
        checkPicLength("currentDate",     currentDate,      8);  // CURDATEO PIC X(8)
        checkPicLength("programName",     programName,      8);  // PGMNAMEO PIC X(8)
        checkPicLength("title2",          title2,          40);  // TITLE02O PIC X(40)
        checkPicLength("currentTime",     currentTime,      8);  // CURTIMEO PIC X(8)
        checkPicLength("accountId",       accountId,       11);  // ACTIDINO PIC X(11)
        checkPicLength("cardNumber",      cardNumber,      16);  // CARDNINO PIC X(16)
        checkPicLength("typeCode",        typeCode,         2);  // TTYPCDO  PIC X(2)
        checkPicLength("categoryCode",    categoryCode,     4);  // TCATCDO  PIC X(4)
        checkPicLength("source",          source,          10);  // TRNSRCO  PIC X(10)
        checkPicLength("description",     description,     60);  // TDESCO   PIC X(60)
        checkPicLength("amount",          amount,          12);  // TRNAMTO  PIC X(12)
        checkPicLength("origDate",        origDate,        10);  // TORIGDTO PIC X(10)
        checkPicLength("procDate",        procDate,        10);  // TPROCDTO PIC X(10)
        checkPicLength("merchantId",      merchantId,       9);  // MIDO     PIC X(9)
        checkPicLength("merchantName",    merchantName,    30);  // MNAMEO   PIC X(30)
        checkPicLength("merchantCity",    merchantCity,    25);  // MCITYO   PIC X(25)
        checkPicLength("merchantZip",     merchantZip,     10);  // MZIPO    PIC X(10)
        checkPicLength("confirmation",    confirmation,     1);  // CONFIRMO PIC X(1)
        checkPicLength("errMsg",          errMsg,          78);  // ERRMSGO  PIC X(78)
    }

    /**
     * Validates that a {@link String} component does not exceed its declared
     * BMS {@code PIC X(n)} on-screen width.
     *
     * <p>Enforces the AAP &sect;0.7.1 Preserve-As-Is contract at the DTO
     * boundary (CWE-20 input validation): values longer than the declared
     * BMS width would cause silent hardware truncation in the CICS
     * SEND-MAP layer. Shorter values are accepted unchanged.
     *
     * @param name      the component name (used in the exception message)
     * @param value     the component value (never {@code null}: the caller
     *                  guarantees non-null via {@code Objects.requireNonNull})
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
     * Returns an "empty" output record suitable for the initial COTRN02
     * send-map cycle: every text component is the empty {@link String} and the
     * error-message color is {@link FieldColor#DEFAULT}.
     *
     * <p>This matches the legacy COBOL idiom for the first program invocation:
     * the controller issues {@code MOVE LOW-VALUES TO COTRN2AO} before
     * populating any header or detail fields, then issues
     * {@code EXEC CICS SEND MAP} to paint the empty Add Transaction screen
     * (operator can then type an account id or card number plus the remaining
     * transaction fields, then press ENTER and Y to confirm the write).
     *
     * <p>The returned record satisfies every non-null invariant of the compact
     * constructor (empty {@link String} is non-null) and carries no payload
     * &mdash; consumers may safely treat it as the canonical "starting state"
     * of an output cycle.
     *
     * @return a fully-blank {@code CoTrn02Output} (never {@code null}) with all
     *         twenty-one text components set to the empty {@link String} and
     *         {@link #errMsgColor()} set to {@link FieldColor#DEFAULT}
     */
    public static CoTrn02Output empty() {
        return new CoTrn02Output(
                "",                // title1
                "",                // title2
                "",                // transactionName
                "",                // programName
                "",                // currentDate
                "",                // currentTime
                "",                // accountId
                "",                // cardNumber
                "",                // typeCode
                "",                // categoryCode
                "",                // source
                "",                // description
                "",                // amount
                "",                // origDate
                "",                // procDate
                "",                // merchantId
                "",                // merchantName
                "",                // merchantCity
                "",                // merchantZip
                "",                // confirmation
                "",                // errMsg
                FieldColor.DEFAULT // errMsgColor
        );
    }

    /**
     * Returns a copy of this {@code CoTrn02Output} with a new {@link #errMsg()}
     * and {@link #errMsgColor()}, preserving all other twenty fields unchanged.
     *
     * <p>This helper supports the canonical COBOL "decorate output with error
     * message" idiom &mdash; in the COBOL program, the controller computes the
     * transaction-detail field values into the output map area, then
     * conditionally moves an error message string to {@code ERRMSGO} and a
     * color byte ({@code RED} for validation/IO errors, {@code GREEN} for the
     * success indicator following a {@code WRITE TRANSACT-FILE NORMAL} branch)
     * to {@code ERRMSGC} just before {@code EXEC CICS SEND MAP}. In the Java
     * translation, the controller builds the output record without an error
     * message via the {@link #empty()} factory or the canonical constructor,
     * populates the transaction-detail fields, and then, if an error or
     * success condition occurs, calls {@code output.withErrMsg(msg,
     * FieldColor.RED)} or {@code output.withErrMsg(msg, FieldColor.GREEN)} to
     * derive a decorated copy. Because records in finalized Java 25 do not
     * have a built-in {@code with} syntax (per AAP &sect;0.1.2), this method is
     * the hand-written equivalent.
     *
     * @param newMsg   the new error / success / informational message text (78
     *                 chars max in the BMS map; this method does not truncate,
     *                 leaving the wire-level truncation to the SEND-MAP
     *                 adapter). Must not be {@code null}; pass the empty
     *                 {@link String} {@code ""} to clear the message.
     * @param newColor the new color attribute. Must not be {@code null}; pass
     *                 {@link FieldColor#DEFAULT} to clear the override,
     *                 {@link FieldColor#RED} to emit a hard-error message, or
     *                 {@link FieldColor#GREEN} to emit a success indicator.
     * @return a new {@code CoTrn02Output} identical to this one except with the
     *         supplied {@code newMsg} and {@code newColor}
     *
     * @throws NullPointerException if {@code newMsg} or {@code newColor} is
     *                              {@code null}
     */
    public CoTrn02Output withErrMsg(String newMsg, FieldColor newColor) {
        return new CoTrn02Output(
                title1,
                title2,
                transactionName,
                programName,
                currentDate,
                currentTime,
                accountId,
                cardNumber,
                typeCode,
                categoryCode,
                source,
                description,
                amount,
                origDate,
                procDate,
                merchantId,
                merchantName,
                merchantCity,
                merchantZip,
                confirmation,
                newMsg,
                newColor);
    }
}
