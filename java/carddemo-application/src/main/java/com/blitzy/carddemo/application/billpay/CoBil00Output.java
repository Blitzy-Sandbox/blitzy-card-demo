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
// java.lang.String (the type of the ten String record components and the
// twelve Builder fields), the java.lang Enum facilities (the supertype of
// the nested FieldColor enumeration), and java.lang.Override (used implicitly
// by record-generated accessors / equals / hashCode / toString) into scope
// without further import statements, per AAP §0.6.7 and §0.7.3.
import module java.base;

/**
 * Immutable output DTO for the {@code COBIL00C} bill payment program
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
 *       output group {@code 01 COBIL0AO REDEFINES COBIL0AI} (lines 79-140).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.1.2 (COBOL {@code EXEC CICS SEND/RECEIVE MAP} translates
 * to method parameters and return values on the corresponding Java
 * application class) and &sect;0.4.1 (BMS maps become entry-contract DTO
 * records placed alongside the using application class), this record is the
 * Java analog of the output view of the BMS symbolic structure
 * {@code COBIL0AO}: it carries the field values written to the terminal by
 * the controller, replacing what the COBOL program does via {@code EXEC CICS
 * SEND MAP}. There is no web framework, no Spring binding, no Jakarta Bean
 * Validation; this is a plain Java carrier built around finalized Java 25
 * language features only (records, JEP 511 module imports, JEP 513 flexible
 * constructor bodies).
 *
 * <h2>Field selection &mdash; ten {@code -O} leaves plus one color override</h2>
 * <p>The BMS symbolic copybook {@code COBIL0AO} declares, for each on-screen
 * field, six bytes plus a payload &mdash; one byte each for color
 * ({@code -C}), PS ({@code -P}), highlight ({@code -H}), validation
 * ({@code -V}), and length ({@code -L}, in the input layout) attribute bytes
 * plus the {@code -O} value. This DTO models the ten {@code -O} value leaves
 * (the payload data fields) and the dynamic ERRMSGC color attribute byte; the
 * other {@code -C}, {@code -P}, {@code -H}, {@code -V} attribute bytes default
 * to whatever the BMS map definition specifies in
 * {@code app/bms/COBIL00.bms} and are never modified at runtime by
 * {@code COBIL00C} &mdash; so they are not surfaced here, in keeping with the
 * AAP &sect;0.7.1 minimal-change principle.
 *
 * <h2>Dynamic ERRMSGC color override</h2>
 * <p>The BMS map declares {@code ERRMSG ATTRB=(ASKIP,BRT,FSET) COLOR=RED}, so
 * any error message is rendered red by default. {@code COBIL00C} overrides
 * {@code ERRMSGC} to {@code DFHGREEN} on the {@code WRITE TRANSACT-FILE}
 * success path (the line {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} in
 * {@code app/cbl/COBIL00C.cbl}). The {@link #errMsgColor()} component carries
 * the active runtime value; the sentinel {@link FieldColor#DEFAULT} means
 * "no override &mdash; honour the BMS compile-time {@code COLOR=} setting
 * (red)".
 *
 * <h2>3270 cursor-position hints</h2>
 * <p>The {@code -L} length bytes in the BMS <em>input</em> layout
 * ({@code COBIL0AI}, lines 17-78 of the symbolic copybook) carry a 3270
 * protocol convention: a value of {@code -1} on, e.g., {@code ACTIDINL} on
 * the next {@code RECEIVE MAP} tells CICS "place the cursor at the start of
 * this field." {@code COBIL00C} writes to these L fields on the
 * <em>SEND</em> path to influence the next RECEIVE; the Java translation
 * surfaces this via {@link #actIdInLength()} and {@link #confirmLength()}
 * cursor-position hints on this output record. A value of
 * {@link #CURSOR_HINT} ({@code -1}) means "place the cursor here"; any other
 * value is pass-through (zero, the default, means "no hint").
 *
 * <h2>Component inventory</h2>
 * <table border="1" summary="BMS-to-record component mapping">
 *   <thead>
 *     <tr><th>BMS field</th><th>BMS attrs / position</th><th>Record component</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code TRNNAMEO}</td><td>BLUE FSET ASKIP, 4 chars, (1,7)</td>
 *         <td>{@link #transactionName()}</td></tr>
 *     <tr><td>{@code TITLE01O}</td><td>YELLOW FSET ASKIP, 40 chars, (1,21)</td>
 *         <td>{@link #title01()}</td></tr>
 *     <tr><td>{@code CURDATEO}</td><td>BLUE FSET ASKIP, 8 chars (MM/DD/YY), (1,71)</td>
 *         <td>{@link #currentDate()}</td></tr>
 *     <tr><td>{@code PGMNAMEO}</td><td>BLUE FSET ASKIP, 8 chars, (2,7)</td>
 *         <td>{@link #programName()}</td></tr>
 *     <tr><td>{@code TITLE02O}</td><td>YELLOW FSET ASKIP, 40 chars, (2,21)</td>
 *         <td>{@link #title02()}</td></tr>
 *     <tr><td>{@code CURTIMEO}</td><td>BLUE FSET ASKIP, 8 chars (HH:MM:SS), (2,71)</td>
 *         <td>{@link #currentTime()}</td></tr>
 *     <tr><td>{@code ACTIDINO}</td><td>GREEN UNDERLINE FSET IC UNPROT, 11 chars, (6,21)</td>
 *         <td>{@link #accountIdDisplay()}</td></tr>
 *     <tr><td>{@code CURBALO}</td><td>BLUE FSET ASKIP, 14 chars (-99999999.99 display), (11,32)</td>
 *         <td>{@link #currentBalance()}</td></tr>
 *     <tr><td>{@code CONFIRMO}</td><td>GREEN UNDERLINE FSET UNPROT, 1 char (Y/N), (15,60)</td>
 *         <td>{@link #confirmDisplay()}</td></tr>
 *     <tr><td>{@code ERRMSGO}</td><td>RED BRT FSET ASKIP, 78 chars, (23,1)</td>
 *         <td>{@link #errorMessage()}</td></tr>
 *     <tr><td>{@code ERRMSGC}</td><td>color attribute byte (dynamic)</td>
 *         <td>{@link #errMsgColor()}</td></tr>
 *     <tr><td>{@code ACTIDINL}</td><td>L-field cursor hint (3270 protocol)</td>
 *         <td>{@link #actIdInLength()}</td></tr>
 *     <tr><td>{@code CONFIRML}</td><td>L-field cursor hint (3270 protocol)</td>
 *         <td>{@link #confirmLength()}</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Null and over-length tolerance</h2>
 * <p>The compact canonical constructor is <em>lenient</em>: every
 * {@code null} {@link String} is normalised to the empty string {@code ""},
 * and every value longer than the BMS-declared {@code PIC X(n)} width is
 * truncated from the right (COBOL {@code MOVE} semantics for an
 * over-sized source). The {@link FieldColor} parameter is also lenient:
 * {@code null} is normalised to {@link FieldColor#DEFAULT}. Right-padding
 * each field to its declared width (with spaces, {@code 0x20}) is the BMS
 * adapter's responsibility; this DTO carries only the meaningful prefix.
 *
 * <h2>Construction &mdash; prefer the {@link #builder()}</h2>
 * <p>With thirteen parameters, invoking the canonical constructor directly
 * is unwieldy and error-prone. The {@link #builder()} factory is the
 * recommended entry point: it lets callers set only the fields they care
 * about and uses sensible defaults for the rest. Static factory
 * {@link #empty()} returns a fully-blank record &mdash; the Java equivalent
 * of the COBOL idiom {@code MOVE LOW-VALUES TO COBIL0AO} performed by
 * {@code COBIL00C} on every entry.
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all components are {@code final} and
 * accessors are auto-generated; there are no setters and no mutable internal
 * state. The record is therefore safe to share across threads (including the
 * virtual-thread workers mandated by AAP &sect;0.6.6) without
 * synchronisation. The {@link Builder} is the only mutable type, and it is
 * <strong>not</strong> thread-safe &mdash; one builder per
 * application-class invocation is the intended usage.
 *
 * <h2>Non-goals (faithful to AAP &sect;0.7.4 "explicitly forbidden")</h2>
 * <ul>
 *   <li>No Spring or Jakarta annotations &mdash; this is plain Java.</li>
 *   <li>No Lombok {@code @Builder} &mdash; the hand-written
 *       {@link Builder} is the only builder.</li>
 *   <li>No {@code java.util.Date} / {@code Calendar} &mdash; date and time
 *       components are pre-formatted display {@link String}s.</li>
 *   <li>No {@code double} or {@code float} &mdash; the monetary
 *       {@link #currentBalance()} is a pre-formatted display {@link String}.
 *       The underlying arithmetic uses {@link java.math.BigDecimal} via the
 *       {@code com.blitzy.carddemo.domain.util.Decimals} facade per AAP
 *       &sect;0.6.1.</li>
 *   <li>No preview Java features &mdash; only finalized Java 25 features.</li>
 * </ul>
 *
 * @param transactionName  TRNNAMEO &mdash; 4-char transaction code header;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 4
 * @param title01          TITLE01O &mdash; 40-char line-1 title bar;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 40
 * @param currentDate      CURDATEO &mdash; 8-char date "MM/DD/YY";
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 8
 * @param programName      PGMNAMEO &mdash; 8-char program-id header;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 8
 * @param title02          TITLE02O &mdash; 40-char line-2 title bar;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 40
 * @param currentTime      CURTIMEO &mdash; 8-char time "HH:MM:SS";
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 8
 * @param accountIdDisplay ACTIDINO &mdash; 11-char account ID echo;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 11
 * @param currentBalance   CURBALO &mdash; 14-char balance display
 *                         (PIC +9999999999.99); {@code null} normalised to
 *                         {@code ""}; longer values truncated to 14
 * @param confirmDisplay   CONFIRMO &mdash; 1-char (Y/N) confirmation echo;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 1
 * @param errorMessage     ERRMSGO &mdash; 78-char error or success message;
 *                         {@code null} normalised to {@code ""}; longer
 *                         values truncated to 78
 * @param errMsgColor      ERRMSGC &mdash; color override for ERRMSGO;
 *                         {@code null} normalised to {@link FieldColor#DEFAULT}
 * @param actIdInLength    ACTIDINL &mdash; cursor hint for the account ID
 *                         field ({@link #CURSOR_HINT} = cursor here, 0 =
 *                         no hint)
 * @param confirmLength    CONFIRML &mdash; cursor hint for the confirmation
 *                         field ({@link #CURSOR_HINT} = cursor here, 0 =
 *                         no hint)
 *
 * @see CoBil00Input
 * @see CoBil00C
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS/TS BMS reference</a>
 * @since 1.0.0
 */
public record CoBil00Output(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String accountIdDisplay,
        String currentBalance,
        String confirmDisplay,
        String errorMessage,
        FieldColor errMsgColor,
        int actIdInLength,
        int confirmLength
) {

    /** Length of {@link #transactionName()} per BMS map {@code TRNNAMEO} &mdash; 4 chars. */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /** Length of {@link #title01()} per BMS map {@code TITLE01O} &mdash; 40 chars. */
    public static final int TITLE_01_LENGTH = 40;

    /** Length of {@link #currentDate()} per BMS map {@code CURDATEO} &mdash; 8 chars (MM/DD/YY). */
    public static final int CURRENT_DATE_LENGTH = 8;

    /** Length of {@link #programName()} per BMS map {@code PGMNAMEO} &mdash; 8 chars. */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /** Length of {@link #title02()} per BMS map {@code TITLE02O} &mdash; 40 chars. */
    public static final int TITLE_02_LENGTH = 40;

    /** Length of {@link #currentTime()} per BMS map {@code CURTIMEO} &mdash; 8 chars (HH:MM:SS). */
    public static final int CURRENT_TIME_LENGTH = 8;

    /** Length of {@link #accountIdDisplay()} per BMS map {@code ACTIDINO} &mdash; 11 chars. */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /** Length of {@link #currentBalance()} per BMS map {@code CURBALO} &mdash; 14 chars (PIC +9999999999.99 display). */
    public static final int CURRENT_BALANCE_LENGTH = 14;

    /** Length of {@link #confirmDisplay()} per BMS map {@code CONFIRMO} &mdash; 1 char. */
    public static final int CONFIRMATION_LENGTH = 1;

    /** Length of {@link #errorMessage()} per BMS map {@code ERRMSGO} &mdash; 78 chars. */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * Sentinel value for 3270 cursor-position hints, mirroring the CICS BMS
     * L-field convention {@code MOVE -1 TO field-L}: a value of {@code -1} on
     * a length byte instructs CICS to place the cursor at the start of that
     * field on the next {@code RECEIVE MAP}. Use this constant for the
     * {@link #actIdInLength()} or {@link #confirmLength()} record component
     * to request cursor placement.
     */
    public static final int CURSOR_HINT = -1;

    /**
     * 3270 field-color override, mirroring the CICS BMS attribute-byte values
     * from the copybook {@code DFHBMSCA}. The {@code COBIL00C} program only
     * ever overrides the {@code ERRMSGC} byte (to {@code DFHGREEN} on
     * success); all other field colors stay at the BMS map definition's
     * compile-time {@code COLOR=} default.
     *
     * <p>The enum is intentionally narrower than the full DFHBMSCA palette
     * &mdash; only the colors actually relevant to {@code COBIL00C} are
     * exposed. Adding new colors is safe; removing a color requires
     * verifying that no application class depends on it.
     *
     * <p>Why an enum rather than a {@link String}? An enum makes the closed
     * set of legal color values compiler-enforced; the BMS adapter
     * (responsible for translating this record into raw 3270 traffic) can
     * exhaustively pattern-match on the values to derive the attribute byte
     * &mdash; no string parsing, no spelling errors, no silent fallthrough.
     */
    public enum FieldColor {
        /** Use the color defined in the BMS map (DFHBMSCA {@code DFHDFT} attribute default). */
        DEFAULT,
        /** {@code DFHGREEN} &mdash; used for the success message on {@code WRITE TRANSACT-FILE NORMAL}. */
        GREEN,
        /** {@code DFHRED} &mdash; reserved for future use; not currently emitted by COBIL00C. */
        RED,
        /** {@code DFHYELLOW} &mdash; reserved for future use; not currently emitted by COBIL00C. */
        YELLOW,
        /** {@code DFHBLUE} &mdash; reserved for future use; not currently emitted by COBIL00C. */
        BLUE,
        /** {@code DFHTURQ} (turquoise) &mdash; reserved for future use; not currently emitted by COBIL00C. */
        TURQUOISE,
        /** {@code DFHPINK} &mdash; reserved for future use; not currently emitted by COBIL00C. */
        PINK,
        /** {@code DFHNEUTR} (neutral white) &mdash; reserved for future use; not currently emitted by COBIL00C. */
        NEUTRAL
    }

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor Bodies,
     * finalized in Java 25).
     *
     * <p>Normalisation rules:
     * <ul>
     *   <li>Every {@link String} component: {@code null} is rewritten to
     *       {@code ""}; values longer than the BMS-defined max length are
     *       truncated from the right (COBOL {@code MOVE} semantics for an
     *       over-sized source).</li>
     *   <li>{@link #errMsgColor}: {@code null} is rewritten to
     *       {@link FieldColor#DEFAULT}.</li>
     *   <li>{@link #actIdInLength} and {@link #confirmLength}: any
     *       {@code int} value is accepted. The BMS adapter treats
     *       {@link #CURSOR_HINT} ({@code -1}) specially; all other values
     *       are pass-through. A value of {@code 0} represents "no cursor
     *       hint" (the default).</li>
     * </ul>
     *
     * <p>Validation runs in the body of the compact constructor &mdash; that
     * is, before the implicit canonical field-assignment &mdash; which is the
     * idiomatic JEP 513 location for COBOL-style "validate before bind"
     * semantics per AAP &sect;0.6.3.
     */
    public CoBil00Output {
        transactionName  = normalize(transactionName,  TRANSACTION_NAME_LENGTH);
        title01          = normalize(title01,          TITLE_01_LENGTH);
        currentDate      = normalize(currentDate,      CURRENT_DATE_LENGTH);
        programName      = normalize(programName,      PROGRAM_NAME_LENGTH);
        title02          = normalize(title02,          TITLE_02_LENGTH);
        currentTime      = normalize(currentTime,      CURRENT_TIME_LENGTH);
        accountIdDisplay = normalize(accountIdDisplay, ACCOUNT_ID_LENGTH);
        currentBalance   = normalize(currentBalance,   CURRENT_BALANCE_LENGTH);
        confirmDisplay   = normalize(confirmDisplay,   CONFIRMATION_LENGTH);
        errorMessage     = normalize(errorMessage,     ERROR_MESSAGE_LENGTH);
        if (errMsgColor == null) {
            errMsgColor = FieldColor.DEFAULT;
        }
    }

    /**
     * Returns a fully-blank output record: every {@link String} component is
     * {@code ""}, the {@link #errMsgColor()} is {@link FieldColor#DEFAULT},
     * and both cursor hints are {@code 0}. This is the Java equivalent of
     * the COBOL idiom {@code MOVE LOW-VALUES TO COBIL0AO} performed by
     * {@code COBIL00C} on every entry.
     *
     * <p>The returned record satisfies every invariant of the compact
     * constructor and carries no payload &mdash; consumers may safely treat
     * it as the canonical starting state of an output cycle and decorate it
     * via {@link #builder()} into a populated send-map payload.
     *
     * @return a fresh empty {@code CoBil00Output} (never {@code null})
     */
    public static CoBil00Output empty() {
        return new CoBil00Output(
                "", "", "", "", "", "", "", "", "", "",
                FieldColor.DEFAULT, 0, 0
        );
    }

    /**
     * Returns a fresh {@link Builder} for fluent construction of a
     * {@code CoBil00Output}.
     *
     * <p>Builder defaults: every {@link String} field is {@code ""};
     * {@link Builder#errMsgColor(FieldColor) errMsgColor} is
     * {@link FieldColor#DEFAULT}; both cursor-hint fields are {@code 0}
     * (no hint).
     *
     * @return a new builder (never {@code null})
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Normalises a {@link String} field per the COBOL {@code MOVE} contract:
     * a {@code null} input yields {@code ""}; an over-long input is
     * truncated from the right to {@code maxLen} characters; an in-range
     * input is returned unchanged.
     *
     * <p>This helper is the single point of control for null-and-truncation
     * semantics so that the compact canonical constructor and any future
     * with-style copy helpers behave identically.
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

    /**
     * Fluent builder for {@link CoBil00Output}. Reusable: call
     * {@link #build()} multiple times if needed (each call returns a new
     * immutable record reflecting the builder's current state).
     *
     * <p>Every setter returns {@code this} for chaining. Defaults: every
     * {@link String} field is {@code ""}; {@link #errMsgColor} is
     * {@link FieldColor#DEFAULT}; both cursor-hint fields are {@code 0}.
     *
     * <p><strong>Not thread-safe:</strong> use one builder per
     * application-class invocation. Sharing a builder across threads has
     * undefined behavior; the resulting immutable {@link CoBil00Output} is
     * itself thread-safe and may be shared freely.
     */
    public static final class Builder {

        private String transactionName  = "";
        private String title01          = "";
        private String currentDate      = "";
        private String programName      = "";
        private String title02          = "";
        private String currentTime      = "";
        private String accountIdDisplay = "";
        private String currentBalance   = "";
        private String confirmDisplay   = "";
        private String errorMessage     = "";
        private FieldColor errMsgColor  = FieldColor.DEFAULT;
        private int actIdInLength       = 0;
        private int confirmLength       = 0;

        /** Package-private to force construction via {@link CoBil00Output#builder()}. */
        private Builder() {
            // no-op
        }

        /**
         * Sets the {@link CoBil00Output#transactionName()} field.
         *
         * @param value the transaction code (typically {@code "CB00"});
         *              {@code null} is normalised to {@code ""}; longer
         *              values are truncated by the canonical constructor
         * @return this builder
         */
        public Builder transactionName(String value) {
            this.transactionName = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#title01()} field.
         *
         * @param value the line-1 title bar; {@code null} normalised to
         *              {@code ""}; longer values truncated by the canonical
         *              constructor
         * @return this builder
         */
        public Builder title01(String value) {
            this.title01 = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#currentDate()} field.
         *
         * @param value the date in "MM/DD/YY" format; {@code null} normalised
         *              to {@code ""}; longer values truncated by the
         *              canonical constructor
         * @return this builder
         */
        public Builder currentDate(String value) {
            this.currentDate = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#programName()} field.
         *
         * @param value the program-id (typically {@code "COBIL00C"});
         *              {@code null} normalised to {@code ""}; longer values
         *              truncated by the canonical constructor
         * @return this builder
         */
        public Builder programName(String value) {
            this.programName = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#title02()} field.
         *
         * @param value the line-2 title bar; {@code null} normalised to
         *              {@code ""}; longer values truncated by the canonical
         *              constructor
         * @return this builder
         */
        public Builder title02(String value) {
            this.title02 = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#currentTime()} field.
         *
         * @param value the time in "HH:MM:SS" format; {@code null} normalised
         *              to {@code ""}; longer values truncated by the
         *              canonical constructor
         * @return this builder
         */
        public Builder currentTime(String value) {
            this.currentTime = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#accountIdDisplay()} field
         * (operator-typed account ID echo).
         *
         * @param value the 11-char account ID; {@code null} normalised to
         *              {@code ""}; longer values truncated by the canonical
         *              constructor
         * @return this builder
         */
        public Builder accountIdDisplay(String value) {
            this.accountIdDisplay = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#currentBalance()} field. The caller
         * is responsible for formatting any underlying
         * {@link java.math.BigDecimal} value into the BMS-expected
         * "{@code +9999999999.99}" display form &mdash; this DTO never sees a
         * raw BigDecimal per AAP &sect;0.6.1.
         *
         * @param value the 14-char preformatted balance display;
         *              {@code null} normalised to {@code ""}; longer values
         *              truncated by the canonical constructor
         * @return this builder
         */
        public Builder currentBalance(String value) {
            this.currentBalance = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#confirmDisplay()} field
         * (operator-typed Y/N confirmation echo).
         *
         * @param value the 1-char confirmation indicator ({@code "Y"},
         *              {@code "N"}, or {@code ""}); {@code null} normalised
         *              to {@code ""}; longer values truncated by the
         *              canonical constructor
         * @return this builder
         */
        public Builder confirmDisplay(String value) {
            this.confirmDisplay = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#errorMessage()} field. To clear an
         * existing message, pass {@code ""}; to set a success indicator,
         * combine with {@link #errMsgColor(FieldColor)
         * errMsgColor(FieldColor.GREEN)}.
         *
         * @param value the 78-char message text; {@code null} normalised to
         *              {@code ""}; longer values truncated by the canonical
         *              constructor
         * @return this builder
         */
        public Builder errorMessage(String value) {
            this.errorMessage = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#errMsgColor()} attribute byte
         * override. Pass {@link FieldColor#GREEN} on the
         * {@code WRITE TRANSACT-FILE NORMAL} success path; pass
         * {@link FieldColor#DEFAULT} (or omit) to leave the field at its
         * BMS map default of RED.
         *
         * @param value the color attribute; {@code null} normalised to
         *              {@link FieldColor#DEFAULT} by the canonical
         *              constructor
         * @return this builder
         */
        public Builder errMsgColor(FieldColor value) {
            this.errMsgColor = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#actIdInLength()} cursor-position
         * hint. Pass {@link CoBil00Output#CURSOR_HINT} ({@code -1}) to ask
         * the BMS adapter to place the cursor at the start of the account-ID
         * field on the next {@code RECEIVE MAP}.
         *
         * @param value the cursor hint ({@code -1} = place cursor here,
         *              {@code 0} = no hint, other values = pass-through)
         * @return this builder
         */
        public Builder actIdInLength(int value) {
            this.actIdInLength = value;
            return this;
        }

        /**
         * Sets the {@link CoBil00Output#confirmLength()} cursor-position
         * hint. Pass {@link CoBil00Output#CURSOR_HINT} ({@code -1}) to ask
         * the BMS adapter to place the cursor at the start of the
         * confirmation field on the next {@code RECEIVE MAP}.
         *
         * @param value the cursor hint ({@code -1} = place cursor here,
         *              {@code 0} = no hint, other values = pass-through)
         * @return this builder
         */
        public Builder confirmLength(int value) {
            this.confirmLength = value;
            return this;
        }

        /**
         * Builds an immutable {@link CoBil00Output} from this builder's
         * current state. The compact canonical constructor of
         * {@code CoBil00Output} applies its null-and-truncation
         * normalisation rules to every field; the builder itself does not
         * normalise.
         *
         * <p>This method may be called multiple times; each call returns a
         * new record reflecting the builder's current state. Subsequent
         * setter calls do not affect previously-built instances.
         *
         * @return a freshly-constructed {@code CoBil00Output} (never
         *         {@code null})
         */
        public CoBil00Output build() {
            return new CoBil00Output(
                    transactionName,
                    title01,
                    currentDate,
                    programName,
                    title02,
                    currentTime,
                    accountIdDisplay,
                    currentBalance,
                    confirmDisplay,
                    errorMessage,
                    errMsgColor,
                    actIdInLength,
                    confirmLength
            );
        }
    }
}
