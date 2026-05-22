/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by
// the java.base module (and by the modules it reads). This brings in:
//   * java.lang.String                   — the type of twenty-one record components
//                                          carrying BMS display field values (title1,
//                                          title2, transactionName, programName,
//                                          currentDate, currentTime, transactionIdIn,
//                                          transactionId, cardNumber, typeCode,
//                                          categoryCode, source, description, amount,
//                                          origDate, procDate, merchantId, merchantName,
//                                          merchantCity, merchantZip, errMsg);
//   * java.util.Objects                  — the source of Objects.requireNonNull(...)
//                                          invoked twenty-two times inside the compact
//                                          constructor per AAP §0.6.3 / JEP 513 to
//                                          enforce non-null contracts before the
//                                          canonical field assignment runs;
//   * java.lang.NullPointerException     — raised (via Objects.requireNonNull) on any
//                                          null component, with the component name as
//                                          the exception detail message for diagnosis
//                                          per the COBOL VALIDATE-INPUT idiom;
//   * java.lang.Enum facilities          — the supertype of the nested FieldColor
//                                          enumeration and the source of its auto-
//                                          generated values()/valueOf() utilities.
//
// The file schema's external_imports list specifies exactly one entry — the java.base
// module — and the internal_imports list is empty; no other import is permitted or
// required on this file. The single "import module java.base;" line replaces the
// verbose pair of "import java.lang.String;" (implicit in every compilation unit) and
// "import java.util.Objects;", and aligns the file with the AAP §0.6.7 / §0.7.3 mandate
// to use Module Import Declarations finalized in Java 25.
import module java.base;

/**
 * BMS output record carrying every value sent to the 3270 terminal for the
 * <strong>COTRN01</strong> (View Transaction) screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COTRN01.bms} (mapset {@code COTRN01},
 *       map {@code COTRN1A}, size 24x80, {@code CTRL=(ALARM,FREEKB)},
 *       {@code EXTATT=YES}, {@code LANG=COBOL}, {@code MODE=INOUT},
 *       {@code STORAGE=AUTO}, {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COTRN01.CPY} (output group
 *       {@code 01 COTRN1AO REDEFINES COTRN1AI} containing the six header echoes
 *       {@code TRNNAMEO}, {@code TITLE01O}, {@code CURDATEO}, {@code PGMNAMEO},
 *       {@code TITLE02O}, {@code CURTIMEO}; the operator-input echo
 *       {@code TRNIDINO}; the thirteen transaction-detail display leaves
 *       {@code TRNIDO}, {@code CARDNUMO}, {@code TTYPCDO}, {@code TCATCDO},
 *       {@code TRNSRCO}, {@code TDESCO}, {@code TRNAMTO}, {@code TORIGDTO},
 *       {@code TPROCDTO}, {@code MIDO}, {@code MNAMEO}, {@code MCITYO},
 *       {@code MZIPO}; and the {@code ERRMSGO} status line plus its color
 *       attribute byte {@code ERRMSGC}).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COTRN01C.cbl} (transaction
 *       {@code CT01}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application class. This
 * record is the Java analog of the output view of the BMS symbolic structure
 * {@code COTRN1AO}: it carries the field values written to the terminal by the
 * controller via the equivalent of {@code EXEC CICS SEND MAP} after
 * {@code CoTrn01C.run(...)} returns an outcome of {@code SendMap}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; the record is a plain
 * Java carrier built around finalized Java 25 language features only.
 *
 * <h2>Transaction-view semantics</h2>
 * <p>COTRN01 is the <em>view / detail</em> screen for a single transaction. Unlike
 * COTRN02 (Add Transaction) which is mostly operator-editable, the View Transaction
 * screen is mostly {@code ATTRB=(ASKIP,...)} (read-only); the operator's only point
 * of input is the {@code TRNIDIN} field at row 6, column 21
 * ({@code LENGTH=16}, {@code COLOR=GREEN}, {@code HILIGHT=UNDERLINE}). When the
 * operator types a transaction id and presses ENTER, the COBOL controller issues
 * {@code EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRNIDINI)} and projects every
 * field of the resulting record onto its symbolic-map counterpart in
 * {@code COTRN1AO}, then issues {@code EXEC CICS SEND MAP} to paint the populated
 * screen. The Java translation is identical in observable behaviour: the
 * controller builds an instance of this record from a fetched
 * {@link com.blitzy.carddemo.domain.record.TranRecord} and returns it via the
 * {@code SendMap} outcome.
 *
 * <h2>Component inventory (22 components, one per output BMS leaf plus the
 * dynamic ERRMSGC color byte)</h2>
 * <p>The output side of the COTRN01 symbolic copybook declares twenty-one
 * {@code "O"}-suffixed leaves &mdash; one per {@code DFHMDF} field on the map. The
 * twenty-second record component, {@link #errMsgColor()}, models the dynamic
 * color-attribute byte {@code ERRMSGC} which the controller toggles between
 * {@link FieldColor#RED RED} (error condition) and {@link FieldColor#DEFAULT
 * DEFAULT} (no error) immediately before {@code SEND-MAP}. The seven other
 * per-field {@code "C"}-suffixed attribute bytes
 * ({@code TRNNAMEC}, {@code TITLE01C}, {@code CURDATEC}, {@code PGMNAMEC},
 * {@code TITLE02C}, {@code CURTIMEC}, ...) are never modified at run time by the
 * COBOL controller &mdash; they keep their BMS-compile-time {@code COLOR=} values
 * &mdash; so they are not materialized as record components in the Java
 * translation, in keeping with the AAP &sect;0.7.1 minimal-change principle.
 *
 * <ol>
 *   <li>A two-line title bar populated from the
 *       {@link com.blitzy.carddemo.domain.text.ScreenTitle} constants (mapped to
 *       {@link #title1()} and {@link #title2()}, both YELLOW at compile time).</li>
 *   <li>Static header chrome &mdash; the transaction id ({@link #transactionName()}),
 *       the program name ({@link #programName()}), the current date in MM/DD/YY
 *       format ({@link #currentDate()}), and the current time in HH:MM:SS format
 *       ({@link #currentTime()}).</li>
 *   <li>The operator-echo input field {@link #transactionIdIn()} (GREEN UNDERLINE)
 *       which preserves the value typed on the previous cycle so that PF4 (clear)
 *       and PF3 (back) work intuitively.</li>
 *   <li>Thirteen transaction-detail display fields populated from the fetched
 *       {@link com.blitzy.carddemo.domain.record.TranRecord}: transaction id
 *       ({@link #transactionId()}), card number ({@link #cardNumber()}, NB: per
 *       AAP &sect;0.7.2 / PCI section, the application class is responsible for
 *       masking the PAN in log output, but the BMS screen displays the full PAN
 *       to the operator), type code ({@link #typeCode()}), category code
 *       ({@link #categoryCode()}), source ({@link #source()}), description
 *       ({@link #description()}), amount ({@link #amount()}, signed decimal
 *       formatted as {@code -99999999.99}), origination date
 *       ({@link #origDate()}, YYYY-MM-DD), processing date ({@link #procDate()},
 *       YYYY-MM-DD), merchant id ({@link #merchantId()}), merchant name
 *       ({@link #merchantName()}), merchant city ({@link #merchantCity()}), and
 *       merchant ZIP ({@link #merchantZip()}).</li>
 *   <li>A single-line error/info message at row 23, 78 characters wide, declared
 *       BRT RED at BMS-compile time. The message is empty (the empty
 *       {@link String}) when no error has occurred; the
 *       {@link #errMsgColor()} attribute byte flips to {@link FieldColor#RED}
 *       only when the controller emits a hard-error string.</li>
 * </ol>
 *
 * <h2>BMS field-by-field mapping (per agent prompt Phase 2 syntactic checklist)</h2>
 * <table border="1" summary="BMS-to-record component mapping">
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
 *     <tr><td>{@code TRNIDIN}</td><td>UNPROT FSET IC GREEN UNDERLINE, 16 chars, (6,21)</td>
 *         <td>{@link #transactionIdIn()}</td></tr>
 *     <tr><td>{@code TRNID}</td><td>BLUE ASKIP, 16 chars, (10,22)</td>
 *         <td>{@link #transactionId()}</td></tr>
 *     <tr><td>{@code CARDNUM}</td><td>BLUE ASKIP, 16 chars, (10,58)</td>
 *         <td>{@link #cardNumber()}</td></tr>
 *     <tr><td>{@code TTYPCD}</td><td>BLUE ASKIP, 2 chars, (12,15)</td>
 *         <td>{@link #typeCode()}</td></tr>
 *     <tr><td>{@code TCATCD}</td><td>BLUE ASKIP, 4 chars, (12,36)</td>
 *         <td>{@link #categoryCode()}</td></tr>
 *     <tr><td>{@code TRNSRC}</td><td>BLUE ASKIP, 10 chars, (12,54)</td>
 *         <td>{@link #source()}</td></tr>
 *     <tr><td>{@code TDESC}</td><td>BLUE ASKIP, 60 chars, (14,19)</td>
 *         <td>{@link #description()}</td></tr>
 *     <tr><td>{@code TRNAMT}</td><td>BLUE ASKIP, 12 chars (-99999999.99), (16,14)</td>
 *         <td>{@link #amount()}</td></tr>
 *     <tr><td>{@code TORIGDT}</td><td>BLUE ASKIP, 10 chars (YYYY-MM-DD), (16,42)</td>
 *         <td>{@link #origDate()}</td></tr>
 *     <tr><td>{@code TPROCDT}</td><td>BLUE ASKIP, 10 chars (YYYY-MM-DD), (16,68)</td>
 *         <td>{@link #procDate()}</td></tr>
 *     <tr><td>{@code MID}</td><td>BLUE ASKIP, 9 chars, (18,19)</td>
 *         <td>{@link #merchantId()}</td></tr>
 *     <tr><td>{@code MNAME}</td><td>BLUE ASKIP, 30 chars, (18,48)</td>
 *         <td>{@link #merchantName()}</td></tr>
 *     <tr><td>{@code MCITY}</td><td>BLUE ASKIP, 25 chars, (20,21)</td>
 *         <td>{@link #merchantCity()}</td></tr>
 *     <tr><td>{@code MZIP}</td><td>BLUE ASKIP, 10 chars, (20,67)</td>
 *         <td>{@link #merchantZip()}</td></tr>
 *     <tr><td>{@code ERRMSG}</td><td>RED BRT FSET ASKIP, 78 chars, (23,1)</td>
 *         <td>{@link #errMsg()}</td></tr>
 *     <tr><td>{@code ERRMSGC}</td><td>color attribute byte (dynamic)</td>
 *         <td>{@link #errMsgColor()}</td></tr>
 *   </tbody>
 * </table>
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
 * {@code CoTrn00Output} (Transaction List) and {@code CoTrn02Output}
 * (Add Transaction) records. This uniformity lets the future CICS-SEND-MAP
 * adapter use a single {@code FieldColor}-to-BMS-attribute-byte mapping
 * function across the entire transaction sub-package, and lets the controllers
 * share a common error-decoration idiom (the {@code withErrMsg(...)} helper
 * below has the same shape on every transaction output DTO).
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
 * @param transactionIdIn   {@code TRNIDIN} echo (the operator-typed search id, 16
 *                          chars, GREEN UNDERLINE UNPROT FSET IC). Must not be
 *                          {@code null}; empty string means "no value typed yet".
 * @param transactionId     {@code TRNID} display field (16 chars, BLUE ASKIP) &mdash;
 *                          the fetched transaction id. Must not be {@code null};
 *                          empty string is accepted (no record displayed yet).
 * @param cardNumber        {@code CARDNUM} display field (16 chars, BLUE ASKIP)
 *                          &mdash; the full PAN as stored on the
 *                          {@link com.blitzy.carddemo.domain.record.TranRecord}.
 *                          Per AAP &sect;0.7.2 / PCI, full PAN is displayed on
 *                          the BMS screen exactly as in the legacy COBOL
 *                          program, but the application class is responsible
 *                          for masking the PAN in any log output (mask all but
 *                          the last four digits). Must not be {@code null};
 *                          empty string is accepted.
 * @param typeCode          {@code TTYPCD} display field (2 chars, BLUE ASKIP).
 *                          Must not be {@code null}; empty string is accepted.
 * @param categoryCode      {@code TCATCD} display field (4 chars, BLUE ASKIP).
 *                          Must not be {@code null}; empty string is accepted.
 * @param source            {@code TRNSRC} display field (10 chars, BLUE ASKIP).
 *                          Must not be {@code null}; empty string is accepted.
 * @param description       {@code TDESC} display field (60 chars, BLUE ASKIP).
 *                          Must not be {@code null}; empty string is accepted.
 * @param amount            {@code TRNAMT} display field (12 chars, BLUE ASKIP,
 *                          format {@code -99999999.99}). The Java-side controller
 *                          formats a {@link java.math.BigDecimal} value into this
 *                          string via the {@code Decimals} utility (AAP
 *                          &sect;0.6.1) before constructing this record.
 *                          Must not be {@code null}; empty string is accepted.
 * @param origDate          {@code TORIGDT} display field (10 chars, BLUE ASKIP,
 *                          format YYYY-MM-DD). Must not be {@code null}; empty
 *                          string is accepted.
 * @param procDate          {@code TPROCDT} display field (10 chars, BLUE ASKIP,
 *                          format YYYY-MM-DD). Must not be {@code null}; empty
 *                          string is accepted.
 * @param merchantId        {@code MID} display field (9 chars, BLUE ASKIP).
 *                          Must not be {@code null}; empty string is accepted.
 * @param merchantName      {@code MNAME} display field (30 chars, BLUE ASKIP).
 *                          Must not be {@code null}; empty string is accepted.
 * @param merchantCity      {@code MCITY} display field (25 chars, BLUE ASKIP).
 *                          Must not be {@code null}; empty string is accepted.
 * @param merchantZip       {@code MZIP} display field (10 chars, BLUE ASKIP).
 *                          Must not be {@code null}; empty string is accepted.
 * @param errMsg            {@code ERRMSG} status line (78 chars, RED BRT FSET
 *                          ASKIP at row 23, column 1). Must not be {@code null};
 *                          empty string means "no error condition".
 * @param errMsgColor       {@code ERRMSGC} attribute byte. Set to
 *                          {@link FieldColor#RED} when the controller emits a
 *                          hard-error message; {@link FieldColor#DEFAULT}
 *                          otherwise.
 *
 * @see CoTrn01Input
 * @see com.blitzy.carddemo.application.transaction.CoTrn01Output.FieldColor
 * @since 1.0.0
 */
public record CoTrn01Output(
        String title1,
        String title2,
        String transactionName,
        String programName,
        String currentDate,
        String currentTime,
        String transactionIdIn,
        String transactionId,
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
        String errMsg,
        FieldColor errMsgColor) {

    /**
     * BMS field-color attribute, used by {@link #errMsgColor()} to model the
     * dynamic {@code ERRMSGC} attribute byte and (by convention shared with the
     * sibling DTOs {@code CoTrn00Output} and {@code CoTrn02Output}) any other
     * runtime color override the controller may wish to apply.
     *
     * <p>The nine values map one-for-one to the eight BMS-supported colors
     * (NEUTRAL, BLUE, GREEN, YELLOW, RED, TURQUOISE, PINK, WHITE) plus a single
     * sentinel value {@link #DEFAULT} which means "no override &mdash; honour
     * the BMS-compile-time {@code COLOR=} setting on the field". The
     * BMS-compile-time setting for {@code ERRMSG} is {@code COLOR=RED} (see
     * {@code app/bms/COTRN01.bms}), so the only practical use for
     * {@link #errMsgColor()} on this DTO is to flip between {@link #RED} (when
     * the error message is populated) and {@link #DEFAULT} (when the message is
     * empty); the other seven values exist for cross-DTO consistency.
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

        /** Green (BMS {@code COLOR=GREEN}). */
        GREEN,

        /** Yellow (BMS {@code COLOR=YELLOW}). */
        YELLOW,

        /**
         * Red (BMS {@code COLOR=RED}) &mdash; used for the
         * {@link CoTrn01Output#errMsgColor() error-message field} on error
         * conditions per the BMS map's
         * {@code ERRMSG ATTRB=(ASKIP,BRT,FSET) COLOR=RED} declaration.
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
    public CoTrn01Output {
        Objects.requireNonNull(title1, "title1");
        Objects.requireNonNull(title2, "title2");
        Objects.requireNonNull(transactionName, "transactionName");
        Objects.requireNonNull(programName, "programName");
        Objects.requireNonNull(currentDate, "currentDate");
        Objects.requireNonNull(currentTime, "currentTime");
        Objects.requireNonNull(transactionIdIn, "transactionIdIn");
        Objects.requireNonNull(transactionId, "transactionId");
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
        Objects.requireNonNull(errMsg, "errMsg");
        Objects.requireNonNull(errMsgColor, "errMsgColor");
    }

    /**
     * Returns an "empty" output record suitable for the initial COTRN01
     * send-map cycle: every text component is the empty {@link String} and the
     * error-message color is {@link FieldColor#DEFAULT}.
     *
     * <p>This matches the legacy COBOL idiom for the first program invocation:
     * the controller issues {@code MOVE LOW-VALUES TO COTRN1AO} before
     * populating any header or detail fields, then issues
     * {@code EXEC CICS SEND MAP} to paint the empty View Transaction screen
     * (operator can then type a transaction id and press ENTER to fetch).
     *
     * <p>The returned record satisfies every non-null invariant of the compact
     * constructor (empty {@link String} is non-null) and carries no payload
     * &mdash; consumers may safely treat it as the canonical "starting state"
     * of an output cycle.
     *
     * @return a fully-blank {@code CoTrn01Output} (never {@code null}) with all
     *         twenty-one text components set to the empty {@link String} and
     *         {@link #errMsgColor()} set to {@link FieldColor#DEFAULT}
     */
    public static CoTrn01Output empty() {
        return new CoTrn01Output(
                "",                // title1
                "",                // title2
                "",                // transactionName
                "",                // programName
                "",                // currentDate
                "",                // currentTime
                "",                // transactionIdIn
                "",                // transactionId
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
                "",                // errMsg
                FieldColor.DEFAULT // errMsgColor
        );
    }

    /**
     * Returns a copy of this {@code CoTrn01Output} with a new {@link #errMsg()}
     * and {@link #errMsgColor()}, preserving all other twenty fields unchanged.
     *
     * <p>This helper supports the canonical COBOL "decorate output with error
     * message" idiom &mdash; in the COBOL program, the controller computes the
     * transaction-detail field values into the output map area, then
     * conditionally moves an error message string to {@code ERRMSGO} and the
     * RED color byte to {@code ERRMSGC} just before {@code EXEC CICS SEND MAP}.
     * In the Java translation, the controller builds the output record without
     * an error message via the {@link #empty()} factory or the canonical
     * constructor, populates the transaction-detail fields, and then, if an
     * error condition occurs (e.g. {@code "Tran ID can NOT be empty..."},
     * {@code "Transaction id NOT found..."}, or {@code "Invalid key pressed..."}),
     * calls {@code output.withErrMsg(msg, FieldColor.RED)} to derive a
     * decorated copy. Because records in finalized Java 25 do not have a
     * built-in {@code with} syntax (per AAP &sect;0.1.2), this method is the
     * hand-written equivalent.
     *
     * @param newMsg   the new error / informational message text (78 chars max
     *                 in the BMS map; this method does not truncate, leaving
     *                 the wire-level truncation to the SEND-MAP adapter). Must
     *                 not be {@code null}; pass the empty {@link String}
     *                 {@code ""} to clear the message.
     * @param newColor the new color attribute. Must not be {@code null}; pass
     *                 {@link FieldColor#DEFAULT} to clear the override or
     *                 {@link FieldColor#RED} to emit a hard-error message.
     * @return a new {@code CoTrn01Output} identical to this one except with the
     *         supplied {@code newMsg} and {@code newColor}
     *
     * @throws NullPointerException if {@code newMsg} or {@code newColor} is
     *                              {@code null}
     */
    public CoTrn01Output withErrMsg(String newMsg, FieldColor newColor) {
        return new CoTrn01Output(
                title1,
                title2,
                transactionName,
                programName,
                currentDate,
                currentTime,
                transactionIdIn,
                transactionId,
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
                newMsg,
                newColor);
    }
}
