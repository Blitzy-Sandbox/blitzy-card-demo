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

// JEP 511 (finalized in Java 25): a single declaration imports every package
// exported by the java.base module (and the modules it reads). This brings
// java.lang.String into scope without further import statements, matching
// the canonical pattern established by the sibling CoBil00Input,
// CoTrn02Input, and CoUsr00Input record DTOs per AAP §0.6.7 and §0.7.3.
import module java.base;

// AAP §0.7.1 traceability mandate: every translated artifact must cite its
// original COBOL source via the @CobolProgram annotation declared in the
// carddemo-domain module. carddemo-application declares carddemo-domain as a
// direct dependency in its pom.xml, so the annotation is on the classpath
// and resolvable here.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Immutable input DTO for the {@code COMEN01C} main menu online program
 * (CICS transaction {@code CM00}, COBOL source
 * {@code app/cbl/COMEN01C.cbl}).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COMEN01.bms} (mapset
 *       {@code COMEN01}, map {@code COMEN1A}, size {@code 24x80},
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}, {@code LANG=COBOL},
 *       {@code MODE=INOUT}, {@code STORAGE=AUTO}, {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COMEN01.CPY},
 *       input group {@code 01 COMEN1AI} (lines 17-138).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.1.2 (COBOL {@code EXEC CICS SEND/RECEIVE MAP}
 * translates to method parameters and return values on the corresponding
 * Java application class) and &sect;0.4.1 (BMS maps become entry-contract
 * DTO records placed alongside the using application class), this record
 * is the Java analog of the input view of the BMS symbolic structure
 * {@code COMEN1AI}: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to {@code CoMen01C}. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation; this is a
 * plain Java carrier built around finalized Java 25 language features
 * only (records, JEP 511 module imports, JEP 513 flexible constructor
 * bodies).
 *
 * <h2>Field selection &mdash; eighteen {@code -I} leaves</h2>
 * <p>The BMS symbolic copybook {@code COMEN1AI} declares, for each
 * on-screen field, three bytes ({@code -L} length, {@code -F} flag,
 * redefined as {@code -A} attribute) plus a payload {@code -I} byte
 * string. This DTO models the eighteen {@code -I} value leaves (the
 * payload data fields); the {@code -L}, {@code -F}, and {@code -A} bytes
 * are 3270-protocol artifacts handled by the BMS adapter in
 * {@code carddemo-app} and are never inspected by the application logic,
 * so they are not surfaced here, in keeping with the AAP &sect;0.7.1
 * minimal-change principle.
 *
 * <h2>Operator-editable field</h2>
 * <p>The only field the user actually populates is {@link #option()}
 * (OPTIONI, 2 chars, declared numeric on the BMS map with
 * {@code JUSTIFY=(RIGHT,ZERO)}): the two-digit menu selection
 * (e.g. {@code "01"}, {@code "02"} ... {@code "12"}) corresponding to
 * one of the twelve option labels echoed in {@link #option01()} through
 * {@link #option12()}. The COBOL program validates the selection against
 * the static lookup table {@code COMEN02Y} and dispatches via
 * {@code XCTL PROGRAM(...)}.
 *
 * <h2>Header fields and option labels</h2>
 * <p>The fields {@link #transactionName()}, {@link #title01()},
 * {@link #currentDate()}, {@link #programName()}, {@link #title02()},
 * and {@link #currentTime()} are protected header echo content carried
 * by the 3270 protocol on every RECEIVE MAP; the application class
 * overwrites them in the {@link CoMen01Output output} via its header
 * population paragraph. The twelve {@link #option01()} through
 * {@link #option12()} fields are likewise protected option labels echoed
 * back to the COBOL program; they are modeled here only so the DTO is
 * byte-symmetric with the COBOL symbolic input structure (AAP
 * &sect;0.4.1 field-for-field translation mandate).
 *
 * <h2>AID-key dispatch &mdash; separate parameter</h2>
 * <p>Per the canonical sibling pattern established by
 * {@code CoBil00C} / {@code CoBil00Input}, the AID key (ENTER, PF3,
 * etc.) is <strong>not</strong> a component of this DTO. The 3270
 * attention identifier is decoded by the BMS adapter and passed to
 * {@code CoMen01C#execute} as a separate parameter, drawing from the
 * central {@code com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey}
 * sealed hierarchy (AAP &sect;0.6.10). This avoids duplicating the
 * AID-key taxonomy across every BMS DTO and matches AAP &sect;0.6.7's
 * "sealed types for every 88-level taxonomy partitioning a value
 * space" mandate.
 *
 * <h2>Null and over-length tolerance</h2>
 * <p>The compact canonical constructor is <em>lenient</em>: every
 * {@code null} {@link String} is normalised to the empty string
 * {@code ""}, and every value longer than the BMS-declared
 * {@code PIC X(n)} width is truncated from the right (COBOL
 * {@code MOVE} semantics for an over-sized source). This mirrors the
 * COBOL RECEIVE-MAP behaviour where unfilled BMS {@code PIC X(n)}
 * fields are SPACES and the BMS layer transparently truncates over-long
 * values. The constructor does <strong>not</strong> validate the
 * <em>content</em> of {@link #option()} (e.g., numeric format, in-range
 * value) &mdash; those are business validations performed by
 * {@code CoMen01C} and reported back through
 * {@link CoMen01Output#errorMessage()}.
 *
 * <h2>Trailing-space preservation</h2>
 * <p>The normaliser does <strong>not</strong> trim trailing spaces.
 * BMS fixed-width fields arrive padded with {@code SPACE} ({@code 0x20})
 * to the declared width; preserving that padding is essential to
 * round-trip byte fidelity per AAP &sect;0.7.1.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final}
 * and accessors are auto-generated; there are no setters and no mutable
 * internal state. The record is therefore safe to share across threads
 * (including the virtual-thread workers mandated by AAP &sect;0.6.6)
 * without synchronisation.
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
 *         <td>40 chars each, (6,20)..(17,20)</td>
 *         <td>{@link #option01()}..{@link #option12()}</td></tr>
 *     <tr><td>{@code OPTIONI}</td>
 *         <td>2 chars, UNPROT NUM JUSTIFY=(RIGHT,ZERO), (20,41)</td>
 *         <td>{@link #option()} &mdash; <em>operator input</em></td></tr>
 *     <tr><td>{@code ERRMSGI}</td><td>78 chars (echo), (23,1)</td>
 *         <td>{@link #errorMessage()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Non-goals (faithful to AAP &sect;0.7.4 "explicitly forbidden")</h2>
 * <ul>
 *   <li>No Spring or Jakarta annotations &mdash; this is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are
 *       explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written
 *       in the compact constructor (see AAP &sect;0.6.3 / JEP 513).</li>
 *   <li>No {@code java.util.Date} or {@code java.util.Calendar} &mdash;
 *       date and time components are pre-formatted BMS display
 *       strings.</li>
 *   <li>No {@code double} or {@code float}.</li>
 *   <li>No nested {@code AidKey} type &mdash; the AID key is a separate
 *       parameter to {@code CoMen01C#execute} drawing from the central
 *       {@code CcWorkAreas.AidKey} sealed hierarchy.</li>
 *   <li>No preview Java features &mdash; only finalized Java 25
 *       features (records, JEP 511 module import, JEP 513 flexible
 *       constructor bodies).</li>
 * </ul>
 *
 * @param transactionName  TRNNAMEI &mdash; 4-char transaction code echo
 *                         (typically {@code "CM00"})
 * @param title01          TITLE01I &mdash; 40-char line-1 title bar echo
 * @param currentDate      CURDATEI &mdash; 8-char date {@code "mm/dd/yy"} echo
 * @param programName      PGMNAMEI &mdash; 8-char program-id echo
 * @param title02          TITLE02I &mdash; 40-char line-2 title bar echo
 * @param currentTime      CURTIMEI &mdash; 8-char time {@code "hh:mm:ss"} echo
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
 * @param option           OPTIONI &mdash; 2-char menu selection input
 *                         (operator-typed)
 * @param errorMessage     ERRMSGI &mdash; 78-char error message echo
 *
 * @see CoMen01Output
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COMEN01",
        sourcePath = "app/bms/COMEN01.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook 01 COMEN1AI in "
                + "app/cpy-bms/COMEN01.CPY (lines 17-138). Driven by online program "
                + "app/cbl/COMEN01C.cbl (main menu, transaction CM00). The static "
                + "menu-option lookup table is provided by domain record COMEN02Y "
                + "(MainMenuTable)."
)
public record CoMen01Input(
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

    /**
     * Length of each menu option label
     * ({@code OPTN001I}..{@code OPTN012I}) per BMS map &mdash; 40 chars
     * each. All twelve option labels share the same width.
     */
    public static final int OPTION_LABEL_LENGTH = 40;

    /** Length of {@link #option()} per BMS map {@code OPTIONI} &mdash; 2 chars. */
    public static final int OPTION_LENGTH = 2;

    /** Length of {@link #errorMessage()} per BMS map {@code ERRMSGI} &mdash; 78 chars. */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor Bodies,
     * finalized in Java 25).
     *
     * <p>Normalisation rules:
     * <ul>
     *   <li>Every {@link String} component: {@code null} is rewritten to
     *       {@code ""}; values longer than the BMS-defined max length
     *       are truncated from the right (COBOL {@code MOVE} semantics
     *       for an over-sized source &mdash; the leftmost characters
     *       are retained).</li>
     * </ul>
     *
     * <p>Normalisation runs in the body of the compact constructor
     * &mdash; that is, before the implicit canonical field-assignment
     * &mdash; which is the idiomatic JEP 513 location for COBOL-style
     * "default to SPACES then truncate to declared width" cleansing per
     * AAP &sect;0.6.3.
     *
     * <p>The constructor does <strong>not</strong> validate that
     * {@link #option()} is numeric or in the range {@code "01"}-
     * {@code "12"} &mdash; those are business validations performed by
     * {@code CoMen01C} and reported back as error messages via
     * {@link CoMen01Output#errorMessage()}.
     */
    public CoMen01Input {
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
     * Returns a fully-blank input record: every {@link String} component
     * is the empty {@link String} {@code ""}. This is the Java
     * equivalent of the COBOL idiom {@code MOVE LOW-VALUES TO COMEN1AI}
     * performed by {@code COMEN01C} on the first entry from
     * {@code COSGN00C} (signon) or after a CLEAR-CURRENT-SCREEN
     * paragraph.
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
                "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "", "", "",
                "", ""
        );
    }

    /**
     * Returns a copy of this input with {@link #option()} replaced by
     * the given value. All other components are preserved.
     *
     * <p>The new option value is subjected to the same normalisation as
     * the canonical constructor: {@code null} becomes {@code ""};
     * over-long values are truncated from the right to
     * {@value #OPTION_LENGTH} characters.
     *
     * @param newOption the replacement menu selection (may be
     *                  {@code null} or any length; normalised by the
     *                  canonical constructor)
     * @return a new {@code CoMen01Input} with {@link #option()} replaced
     *         (never {@code null})
     */
    public CoMen01Input withOption(String newOption) {
        return new CoMen01Input(
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
     * Normalises a {@link String} field per the COBOL {@code MOVE}
     * contract: a {@code null} input yields {@code ""}; an over-long
     * input is truncated from the right to {@code maxLen} characters
     * (the leftmost characters are retained); an in-range input is
     * returned unchanged.
     *
     * <p>This helper is the single point of control for null-and-
     * truncation semantics so that the compact canonical constructor
     * and the with-style copy helpers behave identically.
     *
     * <p>The helper deliberately does <strong>not</strong> trim
     * trailing spaces: BMS fixed-width fields arrive padded with
     * {@code SPACE} ({@code 0x20}) to the declared width, and
     * preserving that padding is essential to round-trip byte fidelity
     * per AAP &sect;0.7.1.
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
