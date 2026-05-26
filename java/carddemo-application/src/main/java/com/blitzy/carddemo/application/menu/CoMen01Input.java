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
 * Input DTO for the main menu BMS map ({@code COMEN1AI}). Translates the
 * input-side symbolic structure from {@code app/cpy-bms/COMEN01.CPY} and the
 * BMS field declarations in {@code app/bms/COMEN01.bms}.
 *
 * <p>Conceptually corresponds to {@code EXEC CICS RECEIVE MAP('COMEN1A')
 * MAPSET('COMEN01') INTO(COMEN1AI)} in {@code app/cbl/COMEN01C.cbl}. The Java
 * translation does not actually read from a terminal; the record value is the
 * contract between the application class ({@link CoMen01C}) and any
 * presentation adapter (a test harness, a 3270 emulator integration, or a
 * future web frontend).
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.1.2 (COBOL {@code EXEC CICS SEND/RECEIVE MAP} translates
 * to method parameters and return values on the corresponding Java application
 * class) and &sect;0.4.1 (BMS maps become entry-contract DTO records placed
 * alongside the using application class), this record is the Java analog of
 * the input view of the BMS symbolic structure {@code COMEN1AI}: it carries
 * the field values returned from {@code EXEC CICS RECEIVE MAP} to
 * {@code CoMen01C}. There is no web framework, no Spring binding, no Jakarta
 * Bean Validation; this is a plain Java carrier built around finalized
 * Java&nbsp;25 language features only (records and JEP&nbsp;513 flexible
 * constructor bodies).
 *
 * <h2>Why twelve option slots (vs. ten in COADM01)?</h2>
 * <p>The BMS source ({@code app/bms/COMEN01.bms} lines 80-139) declares
 * twelve {@code OPTN001}..{@code OPTN012} display fields, one per option line.
 * The companion COBOL program {@code COMEN01C} populates only the first ten
 * slots from the static lookup table {@code COMEN02Y}
 * ({@link com.blitzy.carddemo.domain.menu.MainMenuTable}) but the BMS map
 * surface is a fixed 12 slots, so this DTO faithfully models all twelve to
 * mirror the symbolic structure exactly (AAP &sect;0.4.1 field-for-field
 * translation mandate).
 *
 * <h2>Operator-editable field</h2>
 * <p>The only field the user actually populates is {@link #option()}
 * ({@code OPTIONI}, 2 chars, declared numeric on the BMS map with
 * {@code JUSTIFY=(RIGHT,ZERO)}): the two-digit menu selection (e.g.
 * {@code "01"}, {@code "02"} ... {@code "12"}) corresponding to one of the
 * twelve option labels echoed in {@link #option001()} through
 * {@link #option012()}. The COBOL program {@code PROCESS-ENTER-KEY}
 * paragraph validates the selection against the static lookup table
 * {@code COMEN02Y} and dispatches via {@code XCTL PROGRAM(...)}. Every
 * other component is an echo of what the menu program sent on the
 * previous {@code SEND MAP} cycle (header lines, option labels, prior
 * error message) and is included for round-trip fidelity with the
 * mainframe BMS contract.
 *
 * <h2>Component inventory (BMS field &rarr; record component)</h2>
 * <table border="1">
 * <caption>BMS-to-record component mapping</caption>
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
 *     <tr><td>{@code OPTN001I}..{@code OPTN012I}</td>
 *         <td>40 chars each, (6,20)..(17,20)</td>
 *         <td>{@link #option001()}..{@link #option012()}</td></tr>
 *     <tr><td>{@code OPTIONI}</td>
 *         <td>2 chars, {@code UNPROT}, {@code NUM}, {@code IC},
 *             {@code JUSTIFY=(RIGHT,ZERO)}, (20,41)</td>
 *         <td>{@link #option()} &mdash; <em>operator input</em></td></tr>
 *     <tr><td>{@code ERRMSGI}</td><td>78 chars, (23,1)</td>
 *         <td>{@link #errMsg()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Null contract (strict fail-fast)</h2>
 * <p>The compact canonical constructor uses
 * {@link Objects#requireNonNull(Object, String)} to fail-fast with a
 * {@link NullPointerException} naming the offending component if any of the
 * 20 String components is {@code null}. Callers that need a blank starting
 * point should use {@link #empty()} instead of passing {@code null}. Callers
 * that only have an option value should use {@link #ofOption(String)}.
 *
 * <p>This strict contract matches the canonical sibling
 * {@link CoAdm01Input} and the broader CardDemo BMS-DTO convention. No
 * normalisation (no null-to-blank, no truncation) happens in the
 * constructor: BMS layer responsibilities (truncation to declared widths,
 * SPACE padding) live in the adapter layer that materialises this record
 * from on-wire bytes.
 *
 * <h2>Trailing-space and padding semantics</h2>
 * <p>BMS fixed-width fields arrive padded with {@code SPACE} ({@code 0x20})
 * to the declared width. Per AAP &sect;0.7.1, preserving that padding is
 * essential to round-trip byte fidelity. This DTO carries the strings
 * verbatim &mdash; including trailing spaces &mdash; and does not trim them.
 * Application logic that needs the trimmed user-typed value (most notably
 * the {@code PROCESS-ENTER-KEY} paragraph in {@code CoMen01C}) is
 * responsible for performing its own trim/parse step, exactly as the COBOL
 * paragraph does via {@code PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI
 * BY -1 UNTIL OPTIONI(WS-IDX:1) NOT = SPACES OR WS-IDX = 1} followed by
 * {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}.
 *
 * <h2>AID-key dispatch &mdash; separate parameter</h2>
 * <p>Per the canonical sibling pattern established by
 * {@link CoAdm01Input} / {@code CoAdm01C}, the AID key (ENTER, PF3, etc.)
 * is <strong>not</strong> a component of this DTO. The 3270 attention
 * identifier is decoded by the BMS adapter and passed to
 * {@link CoMen01C#process(com.blitzy.carddemo.domain.commarea.CardDemoCommarea,
 * CoMen01Input, byte, int)} as a separate parameter, drawing from the central
 * {@code com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey} sealed
 * hierarchy (AAP &sect;0.6.10). This avoids duplicating the AID-key
 * taxonomy across every BMS DTO and matches AAP &sect;0.6.7's "sealed
 * types for every 88-level taxonomy partitioning a value space" mandate.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final}
 * and accessors are auto-generated; there are no setters and no mutable
 * internal state. The record is therefore safe to share across threads
 * (including the virtual-thread workers mandated by AAP &sect;0.6.6)
 * without synchronisation. Mutations are expressed via fluent
 * {@code withX(...)} copies that return new instances.
 *
 * <h2>Non-goals (faithful to AAP &sect;0.7.4 "explicitly forbidden")</h2>
 * <ul>
 *   <li>No Spring or Jakarta annotations &mdash; this is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in
 *       the compact constructor (see AAP &sect;0.6.3 / JEP&nbsp;513).</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} &mdash;
 *       date and time components are pre-formatted BMS display strings.</li>
 *   <li>No {@code double} or {@code float}.</li>
 *   <li>No nested {@code AidKey} type &mdash; the AID key is a separate
 *       parameter to {@code CoMen01C#run} drawing from the central
 *       {@code CcWorkAreas.AidKey} sealed hierarchy.</li>
 *   <li>No preview Java features &mdash; only finalized Java&nbsp;25
 *       features (records, JEP&nbsp;513 flexible constructor bodies).</li>
 * </ul>
 *
 * @param trnName    {@code TRNNAMEI} &mdash; 4-char transaction code echo
 *                   (typically {@code "CM00"})
 * @param title01    {@code TITLE01I} &mdash; 40-char line-1 title bar echo
 * @param curDate    {@code CURDATEI} &mdash; 8-char date {@code "mm/dd/yy"} echo
 * @param pgmName    {@code PGMNAMEI} &mdash; 8-char program-id echo
 *                   (typically {@code "COMEN01C"})
 * @param title02    {@code TITLE02I} &mdash; 40-char line-2 title bar echo
 * @param curTime    {@code CURTIMEI} &mdash; 8-char time {@code "hh:mm:ss"} echo
 * @param option001  {@code OPTN001I} &mdash; 40-char menu option 1 label echo
 * @param option002  {@code OPTN002I} &mdash; 40-char menu option 2 label echo
 * @param option003  {@code OPTN003I} &mdash; 40-char menu option 3 label echo
 * @param option004  {@code OPTN004I} &mdash; 40-char menu option 4 label echo
 * @param option005  {@code OPTN005I} &mdash; 40-char menu option 5 label echo
 * @param option006  {@code OPTN006I} &mdash; 40-char menu option 6 label echo
 * @param option007  {@code OPTN007I} &mdash; 40-char menu option 7 label echo
 * @param option008  {@code OPTN008I} &mdash; 40-char menu option 8 label echo
 * @param option009  {@code OPTN009I} &mdash; 40-char menu option 9 label echo
 * @param option010  {@code OPTN010I} &mdash; 40-char menu option 10 label echo
 * @param option011  {@code OPTN011I} &mdash; 40-char menu option 11 label echo
 *                   (typically blank &mdash; COMEN02Y has 10 entries)
 * @param option012  {@code OPTN012I} &mdash; 40-char menu option 12 label echo
 *                   (typically blank &mdash; COMEN02Y has 10 entries)
 * @param option     {@code OPTIONI} &mdash; 2-char menu selection input
 *                   (operator-typed; the only meaningfully-populated field
 *                   on input)
 * @param errMsg     {@code ERRMSGI} &mdash; 78-char error message echo
 *
 * @see CoMen01C
 * @see CoMen01Output
 * @see com.blitzy.carddemo.domain.menu.MainMenuTable
 * @since 1.0.0
 */
@CobolProgram(
        value = "COMEN01",
        sourcePath = "app/cpy-bms/COMEN01.CPY",
        translationDate = "2025-09-16",
        notes = "BMS input map COMEN1AI fields. 12 option slots (vs 10 in COADM01) "
                + "matching the BMS map's twelve declared OPTN001..OPTN012 "
                + "display fields; only the first 10 are typically populated "
                + "from the COMEN02Y main-menu lookup table."
)
public record CoMen01Input(
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
        String errMsg
) {

    // ---------------------------------------------------------------
    // BMS width constants (chars). Sourced from app/bms/COMEN01.bms.
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
     * {@code OPTN001}..{@code OPTN012} ({@code LENGTH=40}).
     */
    public static final int OPTION_LINE_WIDTH = 40;

    /**
     * Width of {@link #option()} per BMS field {@code OPTION}
     * ({@code LENGTH=2}, {@code UNPROT}, {@code NUM}, {@code IC},
     * {@code JUSTIFY=(RIGHT,ZERO)}). This is the only operator-editable
     * field on the main menu screen.
     */
    public static final int OPTION_WIDTH = 2;

    /**
     * Width of {@link #errMsg()} per BMS field {@code ERRMSG}
     * ({@code LENGTH=78} at {@code POS=(23,1)}).
     */
    public static final int ERRMSG_WIDTH = 78;

    /**
     * Number of option-line slots surfaced by this DTO
     * ({@code OPTN001I..OPTN012I}). Twelve slots match the
     * BMS map's twelve declared option-line display fields (vs. ten in
     * {@code CoAdm01Input}). See class Javadoc &mdash; "Why twelve option
     * slots".
     */
    public static final int OPTION_LINE_COUNT = 12;

    // ---------------------------------------------------------------
    // Compact canonical constructor (JEP 513 Flexible Constructor
    // Bodies, finalized in Java 25). Per AAP §0.6.3, the right place
    // for COBOL-style fail-fast input validation: every component is
    // checked before the canonical field assignments.
    // ---------------------------------------------------------------

    /**
     * Compact canonical constructor enforcing the non-null contract for
     * all 20 record components. Java&nbsp;25 JEP&nbsp;513 permits this
     * validation to execute before the implicit canonical field
     * assignments.
     *
     * @throws NullPointerException if any component is {@code null}; the
     *         exception message names the offending component
     */
    public CoMen01Input {
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
        Objects.requireNonNull(option011,  "option011");
        Objects.requireNonNull(option012,  "option012");
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
     * <p>The returned record satisfies every invariant of the compact
     * canonical constructor and carries no payload; consumers may
     * safely treat it as the canonical starting state of an input
     * cycle.
     *
     * @return a fresh empty {@code CoMen01Input} (never {@code null})
     */
    public static CoMen01Input empty() {
        return new CoMen01Input(
                "", "", "", "", "", "",   // header (trnName, title01, curDate, pgmName, title02, curTime)
                "", "", "", "", "", "",   // option001-option006
                "", "", "", "", "", "",   // option007-option012
                "", ""                    // option, errMsg
        );
    }

    /**
     * Convenience factory: returns a blank input with only
     * {@link #option()} populated. Equivalent to
     * {@code empty().withOption(optionValue)} but documents intent
     * &mdash; the {@code option} component is the only field the
     * terminal user actually populates on the main menu screen.
     *
     * @param optionValue the 2-char main menu selection (non-null;
     *                    pass {@code ""} for no entry)
     * @return a new {@code CoMen01Input} with {@link #option()} set
     *         and every other component blank (never {@code null})
     * @throws NullPointerException if {@code optionValue} is
     *         {@code null}
     */
    public static CoMen01Input ofOption(String optionValue) {
        Objects.requireNonNull(optionValue, "optionValue");
        return empty().withOption(optionValue);
    }

    // ---------------------------------------------------------------
    // Fluent withX(...) update methods. One per record component
    // (20 total). Each returns a new immutable instance with the
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
    public CoMen01Input withTrnName(String v) {
        return new CoMen01Input(v, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #title01()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withTitle01(String v) {
        return new CoMen01Input(trnName, v, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #curDate()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withCurDate(String v) {
        return new CoMen01Input(trnName, title01, v, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #pgmName()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withPgmName(String v) {
        return new CoMen01Input(trnName, title01, curDate, v, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #title02()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withTitle02(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, v, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #curTime()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withCurTime(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, v,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option001()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption001(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                v, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option002()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption002(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, v, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option003()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption003(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, v, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option004()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption004(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, v, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option005()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption005(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, v, option006,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option006()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption006(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, v,
                option007, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option007()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption007(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                v, option008, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option008()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption008(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, v, option009, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option009()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption009(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, v, option010, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option010()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption010(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, v, option011, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option011()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption011(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, v, option012,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option012()} replaced.
     *
     * @param v new value (non-null)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption012(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, v,
                option, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #option()} replaced.
     *
     * <p>This is the most commonly invoked {@code withX} method &mdash;
     * the {@code option} component is the only operator-typed field on
     * the main menu screen, and the COBOL paragraph
     * {@code PROCESS-ENTER-KEY} echoes the parsed selection back via the
     * equivalent of this call.
     *
     * @param v new value (non-null; pass {@code ""} for no entry)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withOption(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                v, errMsg);
    }

    /**
     * Returns a copy of this input with {@link #errMsg()} replaced.
     *
     * @param v new value (non-null; pass {@code ""} for no message)
     * @return new instance (never {@code null})
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public CoMen01Input withErrMsg(String v) {
        return new CoMen01Input(trnName, title01, curDate, pgmName, title02, curTime,
                option001, option002, option003, option004, option005, option006,
                option007, option008, option009, option010, option011, option012,
                option, v);
    }
}
