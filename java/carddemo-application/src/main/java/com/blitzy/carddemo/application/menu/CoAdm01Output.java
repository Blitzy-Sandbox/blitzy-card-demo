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
 * Output DTO for the admin menu BMS map ({@code COADM1AO}). Translates the
 * output-side symbolic structure {@code 01 COADM1AO REDEFINES COADM1AI} from
 * {@code app/cpy-bms/COADM01.CPY} (lines 139-260) and the {@code DFHMDF}
 * field declarations in {@code app/bms/COADM01.bms}.
 *
 * <p>Conceptually corresponds to {@code EXEC CICS SEND MAP('COADM1A')
 * MAPSET('COADM01') FROM(COADM1AO)} in {@code app/cbl/COADM01C.cbl}: the
 * field values that {@code CoAdm01C} writes back to the 3270 screen.
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.1.2 (COBOL {@code EXEC CICS SEND/RECEIVE MAP} translates
 * to method parameters and return values on the corresponding Java
 * application class) and &sect;0.4.1 (BMS maps become entry-contract DTO
 * records placed alongside the using application class), this record is the
 * Java analog of the output view of the BMS symbolic structure
 * {@code COADM1AO}. There is no web framework, no Spring binding, no
 * Jakarta Bean Validation; this is a plain Java carrier built around
 * finalized Java&nbsp;25 language features only (records and JEP&nbsp;513
 * flexible constructor bodies).
 *
 * <h2>Why only 10 option slots (vs. 12 in COMEN01)</h2>
 * <p>Although the BMS source {@code app/bms/COADM01.bms} declares twelve
 * {@code OPTN0nn} display fields (OPTN001 through OPTN012, at screen rows
 * 6 through 17), the Java DTO models exactly the ten slots that the admin
 * program's static lookup table ({@code COADM02Y} &rarr;
 * {@link com.blitzy.carddemo.domain.menu.AdminMenuTable}) can populate.
 * Per AAP &sect;0.7.4 ("Phase 8: Forbidden / Required &mdash; Required:
 * Exactly 10 option fields (no 11, 12)") this DTO surfaces only
 * {@code OPTN001O..OPTN010O}; the two trailing display-only slots are
 * always blank in COBOL output and therefore have no semantic content to
 * convey across the Java entry contract. This makes {@code CoAdm01Output}
 * structurally symmetric with {@link CoAdm01Input} (which also surfaces
 * ten option slots), so the input/output pair on the admin menu screen
 * has matching cardinality.
 *
 * <h2>The {@code errMsgColor} attribute</h2>
 * <p>The COBOL admin menu program {@code app/cbl/COADM01C.cbl} does
 * <strong>not</strong> programmatically set the {@code ERRMSGC} attribute
 * byte (unlike {@code COMEN01C}, which uses {@code MOVE DFHGREEN TO
 * ERRMSGC} on the coming-soon path); {@code COADM01C}'s coming-soon path
 * leaves the option-name {@code MOVE} commented out and does not touch
 * {@code ERRMSGC}. The {@link #errMsgColor()} component is preserved here
 * for parity with the BMS symbolic structure ({@code ERRMSGC PICTURE X}
 * at {@code app/cpy-bms/COADM01.CPY:256}) and for use by future code
 * paths; in current admin-menu output it is always the empty string.
 * Per the COBOL convention, an empty string means "leave at BMS
 * compile-time {@code COLOR=} default (red on black for {@code ERRMSG})".
 *
 * <h2>Component inventory (BMS field &rarr; record component)</h2>
 * <table border="1">
 * <caption>BMS-to-record component mapping</caption>
 *   <thead>
 *     <tr><th>BMS field</th><th>BMS width / position</th>
 *         <th>Record component</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code TRNNAMEO}</td><td>4 chars, (1,7)</td>
 *         <td>{@link #trnName()}</td></tr>
 *     <tr><td>{@code TITLE01O}</td><td>40 chars, (1,21)</td>
 *         <td>{@link #title01()}</td></tr>
 *     <tr><td>{@code CURDATEO}</td><td>8 chars (mm/dd/yy), (1,71)</td>
 *         <td>{@link #curDate()}</td></tr>
 *     <tr><td>{@code PGMNAMEO}</td><td>8 chars, (2,7)</td>
 *         <td>{@link #pgmName()}</td></tr>
 *     <tr><td>{@code TITLE02O}</td><td>40 chars, (2,21)</td>
 *         <td>{@link #title02()}</td></tr>
 *     <tr><td>{@code CURTIMEO}</td><td>8 chars (hh:mm:ss), (2,71)</td>
 *         <td>{@link #curTime()}</td></tr>
 *     <tr><td>{@code OPTN001O}..{@code OPTN010O}</td>
 *         <td>40 chars each, (6,20)..(15,20)</td>
 *         <td>{@link #option001()}..{@link #option010()}</td></tr>
 *     <tr><td>{@code OPTIONO}</td>
 *         <td>2 chars, (20,41)</td>
 *         <td>{@link #option()}</td></tr>
 *     <tr><td>{@code ERRMSGO}</td><td>78 chars, (23,1)</td>
 *         <td>{@link #errMsg()}</td></tr>
 *     <tr><td>{@code ERRMSGC}</td><td>1 attribute byte</td>
 *         <td>{@link #errMsgColor()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Null contract (strict fail-fast)</h2>
 * <p>The compact canonical constructor uses
 * {@link Objects#requireNonNull(Object, String)} to fail-fast with a
 * {@link NullPointerException} naming the offending component if any of
 * the 19 String components is {@code null}. Callers that need a blank
 * starting point should use {@link #empty()} instead of passing
 * {@code null}.
 *
 * <h2>Java&nbsp;25 features used</h2>
 * <ul>
 *   <li>Record types (Java&nbsp;16+, mandated by AAP &sect;0.6.7)</li>
 *   <li>JEP&nbsp;513 Flexible Constructor Bodies (finalized in
 *       Java&nbsp;25): validation runs before canonical assignments</li>
 *   <li>{@link CobolProgram @CobolProgram} traceability annotation
 *       (AAP &sect;0.7.1)</li>
 * </ul>
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Records, final components, no setters &mdash; safe to share across
 * threads including the virtual-thread workers per AAP &sect;0.6.6.
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <p>No Spring, Lombok, Bean Validation, {@code java.util.Date},
 * {@code double}, {@code float}, or preview features.
 *
 * @param trnName      {@code TRNNAMEO} &mdash; 4-char transaction code echo
 *                     ({@code 'CA00'} for the admin menu)
 * @param title01      {@code TITLE01O} &mdash; 40-char line-1 title bar
 * @param curDate      {@code CURDATEO} &mdash; 8-char date {@code "mm/dd/yy"}
 * @param pgmName      {@code PGMNAMEO} &mdash; 8-char program-id echo
 *                     ({@code 'COADM01C'})
 * @param title02      {@code TITLE02O} &mdash; 40-char line-2 title bar
 * @param curTime      {@code CURTIMEO} &mdash; 8-char time {@code "hh:mm:ss"}
 * @param option001    {@code OPTN001O} &mdash; 40-char admin option&nbsp;1 label
 * @param option002    {@code OPTN002O} &mdash; 40-char admin option&nbsp;2 label
 * @param option003    {@code OPTN003O} &mdash; 40-char admin option&nbsp;3 label
 * @param option004    {@code OPTN004O} &mdash; 40-char admin option&nbsp;4 label
 * @param option005    {@code OPTN005O} &mdash; 40-char admin option&nbsp;5 label
 * @param option006    {@code OPTN006O} &mdash; 40-char admin option&nbsp;6 label
 * @param option007    {@code OPTN007O} &mdash; 40-char admin option&nbsp;7 label
 * @param option008    {@code OPTN008O} &mdash; 40-char admin option&nbsp;8 label
 * @param option009    {@code OPTN009O} &mdash; 40-char admin option&nbsp;9 label
 * @param option010    {@code OPTN010O} &mdash; 40-char admin option&nbsp;10 label
 * @param option       {@code OPTIONO} &mdash; 2-char admin menu selection echo
 * @param errMsg       {@code ERRMSGO} &mdash; 78-char error message
 * @param errMsgColor  {@code ERRMSGC} &mdash; 1-char extended-color attribute
 *                     override (empty string = leave at BMS default)
 *
 * @see CoAdm01Input
 * @see CoAdm01C
 * @see com.blitzy.carddemo.domain.menu.AdminMenuTable
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COADM01",
        sourcePath = "app/cpy-bms/COADM01.CPY",
        translationDate = "2025-09-16",
        notes = "BMS output map COADM1AO output-side fields. 10 option slots (vs 12 in COMEN01)."
)
public record CoAdm01Output(
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
        String option,
        String errMsg,
        String errMsgColor
) {

    // ---------------------------------------------------------------
    // BMS width constants (chars). Sourced from app/bms/COADM01.bms.
    // Public so callers (BMS reader/writer adapters, tests, golden-
    // record harness) can pad/truncate exactly to the on-wire widths.
    // ---------------------------------------------------------------

    /**
     * Width of {@link #trnName()} per BMS field {@code TRNNAME}
     * ({@code LENGTH=4} at {@code POS=(1,7)}).
     */
    public static final int TRN_NAME_WIDTH = 4;

    /**
     * Width of {@link #title01()} and {@link #title02()} per BMS fields
     * {@code TITLE01} / {@code TITLE02} ({@code LENGTH=40}).
     */
    public static final int TITLE_WIDTH = 40;

    /**
     * Width of {@link #curDate()} and {@link #curTime()} per BMS fields
     * {@code CURDATE} / {@code CURTIME} ({@code LENGTH=8}).
     */
    public static final int DATE_TIME_WIDTH = 8;

    /**
     * Width of {@link #pgmName()} per BMS field {@code PGMNAME}
     * ({@code LENGTH=8} at {@code POS=(2,7)}).
     */
    public static final int PGM_NAME_WIDTH = 8;

    /**
     * Width of each menu-option label slot per BMS fields
     * {@code OPTN001}..{@code OPTN010} ({@code LENGTH=40}).
     */
    public static final int OPTION_LINE_WIDTH = 40;

    /**
     * Width of {@link #option()} per BMS field {@code OPTION}
     * ({@code LENGTH=2}, {@code UNPROT}, {@code NUM}, {@code IC},
     * {@code JUSTIFY=(RIGHT,ZERO)}).
     */
    public static final int OPTION_WIDTH = 2;

    /**
     * Width of {@link #errMsg()} per BMS field {@code ERRMSG}
     * ({@code LENGTH=78} at {@code POS=(23,1)}).
     */
    public static final int ERRMSG_WIDTH = 78;

    /**
     * Width of the {@code ERRMSGC} BMS extended-color attribute byte,
     * which carries a single character such as {@code 'R'} (red),
     * {@code 'G'} (green), or {@code ' '} (no override). One char.
     */
    public static final int COLOR_ATTR_WIDTH = 1;

    /**
     * Number of option-line slots surfaced by this DTO
     * ({@code OPTN001O..OPTN010O}). Ten slots match the
     * {@code COADM02Y} admin-menu lookup table cardinality (vs. twelve
     * for the main menu). See class Javadoc &mdash; "Why only 10 option
     * slots".
     */
    public static final int OPTION_LINE_COUNT = 10;

    // ---------------------------------------------------------------
    // Compact canonical constructor (JEP 513 Flexible Constructor
    // Bodies, finalized in Java 25). Per AAP §0.6.3, the right place
    // for COBOL-style fail-fast input validation: every component is
    // checked before the canonical field assignments.
    // ---------------------------------------------------------------

    /**
     * Compact canonical constructor enforcing the non-null contract for
     * all 19 record components. Java&nbsp;25 JEP&nbsp;513 (Flexible
     * Constructor Bodies) permits this validation to execute before the
     * implicit canonical field assignments. Use empty string for unset
     * fields; never {@code null}.
     *
     * @throws NullPointerException if any component is {@code null}; the
     *         exception message names the offending component
     */
    public CoAdm01Output {
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
        Objects.requireNonNull(option,      "option");
        Objects.requireNonNull(errMsg,      "errMsg");
        Objects.requireNonNull(errMsgColor, "errMsgColor");
    }

    // ---------------------------------------------------------------
    // Static factories
    // ---------------------------------------------------------------

    /**
     * Creates an empty {@code CoAdm01Output} with all 19 components as
     * empty {@link String}s. Java equivalent of {@code MOVE LOW-VALUES TO
     * COADM1AO} performed by {@code COADM01C} prior to populating the
     * header on each cycle. Useful as a starting point before chaining
     * {@code withX(...)} mutations to populate specific fields.
     *
     * @return a fresh empty {@code CoAdm01Output} (never {@code null})
     */
    public static CoAdm01Output empty() {
        return new CoAdm01Output(
                "", "", "", "", "", "",   // header: trnName, title01, curDate, pgmName, title02, curTime
                "", "", "", "", "", "",   // option001-option006
                "", "", "", "",           // option007-option010
                "", "", ""                // option, errMsg, errMsgColor
        );
    }

    // ---------------------------------------------------------------
    // Fluent withX(...) update methods. One per record component
    // (19 total = 6 header + 10 option lines + option + errMsg
    // + errMsgColor). Each returns a new immutable instance with the
    // single named field replaced; all other components are preserved
    // by reference. The canonical constructor re-validates non-null
    // on every call, so withX(null) throws.
    // ---------------------------------------------------------------

    /**
     * Returns a copy of this output with {@link #trnName()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withTrnName(String v) {
        return new CoAdm01Output(v, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #title01()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withTitle01(String v) {
        return new CoAdm01Output(trnName, v, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #curDate()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withCurDate(String v) {
        return new CoAdm01Output(trnName, title01, v, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #pgmName()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withPgmName(String v) {
        return new CoAdm01Output(trnName, title01, curDate, v, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #title02()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withTitle02(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, v, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #curTime()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withCurTime(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, v,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option001()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption001(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                v, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option002()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption002(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, v, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option003()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption003(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, v, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option004()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption004(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, v, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option005()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption005(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, v, option006,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option006()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption006(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, v,
                option007, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option007()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption007(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                v, option008, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option008()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption008(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, v, option009, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option009()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption009(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, v, option010,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option010()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption010(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, v,
                option, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #option()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withOption(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                v, errMsg, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #errMsg()} replaced.
     * Note: unlike {@code COMEN01C}, the admin program does
     * <strong>not</strong> set {@link #errMsgColor()} programmatically;
     * to update both error message and color use
     * {@code .withErrMsg(...).withErrMsgColor(...)}.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withErrMsg(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, v, errMsgColor);
    }

    /**
     * Returns a copy of this output with {@link #errMsgColor()} replaced.
     * The value is the {@code ERRMSGC} 1-char extended-color attribute
     * (e.g. {@code "R"} for red, {@code "G"} for green). An empty string
     * means "leave at BMS compile-time {@code COLOR=} default".
     *
     * @param v new value (non-null; use empty string for "no override")
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Output withErrMsgColor(String v) {
        return new CoAdm01Output(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg, v);
    }
}
