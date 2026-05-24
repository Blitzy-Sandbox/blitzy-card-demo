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
 * Immutable output DTO for the {@code COMEN01C} main menu online program
 * (CICS transaction {@code CM00}, COBOL source
 * {@code app/cbl/COMEN01C.cbl}).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COMEN01.bms}.</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COMEN01.CPY},
 *       output group {@code 01 COMEN1AO REDEFINES COMEN1AI}
 *       (lines 139-260).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.4.1, this record is the Java analog of the output
 * view of the BMS symbolic structure {@code COMEN1AO}: it carries the
 * field values that {@code CoMen01C} writes back to the 3270 screen via
 * {@code EXEC CICS SEND MAP}. The BMS output structure
 * {@code COMEN1AO REDEFINES COMEN1AI}, so the same memory region is
 * reinterpreted &mdash; each input {@code -I} leaf becomes a quartet of
 * three protocol bytes ({@code -C} color, {@code -P} PS, {@code -H}
 * highlight, {@code -V} validation) followed by an output payload
 * {@code -O}.
 *
 * <h2>Field selection &mdash; output payloads plus error color</h2>
 * <p>This DTO models the eighteen {@code -O} value leaves plus the
 * single {@code ERRMSGC} color byte (the only 3270 attribute byte that
 * {@code COMEN01C} actually overrides &mdash; from default to
 * {@code DFHRED} on error). All other {@code -C}, {@code -P},
 * {@code -H}, and {@code -V} attribute bytes inherit their compile-time
 * defaults from the BMS map definition and are not surfaced here, in
 * keeping with AAP &sect;0.7.1 minimal-change.
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
 *         <td>40 chars each, (6,20)..(17,20)</td>
 *         <td>{@link #option01()}..{@link #option12()}</td></tr>
 *     <tr><td>{@code OPTIONO}</td>
 *         <td>2 chars, UNPROT, (20,41)</td>
 *         <td>{@link #option()}</td></tr>
 *     <tr><td>{@code ERRMSGO}</td><td>78 chars, (23,1)</td>
 *         <td>{@link #errorMessage()}</td></tr>
 *     <tr><td>{@code ERRMSGC}</td><td>1 byte, attribute</td>
 *         <td>{@link #errMsgColor()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Field colour override</h2>
 * <p>The {@link #errMsgColor()} component carries the
 * runtime-overridden colour for the {@code ERRMSGO} payload &mdash;
 * {@link FieldColor#NONE} means "leave at BMS map default (red on
 * black)", and any other value triggers the BMS adapter to emit the
 * matching {@code DFH<color>} attribute byte. {@code COMEN01C} sets it
 * to {@link FieldColor#NONE} on success and to {@link FieldColor#RED}
 * when an error message is present.
 *
 * <h2>Null and over-length tolerance</h2>
 * <p>The compact canonical constructor is <em>lenient</em>: every
 * {@code null} {@link String} is normalised to {@code ""}, and every
 * value longer than the BMS-declared {@code PIC X(n)} width is
 * truncated from the right (COBOL {@code MOVE} semantics for an
 * over-sized source). {@link #errMsgColor()} cannot be {@code null};
 * the constructor substitutes {@link FieldColor#NONE} if the caller
 * passes {@code null} (the "no override" case).
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Records, final components, no setters &mdash; safe to share across
 * threads including the virtual-thread workers per AAP &sect;0.6.6.
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
 * @param option01         OPTN001O &mdash; 40-char menu option 1 label
 * @param option02         OPTN002O &mdash; 40-char menu option 2 label
 * @param option03         OPTN003O &mdash; 40-char menu option 3 label
 * @param option04         OPTN004O &mdash; 40-char menu option 4 label
 * @param option05         OPTN005O &mdash; 40-char menu option 5 label
 * @param option06         OPTN006O &mdash; 40-char menu option 6 label
 * @param option07         OPTN007O &mdash; 40-char menu option 7 label
 * @param option08         OPTN008O &mdash; 40-char menu option 8 label
 * @param option09         OPTN009O &mdash; 40-char menu option 9 label
 * @param option10         OPTN010O &mdash; 40-char menu option 10 label
 * @param option11         OPTN011O &mdash; 40-char menu option 11 label
 * @param option12         OPTN012O &mdash; 40-char menu option 12 label
 * @param option           OPTIONO &mdash; 2-char menu selection echo
 * @param errorMessage     ERRMSGO &mdash; 78-char error message
 * @param errMsgColor      ERRMSGC override
 *                         ({@link FieldColor#NONE} = no override)
 *
 * @see CoMen01Input
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COMEN01",
        sourcePath = "app/bms/COMEN01.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (output side); symbolic copybook 01 COMEN1AO "
                + "REDEFINES COMEN1AI in app/cpy-bms/COMEN01.CPY (lines 139-260). Driven "
                + "by online program app/cbl/COMEN01C.cbl (main menu, transaction CM00)."
)
public record CoMen01Output(
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

    /**
     * Length of each menu option label
     * ({@code OPTN001O}..{@code OPTN012O}) per BMS map &mdash; 40 chars
     * each.
     */
    public static final int OPTION_LABEL_LENGTH = 40;

    /** Length of {@link #option()} per BMS map {@code OPTIONO} &mdash; 2 chars. */
    public static final int OPTION_LENGTH = 2;

    /** Length of {@link #errorMessage()} per BMS map {@code ERRMSGO} &mdash; 78 chars. */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor
     * Bodies). See {@link CoMen01Input#CoMen01Input} for the shared
     * normalisation contract. {@code null} {@code errMsgColor} is
     * substituted with {@link FieldColor#NONE} (the "no override"
     * sentinel).
     */
    public CoMen01Output {
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
     * Returns a fully-blank output record: every {@link String}
     * component is {@code ""} and {@link #errMsgColor()} is
     * {@link FieldColor#NONE}. The Java equivalent of
     * {@code MOVE LOW-VALUES TO COMEN1AO} performed by
     * {@code COMEN01C} prior to populating the header on each cycle.
     *
     * @return a fresh empty {@code CoMen01Output} (never {@code null})
     */
    public static CoMen01Output empty() {
        return new CoMen01Output(
                "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "", "", "",
                "", "",
                FieldColor.NONE
        );
    }

    /**
     * Returns a copy of this output with {@link #errorMessage()} and
     * {@link #errMsgColor()} replaced (all other components preserved).
     * Setting an error message conventionally also sets the colour to
     * {@link FieldColor#RED}; this helper enforces that pairing.
     *
     * @param newErrorMessage the replacement error text (may be
     *                        {@code null}; normalised to {@code ""}
     *                        and truncated to 78 chars)
     * @return a new {@code CoMen01Output} with the error pair replaced
     */
    public CoMen01Output withErrorMessage(String newErrorMessage) {
        return new CoMen01Output(
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
     * Sealed enumeration of 3270 field colour overrides emitted on the
     * single attribute byte that {@code COMEN01C} actually changes
     * ({@code ERRMSGC}). Mirrors the CICS BMS constants from copybook
     * {@code DFHBMSCA} but is locally scoped to the menu DTO so a
     * downstream change to the central colour table does not ripple
     * into every BMS output record.
     *
     * <p>{@link #NONE} is the sentinel meaning "no override &mdash;
     * leave the field at the BMS map's compile-time
     * {@code COLOR=} default". Any other value triggers the BMS
     * adapter to emit the matching {@code DFH<color>} byte.
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
     * Normalises a {@link String} field per the COBOL {@code MOVE}
     * contract. See {@link CoMen01Input} for the full contract.
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
