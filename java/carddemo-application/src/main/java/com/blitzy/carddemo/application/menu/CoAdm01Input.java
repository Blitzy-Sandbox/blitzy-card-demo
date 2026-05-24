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
 * Input DTO for the admin menu BMS map ({@code COADM1AI}). Translates the
 * input-side symbolic structure from {@code app/cpy-bms/COADM01.CPY} and
 * the BMS field declarations in {@code app/bms/COADM01.bms}.
 *
 * <p>Conceptually corresponds to {@code EXEC CICS RECEIVE MAP('COADM1A')
 * MAPSET('COADM01') INTO(COADM1AI)} in {@code app/cbl/COADM01C.cbl}.
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.1.2 (COBOL {@code EXEC CICS SEND/RECEIVE MAP} translates
 * to method parameters and return values on the corresponding Java application
 * class) and &sect;0.4.1 (BMS maps become entry-contract DTO records placed
 * alongside the using application class), this record is the Java analog of
 * the input view of the BMS symbolic structure {@code COADM1AI}: it carries
 * the field values returned from {@code EXEC CICS RECEIVE MAP} to
 * {@code CoAdm01C}. There is no web framework, no Spring binding, no Jakarta
 * Bean Validation; this is a plain Java carrier built around finalized
 * Java&nbsp;25 language features only (records and JEP&nbsp;513 flexible
 * constructor bodies).
 *
 * <h2>Why only 10 option slots (vs. 12 in COMEN01)?</h2>
 * <p>Although the BMS source ({@code app/bms/COADM01.bms}) declares twelve
 * {@code OPTN0nn} display fields (lines 16-17 of the 24-line screen), the
 * Java DTO models exactly the ten slots that the admin program's static
 * lookup table ({@code COADM02Y} &rarr; {@code AdminMenuTable}) can
 * populate. Per AAP &sect;0.7.4 ("Phase 8: Forbidden / Required &mdash;
 * Required: [x] Exactly 10 option fields (no 11, 12)") this DTO surfaces
 * only {@code OPTN001I..OPTN010I}; the two trailing display-only slots are
 * always blank in COBOL output and therefore have no semantic content to
 * convey across the Java entry contract.
 *
 * <h2>Operator-editable field</h2>
 * <p>The only field the user actually populates is {@link #option()}
 * ({@code OPTIONI}, 2 chars): the two-digit menu selection that
 * {@code COADM01C} validates against the static admin menu table
 * {@code COADM02Y} and dispatches via {@code XCTL PROGRAM(...)}. Every
 * other component is an echo of what the admin program sent on the
 * previous {@code SEND MAP} cycle (header lines, option labels, prior
 * error message) and is included for round-trip fidelity with the
 * mainframe BMS contract.
 *
 * <h2>Component inventory (BMS field &rarr; record component)</h2>
 * <table border="1" summary="BMS-to-record component mapping">
 *   <thead>
 *     <tr><th>BMS field</th><th>BMS width / position</th>
 *         <th>Record component</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code TRNNAMEI}</td><td>4 chars, (1,7)</td>
 *         <td>{@link #trnName()}</td></tr>
 *     <tr><td>{@code TITLE01I}</td><td>40 chars, (1,21)</td>
 *         <td>{@link #title01()}</td></tr>
 *     <tr><td>{@code CURDATEI}</td><td>8 chars (mm/dd/yy), (1,71)</td>
 *         <td>{@link #curDate()}</td></tr>
 *     <tr><td>{@code PGMNAMEI}</td><td>8 chars, (2,7)</td>
 *         <td>{@link #pgmName()}</td></tr>
 *     <tr><td>{@code TITLE02I}</td><td>40 chars, (2,21)</td>
 *         <td>{@link #title02()}</td></tr>
 *     <tr><td>{@code CURTIMEI}</td><td>8 chars (hh:mm:ss), (2,71)</td>
 *         <td>{@link #curTime()}</td></tr>
 *     <tr><td>{@code OPTN001I}..{@code OPTN010I}</td>
 *         <td>40 chars each</td>
 *         <td>{@link #option001()}..{@link #option010()}</td></tr>
 *     <tr><td>{@code OPTIONI}</td>
 *         <td>2 chars, {@code UNPROT}, {@code NUM}</td>
 *         <td>{@link #option()} &mdash; <em>operator input</em></td></tr>
 *     <tr><td>{@code ERRMSGI}</td><td>78 chars</td>
 *         <td>{@link #errMsg()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Null contract (strict fail-fast)</h2>
 * <p>The compact canonical constructor uses
 * {@link Objects#requireNonNull(Object, String)} to fail-fast with a
 * {@link NullPointerException} naming the offending component if any of
 * the 18 String components is {@code null}. Callers that need a blank
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
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <p>No Spring, Lombok, Bean Validation, {@code java.util.Date},
 * {@code double}, {@code float}, or preview features.
 *
 * @param trnName    {@code TRNNAMEI} &mdash; 4-char transaction code echo
 *                   ({@code 'CA00'} for the admin menu)
 * @param title01    {@code TITLE01I} &mdash; 40-char line-1 title bar echo
 * @param curDate    {@code CURDATEI} &mdash; 8-char date echo (mm/dd/yy)
 * @param pgmName    {@code PGMNAMEI} &mdash; 8-char program-id echo
 *                   ({@code 'COADM01C'})
 * @param title02    {@code TITLE02I} &mdash; 40-char line-2 title bar echo
 * @param curTime    {@code CURTIMEI} &mdash; 8-char time echo (hh:mm:ss)
 * @param option001  {@code OPTN001I} &mdash; 40-char menu option&nbsp;1 label echo
 * @param option002  {@code OPTN002I} &mdash; 40-char menu option&nbsp;2 label echo
 * @param option003  {@code OPTN003I} &mdash; 40-char menu option&nbsp;3 label echo
 * @param option004  {@code OPTN004I} &mdash; 40-char menu option&nbsp;4 label echo
 * @param option005  {@code OPTN005I} &mdash; 40-char menu option&nbsp;5 label echo
 * @param option006  {@code OPTN006I} &mdash; 40-char menu option&nbsp;6 label echo
 * @param option007  {@code OPTN007I} &mdash; 40-char menu option&nbsp;7 label echo
 * @param option008  {@code OPTN008I} &mdash; 40-char menu option&nbsp;8 label echo
 * @param option009  {@code OPTN009I} &mdash; 40-char menu option&nbsp;9 label echo
 * @param option010  {@code OPTN010I} &mdash; 40-char menu option&nbsp;10 label echo
 * @param option     {@code OPTIONI} &mdash; 2-char admin menu selection input
 *                   (the only operator-editable field)
 * @param errMsg     {@code ERRMSGI} &mdash; 78-char error message echo
 *
 * @see CoAdm01Output
 * @see CoAdm01C
 * @since 1.0.0
 */
@CobolProgram(
        value = "COADM01",
        sourcePath = "app/cpy-bms/COADM01.CPY",
        translationDate = "2025-09-16",
        notes = "BMS input map COADM1AI fields. 10 option slots (vs 12 in COMEN01) "
                + "matching the COADM02Y admin-menu lookup table cardinality."
)
public record CoAdm01Input(
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
        String errMsg
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
     * {@code JUSTIFY=(RIGHT,ZERO)}). This is the only operator-editable
     * field on the admin menu screen.
     */
    public static final int OPTION_WIDTH = 2;

    /**
     * Width of {@link #errMsg()} per BMS field {@code ERRMSG}
     * ({@code LENGTH=78} at {@code POS=(23,1)}).
     */
    public static final int ERRMSG_WIDTH = 78;

    /**
     * Number of option-line slots surfaced by this DTO
     * ({@code OPTN001I..OPTN010I}). Ten slots match the
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
     * Compact canonical constructor enforcing the non-null contract
     * for all 18 record components. Java&nbsp;25 JEP&nbsp;513 permits
     * this validation to execute before the implicit canonical field
     * assignments.
     *
     * @throws NullPointerException if any component is {@code null}; the
     *         exception message names the offending component
     */
    public CoAdm01Input {
        Objects.requireNonNull(trnName,    "trnName");
        Objects.requireNonNull(title01,    "title01");
        Objects.requireNonNull(curDate,    "curDate");
        Objects.requireNonNull(pgmName,    "pgmName");
        Objects.requireNonNull(title02,    "title02");
        Objects.requireNonNull(curTime,    "curTime");
        Objects.requireNonNull(option001,  "option001");
        Objects.requireNonNull(option002,  "option002");
        Objects.requireNonNull(option003,  "option003");
        Objects.requireNonNull(option004,  "option004");
        Objects.requireNonNull(option005,  "option005");
        Objects.requireNonNull(option006,  "option006");
        Objects.requireNonNull(option007,  "option007");
        Objects.requireNonNull(option008,  "option008");
        Objects.requireNonNull(option009,  "option009");
        Objects.requireNonNull(option010,  "option010");
        Objects.requireNonNull(option,     "option");
        Objects.requireNonNull(errMsg,     "errMsg");
    }

    // ---------------------------------------------------------------
    // Static factories
    // ---------------------------------------------------------------

    /**
     * Returns a fully-blank input record &mdash; every {@link String}
     * component is the empty string {@code ""}. Useful as a starting
     * point before chaining {@code withX(...)} mutations to populate
     * specific fields, and as the canonical "blank cycle" value during
     * BMS adapter initialisation.
     *
     * @return a fresh empty {@code CoAdm01Input} (never {@code null})
     */
    public static CoAdm01Input empty() {
        return new CoAdm01Input(
                "", "", "", "", "", "",   // header (trnName, title01, curDate, pgmName, title02, curTime)
                "", "", "", "", "", "",   // option001-option006
                "", "", "", "",           // option007-option010
                "", ""                    // option, errMsg
        );
    }

    /**
     * Convenience factory: returns a blank input with only
     * {@link #option()} populated. Equivalent to
     * {@code empty().withOption(optionValue)} but documents intent
     * &mdash; the {@code option} component is the only field the
     * terminal user actually populates on the admin menu screen.
     *
     * @param optionValue the 2-char admin menu selection (non-null)
     * @return a new {@code CoAdm01Input} with {@link #option()} set
     *         and every other component blank (never {@code null})
     * @throws NullPointerException if {@code optionValue} is
     *         {@code null}
     */
    public static CoAdm01Input ofOption(String optionValue) {
        Objects.requireNonNull(optionValue, "optionValue");
        return empty().withOption(optionValue);
    }

    // ---------------------------------------------------------------
    // Fluent withX(...) update methods. One per record component
    // (18 total). Each returns a new immutable instance with the
    // single named field replaced; all other components are preserved
    // by reference. The canonical constructor re-validates non-null
    // on every call, so withX(null) throws.
    // ---------------------------------------------------------------

    /**
     * Returns a copy of this input with {@link #trnName()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withTrnName(String v) {
        return new CoAdm01Input(v, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #title01()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withTitle01(String v) {
        return new CoAdm01Input(trnName, v, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #curDate()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withCurDate(String v) {
        return new CoAdm01Input(trnName, title01, v, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #pgmName()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withPgmName(String v) {
        return new CoAdm01Input(trnName, title01, curDate, v, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #title02()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withTitle02(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, v, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #curTime()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withCurTime(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, v,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option001()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption001(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                v, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option002()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption002(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, v, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option003()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption003(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, v, option004, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option004()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption004(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, v, option005, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option005()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption005(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, v, option006,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option006()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption006(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, v,
                option007, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option007()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption007(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                v, option008, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option008()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption008(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, v, option009, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option009()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption009(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, v, option010,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option010()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption010(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, v,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option()} replaced.
     * This is the primary operator-input mutator: callers typically
     * invoke {@code withOption("01")} (or any 2-digit selection) on an
     * {@link #empty()} instance to simulate a terminal submission.
     *
     * @param v new admin menu selection value (non-null, typically
     *          {@code OPTION_WIDTH} chars after BMS padding)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withOption(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                v, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #errMsg()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoAdm01Input withErrMsg(String v) {
        return new CoAdm01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010,
                option, v);
    }
}
