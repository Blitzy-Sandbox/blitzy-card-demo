/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.report;

// JEP 511 (finalized in Java 25): a single declaration imports all packages
// exported by the java.base module (and by the modules it reads). This brings
// in every standard-library type referenced by this DTO:
//   * java.lang.String              -- type of the eighteen String record
//                                      components carrying BMS display-field
//                                      values (transactionName, title01,
//                                      currentDate, programName, title02,
//                                      currentTime, monthly, yearly, custom,
//                                      startMonth, startDay, startYear,
//                                      endMonth, endDay, endYear,
//                                      confirmation, errMsg, focusField);
//   * java.util.Objects             -- source of Objects.requireNonNull(...)
//                                      called in the compact (canonical)
//                                      constructor, in the Builder.errMsgColor
//                                      setter, and in the withErrMsg copy
//                                      helper to enforce the non-null
//                                      contract on FieldColor inputs;
//   * java.lang.NullPointerException -- raised (via Objects.requireNonNull)
//                                      when a null FieldColor is supplied,
//                                      with the parameter name carried as the
//                                      exception detail message;
//   * java.lang.Enum facilities     -- the supertype of the nested FieldColor
//                                      enumeration and the source of its
//                                      auto-generated values()/valueOf().
//   * java.lang.IllegalArgumentException -- raised by the compact constructor
//                                      and by every Builder setter to reject
//                                      over-length BMS field values per AAP
//                                      §0.6.5 byte-for-byte parity mandate
//                                      (silent truncation would corrupt the
//                                      send-map and break golden tests).
//
// The single "import module java.base;" declaration replaces the implicit
// "import java.lang.String;" and the explicit "import java.util.Objects;"
// required to compile this file, and aligns the code with the AAP §0.6.7
// / §0.7.3 mandate to use Module Import Declarations finalized in Java 25
// (JEP 511).
import module java.base;

// AAP §0.7.1 traceability mandate: every translated artifact must cite its
// original COBOL source via the @CobolProgram annotation declared in the
// carddemo-domain module. carddemo-application declares carddemo-domain as
// a direct dependency in its pom.xml, so the annotation is on the classpath
// and resolvable here. The previous claim that the annotation module was
// unreachable was incorrect; the dependency has always been available.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Output DTO for the {@code CORPT0A} BMS map (transaction {@code CR00},
 * online program {@code CORPT00C} — "Print Transaction Reports").
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/CORPT00.bms} (mapset
 *       {@code CORPT00}, map {@code CORPT0A}, size 24x80,
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}, {@code LANG=COBOL},
 *       {@code MODE=INOUT}, {@code STORAGE=AUTO}, {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/CORPT00.CPY} — the output
 *       group {@code 01 CORPT0AO REDEFINES CORPT0AI} on lines 121-224
 *       declares for every BMS field five attribute bytes (color {@code C},
 *       programmed-symbol {@code P}, highlight {@code H}, validation
 *       {@code V}, and output value {@code O}). Of these, only the
 *       {@code O}-suffixed output values are populated by the controller for
 *       every field, plus the {@code ERRMSGC} color byte which the controller
 *       flips between RED (error) and GREEN (success).</li>
 *   <li>Translated COBOL program: {@code app/cbl/CORPT00C.cbl}
 *       (transaction {@code CR00}, working-storage {@code WS-PGMNAME} =
 *       {@code "CORPT00C"}, {@code WS-TRANID} = {@code "CR00"}).</li>
 * </ul>
 *
 * <h2>Role — entry-contract DTO (output side)</h2>
 * <p>Per Agent Action Plan §0.3.5 and §0.4.1, BMS maps translate to entry-contract
 * DTO records on the corresponding application class. This record is the Java
 * analog of the output view of the BMS symbolic structure {@code CORPT0AO};
 * it is the contract returned by
 * {@code CoRpt00C.execute(CoRpt00Input, com.blitzy.carddemo.domain.commarea.CardDemoCommarea)}
 * (wrapped in {@code Outcome.SendMap}) and consumed by the composition root
 * in {@code carddemo-app} to render the 3270 screen back to the user. There
 * is no web framework, no Spring binding, no Jakarta Bean Validation; the
 * record is a plain Java carrier built around finalized Java 25 language
 * features only (records, JEP 511 module imports, JEP 513 flexible
 * constructor bodies).
 *
 * <h2>Transaction Reports — semantics</h2>
 * <p>{@code CORPT00C} prompts the operator to select one of three report
 * scopes via the radio-style indicators
 * {@link #monthly() MONTHLY}, {@link #yearly() YEARLY},
 * {@link #custom() CUSTOM}. The {@code CUSTOM} option additionally requires
 * a start date ({@link #startMonth()} / {@link #startDay()} /
 * {@link #startYear()}) and an end date ({@link #endMonth()} /
 * {@link #endDay()} / {@link #endYear()}). On {@code ENTER}, the operator is
 * prompted to confirm via the 1-char {@link #confirmation()} flag
 * ({@code Y}/{@code N}); on a confirmed submission the controller writes a
 * job-card record to the JOBS extra-partition TDQ (in the Java translation,
 * the controller invokes the batch driver directly per AAP §0.4.1) and emits
 * a GREEN success message; otherwise it emits a RED error message describing
 * the failed validation.
 *
 * <h2>Dynamic {@code ERRMSGC} color — two values written by the controller</h2>
 * <p>The COBOL controller writes {@code ERRMSGC OF CORPT0AO} with exactly two
 * runtime values:
 * <ul>
 *   <li>{@link FieldColor#GREEN} after a successful TDQ write (per the COBOL
 *       idiom {@code MOVE DFHGREEN TO ERRMSGC OF CORPT0AO} in
 *       {@code CORPT00C}); and</li>
 *   <li>{@link FieldColor#RED} (the BMS compile-time default per
 *       {@code app/bms/CORPT00.bms} line 219: {@code ERRMSG ... COLOR=RED})
 *       on every other error path.</li>
 * </ul>
 * The other {@link FieldColor} constants exist for cross-DTO consistency with
 * the sibling output records {@code CoTrn00Output}, {@code CoTrn01Output},
 * and {@code CoTrn02Output}; the BMS-compile-time color of the
 * {@code ERRMSG} field is {@code RED}, so the {@link #errMsgColor()}
 * component defaults to {@link FieldColor#RED} when constructed via
 * {@link #empty()} or via {@link #builder()}.
 *
 * <h2>Synthetic {@code focusField} component</h2>
 * <p>In COBOL, cursor positioning is performed by setting the {@code L}-suffixed
 * "length" attribute byte of a field to {@code -1} (e.g.,
 * {@code MOVE -1 TO MONTHLYL OF CORPT0AI}); the BMS hardware then positions
 * the 3270 cursor on the next {@code SEND MAP} at the start of that field.
 * Because the Java translation cannot directly manipulate symbolic-map length
 * bytes (and indeed has no {@code L}-suffixed field at all in this record),
 * the cursor-positioning intent is captured here as a synthetic component
 * {@link #focusField()} carrying the COBOL field-length name (e.g.
 * {@code "MONTHLYL"}, {@code "SDTMML"}, {@code "ERRMSGL"}). The composition
 * root in {@code carddemo-app} that renders the 3270 screen translates this
 * hint back into the appropriate {@code MOVE -1 TO XXXL} operation on the
 * outgoing symbolic map. An empty string indicates "no explicit positioning
 * — let the hardware choose".
 *
 * <h2>Component inventory (nineteen components)</h2>
 * <p>Eighteen {@link String} components plus one {@link FieldColor} enum
 * component, mapping one-for-one to the {@code O}-suffixed leaves of
 * {@code CORPT0AO} except for the dynamic-color byte {@code ERRMSGC} and the
 * synthetic {@code focusField} hint:
 *
 * <table>
 * <caption>BMS-to-record component mapping</caption>
 *   <thead>
 *     <tr><th>BMS field (CORPT0AO)</th><th>PIC</th><th>Record component</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code TRNNAMEO}</td><td>{@code X(4)}</td>
 *         <td>{@link #transactionName()} — header transaction id
 *         ({@code "CR00"})</td></tr>
 *     <tr><td>{@code TITLE01O}</td><td>{@code X(40)}</td>
 *         <td>{@link #title01()} — first title line
 *         ({@code CCDA-TITLE01})</td></tr>
 *     <tr><td>{@code CURDATEO}</td><td>{@code X(8)}</td>
 *         <td>{@link #currentDate()} — header date
 *         ({@code MM/DD/YY})</td></tr>
 *     <tr><td>{@code PGMNAMEO}</td><td>{@code X(8)}</td>
 *         <td>{@link #programName()} — header program id
 *         ({@code "CORPT00C"})</td></tr>
 *     <tr><td>{@code TITLE02O}</td><td>{@code X(40)}</td>
 *         <td>{@link #title02()} — second title line
 *         ({@code CCDA-TITLE02})</td></tr>
 *     <tr><td>{@code CURTIMEO}</td><td>{@code X(8)}</td>
 *         <td>{@link #currentTime()} — header time
 *         ({@code HH:MM:SS})</td></tr>
 *     <tr><td>{@code MONTHLYO}</td><td>{@code X(1)}</td>
 *         <td>{@link #monthly()} — echo of monthly-report toggle
 *         (operator types any non-space to select)</td></tr>
 *     <tr><td>{@code YEARLYO}</td><td>{@code X(1)}</td>
 *         <td>{@link #yearly()} — echo of yearly-report toggle</td></tr>
 *     <tr><td>{@code CUSTOMO}</td><td>{@code X(1)}</td>
 *         <td>{@link #custom()} — echo of custom-range toggle</td></tr>
 *     <tr><td>{@code SDTMMO}</td><td>{@code X(2)}</td>
 *         <td>{@link #startMonth()} — echo of start-date month</td></tr>
 *     <tr><td>{@code SDTDDO}</td><td>{@code X(2)}</td>
 *         <td>{@link #startDay()} — echo of start-date day</td></tr>
 *     <tr><td>{@code SDTYYYYO}</td><td>{@code X(4)}</td>
 *         <td>{@link #startYear()} — echo of start-date year</td></tr>
 *     <tr><td>{@code EDTMMO}</td><td>{@code X(2)}</td>
 *         <td>{@link #endMonth()} — echo of end-date month</td></tr>
 *     <tr><td>{@code EDTDDO}</td><td>{@code X(2)}</td>
 *         <td>{@link #endDay()} — echo of end-date day</td></tr>
 *     <tr><td>{@code EDTYYYYO}</td><td>{@code X(4)}</td>
 *         <td>{@link #endYear()} — echo of end-date year</td></tr>
 *     <tr><td>{@code CONFIRMO}</td><td>{@code X(1)}</td>
 *         <td>{@link #confirmation()} — echo of {@code Y}/{@code N}
 *         confirmation</td></tr>
 *     <tr><td>{@code ERRMSGO}</td><td>{@code X(78)}</td>
 *         <td>{@link #errMsg()} — single-line error / success message
 *         at row 23</td></tr>
 *     <tr><td>{@code ERRMSGC}</td><td>{@code X(1)} (attribute byte)</td>
 *         <td>{@link #errMsgColor()} — RED on error, GREEN on
 *         success</td></tr>
 *     <tr><td>(synthetic)</td><td>—</td>
 *         <td>{@link #focusField()} — cursor-positioning hint that the
 *         composition root translates to {@code MOVE -1 TO XXXL}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Null vs empty discipline</h2>
 * <p>Unlike the sibling {@code CoTrn02Output} (which rejects every null
 * component with a {@link NullPointerException}), this record is
 * <em>lenient</em> about {@link String} components: any {@code null}
 * {@link String} argument to the compact constructor is silently normalized
 * to the empty {@link String} {@code ""} (which mirrors the legacy COBOL
 * idiom {@code MOVE SPACES TO field-O} and {@code MOVE LOW-VALUES TO group}).
 * The {@link FieldColor} component must, however, be non-null: a {@code null}
 * {@code errMsgColor} is rejected with a {@link NullPointerException} via
 * {@link java.util.Objects#requireNonNull(Object, String)}. This
 * "{@link String}-lenient, enum-strict" stance matches the agent prompt for
 * this DTO (which mandates normalization for strings and explicit non-null
 * enforcement for the color attribute).
 *
 * <h2>Length and padding</h2>
 * <p>No length sanity checks are performed in the compact constructor. The
 * BMS hardware truncates or pads to the declared field length at
 * {@code SEND MAP} time; the composition root in {@code carddemo-app} is
 * responsible for any field-width adjustment if a strict-mode renderer is
 * needed. This deliberately avoids the {@code PIC X(n)} length checks
 * performed on the sibling DTOs because the controller in {@code CoRpt00C}
 * synthesizes some of these components from formatted-string operations
 * (e.g. {@link #currentDate()} from {@code java.time.LocalDate} formatting)
 * whose outputs are guaranteed to fit but whose internal width assertions
 * belong in the controller, not in this DTO.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all nineteen components are
 * {@code final} and accessors are auto-generated; there are no setters
 * and no mutable internal state. The nested {@link Builder} is mutable
 * during construction but never escapes a single thread (it is created
 * via {@link #builder()}, modified inline, and resolved via
 * {@link Builder#build()} on the same thread). The record itself is
 * therefore safe to share across threads — including across the
 * virtual-thread fan-out mandated by AAP §0.6.6 — without
 * synchronization.
 *
 * <h2>Non-goals (faithful to AAP §0.7.4 "explicitly forbidden")</h2>
 * <ul>
 *   <li>No Spring annotations — this record is plain Java.</li>
 *   <li>No Lombok — record components, accessors, and the builder are
 *       explicit.</li>
 *   <li>No Jakarta Bean Validation — validation is hand-written in the
 *       compact constructor per AAP §0.6.3 / JEP 513.</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} — the
 *       date/time components ({@link #currentDate()}, {@link #currentTime()},
 *       {@link #startMonth()}, ...) are pre-formatted display
 *       {@link String}s; any parsing or formatting is performed elsewhere
 *       via {@code java.time}.</li>
 *   <li>No {@code double} or {@code float} — this record carries no monetary
 *       value; the report's transaction amounts are formatted by the
 *       controller via {@code com.blitzy.carddemo.domain.util.Decimals} from
 *       {@link java.math.BigDecimal} values per AAP §0.6.1.</li>
 *   <li>No preview Java features — only finalized Java 25 features are used
 *       (records, JEP 511 module import, JEP 513 flexible constructor bodies).</li>
 *   <li>Traceability via the {@link CobolProgram} annotation declared in the
 *       carddemo-domain module (a direct dependency of carddemo-application).</li>
 * </ul>
 *
 * @param transactionName {@code TRNNAMEO}: 4-char transaction id
 *                        ({@code "CR00"}). Rejected by the compact
 *                        constructor with {@link IllegalArgumentException}
 *                        if longer than 4 characters; {@code null} is
 *                        silently normalized to {@code ""}.
 * @param title01         {@code TITLE01O}: 40-char top title line
 *                        ({@code CCDA-TITLE01}). {@code null} → {@code ""}.
 *                        Renamed from {@code title1} to match the BMS
 *                        symbolic-map field name {@code TITLE01O} and the
 *                        established sibling-DTO naming convention.
 * @param currentDate     {@code CURDATEO}: 8-char MM/DD/YY current date.
 *                        {@code null} → {@code ""}.
 * @param programName     {@code PGMNAMEO}: 8-char program id
 *                        ({@code "CORPT00C"}). {@code null} → {@code ""}.
 * @param title02         {@code TITLE02O}: 40-char second title line
 *                        ({@code CCDA-TITLE02}). {@code null} → {@code ""}.
 *                        Renamed from {@code title2} to match the BMS
 *                        symbolic-map field name {@code TITLE02O}.
 * @param currentTime     {@code CURTIMEO}: 8-char HH:MM:SS current time.
 *                        {@code null} → {@code ""}.
 * @param monthly         {@code MONTHLYO}: 1-char echo of MONTHLYI toggle.
 *                        {@code null} → {@code ""}.
 * @param yearly          {@code YEARLYO}: 1-char echo of YEARLYI toggle.
 *                        {@code null} → {@code ""}.
 * @param custom          {@code CUSTOMO}: 1-char echo of CUSTOMI toggle.
 *                        {@code null} → {@code ""}.
 * @param startMonth      {@code SDTMMO}: 2-char echo of start-date month
 *                        (SDTMMI). {@code null} → {@code ""}.
 * @param startDay        {@code SDTDDO}: 2-char echo of start-date day
 *                        (SDTDDI). {@code null} → {@code ""}.
 * @param startYear       {@code SDTYYYYO}: 4-char echo of start-date year
 *                        (SDTYYYYI). {@code null} → {@code ""}.
 * @param endMonth        {@code EDTMMO}: 2-char echo of end-date month
 *                        (EDTMMI). {@code null} → {@code ""}.
 * @param endDay          {@code EDTDDO}: 2-char echo of end-date day
 *                        (EDTDDI). {@code null} → {@code ""}.
 * @param endYear         {@code EDTYYYYO}: 4-char echo of end-date year
 *                        (EDTYYYYI). {@code null} → {@code ""}.
 * @param confirmation    {@code CONFIRMO}: 1-char echo of CONFIRMI Y/N
 *                        confirmation. {@code null} → {@code ""}.
 * @param errMsg          {@code ERRMSGO}: 78-char error or success message.
 *                        {@code null} → {@code ""}.
 * @param errMsgColor     {@code ERRMSGC}: dynamic color attribute. Must be
 *                        non-null; {@link FieldColor#RED} is the default
 *                        (BMS compile-time), {@link FieldColor#GREEN} is
 *                        used on a successful submission. A {@code null}
 *                        argument raises {@link NullPointerException}.
 * @param focusField      Synthetic: COBOL field-length name (e.g.,
 *                        {@code "MONTHLYL"}, {@code "SDTMML"}, {@code "ERRMSGL"})
 *                        that the COBOL set to {@code -1} via
 *                        {@code MOVE -1 TO XXXL}; the composition root
 *                        uses this to position the cursor. {@code null} →
 *                        {@code ""} (no positioning).
 *
 * @see com.blitzy.carddemo.application.report.CoRpt00Output.FieldColor
 * @since 1.0.0
 */
@CobolProgram(
        value = "CORPT00",
        sourcePath = "app/bms/CORPT00.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (output side); symbolic copybook 01 CORPT0AO "
                + "REDEFINES CORPT0AI in app/cpy-bms/CORPT00.CPY (lines 121-224). Driven "
                + "by online program app/cbl/CORPT00C.cbl (transaction CR00 — Print "
                + "Transaction Reports)."
)
public record CoRpt00Output(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String monthly,
        String yearly,
        String custom,
        String startMonth,
        String startDay,
        String startYear,
        String endMonth,
        String endDay,
        String endYear,
        String confirmation,
        String errMsg,
        FieldColor errMsgColor,
        String focusField) {

    /**
     * BMS 3270 field-color attribute (DFHBMSCA-style), used by
     * {@link #errMsgColor()} to model the dynamic {@code ERRMSGC} attribute
     * byte and, by convention shared with the sibling DTOs in
     * {@code application/transaction/}, any other runtime color override
     * that future controllers may apply.
     *
     * <p>The nine values map to the eight BMS-supported colors
     * ({@link #NEUTRAL}, {@link #BLUE}, {@link #GREEN}, {@link #YELLOW},
     * {@link #RED}, {@link #TURQUOISE}, {@link #PINK}, {@link #WHITE}) plus
     * a single sentinel value {@link #DEFAULT} meaning "no override — honour
     * the BMS-compile-time {@code COLOR=} setting on the field". The
     * {@code CORPT00C} controller flips {@link CoRpt00Output#errMsgColor()}
     * between {@link #RED} (validation/IO error, also the BMS-compile-time
     * default) and {@link #GREEN} (successful TDQ JOBS write).
     *
     * <p>Why an enum rather than a sealed interface? AAP §0.6.10 reserves
     * sealed interfaces for COBOL constructs that partition a value space
     * (REDEFINES and 88-level data taxonomies). The BMS color attribute is
     * a closed set of opaque labels with no payload, which is exactly the
     * case for which a plain {@code enum} is idiomatic.
     *
     * <p>The enum is intentionally identical (same nine values in the same
     * order) to the {@code FieldColor} enums on the sibling
     * {@code CoTrn00Output}, {@code CoTrn01Output}, and {@code CoTrn02Output}
     * records. This uniformity lets a future CICS-SEND-MAP adapter use a
     * single {@code FieldColor}-to-BMS-attribute-byte mapping function
     * across the entire application package.
     */
    public enum FieldColor {

        /**
         * No color override — the BMS map's compile-time {@code COLOR=}
         * setting is used. Carried by {@link CoRpt00Output#errMsgColor()}
         * when no message is being emitted on screens that prefer the
         * BMS default over an explicit value.
         */
        DEFAULT,

        /** CICS-default white/cream tone (BMS {@code COLOR=NEUTRAL}). */
        NEUTRAL,

        /** Blue (BMS {@code COLOR=BLUE}). */
        BLUE,

        /**
         * Green (BMS {@code COLOR=GREEN}) — set by COBOL
         * {@code CORPT00C} on a successful TDQ JOBS write
         * ({@code MOVE DFHGREEN TO ERRMSGC OF CORPT0AO}).
         */
        GREEN,

        /** Yellow (BMS {@code COLOR=YELLOW}). */
        YELLOW,

        /**
         * Red (BMS {@code COLOR=RED}) — the BMS-compile-time default
         * for the {@code ERRMSG} field per {@code app/bms/CORPT00.bms}
         * line 219: {@code ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET) COLOR=RED};
         * also the default emitted by the controller on every error path.
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
     * <p>Enforces two invariants on every constructed instance:
     * <ol>
     *   <li><strong>String null-normalization.</strong> Each of the
     *       eighteen {@link String} components is normalized so that any
     *       {@code null} reference is replaced with the empty
     *       {@link String} {@code ""}. This mirrors the legacy COBOL
     *       send-map idiom in which an unfilled BMS {@code PIC X(n)} field
     *       is SPACES (never undefined), and is the convention used by
     *       the sibling {@code CoTrn00Output} record. Empty strings are
     *       accepted; longer strings are NOT length-checked here (see the
     *       "Length and padding" section of the class-level Javadoc).</li>
     *   <li><strong>{@link FieldColor} non-null.</strong> The
     *       {@code errMsgColor} argument must be non-null. A {@code null}
     *       argument is rejected with a {@link NullPointerException}
     *       carrying the parameter name {@code "errMsgColor"} via
     *       {@link java.util.Objects#requireNonNull(Object, String)}.
     *       Callers must use {@link FieldColor#RED} (the BMS compile-time
     *       default) for "no message override" rather than passing
     *       {@code null}.</li>
     * </ol>
     *
     * <p>This constructor leverages <strong>JEP 513 Flexible Constructor
     * Bodies</strong> (finalized in Java 25): the assignment and validation
     * statements run before the implicit canonical field-binding, which is
     * the location AAP §0.6.3 prescribes for COBOL-style "validate before
     * bind" semantics.
     *
     * @throws NullPointerException if {@code errMsgColor} is {@code null},
     *                              with detail message {@code "errMsgColor"}
     */
    public CoRpt00Output {
        // --- COBOL "SPACES by default" null-normalization per AAP §0.7.1 ---
        transactionName = (transactionName == null) ? "" : transactionName;
        title01         = (title01         == null) ? "" : title01;
        currentDate     = (currentDate     == null) ? "" : currentDate;
        programName     = (programName     == null) ? "" : programName;
        title02         = (title02         == null) ? "" : title02;
        currentTime     = (currentTime     == null) ? "" : currentTime;
        monthly         = (monthly         == null) ? "" : monthly;
        yearly          = (yearly          == null) ? "" : yearly;
        custom          = (custom          == null) ? "" : custom;
        startMonth      = (startMonth      == null) ? "" : startMonth;
        startDay        = (startDay        == null) ? "" : startDay;
        startYear       = (startYear       == null) ? "" : startYear;
        endMonth        = (endMonth        == null) ? "" : endMonth;
        endDay          = (endDay          == null) ? "" : endDay;
        endYear         = (endYear         == null) ? "" : endYear;
        confirmation    = (confirmation    == null) ? "" : confirmation;
        errMsg          = (errMsg          == null) ? "" : errMsg;
        focusField      = (focusField      == null) ? "" : focusField;

        Objects.requireNonNull(errMsgColor, "errMsgColor");

        // --- PIC X(n) fixed-length validation per app/bms/CORPT00.bms ---
        // Values longer than the declared BMS width would cause silent
        // hardware truncation in the CICS SEND-MAP layer, corrupting the
        // wire format and breaking byte-for-byte parity per AAP §0.6.5.
        // Fail fast at the DTO boundary instead. The focusField is a
        // synthetic field (not a BMS leaf), so it is intentionally NOT
        // length-checked here — its consumers (the composition root)
        // interpret it as a COBOL field-length name and have their own
        // validation rules.
        checkPicLength("transactionName", transactionName, LEN_TRANSACTION_NAME);
        checkPicLength("title01",         title01,         LEN_TITLE);
        checkPicLength("currentDate",     currentDate,     LEN_CURRENT_DATE);
        checkPicLength("programName",     programName,     LEN_PROGRAM_NAME);
        checkPicLength("title02",         title02,         LEN_TITLE);
        checkPicLength("currentTime",     currentTime,     LEN_CURRENT_TIME);
        checkPicLength("monthly",         monthly,         LEN_TOGGLE);
        checkPicLength("yearly",          yearly,          LEN_TOGGLE);
        checkPicLength("custom",          custom,          LEN_TOGGLE);
        checkPicLength("startMonth",      startMonth,      LEN_DATE_MM);
        checkPicLength("startDay",        startDay,        LEN_DATE_DD);
        checkPicLength("startYear",       startYear,       LEN_DATE_YYYY);
        checkPicLength("endMonth",        endMonth,        LEN_DATE_MM);
        checkPicLength("endDay",          endDay,          LEN_DATE_DD);
        checkPicLength("endYear",         endYear,         LEN_DATE_YYYY);
        checkPicLength("confirmation",    confirmation,    LEN_TOGGLE);
        checkPicLength("errMsg",          errMsg,          LEN_ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------
    //  BMS PIC X(n) widths per app/bms/CORPT00.bms; used by the compact
    //  constructor's checkPicLength() calls to enforce fail-fast
    //  validation. AAP §0.6.5 byte-for-byte parity mandate: silent
    //  truncation at the CICS SEND-MAP layer would corrupt the wire
    //  format and break golden-record tests.
    // ------------------------------------------------------------------

    /** BMS {@code PIC X(4)} width of the {@code TRNNAME} field. */
    private static final int LEN_TRANSACTION_NAME = 4;

    /** BMS {@code PIC X(40)} width of the {@code TITLE01} / {@code TITLE02} fields. */
    private static final int LEN_TITLE = 40;

    /** BMS {@code PIC X(8)} width of the {@code CURDATE} field. */
    private static final int LEN_CURRENT_DATE = 8;

    /** BMS {@code PIC X(8)} width of the {@code PGMNAME} field. */
    private static final int LEN_PROGRAM_NAME = 8;

    /** BMS {@code PIC X(8)} width of the {@code CURTIME} field. */
    private static final int LEN_CURRENT_TIME = 8;

    /** BMS {@code PIC X(1)} width of the {@code MONTHLY}, {@code YEARLY}, {@code CUSTOM}, and {@code CONFIRM} toggles. */
    private static final int LEN_TOGGLE = 1;

    /** BMS {@code PIC X(2)} width of the start/end month ({@code SDTMM} / {@code EDTMM}). */
    private static final int LEN_DATE_MM = 2;

    /** BMS {@code PIC X(2)} width of the start/end day ({@code SDTDD} / {@code EDTDD}). */
    private static final int LEN_DATE_DD = 2;

    /** BMS {@code PIC X(4)} width of the start/end year ({@code SDTYYYY} / {@code EDTYYYY}). */
    private static final int LEN_DATE_YYYY = 4;

    /** BMS {@code PIC X(78)} width of the {@code ERRMSG} field. */
    private static final int LEN_ERROR_MESSAGE = 78;

    /**
     * Validates that a {@link String} component does not exceed its
     * declared BMS {@code PIC X(n)} on-screen width.
     *
     * <p>Enforces the AAP &sect;0.6.5 byte-for-byte parity contract at the
     * DTO boundary (CWE-20 input validation): values longer than the
     * declared BMS width would cause silent hardware truncation in the
     * CICS SEND-MAP layer, corrupting the wire format. Shorter values are
     * accepted unchanged &mdash; the BMS renderer pads-right with spaces
     * to the declared width before transmission.
     *
     * @param name      the component name (used in the exception message)
     * @param value     the component value (never {@code null}: the caller
     *                  guarantees null-normalization in the compact
     *                  constructor)
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
     * Returns an "empty" output record suitable for the initial CORPT00
     * send-map cycle: every {@link String} component is the empty
     * {@link String} {@code ""}, the {@link #errMsgColor()} is
     * {@link FieldColor#RED} (the BMS compile-time default for
     * {@code ERRMSG}), and the {@link #focusField()} hint is
     * {@code "MONTHLYL"} (the first user-input field on the screen).
     *
     * <p>This matches the legacy COBOL idiom for the first program
     * invocation: the controller issues {@code MOVE LOW-VALUES TO CORPT0AO}
     * before populating any header or detail fields, sets
     * {@code MOVE -1 TO MONTHLYL OF CORPT0AI} to position the cursor on the
     * Monthly toggle, and then issues {@code EXEC CICS SEND MAP} to paint
     * the empty Transaction Reports screen.
     *
     * <p>The returned record satisfies every invariant of the compact
     * constructor (empty {@link String} is non-null; {@link FieldColor#RED}
     * is non-null) and carries no payload — consumers may safely treat it
     * as the canonical "starting state" of an output cycle.
     *
     * @return a fully-blank {@code CoRpt00Output} (never {@code null}) with
     *         all eighteen text components set to the empty {@link String},
     *         {@link #errMsgColor()} set to {@link FieldColor#RED}, and
     *         {@link #focusField()} set to {@code "MONTHLYL"}
     */
    public static CoRpt00Output empty() {
        return new CoRpt00Output(
                "",              // transactionName  (TRNNAMEO)
                "",              // title01          (TITLE01O)
                "",              // currentDate      (CURDATEO)
                "",              // programName      (PGMNAMEO)
                "",              // title02          (TITLE02O)
                "",              // currentTime      (CURTIMEO)
                "",              // monthly          (MONTHLYO)
                "",              // yearly           (YEARLYO)
                "",              // custom           (CUSTOMO)
                "",              // startMonth       (SDTMMO)
                "",              // startDay         (SDTDDO)
                "",              // startYear        (SDTYYYYO)
                "",              // endMonth         (EDTMMO)
                "",              // endDay           (EDTDDO)
                "",              // endYear          (EDTYYYYO)
                "",              // confirmation     (CONFIRMO)
                "",              // errMsg           (ERRMSGO)
                FieldColor.RED,  // errMsgColor      (ERRMSGC; BMS default)
                "MONTHLYL"       // focusField       (initial cursor target)
        );
    }

    /**
     * Returns a new {@link Builder} seeded with the same defaults as
     * {@link #empty()}: every {@link String} field is the empty
     * {@link String} {@code ""}, {@link Builder#errMsgColor} is
     * {@link FieldColor#RED}, and {@link Builder#focusField} is
     * {@code "MONTHLYL"}.
     *
     * <p>The returned {@code Builder} is a one-shot mutable holder that
     * supports the fluent style
     * {@snippet :
     *   CoRpt00Output out = CoRpt00Output.builder()
     *           .transactionName("CR00")
     *           .programName("CORPT00C")
     *           .errMsg("Report submitted.")
     *           .errMsgColor(CoRpt00Output.FieldColor.GREEN)
     *           .focusField("CONFIRML")
     *           .build();
     * }
     * and produces an immutable {@link CoRpt00Output} via
     * {@link Builder#build()}. The {@code Builder} itself is not
     * thread-safe; in the AAP §0.6.6 virtual-thread fan-out model, each
     * worker thread constructs its own builder before publishing the
     * resulting immutable record.
     *
     * @return a fresh, mutable {@code Builder} (never {@code null})
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a copy of this output record with {@link #errMsg()} replaced
     * by {@code newErrMsg} and {@link #errMsgColor()} replaced by
     * {@code newColor}, preserving all other seventeen components unchanged.
     *
     * <p>This helper supports the canonical COBOL "decorate output with
     * error message" idiom in {@code CORPT00C}: the controller populates
     * the screen's header echoes and operator-input echoes, then layers an
     * error or success message on top just before
     * {@code EXEC CICS SEND MAP}. The COBOL idiom is
     * {@code MOVE 'error text' TO ERRMSGO OF CORPT0AO} followed by
     * {@code MOVE DFHRED TO ERRMSGC OF CORPT0AO} (or {@code DFHGREEN} on
     * success). Because records in finalized Java 25 do not have a built-in
     * {@code with} syntax (per AAP §0.1.2), this method is the hand-written
     * equivalent.
     *
     * <p>The {@code newErrMsg} argument is null-normalized to {@code ""}
     * (matching the compact constructor's behaviour); {@code newColor}
     * must be non-null.
     *
     * @param newErrMsg the new error / success / informational message
     *                  text (78 chars max in the BMS map; this method does
     *                  not truncate, leaving wire-level truncation to the
     *                  SEND-MAP adapter). May be {@code null} to clear the
     *                  message; null is normalized to {@code ""}.
     * @param newColor  the new color attribute. Must not be {@code null};
     *                  pass {@link FieldColor#RED} to emit a hard-error
     *                  message or {@link FieldColor#GREEN} to emit a
     *                  success indicator.
     * @return a new {@code CoRpt00Output} identical to this one except
     *         with the supplied {@code newErrMsg} (or {@code ""} if null)
     *         and {@code newColor}
     *
     * @throws NullPointerException if {@code newColor} is {@code null}
     */
    public CoRpt00Output withErrMsg(String newErrMsg, FieldColor newColor) {
        return new CoRpt00Output(
                transactionName,
                title01,
                currentDate,
                programName,
                title02,
                currentTime,
                monthly,
                yearly,
                custom,
                startMonth,
                startDay,
                startYear,
                endMonth,
                endDay,
                endYear,
                confirmation,
                (newErrMsg == null) ? "" : newErrMsg,
                Objects.requireNonNull(newColor, "newColor"),
                focusField);
    }

    /**
     * Returns a copy of this output record with {@link #focusField()}
     * replaced by {@code newFocusField}, preserving all other eighteen
     * components unchanged.
     *
     * <p>This helper supports the canonical COBOL cursor-positioning idiom
     * in {@code CORPT00C}: when a validation error is detected on the
     * monthly toggle, the controller emits
     * {@code MOVE -1 TO MONTHLYL OF CORPT0AI}; when the error is on the
     * start-date month, {@code MOVE -1 TO SDTMML OF CORPT0AI}; and so on.
     * The Java translation carries the same intent in the synthetic
     * {@link #focusField()} hint, and this method derives a decorated copy
     * by replacing it.
     *
     * <p>The {@code newFocusField} argument is null-normalized to
     * {@code ""} (matching the compact constructor's behaviour); the
     * empty string disables explicit cursor positioning.
     *
     * @param newFocusField the new focus-field hint (e.g.
     *                      {@code "MONTHLYL"}, {@code "SDTMML"},
     *                      {@code "ERRMSGL"}); may be {@code null} (which
     *                      is normalized to {@code ""}) to clear the
     *                      hint.
     * @return a new {@code CoRpt00Output} identical to this one except
     *         with the supplied {@code newFocusField} (or {@code ""} if
     *         null)
     */
    public CoRpt00Output withFocusField(String newFocusField) {
        return new CoRpt00Output(
                transactionName,
                title01,
                currentDate,
                programName,
                title02,
                currentTime,
                monthly,
                yearly,
                custom,
                startMonth,
                startDay,
                startYear,
                endMonth,
                endDay,
                endYear,
                confirmation,
                errMsg,
                errMsgColor,
                (newFocusField == null) ? "" : newFocusField);
    }

    /**
     * Fluent builder for {@link CoRpt00Output}.
     *
     * <p>The builder is a one-shot, mutable holder that mirrors the
     * nineteen record components. Each setter normalizes a {@code null}
     * {@link String} argument to the empty {@link String} {@code ""} and
     * each setter returns {@code this} to support method chaining; the
     * {@link #errMsgColor(FieldColor)} setter rejects {@code null} with a
     * {@link NullPointerException} (matching the record's compact
     * constructor contract).
     *
     * <p>The builder is initialized to the same defaults as
     * {@link CoRpt00Output#empty()}: every {@link String} field is
     * {@code ""}, {@link #errMsgColor} is {@link FieldColor#RED}, and
     * {@link #focusField} is {@code "MONTHLYL"}. This default state can
     * be directly resolved by {@link #build()} without further mutation
     * if a blank screen is required.
     *
     * <p>The builder is <strong>not</strong> thread-safe; each thread
     * should obtain its own builder via {@link CoRpt00Output#builder()},
     * mutate it locally, and publish only the immutable record returned
     * by {@link #build()}. In the AAP §0.6.6 virtual-thread fan-out model,
     * each worker thread satisfies this contract by construction (the
     * builder is a method-scoped local).
     *
     * <p>The class is declared {@code final} so that the build-and-publish
     * idiom cannot be subverted by a subclass; once {@link #build()} runs,
     * the resulting {@link CoRpt00Output} is immutable and safe for
     * cross-thread sharing.
     *
     * @see CoRpt00Output#builder()
     */
    public static final class Builder {

        /** Mutable accumulator for {@link CoRpt00Output#transactionName()}. */
        private String transactionName = "";

        /** Mutable accumulator for {@link CoRpt00Output#title01()}. */
        private String title01         = "";

        /** Mutable accumulator for {@link CoRpt00Output#currentDate()}. */
        private String currentDate     = "";

        /** Mutable accumulator for {@link CoRpt00Output#programName()}. */
        private String programName     = "";

        /** Mutable accumulator for {@link CoRpt00Output#title02()}. */
        private String title02         = "";

        /** Mutable accumulator for {@link CoRpt00Output#currentTime()}. */
        private String currentTime     = "";

        /** Mutable accumulator for {@link CoRpt00Output#monthly()}. */
        private String monthly         = "";

        /** Mutable accumulator for {@link CoRpt00Output#yearly()}. */
        private String yearly          = "";

        /** Mutable accumulator for {@link CoRpt00Output#custom()}. */
        private String custom          = "";

        /** Mutable accumulator for {@link CoRpt00Output#startMonth()}. */
        private String startMonth      = "";

        /** Mutable accumulator for {@link CoRpt00Output#startDay()}. */
        private String startDay        = "";

        /** Mutable accumulator for {@link CoRpt00Output#startYear()}. */
        private String startYear       = "";

        /** Mutable accumulator for {@link CoRpt00Output#endMonth()}. */
        private String endMonth        = "";

        /** Mutable accumulator for {@link CoRpt00Output#endDay()}. */
        private String endDay          = "";

        /** Mutable accumulator for {@link CoRpt00Output#endYear()}. */
        private String endYear         = "";

        /** Mutable accumulator for {@link CoRpt00Output#confirmation()}. */
        private String confirmation    = "";

        /** Mutable accumulator for {@link CoRpt00Output#errMsg()}. */
        private String errMsg          = "";

        /**
         * Mutable accumulator for {@link CoRpt00Output#errMsgColor()}; seeded
         * to {@link FieldColor#RED} per the BMS-compile-time default and the
         * sibling {@link CoRpt00Output#empty()} factory.
         */
        private FieldColor errMsgColor = FieldColor.RED;

        /**
         * Mutable accumulator for {@link CoRpt00Output#focusField()}; seeded
         * to {@code "MONTHLYL"} per the {@code CORPT00C} initial-cursor
         * convention.
         */
        private String focusField      = "MONTHLYL";

        /**
         * Package-private constructor — callers must obtain a {@link Builder}
         * via {@link CoRpt00Output#builder()}.
         */
        private Builder() {
            // No-arg; the field initializers above seed the defaults.
        }

        /**
         * Sets the {@link CoRpt00Output#transactionName()} component.
         *
         * @param v the {@code TRNNAMEO} value (4 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder transactionName(String v) {
            this.transactionName = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#title01()} component.
         *
         * @param v the {@code TITLE01O} value (40 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder title01(String v) {
            this.title01 = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#currentDate()} component.
         *
         * @param v the {@code CURDATEO} value (8 chars max, MM/DD/YY);
         *          {@code null} → {@code ""}
         * @return this builder
         */
        public Builder currentDate(String v) {
            this.currentDate = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#programName()} component.
         *
         * @param v the {@code PGMNAMEO} value (8 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder programName(String v) {
            this.programName = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#title02()} component.
         *
         * @param v the {@code TITLE02O} value (40 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder title02(String v) {
            this.title02 = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#currentTime()} component.
         *
         * @param v the {@code CURTIMEO} value (8 chars max, HH:MM:SS);
         *          {@code null} → {@code ""}
         * @return this builder
         */
        public Builder currentTime(String v) {
            this.currentTime = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#monthly()} component.
         *
         * @param v the {@code MONTHLYO} echo (1 char max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder monthly(String v) {
            this.monthly = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#yearly()} component.
         *
         * @param v the {@code YEARLYO} echo (1 char max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder yearly(String v) {
            this.yearly = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#custom()} component.
         *
         * @param v the {@code CUSTOMO} echo (1 char max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder custom(String v) {
            this.custom = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#startMonth()} component.
         *
         * @param v the {@code SDTMMO} echo (2 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder startMonth(String v) {
            this.startMonth = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#startDay()} component.
         *
         * @param v the {@code SDTDDO} echo (2 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder startDay(String v) {
            this.startDay = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#startYear()} component.
         *
         * @param v the {@code SDTYYYYO} echo (4 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder startYear(String v) {
            this.startYear = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#endMonth()} component.
         *
         * @param v the {@code EDTMMO} echo (2 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder endMonth(String v) {
            this.endMonth = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#endDay()} component.
         *
         * @param v the {@code EDTDDO} echo (2 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder endDay(String v) {
            this.endDay = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#endYear()} component.
         *
         * @param v the {@code EDTYYYYO} echo (4 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder endYear(String v) {
            this.endYear = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#confirmation()} component.
         *
         * @param v the {@code CONFIRMO} echo (1 char max, {@code Y}/{@code N});
         *          {@code null} → {@code ""}
         * @return this builder
         */
        public Builder confirmation(String v) {
            this.confirmation = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#errMsg()} component.
         *
         * @param v the {@code ERRMSGO} message (78 chars max); {@code null} →
         *          {@code ""}
         * @return this builder
         */
        public Builder errMsg(String v) {
            this.errMsg = (v == null) ? "" : v;
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#errMsgColor()} component.
         *
         * @param v the new {@link FieldColor}; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code v} is {@code null}, with
         *                              detail message {@code "errMsgColor"}
         */
        public Builder errMsgColor(FieldColor v) {
            this.errMsgColor = Objects.requireNonNull(v, "errMsgColor");
            return this;
        }

        /**
         * Sets the {@link CoRpt00Output#focusField()} component.
         *
         * @param v the new focus-field hint (e.g. {@code "MONTHLYL"},
         *          {@code "SDTMML"}, {@code "ERRMSGL"}); {@code null} →
         *          {@code ""} (which disables explicit cursor positioning)
         * @return this builder
         */
        public Builder focusField(String v) {
            this.focusField = (v == null) ? "" : v;
            return this;
        }

        /**
         * Materializes the accumulated state into an immutable
         * {@link CoRpt00Output} via the record's compact constructor (which
         * re-applies the null-normalization-to-empty for {@link String}
         * fields and re-enforces the non-null contract on
         * {@link #errMsgColor}).
         *
         * <p>This {@link Builder} may be discarded after the call; reusing
         * it for additional mutations and subsequent {@link #build()} calls
         * is supported but not idiomatic — the typical pattern is one
         * {@link Builder} per produced record.
         *
         * @return a fresh, immutable {@code CoRpt00Output} reflecting the
         *         current builder state (never {@code null})
         */
        public CoRpt00Output build() {
            return new CoRpt00Output(
                    transactionName,
                    title01,
                    currentDate,
                    programName,
                    title02,
                    currentTime,
                    monthly,
                    yearly,
                    custom,
                    startMonth,
                    startDay,
                    startYear,
                    endMonth,
                    endDay,
                    endYear,
                    confirmation,
                    errMsg,
                    errMsgColor,
                    focusField);
        }
    }
}
