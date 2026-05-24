/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.application.menu;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.util.Objects;

/**
 * Output DTO for the main menu BMS map ({@code COMEN1AO}). Translates the
 * output-side symbolic structure from {@code app/cpy-bms/COMEN01.CPY}
 * (the {@code 01 COMEN1AO REDEFINES COMEN1AI} group at lines 139-260) and
 * the field declarations in the BMS map source {@code app/bms/COMEN01.bms}.
 *
 * <p>Conceptually corresponds to {@code EXEC CICS SEND MAP('COMEN1A')
 * MAPSET('COMEN01') FROM(COMEN1AO)} in {@code app/cbl/COMEN01C.cbl}. The
 * Java translation does not actually transmit to a terminal; the record
 * value is the contract between the application class ({@link CoMen01C})
 * and any presentation adapter (e.g., a test harness, a 3270 emulator
 * integration, or a future web frontend). This is the entry-contract DTO
 * pattern mandated by AAP &sect;0.1.2 and &sect;0.4.1: BMS maps become
 * input/output records on the corresponding Java application class.
 *
 * <h2>Field semantics</h2>
 * <ul>
 *   <li>{@code trnName} &mdash; 4-char transaction ID echo (TRNNAMEO),
 *       e.g., {@code "CM00"}.</li>
 *   <li>{@code title01} &mdash; 40-char title line 1 (TITLE01O).</li>
 *   <li>{@code curDate} &mdash; 8-char current date {@code "mm/dd/yy"}
 *       (CURDATEO).</li>
 *   <li>{@code pgmName} &mdash; 8-char program name echo (PGMNAMEO),
 *       e.g., {@code "COMEN01C"}.</li>
 *   <li>{@code title02} &mdash; 40-char title line 2 (TITLE02O).</li>
 *   <li>{@code curTime} &mdash; 8-char current time {@code "hh:mm:ss"}
 *       (CURTIMEO).</li>
 *   <li>{@code option001} .. {@code option012} &mdash; 40-char menu
 *       option lines (OPTN001O..OPTN012O); each line is conventionally
 *       formatted as {@code "NN. <name>"} by {@code CoMen01C}.</li>
 *   <li>{@code option} &mdash; 2-char echoed option entry (OPTIONO).</li>
 *   <li>{@code errMsg} &mdash; 78-char error message (ERRMSGO).</li>
 *   <li>{@code errMsgColor} &mdash; 1-char BMS extended color attribute
 *       for ERRMSG (ERRMSGC). Default is the empty string (no override);
 *       the COBOL "coming soon" path sets this to DFHGREEN (X'04').</li>
 * </ul>
 *
 * <h2>String-only field model</h2>
 * <p>All twenty-one fields are stored as {@link String}. BMS fields are
 * {@code PIC X} (alphanumeric); even nominally numeric display fields
 * such as {@code option}, {@code curDate}, and {@code curTime} are
 * character-based on the 3270 datastream. Keeping every field as a
 * {@link String} preserves COBOL semantics directly and avoids the
 * accidental loss of leading zeros or fixed-width padding that would
 * occur with numeric Java types.
 *
 * <h2>Width handling</h2>
 * <p>The BMS-declared widths are documented as public {@code int}
 * constants on this record (see {@link #TRN_NAME_WIDTH},
 * {@link #TITLE_WIDTH}, {@link #DATE_TIME_WIDTH}, {@link #PGM_NAME_WIDTH},
 * {@link #OPTION_LINE_WIDTH}, {@link #OPTION_WIDTH},
 * {@link #ERRMSG_WIDTH}, {@link #COLOR_ATTR_WIDTH}) but are
 * <strong>not enforced</strong> by the canonical constructor &mdash; only
 * the non-null contract is. This matches COBOL {@code MOVE} semantics:
 * a {@code MOVE} of a shorter source pads with spaces; a {@code MOVE} of
 * a longer source truncates from the right. The application class
 * ({@link CoMen01C}) is responsible for padding/truncating to width
 * before populating these fields, so the canonical constructor avoids
 * silently mutating caller-supplied values.
 *
 * <h2>Twelve option slots vs. ten populated</h2>
 * <p>The BMS source ({@code app/bms/COMEN01.bms} lines 80-139) declares
 * twelve {@code OPTN001}..{@code OPTN012} display fields. The companion
 * COBOL program {@code COMEN01C} populates only the first ten (see
 * {@code MainMenuTable.OPT_COUNT}); unused slots are written as empty
 * strings, matching the COBOL {@code MOVE LOW-VALUES TO COMEN1AO}
 * initialization. This DTO mirrors the BMS map exactly (twelve slots)
 * rather than only the populated subset, in keeping with AAP
 * &sect;0.7.1's idiom-for-idiom mandate.
 *
 * <h2>Null safety</h2>
 * <p>Every component is non-null per the canonical constructor's
 * {@link Objects#requireNonNull(Object, String)} checks. Callers must
 * use the empty string ({@code ""}) for unset values rather than
 * {@code null}; see {@link #empty()} for a convenient all-blank factory.
 * This protocol mirrors COBOL: a BMS field is always physically present
 * in the symbolic structure; "absent" is represented by spaces or
 * low-values, never by null.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Java records are implicitly {@code final} with {@code final}
 * components and no setters; instances are safely shareable across
 * threads, including the virtual-thread workers described in AAP
 * &sect;0.6.6. The provided {@code with*} helpers return a new record
 * with one component swapped, preserving immutability while supporting
 * the COBOL {@code MOVE ... TO ...} pattern that this DTO replaces.
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <p>No Spring, Lombok, Bean Validation, {@code java.util.Date},
 * {@code double}, {@code float}, or preview features. The record uses
 * only Java&nbsp;25 finalized language features: record type (Java 16+)
 * and JEP&nbsp;513 Flexible Constructor Bodies for the non-null
 * validation that runs before the implicit canonical field assignment.
 *
 * @see CoMen01Input
 * @see CoMen01C
 * @since 1.0.0
 */
@CobolProgram(
        value = "COMEN01",
        sourcePath = "app/cpy-bms/COMEN01.CPY",
        translationDate = "2025-09-16",
        notes = "BMS output map COMEN1AO output-side fields. Translated from BMS symbolic "
                + "structure in COMEN01.CPY (REDEFINES COMEN1AI). Driven by online program "
                + "app/cbl/COMEN01C.cbl (main menu, transaction CM00). All fields are String "
                + "to preserve BMS PIC X semantics; widths are documented but not enforced by "
                + "the canonical constructor (per AAP §0.7.1 idiom-for-idiom translation)."
)
public record CoMen01Output(
        String trnName,
        String title01,
        String curDate,
        String pgmName,
        String title02,
        String curTime,
        String option001,
        String option002,
        String option003,
        String option004,
        String option005,
        String option006,
        String option007,
        String option008,
        String option009,
        String option010,
        String option011,
        String option012,
        String option,
        String errMsg,
        String errMsgColor
) {

    // ------------------------------------------------------------------
    // BMS field width constants (PIC X(n)) -- public for use by adapters
    // ------------------------------------------------------------------

    /** TRNNAME field width per BMS map ({@code PIC X(04)}). */
    public static final int TRN_NAME_WIDTH = 4;

    /** TITLE01 / TITLE02 field width per BMS map ({@code PIC X(40)}). */
    public static final int TITLE_WIDTH = 40;

    /** CURDATE / CURTIME field width per BMS map ({@code PIC X(08)}). */
    public static final int DATE_TIME_WIDTH = 8;

    /** PGMNAME field width per BMS map ({@code PIC X(08)}). */
    public static final int PGM_NAME_WIDTH = 8;

    /** OPTN001..OPTN012 field width per BMS map ({@code PIC X(40)}). */
    public static final int OPTION_LINE_WIDTH = 40;

    /** OPTION field width per BMS map ({@code PIC X(02)}). */
    public static final int OPTION_WIDTH = 2;

    /** ERRMSG field width per BMS map ({@code PIC X(78)}). */
    public static final int ERRMSG_WIDTH = 78;

    /** ERRMSGC color attribute byte width (1 char). */
    public static final int COLOR_ATTR_WIDTH = 1;

    // ------------------------------------------------------------------
    // Canonical constructor (JEP 513 Flexible Constructor Bodies)
    // ------------------------------------------------------------------

    /**
     * Canonical (compact) constructor. Uses JEP&nbsp;513 Flexible
     * Constructor Bodies (finalized in Java&nbsp;25): the validation
     * statements execute before the implicit canonical field
     * assignments, ensuring that no partially-constructed record can
     * ever exist with a {@code null} component.
     *
     * <p>All twenty-one components are validated non-null via
     * {@link Objects#requireNonNull(Object, String)}. Callers must use
     * the empty string ({@code ""}) to indicate "unset"; {@code null}
     * is rejected with {@link NullPointerException} carrying a
     * descriptive message identifying which component was null.
     *
     * <p>Width validation is intentionally NOT performed here. COBOL
     * {@code MOVE} semantics permit any-length source: shorter sources
     * are space-padded and longer sources are truncated at the target
     * field. The application class manages that contract; this
     * constructor only refuses null. See the class-level Javadoc for
     * the rationale.
     *
     * @throws NullPointerException if any of the twenty-one components
     *                              is {@code null}; the exception
     *                              message identifies the offending
     *                              component name
     */
    public CoMen01Output {
        Objects.requireNonNull(trnName,     "trnName");
        Objects.requireNonNull(title01,     "title01");
        Objects.requireNonNull(curDate,     "curDate");
        Objects.requireNonNull(pgmName,     "pgmName");
        Objects.requireNonNull(title02,     "title02");
        Objects.requireNonNull(curTime,     "curTime");
        Objects.requireNonNull(option001,   "option001");
        Objects.requireNonNull(option002,   "option002");
        Objects.requireNonNull(option003,   "option003");
        Objects.requireNonNull(option004,   "option004");
        Objects.requireNonNull(option005,   "option005");
        Objects.requireNonNull(option006,   "option006");
        Objects.requireNonNull(option007,   "option007");
        Objects.requireNonNull(option008,   "option008");
        Objects.requireNonNull(option009,   "option009");
        Objects.requireNonNull(option010,   "option010");
        Objects.requireNonNull(option011,   "option011");
        Objects.requireNonNull(option012,   "option012");
        Objects.requireNonNull(option,      "option");
        Objects.requireNonNull(errMsg,      "errMsg");
        Objects.requireNonNull(errMsgColor, "errMsgColor");
    }

    // ------------------------------------------------------------------
    // Static factory: empty output (Java analog of MOVE LOW-VALUES)
    // ------------------------------------------------------------------

    /**
     * Returns a fully-blank output record: every component is the empty
     * string {@code ""}. The Java equivalent of
     * {@code MOVE LOW-VALUES TO COMEN1AO} performed by {@code COMEN01C}
     * prior to populating header fields on each transaction cycle.
     *
     * <p>This factory is the recommended starting point for building an
     * outbound DTO: start from {@code empty()}, then chain {@code with*}
     * calls to populate the desired fields. Unused option slots remain
     * blank, matching the COBOL behavior for menus that populate fewer
     * than twelve options.
     *
     * @return a fresh empty {@code CoMen01Output} (never {@code null})
     */
    public static CoMen01Output empty() {
        return new CoMen01Output(
                "",  // trnName
                "",  // title01
                "",  // curDate
                "",  // pgmName
                "",  // title02
                "",  // curTime
                "",  // option001
                "",  // option002
                "",  // option003
                "",  // option004
                "",  // option005
                "",  // option006
                "",  // option007
                "",  // option008
                "",  // option009
                "",  // option010
                "",  // option011
                "",  // option012
                "",  // option
                "",  // errMsg
                ""   // errMsgColor
        );
    }

    // ------------------------------------------------------------------
    // Fluent "with*" copy helpers -- one per component (21 total).
    //
    // Java records do not provide built-in with* methods (per AAP §0.1.2
    // transformation table). Each helper returns a new immutable record
    // with the specified component replaced and all other components
    // preserved. This pattern is the Java translation of the COBOL
    // MOVE ... TO ... (group-element) idiom.
    // ------------------------------------------------------------------

    /**
     * Returns a copy of this record with {@link #trnName()} replaced.
     *
     * @param v the new transaction-name value (non-null; pass
     *          {@code ""} to clear); must be non-null per the
     *          canonical-constructor contract
     * @return a new {@code CoMen01Output} with {@code trnName == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withTrnName(String v) {
        return new CoMen01Output(
                v, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #title01()} replaced.
     *
     * @param v the new line-1 title value (non-null; pass {@code ""}
     *          to clear)
     * @return a new {@code CoMen01Output} with {@code title01 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withTitle01(String v) {
        return new CoMen01Output(
                trnName, v, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #curDate()} replaced.
     *
     * @param v the new current-date value (non-null; pass {@code ""}
     *          to clear); conventionally formatted {@code "mm/dd/yy"}
     * @return a new {@code CoMen01Output} with {@code curDate == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withCurDate(String v) {
        return new CoMen01Output(
                trnName, title01, v, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #pgmName()} replaced.
     *
     * @param v the new program-name value (non-null; pass {@code ""}
     *          to clear)
     * @return a new {@code CoMen01Output} with {@code pgmName == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withPgmName(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, v, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #title02()} replaced.
     *
     * @param v the new line-2 title value (non-null; pass {@code ""}
     *          to clear)
     * @return a new {@code CoMen01Output} with {@code title02 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withTitle02(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, v, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #curTime()} replaced.
     *
     * @param v the new current-time value (non-null; pass {@code ""}
     *          to clear); conventionally formatted {@code "hh:mm:ss"}
     * @return a new {@code CoMen01Output} with {@code curTime == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withCurTime(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, v,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option001()} replaced.
     *
     * @param v the new menu option line 1 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option001 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption001(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                v, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option002()} replaced.
     *
     * @param v the new menu option line 2 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option002 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption002(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, v, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option003()} replaced.
     *
     * @param v the new menu option line 3 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option003 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption003(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, v, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option004()} replaced.
     *
     * @param v the new menu option line 4 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option004 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption004(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, v, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option005()} replaced.
     *
     * @param v the new menu option line 5 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option005 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption005(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, v, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option006()} replaced.
     *
     * @param v the new menu option line 6 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option006 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption006(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, v,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option007()} replaced.
     *
     * @param v the new menu option line 7 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option007 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption007(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                v, option008, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option008()} replaced.
     *
     * @param v the new menu option line 8 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option008 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption008(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, v, option009, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option009()} replaced.
     *
     * @param v the new menu option line 9 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option009 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption009(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, v, option010, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option010()} replaced.
     *
     * @param v the new menu option line 10 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option010 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption010(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, v, option011, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option011()} replaced.
     *
     * @param v the new menu option line 11 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option011 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption011(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, v, option012,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option012()} replaced.
     *
     * @param v the new menu option line 12 value (non-null; pass
     *          {@code ""} to clear)
     * @return a new {@code CoMen01Output} with {@code option012 == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption012(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, v,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #option()} (the echoed
     * 2-character option selection) replaced.
     *
     * @param v the new option-echo value (non-null; pass {@code ""}
     *          to clear)
     * @return a new {@code CoMen01Output} with {@code option == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withOption(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                v, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #errMsg()} (the
     * 78-character ERRMSG payload) replaced. Note that the BMS color
     * attribute {@link #errMsgColor()} is independent; use
     * {@link #withErrMsgColor(String)} to update it.
     *
     * @param v the new error-message text (non-null; pass {@code ""}
     *          to clear)
     * @return a new {@code CoMen01Output} with {@code errMsg == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withErrMsg(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, v, errMsgColor);
    }

    /**
     * Returns a copy of this record with {@link #errMsgColor()} (the
     * 1-character ERRMSGC color attribute byte) replaced.
     *
     * <p>The COBOL {@code COMEN01C} "coming soon" path sets this to
     * DFHGREEN (X'04') to render the message in green; the error path
     * sets it to DFHRED (X'02'); the no-message path leaves it as the
     * empty string (the BMS map default).
     *
     * @param v the new color attribute byte as a 1-character string
     *          (non-null; pass {@code ""} for no override)
     * @return a new {@code CoMen01Output} with {@code errMsgColor == v}
     *         and all other components preserved
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Output withErrMsgColor(String v) {
        return new CoMen01Output(
                trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg, v);
    }
}
