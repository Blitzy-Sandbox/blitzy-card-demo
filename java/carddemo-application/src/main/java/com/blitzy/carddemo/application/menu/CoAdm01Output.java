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
 * Immutable output DTO for the {@code COADM01C} admin menu online
 * program (CICS transaction {@code CA00}, COBOL source
 * {@code app/cbl/COADM01C.cbl}).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COADM01.bms}.</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COADM01.CPY},
 *       output group {@code 01 COADM1AO REDEFINES COADM1AI}
 *       (lines 139-260).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.4.1, this record is the Java analog of the output
 * view of the BMS symbolic structure {@code COADM1AO}: it carries the
 * field values that {@code CoAdm01C} writes back to the 3270 screen
 * via {@code EXEC CICS SEND MAP}. Structurally identical to
 * {@link CoMen01Output} (eighteen 40-char option labels plus
 * navigation header plus error pair); only the static option lookup
 * table differs ({@code COADM02Y} vs. {@code COMEN02Y}).
 *
 * <h2>Component inventory</h2>
 * <table border="1" summary="BMS-to-record component mapping">
 *   <thead>
 *     <tr><th>BMS field</th><th>BMS attrs / position</th>
 *         <th>Record component</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code TRNNAMEO}</td><td>4 chars, (1,7)</td>
 *         <td>{@link #transactionName()}</td></tr>
 *     <tr><td>{@code TITLE01O}</td><td>40 chars, (1,21)</td>
 *         <td>{@link #title01()}</td></tr>
 *     <tr><td>{@code CURDATEO}</td><td>8 chars (mm/dd/yy), (1,71)</td>
 *         <td>{@link #currentDate()}</td></tr>
 *     <tr><td>{@code PGMNAMEO}</td><td>8 chars, (2,7)</td>
 *         <td>{@link #programName()}</td></tr>
 *     <tr><td>{@code TITLE02O}</td><td>40 chars, (2,21)</td>
 *         <td>{@link #title02()}</td></tr>
 *     <tr><td>{@code CURTIMEO}</td><td>8 chars (hh:mm:ss), (2,71)</td>
 *         <td>{@link #currentTime()}</td></tr>
 *     <tr><td>{@code OPTN001O}..{@code OPTN012O}</td>
 *         <td>40 chars each</td>
 *         <td>{@link #option01()}..{@link #option12()}</td></tr>
 *     <tr><td>{@code OPTIONO}</td><td>2 chars</td>
 *         <td>{@link #option()}</td></tr>
 *     <tr><td>{@code ERRMSGO}</td><td>78 chars</td>
 *         <td>{@link #errorMessage()}</td></tr>
 *     <tr><td>{@code ERRMSGC}</td><td>1 attribute byte</td>
 *         <td>{@link #errMsgColor()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <p>No Spring, Lombok, Bean Validation, {@code java.util.Date},
 * {@code double}, {@code float}, or preview features.
 *
 * @param transactionName  TRNNAMEO &mdash; 4-char transaction code echo
 * @param title01          TITLE01O &mdash; 40-char line-1 title bar
 * @param currentDate      CURDATEO &mdash; 8-char date {@code "mm/dd/yy"}
 * @param programName      PGMNAMEO &mdash; 8-char program-id echo
 * @param title02          TITLE02O &mdash; 40-char line-2 title bar
 * @param currentTime      CURTIMEO &mdash; 8-char time {@code "hh:mm:ss"}
 * @param option01         OPTN001O &mdash; 40-char admin option 1 label
 * @param option02         OPTN002O &mdash; 40-char admin option 2 label
 * @param option03         OPTN003O &mdash; 40-char admin option 3 label
 * @param option04         OPTN004O &mdash; 40-char admin option 4 label
 * @param option05         OPTN005O &mdash; 40-char admin option 5 label
 * @param option06         OPTN006O &mdash; 40-char admin option 6 label
 * @param option07         OPTN007O &mdash; 40-char admin option 7 label
 * @param option08         OPTN008O &mdash; 40-char admin option 8 label
 * @param option09         OPTN009O &mdash; 40-char admin option 9 label
 * @param option10         OPTN010O &mdash; 40-char admin option 10 label
 * @param option11         OPTN011O &mdash; 40-char admin option 11 label
 * @param option12         OPTN012O &mdash; 40-char admin option 12 label
 * @param option           OPTIONO &mdash; 2-char admin menu selection echo
 * @param errorMessage     ERRMSGO &mdash; 78-char error message
 * @param errMsgColor      ERRMSGC override
 *                         ({@link FieldColor#NONE} = no override)
 *
 * @see CoAdm01Input
 * @see CoMen01Output
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COADM01",
        sourcePath = "app/bms/COADM01.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (output side); symbolic copybook 01 COADM1AO "
                + "REDEFINES COADM1AI in app/cpy-bms/COADM01.CPY (lines 139-260). Driven "
                + "by online program app/cbl/COADM01C.cbl (admin menu, transaction CA00)."
)
public record CoAdm01Output(
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
        String errorMessage,
        FieldColor errMsgColor
) {

    /** Length of {@link #transactionName()} per BMS map {@code TRNNAMEO} &mdash; 4 chars. */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /** Length of {@link #title01()} per BMS map {@code TITLE01O} &mdash; 40 chars. */
    public static final int TITLE_01_LENGTH = 40;

    /** Length of {@link #currentDate()} per BMS map {@code CURDATEO} &mdash; 8 chars (mm/dd/yy). */
    public static final int CURRENT_DATE_LENGTH = 8;

    /** Length of {@link #programName()} per BMS map {@code PGMNAMEO} &mdash; 8 chars. */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /** Length of {@link #title02()} per BMS map {@code TITLE02O} &mdash; 40 chars. */
    public static final int TITLE_02_LENGTH = 40;

    /** Length of {@link #currentTime()} per BMS map {@code CURTIMEO} &mdash; 8 chars (hh:mm:ss). */
    public static final int CURRENT_TIME_LENGTH = 8;

    /** Length of each menu option label per BMS map &mdash; 40 chars. */
    public static final int OPTION_LABEL_LENGTH = 40;

    /** Length of {@link #option()} per BMS map {@code OPTIONO} &mdash; 2 chars. */
    public static final int OPTION_LENGTH = 2;

    /** Length of {@link #errorMessage()} per BMS map {@code ERRMSGO} &mdash; 78 chars. */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor
     * Bodies). Every {@link String} component is normalised
     * ({@code null} &rarr; {@code ""}, over-long &rarr; truncate-right
     * per COBOL {@code MOVE}). {@code null errMsgColor} is substituted
     * with {@link FieldColor#NONE}.
     */
    public CoAdm01Output {
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
        if (errMsgColor == null) {
            errMsgColor = FieldColor.NONE;
        }
    }

    /**
     * Returns a fully-blank output record.
     *
     * @return a fresh empty {@code CoAdm01Output} (never {@code null})
     */
    public static CoAdm01Output empty() {
        return new CoAdm01Output(
                "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "", "", "",
                "", "",
                FieldColor.NONE
        );
    }

    /**
     * Returns a copy of this output with {@link #errorMessage()} and
     * {@link #errMsgColor()} set together; the colour is forced to
     * {@link FieldColor#RED} to match the BMS convention for error
     * highlighting.
     *
     * @param newErrorMessage the replacement error text
     * @return a new {@code CoAdm01Output} with the error pair replaced
     */
    public CoAdm01Output withErrorMessage(String newErrorMessage) {
        return new CoAdm01Output(
                transactionName,
                title01,
                currentDate,
                programName,
                title02,
                currentTime,
                option01, option02, option03, option04, option05, option06,
                option07, option08, option09, option10, option11, option12,
                option,
                newErrorMessage,
                FieldColor.RED
        );
    }

    /**
     * Sealed enumeration of 3270 field colour overrides for the
     * {@code ERRMSGC} attribute byte. Structurally identical to
     * {@link CoMen01Output.FieldColor}; kept local to the admin DTO so
     * downstream changes to one menu's colour table do not ripple
     * across menus.
     */
    public enum FieldColor {
        /** Sentinel: leave the field at the BMS map's compile-time {@code COLOR=} default. */
        NONE,
        /** {@code DFHRED} &mdash; used for error messages. */
        RED,
        /** {@code DFHGREEN} &mdash; used for success messages. */
        GREEN,
        /** {@code DFHYELLOW}. */
        YELLOW,
        /** {@code DFHBLUE}. */
        BLUE,
        /** {@code DFHPINK}. */
        PINK,
        /** {@code DFHTURQ}. */
        TURQUOISE,
        /** {@code DFHNEUTR}. */
        NEUTRAL
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
