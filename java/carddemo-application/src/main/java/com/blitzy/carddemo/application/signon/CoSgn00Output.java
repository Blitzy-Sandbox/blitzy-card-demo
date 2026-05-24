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
package com.blitzy.carddemo.application.signon;

// JEP 511 (finalized in Java 25): brings java.lang.String and
// java.lang.Override into scope.
import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Immutable output DTO for the {@code COSGN00C} signon online program
 * (CICS transaction {@code CC00}, COBOL source
 * {@code app/cbl/COSGN00C.cbl}).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COSGN00.bms}.</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COSGN00.CPY},
 *       output group {@code 01 COSGN0AO REDEFINES COSGN0AI}
 *       (lines 88-160).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.4.1, this record is the Java analog of the output
 * view of the BMS symbolic structure {@code COSGN0AO}: it carries the
 * field values that {@code COSGN00C} writes back to the 3270 screen
 * via {@code EXEC CICS SEND MAP}. The BMS output structure REDEFINES
 * the input, so the same memory region is reinterpreted &mdash; each
 * input {@code -I} leaf becomes a quartet of three protocol bytes
 * ({@code -C} color, {@code -P} PS, {@code -H} highlight, {@code -V}
 * validation) followed by an output payload {@code -O}.
 *
 * <h2>Field selection &mdash; output payloads plus error color</h2>
 * <p>This DTO models the eleven {@code -O} value leaves plus the
 * single {@code ERRMSGC} color byte (the only 3270 attribute byte that
 * {@code COSGN00C} overrides &mdash; from default to {@code DFHRED} on
 * "invalid credentials"). All other {@code -C}, {@code -P},
 * {@code -H}, and {@code -V} attribute bytes inherit their
 * compile-time defaults from the BMS map definition and are not
 * surfaced here.
 *
 * <h2>Password handling on the output side</h2>
 * <p>The BMS map declares {@code PASSWDO} as a 8-char field with the
 * {@code DRK} (non-display) attribute so the 3270 controller suppresses
 * it from the rendered screen. {@code COSGN00C} clears the field with
 * {@code MOVE SPACES TO PASSWDO} on every {@code SEND MAP} so that no
 * cleartext is ever transmitted, even though the field exists in the
 * symbolic map. For byte-symmetric fidelity (AAP &sect;0.4.1) this DTO
 * surfaces the field, and for defence-in-depth against accidental
 * logging it <strong>masks</strong> the password component in the
 * overridden {@link #toString()} &mdash; matching the symmetric
 * input-side mask in {@link CoSgn00Input#toString()} (see also AAP
 * &sect;0.7.2 PAN-masking mandate extended to passwords by parity).
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
 *     <tr><td>{@code CURTIMEO}</td><td>9 chars (hh:mm:ss), (2,71)</td>
 *         <td>{@link #currentTime()}</td></tr>
 *     <tr><td>{@code APPLIDO}</td><td>8 chars</td>
 *         <td>{@link #applId()}</td></tr>
 *     <tr><td>{@code SYSIDO}</td><td>8 chars</td>
 *         <td>{@link #sysId()}</td></tr>
 *     <tr><td>{@code USERIDO}</td><td>8 chars</td>
 *         <td>{@link #userId()}</td></tr>
 *     <tr><td>{@code PASSWDO}</td><td>8 chars, DRK</td>
 *         <td>{@link #password()} &mdash; masked in
 *             {@link #toString()}; conventionally SPACES on
 *             SEND MAP</td></tr>
 *     <tr><td>{@code ERRMSGO}</td><td>78 chars, (23,1)</td>
 *         <td>{@link #errorMessage()}</td></tr>
 *     <tr><td>{@code ERRMSGC}</td><td>1 attribute byte</td>
 *         <td>{@link #errMsgColor()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Null and over-length tolerance</h2>
 * <p>Every {@link String} is normalised in the compact canonical
 * constructor: {@code null} becomes {@code ""}, over-long values are
 * truncated from the right (COBOL {@code MOVE} semantics). The
 * {@link #errMsgColor()} cannot be {@code null}; the constructor
 * substitutes {@link FieldColor#NONE} if the caller passes
 * {@code null}.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Records, final components, no setters &mdash; safe to share
 * across threads including virtual-thread workers per AAP &sect;0.6.6.
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
 * @param currentTime      CURTIMEO &mdash; 9-char time {@code "hh:mm:ss"}
 * @param applId           APPLIDO &mdash; 8-char CICS APPLID echo
 * @param sysId            SYSIDO &mdash; 8-char CICS SYSID echo
 * @param userId           USERIDO &mdash; 8-char user-id echo
 * @param password         PASSWDO &mdash; 8-char password field
 *                         (conventionally SPACES on SEND;
 *                         <strong>masked</strong> in
 *                         {@link #toString()})
 * @param errorMessage     ERRMSGO &mdash; 78-char error message
 * @param errMsgColor      ERRMSGC override
 *                         ({@link FieldColor#NONE} = no override)
 *
 * @see CoSgn00Input
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COSGN00",
        sourcePath = "app/bms/COSGN00.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (output side); symbolic copybook 01 COSGN0AO "
                + "REDEFINES COSGN0AI in app/cpy-bms/COSGN00.CPY (lines 88-160). Driven "
                + "by online program app/cbl/COSGN00C.cbl (signon, transaction CC00). "
                + "PASSWDO is masked in toString() per AAP §0.7.2 no-credential-in-logs "
                + "mandate; the BMS map applies DRK (non-display) on the 3270 wire."
)
public record CoSgn00Output(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String applId,
        String sysId,
        String userId,
        String password,
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

    /**
     * Length of {@link #currentTime()} per BMS map {@code CURTIMEO}
     * &mdash; <strong>9</strong> chars on the signon map.
     */
    public static final int CURRENT_TIME_LENGTH = 9;

    /** Length of {@link #applId()} per BMS map {@code APPLIDO} &mdash; 8 chars. */
    public static final int APPL_ID_LENGTH = 8;

    /** Length of {@link #sysId()} per BMS map {@code SYSIDO} &mdash; 8 chars. */
    public static final int SYS_ID_LENGTH = 8;

    /** Length of {@link #userId()} per BMS map {@code USERIDO} &mdash; 8 chars. */
    public static final int USER_ID_LENGTH = 8;

    /** Length of {@link #password()} per BMS map {@code PASSWDO} &mdash; 8 chars. */
    public static final int PASSWORD_LENGTH = 8;

    /** Length of {@link #errorMessage()} per BMS map {@code ERRMSGO} &mdash; 78 chars. */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /** Fixed mask for {@link #password()} in {@link #toString()}. */
    private static final String PASSWORD_MASK = "********";

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor
     * Bodies). Every {@link String} is normalised; {@code null
     * errMsgColor} is substituted with {@link FieldColor#NONE}.
     */
    public CoSgn00Output {
        transactionName = normalize(transactionName, TRANSACTION_NAME_LENGTH);
        title01         = normalize(title01,         TITLE_01_LENGTH);
        currentDate     = normalize(currentDate,     CURRENT_DATE_LENGTH);
        programName     = normalize(programName,     PROGRAM_NAME_LENGTH);
        title02         = normalize(title02,         TITLE_02_LENGTH);
        currentTime     = normalize(currentTime,     CURRENT_TIME_LENGTH);
        applId          = normalize(applId,          APPL_ID_LENGTH);
        sysId           = normalize(sysId,           SYS_ID_LENGTH);
        userId          = normalize(userId,          USER_ID_LENGTH);
        password        = normalize(password,        PASSWORD_LENGTH);
        errorMessage    = normalize(errorMessage,    ERROR_MESSAGE_LENGTH);
        if (errMsgColor == null) {
            errMsgColor = FieldColor.NONE;
        }
    }

    /**
     * Returns a fully-blank output record: every {@link String}
     * component is {@code ""} and {@link #errMsgColor()} is
     * {@link FieldColor#NONE}.
     *
     * @return a fresh empty {@code CoSgn00Output} (never {@code null})
     */
    public static CoSgn00Output empty() {
        return new CoSgn00Output(
                "", "", "", "", "", "", "", "", "", "", "",
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
     * @return a new {@code CoSgn00Output} with the error pair replaced
     */
    public CoSgn00Output withErrorMessage(String newErrorMessage) {
        return new CoSgn00Output(
                transactionName,
                title01,
                currentDate,
                programName,
                title02,
                currentTime,
                applId,
                sysId,
                userId,
                password,
                newErrorMessage,
                FieldColor.RED
        );
    }

    /**
     * Returns a string representation that masks the {@link #password()}
     * component with {@value #PASSWORD_MASK}. See
     * {@link CoSgn00Input#toString()} for the rationale; this output
     * DTO is masked symmetrically with the input so a partial copy or
     * a logged round-trip cannot leak a password through this side
     * either.
     *
     * @return a {@link String} that never contains the cleartext
     *         password value
     */
    @Override
    public String toString() {
        return "CoSgn00Output["
                + "transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", applId=" + applId
                + ", sysId=" + sysId
                + ", userId=" + userId
                + ", password=" + PASSWORD_MASK
                + ", errorMessage=" + errorMessage
                + ", errMsgColor=" + errMsgColor
                + "]";
    }

    /**
     * Sealed enumeration of 3270 field colour overrides emitted on the
     * single attribute byte that {@code COSGN00C} actually changes
     * ({@code ERRMSGC}). Mirrors the CICS BMS constants from copybook
     * {@code DFHBMSCA} but locally scoped to the signon DTO.
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
