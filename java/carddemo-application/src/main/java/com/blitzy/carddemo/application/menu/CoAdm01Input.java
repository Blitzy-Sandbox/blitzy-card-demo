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

// JEP 511 (finalized in Java 25): brings java.lang.String into scope.
import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Immutable input DTO for the {@code COADM01C} admin menu online
 * program (CICS transaction {@code CA00}, COBOL source
 * {@code app/cbl/COADM01C.cbl}).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COADM01.bms}
 *       (mapset {@code COADM01}, map {@code COADM1A}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COADM01.CPY},
 *       input group {@code 01 COADM1AI} (lines 17-138).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.4.1, this record is the Java analog of the input
 * view of the BMS symbolic structure {@code COADM1AI}: it carries the
 * field values returned from {@code EXEC CICS RECEIVE MAP} to
 * {@code CoAdm01C}. The admin menu is structurally identical to the
 * main menu (eighteen 40-char option labels echoed plus one 2-char
 * selection field) &mdash; only the static option lookup table differs
 * ({@code COADM02Y} vs.&nbsp;{@code COMEN02Y}).
 *
 * <h2>Operator-editable field</h2>
 * <p>The only field the user actually populates is {@link #option()}
 * (OPTIONI, 2 chars): the two-digit menu selection that
 * {@code COADM01C} validates against the static admin menu table
 * {@code COADM02Y} and dispatches via {@code XCTL PROGRAM(...)}.
 *
 * <h2>AID-key dispatch &mdash; separate parameter</h2>
 * <p>The 3270 AID key (ENTER, PF3, etc.) is decoded by the BMS adapter
 * and passed to {@code CoAdm01C#execute} as a separate parameter,
 * drawing from the central
 * {@code com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey} sealed
 * hierarchy (AAP &sect;0.6.10).
 *
 * <h2>Null and over-length tolerance</h2>
 * <p>The compact canonical constructor is <em>lenient</em>: every
 * {@code null} {@link String} is normalised to {@code ""}, and every
 * value longer than the BMS-declared width is truncated from the right
 * (COBOL {@code MOVE} semantics for an over-sized source). The
 * constructor does <strong>not</strong> validate the <em>content</em>
 * of {@link #option()} &mdash; that is performed by
 * {@code CoAdm01C} and reported via
 * {@link CoAdm01Output#errorMessage()}.
 *
 * <h2>Component inventory</h2>
 * <table border="1" summary="BMS-to-record component mapping">
 *   <thead>
 *     <tr><th>BMS field</th><th>BMS attrs / position</th>
 *         <th>Record component</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code TRNNAMEI}</td><td>4 chars, (1,7)</td>
 *         <td>{@link #transactionName()}</td></tr>
 *     <tr><td>{@code TITLE01I}</td><td>40 chars, (1,21)</td>
 *         <td>{@link #title01()}</td></tr>
 *     <tr><td>{@code CURDATEI}</td><td>8 chars (mm/dd/yy), (1,71)</td>
 *         <td>{@link #currentDate()}</td></tr>
 *     <tr><td>{@code PGMNAMEI}</td><td>8 chars, (2,7)</td>
 *         <td>{@link #programName()}</td></tr>
 *     <tr><td>{@code TITLE02I}</td><td>40 chars, (2,21)</td>
 *         <td>{@link #title02()}</td></tr>
 *     <tr><td>{@code CURTIMEI}</td><td>8 chars (hh:mm:ss), (2,71)</td>
 *         <td>{@link #currentTime()}</td></tr>
 *     <tr><td>{@code OPTN001I}..{@code OPTN012I}</td>
 *         <td>40 chars each</td>
 *         <td>{@link #option01()}..{@link #option12()}</td></tr>
 *     <tr><td>{@code OPTIONI}</td>
 *         <td>2 chars, UNPROT, NUM</td>
 *         <td>{@link #option()} &mdash; <em>operator input</em></td></tr>
 *     <tr><td>{@code ERRMSGI}</td><td>78 chars</td>
 *         <td>{@link #errorMessage()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <p>No Spring, Lombok, Bean Validation, {@code java.util.Date},
 * {@code double}, {@code float}, or preview features.
 *
 * @param transactionName  TRNNAMEI &mdash; 4-char transaction code echo
 * @param title01          TITLE01I &mdash; 40-char line-1 title bar echo
 * @param currentDate      CURDATEI &mdash; 8-char date echo
 * @param programName      PGMNAMEI &mdash; 8-char program-id echo
 * @param title02          TITLE02I &mdash; 40-char line-2 title bar echo
 * @param currentTime      CURTIMEI &mdash; 8-char time echo
 * @param option01         OPTN001I &mdash; 40-char menu option 1 label echo
 * @param option02         OPTN002I &mdash; 40-char menu option 2 label echo
 * @param option03         OPTN003I &mdash; 40-char menu option 3 label echo
 * @param option04         OPTN004I &mdash; 40-char menu option 4 label echo
 * @param option05         OPTN005I &mdash; 40-char menu option 5 label echo
 * @param option06         OPTN006I &mdash; 40-char menu option 6 label echo
 * @param option07         OPTN007I &mdash; 40-char menu option 7 label echo
 * @param option08         OPTN008I &mdash; 40-char menu option 8 label echo
 * @param option09         OPTN009I &mdash; 40-char menu option 9 label echo
 * @param option10         OPTN010I &mdash; 40-char menu option 10 label echo
 * @param option11         OPTN011I &mdash; 40-char menu option 11 label echo
 * @param option12         OPTN012I &mdash; 40-char menu option 12 label echo
 * @param option           OPTIONI &mdash; 2-char admin menu selection input
 * @param errorMessage     ERRMSGI &mdash; 78-char error message echo
 *
 * @see CoAdm01Output
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COADM01",
        sourcePath = "app/bms/COADM01.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook 01 COADM1AI in "
                + "app/cpy-bms/COADM01.CPY (lines 17-138). Driven by online program "
                + "app/cbl/COADM01C.cbl (admin menu, transaction CA00). The static "
                + "admin-menu lookup table is provided by domain record COADM02Y "
                + "(AdminMenuTable)."
)
public record CoAdm01Input(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String option01,
        String option02,
        String option03,
        String option04,
        String option05,
        String option06,
        String option07,
        String option08,
        String option09,
        String option10,
        String option11,
        String option12,
        String option,
        String errorMessage
) {

    /** Length of {@link #transactionName()} per BMS map {@code TRNNAMEI} &mdash; 4 chars. */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /** Length of {@link #title01()} per BMS map {@code TITLE01I} &mdash; 40 chars. */
    public static final int TITLE_01_LENGTH = 40;

    /** Length of {@link #currentDate()} per BMS map {@code CURDATEI} &mdash; 8 chars (mm/dd/yy). */
    public static final int CURRENT_DATE_LENGTH = 8;

    /** Length of {@link #programName()} per BMS map {@code PGMNAMEI} &mdash; 8 chars. */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /** Length of {@link #title02()} per BMS map {@code TITLE02I} &mdash; 40 chars. */
    public static final int TITLE_02_LENGTH = 40;

    /** Length of {@link #currentTime()} per BMS map {@code CURTIMEI} &mdash; 8 chars (hh:mm:ss). */
    public static final int CURRENT_TIME_LENGTH = 8;

    /** Length of each menu option label per BMS map &mdash; 40 chars. */
    public static final int OPTION_LABEL_LENGTH = 40;

    /** Length of {@link #option()} per BMS map {@code OPTIONI} &mdash; 2 chars. */
    public static final int OPTION_LENGTH = 2;

    /** Length of {@link #errorMessage()} per BMS map {@code ERRMSGI} &mdash; 78 chars. */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor
     * Bodies). Every {@link String} is normalised: {@code null}
     * becomes {@code ""}, over-long values are truncated from the
     * right per COBOL {@code MOVE} semantics. Trailing spaces are
     * preserved (AAP &sect;0.7.1 byte fidelity).
     */
    public CoAdm01Input {
        transactionName = normalize(transactionName, TRANSACTION_NAME_LENGTH);
        title01         = normalize(title01,         TITLE_01_LENGTH);
        currentDate     = normalize(currentDate,     CURRENT_DATE_LENGTH);
        programName     = normalize(programName,     PROGRAM_NAME_LENGTH);
        title02         = normalize(title02,         TITLE_02_LENGTH);
        currentTime     = normalize(currentTime,     CURRENT_TIME_LENGTH);
        option01        = normalize(option01,        OPTION_LABEL_LENGTH);
        option02        = normalize(option02,        OPTION_LABEL_LENGTH);
        option03        = normalize(option03,        OPTION_LABEL_LENGTH);
        option04        = normalize(option04,        OPTION_LABEL_LENGTH);
        option05        = normalize(option05,        OPTION_LABEL_LENGTH);
        option06        = normalize(option06,        OPTION_LABEL_LENGTH);
        option07        = normalize(option07,        OPTION_LABEL_LENGTH);
        option08        = normalize(option08,        OPTION_LABEL_LENGTH);
        option09        = normalize(option09,        OPTION_LABEL_LENGTH);
        option10        = normalize(option10,        OPTION_LABEL_LENGTH);
        option11        = normalize(option11,        OPTION_LABEL_LENGTH);
        option12        = normalize(option12,        OPTION_LABEL_LENGTH);
        option          = normalize(option,          OPTION_LENGTH);
        errorMessage    = normalize(errorMessage,    ERROR_MESSAGE_LENGTH);
    }

    /**
     * Returns a fully-blank input record: every {@link String}
     * component is {@code ""}.
     *
     * @return a fresh empty {@code CoAdm01Input} (never {@code null})
     */
    public static CoAdm01Input empty() {
        return new CoAdm01Input(
                "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "", "", "",
                "", ""
        );
    }

    /**
     * Returns a copy of this input with {@link #option()} replaced by
     * the given value (all other components preserved).
     *
     * @param newOption the replacement admin menu selection (normalised
     *                  by the canonical constructor)
     * @return a new {@code CoAdm01Input} with {@link #option()}
     *         replaced (never {@code null})
     */
    public CoAdm01Input withOption(String newOption) {
        return new CoAdm01Input(
                transactionName,
                title01,
                currentDate,
                programName,
                title02,
                currentTime,
                option01, option02, option03, option04, option05, option06,
                option07, option08, option09, option10, option11, option12,
                newOption,
                errorMessage
        );
    }

    /**
     * Normalises per COBOL {@code MOVE}: {@code null} becomes
     * {@code ""}; over-long values are truncated from the right.
     */
    private static String normalize(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() > maxLen) {
            return value.substring(0, maxLen);
        }
        return value;
    }
}
