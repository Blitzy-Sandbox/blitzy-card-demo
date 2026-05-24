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
// java.lang.Override (used by the explicit toString() override that
// masks the plaintext password) into scope.
import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Immutable input DTO for the {@code COSGN00C} signon online program
 * (CICS transaction {@code CC00}, COBOL source
 * {@code app/cbl/COSGN00C.cbl}).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COSGN00.bms} (mapset
 *       {@code COSGN00}, map {@code COSGN0A}, size {@code 24x80},
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}, {@code LANG=COBOL},
 *       {@code MODE=INOUT}, {@code STORAGE=AUTO}, {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COSGN00.CPY},
 *       input group {@code 01 COSGN0AI} (lines 17-87).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.4.1, this record is the Java analog of the input
 * view of the BMS symbolic structure {@code COSGN0AI}: it carries the
 * field values returned from {@code EXEC CICS RECEIVE MAP} to the
 * signon entry point. There is no web framework, no Spring binding,
 * no Jakarta Bean Validation; this is a plain Java carrier built
 * around finalized Java 25 language features only (records, JEP 511
 * module imports, JEP 513 flexible constructor bodies).
 *
 * <h2>Operator-editable fields</h2>
 * <p>The fields the user actually populates are
 * {@link #userId()} (USERIDI, 8 chars) and {@link #password()}
 * (PASSWDI, 8 chars, UNPROT,DRK non-display but editable). The
 * remaining nine fields are header echo content carried by the 3270
 * protocol on every RECEIVE MAP; they are modelled here only so the
 * DTO is byte-symmetric with the COBOL symbolic input structure (AAP
 * &sect;0.4.1 field-for-field translation mandate).
 *
 * <h2>Plaintext password preservation</h2>
 * <p>Per AAP &sect;0.1.3, the COBOL system stores passwords as plain
 * {@code PIC X(8)} text in the {@code SEC-USR-PWD} field of
 * {@code USRSEC} ({@code app/cpy/CSUSR01Y.cpy}). The Java migration
 * preserves this behaviour at the data-storage layer (the user record
 * has a {@code String password()} field; introducing BCrypt/Argon2
 * hashing is a separate effort flagged in {@code MIGRATION_NOTES.md}).
 *
 * <h2>NEVER log the password &mdash; toString() override</h2>
 * <p>Java records auto-generate a {@link #toString()} that prints
 * every component value, which would leak the cleartext password if
 * used in a log message, an exception message, an assertion failure,
 * or any other stringification context. To prevent that, this record
 * <strong>overrides</strong> {@link #toString()} and substitutes the
 * fixed mask {@code "********"} for the {@link #password()} component
 * while preserving the other components verbatim. This is the
 * standard pattern used by the sibling {@code CoUsr01Input} record;
 * it matches the AAP &sect;0.7.2 "PAN masking" mandate extended to
 * passwords by parity (no full credential in logs or error messages).
 *
 * <h2>AID-key dispatch &mdash; separate parameter</h2>
 * <p>Per the canonical sibling pattern established by
 * {@code CoBil00C} / {@code CoBil00Input}, the AID key (ENTER, PF3,
 * etc.) is <strong>not</strong> a component of this DTO. The 3270
 * attention identifier is decoded by the BMS adapter and passed to
 * the signon entry point as a separate parameter, drawing from the
 * central {@code com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey}
 * sealed hierarchy (AAP &sect;0.6.10).
 *
 * <h2>Null and over-length tolerance</h2>
 * <p>The compact canonical constructor is <em>lenient</em>: every
 * {@code null} {@link String} is normalised to {@code ""}, and every
 * value longer than the BMS-declared {@code PIC X(n)} width is
 * truncated from the right (COBOL {@code MOVE} semantics for an
 * over-sized source). The constructor does <strong>not</strong>
 * verify that the user-id exists in {@code USRSEC} or that the
 * password matches &mdash; those are business validations performed
 * by the signon program and reported via
 * {@link CoSgn00Output#errorMessage()}.
 *
 * <h2>Trailing-space preservation</h2>
 * <p>The normaliser does <strong>not</strong> trim trailing spaces.
 * BMS fixed-width fields arrive padded with {@code SPACE}
 * ({@code 0x20}) to the declared width; preserving that padding is
 * essential to round-trip byte fidelity per AAP &sect;0.7.1. The
 * COBOL signon program does its own trimming when comparing
 * credentials against the {@code USRSEC} file, so the DTO does not
 * second-guess that contract.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Records, final components, no setters &mdash; safe to share
 * across threads including the virtual-thread workers per AAP
 * &sect;0.6.6.
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
 *     <tr><td>{@code CURTIMEI}</td><td>9 chars (hh:mm:ss), (2,71)</td>
 *         <td>{@link #currentTime()}</td></tr>
 *     <tr><td>{@code APPLIDI}</td><td>8 chars (echo)</td>
 *         <td>{@link #applId()}</td></tr>
 *     <tr><td>{@code SYSIDI}</td><td>8 chars (echo)</td>
 *         <td>{@link #sysId()}</td></tr>
 *     <tr><td>{@code USERIDI}</td>
 *         <td>8 chars, UNPROT</td>
 *         <td>{@link #userId()} &mdash; <em>operator input</em></td></tr>
 *     <tr><td>{@code PASSWDI}</td>
 *         <td>8 chars, UNPROT, DRK (non-display)</td>
 *         <td>{@link #password()} &mdash; <em>operator input</em>;
 *             masked in {@link #toString()}</td></tr>
 *     <tr><td>{@code ERRMSGI}</td><td>78 chars (echo)</td>
 *         <td>{@link #errorMessage()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring or Jakarta annotations.</li>
 *   <li>No Lombok &mdash; record components and accessors are
 *       explicit, and the {@link #toString()} override is hand-written
 *       so that the password masking cannot be regressed by an IDE
 *       auto-regenerate.</li>
 *   <li>No Bean Validation &mdash; validation is hand-written in the
 *       compact constructor (AAP &sect;0.6.3 / JEP 513).</li>
 *   <li>No {@code java.util.Date}, {@code double}, {@code float}.</li>
 *   <li>No nested {@code AidKey} type &mdash; AID is separate.</li>
 *   <li>No preview Java features.</li>
 * </ul>
 *
 * @param transactionName  TRNNAMEI &mdash; 4-char transaction code echo
 * @param title01          TITLE01I &mdash; 40-char line-1 title bar echo
 * @param currentDate      CURDATEI &mdash; 8-char date {@code "mm/dd/yy"} echo
 * @param programName      PGMNAMEI &mdash; 8-char program-id echo
 * @param title02          TITLE02I &mdash; 40-char line-2 title bar echo
 * @param currentTime      CURTIMEI &mdash; 9-char time {@code "hh:mm:ss"}
 *                         echo. Note: the signon BMS map declares
 *                         {@code CURTIMEI} as 9 chars (not 8 as in
 *                         {@code COBIL00}); the extra byte
 *                         accommodates the trailing space after
 *                         {@code "hh:mm:ss"}.
 * @param applId           APPLIDI &mdash; 8-char CICS APPLID echo
 * @param sysId            SYSIDI &mdash; 8-char CICS SYSID echo
 * @param userId           USERIDI &mdash; 8-char user-id input
 *                         (operator-typed)
 * @param password         PASSWDI &mdash; 8-char password input
 *                         (operator-typed). <strong>Masked</strong> in
 *                         {@link #toString()} per AAP &sect;0.7.2
 *                         parity with the PAN-masking mandate.
 * @param errorMessage     ERRMSGI &mdash; 78-char error message echo
 *
 * @see CoSgn00Output
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COSGN00",
        sourcePath = "app/bms/COSGN00.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook 01 COSGN0AI in "
                + "app/cpy-bms/COSGN00.CPY (lines 17-87). Driven by online program "
                + "app/cbl/COSGN00C.cbl (signon, transaction CC00). USRSEC password is "
                + "preserved as PIC X(8) plaintext per AAP §0.1.3; the toString() "
                + "override masks the password to satisfy the AAP §0.7.2 no-credential-"
                + "in-logs mandate."
)
public record CoSgn00Input(
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

    /**
     * Length of {@link #currentTime()} per BMS map {@code CURTIMEI}
     * &mdash; <strong>9</strong> chars on the signon map (not 8 as in
     * the main menu); see the source artifact note above.
     */
    public static final int CURRENT_TIME_LENGTH = 9;

    /** Length of {@link #applId()} per BMS map {@code APPLIDI} &mdash; 8 chars. */
    public static final int APPL_ID_LENGTH = 8;

    /** Length of {@link #sysId()} per BMS map {@code SYSIDI} &mdash; 8 chars. */
    public static final int SYS_ID_LENGTH = 8;

    /** Length of {@link #userId()} per BMS map {@code USERIDI} &mdash; 8 chars. */
    public static final int USER_ID_LENGTH = 8;

    /** Length of {@link #password()} per BMS map {@code PASSWDI} &mdash; 8 chars. */
    public static final int PASSWORD_LENGTH = 8;

    /** Length of {@link #errorMessage()} per BMS map {@code ERRMSGI} &mdash; 78 chars. */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * Fixed mask for the password in {@link #toString()}. Eight
     * asterisks unconditionally; passwords of any length always render
     * as exactly eight asterisks, so the mask itself reveals no
     * length information (which could weaken brute-force resistance
     * if leaked).
     */
    private static final String PASSWORD_MASK = "********";

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor
     * Bodies). Every {@link String} is normalised: {@code null} becomes
     * {@code ""}, over-long values are truncated from the right per
     * COBOL {@code MOVE} semantics. Trailing spaces are preserved per
     * AAP &sect;0.7.1 byte fidelity.
     */
    public CoSgn00Input {
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
    }

    /**
     * Returns a fully-blank input record: every {@link String}
     * component is {@code ""}. The Java equivalent of
     * {@code MOVE LOW-VALUES TO COSGN0AI} performed by
     * {@code COSGN00C} on first entry (no prior screen state).
     *
     * @return a fresh empty {@code CoSgn00Input} (never {@code null})
     */
    public static CoSgn00Input empty() {
        return new CoSgn00Input("", "", "", "", "", "", "", "", "", "", "");
    }

    /**
     * Returns a copy of this input with {@link #userId()} and
     * {@link #password()} replaced (all other components preserved).
     * This helper exists to centralise credential-bearing copy
     * construction and reduce the surface area where the plaintext
     * password could accidentally end up in a log message via a
     * partial copy.
     *
     * @param newUserId   the replacement user-id (normalised by the
     *                    canonical constructor)
     * @param newPassword the replacement password cleartext
     *                    (normalised by the canonical constructor;
     *                    masked in {@link #toString()})
     * @return a new {@code CoSgn00Input} with the credentials replaced
     */
    public CoSgn00Input withCredentials(String newUserId, String newPassword) {
        return new CoSgn00Input(
                transactionName,
                title01,
                currentDate,
                programName,
                title02,
                currentTime,
                applId,
                sysId,
                newUserId,
                newPassword,
                errorMessage
        );
    }

    /**
     * Returns a string representation that includes every component
     * verbatim <em>except</em> the {@link #password()} component, which
     * is substituted with the fixed mask {@value #PASSWORD_MASK}.
     *
     * <p>Java records auto-generate a {@code toString()} that prints
     * every component value, which would leak the cleartext password
     * if the record were logged or included in an exception message.
     * This override prevents that leakage while keeping the other
     * components observable for diagnostics. A password of any length
     * always renders as exactly eight asterisks, so the mask itself
     * reveals no length information.
     *
     * <p>This override is a parity application of the AAP &sect;0.7.2
     * "PAN masking" mandate to passwords: the same threat model (a
     * credential value appearing unmasked in a log file, an exception
     * stack trace, or an assertion failure) requires the same
     * mitigation.
     *
     * @return a {@link String} of the form
     *         {@code "CoSgn00Input[transactionName=..., ..., userId=..., password=********, ..., errorMessage=...]"}
     *         that never contains the cleartext password value
     */
    @Override
    public String toString() {
        return "CoSgn00Input["
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
                + "]";
    }

    /**
     * Normalises a {@link String} field per the COBOL {@code MOVE}
     * contract: a {@code null} input yields {@code ""}; an over-long
     * input is truncated from the right to {@code maxLen} characters
     * (the leftmost characters are retained); an in-range input is
     * returned unchanged.
     *
     * @param value  the candidate string (may be {@code null})
     * @param maxLen the BMS-declared {@code PIC X(n)} width
     * @return a non-{@code null} string of length at most
     *         {@code maxLen}
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
