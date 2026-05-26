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
package com.blitzy.carddemo.application.billpay;

// JEP 511 (finalized in Java 25): a single declaration imports every package
// exported by the java.base module (and the modules it reads). This brings
// java.lang.String (the type of all ten record components and the helper's
// substring/length operations) into scope without further import statements,
// matching the canonical pattern established by the sibling CoBil00Output
// and CoTrn02Input record DTOs per AAP §0.6.7 and §0.7.3.
import module java.base;

// AAP §0.7.1 traceability mandate: every translated artifact must cite its
// original COBOL source via the @CobolProgram annotation declared in the
// carddemo-domain module. carddemo-application declares carddemo-domain as a
// direct dependency in its pom.xml, so the annotation is on the classpath
// and resolvable here.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Immutable input DTO for the {@code COBIL00C} bill payment program
 * (CICS transaction {@code CB00}, COBOL source
 * {@code app/cbl/COBIL00C.cbl}).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COBIL00.bms} (mapset {@code COBIL00},
 *       map {@code COBIL0A}, size {@code 24x80},
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}, {@code LANG=COBOL},
 *       {@code MODE=INOUT}, {@code STORAGE=AUTO}, {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COBIL00.CPY},
 *       input group {@code 01 COBIL0AI} (lines 17-78).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.1.2 (COBOL {@code EXEC CICS SEND/RECEIVE MAP} translates
 * to method parameters and return values on the corresponding Java
 * application class) and &sect;0.4.1 (BMS maps become entry-contract DTO
 * records placed alongside the using application class), this record is the
 * Java analog of the input view of the BMS symbolic structure
 * {@code COBIL0AI}: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to {@link CoBil00C}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; this is a plain
 * Java carrier built around finalized Java 25 language features only
 * (records, JEP 511 module imports, JEP 513 flexible constructor bodies).
 *
 * <h2>Field selection &mdash; ten {@code -I} leaves</h2>
 * <p>The BMS symbolic copybook {@code COBIL0AI} declares, for each on-screen
 * field, three bytes ({@code -L} length, {@code -F} flag, redefined as
 * {@code -A} attribute) plus a payload {@code -I} byte string. This DTO
 * models the ten {@code -I} value leaves (the payload data fields); the
 * {@code -L}, {@code -F}, and {@code -A} bytes are 3270-protocol artifacts
 * handled by the BMS adapter in {@code carddemo-app} and are never inspected
 * by the application logic, so they are not surfaced here, in keeping with
 * the AAP &sect;0.7.1 minimal-change principle.
 *
 * <h2>Operator-editable fields</h2>
 * <p>The two fields the user actually populates are:
 * <ul>
 *   <li>{@link #accountId()} (ACTIDINI, 11 chars, declared numeric on the
 *       BMS map but received as space-padded text) &mdash; the account to
 *       be billed.</li>
 *   <li>{@link #confirmation()} (CONFIRMI, 1 char) &mdash; {@code Y}/{@code y}
 *       to commit payment, {@code N}/{@code n} to cancel, blank to display
 *       only.</li>
 * </ul>
 * <p>The remaining eight fields are header / echo content carried by the
 * 3270 protocol on every RECEIVE MAP; the application class {@link CoBil00C}
 * overwrites them in the {@link CoBil00Output output} via the
 * {@code POPULATE-HEADER-INFO} paragraph translation. They are modelled
 * here only so the DTO is byte-symmetric with the COBOL symbolic input
 * structure (AAP &sect;0.4.1 field-for-field translation mandate).
 *
 * <h2>AID-key dispatch &mdash; separate parameter</h2>
 * <p>Per the canonical sibling pattern established by
 * {@code CoTrn02C} / {@code CoTrn02Input}, the AID key (ENTER, PF3, PF4,
 * etc.) is <strong>not</strong> a component of this DTO. The 3270 attention
 * identifier is decoded by the BMS adapter and passed to
 * {@link CoBil00C#execute} as a separate parameter, drawing from the central
 * {@code com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey} sealed
 * hierarchy (AAP &sect;0.6.10). This avoids duplicating the AID-key
 * taxonomy across every BMS DTO and matches AAP &sect;0.6.7's "sealed types
 * for every 88-level taxonomy partitioning a value space" mandate.
 *
 * <h2>Component inventory</h2>
 * <table border="1">
 * <caption>BMS-to-record component mapping</caption>
 *   <thead>
 *     <tr><th>BMS field</th><th>BMS attrs / position</th><th>Record component</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code TRNNAMEI}</td><td>4 chars, (1,7)</td>
 *         <td>{@link #transactionName()}</td></tr>
 *     <tr><td>{@code TITLE01I}</td><td>40 chars, (1,21)</td>
 *         <td>{@link #title01()}</td></tr>
 *     <tr><td>{@code CURDATEI}</td><td>8 chars (MM/DD/YY), (1,71)</td>
 *         <td>{@link #currentDate()}</td></tr>
 *     <tr><td>{@code PGMNAMEI}</td><td>8 chars, (2,7)</td>
 *         <td>{@link #programName()}</td></tr>
 *     <tr><td>{@code TITLE02I}</td><td>40 chars, (2,21)</td>
 *         <td>{@link #title02()}</td></tr>
 *     <tr><td>{@code CURTIMEI}</td><td>8 chars (HH:MM:SS), (2,71)</td>
 *         <td>{@link #currentTime()}</td></tr>
 *     <tr><td>{@code ACTIDINI}</td><td>11 chars, UNPROT, (6,21)</td>
 *         <td>{@link #accountId()} &mdash; <em>operator input</em></td></tr>
 *     <tr><td>{@code CURBALI}</td><td>14 chars (echo), (11,32)</td>
 *         <td>{@link #currentBalance()}</td></tr>
 *     <tr><td>{@code CONFIRMI}</td><td>1 char, UNPROT, (15,60)</td>
 *         <td>{@link #confirmation()} &mdash; <em>operator input</em></td></tr>
 *     <tr><td>{@code ERRMSGI}</td><td>78 chars (echo), (23,1)</td>
 *         <td>{@link #errorMessage()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Null and over-length tolerance</h2>
 * <p>The compact canonical constructor is <em>lenient</em>: every
 * {@code null} {@link String} is normalised to the empty string {@code ""},
 * and every value longer than the BMS-declared {@code PIC X(n)} width is
 * truncated from the right (COBOL {@code MOVE} semantics for an
 * over-sized source: {@code MOVE LONG-STRING TO SHORT-FIELD} truncates by
 * keeping the leftmost characters). This mirrors the COBOL RECEIVE-MAP
 * behaviour where unfilled BMS {@code PIC X(n)} fields are SPACES rather
 * than undefined, and the BMS layer transparently truncates over-long
 * values. The constructor does <strong>not</strong> validate the
 * <em>content</em> of {@link #accountId()} (e.g., numeric format) nor
 * {@link #confirmation()} (e.g., Y/N tri-state) &mdash; those are business
 * validations performed by {@link CoBil00C#execute} and reported back
 * through {@link CoBil00Output#errorMessage()}.
 *
 * <h2>Trailing-space preservation</h2>
 * <p>The normaliser does <strong>not</strong> trim trailing spaces. BMS
 * fixed-width fields arrive padded with {@code SPACE} ({@code 0x20}) to the
 * declared width; preserving that padding is essential to round-trip byte
 * fidelity per AAP &sect;0.7.1 ("All record layouts, field orderings,
 * padding, sign representations, and decimal scales" preserved). When the
 * application logic needs the meaningful content of a field, it calls
 * {@link String#trim()} or {@link String#strip()} at the use site &mdash; the
 * DTO itself is purely a structural carrier.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final} and
 * accessors are auto-generated; there are no setters and no mutable internal
 * state. The record is therefore safe to share across threads (including
 * the virtual-thread workers mandated by AAP &sect;0.6.6) without
 * synchronisation.
 *
 * <h2>Non-goals (faithful to AAP &sect;0.7.4 "explicitly forbidden")</h2>
 * <ul>
 *   <li>No Spring or Jakarta annotations &mdash; this is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in
 *       the compact constructor (see AAP &sect;0.6.3 / JEP 513).</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} &mdash; date
 *       and time components are pre-formatted BMS display strings.</li>
 *   <li>No {@code double} or {@code float} &mdash; the
 *       {@link #currentBalance()} field is a pre-formatted display
 *       {@link String}. Underlying arithmetic is performed by
 *       {@link CoBil00C} via {@link java.math.BigDecimal} per the
 *       {@code com.blitzy.carddemo.domain.util.Decimals} facade (AAP
 *       &sect;0.6.1).</li>
 *   <li>No nested {@code AidKey} type &mdash; the AID key is a separate
 *       parameter to {@link CoBil00C#execute} drawing from the central
 *       {@code CcWorkAreas.AidKey} sealed hierarchy.</li>
 *   <li>No preview Java features &mdash; only finalized Java 25 features
 *       (records, JEP 511 module import, JEP 513 flexible constructor
 *       bodies).</li>
 * </ul>
 *
 * @param transactionName  TRNNAMEI &mdash; 4-char transaction code echo
 *                         (typically "CB00"); {@code null} normalised to
 *                         {@code ""}; longer values truncated to 4
 * @param title01          TITLE01I &mdash; 40-char line-1 title bar echo;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 40
 * @param currentDate      CURDATEI &mdash; 8-char date "MM/DD/YY" echo;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 8
 * @param programName      PGMNAMEI &mdash; 8-char program-id echo;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 8
 * @param title02          TITLE02I &mdash; 40-char line-2 title bar echo;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 40
 * @param currentTime      CURTIMEI &mdash; 8-char time "HH:MM:SS" echo;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 8
 * @param accountId        ACTIDINI &mdash; 11-char account ID input
 *                         (operator-typed); {@code null} normalised to
 *                         {@code ""}; longer values truncated to 11
 * @param currentBalance   CURBALI &mdash; 14-char balance display echo
 *                         (PIC +9999999999.99 format); {@code null}
 *                         normalised to {@code ""}; longer values truncated
 *                         to 14
 * @param confirmation     CONFIRMI &mdash; 1-char Y/N confirmation input
 *                         (operator-typed); {@code null} normalised to
 *                         {@code ""}; longer values truncated to 1
 * @param errorMessage     ERRMSGI &mdash; 78-char error message echo;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 78
 *
 * @see CoBil00Output
 * @see CoBil00C
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COBIL00",
        sourcePath = "app/bms/COBIL00.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook 01 COBIL0AI in "
                + "app/cpy-bms/COBIL00.CPY (lines 17-78). Driven by online program "
                + "app/cbl/COBIL00C.cbl (transaction CB00)."
)
public record CoBil00Input(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String accountId,
        String currentBalance,
        String confirmation,
        String errorMessage
) {

    /** Length of {@link #transactionName()} per BMS map {@code TRNNAMEI} &mdash; 4 chars. */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /** Length of {@link #title01()} per BMS map {@code TITLE01I} &mdash; 40 chars. */
    public static final int TITLE_01_LENGTH = 40;

    /** Length of {@link #currentDate()} per BMS map {@code CURDATEI} &mdash; 8 chars (MM/DD/YY). */
    public static final int CURRENT_DATE_LENGTH = 8;

    /** Length of {@link #programName()} per BMS map {@code PGMNAMEI} &mdash; 8 chars. */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /** Length of {@link #title02()} per BMS map {@code TITLE02I} &mdash; 40 chars. */
    public static final int TITLE_02_LENGTH = 40;

    /** Length of {@link #currentTime()} per BMS map {@code CURTIMEI} &mdash; 8 chars (HH:MM:SS). */
    public static final int CURRENT_TIME_LENGTH = 8;

    /** Length of {@link #accountId()} per BMS map {@code ACTIDINI} &mdash; 11 chars (PIC 9(11) display). */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /** Length of {@link #currentBalance()} per BMS map {@code CURBALI} &mdash; 14 chars (PIC +9999999999.99 display). */
    public static final int CURRENT_BALANCE_LENGTH = 14;

    /** Length of {@link #confirmation()} per BMS map {@code CONFIRMI} &mdash; 1 char (Y, N, or blank). */
    public static final int CONFIRMATION_LENGTH = 1;

    /** Length of {@link #errorMessage()} per BMS map {@code ERRMSGI} &mdash; 78 chars. */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor Bodies,
     * finalized in Java 25).
     *
     * <p>Normalisation rules:
     * <ul>
     *   <li>Every {@link String} component: {@code null} is rewritten to
     *       {@code ""}; values longer than the BMS-defined max length are
     *       truncated from the right (COBOL {@code MOVE} semantics for an
     *       over-sized source &mdash; the leftmost characters are
     *       retained).</li>
     * </ul>
     *
     * <p>Normalisation runs in the body of the compact constructor &mdash;
     * that is, before the implicit canonical field-assignment &mdash; which
     * is the idiomatic JEP 513 location for COBOL-style "default to SPACES
     * then truncate to declared width" cleansing per AAP &sect;0.6.3.
     *
     * <p>The constructor does <strong>not</strong> validate that
     * {@link #accountId()} is numeric or that {@link #confirmation()} is
     * {@code Y}/{@code N}/blank &mdash; those are business validations
     * performed by {@link CoBil00C#execute} and reported back as error
     * messages via {@link CoBil00Output#errorMessage()}.
     */
    public CoBil00Input {
        transactionName = normalize(transactionName, TRANSACTION_NAME_LENGTH);
        title01         = normalize(title01,         TITLE_01_LENGTH);
        currentDate     = normalize(currentDate,     CURRENT_DATE_LENGTH);
        programName     = normalize(programName,     PROGRAM_NAME_LENGTH);
        title02         = normalize(title02,         TITLE_02_LENGTH);
        currentTime     = normalize(currentTime,     CURRENT_TIME_LENGTH);
        accountId       = normalize(accountId,       ACCOUNT_ID_LENGTH);
        currentBalance  = normalize(currentBalance,  CURRENT_BALANCE_LENGTH);
        confirmation    = normalize(confirmation,    CONFIRMATION_LENGTH);
        errorMessage    = normalize(errorMessage,    ERROR_MESSAGE_LENGTH);
    }

    /**
     * Returns a fully-blank input record: every {@link String} component is
     * the empty {@link String} {@code ""}. This is the Java equivalent of
     * the COBOL idiom {@code MOVE LOW-VALUES TO COBIL0AI} performed by
     * {@code COBIL00C} when there is no prior screen state &mdash; for
     * example, on the first entry from {@code COSGN00C} (signon) or after a
     * CLEAR-CURRENT-SCREEN paragraph.
     *
     * <p>The returned record satisfies every invariant of the compact
     * canonical constructor and carries no payload; consumers may safely
     * treat it as the canonical starting state of an input cycle.
     *
     * @return a fresh empty {@code CoBil00Input} (never {@code null})
     */
    public static CoBil00Input empty() {
        return new CoBil00Input("", "", "", "", "", "", "", "", "", "");
    }

    /**
     * Returns a copy of this input with {@link #accountId()} replaced by the
     * given value. All other components are preserved.
     *
     * <p>This helper mirrors the COBOL {@code MAIN-PARA} branch in
     * {@code COBIL00C} that performs
     * {@code MOVE CDEMO-CB00-TRN-SELECTED TO ACTIDINI OF COBIL0AI} when a
     * calling program (typically the bill-payment menu) has pre-populated
     * the target account before transferring control via {@code XCTL}.
     *
     * <p>The new account ID is subjected to the same normalisation as the
     * canonical constructor: {@code null} becomes {@code ""}; over-long
     * values are truncated from the right to
     * {@value #ACCOUNT_ID_LENGTH} characters.
     *
     * @param newAccountId the replacement account ID (may be {@code null}
     *                     or any length; normalised by the canonical
     *                     constructor)
     * @return a new {@code CoBil00Input} with {@link #accountId()} replaced
     *         (never {@code null})
     */
    public CoBil00Input withAccountId(String newAccountId) {
        return new CoBil00Input(
                transactionName,
                title01,
                currentDate,
                programName,
                title02,
                currentTime,
                newAccountId,
                currentBalance,
                confirmation,
                errorMessage
        );
    }

    /**
     * Returns a copy of this input with {@link #confirmation()} replaced by
     * the given value. All other components are preserved.
     *
     * <p>This helper is primarily a convenience for unit tests that need
     * to exercise the Y / N / blank confirmation branches of
     * {@link CoBil00C} without re-constructing the full ten-component
     * record by hand.
     *
     * <p>The new confirmation value is subjected to the same normalisation
     * as the canonical constructor: {@code null} becomes {@code ""};
     * over-long values are truncated to {@value #CONFIRMATION_LENGTH}
     * character.
     *
     * @param newConfirmation the replacement confirmation indicator (may be
     *                        {@code null} or any length; normalised by the
     *                        canonical constructor)
     * @return a new {@code CoBil00Input} with {@link #confirmation()}
     *         replaced (never {@code null})
     */
    public CoBil00Input withConfirmation(String newConfirmation) {
        return new CoBil00Input(
                transactionName,
                title01,
                currentDate,
                programName,
                title02,
                currentTime,
                accountId,
                currentBalance,
                newConfirmation,
                errorMessage
        );
    }

    /**
     * Normalises a {@link String} field per the COBOL {@code MOVE} contract:
     * a {@code null} input yields {@code ""}; an over-long input is
     * truncated from the right to {@code maxLen} characters (the leftmost
     * characters are retained); an in-range input is returned unchanged.
     *
     * <p>This helper is the single point of control for null-and-truncation
     * semantics so that the compact canonical constructor and the
     * with-style copy helpers behave identically.
     *
     * <p>The helper deliberately does <strong>not</strong> trim trailing
     * spaces: BMS fixed-width fields arrive padded with {@code SPACE}
     * ({@code 0x20}) to the declared width, and preserving that padding is
     * essential to round-trip byte fidelity per AAP &sect;0.7.1.
     *
     * @param value  the candidate string (may be {@code null})
     * @param maxLen the BMS-declared {@code PIC X(n)} width
     * @return a non-{@code null} string of length at most {@code maxLen}
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
